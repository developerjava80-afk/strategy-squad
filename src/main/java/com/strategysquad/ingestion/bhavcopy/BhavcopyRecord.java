package com.strategysquad.ingestion.bhavcopy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Normalized Bhavcopy option record in internal format.
 */
public record BhavcopyRecord(
        long lineNumber,
        String underlying,
        LocalDate expiryDate,
        BigDecimal strike,
        String optionType,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal settlePrice,
        long contracts,
        BigDecimal valueInLakhs,
        long openInterest,
        long changeInOi,
        String instrumentId
) {
    public BhavcopyRecord {
        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(expiryDate, "expiryDate must not be null");
        Objects.requireNonNull(strike, "strike must not be null");
        Objects.requireNonNull(optionType, "optionType must not be null");
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(open, "open must not be null");
        Objects.requireNonNull(high, "high must not be null");
        Objects.requireNonNull(low, "low must not be null");
        Objects.requireNonNull(close, "close must not be null");
        Objects.requireNonNull(settlePrice, "settlePrice must not be null");
        Objects.requireNonNull(valueInLakhs, "valueInLakhs must not be null");
    }

    public InstrumentKey instrumentKey() {
        return new InstrumentKey(underlying, expiryDate, strike, optionType);
    }

    public BhavcopyRecord withInstrumentId(String value) {
        return new BhavcopyRecord(
                lineNumber,
                underlying,
                expiryDate,
                strike,
                optionType,
                tradeDate,
                open,
                high,
                low,
                close,
                settlePrice,
                contracts,
                valueInLakhs,
                openInterest,
                changeInOi,
                value
        );
    }
}
