package com.strategysquad.derived;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Objects;

/**
 * Writes derived PCR rows into {@code pcr_historical}.
 */
public class PcrHistoricalWriter {
    private static final String DEFAULT_INSERT_SQL =
            "INSERT INTO pcr_historical"
                    + " (bucket_ts, trade_date, underlying, pcr_by_volume, pcr_by_open_interest,"
                    + "  put_volume, call_volume, put_open_interest, call_open_interest, sample_count)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String insertSql;

    public PcrHistoricalWriter() {
        this(DEFAULT_INSERT_SQL);
    }

    public PcrHistoricalWriter(String insertSql) {
        this.insertSql = Objects.requireNonNull(insertSql, "insertSql must not be null");
    }

    public int write(Connection connection, List<PcrHistoricalPoint> points) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(points, "points must not be null");
        if (points.isEmpty()) {
            return 0;
        }

        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (PcrHistoricalPoint point : points) {
                statement.setTimestamp(1, Timestamp.from(point.bucketTs()));
                statement.setDate(2, Date.valueOf(point.tradeDate()));
                statement.setString(3, point.underlying());
                if (point.pcrByVolume() == null) {
                    statement.setNull(4, Types.DOUBLE);
                } else {
                    statement.setDouble(4, point.pcrByVolume().doubleValue());
                }
                if (point.pcrByOpenInterest() == null) {
                    statement.setNull(5, Types.DOUBLE);
                } else {
                    statement.setDouble(5, point.pcrByOpenInterest().doubleValue());
                }
                statement.setLong(6, point.putVolume());
                statement.setLong(7, point.callVolume());
                statement.setLong(8, point.putOpenInterest());
                statement.setLong(9, point.callOpenInterest());
                statement.setLong(10, point.sampleCount());
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
