package com.strategysquad.ingestion.live;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical raw option live tick written to {@code options_live}.
 */
public record OptionLiveTick(
        Instant exchangeTs,
        Instant ingestTs,
        String instrumentId,
        String underlying,
        BigDecimal lastPrice,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        long volume,
        long openInterest
) {
    public OptionLiveTick {
        Objects.requireNonNull(exchangeTs, "exchangeTs must not be null");
        Objects.requireNonNull(ingestTs, "ingestTs must not be null");
        Objects.requireNonNull(lastPrice, "lastPrice must not be null");
        Objects.requireNonNull(bidPrice, "bidPrice must not be null");
        Objects.requireNonNull(askPrice, "askPrice must not be null");

        instrumentId = normalizeRequired(instrumentId, "instrumentId");
        underlying = normalizeRequiredUppercase(underlying, "underlying");
        lastPrice = requireNonNegative(lastPrice, "lastPrice");
        bidPrice = requireNonNegative(bidPrice, "bidPrice");
        askPrice = requireNonNegative(askPrice, "askPrice");
        if (askPrice.compareTo(bidPrice) < 0) {
            throw new IllegalArgumentException("askPrice must be greater than or equal to bidPrice");
        }
        if (volume < 0) {
            throw new IllegalArgumentException("volume must not be negative");
        }
        if (openInterest < 0) {
            throw new IllegalArgumentException("openInterest must not be negative");
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

    private static String normalizeRequiredUppercase(String value, String fieldName) {
        return normalizeRequired(value, fieldName).toUpperCase();
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String fieldName) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }
}
