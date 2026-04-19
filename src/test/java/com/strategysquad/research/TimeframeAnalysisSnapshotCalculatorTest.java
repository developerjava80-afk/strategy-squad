package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeframeAnalysisSnapshotCalculatorTest {
    @Test
    void buildsTrendWindowsAndSelectedSummary() {
        CanonicalCohortKey cohort = new CanonicalCohortKey("NIFTY", "CE", 100, 100, 1500);
        List<TimeframeAnalysisSnapshotCalculator.Observation> observations = List.of(
                new TimeframeAnalysisSnapshotCalculator.Observation(Instant.parse("2021-04-15T09:30:00Z"), "A", 100.0),
                new TimeframeAnalysisSnapshotCalculator.Observation(Instant.parse("2024-11-15T09:30:00Z"), "B", 130.0),
                new TimeframeAnalysisSnapshotCalculator.Observation(Instant.parse("2025-03-15T09:30:00Z"), "C", 140.0),
                new TimeframeAnalysisSnapshotCalculator.Observation(Instant.parse("2025-08-15T09:30:00Z"), "D", 150.0),
                new TimeframeAnalysisSnapshotCalculator.Observation(Instant.parse("2026-03-15T09:30:00Z"), "E", 160.0)
        );

        TimeframeAnalysisSnapshot snapshot = TimeframeAnalysisSnapshotCalculator.calculate(
                cohort,
                observations,
                155.0,
                "1Y",
                null,
                null
        );

        assertEquals("2026-03-15", snapshot.anchorDate());
        assertEquals(6, snapshot.windows().size());
        assertEquals("1Y", snapshot.selectedWindow().label());
        assertEquals(2, snapshot.selectedWindow().observationCount());
        assertEquals(2, snapshot.selectedWindow().uniqueContracts());
        assertEquals(50, snapshot.selectedWindow().percentile());
        assertEquals(155.0 - 155.0, snapshot.selectedWindow().differenceVsCurrent(), 0.0001);
    }

    @Test
    void supportsCustomSelectedWindow() {
        CanonicalCohortKey cohort = new CanonicalCohortKey("NIFTY", "CE", 100, 100, 1500);
        List<TimeframeAnalysisSnapshotCalculator.Observation> observations = List.of(
                new TimeframeAnalysisSnapshotCalculator.Observation(Instant.parse("2025-01-10T09:30:00Z"), "A", 110.0),
                new TimeframeAnalysisSnapshotCalculator.Observation(Instant.parse("2025-01-15T09:30:00Z"), "B", 130.0),
                new TimeframeAnalysisSnapshotCalculator.Observation(Instant.parse("2025-02-20T09:30:00Z"), "C", 170.0)
        );

        TimeframeAnalysisSnapshot snapshot = TimeframeAnalysisSnapshotCalculator.calculate(
                cohort,
                observations,
                135.0,
                "CUSTOM",
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-01-31")
        );

        assertEquals("CUSTOM", snapshot.selectedWindow().label());
        assertEquals(2, snapshot.selectedWindow().observationCount());
        assertEquals(2, snapshot.selectedWindow().uniqueContracts());
        assertEquals(100, snapshot.selectedWindow().percentile());
    }
}
