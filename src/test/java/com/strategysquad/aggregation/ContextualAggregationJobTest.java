package com.strategysquad.aggregation;

import com.strategysquad.enrichment.OptionEnrichedTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextualAggregationJobTest {

    @Test
    void aggregatesEnrichedTicksIntoBothBucketTables() throws Exception {
        TrackingBucketWriter bucketWriter = new TrackingBucketWriter(1);
        TrackingContextWriter contextWriter = new TrackingContextWriter(1);

        ContextualAggregationJob.AggregationResult result = new ContextualAggregationJob(
                new Options15mBucketAggregator(),
                new OptionsContextBucketAggregator(),
                bucketWriter,
                contextWriter
        ).aggregate(connection(), List.of(enrichedTick(), enrichedTick()));

        assertEquals(new ContextualAggregationJob.AggregationResult(1, 1), result);
        assertEquals(1, bucketWriter.calls);
        assertEquals(1, contextWriter.calls);
        assertEquals(1, bucketWriter.lastBucketCount);
        assertEquals(1, contextWriter.lastBucketCount);
    }

    @Test
    void returnsZeroForEmptyInput() throws Exception {
        TrackingBucketWriter bucketWriter = new TrackingBucketWriter(0);
        TrackingContextWriter contextWriter = new TrackingContextWriter(0);

        ContextualAggregationJob.AggregationResult result = new ContextualAggregationJob(
                new Options15mBucketAggregator(),
                new OptionsContextBucketAggregator(),
                bucketWriter,
                contextWriter
        ).aggregate(connection(), List.of());

        assertEquals(new ContextualAggregationJob.AggregationResult(0, 0), result);
        assertEquals(0, bucketWriter.calls);
        assertEquals(0, contextWriter.calls);
    }

    @Test
    void propagatesBucketWriterFailure() {
        TrackingBucketWriter bucketWriter = new TrackingBucketWriter(0);
        bucketWriter.fail = true;
        TrackingContextWriter contextWriter = new TrackingContextWriter(0);

        assertThrows(SQLException.class, () -> new ContextualAggregationJob(
                new Options15mBucketAggregator(),
                new OptionsContextBucketAggregator(),
                bucketWriter,
                contextWriter
        ).aggregate(connection(), List.of(enrichedTick())));

        assertEquals(1, bucketWriter.calls);
        assertEquals(0, contextWriter.calls);
    }

    private static Connection connection() {
        return new AggregationJdbcTestSupport.ConnectionRecorder(null, true).proxy();
    }

    private static OptionEnrichedTick enrichedTick() {
        return new OptionEnrichedTick(
                Instant.parse("2026-04-30T08:30:00Z"),
                "INS_123",
                "NIFTY",
                "CE",
                new BigDecimal("22000"),
                Instant.parse("2026-04-30T10:00:00Z"),
                new BigDecimal("102.50"),
                new BigDecimal("21950"),
                90,
                6,
                new BigDecimal("0.23"),
                new BigDecimal("50"),
                50,
                125
        );
    }

    private static final class TrackingBucketWriter extends Options15mBucketWriter {
        private int calls;
        private int lastBucketCount;
        private boolean fail;
        private final int returnValue;

        private TrackingBucketWriter(int returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public int write(Connection connection, List<Options15mBucket> buckets) throws SQLException {
            calls++;
            lastBucketCount = buckets.size();
            if (fail) {
                throw new SQLException("boom");
            }
            return returnValue;
        }
    }

    private static final class TrackingContextWriter extends OptionsContextBucketWriter {
        private int calls;
        private int lastBucketCount;
        private boolean fail;
        private final int returnValue;

        private TrackingContextWriter(int returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public int write(Connection connection, List<OptionsContextBucket> buckets) throws SQLException {
            calls++;
            lastBucketCount = buckets.size();
            if (fail) {
                throw new SQLException("boom");
            }
            return returnValue;
        }
    }
}
