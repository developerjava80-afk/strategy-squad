package com.strategysquad.ingestion.live;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpotLiveWriterTest {

    @Test
    void writesCanonicalColumnOrder() throws Exception {
        JdbcRecordingSupport.PreparedStatementRecorder statementRecorder =
                new JdbcRecordingSupport.PreparedStatementRecorder(new int[]{1});
        JdbcRecordingSupport.ConnectionRecorder connectionRecorder =
                new JdbcRecordingSupport.ConnectionRecorder(statementRecorder.proxy(), true);

        SpotLiveTick tick = new SpotLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                "NIFTY",
                new BigDecimal("24210.15")
        );

        int inserted = new SpotLiveWriter().write(connectionRecorder.proxy(), List.of(tick));

        assertEquals(1, inserted);
        assertEquals(List.of(Map.of(
                1, Timestamp.from(tick.exchangeTs()),
                2, Timestamp.from(tick.ingestTs()),
                3, "NIFTY",
                4, 24210.15d
        )), statementRecorder.batchParameters());
    }
}
