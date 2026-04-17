package com.strategysquad.aggregation;

import com.strategysquad.enrichment.OptionEnrichedTick;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates contextual aggregation: enriched ticks → bucket aggregates → write.
 * Produces both {@code options_15m_buckets} and {@code options_context_buckets} rows.
 */
public class ContextualAggregationJob {
    private final Options15mBucketAggregator bucketAggregator;
    private final OptionsContextBucketAggregator contextAggregator;
    private final Options15mBucketWriter bucketWriter;
    private final OptionsContextBucketWriter contextWriter;

    public ContextualAggregationJob() {
        this(
                new Options15mBucketAggregator(),
                new OptionsContextBucketAggregator(),
                new Options15mBucketWriter(),
                new OptionsContextBucketWriter()
        );
    }

    public ContextualAggregationJob(
            Options15mBucketAggregator bucketAggregator,
            OptionsContextBucketAggregator contextAggregator,
            Options15mBucketWriter bucketWriter,
            OptionsContextBucketWriter contextWriter
    ) {
        this.bucketAggregator = Objects.requireNonNull(bucketAggregator, "bucketAggregator must not be null");
        this.contextAggregator = Objects.requireNonNull(contextAggregator, "contextAggregator must not be null");
        this.bucketWriter = Objects.requireNonNull(bucketWriter, "bucketWriter must not be null");
        this.contextWriter = Objects.requireNonNull(contextWriter, "contextWriter must not be null");
    }

    public AggregationResult aggregate(Connection connection, List<OptionEnrichedTick> enrichedTicks) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(enrichedTicks, "enrichedTicks must not be null");
        if (enrichedTicks.isEmpty()) {
            return new AggregationResult(0, 0);
        }

        List<Options15mBucket> timeBuckets = bucketAggregator.aggregate(enrichedTicks);
        List<OptionsContextBucket> contextBuckets = contextAggregator.aggregate(enrichedTicks);

        int timeBucketsInserted = bucketWriter.write(connection, timeBuckets);
        int contextBucketsInserted = contextWriter.write(connection, contextBuckets);

        return new AggregationResult(timeBucketsInserted, contextBucketsInserted);
    }

    public record AggregationResult(int timeBucketsInserted, int contextBucketsInserted) {
    }
}
