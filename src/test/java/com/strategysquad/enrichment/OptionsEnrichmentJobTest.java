package com.strategysquad.enrichment;

import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.SpotLiveTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionsEnrichmentJobTest {

    @Test
    void enrichesOptionTicksUsingPointInTimeSpot() throws Exception {
        TrackingInstrumentMasterLookup instrumentLookup = new TrackingInstrumentMasterLookup(false);
        TrackingSpotLiveLookup spotLookup = new TrackingSpotLiveLookup(false);
        TrackingOptionsEnrichedWriter writer = new TrackingOptionsEnrichedWriter(1);
        OptionLiveTick optionTick = optionTick();

        OptionsEnrichmentJob.EnrichmentResult result = new OptionsEnrichmentJob(
                instrumentLookup,
                spotLookup,
                new OptionsEnricher(),
                writer
        ).enrich(connection(), List.of(optionTick));

        assertEquals(new OptionsEnrichmentJob.EnrichmentResult(1, 1), result);
        assertEquals(List.of("INS_123"), instrumentLookup.instrumentIds);
        assertEquals(List.of(optionTick.exchangeTs()), spotLookup.requestedExchangeTimes);
        assertEquals(List.of("NIFTY"), spotLookup.underlyings);
        assertEquals(1, writer.ticks.size());
        assertEquals("INS_123", writer.ticks.get(0).instrumentId());
    }

    @Test
    void failsWhenNoSpotExists() {
        OptionsEnrichmentJob job = new OptionsEnrichmentJob(
                new TrackingInstrumentMasterLookup(false),
                new TrackingSpotLiveLookup(true),
                new OptionsEnricher(),
                new TrackingOptionsEnrichedWriter(0)
        );

        SQLException error = assertThrows(SQLException.class, () -> job.enrich(connection(), List.of(optionTick())));

        assertEquals("No spot_live tick found for underlying NIFTY at or before 2026-04-17T09:15:00Z", error.getMessage());
    }

    private static Connection connection() {
        return new EnrichmentJdbcTestSupport.ConnectionRecorder(null, true).proxy();
    }

    private static OptionLiveTick optionTick() {
        return new OptionLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                "INS_123",
                "NIFTY",
                new BigDecimal("102.50"),
                new BigDecimal("102.45"),
                new BigDecimal("102.55"),
                125,
                900
        );
    }

    private static final class TrackingInstrumentMasterLookup extends InstrumentMasterLookup {
        private final boolean missing;
        private final java.util.List<String> instrumentIds = new java.util.ArrayList<>();

        private TrackingInstrumentMasterLookup(boolean missing) {
            this.missing = missing;
        }

        @Override
        public java.util.Optional<OptionInstrument> findByInstrumentId(Connection connection, String instrumentId) {
            instrumentIds.add(instrumentId);
            if (missing) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new OptionInstrument(
                    instrumentId,
                    "NIFTY",
                    "CE",
                    new BigDecimal("22000"),
                    Instant.parse("2026-04-30T00:00:00Z")
            ));
        }
    }

    private static final class TrackingSpotLiveLookup extends SpotLiveLookup {
        private final boolean missing;
        private final java.util.List<String> underlyings = new java.util.ArrayList<>();
        private final java.util.List<Instant> requestedExchangeTimes = new java.util.ArrayList<>();

        private TrackingSpotLiveLookup(boolean missing) {
            this.missing = missing;
        }

        @Override
        public java.util.Optional<SpotLiveTick> findLatestAtOrBefore(Connection connection, String underlying, Instant exchangeTs) {
            underlyings.add(underlying);
            requestedExchangeTimes.add(exchangeTs);
            if (missing) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new SpotLiveTick(
                    Instant.parse("2026-04-17T09:14:59Z"),
                    Instant.parse("2026-04-17T09:15:00Z"),
                    underlying,
                    new BigDecimal("21950")
            ));
        }
    }

    private static final class TrackingOptionsEnrichedWriter extends OptionsEnrichedWriter {
        private final int returnValue;
        private final java.util.List<OptionEnrichedTick> ticks = new java.util.ArrayList<>();

        private TrackingOptionsEnrichedWriter(int returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public int write(Connection connection, List<OptionEnrichedTick> ticks) {
            this.ticks.addAll(ticks);
            return returnValue;
        }
    }
}
