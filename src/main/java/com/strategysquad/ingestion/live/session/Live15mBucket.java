package com.strategysquad.ingestion.live.session;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A completed 15-minute aggregation bucket for a single instrument in the current session.
 * Written to {@code options_live_15m} when the bucket closes.
 */
public record Live15mBucket(
        Instant bucketTs,
        LocalDate sessionDate,
        String instrumentId,
        int timeBucket15m,
        double avgPrice,
        double minPrice,
        double maxPrice,
        long volumeSum,
        long sampleCount,
        Instant lastUpdatedTs
) {
}
