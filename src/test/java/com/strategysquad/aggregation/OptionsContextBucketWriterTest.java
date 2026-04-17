package com.strategysquad.aggregation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptionsContextBucketWriterTest {

    @Test
    void writesCanonicalColumnOrder() throws Exception {
        AggregationJdbcTestSupport.PreparedStatementRecorder statementRecorder =
                new AggregationJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        AggregationJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new AggregationJdbcTestSupport.ConnectionRecorder(statementRecorder.proxy(), true);
        OptionsContextBucket bucket = new OptionsContextBucket(
                Instant.parse("2026-04-30T08:30:00Z"),
                "NIFTY",
                "CE",
                6,
                50,
                new BigDecimal("105.25"),
                new BigDecimal("0.00479"),
                new BigDecimal("250"),
                2
        );

        int inserted = new OptionsContextBucketWriter().write(connectionRecorder.proxy(), List.of(bucket));

        assertEquals(1, inserted);
        assertEquals(List.of(Map.ofEntries(
                Map.entry(1, Timestamp.from(bucket.bucketTs())),
                Map.entry(2, "NIFTY"),
                Map.entry(3, "CE"),
                Map.entry(4, 6),
                Map.entry(5, 50),
                Map.entry(6, new BigDecimal("105.25")),
                Map.entry(7, new BigDecimal("0.00479")),
                Map.entry(8, new BigDecimal("250")),
                Map.entry(9, 2L)
        )), statementRecorder.batchParameters());
    }

    @Test
    void returnsZeroForEmptyInput() throws Exception {
        AggregationJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new AggregationJdbcTestSupport.ConnectionRecorder(null, true);

        assertEquals(0, new OptionsContextBucketWriter().write(connectionRecorder.proxy(), List.of()));
    }
}
