package com.strategysquad.research;

/**
 * Historical valuation snapshot returned to the research console.
 */
public record FairValueSnapshot(
        CanonicalCohortKey cohort,
        long observationCount,
        String cohortStrength,
        DistributionStats distribution,
        CurrentPricePosition currentPrice
) {
    public record DistributionStats(
            double min,
            double p10,
            double p25,
            double median,
            double mean,
            double p75,
            double p90,
            double max
    ) {
    }

    public record CurrentPricePosition(
            double optionPrice,
            int percentile,
            String label,
            String interpretation
    ) {
    }
}
