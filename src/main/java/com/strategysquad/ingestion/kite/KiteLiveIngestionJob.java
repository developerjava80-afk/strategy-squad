package com.strategysquad.ingestion.kite;

import com.strategysquad.enrichment.InstrumentMasterLookup;
import com.strategysquad.enrichment.OptionEnrichedTick;
import com.strategysquad.enrichment.OptionInstrument;
import com.strategysquad.enrichment.OptionsEnricher;
import com.strategysquad.enrichment.SpotLiveLookup;
import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.OptionsLiveWriter;
import com.strategysquad.ingestion.live.SpotLiveTick;
import com.strategysquad.ingestion.live.SpotLiveWriter;
import com.strategysquad.ingestion.live.session.Live15mAggregator;
import com.strategysquad.ingestion.live.session.LiveEnrichmentWriter;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates a single live tick batch:
 * <ol>
 *   <li>Persist spot ticks → {@code spot_live}</li>
 *   <li>Persist option ticks → {@code options_live}</li>
 *   <li>Enrich each option tick with point-in-time spot → {@code options_live_enriched}</li>
 *   <li>Feed enriched ticks into the in-memory 15m aggregator</li>
 * </ol>
 *
 * <p>Separate from the historical {@link com.strategysquad.ingestion.live.LiveTickIngestionJob}:
 * live ticks write to {@code options_live_enriched}, not to the canonical {@code options_enriched}.
 */
public final class KiteLiveIngestionJob {

    private final SpotLiveWriter spotLiveWriter;
    private final OptionsLiveWriter optionsLiveWriter;
    private final InstrumentMasterLookup instrumentMasterLookup;
    private final SpotLiveLookup spotLiveLookup;
    private final OptionsEnricher optionsEnricher;
    private final LiveEnrichmentWriter liveEnrichmentWriter;
    private final Live15mAggregator aggregator;

    public KiteLiveIngestionJob(Live15mAggregator aggregator) {
        this(
                new SpotLiveWriter(),
                new OptionsLiveWriter(),
                new InstrumentMasterLookup(),
                new SpotLiveLookup(),
                new OptionsEnricher(),
                new LiveEnrichmentWriter(),
                aggregator
        );
    }

    public KiteLiveIngestionJob(
            SpotLiveWriter spotLiveWriter,
            OptionsLiveWriter optionsLiveWriter,
            InstrumentMasterLookup instrumentMasterLookup,
            SpotLiveLookup spotLiveLookup,
            OptionsEnricher optionsEnricher,
            LiveEnrichmentWriter liveEnrichmentWriter,
            Live15mAggregator aggregator
    ) {
        this.spotLiveWriter = Objects.requireNonNull(spotLiveWriter);
        this.optionsLiveWriter = Objects.requireNonNull(optionsLiveWriter);
        this.instrumentMasterLookup = Objects.requireNonNull(instrumentMasterLookup);
        this.spotLiveLookup = Objects.requireNonNull(spotLiveLookup);
        this.optionsEnricher = Objects.requireNonNull(optionsEnricher);
        this.liveEnrichmentWriter = Objects.requireNonNull(liveEnrichmentWriter);
        this.aggregator = Objects.requireNonNull(aggregator);
    }

    public IngestResult ingest(
            Connection connection,
            List<OptionLiveTick> optionTicks,
            List<SpotLiveTick> spotTicks,
            LocalDate sessionDate
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(optionTicks, "optionTicks must not be null");
        Objects.requireNonNull(spotTicks, "spotTicks must not be null");

        boolean autoCommit = connection.getAutoCommit();
        if (autoCommit) connection.setAutoCommit(false);
        try {
            int spotInserted = spotLiveWriter.write(connection, spotTicks);
            int optionsInserted = optionsLiveWriter.write(connection, optionTicks);

            List<OptionEnrichedTick> enriched = enrich(connection, optionTicks);
            int enrichedInserted = liveEnrichmentWriter.write(connection, enriched);

            if (autoCommit) connection.commit();

            // 15m aggregation is in-memory: runs after commit so DB is consistent
            for (OptionEnrichedTick tick : enriched) {
                aggregator.accept(connection, tick);
            }

            return new IngestResult(spotTicks.size(), spotInserted,
                    optionTicks.size(), optionsInserted, enrichedInserted);
        } catch (SQLException | RuntimeException ex) {
            if (autoCommit) connection.rollback();
            throw ex;
        } finally {
            if (autoCommit) connection.setAutoCommit(true);
        }
    }

    private List<OptionEnrichedTick> enrich(Connection connection, List<OptionLiveTick> ticks) throws SQLException {
        if (ticks.isEmpty()) return List.of();

        Map<String, OptionInstrument> cache = new LinkedHashMap<>();
        List<OptionEnrichedTick> result = new ArrayList<>(ticks.size());

        for (OptionLiveTick tick : ticks) {
            OptionInstrument instrument = cache.computeIfAbsent(
                    tick.instrumentId(), id -> loadInstrument(connection, id));
            if (instrument == null) continue;

            SpotLiveTick spot = spotLiveLookup
                    .findLatestAtOrBefore(connection, tick.underlying(), tick.exchangeTs())
                    .orElse(null);
            if (spot == null) continue;

            try {
                result.add(optionsEnricher.enrich(tick, instrument, spot));
            } catch (IllegalArgumentException ignored) {
                // Skip ticks that fail enrichment validation (e.g. stale spot timestamp)
            }
        }
        return result;
    }

    private OptionInstrument loadInstrument(Connection connection, String instrumentId) {
        try {
            return instrumentMasterLookup.findByInstrumentId(connection, instrumentId).orElse(null);
        } catch (SQLException ex) {
            return null;
        }
    }

    public record IngestResult(
            int spotReceived, int spotInserted,
            int optionsReceived, int optionsInserted,
            int enrichedInserted
    ) {
    }
}
