package com.strategysquad.ingestion.kite;

import com.strategysquad.enrichment.InstrumentMasterLookup;
import com.strategysquad.enrichment.OptionEnrichedTick;
import com.strategysquad.enrichment.OptionInstrument;
import com.strategysquad.enrichment.OptionsEnricher;
import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.OptionsLiveWriter;
import com.strategysquad.ingestion.live.SpotLiveTick;
import com.strategysquad.ingestion.live.SpotLiveWriter;
import com.strategysquad.ingestion.live.session.Live15mAggregator;
import com.strategysquad.ingestion.live.session.LiveEnrichmentWriter;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>Instrument metadata is pre-loaded into a session-scoped in-memory cache to avoid
 * querying {@code instrument_master} inside write transactions (which is unreliable under
 * QuestDB's limited ACID model).
 */
public final class KiteLiveIngestionJob {

    private final SpotLiveWriter spotLiveWriter;
    private final OptionsLiveWriter optionsLiveWriter;
    private final InstrumentMasterLookup instrumentMasterLookup;
    private final OptionsEnricher optionsEnricher;
    private final LiveEnrichmentWriter liveEnrichmentWriter;
    private final Live15mAggregator aggregator;
    private final LiveSessionState sessionState;
    private final String jdbcUrl;

    /**
     * Session-scoped instrument cache: populated on first cache miss via a separate read
     * connection, never cleared during the session (instruments are fixed for the trading day).
     * {@code null} as map value means "looked up, not found in instrument_master".
     */
    private final ConcurrentHashMap<String, OptionInstrument> instrumentCache = new ConcurrentHashMap<>();

    public KiteLiveIngestionJob(Live15mAggregator aggregator, LiveSessionState sessionState, String jdbcUrl) {
        this(
                new SpotLiveWriter(),
                new OptionsLiveWriter(),
                new InstrumentMasterLookup(),
                new OptionsEnricher(),
                new LiveEnrichmentWriter(),
                aggregator,
                sessionState,
                jdbcUrl
        );
    }

    public KiteLiveIngestionJob(
            SpotLiveWriter spotLiveWriter,
            OptionsLiveWriter optionsLiveWriter,
            InstrumentMasterLookup instrumentMasterLookup,
            OptionsEnricher optionsEnricher,
            LiveEnrichmentWriter liveEnrichmentWriter,
            Live15mAggregator aggregator,
            LiveSessionState sessionState,
            String jdbcUrl
    ) {
        this.spotLiveWriter = Objects.requireNonNull(spotLiveWriter);
        this.optionsLiveWriter = Objects.requireNonNull(optionsLiveWriter);
        this.instrumentMasterLookup = Objects.requireNonNull(instrumentMasterLookup);
        this.optionsEnricher = Objects.requireNonNull(optionsEnricher);
        this.liveEnrichmentWriter = Objects.requireNonNull(liveEnrichmentWriter);
        this.aggregator = Objects.requireNonNull(aggregator);
        this.sessionState = Objects.requireNonNull(sessionState);
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl);
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

            // Enrich uses the in-memory instrument cache and session state — no DB reads
            // inside the write transaction.
            List<OptionEnrichedTick> enriched = enrich(optionTicks);
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

    private List<OptionEnrichedTick> enrich(List<OptionLiveTick> ticks) {
        if (ticks.isEmpty()) return List.of();

        List<OptionEnrichedTick> result = new ArrayList<>(ticks.size());
        // Collect IDs that need DB lookup (not yet in cache)
        List<String> missing = ticks.stream()
                .map(OptionLiveTick::instrumentId)
                .filter(id -> !instrumentCache.containsKey(id))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            fetchIntoCache(missing);
        }

        for (OptionLiveTick tick : ticks) {
            OptionInstrument instrument = instrumentCache.get(tick.instrumentId());
            if (instrument == null) continue; // not in instrument_master

            // Use in-memory spot from session state rather than a DB lookup.
            // The Kite exchangeTs for illiquid options reflects the last trade time (possibly
            // hours ago), so a DB query bounded by that timestamp finds nothing in today's
            // session. The session-state cache is always current and DB-read-free.
            LiveSessionState.SpotQuote spotQuote = sessionState.getLatestSpot(tick.underlying());
            if (spotQuote == null || spotQuote.price() == null || spotQuote.price().signum() <= 0) continue;

            // Use the option's exchangeTs for the synthetic spot to satisfy the OptionsEnricher
            // invariant: spot exchangeTs must not be after option exchangeTs.
            SpotLiveTick spot = new SpotLiveTick(
                    tick.exchangeTs(), tick.ingestTs(),
                    spotQuote.underlying(), spotQuote.price());

            try {
                result.add(optionsEnricher.enrich(tick, instrument, spot));
            } catch (IllegalArgumentException ex) {
                // Skip ticks that fail enrichment validation
            }
        }
        if (result.isEmpty() && !ticks.isEmpty()) {
            long inCache = ticks.stream()
                    .map(OptionLiveTick::instrumentId)
                    .filter(id -> instrumentCache.get(id) != null)
                    .distinct().count();
            boolean hasSpot = sessionState.getLatestSpot(ticks.get(0).underlying()) != null;
            System.err.printf("[live-enrich] 0 of %d ticks enriched — instrumentsInCache=%d hasSpot=%s%n",
                    ticks.size(), inCache, hasSpot);
        }
        return result;
    }

    /**
     * Fetches instrument metadata for the given IDs from {@code instrument_master} using a
     * dedicated read-only connection (separate from any ongoing write transaction).
     */
    private void fetchIntoCache(List<String> instrumentIds) {
        try (Connection readConn = QuestDbConnectionFactory.open(jdbcUrl)) {
            for (String id : instrumentIds) {
                try {
                    OptionInstrument inst = instrumentMasterLookup.findByInstrumentId(readConn, id).orElse(null);
                    if (inst != null) {
                        instrumentCache.put(id, inst);
                    } else {
                        System.err.printf("[live-enrich] instrument_master miss: %s%n", id);
                    }
                } catch (SQLException ex) {
                    System.err.printf("[live-enrich] DB error looking up %s: %s%n", id, ex.getMessage());
                }
            }
        } catch (SQLException ex) {
            System.err.printf("[live-enrich] Cannot open read connection for instrument lookup: %s%n", ex.getMessage());
        }
    }

    public record IngestResult(
            int spotReceived, int spotInserted,
            int optionsReceived, int optionsInserted,
            int enrichedInserted
    ) {
    }
}
