package com.strategysquad.research;

/**
 * Historical forward-outcome summary for a resolved cohort.
 */
public record ForwardOutcomeSnapshot(
        CanonicalCohortKey cohort,
        long observationCount,
        long nextDayObservationCount,
        long expiryObservationCount,
        OutcomeHorizon nextDay,
        OutcomeHorizon expiry,
        String opportunityLabel,
        String opportunityInterpretation
) {
    public record OutcomeHorizon(
            double medianReturnPct,
            double meanReturnPct,
            double expandProbabilityPct,
            double flatProbabilityPct,
            double decayProbabilityPct
    ) {
    }
}
