package com.strategysquad.ingestion.bhavcopy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Normalized Bhavcopy spot (index) record for {@code spot_historical} ingestion.
 *
 * <p>{@code expiryDate} is present for FUTIDX rows and used to select the
 * nearest-expiry contract as the spot proxy. It is not written to
 * {@code spot_historical}.
 */
public record SpotBhavcopyRecord(
        long lineNumber,
        String underlying,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        LocalDate expiryDate
) {
    public SpotBhavcopyRecord {
        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(open, "open must not be null");
        Objects.requireNonNull(high, "high must not be null");
        Objects.requireNonNull(low, "low must not be null");
        Objects.requireNonNull(close, "close must not be null");
        // expiryDate may be null for index-level Bhavcopy files
    }
}
