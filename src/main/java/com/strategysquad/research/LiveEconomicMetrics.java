package com.strategysquad.research;

/**
 * Trader-readable live overlay metrics for the currently selected structure.
 *
 * <p>This model keeps live mark-to-market semantics separate from canonical
 * historical truth while exposing one-lot current-trade-comparable outputs for the UI.
 */
public record LiveEconomicMetrics(
        LotMetrics lot,
        PremiumMetrics premium,
        PnlMetrics pnl,
        ConfidenceMetrics confidence
) {
    public record LotMetrics(
            int lotSize,
            String scopeLabel
    ) {
    }

    public record PremiumMetrics(
            String entryPremiumLabel,
            String livePremiumLabel,
            double entryPremiumPoints,
            double livePremiumPoints,
            double liveVsEntryPoints,
            double liveVsEntryPct
    ) {
    }

    public record PnlMetrics(
            String selectedSideLabel,
            double livePnlPoints,
            double livePnlRupees,
            String livePnlLabel,
            String markState
    ) {
    }

    public record ConfidenceMetrics(
            String baseEvidenceStrength,
            String effectiveConfidenceLevel,
            boolean feedReliable,
            String liveAdjustmentLabel,
            String confidenceDetail
    ) {
    }
}
