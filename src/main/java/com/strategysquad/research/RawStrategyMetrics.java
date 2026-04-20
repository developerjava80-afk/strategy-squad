package com.strategysquad.research;

import java.util.List;

/**
 * Internal signed strategy-analysis output. This model must not be used directly by UI,
 * recommendation copy, CSV export, or report rendering.
 */
public record RawStrategyMetrics(
        String mode,
        String orientation,
        String timeframe,
        String anchorDate,
        long observationCount,
        double currentNetPremium,
        String premiumType,
        Summary snapshot,
        List<PremiumWindow> premiumWindows,
        ExpiryOutcome expiryOutcome,
        TheoreticalBounds bounds,
        List<HistoricalCase> historicalCases
) {
    public RawStrategyMetrics {
        premiumWindows = List.copyOf(premiumWindows);
        historicalCases = List.copyOf(historicalCases);
    }

    public record Summary(
            double averageNetPremium,
            double medianNetPremium,
            int currentNetPremiumPercentile,
            double currentVsHistoricalAvgPts,
            double averageNetExpiryValue,
            double averagePnl,
            double medianPnl,
            double winRatePct,
            double avgWinPnl,
            double avgLossPnl,
            double payoffRatio,
            double expectancy
    ) {
    }

    public record TheoreticalBounds(
            Double theoreticalMaxProfit,
            Double theoreticalMaxLoss,
            String boundsLabel,
            double historicalBestPnl,
            double historicalWorstPnl
    ) {
    }

    public record PremiumWindow(
            String label,
            double averageNetPremium,
            double medianNetPremium,
            int currentNetPremiumPercentile,
            double currentVsWindowAvgPts,
            long observationCount
    ) {
    }

    public record ExpiryOutcome(
            double averageNetExpiryPayout,
            double averageSellerPnl,
            double averageBuyerPnl,
            double sellerWinRatePct,
            double buyerWinRatePct,
            double tailLossP10,
            String downsideProfile,
            double expectancy
    ) {
    }

    public record HistoricalCase(
            String tradeDate,
            String expiryDate,
            double netEntryPremium,
            double netExpiryValue,
            double selectedPnl,
            double buyerPnl,
            double sellerPnl
    ) {
    }
}
