package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyAnalysisCalculatorTest {
    @Test
    void calculatesCompactShortPremiumMetrics() {
        StrategyAnalysisSnapshot snapshot = StrategyAnalysisCalculator.calculate(
                "STRADDLE",
                "1Y",
                List.of(
                        new StrategyAnalysisCalculator.StrategyScenario(120.0, 40.0, 80.0),
                        new StrategyAnalysisCalculator.StrategyScenario(130.0, 160.0, -30.0),
                        new StrategyAnalysisCalculator.StrategyScenario(110.0, 20.0, 90.0)
                )
        );

        assertEquals(3, snapshot.observationCount());
        assertEquals(120.0, snapshot.averagePremiumCollected(), 0.0001);
        assertEquals(73.3333, snapshot.averageExpiryValue(), 0.001);
        assertEquals(46.6666, snapshot.averageExpiryPnl(), 0.001);
        assertEquals(66.6666, snapshot.winRatePct(), 0.001);
        assertEquals(90.0, snapshot.maxGain(), 0.0001);
        assertEquals(-30.0, snapshot.maxLoss(), 0.0001);
    }
}
