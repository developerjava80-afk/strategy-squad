package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LotSizingRulesTest {
    @Test
    void normalizesMissingQuantityToOneLot() {
        assertEquals(65, LotSizingRules.normalizeQuantity("NIFTY", null));
        assertEquals(30, LotSizingRules.normalizeQuantity("BANKNIFTY", null));
    }

    @Test
    void rejectsQuantityBelowOneLot() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> LotSizingRules.validateQuantity("NIFTY", 64)
        );

        assertEquals("Qty must be at least one lot of 65 for NIFTY", error.getMessage());
    }

    @Test
    void rejectsQuantityOutsideLotMultiple() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> LotSizingRules.validateQuantity("BANKNIFTY", 45)
        );

        assertEquals("Qty must be a multiple of 30 for BANKNIFTY", error.getMessage());
    }

    @Test
    void allowsClosedOpenQuantityToBeZero() {
        assertEquals(0, LotSizingRules.normalizeOpenQuantity("NIFTY", 0));
    }
}
