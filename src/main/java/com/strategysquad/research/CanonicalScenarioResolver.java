package com.strategysquad.research;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves business-facing scenario inputs into the canonical cohort key used by historical analytics.
 */
public final class CanonicalScenarioResolver {
    private CanonicalScenarioResolver() {
    }

    public static CanonicalCohortKey resolve(
            String underlying,
            String optionType,
            BigDecimal spot,
            BigDecimal strike,
            int dte
    ) {
        String normalizedUnderlying = normalizeRequired(underlying);
        String normalizedOptionType = normalizeRequired(optionType);
        BigDecimal distance = strike.subtract(spot);
        int bucketSize = "BANKNIFTY".equals(normalizedUnderlying) ? 100 : 50;
        int moneynessBucket = distance
                .divide(BigDecimal.valueOf(bucketSize), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(bucketSize))
                .intValueExact();
        int estimatedMinutes = estimatedMinutesToExpiry(dte);
        int timeBucket15m = Math.max(0, estimatedMinutes / 15);
        return new CanonicalCohortKey(
                normalizedUnderlying,
                normalizedOptionType,
                timeBucket15m,
                moneynessBucket,
                estimatedMinutes
        );
    }

    public static int estimatedMinutesToExpiry(int dte) {
        if (dte <= 0) {
            return 45;
        }
        return dte * 375;
    }

    private static String normalizeRequired(String value) {
        Objects.requireNonNull(value, "value must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }
}
