package com.strategysquad.enrichment;

import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.SpotLiveTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptionsEnricherTest {

    @Test
    void computesCanonicalEnrichedFields() {
        OptionLiveTick optionTick = new OptionLiveTick(
                Instant.parse("2026-04-30T14:00:00Z"),
                Instant.parse("2026-04-30T14:00:01Z"),
                "INS_123",
                "NIFTY",
                new BigDecimal("102.50"),
                new BigDecimal("102.45"),
                new BigDecimal("102.55"),
                125,
                900
        );
        OptionInstrument instrument = new OptionInstrument(
                "INS_123",
                "NIFTY",
                "CE",
                new BigDecimal("22000"),
                Instant.parse("2026-04-30T00:00:00Z")
        );
        SpotLiveTick spotTick = new SpotLiveTick(
                Instant.parse("2026-04-30T13:59:59Z"),
                Instant.parse("2026-04-30T14:00:00Z"),
                "NIFTY",
                new BigDecimal("21950")
        );

        OptionEnrichedTick enrichedTick = new OptionsEnricher().enrich(optionTick, instrument, spotTick);

        assertEquals(Instant.parse("2026-04-30T15:30:00Z"), enrichedTick.expiryTs());
        assertEquals(90, enrichedTick.minutesToExpiry());
        assertEquals(6, enrichedTick.timeBucket15m());
        assertEquals(new BigDecimal("50"), enrichedTick.moneynessPoints());
        assertEquals(new BigDecimal("0.22779043"), enrichedTick.moneynessPct());
        assertEquals(50, enrichedTick.moneynessBucket());
    }

    @Test
    void clampsMinutesToExpiryAfterExpiry() {
        int minutes = OptionsEnricher.calculateMinutesToExpiry(
                Instant.parse("2026-04-30T16:00:00Z"),
                Instant.parse("2026-04-30T15:30:00Z")
        );

        assertEquals(0, minutes);
    }
}
