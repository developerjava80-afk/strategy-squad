package com.strategysquad.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyRunReportServiceTest {
    private static final Pattern FILE_PATTERN = Pattern.compile("^strategy-run-\\d{8}-\\d{6}-(live|simulation)\\.md$");

    @TempDir
    Path tempDir;

    @Test
    void writesReportFileWithMetadataTimelineAndPnlSections() throws Exception {
        StrategyRunReportService service = new StrategyRunReportService(tempDir);

        Path written = service.writeReport(sampleRequest("live"));

        assertNotNull(written);
        assertTrue(Files.exists(written));
        assertTrue(FILE_PATTERN.matcher(written.getFileName().toString()).matches());

        String markdown = Files.readString(written);
        assertTrue(markdown.contains("## Run Metadata"));
        assertTrue(markdown.contains("## Adjustment Timeline"));
        assertTrue(markdown.contains("## PnL Summary"));
        assertTrue(markdown.contains("## Final Structure"));
        assertTrue(markdown.contains("run-123"));
        assertTrue(markdown.contains("session-123"));
        assertTrue(markdown.contains("ADD"));
        assertTrue(markdown.contains("REDUCE"));
        assertTrue(markdown.contains("Booked PnL"));
        assertTrue(markdown.contains("Live / unrealized PnL"));
        assertTrue(markdown.contains("Total PnL"));
    }

    @Test
    void writesDistinctReportsForLiveAndSimulationModes() throws Exception {
        StrategyRunReportService service = new StrategyRunReportService(tempDir);

        Path liveReport = service.writeReport(sampleRequest("live"));
        Path simulationReport = service.writeReport(sampleRequest("simulation"));

        assertTrue(liveReport.getFileName().toString().endsWith("-live.md"));
        assertTrue(simulationReport.getFileName().toString().endsWith("-simulation.md"));
        assertTrue(Files.readString(liveReport).contains("- **Mode**: live"));
        assertTrue(Files.readString(simulationReport).contains("- **Mode**: simulation"));
    }

    @Test
    void writeReportSafelyReturnsNullWhenRootCannotBeCreated() throws Exception {
        Path occupiedFile = tempDir.resolve("occupied-file");
        Files.writeString(occupiedFile, "not a directory");
        StrategyRunReportService service = new StrategyRunReportService(occupiedFile);

        Path written = service.writeReportSafely(sampleRequest("simulation"));

        assertNull(written);
        assertEquals("not a directory", Files.readString(occupiedFile));
    }

    private static StrategyRunReportService.StrategyRunReportRequest sampleRequest(String mode) {
        return new StrategyRunReportService.StrategyRunReportRequest(
                "run-123",
                "session-123",
                mode,
                "NIFTY",
                "Short Straddle",
                "2026-04-24T09:15:00Z",
                "2026-04-24T09:32:00Z",
                1_020_000L,
                20,
                65,
                new BigDecimal("24310.25"),
                new BigDecimal("145.50"),
                new BigDecimal("875.00"),
                new BigDecimal("1225.00"),
                new BigDecimal("2100.00"),
                List.of(
                        new StrategyRunReportService.LegSnapshot(
                                "leg-call",
                                "ATM call",
                                "SHORT",
                                "CE",
                                new BigDecimal("24300"),
                                "2026-04-30",
                                new BigDecimal("182.40"),
                                new BigDecimal("176.10"),
                                130,
                                130,
                                new BigDecimal("0.42"),
                                BigDecimal.ZERO,
                                null,
                                BigDecimal.ZERO,
                                "OPEN"
                        ),
                        new StrategyRunReportService.LegSnapshot(
                                "leg-put",
                                "ATM put",
                                "SHORT",
                                "PE",
                                new BigDecimal("24300"),
                                "2026-04-30",
                                new BigDecimal("178.10"),
                                new BigDecimal("165.80"),
                                130,
                                65,
                                new BigDecimal("-0.39"),
                                new BigDecimal("875.00"),
                                new BigDecimal("798.75"),
                                new BigDecimal("1673.75"),
                                "PARTIALLY_EXITED"
                        )
                ),
                List.of(
                        new StrategyRunReportService.LegSnapshot(
                                "leg-call",
                                "ATM call",
                                "SHORT",
                                "CE",
                                new BigDecimal("24300"),
                                "2026-04-30",
                                new BigDecimal("182.40"),
                                new BigDecimal("176.10"),
                                130,
                                65,
                                null,
                                new BigDecimal("420.00"),
                                new BigDecimal("409.50"),
                                new BigDecimal("829.50"),
                                "PARTIALLY_EXITED"
                        ),
                        new StrategyRunReportService.LegSnapshot(
                                "leg-put-added",
                                "ATM put",
                                "SHORT",
                                "PE",
                                new BigDecimal("24250"),
                                "2026-04-30",
                                new BigDecimal("169.80"),
                                new BigDecimal("164.20"),
                                0,
                                65,
                                null,
                                new BigDecimal("455.00"),
                                new BigDecimal("815.50"),
                                new BigDecimal("1270.50"),
                                "OPEN"
                        )
                ),
                List.of(
                        new StrategyRunReportService.TimelineEvent(
                                Instant.parse("2026-04-24T09:20:00Z").toString(),
                                "delta_adjustment",
                                "REDUCE",
                                "leg-call",
                                "ATM call",
                                "CE",
                                "SHORT",
                                new BigDecimal("24300"),
                                130,
                                65,
                                4,
                                3,
                                "HARD",
                                "critical_net_delta",
                                "Reduce short call",
                                "Reduced one short call lot to improve portfolio neutrality.",
                                new BigDecimal("420.00"),
                                new BigDecimal("18.20"),
                                new BigDecimal("15.10"),
                                new BigDecimal("11.80"),
                                new BigDecimal("-120.00"),
                                new BigDecimal("-28.50"),
                                new BigDecimal("-46.25"),
                                new BigDecimal("22.90"),
                                new BigDecimal("19.40"),
                                new BigDecimal("12.50"),
                                new BigDecimal("10.20"),
                                new BigDecimal("12.70"),
                                new BigDecimal("0.55"),
                                "UP",
                                "UNFAVORABLE",
                                Boolean.TRUE,
                                Boolean.FALSE,
                                new BigDecimal("-0.35"),
                                new BigDecimal("0.82"),
                                new BigDecimal("1.48")
                        ),
                        new StrategyRunReportService.TimelineEvent(
                                Instant.parse("2026-04-24T09:27:00Z").toString(),
                                "delta_adjustment",
                                "ADD",
                                "leg-put-added",
                                "ATM put",
                                "PE",
                                "SHORT",
                                new BigDecimal("24250"),
                                0,
                                65,
                                3,
                                4,
                                "NORMAL",
                                "theta_preserving_rebalance",
                                "Add short put",
                                "Added one short put lot to improve neutrality while preserving carry.",
                                BigDecimal.ZERO,
                                new BigDecimal("14.60"),
                                new BigDecimal("12.20"),
                                new BigDecimal("8.40"),
                                new BigDecimal("40.00"),
                                new BigDecimal("18.75"),
                                new BigDecimal("26.00"),
                                new BigDecimal("10.20"),
                                new BigDecimal("8.60"),
                                new BigDecimal("6.20"),
                                new BigDecimal("4.95"),
                                new BigDecimal("5.25"),
                                new BigDecimal("0.38"),
                                "DOWN",
                                "FAVORABLE",
                                Boolean.TRUE,
                                Boolean.FALSE,
                                new BigDecimal("0.72"),
                                new BigDecimal("0.88"),
                                new BigDecimal("1.61")
                        )
                )
        );
    }
}
