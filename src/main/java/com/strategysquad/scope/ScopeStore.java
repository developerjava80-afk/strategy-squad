package com.strategysquad.scope;

import com.strategysquad.support.QuestDbConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;

/**
 * Day-scoped persistence for the active {@link Scope}.
 *
 * <p>Mirrors the contract and approach of {@code KiteDailyTokenStore}: one row
 * per trading day in {@code scope_state}, upserted on every {@code POST /api/scope}
 * and cleared by {@code DELETE /api/scope}.
 *
 * <p>On application startup, {@link #loadActive(LocalDate)} reads the most-recent
 * row for today. If the expiry stored in that row is still present in
 * {@code instrument_master}, the scope is restored automatically. If the expiry
 * has passed, the row is left in place but the caller should treat it as stale
 * (return {@code activeScope=null, previousScopeStale=true} in the bootstrap
 * metadata response).
 *
 * <p><strong>QuestDB WAL note:</strong> {@code scope_state} uses WAL mode
 * ({@code PARTITION BY DAY WAL}). QuestDB WAL tables do not support the
 * PostgreSQL-wire {@code ON CONFLICT} upsert syntax. The store uses a
 * delete-then-insert pattern within the same logical operation instead.
 * This is safe because scope changes are low-frequency (at most a few per
 * trading day) and the table is not append-only golden-source data.
 *
 * <p><strong>Thread safety:</strong> this class is not thread-safe. Callers
 * ({@link com.strategysquad.scope.ScopeService}) must synchronise externally.
 */
public class ScopeStore {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS scope_state (" +
            " trading_date TIMESTAMP," +
            " scope_id VARCHAR," +
            " user_id SYMBOL," +
            " underlying SYMBOL," +
            " expiry TIMESTAMP," +
            " expiry_type SYMBOL," +
            " strategy SYMBOL," +
            " strike_window VARCHAR," +
            " max_candidates INT," +
            " created_at TIMESTAMP," +
            " last_active_at TIMESTAMP" +
            ") TIMESTAMP(trading_date) PARTITION BY DAY WAL";

    private static final String TRUNCATE_SQL =
            "TRUNCATE TABLE scope_state";

    private static final String INSERT_SQL =
            "INSERT INTO scope_state " +
            "(trading_date, scope_id, user_id, underlying, expiry, expiry_type, " +
            " strategy, strike_window, max_candidates, created_at, last_active_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Reads the scope for today from scope_state, selecting the row with the
     * most-recent last_active_at. Returns {@link Optional#empty()} when no
     * row exists for today.
     */
    private static final String SELECT_TODAY_SQL =
            "SELECT scope_id, underlying, expiry, expiry_type, strategy, " +
            "       strike_window, max_candidates, created_at, last_active_at " +
            "FROM scope_state " +
            "WHERE trading_date = ? " +
            "ORDER BY last_active_at DESC " +
            "LIMIT 1";

    private final String jdbcUrl;

    /**
     * Creates a ScopeStore backed by the given QuestDB JDBC URL.
     *
     * @param jdbcUrl QuestDB PostgreSQL-wire URL, e.g.
     *                {@code jdbc:postgresql://localhost:8812/qdb}
     */
    public ScopeStore(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    /**
     * Persists the scope for today, replacing any previously saved scope for
     * the same trading day (delete-then-insert).
     *
     * @param tradingDate the current trading day (IST date)
     * @param scope       the scope to persist
     * @throws SQLException if the database is unreachable or the write fails
     */
    public void save(LocalDate tradingDate, Scope scope) throws SQLException {
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        Timestamp tradingTs = toMidnightUtcTimestamp(tradingDate);
        Instant now = Instant.now();
        String scopeId = scope.toScopeId(tradingDate);
        String strikeWindowJson = StrikeWindowJson.toJson(scope.strikeWindow());

        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
            ensureTableExists(conn);
            // Keep only the latest local scope state.
            try (Statement truncate = conn.createStatement()) {
                truncate.executeUpdate(TRUNCATE_SQL);
            }
            try (PreparedStatement ins = conn.prepareStatement(INSERT_SQL)) {
                ins.setTimestamp(1, toMidnightUtcTimestamp(tradingDate));        // trading_date
                ins.setString   (2, scopeId);                                   // scope_id
                ins.setString   (3, "default");                                 // user_id
                ins.setString   (4, scope.underlying());                        // underlying
                ins.setTimestamp(5, toMidnightUtcTimestamp(scope.expiry()));       // expiry
                ins.setString   (6, scope.expiryType().name());                 // expiry_type
                ins.setString   (7, scope.strategy().name());                   // strategy
                ins.setString   (8, strikeWindowJson);                          // strike_window
                ins.setInt      (9, scope.maxCandidates());                     // max_candidates
                ins.setTimestamp(10, Timestamp.from(now));                      // created_at
                ins.setTimestamp(11, Timestamp.from(now));                      // last_active_at
                ins.executeUpdate();
            }
        }
    }

    /**
     * Loads the active scope for today. Returns {@link Optional#empty()} when no
     * scope has been activated for this trading day.
     *
     * <p>The caller is responsible for checking whether the returned scope's expiry
     * is still valid in {@code instrument_master}. If the expiry has passed, the
     * scope should be treated as stale.
     *
     * @param tradingDate the current trading day (IST date)
     * @return the most-recently activated scope for today, or empty
     * @throws SQLException if the database is unreachable
     */
    public Optional<StoredScope> loadActive(LocalDate tradingDate) throws SQLException {
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");

        Timestamp tradingTs = toMidnightUtcTimestamp(tradingDate);

        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
            ensureTableExists(conn);
            try (PreparedStatement ps = conn.prepareStatement(SELECT_TODAY_SQL)) {
                ps.setTimestamp(1, tradingTs);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }

                    String scopeId        = rs.getString("scope_id");
                    String underlying     = rs.getString("underlying");
                    LocalDate expiry      = rs.getTimestamp("expiry", utcCal()).toInstant()
                            .atZone(IST).toLocalDate();
                    ExpiryType expiryType = ExpiryType.valueOf(rs.getString("expiry_type"));
                    StrategyKind strategy = StrategyKind.valueOf(rs.getString("strategy"));
                    StrikeWindow window   = StrikeWindowJson.fromJson(rs.getString("strike_window"));
                    int maxCandidates     = rs.getInt("max_candidates");
                    Instant createdAt     = rs.getTimestamp("created_at").toInstant();
                    Instant lastActiveAt  = rs.getTimestamp("last_active_at").toInstant();

                    Scope scope = new Scope(underlying, expiry, expiryType, strategy, window, maxCandidates);
                    return Optional.of(new StoredScope(scopeId, scope, tradingDate, createdAt, lastActiveAt));
                }
            }
        } catch (SQLException ex) {
            if (tableMissing(ex)) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    /**
     * Removes today's scope row. Called by {@code DELETE /api/scope}.
     * After this call, {@link #loadActive(LocalDate)} returns empty for today.
     *
     * @param tradingDate the current trading day (IST date)
     * @throws SQLException if the database is unreachable
     */
    public void clear(LocalDate tradingDate) throws SQLException {
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");

        Timestamp tradingTs = toMidnightUtcTimestamp(tradingDate);

        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
            ensureTableExists(conn);
            try (Statement truncate = conn.createStatement()) {
                truncate.executeUpdate(TRUNCATE_SQL);
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Timestamp toMidnightUtcTimestamp(LocalDate date) {
        return Timestamp.from(date.atStartOfDay(IST).toInstant());
    }

    private static Calendar utcCal() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static void ensureTableExists(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(CREATE_TABLE_SQL);
        }
    }

    private static boolean tableMissing(SQLException ex) {
        String msg = ex.getMessage();
        return msg != null && msg.contains("table does not exist [table=scope_state]");
    }

    // =========================================================================
    // Nested types
    // =========================================================================

    /**
     * A {@link Scope} as loaded from {@code scope_state}, with its persisted
     * metadata (scope ID and lifecycle timestamps).
     */
    public record StoredScope(
            String scopeId,
            Scope scope,
            LocalDate tradingDate,
            Instant createdAt,
            Instant lastActiveAt
    ) {
        public StoredScope {
            Objects.requireNonNull(scopeId,      "scopeId must not be null");
            Objects.requireNonNull(scope,        "scope must not be null");
            Objects.requireNonNull(tradingDate,  "tradingDate must not be null");
            Objects.requireNonNull(createdAt,    "createdAt must not be null");
            Objects.requireNonNull(lastActiveAt, "lastActiveAt must not be null");
        }
    }

    /**
     * Minimal JSON serialiser/deserialiser for {@link StrikeWindow}.
     *
     * <p>Kept package-private so tests can exercise it directly. Uses hand-rolled
     * parsing to avoid pulling in a JSON library — the payload is small and
     * structurally fixed.
     */
    static final class StrikeWindowJson {

        private StrikeWindowJson() {}

        static String toJson(StrikeWindow window) {
            if (window instanceof StrikeWindow.AtmPct w) {
                return "{\"kind\":\"ATM_PCT\",\"pct\":" + w.pct() + "}";
            }
            if (window instanceof StrikeWindow.AtmPoints w) {
                return "{\"kind\":\"ATM_POINTS\",\"points\":" + w.points() + "}";
            }
            if (window instanceof StrikeWindow.ExplicitRange w) {
                return "{\"kind\":\"EXPLICIT_RANGE\",\"low\":" + w.low() +
                        ",\"high\":" + w.high() + "}";
            }
            if (window instanceof StrikeWindow.LegsOnly w) {
                StringBuilder sb = new StringBuilder("{\"kind\":\"LEGS_ONLY\",\"instrumentIds\":[");
                for (int i = 0; i < w.instrumentIds().size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(w.instrumentIds().get(i)).append('"');
                }
                sb.append("]}");
                return sb.toString();
            }
            throw new IllegalArgumentException("Unknown StrikeWindow type: " + window.getClass().getName());
        }

        static StrikeWindow fromJson(String json) {
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("strike_window JSON must not be blank");
            }
            String kind = extractString(json, "kind");
            return switch (kind) {
                case "ATM_PCT"       -> new StrikeWindow.AtmPct(extractDouble(json, "pct"));
                case "ATM_POINTS"    -> new StrikeWindow.AtmPoints(extractDouble(json, "points"));
                case "EXPLICIT_RANGE"-> new StrikeWindow.ExplicitRange(
                                                extractDouble(json, "low"),
                                                extractDouble(json, "high"));
                case "LEGS_ONLY"     -> new StrikeWindow.LegsOnly(extractStringArray(json, "instrumentIds"));
                default -> throw new IllegalArgumentException("Unknown StrikeWindow kind: " + kind);
            };
        }

        /** Extracts a string value for the given key from a simple flat JSON object. */
        private static String extractString(String json, String key) {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) {
                throw new IllegalArgumentException("Key '" + key + "' not found in: " + json);
            }
            start += search.length();
            int end = json.indexOf('"', start);
            if (end < 0) {
                throw new IllegalArgumentException("Unterminated string for key '" + key + "'");
            }
            return json.substring(start, end);
        }

        /** Extracts a numeric value for the given key from a simple flat JSON object. */
        private static double extractDouble(String json, String key) {
            String search = "\"" + key + "\":";
            int start = json.indexOf(search);
            if (start < 0) {
                throw new IllegalArgumentException("Key '" + key + "' not found in: " + json);
            }
            start += search.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end))
                    || json.charAt(end) == '.' || json.charAt(end) == '-')) {
                end++;
            }
            return Double.parseDouble(json.substring(start, end));
        }

        /** Extracts a JSON string array value for the given key. */
        private static java.util.List<String> extractStringArray(String json, String key) {
            String search = "\"" + key + "\":[";
            int start = json.indexOf(search);
            if (start < 0) {
                throw new IllegalArgumentException("Key '" + key + "' not found in: " + json);
            }
            start += search.length();
            int end = json.indexOf(']', start);
            if (end < 0) {
                throw new IllegalArgumentException("Unterminated array for key '" + key + "'");
            }
            String arrayContent = json.substring(start, end).trim();
            if (arrayContent.isEmpty()) {
                throw new IllegalArgumentException("instrumentIds array must not be empty");
            }
            java.util.List<String> result = new java.util.ArrayList<>();
            for (String token : arrayContent.split(",")) {
                String cleaned = token.trim();
                if (cleaned.startsWith("\"")) cleaned = cleaned.substring(1);
                if (cleaned.endsWith("\""))   cleaned = cleaned.substring(0, cleaned.length() - 1);
                if (!cleaned.isBlank()) result.add(cleaned);
            }
            return result;
        }
    }
}
