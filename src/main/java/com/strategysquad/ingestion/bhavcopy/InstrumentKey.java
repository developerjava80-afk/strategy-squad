package com.strategysquad.ingestion.bhavcopy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable key used to identify options contracts in Bhavcopy ingestion.
 */
public record InstrumentKey(
        String underlying,
        LocalDate expiryDate,
        BigDecimal strike,
        String optionType
) {
    public InstrumentKey {
        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(expiryDate, "expiryDate must not be null");
        Objects.requireNonNull(strike, "strike must not be null");
        Objects.requireNonNull(optionType, "optionType must not be null");

        underlying = normalizeRequired(underlying, "underlying");
        optionType = normalizeRequired(optionType, "optionType");
        strike = strike.stripTrailingZeros();
        if (strike.signum() <= 0) {
            throw new IllegalArgumentException("strike must be positive");
        }
        if (!optionType.equals("CE") && !optionType.equals("PE")) {
            throw new IllegalArgumentException("optionType must be CE or PE");
        }
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = value.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
