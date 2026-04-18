package com.strategysquad.ingestion.bhavcopy;

import com.strategysquad.aggregation.ContextualAggregationJob;
import com.strategysquad.derived.HistoricalOptionSignalRow;
import com.strategysquad.derived.PcrHistoricalAggregator;
import com.strategysquad.derived.PcrHistoricalPoint;
import com.strategysquad.derived.PcrHistoricalWriter;
import com.strategysquad.enrichment.OptionEnrichedTick;
import com.strategysquad.enrichment.OptionInstrument;
import com.strategysquad.enrichment.OptionsEnrichedWriter;
import com.strategysquad.enrichment.OptionsEnricher;
import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.SpotLiveTick;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZoneOffset;

/**
 * Backfills non-live derived tables from historical raw tables.
 */
public class HistoricalDerivedBackfillMain {
    private static final Logger LOGGER = Logger.getLogger(HistoricalDerivedBackfillMain.class.getName());
    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";

    private static final String PCR_SIGNAL_SQL =
            "SELECT oh.trade_ts, im.underlying, im.option_type, oh.volume, oh.open_interest "
                    + "FROM options_historical oh "
                    + "JOIN instrument_master im ON im.instrument_id = oh.instrument_id";
        private static final String HISTORICAL_TRADE_DATES_SQL =
            "SELECT trade_ts FROM spot_historical ORDER BY trade_ts";

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
                if (autoCommit) {
                    connection.commit();
                }

                List<LocalDate> tradeDates = loadTradeDates(connection);
                OptionsEnrichedWriter enrichedWriter = new OptionsEnrichedWriter();
                ContextualAggregationJob aggregationJob = new ContextualAggregationJob();
                PcrHistoricalWriter pcrWriter = new PcrHistoricalWriter();

                int enrichedInserted = 0;
                int timeBucketsInserted = 0;
                int contextBucketsInserted = 0;
                int pcrInserted = 0;

                for (int index = 0; index < tradeDates.size(); index++) {
                    LocalDate tradeDate = tradeDates.get(index);
                    List<OptionEnrichedTick> enrichedTicks = buildEnrichedTicks(connection, tradeDate);
                    int insertedEnriched = enrichedWriter.write(connection, enrichedTicks);
                    enrichedInserted += insertedEnriched;

                    ContextualAggregationJob.AggregationResult aggregationResult =
                            aggregationJob.aggregate(connection, enrichedTicks);
                    timeBucketsInserted += aggregationResult.timeBucketsInserted();
                    contextBucketsInserted += aggregationResult.contextBucketsInserted();
                    List<PcrHistoricalPoint> pcrPoints = buildPcrPoints(connection, tradeDate);
                    int insertedPcr = pcrWriter.write(connection, pcrPoints);
                    pcrInserted += insertedPcr;

                    if (insertedEnriched != enrichedTicks.size() || insertedPcr != pcrPoints.size()) {
                        LOGGER.warning("Historical backfill mismatch: tradeDate=" + tradeDate
                                + " builtEnriched=" + enrichedTicks.size()
                                + " insertedEnriched=" + insertedEnriched
                                + " builtPcr=" + pcrPoints.size()
                                + " insertedPcr=" + insertedPcr);
                    } else if (tradeDate.getDayOfWeek() == DayOfWeek.FRIDAY) {
                        LOGGER.info("Historical backfill Friday checkpoint: tradeDate=" + tradeDate
                                + " enriched=" + insertedEnriched
                                + " pcr=" + insertedPcr);
                    }

                    if (autoCommit) {
                        connection.commit();
                    }

                    if ((index + 1) % 50 == 0 || index + 1 == tradeDates.size()) {
                        LOGGER.info("Historical backfill progress: processedDates=" + (index + 1)
                                + "/" + tradeDates.size()
                                + " latestTradeDate=" + tradeDate);
                    }
                }

                LOGGER.info("Historical backfill complete:"
                        + " enrichedInserted=" + enrichedInserted
                        + " timeBucketsInserted=" + timeBucketsInserted
                        + " contextBucketsInserted=" + contextBucketsInserted
                        + " pcrInserted=" + pcrInserted);
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
            stmt.execute("TRUNCATE TABLE pcr_historical");
            stmt.execute("TRUNCATE TABLE options_context_buckets");
            stmt.execute("TRUNCATE TABLE options_15m_buckets");
            stmt.execute("TRUNCATE TABLE options_enriched");
        }
    }

    private static List<LocalDate> loadTradeDates(Connection connection) throws Exception {
        List<LocalDate> tradeDates = new ArrayList<>();
        LocalDate lastTradeDate = null;
        try (PreparedStatement statement = connection.prepareStatement(HISTORICAL_TRADE_DATES_SQL)) {
            statement.setFetchSize(256);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate tradeDate = toIstLocalDate(rs.getTimestamp("trade_ts").toInstant());
                    if (!tradeDate.equals(lastTradeDate)) {
                        tradeDates.add(tradeDate);
                        lastTradeDate = tradeDate;
                    }
                }
            }
        }
        return tradeDates;
    }

    private static List<OptionEnrichedTick> buildEnrichedTicks(Connection connection, LocalDate tradeDate) throws Exception {
        return HistoricalDerivedSupport.buildEnrichedTicks(connection, tradeDate);
    }

    private static List<PcrHistoricalPoint> buildPcrPoints(Connection connection, LocalDate tradeDate) throws Exception {
        List<HistoricalOptionSignalRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                PCR_SIGNAL_SQL + " WHERE oh.trade_ts >= ? AND oh.trade_ts < ? ORDER BY im.underlying, im.option_type")) {
            statement.setTimestamp(1, HistoricalDerivedSupport.tradeStartTimestampUtc(tradeDate));
            statement.setTimestamp(2, HistoricalDerivedSupport.tradeEndTimestampUtc(tradeDate));
            statement.setFetchSize(2048);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HistoricalOptionSignalRow(
                        toIstLocalDate(rs.getTimestamp("trade_ts").toInstant()),
                            rs.getString("underlying"),
                            rs.getString("option_type"),
                            rs.getLong("volume"),
                            rs.getLong("open_interest")
                    ));
                }
            }
        }
        return new PcrHistoricalAggregator().aggregate(rows);
    }

    private static LocalDate toIstLocalDate(Instant instant) {
        return instant.atOffset(IST_OFFSET).toLocalDate();
    }

}
