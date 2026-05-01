package com.strategysquad.scope;

import com.strategysquad.support.QuestDbConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;

/**
 * Produces the lightweight startup metadata payload for {@code GET /api/bootstrap/metadata}.
 *
 * <p>This service reads exclusively from {@code instrument_master} — it makes
 * no Kite API calls and does not touch any live-session state. It is safe to
 * call before any scope has been activated.
 *
 * <p>The returned payload tells the UI:
 * <ul>
 *   <li>Which underlyings are available ({@code "NIFTY"}, {@code "BANKNIFTY"}).
 *   <li>Which expiries are present in {@code instrument_master} for each underlying,
 *       along with the expiry type and instrument count.  Past expiries are excluded.
 *   <li>The freshness of {@code instrument_master} (UTC timestamp of the most-recent
 *       {@code updated_at} row), so the UI can warn when the data is stale (> 24h).
 *   <li>The currently active scope for today, if any (or {@code null}).
 *   <li>Whether a previously saved scope was found but is now stale (the expiry
 *       it named is no longer present in {@code instrument_master} as a future date).
 * </ul>
 *
 * <p><strong>Performance contract:</strong> must complete in under 100 ms on a warm
 * QuestDB instance with a populated {@code instrument_master}. The queries are
 * GROUP BY aggregates over a lookup table (not the time-series partitioned tables)
 * and should be fast.
 */
public class BootstrapMetadataService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Select all distinct future expiries from instrument_master grouped by
     * underlying + expiry_date + expiry_type, with an instrument count per group.
     * Expiries in the past (expiry_date < today midnight IST) are excluded.
     */
    private static final String EXPIRIES_SQL_TEMPLATE =
            "SELECT underlying, expiry_date, expiry_type, COUNT(*) AS instrument_count " +
            "FROM instrument_master " +
            "WHERE is_active = true " +
            "  AND expiry_date >= '%s' " +
            "GROUP BY underlying, expiry_date, expiry_type " +
            "ORDER BY underlying, expiry_date";

    /**
     * Most-recent updated_at across the entire instrument_master table.
     * This is the proxy for instrument-master freshness.
     */
    private static final String FRESHNESS_SQL =
            "SELECT MAX(updated_at) AS max_updated_at FROM instrument_master";

    private final String jdbcUrl;
    final ScopeStore scopeStore; // package-accessible for subclass use in tests

    /**
     * Creates a service backed by the given QuestDB JDBC URL and scope store.
     *
     * @param jdbcUrl    QuestDB PostgreSQL-wire URL
     * @param scopeStore store from which the active scope for today is read
     */
    public BootstrapMetadataService(String jdbcUrl, ScopeStore scopeStore) {
        this.jdbcUrl    = Objects.requireNonNull(jdbcUrl,    "jdbcUrl must not be null");
        this.scopeStore = Objects.requireNonNull(scopeStore, "scopeStore must not be null");
    }

    /**
     * Returns the underlying {@link ScopeStore}. Accessible to subclasses (e.g., test stubs)
     * that need to reproduce the staleness-check logic without a real database connection.
     */
    ScopeStore scopeStore() {
        return scopeStore;
    }

    /**
     * Loads the full bootstrap metadata payload.
     *
     * @param today the current trading day in IST
     * @return the metadata snapshot
     * @throws SQLException if the database is unreachable
     */
    public BootstrapMetadata load(LocalDate today) throws SQLException {
        Objects.requireNonNull(today, "today must not be null");

        List<ExpiryInfo> expiries;
        Instant masterFreshness;

        Timestamp todayMidnight = toMidnightUtcTimestamp(today);

        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
            expiries = loadExpiries(conn, todayMidnight);
            masterFreshness = loadFreshness(conn);
        }

        // Resolve active scope — load from store, then check whether the expiry
        // is still present as a future date in instrument_master.
        Optional<ScopeStore.StoredScope> stored;
        try {
            stored = scopeStore.loadActive(today);
        } catch (SQLException ex) {
            // scope_state table may not yet exist (pre-migration run); treat as no scope.
            stored = Optional.empty();
        }

        ActiveScopeInfo activeScopeInfo = null;
        boolean previousScopeStale = false;

        if (stored.isPresent()) {
            ScopeStore.StoredScope ss = stored.get();
            // Check whether the stored expiry is still present in the future-expiry list.
            boolean expiryStillValid = expiries.stream()
                    .anyMatch(e -> e.underlying().equals(ss.scope().underlying())
                                && e.expiry().equals(ss.scope().expiry()));
            if (expiryStillValid) {
                activeScopeInfo = new ActiveScopeInfo(
                        ss.scopeId(),
                        ss.scope(),
                        ss.lastActiveAt()
                );
            } else {
                previousScopeStale = true;
            }
        }

        return new BootstrapMetadata(
                List.of("NIFTY", "BANKNIFTY"),
                expiries,
                masterFreshness,
                activeScopeInfo,
                previousScopeStale
        );
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private List<ExpiryInfo> loadExpiries(Connection conn, Timestamp fromTs) throws SQLException {
        List<ExpiryInfo> result = new ArrayList<>();
        String sql = String.format(EXPIRIES_SQL_TEMPLATE, toQuestDbTimestampLiteral(fromTs.toInstant()));
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String underlying  = rs.getString("underlying");
                    LocalDate expiry   = rs.getTimestamp("expiry_date", utcCal()).toInstant()
                                          .atZone(IST).toLocalDate();
                    String expiryTypeStr = rs.getString("expiry_type");
                    ExpiryType expiryType;
                    try {
                        expiryType = ExpiryType.valueOf(expiryTypeStr);
                    } catch (IllegalArgumentException ex) {
                        // Unknown expiry_type value in instrument_master — skip this row.
                        continue;
                    }
                    int instrumentCount = rs.getInt("instrument_count");
                    result.add(new ExpiryInfo(underlying, expiry, expiryType, instrumentCount));
                }
            }
        }
        return result;
    }

    private Instant loadFreshness(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(FRESHNESS_SQL);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp("max_updated_at");
                if (ts != null) {
                    return ts.toInstant();
                }
            }
        }
        return Instant.EPOCH; // instrument_master is empty
    }

    private static Calendar utcCal() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static Timestamp toMidnightUtcTimestamp(LocalDate date) {
        return Timestamp.from(date.atStartOfDay(IST).toInstant());
    }

    private static String toQuestDbTimestampLiteral(Instant instant) {
        return instant.toString().replace("Z", ".000000Z");
    }

    // =========================================================================
    // Response types
    // =========================================================================

    /**
     * Metadata about a single expiry available in {@code instrument_master}.
     *
     * @param underlying      underlying index, e.g. {@code "NIFTY"}
     * @param expiry          expiry date
     * @param expiryType      WEEKLY or MONTHLY
     * @param instrumentCount number of active CE+PE contracts for this expiry
     */
    public record ExpiryInfo(
            String underlying,
            LocalDate expiry,
            ExpiryType expiryType,
            int instrumentCount
    ) {}

    /**
     * The currently active scope for today, as returned in the bootstrap payload.
     *
     * @param scopeId      persisted scope ID
     * @param scope        the full scope parameters
     * @param lastActiveAt when the scope was last activated
     */
    public record ActiveScopeInfo(
            String scopeId,
            Scope scope,
            Instant lastActiveAt
    ) {}

    /**
     * The full bootstrap metadata payload.
     *
     * @param underlyings        always {@code ["NIFTY","BANKNIFTY"]}
     * @param expiries           future expiries available in instrument_master
     * @param instrumentMasterFreshness UTC timestamp of the most-recent instrument_master update
     * @param activeScope        the active scope for today, or {@code null}
     * @param previousScopeStale true when a scope was found for today but its expiry has passed
     */
    public record BootstrapMetadata(
            List<String> underlyings,
            List<ExpiryInfo> expiries,
            Instant instrumentMasterFreshness,
            ActiveScopeInfo activeScope,
            boolean previousScopeStale
    ) {}
}
