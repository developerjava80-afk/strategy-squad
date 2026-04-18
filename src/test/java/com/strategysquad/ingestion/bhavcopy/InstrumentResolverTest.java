package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentResolverTest {

    private final InstrumentResolver resolver = new InstrumentResolver();

    @Test
    void resolvesDeterministicId() {
        InstrumentKey key = new InstrumentKey("NIFTY", LocalDate.of(2024, 3, 28), new BigDecimal("22000"), "CE");
        String id = resolver.resolveInstrumentId(key);

        assertNotNull(id);
        assertEquals("INS_NIFTY_20240328_22000_CE", id);
        // Deterministic: same key must always produce same ID
        assertEquals(id, resolver.resolveInstrumentId(key));
    }

    @Test
    void encodesDecimalStrikeInReadableId() {
        InstrumentKey key = new InstrumentKey("BANKNIFTY", LocalDate.of(2026, 4, 28), new BigDecimal("61900.50"), "PE");
        assertEquals("INS_BANKNIFTY_20260428_61900P5_PE", resolver.resolveInstrumentId(key));
    }

    @Test
    void differentKeysProduceDifferentIds() {
        InstrumentKey key1 = new InstrumentKey("NIFTY", LocalDate.of(2024, 3, 28), new BigDecimal("22000"), "CE");
        InstrumentKey key2 = new InstrumentKey("NIFTY", LocalDate.of(2024, 3, 28), new BigDecimal("22000"), "PE");

        assertNotEquals(resolver.resolveInstrumentId(key1), resolver.resolveInstrumentId(key2));
    }
}
