package com.strategysquad.ingestion.bhavcopy;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * Writes spot (index) data into {@code spot_historical}.
 *
 * <p>Column mapping follows the frozen Golden Source DDL (V001):
 * {@code trade_ts, trade_date, underlying, open_price, high_price, low_price, close_price}.
 */
public class SpotBhavcopyWriter {
    private static final String DEFAULT_INSERT_SQL =
            "INSERT INTO spot_historical"
                    + " (trade_ts, trade_date, underlying, open_price, high_price,"
                    + "  low_price, close_price)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    /** Market close time used to normalize trade_date into trade_ts (IST 15:30). */
    private static final LocalTime MARKET_CLOSE_IST = LocalTime.of(15, 30);

    private final String insertSql;

    public SpotBhavcopyWriter() {
        this(DEFAULT_INSERT_SQL);
    }

    public SpotBhavcopyWriter(String insertSql) {
        this.insertSql = Objects.requireNonNull(insertSql, "insertSql must not be null");
    }

    public int write(Connection connection, List<SpotBhavcopyRecord> records) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(records, "records must not be null");
        if (records.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (SpotBhavcopyRecord record : records) {
                Timestamp tradeTs = Timestamp.valueOf(record.tradeDate().atTime(MARKET_CLOSE_IST));
                statement.setTimestamp(1, tradeTs);
                statement.setDate(2, Date.valueOf(record.tradeDate()));
                statement.setString(3, record.underlying());
                statement.setBigDecimal(4, record.open());
                statement.setBigDecimal(5, record.high());
                statement.setBigDecimal(6, record.low());
                statement.setBigDecimal(7, record.close());
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
