package com.strategysquad.ingestion.live;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptionsLiveWriterTest {

    @Test
    void writesCanonicalColumnOrder() throws Exception {
        JdbcRecordingSupport.PreparedStatementRecorder statementRecorder =
                new JdbcRecordingSupport.PreparedStatementRecorder(new int[]{1});
        JdbcRecordingSupport.ConnectionRecorder connectionRecorder =
                new JdbcRecordingSupport.ConnectionRecorder(statementRecorder.proxy(), true);

        OptionLiveTick tick = new OptionLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                "NIFTY-20260430-22000-CE",
                "NIFTY",
                new BigDecimal("102.50"),
                new BigDecimal("102.45"),
                new BigDecimal("102.55"),
                125,
                900
        );

        int inserted = new OptionsLiveWriter().write(connectionRecorder.proxy(), List.of(tick));

        assertEquals(1, inserted);
        assertEquals(List.of(Map.of(
                1, Timestamp.from(tick.exchangeTs()),
                2, Timestamp.from(tick.ingestTs()),
                3, "NIFTY-20260430-22000-CE",
                4, "NIFTY",
                5, new BigDecimal("102.50"),
                6, new BigDecimal("102.45"),
                7, new BigDecimal("102.55"),
                8, 125L,
                9, 900L
        )), statementRecorder.batchParameters());
    }
}
