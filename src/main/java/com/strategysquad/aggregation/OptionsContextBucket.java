package com.strategysquad.aggregation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Aggregated row for {@code options_context_buckets}.
 * Cross-contract contextual aggregation by moneyness bucket + DTE bucket + option type.
 */
public record OptionsContextBucket(
        Instant bucketTs,
        String underlying,
        String optionType,
        int timeBucket15m,
        int moneynessBucket,
        BigDecimal avgOptionPrice,
        BigDecimal avgPriceToSpotRatio,
        BigDecimal avgVolume,
        long sampleCount
) {
    public OptionsContextBucket {
        Objects.requireNonNull(bucketTs, "bucketTs must not be null");
        underlying = normalizeRequired(underlying, "underlying", true);
        optionType = normalizeRequired(optionType, "optionType", true);
        if (timeBucket15m < 0) {
            throw new IllegalArgumentException("timeBucket15m must not be negative");
        }
        Objects.requireNonNull(avgOptionPrice, "avgOptionPrice must not be null");
        Objects.requireNonNull(avgPriceToSpotRatio, "avgPriceToSpotRatio must not be null");
        Objects.requireNonNull(avgVolume, "avgVolume must not be null");
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive");
        }
    }

    private static String normalizeRequired(String value, String fieldName, boolean uppercase) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return uppercase ? normalized.toUpperCase() : normalized;
    }
}
