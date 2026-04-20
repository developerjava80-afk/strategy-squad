package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomicMetricsTransformerTest {
    @Test
    void sellerEconomicMetricsExposeCreditsAsPositiveAndKeepEconomicPercentileAligned() {
        RawStrategyMetrics raw = rawMetrics(
                "SHORT_STRADDLE",
                "SELLER",
                140,
                -120.0,
                new RawStrategyMetrics.Summary(-100.0, -95.0, 81, -20.0, -35.0, 28.0, 22.0, 68.0, 70.0, -35.0, 2.0, 36.8),
                List.of(new RawStrategyMetrics.PremiumWindow("1Y", -90.0, -88.0, 76, -30.0, 140)),
                new RawStrategyMetrics.ExpiryOutcome(-40.0, 28.0, -28.0, 68.0, 32.0, -55.0, "seller-side tail -55.00 pts", 36.8),
                new RawStrategyMetrics.TheoreticalBounds(120.0, null, "Unlimited risk", 140.0, -95.0),
                List.of(new RawStrategyMetrics.HistoricalCase("2025-01-01", "2025-01-08", -110.0, -25.0, 85.0, -85.0, 85.0))
        );

        EconomicMetrics economic = EconomicMetricsTransformer.transform(raw, EconomicMetricsTransformer.emptyRecommendationContext());

        assertEquals(120.0, economic.premium().currentPremiumPoints(), 0.0001);
        assertEquals(100.0, economic.premium().averageEntryPoints(), 0.0001);
        assertEquals(81, economic.premium().rawPricePercentile());
        assertEquals(81, economic.premium().economicPercentile());
        assertEquals("Supportive seller setup", economic.premium().attractivenessLabel());
        assertEquals(40.0, economic.expiry().averageExpiryPayoutPoints(), 0.0001);
        assertEquals(28.0, economic.expiry().selectedSideAveragePnlPoints(), 0.0001);
        assertEquals(30.0, economic.timeframeTrend().windows().get(0).currentVsAveragePoints(), 0.0001);
        assertEquals(110.0, economic.historicalCases().get(0).entryPremiumPoints(), 0.0001);
        assertEquals("Raw historical sample extreme", economic.risk().historicalExtremesLabel());
        assertFalse(economic.observation().lowSampleDowngrade());
    }

    @Test
    void buyerEconomicMetricsInvertAttractivenessWithoutReusingSellerRichness() {
        RawStrategyMetrics raw = rawMetrics(
                "LONG_STRADDLE",
                "BUYER",
                140,
                120.0,
                new RawStrategyMetrics.Summary(100.0, 95.0, 80, 20.0, 165.0, -12.0, -10.0, 38.0, 92.0, -48.0, 1.9, -8.0),
                List.of(new RawStrategyMetrics.PremiumWindow("1Y", 90.0, 88.0, 90, 30.0, 140)),
                new RawStrategyMetrics.ExpiryOutcome(160.0, 12.0, -12.0, 62.0, 38.0, -80.0, "buyer-side tail -80.00 pts", -8.0),
                new RawStrategyMetrics.TheoreticalBounds(null, -120.0, "Unlimited upside", 180.0, -120.0),
                List.of()
        );

        EconomicMetrics economic = EconomicMetricsTransformer.transform(raw, EconomicMetricsTransformer.emptyRecommendationContext());

        assertEquals(120.0, economic.premium().currentPremiumPoints(), 0.0001);
        assertEquals(80, economic.premium().rawPricePercentile());
        assertEquals(21, economic.premium().economicPercentile());
        assertEquals("Above average buyer cost", economic.premium().attractivenessLabel());
        assertEquals(120.0, economic.risk().currentTheoreticalMaxLossPoints(), 0.0001);
        assertEquals(38.0, economic.expiry().selectedSideWinRatePct(), 0.0001);
        assertEquals(11, economic.timeframeTrend().windows().get(0).economicPercentile());
    }

    @Test
    void recommendationCandidateUsesEconomicMetricsAndDowngradesLowSampleHistory() {
        RawStrategyMetrics raw = rawMetrics(
                "SHORT_STRANGLE",
                "SELLER",
                18,
                -85.0,
                new RawStrategyMetrics.Summary(-80.0, -79.0, 76, -5.0, -42.0, 18.0, 12.0, 61.0, 54.0, -28.0, 1.9, 22.0),
                List.of(new RawStrategyMetrics.PremiumWindow("1Y", -76.0, -74.0, 72, -9.0, 18)),
                new RawStrategyMetrics.ExpiryOutcome(-42.0, 18.0, -18.0, 61.0, 39.0, -90.0, "seller-side tail -90.00 pts", 22.0),
                new RawStrategyMetrics.TheoreticalBounds(85.0, null, "Unlimited risk", 110.0, -95.0),
                List.of()
        );

        EconomicMetrics.RecommendationCandidate candidate =
                EconomicMetricsTransformer.toRecommendationCandidate("SHORT_STRANGLE", "SELLER", raw);

        assertEquals(76, candidate.rawPricePercentile());
        assertEquals(76, candidate.economicPercentile());
        assertTrue(candidate.lowSampleWarning().contains("Low sample warning"));
        assertEquals("Low-confidence candidate", candidate.verdict());
        assertTrue(candidate.reason().contains("Strong") || candidate.reason().contains("Sparse") || candidate.reason().contains("Mixed"));
    }

    private static RawStrategyMetrics rawMetrics(
            String mode,
            String orientation,
            long observationCount,
            double currentNetPremium,
            RawStrategyMetrics.Summary summary,
            List<RawStrategyMetrics.PremiumWindow> windows,
            RawStrategyMetrics.ExpiryOutcome expiryOutcome,
            RawStrategyMetrics.TheoreticalBounds bounds,
            List<RawStrategyMetrics.HistoricalCase> historicalCases
    ) {
        return new RawStrategyMetrics(
                mode,
                orientation,
                "1Y",
                "2025-03-28",
                observationCount,
                currentNetPremium,
                currentNetPremium < 0 ? "NET_CREDIT" : "NET_DEBIT",
                summary,
                windows,
                expiryOutcome,
                bounds,
                historicalCases
        );
    }
}
