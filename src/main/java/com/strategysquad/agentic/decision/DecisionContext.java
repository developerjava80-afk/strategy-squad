package com.strategysquad.agentic.decision;

import com.strategysquad.agentic.risk.RiskGuardSnapshot;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.PositionSessionSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A single immutable snapshot of all inputs the Decision Agent requires for one
 * decision cycle.
 *
 * <p>This record eliminates the need to pass many arguments into the decision policy.
 * {@code DecisionAgent} assembles one {@code DecisionContext} per cycle by collecting
 * outputs from {@code MorningScannerService}, {@code SignalSnapshotService}, and
 * {@code ResearchPositionSessionService}, then passes the assembled context to
 * {@code DecisionPolicy}.
 *
 * <h2>Immutability</h2>
 * <p>All list and map fields are made unmodifiable at construction time. Optional
 * fields are never null — absent values are represented as {@link Optional#empty()}.
 * Do not add logic, I/O, or DB access to this record.
 *
 * <h2>Units</h2>
 * <ul>
 *   <li>{@link #livePnl()} and {@link #bookedPnl()} are in <b>NSE index points</b>,
 *       not rupees and not per-lot. Sign: positive means profitable for a short-option
 *       structure (premiums have decayed since entry).</li>
 *   <li>{@link #maxLotCap()} is the total lot count allowed across all active legs
 *       combined, not per leg.</li>
 * </ul>
 */
public record DecisionContext(

        /**
         * UTC instant at which this context was assembled.
         * In simulation mode this is the {@code SimulationClock} time, not wall clock.
         */
        Instant contextTs,

        /**
         * Operating mode for this decision cycle.
         * Propagated from {@code DecisionAgent} configuration; matches the mode
         * written into the resulting {@link DecisionCommand}.
         */
        DecisionCommand.Mode mode,

        /**
         * Scanner candidates ranked by {@code total_score} descending, as returned
         * by {@code MorningScannerService}.
         * Disqualified candidates appear at the bottom of the list with
         * {@code disqualifierReason} populated and all scores zero.
         * Never null — empty list when no contracts are available.
         */
        List<CandidateOpportunity> rankedCandidates,

        /**
         * Signal snapshots keyed by {@code instrument_id}.
         * Contains one entry per instrument for which a signal could be computed.
         * Missing instruments are absent from the map (not represented as null values).
         * Never null — empty map when no signals are available.
         */
        Map<String, SignalSnapshot> signalSnapshots,

        /**
         * The currently active position session, if any.
         * {@link Optional#empty()} when no session is open (pre-entry state).
         * Present when a short-option structure is active and being monitored.
         */
        Optional<PositionSessionSnapshot> activeSession,

        /**
         * Unrealised PnL of the active session at context assembly time.
         * Units: NSE index points (not rupees).
         * Positive means the structure is profitable (premiums have decayed).
         * Zero when no session is active.
         */
        double livePnl,

        /**
         * Cumulative realised (booked) PnL from all prior closed legs and sessions
         * within the current trading day.
         * Units: NSE index points (not rupees).
         * Zero at the start of the day; increases as profit-booking actions complete.
         */
        double bookedPnl,

        /**
         * Compatibility snapshot retained for audit/backward compatibility.
         * Runtime Risk Guard enforcement is disabled.
         */
        RiskGuardSnapshot riskGuardSnapshot,

        /**
         * Maximum total lot count allowed across all active legs combined.
         * Sourced from agent configuration (not from the position session).
         * The Decision Policy must not recommend {@code ENTER} or {@code ADD} if
         * doing so would cause the active lot count to exceed this cap.
         */
        int maxLotCap,

        /**
         * {@code true} when the cooldown period after a strike shift or adjustment
         * is still active for one or more legs.
         * When {@code true}, the Decision Policy must not recommend {@code ADD},
         * {@code SHIFT_STRIKE}, or {@code ENTER} for the cooled-down leg.
         */
        boolean cooldownActive,

        /**
         * {@code true} when adjustment churn has been detected by upstream context
         * assembly logic. Exposed for policy decisions independent of Risk Guard.
         */
        boolean churnGuardActive

) {

    /**
     * Compact constructor enforces null-safety and defensive copies for all
     * collection and optional fields.
     */
    public DecisionContext {
        if (contextTs == null) throw new IllegalArgumentException("contextTs must not be null");
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        if (rankedCandidates == null) throw new IllegalArgumentException("rankedCandidates must not be null");
        if (signalSnapshots == null) throw new IllegalArgumentException("signalSnapshots must not be null");
        if (activeSession == null) throw new IllegalArgumentException("activeSession must not be null (use Optional.empty())");
        if (riskGuardSnapshot == null) throw new IllegalArgumentException("riskGuardSnapshot must not be null");
        // Defensive unmodifiable copies
        rankedCandidates = List.copyOf(rankedCandidates);
        signalSnapshots = Map.copyOf(signalSnapshots);
    }
}
