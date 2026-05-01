package com.strategysquad.agentic.risk;

import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.PositionSessionSnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable input contract for {@link RiskGuardService#evaluate(RiskGuardInput)}.
 *
 * <p>Assembled by {@code DecisionAgent} once per decision cycle and passed to
 * {@code RiskGuardService} before any command is applied. Contains all market,
 * position, and configuration state the Risk Guard needs to evaluate hard stops.
 *
 * <h2>Units</h2>
 * <ul>
 *   <li>{@link #livePnl()} and {@link #bookedPnl()} — NSE index points (not rupees).</li>
 *   <li>{@link #netDelta()} — dimensionless signed ratio summed across all active legs.</li>
 *   <li>{@link #maxLossPoints()} — NSE index points; the threshold below which
 *       {@code FORCE_EXIT} is triggered.</li>
 * </ul>
 *
 * <h2>Phase note</h2>
 * <p>This record is pre-created in Phase 2 so that {@link RiskGuardService} has a
 * complete method signature. The full enforcement logic (Task 4.1) reads all fields.
 * The Phase 2 stub reads only {@link #livePnl()} for audit pass-through.
 */
public record RiskGuardInput(

        /**
         * UTC instant at which this input was assembled.
         * In simulation mode this is the {@code SimulationClock} time, not wall clock.
         * Never null.
         */
        Instant evaluationTs,

        /**
         * The currently active position session, if any.
         * {@link Optional#empty()} when no session is open (pre-entry state).
         * Never null.
         */
        Optional<PositionSessionSnapshot> activeSession,

        /**
         * Signal snapshots keyed by {@code instrument_id}.
         * Contains one entry per instrument for which a signal could be computed.
         * Missing instruments are absent from the map (not represented as null values).
         * Never null — empty map when no signals are available.
         */
        Map<String, SignalSnapshot> signalSnapshots,

        /**
         * Unrealised PnL of the active session at evaluation time.
         * Units: NSE index points (not rupees, not per-lot).
         * Positive means the structure is profitable (premiums have decayed).
         * Zero when no session is active.
         */
        double livePnl,

        /**
         * Cumulative realised (booked) PnL from all prior closed legs within the day.
         * Units: NSE index points.
         * Zero at the start of the day.
         */
        double bookedPnl,

        /**
         * Net signed delta of the active position at evaluation time.
         * Dimensionless ratio. Positive = net long, negative = net short.
         * Zero when no session is active.
         */
        double netDelta,

        /**
         * Total active lot count across all legs of the current session.
         * Zero when no session is open.
         */
        int lotCount,

        /**
         * Maximum total lot count allowed across all active legs combined.
         * Sourced from agent configuration.
         */
        int maxLotCap,

        /**
         * Number of adjustment or decision commands issued in the current churn window.
         * Used to detect excessive churn (too many commands in a short period).
         */
        int recentCommandCount,

        /**
         * Width of the churn detection window, in minutes.
         * If {@link #recentCommandCount()} exceeds the configured maximum within this
         * window, {@code HALT_SESSION} is triggered.
         */
        int churnWindowMinutes,

        /**
         * Maximum allowable live PnL deterioration in NSE index points.
         * A {@code livePnl} value below {@code -maxLossPoints} triggers
         * {@code FORCE_EXIT}.
         * Always a positive value representing the magnitude of the loss limit.
         */
        double maxLossPoints,

        /**
         * Maximum age of market data before it is considered stale, in seconds.
         * If {@link #lastTickAgeSeconds()} exceeds this value, {@code BLOCK_NEW_ENTRY}
         * is triggered.
         */
        int staleDataSeconds,

        /**
         * Age of the most recent market tick received for any active instrument,
         * in seconds relative to {@link #evaluationTs()}.
         * Zero or negative indicates a fresh tick arrived at or after evaluation time.
         */
        int lastTickAgeSeconds

) {

    /**
     * Compact constructor enforcing null-safety on all required reference fields.
     */
    public RiskGuardInput {
        if (evaluationTs == null) throw new IllegalArgumentException("evaluationTs must not be null");
        if (activeSession == null) throw new IllegalArgumentException("activeSession must not be null (use Optional.empty())");
        if (signalSnapshots == null) throw new IllegalArgumentException("signalSnapshots must not be null");
        // Defensive unmodifiable copy
        signalSnapshots = Map.copyOf(signalSnapshots);
    }
}
