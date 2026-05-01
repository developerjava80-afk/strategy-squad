package com.strategysquad.research;

import java.util.List;

/**
 * Full response from the Theta + Delta Sense Check.
 *
 * <p>Deliberately contains no Gamma, Vega, IV, or Black-Scholes fields.
 * All averages originate from the historical backend.
 */
public record ThetaDeltaSenseCheckResponse(
        InputEcho input,
        Calculation calculation,
        Signal signal,
        Reliability reliability
) {

    /**
     * Echo of the validated inputs used for this run.
     */
    public record InputEcho(
            String underlying,
            String expiry,
            double strike,
            String optionType,
            String side,
            double entryPremium,
            double currentPremium,
            double entryUnderlying,
            double currentUnderlying,
            int elapsedMinutes,
            /** "live" or "simulation" — for UI display only. */
            String mode,
            /** ISO-8601 date used as reference for DTE bucket computation. */
            String asOf
    ) {}

    /**
     * All calculated values. Signed values preserve direction as required by the spec.
     */
    public record Calculation(
            /** current_underlying - entry_underlying (signed). */
            double underlyingMove,

            /** current_premium - entry_premium (signed). */
            double premiumMove,

            /** premium_move / underlying_move (signed, raw). */
            double observedDelta,

            /** Clamped display value of observed delta. */
            double observedDeltaClamped,

            /** Historical average delta from backend (null when unavailable). */
            Double averageHistoricalDelta,

            /** ((observed - avg) / |avg|) * 100 (null when avg unavailable). */
            Double deltaDeviationPct,

            /** NORMAL / HIGH / VERY_HIGH / LOW / VERY_LOW / NOT_RELIABLE */
            String deltaStatus,

            /** average_historical_delta * underlying_move */
            double expectedDeltaMove,

            /** premium_move - expected_delta_move */
            double residualPremiumMove,

            /** Delta-adjusted empirical theta benefit (sign depends on LONG/SHORT). */
            double thetaBenefit,

            /** theta_benefit / elapsed_minutes */
            double thetaBenefitPerMin,

            /** Historical average theta benefit per minute (null when unavailable). */
            Double averageThetaBenefitPerMin,

            /** ((theta_per_min - avg) / |avg|) * 100 (null when avg unavailable). */
            Double thetaDeviationPct,

            /** NORMAL / HIGH / VERY_HIGH / LOW / VERY_LOW / NOT_RELIABLE */
            String thetaStatus,

            /** Historical bucket used for delta lookup (null when unavailable). */
            HistoricalBucket deltaBucket,

            /** Historical bucket used for theta lookup (null when unavailable). */
            HistoricalBucket thetaBucket,

            /** Sample sizes from historical backend (null fields = unavailable). */
            Integer deltaSampleSize,
            Integer thetaSampleSize
    ) {}

    /**
     * Description of the historical bucket matched for delta or theta lookup.
     */
    public record HistoricalBucket(
            String underlying,
            String optionType,
            String dteBucket,
            String moneynessBucket,
            String timeBucket,
            String matchLevel   // "exact" | "dte+moneyness" | "moneyness_only" | "unavailable"
    ) {}

    /**
     * Opportunity signal derived from combined delta and theta status.
     */
    public record Signal(
            /** ATTRACTIVE / MILDLY_ATTRACTIVE / NO_EDGE / WEAK / UNSTABLE / NOT_RELIABLE */
            String opportunitySignal,

            /** Short trader-readable label. */
            String label,

            /** GREEN / YELLOW / RED / GREY */
            String color,

            /** Plain-English reason behind the signal. */
            String reason
    ) {}

    /**
     * Simple reliability assessment for this run.
     */
    public record Reliability(
            boolean isReliable,
            int reliabilityScore,
            String reliabilityLabel,   // Reliable / Usable / Weak / Not Reliable
            List<String> warnings
    ) {}
}
