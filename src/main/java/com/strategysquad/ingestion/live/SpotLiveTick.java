package com.strategysquad.ingestion.live;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical raw spot live tick written to {@code spot_live}.
 */
public record SpotLiveTick(
        Instant exchangeTs,
        Instant ingestTs,
        String underlying,
        BigDecimal lastPrice
) {
    public SpotLiveTick {
        Objects.requireNonNull(exchangeTs, "exchangeTs must not be null");
        Objects.requireNonNull(ingestTs, "ingestTs must not be null");
        Objects.requireNonNull(lastPrice, "lastPrice must not be null");

        underlying = normalizeRequired(underlying, "underlying");
        if (lastPrice.signum() < 0) {
            throw new IllegalArgumentException("lastPrice must not be negative");
        }
    }

    private static String normalizeRequired(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
