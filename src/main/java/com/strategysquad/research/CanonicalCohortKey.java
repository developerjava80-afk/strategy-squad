package com.strategysquad.research;

/**
 * Canonical cohort identity used by the research console and historical context model.
 */
public record CanonicalCohortKey(
        String underlying,
        String optionType,
        int timeBucket15m,
        int moneynessBucket,
        int estimatedMinutesToExpiry
) {
}
