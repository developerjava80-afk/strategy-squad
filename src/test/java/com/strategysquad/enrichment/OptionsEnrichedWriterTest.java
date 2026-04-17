package com.strategysquad.enrichment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptionsEnrichedWriterTest {

    @Test
    void writesCanonicalColumnOrder() throws Exception {
        EnrichmentJdbcTestSupport.PreparedStatementRecorder statementRecorder =
                new EnrichmentJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        EnrichmentJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new EnrichmentJdbcTestSupport.ConnectionRecorder(statementRecorder.proxy(), true);
        OptionEnrichedTick tick = new OptionEnrichedTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                "INS_123",
                "NIFTY",
                "CE",
                new BigDecimal("22000"),
                Instant.parse("2026-04-30T15:30:00Z"),
                new BigDecimal("102.50"),
                new BigDecimal("24210.15"),
                1234,
                82,
                new BigDecimal("-9.13155408"),
                new BigDecimal("-2210.15"),
                -2200
        );

        int inserted = new OptionsEnrichedWriter().write(connectionRecorder.proxy(), List.of(tick));

        assertEquals(1, inserted);
        assertEquals(List.of(Map.ofEntries(
                Map.entry(1, Timestamp.from(tick.exchangeTs())),
                Map.entry(2, "INS_123"),
                Map.entry(3, "NIFTY"),
                Map.entry(4, "CE"),
                Map.entry(5, new BigDecimal("22000")),
                Map.entry(6, Timestamp.from(tick.expiryTs())),
                Map.entry(7, new BigDecimal("102.50")),
                Map.entry(8, new BigDecimal("24210.15")),
                Map.entry(9, 1234),
                Map.entry(10, 82),
                Map.entry(11, new BigDecimal("-9.13155408")),
                Map.entry(12, new BigDecimal("-2210.15")),
                Map.entry(13, -2200)
        )), statementRecorder.batchParameters());
    }
}
