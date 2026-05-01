package com.strategysquad.agentic.risk;

import java.time.Instant;
import java.util.List;

/**
 * Immutable output contract of {@code RiskGuardService}.
 *
 * <p>Produced once per decision cycle, before the Decision Agent applies any command.
 * The snapshot records the full risk posture at evaluation time so that the audit
 * trail is self-contained and reproducible.
 *
 * <h2>Consumption rules</h2>
 * <ul>
 *   <li>The Decision Agent reads {@link #decision()} to determine whether the policy
 *       command must be overridden.</li>
 *   <li>If {@link #decision()} is anything other than {@link RiskGuardDecision#ALLOW},
 *       {@link #triggeredConditions()} must be non-empty.</li>
 *   <li>The entire snapshot is written verbatim to the {@code agentic_decision_audit}
 *       row for the corresponding {@code DecisionCommand}.</li>
 * </ul>
 *
 * <h2>Immutability</h2>
 * <p>Pure value object. No logic, no I/O, no DB access. The computation lives in
 * {@code RiskGuardService}.
 *
 * <h2>Sign and unit conventions</h2>
 * <ul>
 *   <li>{@link #netDelta()} — dimensionless signed ratio summed across all active
 *       legs. Positive means net long delta, negative means net short delta.</li>
 *   <li>{@link #livePnl()} — unrealised PnL of the active session in
 *       <b>NSE index points</b>. Positive is profitable for a short-option structure
 *       (option price has fallen since entry). Never in rupees.</li>
 * </ul>
 */
public record RiskGuardSnapshot(

        /**
         * UTC instant at which this snapshot was evaluated.
         * In simulation mode this is the {@code SimulationClock} time, not wall clock.
         */
        Instant snapshotTs,

        /**
         * The Risk Guard verdict for this cycle.
         * Drives the Decision Agent's override logic.
         * Never null.
         */
        RiskGuardDecision decision,

        /**
         * Machine-readable condition codes that caused the current decision.
         * Examples: {@code "NET_DELTA_BREACH"}, {@code "MAX_LOSS_EXCEEDED"},
         * {@code "DATA_STALE"}, {@code "CHURN_DETECTED"}.
         * <p><strong>Never null.</strong> Empty list when {@link #decision()} is
         * {@link RiskGuardDecision#ALLOW}. Non-empty for every other decision value.
         * Populated by {@code RiskGuardService} from the matching hard-stop constants.
         */
        List<String> triggeredConditions,

        /**
         * Trader-readable explanation of the current risk posture.
         * Example: {@code "Net delta 0.32 exceeds threshold 0.30 — reduce required"}.
         * Shown in the live-assist UI and included in the audit record.
         * Never null; may be an empty string when decision is ALLOW.
         */
        String explanation,

        /**
         * Net signed delta of the active position at evaluation time.
         * Dimensionless ratio. Positive = net long, negative = net short.
         * Zero when no session is active.
         */
        double netDelta,

        /**
         * Unrealised PnL of the active session at evaluation time.
         * Units: NSE index points (not rupees, not per-lot).
         * Positive means the structure is profitable (premiums have decayed).
         * Zero when no session is active.
         */
        double livePnl,

        /**
         * {@code true} when the session's live PnL has deteriorated below the
         * configured {@code max_loss_points} threshold.
         * Triggers at minimum {@link RiskGuardDecision#FORCE_EXIT}.
         */
        boolean maxLossBreached,

        /**
         * {@code true} when one or more legs have a current premium that is
         * expanding (rising) beyond the configured percent above the entry price.
         * Triggers at minimum {@link RiskGuardDecision#WARN},
         * escalating to {@link RiskGuardDecision#FORCE_REDUCE} at the hard threshold.
         */
        boolean premiumExpansionAlert,

        /**
         * {@code true} when the bid price for one or more active legs is zero,
         * or when the bid-ask spread has widened beyond the configured maximum,
         * indicating a liquidity collapse.
         * Triggers {@link RiskGuardDecision#BLOCK_NEW_ENTRY} or
         * {@link RiskGuardDecision#FORCE_EXIT} depending on whether a session is open.
         */
        boolean liquidityAlert,

        /**
         * {@code true} when the most recent market tick for any active leg or
         * the underlying arrived more than the configured {@code stale_data_seconds}
         * threshold before {@link #snapshotTs()}.
         * Triggers {@link RiskGuardDecision#BLOCK_NEW_ENTRY}.
         */
        boolean dataStale,

        /**
         * {@code true} when the number of adjustment commands issued in the
         * configured churn window exceeds the configured maximum.
         * Triggers {@link RiskGuardDecision#HALT_SESSION}.
         */
        boolean churnDetected,

        /**
         * {@code true} when the total active lot count across all legs equals or
         * exceeds the configured {@code max_lot_cap}.
         * Triggers {@link RiskGuardDecision#BLOCK_NEW_ENTRY}.
         */
        boolean lotCapBreached

) {

    /**
     * Compact constructor enforces that {@code triggeredConditions} is never null
     * and that the list is defensively copied to prevent external mutation.
     */
    public RiskGuardSnapshot {
        if (snapshotTs == null) throw new IllegalArgumentException("snapshotTs must not be null");
        if (decision == null) throw new IllegalArgumentException("decision must not be null");
        if (triggeredConditions == null) throw new IllegalArgumentException("triggeredConditions must not be null");
        if (explanation == null) throw new IllegalArgumentException("explanation must not be null");
        // Defensive unmodifiable copy — triggeredConditions must never be null or mutable
        triggeredConditions = List.copyOf(triggeredConditions);
    }
}
