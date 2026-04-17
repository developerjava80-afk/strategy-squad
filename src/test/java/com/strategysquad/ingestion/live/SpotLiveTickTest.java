package com.strategysquad.ingestion.live;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpotLiveTickTest {

    @Test
    void normalizesUnderlying() {
        SpotLiveTick tick = new SpotLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                " banknifty ",
                new BigDecimal("48210.15")
        );

        assertEquals("BANKNIFTY", tick.underlying());
    }

    @Test
    void rejectsNegativePrice() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new SpotLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                "BANKNIFTY",
                new BigDecimal("-1")
        ));

        assertEquals("lastPrice must not be negative", error.getMessage());
    }
}
