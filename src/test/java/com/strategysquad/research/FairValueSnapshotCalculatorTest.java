package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FairValueSnapshotCalculatorTest {
    @Test
    void calculatesDistributionAndCurrentPercentile() {
        FairValueSnapshot snapshot = FairValueSnapshotCalculator.calculate(
                new CanonicalCohortKey("NIFTY", "CE", 100, 100, 1500),
                List.of(100.0, 110.0, 120.0, 130.0, 140.0),
                135.0
        );

        assertEquals(5, snapshot.observationCount());
        assertEquals("Sparse", snapshot.cohortStrength());
        assertEquals(120.0, snapshot.distribution().median(), 0.0001);
        assertEquals(120.0, snapshot.distribution().mean(), 0.0001);
        assertEquals(80, snapshot.currentPrice().percentile());
        assertEquals("Rich", snapshot.currentPrice().label());
    }

    @Test
    void returnsSparseSnapshotWhenNoHistoryExists() {
        FairValueSnapshot snapshot = FairValueSnapshotCalculator.calculate(
                new CanonicalCohortKey("NIFTY", "CE", 100, 100, 1500),
                List.of(),
                135.0
        );

        assertEquals(0, snapshot.observationCount());
        assertEquals("Sparse", snapshot.cohortStrength());
        assertEquals("Sparse", snapshot.currentPrice().label());
    }
}
