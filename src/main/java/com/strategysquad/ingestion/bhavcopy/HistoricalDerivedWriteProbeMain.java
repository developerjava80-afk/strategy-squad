package com.strategysquad.ingestion.bhavcopy;

import com.strategysquad.enrichment.OptionEnrichedTick;
import com.strategysquad.enrichment.OptionsEnrichedWriter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HistoricalDerivedWriteProbeMain {
    private static final Logger LOGGER = Logger.getLogger(HistoricalDerivedWriteProbeMain.class.getName());

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";
    private static final String PROBE_TABLE = "options_enriched_probe";

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java com.strategysquad.ingestion.bhavcopy.HistoricalDerivedWriteProbeMain <yyyy-MM-dd> [jdbc-url]");
            System.exit(1);
        }

        LocalDate tradeDate = LocalDate.parse(args[0]);
        String jdbcUrl = args.length > 1 ? args[1] : DEFAULT_JDBC_URL;
        LOGGER.info("Historical derived write probe: tradeDate=" + tradeDate + " jdbcUrl=" + jdbcUrl);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD)) {
            List<OptionEnrichedTick> enrichedTicks = HistoricalDerivedSupport.buildEnrichedTicks(connection, tradeDate);

            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + PROBE_TABLE);
            } catch (Exception ignored) {
                // Probe table may not exist.
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + PROBE_TABLE + " ("
                        + "exchange_ts TIMESTAMP, instrument_id STRING, underlying SYMBOL, option_type SYMBOL, strike DOUBLE, "
                        + "expiry_date TIMESTAMP, last_price DOUBLE, underlying_price DOUBLE, minutes_to_expiry INT, "
                        + "time_bucket_15m INT, moneyness_pct DOUBLE, moneyness_points DOUBLE, moneyness_bucket INT, volume LONG"
                        + ") timestamp(exchange_ts) PARTITION BY DAY");
            }

            int inserted = new OptionsEnrichedWriter(
                    "INSERT INTO " + PROBE_TABLE
                            + " (exchange_ts, instrument_id, underlying, option_type, strike,"
                            + "  expiry_date, last_price, underlying_price, minutes_to_expiry,"
                            + "  time_bucket_15m, moneyness_pct, moneyness_points, moneyness_bucket, volume)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).write(connection, enrichedTicks);

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count() FROM " + PROBE_TABLE + " WHERE exchange_ts >= ? AND exchange_ts < ?")) {
                statement.setTimestamp(1, HistoricalDerivedSupport.tradeStartTimestampUtc(tradeDate));
                statement.setTimestamp(2, HistoricalDerivedSupport.tradeEndTimestampUtc(tradeDate));
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    LOGGER.info("Historical derived write probe complete: built=" + enrichedTicks.size()
                            + " inserted=" + inserted
                            + " counted=" + rs.getLong(1));
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Historical derived write probe failed", ex);
            System.exit(2);
        }
    }
}