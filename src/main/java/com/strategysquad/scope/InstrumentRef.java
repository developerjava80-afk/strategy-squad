package com.strategysquad.scope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A lightweight, immutable reference to a single option contract within a
 * resolved scope universe.
 *
 * <p>This record carries only the fields needed to subscribe to live quotes,
 * run the scanner, and present candidates to the UI. It is derived from
 * {@code instrument_master} by {@link com.strategysquad.scope.UniverseResolver}
 * and never written back to the database.
 *
 * <p>Instrument IDs follow the canonical format defined in the domain contract:
 * {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>}
 * Example: {@code INS_NIFTY_20260430_24800_CE}
 */
public record InstrumentRef(
        String instrumentId,
        long kiteToken,
        String tradingSymbol,
        String optionType,
        BigDecimal strike,
        LocalDate expiry,
        ExpiryType expiryType,
        int lotSize
) {

    public InstrumentRef {
        Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        Objects.requireNonNull(tradingSymbol, "tradingSymbol must not be null");
        Objects.requireNonNull(optionType, "optionType must not be null");
        Objects.requireNonNull(strike, "strike must not be null");
        Objects.requireNonNull(expiry, "expiry must not be null");
        Objects.requireNonNull(expiryType, "expiryType must not be null");

        if (!"CE".equals(optionType) && !"PE".equals(optionType)) {
            throw new IllegalArgumentException(
                    "optionType must be CE or PE, got: " + optionType);
        }
        if (strike.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "strike must be > 0, got: " + strike);
        }
        if (lotSize <= 0) {
            throw new IllegalArgumentException(
                    "lotSize must be > 0, got: " + lotSize);
        }
    }

    /** Returns true if this is a call option. */
    public boolean isCall() {
        return "CE".equals(optionType);
    }

    /** Returns true if this is a put option. */
    public boolean isPut() {
        return "PE".equals(optionType);
    }
}
