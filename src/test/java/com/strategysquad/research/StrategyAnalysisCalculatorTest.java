package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyAnalysisCalculatorTest {
    @Test
    void calculatesRawStructureMetricsAndWindows() {
        RawStrategyMetrics snapshot = StrategyAnalysisCalculator.calculate(
                "SHORT_STRADDLE",
                "SELLER",
                "1Y",
                -120.0,
                List.of(
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 9), -120.0, -40.0, 80.0, -80.0, 80.0),
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 2, 2), LocalDate.of(2025, 2, 9), -130.0, -160.0, -30.0, 30.0, -30.0),
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 3, 2), LocalDate.of(2025, 3, 9), -110.0, -20.0, 90.0, -90.0, 90.0)
                ),
                new StrategyAnalysisCalculator.TheoreticalBoundsInput("SHORT_STRADDLE", "SHORT", 0, 90.0, -30.0)
        );

        assertEquals(3, snapshot.observationCount());
        assertEquals("NET_CREDIT", snapshot.premiumType());
        assertEquals("-120.0", String.valueOf(snapshot.currentNetPremium()));
        assertEquals(-120.0, snapshot.snapshot().averageNetPremium(), 0.0001);
        assertEquals(46.6666, snapshot.snapshot().averagePnl(), 0.001);
        assertEquals(66.6666, snapshot.snapshot().winRatePct(), 0.001);
        assertEquals(85.0, snapshot.snapshot().avgWinPnl(), 0.001);
        assertEquals(-30.0, snapshot.snapshot().avgLossPnl(), 0.001);
        assertEquals(46.6666, snapshot.snapshot().expectancy(), 0.1);
        assertEquals(-73.3333, snapshot.expiryOutcome().averageNetExpiryPayout(), 0.001);
        assertEquals(6, snapshot.premiumWindows().size());
        assertEquals(3, snapshot.historicalCases().size());
    }

    @Test
    void emptyScenarioReturnsZeroedRawMetrics() {
        RawStrategyMetrics snapshot = StrategyAnalysisCalculator.calculate(
                "SHORT_STRADDLE",
                "SELLER",
                "1Y",
                -50.0,
                List.of(),
                new StrategyAnalysisCalculator.TheoreticalBoundsInput("SHORT_STRADDLE", "SHORT", 0, 0, 0)
        );

        assertEquals(0, snapshot.observationCount());
        assertEquals(6, snapshot.premiumWindows().size());
        assertEquals(50.0, snapshot.bounds().theoreticalMaxProfit(), 0.0001);
        assertNull(snapshot.bounds().theoreticalMaxLoss());
    }

    @Test
    void definedRiskBoundsForBullCallSpreadRemainRawInternal() {
        RawStrategyMetrics.TheoreticalBounds bounds = StrategyAnalysisCalculator.computeBounds(
                new StrategyAnalysisCalculator.TheoreticalBoundsInput("BULL_CALL_SPREAD", "LONG", 100, 75, -18),
                20.0
        );

        assertEquals(80.0, bounds.theoreticalMaxProfit(), 0.0001);
        assertEquals(-20.0, bounds.theoreticalMaxLoss(), 0.0001);
        assertEquals("Defined risk", bounds.boundsLabel());
    }

    @Test
    void buyerExpiryExpectancyUsesBuyerWinRate() {
        RawStrategyMetrics snapshot = StrategyAnalysisCalculator.calculate(
                "LONG_STRADDLE",
                "BUYER",
                "1Y",
                100.0,
                List.of(
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 9), 90.0, 130.0, 40.0, 40.0, -40.0),
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 2, 2), LocalDate.of(2025, 2, 9), 100.0, 60.0, -40.0, -40.0, 40.0),
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 3, 2), LocalDate.of(2025, 3, 9), 110.0, 180.0, 70.0, 70.0, -70.0)
                ),
                new StrategyAnalysisCalculator.TheoreticalBoundsInput("LONG_STRADDLE", "LONG", 0, 70.0, -40.0)
        );

        assertTrue(snapshot.expiryOutcome().buyerWinRatePct() > 60.0);
        assertEquals(23.3333, snapshot.expiryOutcome().expectancy(), 0.1);
    }
}
