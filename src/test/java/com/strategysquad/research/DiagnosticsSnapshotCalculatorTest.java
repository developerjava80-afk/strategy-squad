package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DiagnosticsSnapshotCalculatorTest {
    @Test
    void calculatesDiagnosticsAndRepresentativeCases() {
        DiagnosticsSnapshot snapshot = DiagnosticsSnapshotCalculator.calculate(
                new CanonicalCohortKey("NIFTY", "CE", 100, 100, 1500),
                145.0,
                List.of(
                        new MatchedHistoricalObservation("A", LocalDate.of(2024, 2, 14), 144.0, 5.0, -10.0),
                        new MatchedHistoricalObservation("B", LocalDate.of(2024, 2, 15), 147.0, 2.0, -8.0),
                        new MatchedHistoricalObservation("C", LocalDate.of(2024, 2, 16), 149.0, null, -6.0),
                        new MatchedHistoricalObservation("D", LocalDate.of(2024, 2, 16), 143.0, 1.0, null)
                )
        );

        assertEquals(4, snapshot.observationCount());
        assertEquals(4, snapshot.uniqueInstrumentCount());
        assertEquals(3, snapshot.uniqueTradeDateCount());
        assertEquals("Weak", snapshot.confidenceLevel());
        assertFalse(snapshot.cases().isEmpty());
    }
}
