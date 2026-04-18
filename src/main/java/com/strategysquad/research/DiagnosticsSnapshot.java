package com.strategysquad.research;

import java.util.List;

/**
 * Real-data diagnostics and transparency payload for the research console.
 */
public record DiagnosticsSnapshot(
        CanonicalCohortKey cohort,
        long observationCount,
        int uniqueInstrumentCount,
        int uniqueTradeDateCount,
        double concentrationPct,
        double nextDayCoveragePct,
        double expiryCoveragePct,
        String confidenceLevel,
        String confidenceText,
        String concentrationLabel,
        String concentrationText,
        String sparsityLabel,
        String sparsityText,
        List<String> warnings,
        List<String> comparabilityReasons,
        List<HistoricalCaseMatch> cases
) {
    public record HistoricalCaseMatch(
            String tradeDate,
            double matchScore,
            String context,
            double entryPrice,
            Double nextDayReturnPct,
            Double expiryReturnPct,
            String whyComparable
    ) {
    }
}
