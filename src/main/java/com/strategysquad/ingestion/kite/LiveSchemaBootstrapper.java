package com.strategysquad.ingestion.kite;

import com.strategysquad.support.QuestDbConnectionFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Ensures the live-session tables exist before live ingestion starts.
 *
 * <p>The live console is often started outside a formal migration runner, so this
 * bootstrapper creates the V004 live tables on demand. Historical/canonical tables
 * remain untouched.
 */
public final class LiveSchemaBootstrapper {

    private static final String CREATE_OPTIONS_LIVE_ENRICHED = """
            CREATE TABLE IF NOT EXISTS options_live_enriched (
                exchange_ts          TIMESTAMP,
                instrument_id        STRING,
                underlying           SYMBOL,
                option_type          SYMBOL,
                strike               DOUBLE,
                expiry_date          TIMESTAMP,
                last_price           DOUBLE,
                underlying_price     DOUBLE,
                minutes_to_expiry    INT,
                time_bucket_15m      INT,
                moneyness_pct        DOUBLE,
                moneyness_points     DOUBLE,
                moneyness_bucket     INT,
                volume               LONG
            ) timestamp(exchange_ts) PARTITION BY DAY
            """;

    private static final String CREATE_OPTIONS_LIVE_15M = """
            CREATE TABLE IF NOT EXISTS options_live_15m (
                bucket_ts        TIMESTAMP,
                session_date     TIMESTAMP,
                instrument_id    STRING,
                time_bucket_15m  INT,
                avg_price        DOUBLE,
                min_price        DOUBLE,
                max_price        DOUBLE,
                volume_sum       LONG,
                sample_count     LONG,
                last_updated_ts  TIMESTAMP
            ) timestamp(bucket_ts) PARTITION BY DAY
            """;

    private static final String CREATE_LIVE_STRUCTURE_SNAPSHOT = """
            CREATE TABLE IF NOT EXISTS live_structure_snapshot (
                snapshot_ts      TIMESTAMP,
                session_date     TIMESTAMP,
                structure_key    STRING,
                underlying       SYMBOL,
                expiry_type      SYMBOL,
                net_premium      DOUBLE,
                leg_count        INT,
                leg_detail_json  STRING
            ) timestamp(snapshot_ts) PARTITION BY DAY
            """;

    private final String jdbcUrl;

    public LiveSchemaBootstrapper(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public void ensureLiveTables() throws SQLException {
        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_OPTIONS_LIVE_ENRICHED);
            statement.execute(CREATE_OPTIONS_LIVE_15M);
            statement.execute(CREATE_LIVE_STRUCTURE_SNAPSHOT);
            validateLiveTables(statement);
        }
    }

    private static void validateLiveTables(Statement statement) throws SQLException {
        ensureTableColumns(statement, "options_live_enriched", Set.of(
                "exchange_ts", "instrument_id", "underlying", "option_type", "strike",
                "expiry_date", "last_price", "underlying_price", "minutes_to_expiry",
                "time_bucket_15m", "moneyness_pct", "moneyness_points", "moneyness_bucket", "volume"
        ));
        ensureTableColumns(statement, "options_live_15m", Set.of(
                "bucket_ts", "session_date", "instrument_id", "time_bucket_15m", "avg_price",
                "min_price", "max_price", "volume_sum", "sample_count", "last_updated_ts"
        ));
        ensureTableColumns(statement, "live_structure_snapshot", Set.of(
                "snapshot_ts", "session_date", "structure_key", "underlying", "expiry_type",
                "net_premium", "leg_count", "leg_detail_json"
        ));
    }

    private static void ensureTableColumns(Statement statement, String tableName, Set<String> expectedColumns)
            throws SQLException {
        Set<String> actualColumns = new HashSet<>();
        try (ResultSet rs = statement.executeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_name = '" + tableName + "'")) {
            while (rs.next()) {
                actualColumns.add(rs.getString("column_name"));
            }
        }
        if (!actualColumns.containsAll(expectedColumns)) {
            throw new IllegalStateException(
                    "Live table '" + tableName + "' is missing required columns. "
                            + "Apply or reapply V004__live_session_tables.sql, or let the live console create a clean schema."
            );
        }
    }
}
