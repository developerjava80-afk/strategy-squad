package com.strategysquad.derived;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PcrHistoricalWriterTest {

    @Test
    void writesCanonicalColumnOrder() throws Exception {
        DerivedJdbcTestSupport.PreparedStatementRecorder statementRecorder =
                new DerivedJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        DerivedJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new DerivedJdbcTestSupport.ConnectionRecorder(statementRecorder.proxy(), true);

        PcrHistoricalPoint point = new PcrHistoricalPoint(
                Instant.parse("2026-04-17T10:00:00Z"),
                LocalDate.of(2026, 4, 17),
                "NIFTY",
                new BigDecimal("1.25"),
                new BigDecimal("1.10"),
                500,
                400,
                1200,
                1090,
                40
        );

        int inserted = new PcrHistoricalWriter().write(connectionRecorder.proxy(), List.of(point));

        assertEquals(1, inserted);
        assertEquals(List.of(Map.ofEntries(
                Map.entry(1, Timestamp.from(point.bucketTs())),
                Map.entry(2, Date.valueOf(point.tradeDate())),
                Map.entry(3, "NIFTY"),
                Map.entry(4, new BigDecimal("1.25")),
                Map.entry(5, new BigDecimal("1.1")),
                Map.entry(6, 500L),
                Map.entry(7, 400L),
                Map.entry(8, 1200L),
                Map.entry(9, 1090L),
                Map.entry(10, 40L)
        )), statementRecorder.batchParameters());
    }
}
