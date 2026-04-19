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
        int bucketSize = 50;
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
            return 0;
        }
        // Calendar-minute estimate matching the enricher's computation.
        // EOD bhavcopy exchange_ts is at 10:00 IST (04:30 UTC).
        // Effective expiry is at 18:30 IST (13:00 UTC) on the day before nominal expiry.
        // Each calendar day adds 1440 minutes; the base offset on DTE=1 is 510 minutes.
        return (dte - 1) * 1440 + 510;
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
