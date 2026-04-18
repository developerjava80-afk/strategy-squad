package com.strategysquad.ingestion.bhavcopy;

import com.strategysquad.aggregation.ContextualAggregationJob;
import com.strategysquad.enrichment.OptionEnrichedTick;
import com.strategysquad.enrichment.OptionInstrument;
import com.strategysquad.enrichment.OptionsEnrichedWriter;
import com.strategysquad.enrichment.OptionsEnricher;
import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.SpotLiveTick;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Backfills non-live derived tables from historical raw tables.
 *
 * <p>Populates:
 * <ul>
 *   <li>options_enriched</li>
 *   <li>options_15m_buckets</li>
 *   <li>options_context_buckets</li>
 * </ul>
 *
 * <p>Live tables are intentionally not touched.
 */
public class HistoricalDerivedBackfillMain {
    private static final Logger LOGGER = Logger.getLogger(HistoricalDerivedBackfillMain.class.getName());

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";

    private static final String HISTORICAL_JOIN_SQL =
            "SELECT oh.trade_ts, oh.instrument_id, oh.close_price, oh.volume, "
                    + "im.underlying, im.option_type, im.strike, im.expiry_date, sh.close_price AS spot_close "
                    + "FROM options_historical oh "
                    + "JOIN instrument_master im ON im.instrument_id = oh.instrument_id "
                    + "JOIN spot_historical sh ON sh.trade_date = oh.trade_date AND sh.underlying = im.underlying";

    public static void main(String[] args) {
        String jdbcUrl = args.length > 0 ? args[0] : DEFAULT_JDBC_URL;
        LOGGER.info("Historical backfill: jdbcUrl=" + jdbcUrl);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD)) {
            boolean autoCommit = connection.getAutoCommit();
            if (autoCommit) {
                connection.setAutoCommit(false);
            }
            try {
                truncateDerivedTables(connection);
                List<OptionEnrichedTick> enrichedTicks = buildEnrichedTicks(connection);
                int enrichedInserted = new OptionsEnrichedWriter().write(connection, enrichedTicks);
                ContextualAggregationJob.AggregationResult aggregationResult =
                        new ContextualAggregationJob().aggregate(connection, enrichedTicks);

                if (autoCommit) {
                    connection.commit();
                }

                LOGGER.info("Historical backfill complete:"
                        + " enrichedInserted=" + enrichedInserted
                        + " timeBucketsInserted=" + aggregationResult.timeBucketsInserted()
                        + " contextBucketsInserted=" + aggregationResult.contextBucketsInserted());
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                if (autoCommit) {
                    connection.setAutoCommit(true);
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Historical backfill failed", ex);
            System.exit(2);
        }
    }

    private static void truncateDerivedTables(Connection connection) throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("TRUNCATE TABLE options_context_buckets");
            stmt.execute("TRUNCATE TABLE options_15m_buckets");
            stmt.execute("TRUNCATE TABLE options_enriched");
        }
    }

    private static List<OptionEnrichedTick> buildEnrichedTicks(Connection connection) throws Exception {
        List<OptionEnrichedTick> enrichedTicks = new ArrayList<>();
        OptionsEnricher enricher = new OptionsEnricher();

        try (PreparedStatement statement = connection.prepareStatement(HISTORICAL_JOIN_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Instant exchangeTs = toInstant(rs.getTimestamp("trade_ts"));
                String instrumentId = rs.getString("instrument_id");
                String underlying = rs.getString("underlying");
                String optionType = rs.getString("option_type");
                BigDecimal strike = BigDecimal.valueOf(rs.getDouble("strike"));
                Instant expiryTs = toInstant(rs.getTimestamp("expiry_date"));
                BigDecimal lastPrice = BigDecimal.valueOf(rs.getDouble("close_price"));
                BigDecimal spotPrice = BigDecimal.valueOf(rs.getDouble("spot_close"));
                long volume = rs.getLong("volume");

                OptionInstrument instrument = new OptionInstrument(
                        instrumentId,
                        underlying,
                        optionType,
                        strike,
                        expiryTs
                );
                OptionLiveTick optionTick = new OptionLiveTick(
                        exchangeTs,
                        exchangeTs,
                        instrumentId,
                        underlying,
                        lastPrice,
                        lastPrice,
                        lastPrice,
                        volume,
                        0
                );
                SpotLiveTick spotTick = new SpotLiveTick(
                        exchangeTs,
                        exchangeTs,
                        underlying,
                        spotPrice
                );

                enrichedTicks.add(enricher.enrich(optionTick, instrument, spotTick));
            }
        }

        return enrichedTicks;
    }

    private static Instant toInstant(Timestamp value) {
        return value.toInstant();
    }
}
