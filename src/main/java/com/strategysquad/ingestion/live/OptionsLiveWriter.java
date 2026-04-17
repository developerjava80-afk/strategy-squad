package com.strategysquad.ingestion.live;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * Writes canonical option ticks into {@code options_live}.
 */
public class OptionsLiveWriter {
    private static final String DEFAULT_INSERT_SQL =
            "INSERT INTO options_live"
                    + " (exchange_ts, ingest_ts, instrument_id, underlying, last_price,"
                    + "  bid_price, ask_price, volume, open_interest)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String insertSql;

    public OptionsLiveWriter() {
        this(DEFAULT_INSERT_SQL);
    }

    public OptionsLiveWriter(String insertSql) {
        this.insertSql = Objects.requireNonNull(insertSql, "insertSql must not be null");
    }

    public int write(Connection connection, List<OptionLiveTick> ticks) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(ticks, "ticks must not be null");
        if (ticks.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (OptionLiveTick tick : ticks) {
                statement.setTimestamp(1, Timestamp.from(tick.exchangeTs()));
                statement.setTimestamp(2, Timestamp.from(tick.ingestTs()));
                statement.setString(3, tick.instrumentId());
                statement.setString(4, tick.underlying());
                statement.setBigDecimal(5, tick.lastPrice());
                statement.setBigDecimal(6, tick.bidPrice());
                statement.setBigDecimal(7, tick.askPrice());
                statement.setLong(8, tick.volume());
                statement.setLong(9, tick.openInterest());
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
