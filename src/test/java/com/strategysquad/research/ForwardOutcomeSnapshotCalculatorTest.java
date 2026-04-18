package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForwardOutcomeSnapshotCalculatorTest {
    @Test
    void marksLongPremiumFavoredWhenExpansionDominates() {
        ForwardOutcomeSnapshot snapshot = ForwardOutcomeSnapshotCalculator.calculate(
                new CanonicalCohortKey("NIFTY", "CE", 100, 100, 1500),
                List.of(8.0, 6.0, 4.0, 7.0, 5.0, 9.0, 3.0, 2.0, 11.0, 10.0,
                        5.0, 4.0, 6.0, 7.0, 3.0, 8.0, 9.0, 5.0, 4.0, 6.0,
                        8.0, 7.0, 5.0, 3.0, 4.0, 6.0, 7.0, 9.0, 10.0, 5.0,
                        4.0, 6.0, 7.0, 8.0, 5.0, 4.0, 6.0, 7.0, 8.0, 9.0),
                List.of(12.0, 10.0, 7.0, 8.0, 6.0, 9.0, 5.0, 4.0, 11.0, 13.0,
                        7.0, 8.0, 9.0, 6.0, 5.0, 10.0, 11.0, 7.0, 8.0, 9.0,
                        12.0, 10.0, 8.0, 7.0, 6.0, 11.0, 9.0, 10.0, 12.0, 8.0,
                        7.0, 9.0, 10.0, 11.0, 8.0, 7.0, 9.0, 10.0, 11.0, 12.0)
        );

        assertEquals("Long premium favored", snapshot.opportunityLabel());
    }

    @Test
    void marksNoClearEdgeWhenSamplesAreThin() {
        ForwardOutcomeSnapshot snapshot = ForwardOutcomeSnapshotCalculator.calculate(
                new CanonicalCohortKey("NIFTY", "CE", 100, 100, 1500),
                List.of(5.0, -2.0, 4.0),
                List.of(-8.0, 9.0, -3.0)
        );

        assertEquals("No clear edge", snapshot.opportunityLabel());
    }
}
