package com.strategysquad.ingestion.bhavcopy;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Resolves a stable instrument_id for Bhavcopy option contracts.
 */
public class InstrumentResolver {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String ID_PREFIX = "INS_";

    public String resolveInstrumentId(InstrumentKey key) {
        Objects.requireNonNull(key, "key must not be null");
        return ID_PREFIX
                + key.underlying()
                + "_"
                + key.expiryDate().format(DATE_FORMATTER)
                + "_"
                + strikeToken(key.strike())
                + "_"
                + key.optionType();
    }

    private static String strikeToken(BigDecimal strike) {
        return strike
                .stripTrailingZeros()
                .toPlainString()
                .replace('-', 'M')
                .replace('.', 'P');
    }
}
