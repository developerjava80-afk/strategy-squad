package com.strategysquad.derived;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Minimal historical option row used to derive non-pricing signals.
 */
public record HistoricalOptionSignalRow(
        LocalDate tradeDate,
        String underlying,
        String optionType,
        long volume,
        long openInterest
) {
    public HistoricalOptionSignalRow {
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(optionType, "optionType must not be null");
    }
}
