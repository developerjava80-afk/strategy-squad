package com.strategysquad.agentic.liveassist;

import com.strategysquad.agentic.decision.DecisionCommand;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Live-assist confirmation gate: intercepts every {@link DecisionCommand} before
 * it is applied to position state and routes it through an operator confirmation
 * step in {@code LIVE_ASSIST} mode.
 *
 * <h2>Mode behaviour</h2>
 * <ul>
 *   <li>{@code SIMULATION} / {@code PAPER} — gate is bypassed; every command is
 *       auto-approved. The orchestrator proceeds without operator interaction.</li>
 *   <li>{@code LIVE_ASSIST} — command is written to {@code agentic_pending_commands}
 *       with status {@code SUBMITTED}; the orchestrator transitions to
 *       {@link com.strategysquad.agentic.orchestrator.MarketDayOrchestrator.OrchestratorState#WAIT_OPERATOR_CONFIRM}
 *       and polls {@link #checkStatus(String)} on each tick until the operator
 *       confirms, cancels, or the timeout elapses.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>The in-memory pending store ({@code ConcurrentHashMap}) is thread-safe.
 * {@link #confirm(String)} and {@link #cancel(String)} may be called from the
 * REST handler thread while {@link #checkStatus(String)} is called from the
 * orchestrator tick thread — this is safe.
 *
 * <h2>Persistence</h2>
 * <p>When a {@link ConnectionSupplier} is provided at construction time, every
 * state transition writes an event row to {@code agentic_pending_commands}.
 * This is an append-only audit log; the in-memory map is the authoritative
 * source of truth during a running session. Persistence failures are logged but
 * never propagated to the caller.
 *
 * <h2>Timeout</h2>
 * <p>A pending command that has not been confirmed within {@code timeoutSeconds}
 * (default {@value #DEFAULT_TIMEOUT_SECONDS} seconds) transitions to
 * {@link GateStatus#EXPIRED}. Expired commands are rejected by
 * {@link #confirm(String)}.
 */
public final class LiveAssistConfirmationGate {

    private static final Logger LOG = Logger.getLogger(LiveAssistConfirmationGate.class.getName());

    /** Default operator confirmation timeout: 60 seconds. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 60L;

    // =========================================================================
    // Public API types
    // =========================================================================

    /**
     * Possible outcomes of a gate interaction.
     */
    public enum GateStatus {

        /** Command approved without operator interaction (SIMULATION / PAPER mode). */
        APPROVED,

        /** Command written to the pending store; waiting for operator confirmation. */
        PENDING,

        /** Operator confirmed the pending command within the timeout window. */
        CONFIRMED,

        /** Operator explicitly cancelled the pending command. */
        CANCELLED,

        /**
         * Confirmation window has elapsed without operator action.
         * The command must not be applied; the orchestrator should discard it and
         * re-evaluate in the next scan cycle.
         */
        EXPIRED,

        /** No entry found for the supplied {@code commandId}. */
        NOT_FOUND
    }

    /**
     * Result of a gate operation.
     *
     * @param status     the gate decision
     * @param commandId  the command UUID string this result relates to
     */
    public record GateResult(GateStatus status, String commandId) {}

    // =========================================================================
    // Internal pending entry
    // =========================================================================

    /**
     * Mutable lifecycle holder for a single pending command.
     */
    static final class PendingEntry {
        final DecisionCommand command;
        final Instant submittedAt;
        final Instant expiresAt;
        /**
         * Current status: one of "PENDING", "CONFIRMED", "CANCELLED".
         * May be updated concurrently by REST handler threads; the volatile keyword
         * provides visibility without locking (single-writer per field is sufficient
         * because only one thread transitions from PENDING→CONFIRMED or PENDING→CANCELLED).
         */
        volatile String status = "PENDING";

        PendingEntry(DecisionCommand command, Instant submittedAt, Instant expiresAt) {
            this.command = Objects.requireNonNull(command, "command must not be null");
            this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt must not be null");
            this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }

        boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    // =========================================================================
    // JDBC SQL
    // =========================================================================

    private static final String INSERT_EVENT_SQL =
            "INSERT INTO agentic_pending_commands " +
            "(event_ts, command_id, run_id, mode, underlying, command_type, reason_code, " +
            " explanation, event_type, submitted_ts, expires_at_ts, timeout_seconds) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

    // =========================================================================
    // Fields
    // =========================================================================

    private final DecisionCommand.Mode mode;
    private final long timeoutSeconds;
    private final Supplier<Instant> clock;
    private final ConnectionSupplier connSupplier;   // nullable — no DB persistence when null
    private final String runId;

    /** In-memory pending store: commandId → entry. Thread-safe. */
    private final ConcurrentHashMap<String, PendingEntry> pending = new ConcurrentHashMap<>();

    // =========================================================================
    // Connection supplier
    // =========================================================================

    /**
     * Functional interface for obtaining a JDBC {@link Connection}.
     * Follows the same pattern used by {@code MarketDayOrchestrator} and
     * {@code DecisionAgent} throughout the codebase.
     */
    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection get() throws Exception;
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Full constructor.
     *
     * @param mode           operating mode; determines auto-approve vs pending behaviour
     * @param timeoutSeconds seconds before a pending command expires; must be &gt; 0
     * @param clock          time source — inject a test clock for deterministic tests
     * @param connSupplier   JDBC connection supplier for persistence; {@code null} for
     *                       in-memory-only operation (tests and SIMULATION mode)
     * @param runId          orchestrator run ID for the event log; may be {@code null}
     *                       in which case an empty string is recorded
     */
    public LiveAssistConfirmationGate(
            DecisionCommand.Mode mode,
            long timeoutSeconds,
            Supplier<Instant> clock,
            ConnectionSupplier connSupplier,
            String runId) {

        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be > 0");
        this.timeoutSeconds = timeoutSeconds;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.connSupplier = connSupplier;
        this.runId = runId != null ? runId : "";
    }

    /**
     * Convenience factory for SIMULATION mode (auto-approve, no DB required).
     *
     * @return a gate configured for simulation — no pending commands, no DB writes
     */
    public static LiveAssistConfirmationGate simulation() {
        return new LiveAssistConfirmationGate(
                DecisionCommand.Mode.SIMULATION, DEFAULT_TIMEOUT_SECONDS, Instant::now, null, "");
    }

    /**
     * Convenience factory for LIVE_ASSIST mode.
     *
     * @param timeoutSeconds confirmation timeout; 0 or negative uses the default
     * @param clock          time source (use {@code Instant::now} in production)
     * @param connSupplier   JDBC connection for audit persistence; may be {@code null}
     * @param runId          run ID from the orchestrator
     * @return a gate configured for live-assist
     */
    public static LiveAssistConfirmationGate liveAssist(
            long timeoutSeconds,
            Supplier<Instant> clock,
            ConnectionSupplier connSupplier,
            String runId) {

        long timeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        return new LiveAssistConfirmationGate(
                DecisionCommand.Mode.LIVE_ASSIST, timeout, clock, connSupplier, runId);
    }

    // =========================================================================
    // Public gate API
    // =========================================================================

    /**
     * Submits a {@link DecisionCommand} to the gate.
     *
     * <ul>
     *   <li>If the mode is {@code SIMULATION} or {@code PAPER}: returns
     *       {@link GateStatus#APPROVED} immediately — no pending entry is created.</li>
     *   <li>If the mode is {@code LIVE_ASSIST}: records a pending entry, writes a
     *       {@code SUBMITTED} event to {@code agentic_pending_commands}, and returns
     *       {@link GateStatus#PENDING}.</li>
     * </ul>
     *
     * @param command the command to submit; must not be null
     * @return a {@link GateResult} with status {@code APPROVED} or {@code PENDING}
     */
    public GateResult submit(DecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String commandId = command.commandId().toString();

        if (mode != DecisionCommand.Mode.LIVE_ASSIST) {
            // SIMULATION and PAPER: bypass the gate entirely.
            return new GateResult(GateStatus.APPROVED, commandId);
        }

        Instant now = clock.get();
        Instant expiresAt = now.plusSeconds(timeoutSeconds);
        PendingEntry entry = new PendingEntry(command, now, expiresAt);
        pending.put(commandId, entry);

        LOG.info("[LiveAssistConfirmationGate] SUBMITTED command " + commandId
                + " type=" + command.commandType()
                + " expires=" + expiresAt);

        persistEvent(commandId, command, "SUBMITTED", now, expiresAt);

        return new GateResult(GateStatus.PENDING, commandId);
    }

    /**
     * Checks the current status of a pending command.
     *
     * <p>Called by the orchestrator on each tick while in
     * {@code WAIT_OPERATOR_CONFIRM} state. Transitions the entry to
     * {@link GateStatus#EXPIRED} when the confirmation window has elapsed.
     *
     * @param commandId the UUID string of the pending command
     * @return the current status
     */
    public GateResult checkStatus(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return new GateResult(GateStatus.NOT_FOUND, commandId);
        }
        PendingEntry entry = pending.get(commandId);
        if (entry == null) {
            return new GateResult(GateStatus.NOT_FOUND, commandId);
        }

        String currentStatus = entry.status;

        if ("CONFIRMED".equals(currentStatus)) {
            return new GateResult(GateStatus.CONFIRMED, commandId);
        }
        if ("CANCELLED".equals(currentStatus)) {
            return new GateResult(GateStatus.CANCELLED, commandId);
        }

        // Check expiry
        if (entry.isExpired(clock.get())) {
            if (entry.status.equals("PENDING")) {
                // Mark expired in-memory (idempotent with volatile write)
                entry.status = "EXPIRED";
                Instant now = clock.get();
                persistEvent(commandId, entry.command, "EXPIRED", now, entry.expiresAt);
                LOG.info("[LiveAssistConfirmationGate] EXPIRED command " + commandId);
            }
            return new GateResult(GateStatus.EXPIRED, commandId);
        }

        return new GateResult(GateStatus.PENDING, commandId);
    }

    /**
     * Confirms a pending command, advancing it from {@code PENDING} to
     * {@code CONFIRMED}.
     *
     * <p>Returns {@link GateStatus#EXPIRED} when the confirmation window has
     * elapsed — the caller must reject the confirmation.
     * Returns {@link GateStatus#NOT_FOUND} when no entry exists for the ID.
     * Returns {@link GateStatus#CANCELLED} when the entry was already cancelled.
     *
     * @param commandId the UUID string of the pending command to confirm
     * @return a {@link GateResult} with the resulting status
     */
    public GateResult confirm(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return new GateResult(GateStatus.NOT_FOUND, commandId);
        }
        PendingEntry entry = pending.get(commandId);
        if (entry == null) {
            return new GateResult(GateStatus.NOT_FOUND, commandId);
        }

        // Check for expiry first
        Instant now = clock.get();
        if (entry.isExpired(now)) {
            entry.status = "EXPIRED";
            persistEvent(commandId, entry.command, "EXPIRED", now, entry.expiresAt);
            return new GateResult(GateStatus.EXPIRED, commandId);
        }

        String currentStatus = entry.status;
        if ("CANCELLED".equals(currentStatus)) {
            return new GateResult(GateStatus.CANCELLED, commandId);
        }
        if ("CONFIRMED".equals(currentStatus)) {
            // Idempotent
            return new GateResult(GateStatus.CONFIRMED, commandId);
        }

        // Transition PENDING → CONFIRMED
        entry.status = "CONFIRMED";
        persistEvent(commandId, entry.command, "CONFIRMED", now, entry.expiresAt);

        LOG.info("[LiveAssistConfirmationGate] CONFIRMED command " + commandId
                + " type=" + entry.command.commandType());

        return new GateResult(GateStatus.CONFIRMED, commandId);
    }

    /**
     * Cancels a pending command, advancing it from {@code PENDING} to
     * {@code CANCELLED}.
     *
     * <p>A cancelled command must not be applied. The orchestrator should
     * discard the command and transition back to a safe scanning state.
     *
     * @param commandId the UUID string of the pending command to cancel
     * @return a {@link GateResult} with the resulting status; {@link GateStatus#NOT_FOUND}
     *         if no entry exists
     */
    public GateResult cancel(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return new GateResult(GateStatus.NOT_FOUND, commandId);
        }
        PendingEntry entry = pending.get(commandId);
        if (entry == null) {
            return new GateResult(GateStatus.NOT_FOUND, commandId);
        }

        String currentStatus = entry.status;
        if ("CONFIRMED".equals(currentStatus)) {
            // Already confirmed — cannot cancel after confirmation
            return new GateResult(GateStatus.CONFIRMED, commandId);
        }
        if ("CANCELLED".equals(currentStatus)) {
            // Idempotent
            return new GateResult(GateStatus.CANCELLED, commandId);
        }

        entry.status = "CANCELLED";
        Instant now = clock.get();
        persistEvent(commandId, entry.command, "CANCELLED", now, entry.expiresAt);

        LOG.info("[LiveAssistConfirmationGate] CANCELLED command " + commandId);

        return new GateResult(GateStatus.CANCELLED, commandId);
    }

    /**
     * Returns the {@link DecisionCommand} for a pending command ID, or {@code null}
     * if not found. Used by the orchestrator to retrieve the original command after
     * confirmation so it can apply the correct state transition.
     *
     * @param commandId the UUID string of the command
     * @return the original {@link DecisionCommand}, or {@code null}
     */
    public DecisionCommand getPendingCommand(String commandId) {
        PendingEntry entry = pending.get(commandId);
        return entry == null ? null : entry.command;
    }

    /**
     * Returns the current mode this gate was constructed with.
     */
    public DecisionCommand.Mode mode() {
        return mode;
    }

    /**
     * Returns the timeout in seconds applied to pending commands.
     */
    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    // =========================================================================
    // Persistence (best-effort, never propagates exceptions)
    // =========================================================================

    /**
     * Writes one event row to {@code agentic_pending_commands}.
     * Logs a warning on failure but never propagates the exception.
     *
     * @param commandId  the command UUID string
     * @param command    the DecisionCommand being tracked
     * @param eventType  one of SUBMITTED, CONFIRMED, CANCELLED, EXPIRED
     * @param eventTs    UTC instant of this event
     * @param expiresAt  UTC instant at which the command expires
     */
    private void persistEvent(
            String commandId,
            DecisionCommand command,
            String eventType,
            Instant eventTs,
            Instant expiresAt) {

        if (connSupplier == null) {
            return; // In-memory only (tests / simulation) — no DB
        }
        try (Connection conn = connSupplier.get();
             PreparedStatement ps = conn.prepareStatement(INSERT_EVENT_SQL)) {

            ps.setTimestamp(1, Timestamp.from(eventTs));             // event_ts
            ps.setString(2, commandId);                              // command_id
            ps.setString(3, runId);                                  // run_id
            ps.setString(4, command.mode().name());                  // mode
            // underlying is not directly on DecisionCommand — derive from reason code context
            // The command's underlying is not a direct field; use empty string as placeholder.
            // Phase 7 will wire the underlying from the orchestrator context.
            ps.setString(5, "");                                     // underlying (TODO: wire from orchestrator)
            ps.setString(6, command.commandType().name());           // command_type
            ps.setString(7, command.reasonCode());                   // reason_code
            ps.setString(8, command.explanation());                  // explanation
            ps.setString(9, eventType);                              // event_type
            ps.setTimestamp(10, Timestamp.from(command.issuedTs())); // submitted_ts
            ps.setTimestamp(11, Timestamp.from(expiresAt));          // expires_at_ts
            ps.setInt(12, (int) timeoutSeconds);                     // timeout_seconds

            ps.executeUpdate();

        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "[LiveAssistConfirmationGate] Failed to persist event " + eventType
                            + " for command " + commandId, e);
        }
    }
}
