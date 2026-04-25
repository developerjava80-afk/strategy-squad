package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEconomicMetricsTransformerTest {
    @Test
    void sellerStructureScalesEntryPremiumAndLivePnlByConfiguredLots() {
        StrategyStructureDefinition definition = new StrategyStructureDefinition(
                "SHORT_STRADDLE",
                "SELLER",
                "NIFTY",
                "WEEKLY",
                4,
                new BigDecimal("22480"),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("ATM call", "CE", "SHORT", new BigDecimal("22500"), new BigDecimal("140"), 130),
                        new StrategyStructureDefinition.StrategyLeg("ATM put", "PE", "SHORT", new BigDecimal("22500"), new BigDecimal("126"), 130)
                )
        );
        LiveMarketService.LiveStructureSnapshot liveStructure = new LiveMarketService.LiveStructureSnapshot(
                "short-straddle",
                "SHORT_STRADDLE",
                "SELLER",
                "NIFTY",
                "WEEKLY",
                "LIVE_MARKET",
                new BigDecimal("22492"),
                Instant.now(),
                null,
                "LIVE_LTP",
                "kite",
                false,
                "Live spot",
                new BigDecimal("472"),
                "Net credit",
                65,
                null,
                null,
                null,
                false,
                Instant.now(),
                List.of()
        );
        EconomicMetrics historical = sellerHistoricalMetrics();

        LiveEconomicMetrics live = LiveEconomicMetricsTransformer.transform(definition, liveStructure, historical);

        assertEquals(532.0, live.premium().entryPremiumPoints(), 0.0001);
        assertEquals(472.0, live.premium().livePremiumPoints(), 0.0001);
        assertEquals(60.0, live.pnl().livePnlPoints(), 0.0001);
        assertEquals(3900.0, live.pnl().livePnlRupees(), 0.0001);
        assertEquals("Strong", live.confidence().effectiveConfidenceLevel());
        assertTrue(live.confidence().feedReliable());
    }

    @Test
    void buyerStructureUsesBaseLotSizeForRupeePnlAndDowngradesOnStalePartialFeed() {
        StrategyStructureDefinition definition = new StrategyStructureDefinition(
                "LONG_STRADDLE",
                "BUYER",
                "NIFTY",
                "WEEKLY",
                4,
                new BigDecimal("22480"),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("ATM call", "CE", "LONG", new BigDecimal("22500"), new BigDecimal("140"), 65),
                        new StrategyStructureDefinition.StrategyLeg("ATM put", "PE", "LONG", new BigDecimal("22500"), new BigDecimal("126"), 65)
                )
        );
        LiveMarketService.LiveStructureSnapshot liveStructure = new LiveMarketService.LiveStructureSnapshot(
                "long-straddle",
                "LONG_STRADDLE",
                "BUYER",
                "NIFTY",
                "WEEKLY",
                "POST_CLOSE",
                new BigDecimal("22492"),
                Instant.now(),
                null,
                "PRE_CLOSE_LAST_TRADE",
                "historical_db",
                true,
                "Official close unavailable; using last valid trade before close",
                new BigDecimal("292"),
                "Net debit",
                65,
                null,
                null,
                null,
                true,
                Instant.now().minusSeconds(90),
                List.of()
        );
        EconomicMetrics historical = buyerHistoricalMetrics();

        LiveEconomicMetrics live = LiveEconomicMetricsTransformer.transform(definition, liveStructure, historical);

        assertEquals(266.0, live.premium().entryPremiumPoints(), 0.0001);
        assertEquals(292.0, live.premium().livePremiumPoints(), 0.0001);
        assertEquals(26.0, live.pnl().livePnlPoints(), 0.0001);
        assertEquals(1690.0, live.pnl().livePnlRupees(), 0.0001);
        assertEquals("Low-confidence read", live.confidence().effectiveConfidenceLevel());
        assertFalse(live.confidence().feedReliable());
    }

    private static EconomicMetrics sellerHistoricalMetrics() {
        RawStrategyMetrics raw = new RawStrategyMetrics(
                "SHORT_STRADDLE",
                "SELLER",
                "1Y",
                "2026-04-21",
                160,
                -236.0,
                "NET_CREDIT",
                new RawStrategyMetrics.Summary(-210.0, -205.0, 84, -26.0, -95.0, 22.0, 18.0, 64.0, 70.0, -42.0, 1.67, 29.0),
                List.of(new RawStrategyMetrics.PremiumWindow("1Y", -210.0, -205.0, 84, -26.0, 160)),
                new RawStrategyMetrics.ExpiryOutcome(-88.0, 22.0, -22.0, 64.0, 36.0, -70.0, "seller-side tail -70.00 pts", 29.0),
                new RawStrategyMetrics.TheoreticalBounds(236.0, null, "Unlimited risk", 110.0, -150.0),
                List.of()
        );
        return EconomicMetricsTransformer.transform(raw, EconomicMetricsTransformer.emptyRecommendationContext());
    }

    private static EconomicMetrics buyerHistoricalMetrics() {
        RawStrategyMetrics raw = new RawStrategyMetrics(
                "LONG_STRADDLE",
                "BUYER",
                "1Y",
                "2026-04-21",
                80,
                292.0,
                "NET_DEBIT",
                new RawStrategyMetrics.Summary(250.0, 246.0, 78, 42.0, 310.0, 18.0, 12.0, 46.0, 96.0, -50.0, 1.92, 15.0),
                List.of(new RawStrategyMetrics.PremiumWindow("1Y", 250.0, 246.0, 78, 42.0, 80)),
                new RawStrategyMetrics.ExpiryOutcome(305.0, -18.0, 18.0, 54.0, 46.0, -88.0, "buyer-side tail -88.00 pts", 15.0),
                new RawStrategyMetrics.TheoreticalBounds(null, -292.0, "Unlimited upside", 220.0, -292.0),
                List.of()
        );
        return EconomicMetricsTransformer.transform(raw, EconomicMetricsTransformer.emptyRecommendationContext());
    }
}
