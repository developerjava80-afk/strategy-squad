package com.strategysquad.research;

import java.util.List;

/**
 * Compact structure-testing snapshot for the algo-testing console.
 */
public record StrategyAnalysisSnapshot(
        String mode,
        String orientation,
        String timeframe,
        long observationCount,
        double currentTotalPremium,
        Summary snapshot,
        List<PremiumWindow> premiumWindows,
        ExpiryOutcome expiryOutcome,
        RecommendationSummary recommendation,
        List<MatchedCase> matchedCases
) {
    public StrategyAnalysisSnapshot {
        premiumWindows = List.copyOf(premiumWindows);
        matchedCases = List.copyOf(matchedCases);
    }

    public record Summary(
            double averageEntryPremium,
            double medianEntryPremium,
            int currentPremiumPercentile,
            double currentVsHistoricalAverage,
            double averageExpiryValue,
            double averagePnl,
            double medianPnl,
            double winRatePct,
            double bestCase,
            double worstCase
    ) {
    }

    public record PremiumWindow(
            String label,
            double averageTotalPremium,
            double medianPremium,
            double currentVsHistoricalAverage,
            long observationCount
    ) {
    }

    public record ExpiryOutcome(
            double averageExpiryPayout,
            double averageSellerPnl,
            double averageBuyerPnl,
            double winRatePct,
            double tailLossP10,
            String downsideProfile
    ) {
    }

    public record RecommendationSummary(
            StrategyRecommendation preferred,
            StrategyRecommendation alternative,
            StrategyRecommendation avoid
    ) {
    }

    public record StrategyRecommendation(
            String mode,
            String orientation,
            double score,
            long observationCount,
            double premiumVsHistory,
            double averagePnl,
            double winRatePct,
            double downsideSeverity,
            String reason
    ) {
    }

    public record MatchedCase(
            String tradeDate,
            String expiryDate,
            double totalEntryPremium,
            double expiryValue,
            double selectedPnl,
            double buyerPnl,
            double sellerPnl
    ) {
    }
}
