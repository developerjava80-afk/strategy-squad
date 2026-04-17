package com.strategysquad.enrichment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical enriched option tick written to {@code options_enriched}.
 */
public record OptionEnrichedTick(
        Instant exchangeTs,
        String instrumentId,
        String underlying,
        String optionType,
        BigDecimal strike,
        Instant expiryTs,
        BigDecimal lastPrice,
        BigDecimal underlyingPrice,
        int minutesToExpiry,
        int timeBucket15m,
        BigDecimal moneynessPct,
        BigDecimal moneynessPoints,
        int moneynessBucket
) {
    public OptionEnrichedTick {
        Objects.requireNonNull(exchangeTs, "exchangeTs must not be null");
        instrumentId = normalizeRequired(instrumentId, "instrumentId", false);
        underlying = normalizeRequired(underlying, "underlying", true);
        optionType = normalizeRequired(optionType, "optionType", true);
        Objects.requireNonNull(strike, "strike must not be null");
        Objects.requireNonNull(expiryTs, "expiryTs must not be null");
        Objects.requireNonNull(lastPrice, "lastPrice must not be null");
        Objects.requireNonNull(underlyingPrice, "underlyingPrice must not be null");
        Objects.requireNonNull(moneynessPct, "moneynessPct must not be null");
        Objects.requireNonNull(moneynessPoints, "moneynessPoints must not be null");
        if (minutesToExpiry < 0) {
            throw new IllegalArgumentException("minutesToExpiry must not be negative");
        }
        if (timeBucket15m < 0) {
            throw new IllegalArgumentException("timeBucket15m must not be negative");
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
