package com.strategysquad.ingestion.live;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionLiveTickTest {

    @Test
    void normalizesCanonicalFields() {
        OptionLiveTick tick = new OptionLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                "  nifty-20260430-22000-ce  ",
                " nifty ",
                new BigDecimal("102.50"),
                new BigDecimal("102.45"),
                new BigDecimal("102.55"),
                125,
                900
        );

        assertEquals("nifty-20260430-22000-ce", tick.instrumentId());
        assertEquals("NIFTY", tick.underlying());
    }

    @Test
    void rejectsAskBelowBid() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new OptionLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                "instrument",
                "NIFTY",
                new BigDecimal("102.50"),
                new BigDecimal("102.60"),
                new BigDecimal("102.55"),
                125,
                900
        ));

        assertEquals("askPrice must be greater than or equal to bidPrice", error.getMessage());
    }
}
