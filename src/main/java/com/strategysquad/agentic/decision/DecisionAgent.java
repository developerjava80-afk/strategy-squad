package com.strategysquad.agentic.decision;

import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.risk.RiskGuardSnapshot;
import com.strategysquad.agentic.builder.PositionBuilderAgent;
import com.strategysquad.agentic.builder.PositionPlan;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.PositionSessionSnapshot;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrating service for one decision cycle of the agentic theta-decay loop.
 *
 * <h2>Responsibilities (Phase 2 scope)</h2>
 * <ol>
 *   <li>Calls the injected {@link CandidateLoader} to get ranked candidates.</li>
 *   <li>Calls {@link SignalSnapshotService} for the active leg instruments.</li>
 *   <li>Reads the active position session via the injected {@link SessionLoader}.</li>
 *   <li>Builds a default ALLOW risk snapshot for command/audit compatibility.</li>
 *   <li>Assembles a {@link DecisionContext}.</li>
 *   <li>Calls {@link DecisionPolicy} to produce a {@link DecisionCommand}.</li>
 *   <li>Writes an audit record via {@link DecisionAuditWriter} — always, even for SKIP.</li>
 *   <li>Returns the command — does NOT apply it in PAPER or SIMULATION mode.</li>
 * </ol>
 *
 * <h2>Paper-mode gate</h2>
 * <p>In {@link DecisionCommand.Mode#PAPER} and {@link DecisionCommand.Mode#SIMULATION}
 * mode this agent never calls {@code PositionSessionActionService}. The decision is
 * audited but position state is not mutated. Position mutation (for ENTER commands) is
 * wired in Phase 3 (Task 3.3) after replay quality has been validated.
 *
 * <h2>Audit-before-action contract</h2>
 * <p>The audit record is written to {@code agentic_decision_audit} <em>before</em> this
 * method returns the command to the caller. If the write fails, a WARNING is logged but
 * the command is still returned — the decision loop must not halt because of an audit
 * write failure.
 *
 * <h2>No broker orders</h2>
 * <p>No code path in this class calls any broker order API. The constraint applies
 * unconditionally — see {@code developer-notes.md} "No broker order placement."
 *
 * <h2>Signal snapshot retrieval</h2>
 * <p>In Phase 2 the agent builds signal snapshots only for legs of the active session.
 * When no session is active the signal map is empty. This is correct for ENTER and SKIP
 * decisions where no live position context is required.
 */
public final class DecisionAgent {

    private static final Logger LOG = Logger.getLogger(DecisionAgent.class.getName());

    // -------------------------------------------------------------------------
    // Default configuration constants
    // -------------------------------------------------------------------------

    /** Default maximum lot count across all active legs. Overridable at construction. */
    public static final int DEFAULT_MAX_LOT_CAP = 4;

    /**
     * State machine state written to the audit record in Phase 2.
     * The full orchestrator (Phase 5) will supply the live state; for Phase 2
     * the agent operates in a stateless mode and records this sentinel value.
     */
    private static final String PHASE2_STATE = "EVALUATE_ENTRY";

    // -------------------------------------------------------------------------
    // Collaborators
    // -------------------------------------------------------------------------

    private final DecisionCommand.Mode mode;
    private final String underlying;
    private final CandidateLoader candidateLoader;
    private final SignalLoader signalLoader;
    private final SessionLoader sessionLoader;
    private final DecisionPolicy policy;
    private final DecisionAuditWriter auditWriter;
    private final ConnectionSupplier connectionSupplier;
    private final int maxLotCap;
    /** Nullable — when null, ENTER commands are not expanded into a position session. */
    private final PlanBuilder planBuilder;
    /** Nullable — when null, accepted plans are not persisted to storage. */
    private final SessionPersister sessionPersister;

    // -------------------------------------------------------------------------
    // Functional interface seams — allow test injection
    // -------------------------------------------------------------------------

    /**
     * Supplies an open JDBC connection for the audit write. The implementation
     * must return a fresh (or pooled) connection; this agent does not own the
     * connection lifecycle — it closes the connection after the audit write.
     */
    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    /**
     * Loads the currently active position session for the agent's underlying.
     * Returns {@link Optional#empty()} when no session is open.
     */
    @FunctionalInterface
    public interface SessionLoader {
        Optional<PositionSessionSnapshot> loadActiveSession() throws Exception;
    }

    /**
     * Supplies signal snapshots for the active legs at a given instant.
     *
     * <p>In Phase 2 the default implementation returns an empty map (no live feed).
     * Phase 3 wires real signal computation from {@link SignalSnapshotService}.
     * Tests inject a stub that returns pre-built fixture snapshots so the BOOK_PROFIT
     * and REDUCE scenarios can be exercised without a live database.
     */
    @FunctionalInterface
    public interface SignalLoader {
        /**
         * Returns signal snapshots keyed by {@code instrument_id}.
         * Must never return {@code null} — return an empty map when no signals exist.
         *
         * @param activeSession the currently active session (may be empty)
         * @param signalTs      the instant at which signals are being computed
         * @throws Exception if signal computation fails (caller logs and uses empty map)
         */
        Map<String, SignalSnapshot> load(
                Optional<PositionSessionSnapshot> activeSession,
                Instant signalTs) throws Exception;
    }

    /**
     * Supplies ranked scanner candidates for a given scan instant.
     *
     * <p>The implementation is responsible for loading the cohort map and delegating
     * to {@link MorningScannerService#scan(Map, Instant)}. This seam allows
     * {@code DecisionAgent} to remain decoupled from the cohort data source (live DB,
     * in-memory fixture, or simulation replay).
     */
    @FunctionalInterface
    public interface CandidateLoader {
        /**
         * Returns the ranked candidate list for {@code scanInstant}.
         * Must never return {@code null} — return an empty list when no candidates exist.
         *
         * @param scanInstant the instant at which the scan is being run
         * @throws Exception if the scanner pipeline fails (caller will log and use empty list)
         */
        List<CandidateOpportunity> load(Instant scanInstant) throws Exception;
    }

    /**
     * Builds a {@link PositionPlan} from ranked candidates and signal snapshots.
     *
     * <p>Typically backed by {@link PositionBuilderAgent#buildShortStraddle} or
     * {@link PositionBuilderAgent#buildShortStrangle}. Using a functional interface
     * keeps {@code DecisionAgent} decoupled from the choice of structure and allows
     * test injection of a pre-built plan.
     */
    @FunctionalInterface
    public interface PlanBuilder {
        /**
         * Builds a position plan for the given underlying and candidates.
         *
         * @param underlying      underlying name ({@code NIFTY} / {@code BANKNIFTY})
         * @param candidates      ranked qualified+disqualified candidate list
         * @param signals         signal snapshots keyed by instrument_id
         * @param planTs          UTC instant at plan build time
         * @return accepted or rejected plan; never null
         * @throws Exception if the builder encounters an unexpected error
         */
        PositionPlan build(String underlying,
                           List<CandidateOpportunity> candidates,
                           Map<String, SignalSnapshot> signals,
                           Instant planTs) throws Exception;
    }

    /**
     * Persists a newly created {@link PositionSessionSnapshot}.
     *
     * <p>Typically backed by {@link com.strategysquad.research.ResearchPositionSessionService#save}.
     * Tests inject a capturing lambda to verify session creation without file I/O.
     */
    @FunctionalInterface
    public interface SessionPersister {
        /**
         * Saves the position session. Implementations must be idempotent on the session ID.
         *
         * @param session the newly created session snapshot
         * @throws Exception if persistence fails
         */
        void persist(PositionSessionSnapshot session) throws Exception;
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code DecisionAgent} with all collaborators injected.
     *
     * @param mode               operating mode (SIMULATION / PAPER / LIVE_ASSIST)
     * @param underlying         the underlying this agent manages ({@code NIFTY} / {@code BANKNIFTY})
     * @param candidateLoader    supplies ranked candidates for the scan instant
     * @param signalLoader       supplies signal snapshots for the active legs; use
     *                           {@code (session, ts) -> Collections.emptyMap()} for Phase 2
     *                           paper-only runs where no live feed is available
     * @param sessionLoader      loads the active position session (or empty)
     * @param policy             deterministic decision policy (pure, no I/O)
     * @param auditWriter        writes DecisionCommand to agentic_decision_audit
     * @param connectionSupplier opens a JDBC connection for each audit write
     * @param maxLotCap          total lot cap across all active legs (configuration)
     * @param planBuilder        builds a {@link PositionPlan} when ENTER is issued; may be
     *                           {@code null} to disable session creation (Phase 2 behaviour)
     * @param sessionPersister   persists the new session after an accepted plan; may be
     *                           {@code null} to skip persistence (Phase 2 behaviour)
     */
    public DecisionAgent(
            DecisionCommand.Mode mode,
            String underlying,
            CandidateLoader candidateLoader,
            SignalLoader signalLoader,
            SessionLoader sessionLoader,
            DecisionPolicy policy,
            DecisionAuditWriter auditWriter,
            ConnectionSupplier connectionSupplier,
            int maxLotCap,
            PlanBuilder planBuilder,
            SessionPersister sessionPersister) {

        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.underlying = Objects.requireNonNull(underlying, "underlying must not be null");
        this.candidateLoader = Objects.requireNonNull(candidateLoader, "candidateLoader must not be null");
        this.signalLoader = Objects.requireNonNull(signalLoader, "signalLoader must not be null");
        this.sessionLoader = Objects.requireNonNull(sessionLoader, "sessionLoader must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.connectionSupplier = Objects.requireNonNull(connectionSupplier, "connectionSupplier must not be null");
        if (maxLotCap < 1) throw new IllegalArgumentException("maxLotCap must be >= 1");
        this.maxLotCap = maxLotCap;
        this.planBuilder = planBuilder;        // nullable — Phase 2 passes null
        this.sessionPersister = sessionPersister; // nullable — Phase 2 passes null
    }

    /**
     * Convenience constructor for Phase 2 callers that do not wire position-building.
     * Equivalent to passing {@code null} for {@code planBuilder} and {@code sessionPersister}.
     */
    public DecisionAgent(
            DecisionCommand.Mode mode,
            String underlying,
            CandidateLoader candidateLoader,
            SignalLoader signalLoader,
            SessionLoader sessionLoader,
            DecisionPolicy policy,
            DecisionAuditWriter auditWriter,
            ConnectionSupplier connectionSupplier,
            int maxLotCap) {

        this(mode, underlying, candidateLoader, signalLoader, sessionLoader,
             policy, auditWriter, connectionSupplier, maxLotCap,
             null, null);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs one decision cycle and returns the resulting {@link DecisionCommand}.
     *
     * <p>The audit record is written before this method returns. The returned command
     * is NOT applied to any position state in PAPER or SIMULATION mode.
     *
     * @param decisionTs UTC instant at which this cycle is being evaluated; must not be null
     * @return the decision command; never null
     */
    public DecisionCommand decide(Instant decisionTs) {
        Objects.requireNonNull(decisionTs, "decisionTs must not be null");

        // ------------------------------------------------------------------
        // Step 1 — Load ranked scanner candidates
        // ------------------------------------------------------------------
        List<CandidateOpportunity> rankedCandidates;
        try {
            rankedCandidates = candidateLoader.load(decisionTs);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "CandidateLoader failed — proceeding with empty candidate list", e);
            rankedCandidates = Collections.emptyList();
        }

        // ------------------------------------------------------------------
        // Step 2 — Load active position session
        // ------------------------------------------------------------------
        Optional<PositionSessionSnapshot> activeSession;
        try {
            activeSession = sessionLoader.loadActiveSession();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Session loader failed — assuming no active session", e);
            activeSession = Optional.empty();
        }

        // ------------------------------------------------------------------
        // Step 3 — Build signal snapshots for active legs
        //          (In Phase 2, signal map is built from live data via the
        //           SignalSnapshotService. For paper/sim mode without a live
        //           DB the map may be empty — DecisionPolicy handles this.)
        // ------------------------------------------------------------------
        Map<String, SignalSnapshot> signalSnapshots = buildSignalSnapshots(activeSession, decisionTs);

        PnlSummary pnlSummary = computePnlSummary(activeSession, signalSnapshots);
        RiskGuardSnapshot riskSnapshot = allowSnapshot(decisionTs);

        // ------------------------------------------------------------------
        // Step 5 — Assemble DecisionContext
        // ------------------------------------------------------------------
        DecisionContext ctx = new DecisionContext(
                decisionTs,
                mode,
                rankedCandidates,
                signalSnapshots,
                activeSession,
                pnlSummary.livePnl(),
                pnlSummary.bookedPnl(),
                riskSnapshot,
                maxLotCap,
                false,  // cooldownActive — Phase 4 wires this
                riskSnapshot.churnDetected()
        );

        // ------------------------------------------------------------------
        // Step 6 — Evaluate decision policy
        // ------------------------------------------------------------------
        DecisionCommand cmd = policy.evaluate(ctx);

        // ------------------------------------------------------------------
        // Step 6b — If ENTER, call PositionBuilderAgent (Task 3.3)
        //           If plan is rejected → rebind cmd to SKIP with the rejection reason.
        //           If plan is accepted → create position session via SessionPersister.
        //           When planBuilder is null (Phase 2 mode) this block is skipped.
        // ------------------------------------------------------------------
        if (cmd.commandType() == DecisionCommand.CommandType.ENTER && planBuilder != null) {
            PositionPlan plan;
            try {
                plan = planBuilder.build(underlying, rankedCandidates, signalSnapshots, decisionTs);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "PlanBuilder threw for ENTER command — emitting SKIP", e);
                cmd = buildSkipFromRejection(cmd, "PLAN_BUILDER_ERROR");
                plan = null;
            }
            if (plan != null && !plan.riskGuardApproved()) {
                String reason = plan.rejectionReason().orElse("PLAN_REJECTED");
                LOG.warning("[DecisionAgent] Plan rejected for " + underlying +
                            " — reason: " + reason + " — converting ENTER to SKIP");
                cmd = buildSkipFromRejection(cmd, reason);
            } else if (plan != null) {
                // Plan accepted — create position session
                PositionSessionSnapshot newSession = buildSessionFromPlan(plan, cmd, decisionTs);
                if (sessionPersister != null) {
                    try {
                        sessionPersister.persist(newSession);
                        LOG.info("[DecisionAgent] Position session created: " + newSession.sessionId() +
                                 " for " + underlying + " (" + plan.structureType() + ")");
                    } catch (Exception e) {
                        LOG.log(Level.WARNING,
                                "SessionPersister failed for session " + newSession.sessionId() +
                                " — ENTER command still audited", e);
                    }
                }
                // Rebind positionSessionId on the command so audit links to the new session
                cmd = new DecisionCommand(
                        cmd.commandId(),
                        cmd.issuedTs(),
                        cmd.mode(),
                        cmd.commandType(),
                        cmd.selectedCandidateIds(),
                        Optional.of(newSession.sessionId()),
                        cmd.reasonCode(),
                        cmd.explanation(),
                        cmd.riskGuardDecision(),
                        cmd.overriddenByRiskGuard()
                );
            }
        }

        // ------------------------------------------------------------------
        // Step 7 — Write audit record (always — even for SKIP)
        //          Audit is written BEFORE returning the command.
        // ------------------------------------------------------------------
        writeAudit(cmd, ctx, riskSnapshot, rankedCandidates);

        // ------------------------------------------------------------------
        // Step 8 — Paper / simulation gate
        // ------------------------------------------------------------------
        if (mode == DecisionCommand.Mode.LIVE_ASSIST) {
            LOG.warning("LIVE_ASSIST mode is not yet active — treating as PAPER. " +
                        "No position mutation occurred.");
        }

        return cmd;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Wraps a plan rejection reason into a SKIP command, preserving all metadata
     * from the original ENTER command.
     */
    private static DecisionCommand buildSkipFromRejection(DecisionCommand original, String rejectionReason) {
        return new DecisionCommand(
                original.commandId(),
                original.issuedTs(),
                original.mode(),
                DecisionCommand.CommandType.SKIP,
                List.of(),
                Optional.empty(),
                rejectionReason,
                "Plan rejected — " + rejectionReason,
                original.riskGuardDecision(),
                original.overriddenByRiskGuard()
        );
    }

    /**
     * Converts an accepted {@link PositionPlan} into an initial {@link PositionSessionSnapshot}
     * suitable for persistence.
     */
    private static PositionSessionSnapshot buildSessionFromPlan(
            PositionPlan plan,
            DecisionCommand cmd,
            Instant sessionTs) {

        String sessionId = UUID.randomUUID().toString();
        List<PositionSessionSnapshot.PositionLegSnapshot> legs = plan.legs().stream()
                .map(planLeg -> new PositionSessionSnapshot.PositionLegSnapshot(
                        UUID.randomUUID().toString(),          // legId
                        planLeg.optionType() + " " + planLeg.strike(),  // label
                        planLeg.optionType(),
                        planLeg.side().name(),
                        planLeg.strike(),
                        planLeg.expiryDate().toString(),
                        planLeg.instrumentId(),                // symbol (same as instrumentId for now)
                        planLeg.instrumentId(),
                        planLeg.entryPrice(),
                        planLeg.lotSize() * planLeg.lots(),    // originalQuantity
                        planLeg.lotSize() * planLeg.lots(),    // openQuantity
                        BigDecimal.ZERO,                       // bookedPnl
                        "OPEN",
                        sessionTs,
                        sessionTs
                ))
                .toList();

        String modeLabel = cmd.mode().name();
        return new PositionSessionSnapshot(
                sessionId,
                modeLabel,
                plan.structureType() + " " + plan.underlying(),
                "SHORT",
                plan.underlying(),
                "WEEKLY",       // expiry type — Phase 3 default
                "DAY",
                0,              // DTE — Phase 3 will compute from expiryDate
                BigDecimal.ZERO, // spot at entry — Phase 3 wires live spot
                1,              // scenarioQty
                sessionTs,
                sessionTs,
                null,           // lastDeltaAdjustmentTs
                "ACTIVE",
                legs,
                List.of()       // no audit entries yet
        );
    }

    /**
     * Builds signal snapshots by delegating to the injected {@link SignalLoader}.
     *
     * <p>In Phase 2 production use the loader is {@code (s, t) -> emptyMap()}.
     * Tests inject a stub that returns pre-built fixture snapshots so the
     * BOOK_PROFIT and REDUCE decision paths can be exercised without a live DB.
     * Phase 3 wires the real {@link SignalSnapshotService} computation here.
     */
    private Map<String, SignalSnapshot> buildSignalSnapshots(
            Optional<PositionSessionSnapshot> activeSession,
            Instant signalTs) {

        try {
            Map<String, SignalSnapshot> snapshots = signalLoader.load(activeSession, signalTs);
            return (snapshots != null) ? snapshots : Collections.emptyMap();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "SignalLoader failed — proceeding with empty signal map", e);
            return Collections.emptyMap();
        }
    }

        private record PnlSummary(double livePnl, double bookedPnl) {
        }

        /**
         * Computes live and booked PnL context used by DecisionContext and audit.
         */
        private PnlSummary computePnlSummary(
            Optional<PositionSessionSnapshot> activeSession,
            Map<String, SignalSnapshot> signalSnapshots) {

        double livePnl = 0.0;
        double bookedPnl = 0.0;

        if (activeSession.isPresent()) {
            PositionSessionSnapshot session = activeSession.get();
            // Sum booked PnL across all legs
            bookedPnl = session.legs().stream()
                    .filter(leg -> leg.bookedPnl() != null)
                    .mapToDouble(leg -> leg.bookedPnl().doubleValue())
                    .sum();
            // Live PnL: for each active leg, sum (expectedDecaySinceEntry × openQuantity).
            // expectedDecaySinceEntry is positive for a short leg when the premium has
            // decayed since entry (i.e., the position is profitable). Signal snapshots
            // are matched by instrumentId.
            livePnl = session.legs().stream()
                    .filter(leg -> {
                        String s = leg.status();
                        return s != null && !"CLOSED".equalsIgnoreCase(s) && leg.openQuantity() > 0;
                    })
                    .mapToDouble(leg -> {
                        SignalSnapshot sig = signalSnapshots.get(leg.instrumentId());
                        if (sig == null || sig.expectedDecaySinceEntry() == null) return 0.0;
                        return sig.expectedDecaySinceEntry().doubleValue() * leg.openQuantity();
                    })
                    .sum();
        }

                return new PnlSummary(livePnl, bookedPnl);
    }

    /**
     * Writes the audit record. Logs a warning on failure but never propagates the
     * exception — audit failures must not halt the decision loop.
     */
    private void writeAudit(
            DecisionCommand cmd,
            DecisionContext ctx,
            RiskGuardSnapshot riskSnapshot,
            List<CandidateOpportunity> rankedCandidates) {

        // Derive optional audit context fields from signal snapshots and candidates
        String thetaState = null;
        Double thetaProgressRatio = null;
        Map<String, SignalSnapshot> signals = ctx.signalSnapshots();
        if (!signals.isEmpty()) {
            // Pick the first available signal for theta context
            SignalSnapshot firstSignal = signals.values().iterator().next();
            if (firstSignal.thetaState() != null) {
                thetaState = firstSignal.thetaState().name();
            }
            if (firstSignal.thetaProgressRatio() != null) {
                thetaProgressRatio = firstSignal.thetaProgressRatio().doubleValue();
            }
        }

        Double liquidityScore = null;
        if (!rankedCandidates.isEmpty()) {
            // Top-ranked candidate (qualified or not) — use its liquidity score
            CandidateOpportunity top = rankedCandidates.get(0);
            if (top.disqualifierReason().isEmpty()) {
                liquidityScore = top.liquidityScore();
            }
        }

        DecisionAuditWriter.AuditContext auditCtx = new DecisionAuditWriter.AuditContext(
                PHASE2_STATE,
                null,                   // liveSpot — not yet wired in Phase 2
                riskSnapshot.netDelta(),
                riskSnapshot.netDelta(), // netDeltaAfter same as before for Phase 2 stub
                thetaState,
                thetaProgressRatio,
                ctx.livePnl(),
                ctx.bookedPnl(),
                liquidityScore
        );

        try (Connection conn = connectionSupplier.get()) {
            auditWriter.write(conn, cmd, auditCtx);
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "Audit write failed for command " + cmd.commandId() +
                    " — decision still returned to caller",
                    e);
        }
    }

    /**
     * Builds a default ALLOW snapshot while Risk Guard enforcement is disabled.
     */
    private static RiskGuardSnapshot allowSnapshot(Instant ts) {
        return new RiskGuardSnapshot(
                ts,
                com.strategysquad.agentic.risk.RiskGuardDecision.ALLOW,
                List.of(),
                "RISK_GUARD_DISABLED",
                0.0,
                0.0,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }
}
