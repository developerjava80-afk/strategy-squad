package com.strategysquad.research;

import java.util.List;

/**
 * Timeframe-based cohort analysis for the flat algo-testing console.
 */
public record TimeframeAnalysisSnapshot(
        CanonicalCohortKey cohort,
        String anchorDate,
        double currentOptionPrice,
        List<WindowStats> windows,
        WindowStats selectedWindow
) {
    public record WindowStats(
            String label,
            double averagePrice,
            double medianPrice,
            long observationCount,
            long uniqueContracts,
            int percentile,
            double differenceVsCurrent
    ) {
    }
}
