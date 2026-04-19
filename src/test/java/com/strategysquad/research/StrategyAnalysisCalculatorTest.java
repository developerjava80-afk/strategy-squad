package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyAnalysisCalculatorTest {
    @Test
    void calculatesStructureLevelMetricsAndPremiumWindows() {
        StrategyAnalysisSnapshot snapshot = StrategyAnalysisCalculator.calculate(
                "SHORT_STRADDLE",
                "SELLER",
                "1Y",
                120.0,
                List.of(
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 9), 120.0, 40.0, 80.0, -80.0, 80.0),
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 2, 2), LocalDate.of(2025, 2, 9), 130.0, 160.0, -30.0, 30.0, -30.0),
                        new StrategyAnalysisCalculator.StrategyScenario(LocalDate.of(2025, 3, 2), LocalDate.of(2025, 3, 9), 110.0, 20.0, 90.0, -90.0, 90.0)
                ),
                List.of(
                        new StrategyAnalysisSnapshot.StrategyRecommendation("SHORT_STRADDLE", "SELLER", 12.0, 3, 10.0, 46.0, 66.0, 20.0, "Preferred"),
                        new StrategyAnalysisSnapshot.StrategyRecommendation("IRON_CONDOR", "SELLER", 9.0, 3, 5.0, 30.0, 62.0, 10.0, "Alternative"),
                        new StrategyAnalysisSnapshot.StrategyRecommendation("LONG_STRADDLE", "BUYER", -6.0, 3, -10.0, -20.0, 33.0, 40.0, "Avoid")
                )
        );

        assertEquals(3, snapshot.observationCount());
        assertEquals(120.0, snapshot.snapshot().averageEntryPremium(), 0.0001);
        assertEquals(120.0, snapshot.snapshot().medianEntryPremium(), 0.0001);
        assertEquals(46.6666, snapshot.snapshot().averagePnl(), 0.001);
        assertEquals(66.6666, snapshot.snapshot().winRatePct(), 0.001);
        assertEquals(90.0, snapshot.snapshot().bestCase(), 0.0001);
        assertEquals(-30.0, snapshot.snapshot().worstCase(), 0.0001);
        assertEquals(73.3333, snapshot.expiryOutcome().averageExpiryPayout(), 0.001);
        assertEquals(6, snapshot.premiumWindows().size());
        assertEquals("SHORT_STRADDLE", snapshot.recommendation().preferred().mode());
        assertEquals("LONG_STRADDLE", snapshot.recommendation().avoid().mode());
    }
}
