package com.strategysquad.agentic.decision;

import com.strategysquad.agentic.risk.RiskGuardDecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical output contract of the Decision Agent.
 *
 * <p>One {@code DecisionCommand} is produced per decision cycle. It captures the
 * agent's intent — what it would do and why — together with compatibility audit
 * metadata. In {@link Mode#PAPER} and
 * {@link Mode#SIMULATION} modes the command is audited but does <em>not</em> mutate
 * position state. In {@link Mode#LIVE_ASSIST} mode the command is presented to the
 * operator for confirmation before any position mutation occurs.
 *
 * <h2>Immutability</h2>
 * <p>This is a pure value object. No setters. All collections are unmodifiable at
 * construction time. Do not add logic, I/O, or DB access here.
 *
 * <h2>Audit requirement</h2>
 * <p>Every {@code DecisionCommand} must have a non-empty {@link #reasonCode()} and
 * a non-empty {@link #explanation()} before it is written to the audit log. A command
 * without these fields is incomplete and must not be applied.
 *
 * <h2>Compatibility fields</h2>
 * <p>{@link #riskGuardDecision()} and {@link #overriddenByRiskGuard()} are retained for
 * audit/backward compatibility. Runtime Risk Guard enforcement is disabled; these
 * fields are populated by the decision pipeline as metadata.
 */
public record DecisionCommand(

        /**
         * Stable unique identifier for this command.
         * Generated as a random UUID at construction time.
         * Used as the primary key in the {@code agentic_decision_audit} table and
         * as the confirmation token in {@code LIVE_ASSIST} mode.
         */
        UUID commandId,

        /**
         * UTC instant at which this command was issued by the Decision Agent.
         * In simulation mode this is the {@code SimulationClock} time, not wall clock.
         */
        Instant issuedTs,

        /**
         * Operating mode active when this command was issued.
         * Determines whether the command is applied automatically (SIMULATION/PAPER)
         * or requires operator confirmation (LIVE_ASSIST).
         */
        Mode mode,

        /**
         * The action the Decision Agent recommends for this cycle.
         */
        CommandType commandType,

        /**
         * Instrument IDs of the scanner candidates selected for an entry or
         * adjustment action.
         * Empty list for non-entry commands ({@code HOLD}, {@code BOOK_PROFIT},
         * {@code EXIT_ALL}, {@code SKIP}, etc.).
         * Units: canonical instrument ID strings from {@code instrument_master}.
         * Never null — use an empty list when no candidates are selected.
         */
        List<String> selectedCandidateIds,

        /**
         * ID of the position session this command targets, if one is active.
         * Empty for {@code ENTER} and {@code SKIP} commands when no session exists.
         */
        Optional<String> positionSessionId,

        /**
         * Machine-readable reason code for this decision.
         * Examples: {@code PREMIUM_RICH_LOW_DELTA}, {@code THETA_TARGET_REACHED},
         * {@code NO_QUALIFIED_CANDIDATES}.
         * Must never be blank in a complete command. Consumed by downstream analytics
         * and audit queries.
         */
        String reasonCode,

        /**
         * Trader-readable explanation for this decision.
         * Example: {@code "CE 24800 premium 18% above historical average, empirical
         * delta 0.12, entering short straddle"}.
         * Must never be blank in a complete command. Shown in the live-assist UI and
         * in simulation reports.
         */
        String explanation,

        /**
         * Compatibility metadata field for audit/backward compatibility.
         * Runtime Risk Guard enforcement is disabled.
         */
        RiskGuardDecision riskGuardDecision,

        /**
         * Compatibility audit flag. Runtime Risk Guard overrides are disabled.
         */
        boolean overriddenByRiskGuard

) {

    // -------------------------------------------------------------------------
    // Embedded enums
    // -------------------------------------------------------------------------

    /**
     * Operating mode of the Decision Agent when a command is issued.
     */
    public enum Mode {

        /**
         * Historical replay mode. Time advances via {@code SimulationClock}.
         * Commands are audited and position state is mutated in simulation tables,
         * but no live market data is consumed.
         */
        SIMULATION,

        /**
         * Paper trading mode. Live market data is consumed but commands are
         * audited only — position state is tracked in paper session tables and
         * no real orders are placed.
         */
        PAPER,

        /**
         * Live-assist mode. Live market data is consumed. Commands are presented
         * to the operator for confirmation via the live-assist UI before any
         * position mutation occurs. No broker order is placed automatically.
         */
        LIVE_ASSIST
    }

    /**
     * The type of action the Decision Agent recommends for a given cycle.
     */
    public enum CommandType {

        /**
         * Open a new short-option structure using the top-ranked scanner candidate.
         * Only valid when no active session exists.
         */
        ENTER,

        /**
         * Add one additional lot to each leg of the active session.
         * Only valid when an active session exists.
         */
        ADD,

        /**
         * Remove one lot from one or more legs of the active session to reduce
         * net delta or lock in partial premium.
         */
        REDUCE,

        /**
         * Exit the current leg and re-enter at a more favourable strike
         * recommended by the scanner. One leg per command.
         */
        SHIFT_STRIKE,

        /**
         * No action this cycle. Position remains unchanged.
         * The most common command type during normal monitoring.
         */
        HOLD,

        /**
         * Book profit on the active session (partial or full depending on
         * {@code theta_progress_ratio}). Triggers scanner restart after full booking.
         */
        BOOK_PROFIT,

        /**
         * Exit a single identified leg of the active session. The session
         * remains open with the remaining legs.
         */
        EXIT_LEG,

        /**
         * Exit all legs of the active session immediately. Freezes the session
         * as {@code CLOSED}.
         */
        EXIT_ALL,

        /**
         * No action and no entry this cycle. Emitted when no qualified candidates
         * exist or entry conditions are not met.
         */
        SKIP,

        /**
         * Operator-initiated session halt. Used exclusively in audit records written
         * by {@code LiveAssistConfirmationGate} and the operator halt REST endpoint.
         * Never emitted by {@code DecisionPolicy} or {@code DecisionAgent}.
         */
        HALT_SESSION
    }

    // -------------------------------------------------------------------------
    // Compact constructor — defensive copies for mutable collections
    // -------------------------------------------------------------------------

    /**
     * Compact constructor enforces that list fields are never null and that
     * {@code reasonCode} and {@code explanation} are not null (though they may be
     * blank in intermediate states; callers must validate before writing the audit).
     */
    public DecisionCommand {
        if (commandId == null) throw new IllegalArgumentException("commandId must not be null");
        if (issuedTs == null) throw new IllegalArgumentException("issuedTs must not be null");
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        if (commandType == null) throw new IllegalArgumentException("commandType must not be null");
        if (selectedCandidateIds == null) throw new IllegalArgumentException("selectedCandidateIds must not be null");
        if (positionSessionId == null) throw new IllegalArgumentException("positionSessionId must not be null");
        if (reasonCode == null) throw new IllegalArgumentException("reasonCode must not be null");
        if (explanation == null) throw new IllegalArgumentException("explanation must not be null");
        if (riskGuardDecision == null) throw new IllegalArgumentException("riskGuardDecision must not be null");
        // Defensive unmodifiable copy
        selectedCandidateIds = List.copyOf(selectedCandidateIds);
    }
}
