package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentKeyTest {

    @Test
    void validKeyCreation() {
        InstrumentKey key = new InstrumentKey("NIFTY", LocalDate.of(2024, 3, 28), new BigDecimal("22000"), "CE");
        assertEquals("NIFTY", key.underlying());
        assertEquals("CE", key.optionType());
    }

    @Test
    void normalizesInput() {
        InstrumentKey key = new InstrumentKey(" nifty ", LocalDate.of(2024, 3, 28), new BigDecimal("22000.00"), " ce ");
        assertEquals("NIFTY", key.underlying());
        assertEquals("CE", key.optionType());
        // strike trailing zeros stripped
        assertEquals(new BigDecimal("22000").stripTrailingZeros(), key.strike());
    }

    @Test
    void rejectsNegativeStrike() {
        assertThrows(IllegalArgumentException.class, () ->
                new InstrumentKey("NIFTY", LocalDate.of(2024, 3, 28), new BigDecimal("-100"), "CE"));
    }

    @Test
    void rejectsZeroStrike() {
        assertThrows(IllegalArgumentException.class, () ->
                new InstrumentKey("NIFTY", LocalDate.of(2024, 3, 28), BigDecimal.ZERO, "CE"));
    }

    @Test
    void rejectsInvalidOptionType() {
        assertThrows(IllegalArgumentException.class, () ->
                new InstrumentKey("NIFTY", LocalDate.of(2024, 3, 28), new BigDecimal("22000"), "XX"));
    }

    @Test
    void rejectsBlankUnderlying() {
        assertThrows(IllegalArgumentException.class, () ->
                new InstrumentKey("  ", LocalDate.of(2024, 3, 28), new BigDecimal("22000"), "CE"));
    }
}
