package com.strategysquad.aggregation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * Writes aggregated rows into {@code options_context_buckets}.
 */
public class OptionsContextBucketWriter {
    private static final String DEFAULT_INSERT_SQL =
            "INSERT INTO options_context_buckets"
                    + " (bucket_ts, underlying, option_type, time_bucket_15m,"
                    + "  moneyness_bucket, avg_option_price, avg_price_to_spot_ratio,"
                    + "  avg_volume, sample_count)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String insertSql;

    public OptionsContextBucketWriter() {
        this(DEFAULT_INSERT_SQL);
    }

    public OptionsContextBucketWriter(String insertSql) {
        this.insertSql = Objects.requireNonNull(insertSql, "insertSql must not be null");
    }

    public int write(Connection connection, List<OptionsContextBucket> buckets) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(buckets, "buckets must not be null");
        if (buckets.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (OptionsContextBucket bucket : buckets) {
                statement.setTimestamp(1, Timestamp.from(bucket.bucketTs()));
                statement.setString(2, bucket.underlying());
                statement.setString(3, bucket.optionType());
                statement.setInt(4, bucket.timeBucket15m());
                statement.setInt(5, bucket.moneynessBucket());
                statement.setBigDecimal(6, bucket.avgOptionPrice());
                statement.setBigDecimal(7, bucket.avgPriceToSpotRatio());
                statement.setBigDecimal(8, bucket.avgVolume());
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
