package com.strategysquad.research;

import java.util.List;

/**
 * Trader-readable, orientation-aware metrics contract for UI, recommendation, CSV, and report use.
 */
public record EconomicMetrics(
        String mode,
        String orientation,
        String timeframe,
        ObservationMetrics observation,
        PremiumMetrics premium,
        ExpiryMetrics expiry,
        PnlMetrics pnl,
        RiskMetrics risk,
        InsightMetrics insight,
        TimeframeTrendMetrics timeframeTrend,
        RecommendationContext recommendation,
        List<HistoricalCase> historicalCases
) {
    public EconomicMetrics {
        historicalCases = List.copyOf(historicalCases);
    }

    public record ObservationMetrics(
            String anchorDate,
            long observationCount,
            String evidenceStrength,
            String lowSampleWarning,
            boolean lowSampleDowngrade
    ) {
    }

    public record PremiumMetrics(
            String currentPremiumLabel,
            String averageEntryLabel,
            String medianEntryLabel,
            double currentPremiumPoints,
            double averageEntryPoints,
            double medianEntryPoints,
            int rawPricePercentile,
            int economicPercentile,
            boolean percentileReliable,
            double currentVsAveragePoints,
            double currentVsAveragePct,
            String priceConditionLabel,
            String attractivenessLabel
    ) {
    }

    public record ExpiryMetrics(
            String averageExpiryValueLabel,
            double averageExpiryValuePoints,
            double averageExpiryPayoutPoints,
            double selectedSideAveragePnlPoints,
            double oppositeSideAveragePnlPoints,
            double selectedSideWinRatePct,
            double oppositeSideWinRatePct,
            String selectedSideLabel
    ) {
    }

    public record PnlMetrics(
            double averagePnlPoints,
            double medianPnlPoints,
            double avgWinPnlPoints,
            double avgLossPnlPoints,
            double payoffRatio,
            double expectancyPoints,
            String expectancyLabel,
            String selectedSideLabel
    ) {
    }

    public record RiskMetrics(
            Double currentTheoreticalMaxProfitPoints,
            Double currentTheoreticalMaxLossPoints,
            String boundsLabel,
            double tailLossP10Points,
            String downsideProfile,
            String lowSampleWarning,
            double historicalBestPnlPoints,
            double historicalWorstPnlPoints,
            String historicalExtremesLabel
    ) {
    }

    public record InsightMetrics(
            String premiumVerdict,
            String premiumDetail,
            String edgeVerdict,
            String edgeDetail,
            String riskVerdict,
            String riskDetail,
            String overallVerdict,
            String overallDetail
    ) {
    }

    public record TimeframeTrendMetrics(
            double currentPremiumPoints,
            List<TimeframeWindow> windows
    ) {
        public TimeframeTrendMetrics {
            windows = List.copyOf(windows);
        }
    }

    public record TimeframeWindow(
            String label,
            double averagePremiumPoints,
            double medianPremiumPoints,
            int rawPricePercentile,
            int economicPercentile,
            boolean percentileReliable,
            double currentVsAveragePoints,
            long observationCount
    ) {
    }

    public record RecommendationContext(
            RecommendationCandidate preferred,
            RecommendationCandidate alternative,
            RecommendationCandidate avoid,
            String contextNote
    ) {
    }

    public record RecommendationCandidate(
            String mode,
            String orientation,
            String title,
            double score,
            long observationCount,
            String lowSampleWarning,
            int rawPricePercentile,
            int economicPercentile,
            double premiumVsAveragePoints,
            double averagePnlPoints,
            double winRatePct,
            double downsideSeverityPoints,
            String verdict,
            String reason
    ) {
    }

    public record HistoricalCase(
            String tradeDate,
            String expiryDate,
            double entryPremiumPoints,
            double expiryValuePoints,
            double selectedSidePnlPoints,
            double buyerPnlPoints,
            double sellerPnlPoints,
            String historicalExtremesLabel
    ) {
    }
}
