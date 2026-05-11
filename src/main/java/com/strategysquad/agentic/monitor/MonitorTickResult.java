package com.strategysquad.agentic.monitor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of one 15-second monitor tick.
 *
 * <p>Produced by {@link AdjustmentMonitorLoop} each cycle. Held in the loop's
 * in-memory ring buffer (last 100 rows) and served by the REST endpoints.
 */
public record MonitorTickResult(

        /** UTC instant this tick was evaluated. */
        Instant tickTs,

        /** Position session ID that was evaluated, or null when no session is open. */
        String sessionId,

        /** Underlying index (NIFTY / BANKNIFTY), or null when no session is open. */
        String underlying,

        /** Live spot price at tick time, or null if unavailable. */
        BigDecimal spotPrice,

        /** Net signed delta across all short legs (positive = CE stressed, negative = PE stressed). */
        BigDecimal netDelta2m,

        /** Per-leg signal snapshots at this tick. */
        List<LegSignal> legs,

        /** Active signal flags that fired this tick (may be empty for HOLD). */
        List<String> signalFlags,

        /**
         * Decision produced by the rule engine.
         * One of: HOLD, REDUCE, COOLDOWN_ACTIVE, MIN_LOTS_FLOOR, NO_SESSION, NO_DATA
         */
        String decision,

        /** Machine-readable reason code for the decision. */
        String reasonCode,

        /** Human-readable explanation. */
        String explanation,

        /** True when a REDUCE action was actually applied to the session this tick. */
        boolean actionTaken,

        /** The leg label that was reduced, or null when no action was taken. */
        String reducedLegLabel,

        /** Lots before the reduce, or null when no action was taken. */
        Integer lotsBefore,

        /** Lots after the reduce, or null when no action was taken. */
        Integer lotsAfter

) {

    public MonitorTickResult {
        legs = legs == null ? List.of() : List.copyOf(legs);
        signalFlags = signalFlags == null ? List.of() : List.copyOf(signalFlags);
    }

    /**
     * Signal snapshot for one leg at a single tick.
     */
    public record LegSignal(

            /** Leg identifier. */
            String legId,

            /** Human-readable label (e.g. "Short CE 24800"). */
            String label,

            /** CE or PE. */
            String optionType,

            /** Strike price in index points. */
            BigDecimal strike,

            /** Open lots remaining on this leg. */
            int openQty,

            /** Last traded price of this option in index points, or null. */
            BigDecimal lastPrice,

            /**
             * Empirical delta over the 2-minute window.
             * Positive for CE (option rises when market rises).
             * Null when insufficient tick data.
             */
            BigDecimal delta2m,

            /**
             * Delta-adjusted theta benefit over the 2-minute window (index points).
             * Positive = option decaying faster than delta explains (good for seller).
             * Negative = premium expanding adversely.
             * Null when insufficient data.
             */
            BigDecimal deltaAdjustedTheta2m,

            /**
             * Theta progress ratio: fraction of entry premium captured as decay.
             * Range 0.0–1.0+. Null when no entry price available.
             */
            BigDecimal thetaProgressRatio,

            /**
             * Absolute distance between strike and current spot (index points).
             * Lower = closer to ITM. Sourced from options_live_enriched.moneyness_points.
             */
            BigDecimal moneynessPoints,

            /** Signal flags active on this leg (subset of: DELTA_HIGH, NEAR_ITM, THETA_ADVERSE). */
            List<String> legSignalFlags

    ) {
        public LegSignal {
            legSignalFlags = legSignalFlags == null ? List.of() : List.copyOf(legSignalFlags);
        }
    }
}
