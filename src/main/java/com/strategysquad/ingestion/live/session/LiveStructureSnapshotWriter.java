package com.strategysquad.ingestion.live.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Materializes current-session structure snapshots into {@code live_structure_snapshot}.
 *
 * <p>This table is a live-session artifact only. It is intentionally isolated from
 * the canonical historical strategy outputs so replay/backfill truth remains unchanged.
 */
public final class LiveStructureSnapshotWriter {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String INSERT_SQL =
            "INSERT INTO live_structure_snapshot"
                    + " (snapshot_ts, session_date, structure_key, underlying, expiry_type,"
                    + "  net_premium, leg_count, leg_detail_json)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    public int write(Connection connection, List<SnapshotRow> rows) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(rows, "rows must not be null");
        if (rows.isEmpty()) {
            return 0;
        }

        try (PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
            for (SnapshotRow row : rows) {
                stmt.setTimestamp(1, Timestamp.from(row.snapshotTs()));
                stmt.setTimestamp(2, Timestamp.from(row.sessionDate().atStartOfDay(IST).toInstant()));
                stmt.setString(3, row.structureKey());
                stmt.setString(4, row.underlying());
                stmt.setString(5, row.expiryType());
                stmt.setDouble(6, row.netPremium());
                stmt.setInt(7, row.legCount());
                stmt.setString(8, row.legDetailJson());
                stmt.addBatch();
            }
            return successCount(stmt.executeBatch());
        }
    }

    private static int successCount(int[] results) throws SQLException {
        int count = 0;
        for (int result : results) {
            if (result == Statement.EXECUTE_FAILED) {
                throw new SQLException("Batch write failed for live structure snapshot");
            }
            if (result == Statement.SUCCESS_NO_INFO || result >= 0) {
                count++;
            }
        }
        return count;
    }

    public record SnapshotRow(
            Instant snapshotTs,
            LocalDate sessionDate,
            String structureKey,
            String underlying,
            String expiryType,
            double netPremium,
            int legCount,
            String legDetailJson
    ) {
    }
}
