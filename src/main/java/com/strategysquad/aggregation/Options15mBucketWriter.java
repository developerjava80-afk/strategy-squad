package com.strategysquad.aggregation;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * Writes aggregated rows into {@code options_15m_buckets}.
 */
public class Options15mBucketWriter {
    private static final String DEFAULT_INSERT_SQL =
            "INSERT INTO options_15m_buckets"
                    + " (bucket_ts, trade_date, instrument_id, time_bucket_15m,"
                    + "  avg_price, min_price, max_price, volume_sum, sample_count)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String insertSql;

    public Options15mBucketWriter() {
        this(DEFAULT_INSERT_SQL);
    }

    public Options15mBucketWriter(String insertSql) {
        this.insertSql = Objects.requireNonNull(insertSql, "insertSql must not be null");
    }

    public int write(Connection connection, List<Options15mBucket> buckets) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(buckets, "buckets must not be null");
        if (buckets.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (Options15mBucket bucket : buckets) {
                statement.setTimestamp(1, Timestamp.from(bucket.bucketTs()));
                statement.setDate(2, Date.valueOf(bucket.tradeDate()));
                statement.setString(3, bucket.instrumentId());
                statement.setInt(4, bucket.timeBucket15m());
                statement.setBigDecimal(5, bucket.avgPrice());
                statement.setBigDecimal(6, bucket.minPrice());
                statement.setBigDecimal(7, bucket.maxPrice());
                statement.setLong(8, bucket.volumeSum());
                statement.setLong(9, bucket.sampleCount());
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
