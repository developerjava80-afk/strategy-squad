package com.strategysquad.enrichment;

import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.SpotLiveTick;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Enriches option ticks using instrument metadata and point-in-time spot prices.
 */
public class OptionsEnrichmentJob {
    private final InstrumentMasterLookup instrumentMasterLookup;
    private final SpotLiveLookup spotLiveLookup;
    private final OptionsEnricher optionsEnricher;
    private final OptionsEnrichedWriter optionsEnrichedWriter;

    public OptionsEnrichmentJob() {
        this(new InstrumentMasterLookup(), new SpotLiveLookup(), new OptionsEnricher(), new OptionsEnrichedWriter());
    }

    public OptionsEnrichmentJob(
            InstrumentMasterLookup instrumentMasterLookup,
            SpotLiveLookup spotLiveLookup,
            OptionsEnricher optionsEnricher,
            OptionsEnrichedWriter optionsEnrichedWriter
    ) {
        this.instrumentMasterLookup = Objects.requireNonNull(instrumentMasterLookup, "instrumentMasterLookup must not be null");
        this.spotLiveLookup = Objects.requireNonNull(spotLiveLookup, "spotLiveLookup must not be null");
        this.optionsEnricher = Objects.requireNonNull(optionsEnricher, "optionsEnricher must not be null");
        this.optionsEnrichedWriter = Objects.requireNonNull(optionsEnrichedWriter, "optionsEnrichedWriter must not be null");
    }

    public EnrichmentResult enrich(Connection connection, List<OptionLiveTick> optionTicks) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(optionTicks, "optionTicks must not be null");
        if (optionTicks.isEmpty()) {
            return new EnrichmentResult(0, 0);
        }

        Map<String, OptionInstrument> instrumentCache = new LinkedHashMap<>();
        List<OptionEnrichedTick> enrichedTicks = new ArrayList<>(optionTicks.size());
        try {
            for (OptionLiveTick optionTick : optionTicks) {
                OptionInstrument instrument = instrumentCache.computeIfAbsent(
                        optionTick.instrumentId(),
                        instrumentId -> loadInstrument(connection, instrumentId)
                );
                SpotLiveTick spotTick = spotLiveLookup.findLatestAtOrBefore(
                                connection,
                                optionTick.underlying(),
                                optionTick.exchangeTs()
                        )
                        .orElseThrow(() -> new SQLException(
                                "No spot_live tick found for underlying " + optionTick.underlying()
                                        + " at or before " + optionTick.exchangeTs()
                        ));
                enrichedTicks.add(optionsEnricher.enrich(optionTick, instrument, spotTick));
            }
        } catch (InstrumentLookupException ex) {
            throw ex.sqlException();
        } catch (IllegalArgumentException ex) {
            throw new SQLException("Failed to enrich option tick: " + ex.getMessage(), ex);
        }

        int inserted = optionsEnrichedWriter.write(connection, enrichedTicks);
        return new EnrichmentResult(optionTicks.size(), inserted);
    }

    private OptionInstrument loadInstrument(Connection connection, String instrumentId) {
        try {
            return instrumentMasterLookup.findByInstrumentId(connection, instrumentId)
                    .orElseThrow(() -> new SQLException("No instrument_master row found for instrument_id " + instrumentId));
        } catch (SQLException ex) {
            throw new InstrumentLookupException(ex);
        }
    }

    public record EnrichmentResult(int optionTicksReceived, int optionsEnrichedInserted) {
    }

    private static final class InstrumentLookupException extends RuntimeException {
        private InstrumentLookupException(SQLException cause) {
            super(cause);
        }

        private SQLException sqlException() {
            return (SQLException) getCause();
        }
    }
}
