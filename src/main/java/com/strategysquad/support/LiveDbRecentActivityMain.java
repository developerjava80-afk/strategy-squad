package com.strategysquad.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Quick local operator utility: confirms whether live data tables received updates
 * within the last N seconds.
 */
public final class LiveDbRecentActivityMain {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final int DEFAULT_WINDOW_SECONDS = 30;

    private static final List<TableProbe> TABLES = List.of(
            new TableProbe("spot_live", "exchange_ts"),
            new TableProbe("options_live", "exchange_ts"),
            new TableProbe("options_live_enriched", "exchange_ts"),
            new TableProbe("options_live_15m", "last_updated_ts"),
            new TableProbe("live_structure_snapshot", "snapshot_ts")
    );

    private LiveDbRecentActivityMain() {
    }

    public static void main(String[] args) throws Exception {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("PostgreSQL JDBC driver not available", exception);
        }

        String jdbcUrl = args.length >= 1 && !args[0].isBlank() ? args[0] : DEFAULT_JDBC_URL;
        int windowSeconds = args.length >= 2 && !args[1].isBlank()
                ? Integer.parseInt(args[1])
                : DEFAULT_WINDOW_SECONDS;

        List<ProbeResult> results = new ArrayList<>();
        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl)) {
            for (TableProbe table : TABLES) {
                results.add(probe(connection, table, windowSeconds));
            }
        }

        boolean anyRecent = results.stream().anyMatch(item -> item.recentCount() > 0);
        System.out.printf("Live DB activity check window: last %d seconds%n", windowSeconds);
        System.out.printf("JDBC URL: %s%n", jdbcUrl);
        System.out.println();
        for (ProbeResult result : results) {
            System.out.printf(
                    "%-24s total=%-8d recent=%-8d last_ts=%s%n",
                    result.tableName(),
                    result.totalCount(),
                    result.recentCount(),
                    result.lastTimestamp() == null ? "-" : result.lastTimestamp()
            );
        }
        System.out.println();
        System.out.println(anyRecent
                ? "STATUS: OK - live DB updates detected in the requested window."
                : "STATUS: STALE - no live DB updates detected in the requested window.");
    }

    private static ProbeResult probe(Connection connection, TableProbe table, int windowSeconds) throws SQLException {
        long totalCount = scalarLong(
                connection,
                "SELECT count(*) FROM " + table.tableName()
        );
        long recentCount = scalarLong(
                connection,
                "SELECT count(*) FROM " + table.tableName()
                        + " WHERE " + table.timestampColumn() + " >= dateadd('s', ?, now())",
                -windowSeconds
        );
        Timestamp lastTimestamp = scalarTimestamp(
                connection,
                "SELECT max(" + table.timestampColumn() + ") FROM " + table.tableName()
        );
        return new ProbeResult(table.tableName(), totalCount, recentCount, lastTimestamp);
    }

    private static long scalarLong(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Timestamp scalarTimestamp(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    private record TableProbe(String tableName, String timestampColumn) {
    }

    private record ProbeResult(String tableName, long totalCount, long recentCount, Timestamp lastTimestamp) {
    }
}
