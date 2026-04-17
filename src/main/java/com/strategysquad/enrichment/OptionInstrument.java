package com.strategysquad.enrichment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Instrument master metadata required to enrich a live option tick.
 */
public record OptionInstrument(
        String instrumentId,
        String underlying,
        String optionType,
        BigDecimal strike,
        Instant expiryTs
) {
    public OptionInstrument {
        instrumentId = normalizeRequired(instrumentId, "instrumentId", false);
        underlying = normalizeRequired(underlying, "underlying", true);
        optionType = normalizeRequired(optionType, "optionType", true);
        Objects.requireNonNull(strike, "strike must not be null");
        Objects.requireNonNull(expiryTs, "expiryTs must not be null");
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
