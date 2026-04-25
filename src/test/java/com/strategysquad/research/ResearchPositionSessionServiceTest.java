package com.strategysquad.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ResearchPositionSessionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsPersistedSession() throws IOException {
        ResearchPositionSessionService service = new ResearchPositionSessionService(tempDir);
        PositionSessionSnapshot snapshot = new PositionSessionSnapshot(
                "session-restore",
                "IRON_CONDOR",
                "Iron Condor",
                "SELLER",
                "BANKNIFTY",
                "WEEKLY",
                "1Y",
                2,
                new BigDecimal("48500"),
                30,
                Instant.parse("2026-04-23T09:15:00Z"),
                Instant.parse("2026-04-23T09:15:00Z"),
                Instant.parse("2026-04-23T09:30:00Z"),
                "PARTIALLY_EXITED",
                List.of(new PositionSessionSnapshot.PositionLegSnapshot(
                        "leg-1",
                        "Short call",
                        "CE",
                        "SHORT",
                        new BigDecimal("48700"),
                        "2026-04-30",
                        "BANKNIFTY26APR48700CE",
                        "OPT99",
                        new BigDecimal("210"),
                        60,
                        30,
                        new BigDecimal("450"),
                        "PARTIALLY_EXITED",
                        Instant.parse("2026-04-23T09:15:00Z"),
                        Instant.parse("2026-04-23T09:30:00Z")
                )),
                List.of(new PositionSessionSnapshot.PositionAuditEntry(
                        "audit-1",
                        Instant.parse("2026-04-23T09:30:00Z"),
                        "leg-1",
                        "Short call",
                        "delta_adjustment",
                        60,
                        30,
                        30,
                        new BigDecimal("210"),
                        new BigDecimal("195"),
                        new BigDecimal("450"),
                        new BigDecimal("-0.70"),
                        new BigDecimal("-0.55"),
                        new BigDecimal("-0.40"),
                        1800L,
                        new BigDecimal("1200"),
                        "delta_trend_and_volume_spike",
                        "Delta adjustment applied"
                ))
        );

        service.save(snapshot);
        PositionSessionSnapshot loaded = service.load("session-restore");

        assertNotNull(loaded);
        assertEquals(snapshot.sessionId(), loaded.sessionId());
        assertEquals(snapshot.status(), loaded.status());
        assertEquals(1, loaded.legs().size());
        assertEquals(1, loaded.auditLog().size());
        assertEquals(new BigDecimal("450"), loaded.legs().get(0).bookedPnl());
    }
}
