package com.strategysquad.research;

/**
 * Compact strategy-testing metrics for the algo-testing console.
 */
public record StrategyAnalysisSnapshot(
        String mode,
        String timeframe,
        long observationCount,
        double averagePremiumCollected,
        double averageExpiryValue,
        double averageExpiryPnl,
        double winRatePct,
        double maxGain,
        double maxLoss
) {
}
