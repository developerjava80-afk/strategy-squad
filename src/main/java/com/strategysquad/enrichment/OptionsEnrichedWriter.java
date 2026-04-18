package com.strategysquad.enrichment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * Writes enriched option ticks into {@code options_enriched}.
 */
public class OptionsEnrichedWriter {
    private static final String DEFAULT_INSERT_SQL =
            "INSERT INTO options_enriched"
                    + " (exchange_ts, instrument_id, underlying, option_type, strike,"
                    + "  expiry_date, last_price, underlying_price, minutes_to_expiry,"
                + "  time_bucket_15m, moneyness_pct, moneyness_points, moneyness_bucket)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String insertSql;

    public OptionsEnrichedWriter() {
        this(DEFAULT_INSERT_SQL);
    }

    public OptionsEnrichedWriter(String insertSql) {
        this.insertSql = Objects.requireNonNull(insertSql, "insertSql must not be null");
    }

    public int write(Connection connection, List<OptionEnrichedTick> ticks) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(ticks, "ticks must not be null");
        if (ticks.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (OptionEnrichedTick tick : ticks) {
                statement.setTimestamp(1, Timestamp.from(tick.exchangeTs()));
                statement.setString(2, tick.instrumentId());
                statement.setString(3, tick.underlying());
                statement.setString(4, tick.optionType());
                statement.setDouble(5, tick.strike().doubleValue());
                statement.setTimestamp(6, Timestamp.from(tick.expiryTs()));
                statement.setDouble(7, tick.lastPrice().doubleValue());
                statement.setDouble(8, tick.underlyingPrice().doubleValue());
                statement.setInt(9, tick.minutesToExpiry());
                statement.setInt(10, tick.timeBucket15m());
                statement.setDouble(11, tick.moneynessPct().doubleValue());
                statement.setDouble(12, tick.moneynessPoints().doubleValue());
                statement.setInt(13, tick.moneynessBucket());
                statement.addBatch();
            }
            return successfulBatchCount(statement.executeBatch());
        }
    }

    private int successfulBatchCount(int[] results) throws SQLException {
        int count = 0;
        for (int result : results) {
            if (result == Statement.EXECUTE_FAILED) {
                throw new SQLException("Batch execution failed for one or more rows");
            }
            if (result == Statement.SUCCESS_NO_INFO || result >= 0) {
                count++;
            }
        }
        return count;
    }
}
