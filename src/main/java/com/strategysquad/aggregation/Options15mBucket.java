package com.strategysquad.aggregation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Aggregated row for {@code options_15m_buckets}.
 * Per-contract 15-minute time-to-expiry bucket aggregates.
 */
public record Options15mBucket(
        Instant bucketTs,
        LocalDate tradeDate,
        String instrumentId,
        int timeBucket15m,
        BigDecimal avgPrice,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        long volumeSum,
        long sampleCount
) {
    public Options15mBucket {
        Objects.requireNonNull(bucketTs, "bucketTs must not be null");
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        instrumentId = normalizeRequired(instrumentId, "instrumentId");
        if (timeBucket15m < 0) {
            throw new IllegalArgumentException("timeBucket15m must not be negative");
        }
        Objects.requireNonNull(avgPrice, "avgPrice must not be null");
        Objects.requireNonNull(minPrice, "minPrice must not be null");
        Objects.requireNonNull(maxPrice, "maxPrice must not be null");
        if (volumeSum < 0) {
            throw new IllegalArgumentException("volumeSum must not be negative");
        }
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive");
        }
    }

    private static String normalizeRequired(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
