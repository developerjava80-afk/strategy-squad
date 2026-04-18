package com.strategysquad.derived;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Deterministic historical PCR snapshot by date and underlying.
 */
public record PcrHistoricalPoint(
        Instant bucketTs,
        LocalDate tradeDate,
        String underlying,
        BigDecimal pcrByVolume,
        BigDecimal pcrByOpenInterest,
        long putVolume,
        long callVolume,
        long putOpenInterest,
        long callOpenInterest,
        long sampleCount
) {
    public PcrHistoricalPoint {
        Objects.requireNonNull(bucketTs, "bucketTs must not be null");
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(underlying, "underlying must not be null");
    }
}
