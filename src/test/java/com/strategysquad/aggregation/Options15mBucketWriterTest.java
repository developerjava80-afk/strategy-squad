package com.strategysquad.aggregation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Options15mBucketWriterTest {

    @Test
    void writesCanonicalColumnOrder() throws Exception {
        AggregationJdbcTestSupport.PreparedStatementRecorder statementRecorder =
                new AggregationJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        AggregationJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new AggregationJdbcTestSupport.ConnectionRecorder(statementRecorder.proxy(), true);
        Options15mBucket bucket = new Options15mBucket(
                Instant.parse("2026-04-30T08:30:00Z"),
                LocalDate.of(2026, 4, 30),
                "INS_123",
                6,
                new BigDecimal("105.25"),
                new BigDecimal("100.00"),
                new BigDecimal("110.50"),
                500,
                2
        );

        int inserted = new Options15mBucketWriter().write(connectionRecorder.proxy(), List.of(bucket));

        assertEquals(1, inserted);
        assertEquals(List.of(Map.ofEntries(
                Map.entry(1, Timestamp.from(bucket.bucketTs())),
                Map.entry(2, Date.valueOf(bucket.tradeDate())),
                Map.entry(3, "INS_123"),
                Map.entry(4, 6),
                Map.entry(5, new BigDecimal("105.25")),
                Map.entry(6, new BigDecimal("100.00")),
                Map.entry(7, new BigDecimal("110.50")),
                Map.entry(8, 500L),
                Map.entry(9, 2L)
        )), statementRecorder.batchParameters());
    }

    @Test
    void returnsZeroForEmptyInput() throws Exception {
        AggregationJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new AggregationJdbcTestSupport.ConnectionRecorder(null, true);

        assertEquals(0, new Options15mBucketWriter().write(connectionRecorder.proxy(), List.of()));
    }
}
