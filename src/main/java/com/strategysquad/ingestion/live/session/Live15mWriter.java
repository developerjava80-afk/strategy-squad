package com.strategysquad.ingestion.live.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Writes completed 15-minute session buckets into {@code options_live_15m}.
 */
public final class Live15mWriter {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String INSERT_SQL =
            "INSERT INTO options_live_15m"
                    + " (bucket_ts, session_date, instrument_id, time_bucket_15m,"
                    + "  avg_price, min_price, max_price, volume_sum, sample_count, last_updated_ts)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public int write(Connection connection, List<Live15mBucket> buckets) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(buckets, "buckets must not be null");
        if (buckets.isEmpty()) return 0;

        try (PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
            for (Live15mBucket b : buckets) {
                stmt.setTimestamp(1, Timestamp.from(b.bucketTs()));
                stmt.setTimestamp(2, Timestamp.from(b.sessionDate().atStartOfDay(IST).toInstant()));
                stmt.setString(3, b.instrumentId());
                stmt.setInt(4, b.timeBucket15m());
                stmt.setDouble(5, b.avgPrice());
                stmt.setDouble(6, b.minPrice());
                stmt.setDouble(7, b.maxPrice());
                stmt.setLong(8, b.volumeSum());
                stmt.setLong(9, b.sampleCount());
                stmt.setTimestamp(10, Timestamp.from(b.lastUpdatedTs()));
                stmt.addBatch();
            }
            return successCount(stmt.executeBatch());
        }
    }

    private static int successCount(int[] results) throws SQLException {
        int count = 0;
        for (int r : results) {
            if (r == Statement.EXECUTE_FAILED) throw new SQLException("Batch write failed for live 15m bucket");
            if (r == Statement.SUCCESS_NO_INFO || r >= 0) count++;
        }
        return count;
    }
}
