package com.strategysquad.scope;

import com.strategysquad.support.QuestDbConnectionFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

/**
 * Resolves a bounded set of instruments from {@code instrument_master} for a given {@link Scope}.
 *
 * <p>This is the authoritative entry point for translating a user's scope declaration into a
 * concrete, size-bounded list of {@link InstrumentRef} objects. It makes no Kite API calls —
 * all data comes from the locally-cached {@code instrument_master} table.
 *
 * <h2>Resolution pipeline</h2>
 * <ol>
 *   <li>Validate the scope: underlying must be NIFTY or BANKNIFTY; expiry must be present
 *       in {@code instrument_master}; weekly and monthly expiries are never mixed.</li>
 *   <li>Translate the {@link StrikeWindow} into SQL bounds:
 *       <ul>
 *         <li>{@link StrikeWindow.AtmPct} / {@link StrikeWindow.AtmPoints} →
 *             {@code strike BETWEEN low AND high} derived from {@code spotEstimate}.</li>
 *         <li>{@link StrikeWindow.ExplicitRange} → {@code strike BETWEEN low AND high}
 *             using the literal range values.</li>
 *         <li>{@link StrikeWindow.LegsOnly} → {@code instrument_id IN (...)}.</li>
 *       </ul></li>
 *   <li>Apply the hard cap (default {@value #DEFAULT_HARD_CAP}, configurable). If the result set exceeds the cap,
 *       {@link ResolvedUniverse#truncated()} is {@code true} and a narrowing hint is set.</li>
 *   <li>For {@link StrikeWindow.LegsOnly}: validate every requested instrument_id exists and
 *       that CE/PE pairing is complete for strategies that require both legs.</li>
 * </ol>
 *
 * <h2>Domain invariants</h2>
 * <ul>
 *   <li>Only {@code is_active = true} instruments are returned.</li>
 *   <li>Weekly and monthly expiries are selected by {@code expiry_date} and {@code expiry_type}
 *       — they are never mixed in a single resolved universe.</li>
 *   <li>The resolver never reads from any live-data table or calls Kite.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>Instances are stateless and safe for concurrent use.
 */
public class UniverseResolver {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Default hard cap on resolved instruments per request.
     * Overridable via {@code scope.max.strikes.per.expiry} in {@code kite.properties}.
     */
    static final int DEFAULT_HARD_CAP = 100;

    // -------------------------------------------------------------------------
    // SQL
    // -------------------------------------------------------------------------

    /**
     * Base query for strike-range windows (ATM_PCT, ATM_POINTS, EXPLICIT_RANGE).
     * Parameters: (1) underlying, (2) expiry_date, (3) expiry_type, (4) low strike, (5) high strike.
     * Result ordered by strike ASC, option_type ASC for deterministic ordering.
     */
    private static final String SELECT_BY_RANGE_SQL_TEMPLATE =
            "SELECT instrument_id, exchange_token, trading_symbol, option_type, strike, " +
            "       expiry_date, expiry_type, lot_size " +
            "FROM instrument_master " +
            "WHERE underlying    = '%s' " +
            "  AND expiry_date   >= '%s' " +
            "  AND expiry_date   < '%s' " +
            "  AND expiry_type   = '%s' " +
            "  AND option_type   IN ('CE', 'PE') " +
            "  AND is_active     = true " +
            "  AND strike        >= %.6f " +
            "  AND strike        <= %.6f " +
            "ORDER BY strike, option_type";

    /**
     * Query for LegsOnly windows — instrument_id IN (...) is constructed dynamically.
     * Template: replace {@code {PLACEHOLDERS}} with comma-separated '?' tokens.
     */
    private static final String SELECT_BY_IDS_TEMPLATE =
            "SELECT instrument_id, exchange_token, trading_symbol, option_type, strike, " +
            "       expiry_date, expiry_type, lot_size " +
            "FROM instrument_master " +
            "WHERE instrument_id IN ({PLACEHOLDERS}) " +
            "  AND is_active = true " +
            "ORDER BY strike, option_type";

    /**
     * Checks that at least one row for the requested expiry/underlying exists.
     * Used to distinguish EXPIRY_NOT_IN_MASTER from zero-results-after-filtering.
     */
    private static final String CHECK_EXPIRY_SQL_TEMPLATE =
            "SELECT COUNT(*) AS cnt FROM instrument_master " +
            "WHERE underlying = '%s' " +
            "  AND expiry_date >= '%s' " +
            "  AND expiry_date < '%s' " +
            "  AND expiry_type = '%s' " +
            "  AND is_active = true";

    // -------------------------------------------------------------------------
    // Connection supplier seam (allows test injection without live DB)
    // -------------------------------------------------------------------------

    /**
     * Functional interface for opening a JDBC connection.
     * Defined per-class, following the project's established test-seam convention.
     */
    @FunctionalInterface
    interface ConnectionSupplier {
        Connection open() throws SQLException;
    }

    private final ConnectionSupplier connectionSupplier;
    private final int hardCap;

    /**
     * Production constructor — opens connections via {@link QuestDbConnectionFactory}.
     * Uses the default hard cap ({@value #DEFAULT_HARD_CAP}).
     *
     * @param jdbcUrl QuestDB PostgreSQL-wire JDBC URL
     */
    public UniverseResolver(String jdbcUrl) {
        this(jdbcUrl, DEFAULT_HARD_CAP);
    }

    /**
     * Production constructor with a configurable hard cap.
     * Used by {@code KiteLiveConsoleMain} to pass {@code scope.max.strikes.per.expiry}
     * from {@code kite.properties}.
     *
     * @param jdbcUrl  QuestDB PostgreSQL-wire JDBC URL
     * @param hardCap  maximum instruments to return per resolve call (rejects wider requests)
     */
    public UniverseResolver(String jdbcUrl, int hardCap) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        if (hardCap < 1) throw new IllegalArgumentException("hardCap must be >= 1, got: " + hardCap);
        this.connectionSupplier = () -> QuestDbConnectionFactory.open(jdbcUrl);
        this.hardCap = hardCap;
    }

    /**
     * Package-private test constructor — accepts a {@link ConnectionSupplier} so unit tests
     * can inject stub {@link Connection} objects without a live database.
     */
    UniverseResolver(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = Objects.requireNonNull(connectionSupplier,
                "connectionSupplier must not be null");
        this.hardCap = DEFAULT_HARD_CAP;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resolves the bounded instrument universe for the given scope.
     *
     * @param scope         the scope to resolve; must be valid per {@link Scope} invariants
     * @param spotEstimate  current spot price of the underlying, used to compute ATM_PCT /
     *                      ATM_POINTS bounds; ignored for EXPLICIT_RANGE and LEGS_ONLY windows
     * @return the resolved universe, possibly with {@link ResolvedUniverse#truncated()} = true
     * @throws ScopeValidationException if the scope is structurally invalid or the expiry is absent
     *                                  from {@code instrument_master}
     * @throws SQLException             if the database is unreachable
     */
    public ResolvedUniverse resolve(Scope scope, double spotEstimate) throws SQLException {
        Objects.requireNonNull(scope, "scope must not be null");

        try (Connection conn = connectionSupplier.open()) {
            return resolveWithConnection(conn, scope, spotEstimate);
        }
    }

    // -------------------------------------------------------------------------
    // Internal resolution logic
    // -------------------------------------------------------------------------

    private ResolvedUniverse resolveWithConnection(
            Connection conn, Scope scope, double spotEstimate
    ) throws SQLException {

        StrikeWindow window = scope.strikeWindow();

        if (window instanceof StrikeWindow.LegsOnly legsOnly) {
            return resolveLegsOnly(conn, scope, legsOnly);
        }

        // For all range-based windows: verify expiry exists first, then query by range.
        verifyExpiryExists(conn, scope);

        double low;
        double high;

        if (window instanceof StrikeWindow.AtmPct atmPct) {
            double halfRange = spotEstimate * atmPct.pct() / 100.0;
            low  = spotEstimate - halfRange;
            high = spotEstimate + halfRange;
        } else if (window instanceof StrikeWindow.AtmPoints atmPoints) {
            low  = spotEstimate - atmPoints.points();
            high = spotEstimate + atmPoints.points();
        } else if (window instanceof StrikeWindow.ExplicitRange range) {
            low  = range.low();
            high = range.high();
        } else {
            throw new IllegalStateException("Unknown StrikeWindow type: " + window.getClass().getName());
        }

        return queryByRange(conn, scope, low, high);
    }

    // -------------------------------------------------------------------------
    // Range query (ATM_PCT, ATM_POINTS, EXPLICIT_RANGE)
    // -------------------------------------------------------------------------

    private ResolvedUniverse queryByRange(
            Connection conn, Scope scope, double low, double high
    ) throws SQLException {

        List<InstrumentRef> results = new ArrayList<>();

        String sql = String.format(java.util.Locale.ROOT, SELECT_BY_RANGE_SQL_TEMPLATE,
                scope.underlying(),
                toQuestDbTimestampLiteral(scope.expiry()),
                toQuestDbTimestampLiteral(scope.expiry().plusDays(1)),
                scope.expiryType().name(),
                low,
                high);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    // Respect hard cap + 1 so we can detect truncation without fetching all rows
                    if (results.size() > hardCap) {
                        break;
                    }
                    results.add(toInstrumentRef(rs));
                }
        }

        if (results.size() > hardCap) {
            // Drop the sentinel overflow row
            List<InstrumentRef> capped = results.subList(0, hardCap);
            String hint = buildRangeNarrowingHint(scope, low, high, hardCap);
            return ResolvedUniverse.truncated(scope, capped, hint);
        }

        return ResolvedUniverse.of(scope, results);
    }

    // -------------------------------------------------------------------------
    // LegsOnly query
    // -------------------------------------------------------------------------

    private ResolvedUniverse resolveLegsOnly(
            Connection conn, Scope scope, StrikeWindow.LegsOnly legsOnly
    ) throws SQLException {

        List<String> requestedIds = legsOnly.instrumentIds();

        // Build IN (...) clause dynamically
        String placeholders = requestedIds.stream()
                .map(id -> "?")
                .collect(Collectors.joining(", "));
        String sql = SELECT_BY_IDS_TEMPLATE.replace("{PLACEHOLDERS}", placeholders);

        List<InstrumentRef> found = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < requestedIds.size(); i++) {
                ps.setString(i + 1, requestedIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(toInstrumentRef(rs));
                }
            }
        }

        // Validate every requested ID was found
        Set<String> foundIds = found.stream()
                .map(InstrumentRef::instrumentId)
                .collect(Collectors.toSet());
        for (String requestedId : requestedIds) {
            if (!foundIds.contains(requestedId)) {
                throw new ScopeValidationException(
                        "LEGS_INSTRUMENT_NOT_FOUND",
                        "Instrument not found in instrument_master (or not active): " + requestedId,
                        "Verify the instrument_id format: INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE>_<CE|PE>"
                );
            }
        }

        // Validate CE/PE pairing for strategies that require both legs
        if (requiresPairing(scope.strategy())) {
            validatePairing(found, scope);
        }

        // LegsOnly is exact — never truncated (the hard cap is on the caller's requested list size)
        if (found.size() > hardCap) {
            throw new ScopeValidationException(
                    "STRIKE_WINDOW_TOO_WIDE",
                    "LegsOnly list yields " + found.size() + " instruments; hard cap is " + hardCap + ".",
                    "Reduce the number of instrument IDs in the LegsOnly list to <= " + hardCap + "."
            );
        }

        return ResolvedUniverse.of(scope, found);
    }

    // -------------------------------------------------------------------------
    // Expiry existence check
    // -------------------------------------------------------------------------

    /**
     * Throws {@link ScopeValidationException} with code {@code EXPIRY_NOT_IN_MASTER} if
     * no active instruments exist for the scope's underlying + expiry + expiry_type.
     */
    private void verifyExpiryExists(Connection conn, Scope scope) throws SQLException {
        String sql = String.format(CHECK_EXPIRY_SQL_TEMPLATE,
                scope.underlying(),
                toQuestDbTimestampLiteral(scope.expiry()),
                toQuestDbTimestampLiteral(scope.expiry().plusDays(1)),
                scope.expiryType().name());
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
                if (rs.next() && rs.getLong("cnt") == 0) {
                    throw new ScopeValidationException(
                            "EXPIRY_NOT_IN_MASTER",
                            "No active instruments found for " + scope.underlying() +
                            " expiry=" + scope.expiry() + " type=" + scope.expiryType() +
                            " in instrument_master.",
                            "Call POST /api/admin/instruments/refresh to download the latest NFO dump."
                    );
                }
        }
    }

    // -------------------------------------------------------------------------
    // CE/PE pairing validation
    // -------------------------------------------------------------------------

    /**
     * Returns true for strategies that require a matched CE+PE pair at each strike.
     * ANALYSIS_ONLY and single-leg strategies do not require pairing.
     */
    private static boolean requiresPairing(StrategyKind strategy) {
        return switch (strategy) {
            case SHORT_STRANGLE, IRON_CONDOR, LONG_STRADDLE, LONG_STRANGLE -> true;
            case BULL_PUT_SPREAD, BEAR_CALL_SPREAD, ANALYSIS_ONLY -> false;
        };
    }

    /**
     * Validates that every strike in the found set has both a CE and a PE.
     * Throws {@link ScopeValidationException} with code {@code INSTRUMENT_PAIR_INCOMPLETE}
     * if any strike is missing its counterpart.
     */
    private static void validatePairing(List<InstrumentRef> found, Scope scope) {
        Set<BigDecimal> ceStrikes = new HashSet<>();
        Set<BigDecimal> peStrikes = new HashSet<>();
        for (InstrumentRef ref : found) {
            if ("CE".equals(ref.optionType())) ceStrikes.add(ref.strike());
            if ("PE".equals(ref.optionType())) peStrikes.add(ref.strike());
        }
        // Find strikes that appear in one set but not both
        Set<BigDecimal> ceOnly = new HashSet<>(ceStrikes);
        ceOnly.removeAll(peStrikes);
        Set<BigDecimal> peOnly = new HashSet<>(peStrikes);
        peOnly.removeAll(ceStrikes);

        if (!ceOnly.isEmpty() || !peOnly.isEmpty()) {
            String missing = ceOnly.stream().map(s -> s + " (missing PE)").collect(Collectors.joining(", "))
                    + peOnly.stream().map(s -> s + " (missing CE)").collect(Collectors.joining(", "));
            throw new ScopeValidationException(
                    "INSTRUMENT_PAIR_INCOMPLETE",
                    "Strategy " + scope.strategy() + " requires CE+PE pairs but found unpaired strikes: " + missing,
                    "Include both the CE and PE instrument_id for each strike in the LegsOnly list."
            );
        }
    }

    // -------------------------------------------------------------------------
    // ResultSet → InstrumentRef
    // -------------------------------------------------------------------------

    private static Calendar utcCal() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static InstrumentRef toInstrumentRef(ResultSet rs) throws SQLException {
        String instrumentId  = rs.getString("instrument_id");
        // exchange_token is stored as a string (see KiteInstrumentsDumpJob.insertInstrument)
        // It holds the Kite instrumentToken (long). Parse defensively.
        long kiteToken = parseTokenSafe(rs.getString("exchange_token"), instrumentId);
        String tradingSymbol = rs.getString("trading_symbol");
        String optionType    = rs.getString("option_type");
        BigDecimal strike    = BigDecimal.valueOf(rs.getDouble("strike"));
        LocalDate expiry     = rs.getTimestamp("expiry_date", utcCal()).toInstant()
                                 .atZone(IST).toLocalDate();
        ExpiryType expiryType;
        try {
            expiryType = ExpiryType.valueOf(rs.getString("expiry_type"));
        } catch (IllegalArgumentException ex) {
            expiryType = ExpiryType.WEEKLY; // safe fallback; log-worthy but non-fatal
        }
        int lotSize = rs.getInt("lot_size");

        return new InstrumentRef(
                instrumentId, kiteToken, tradingSymbol,
                optionType, strike, expiry, expiryType, lotSize
        );
    }

    private static long parseTokenSafe(String raw, String instrumentId) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            System.err.printf("[UniverseResolver] Could not parse exchange_token='%s' for %s%n",
                    raw, instrumentId);
            return 0L;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String toQuestDbTimestampLiteral(LocalDate date) {
        return date.atStartOfDay(IST).toInstant().toString().replace("Z", ".000000Z");
    }

    private static String buildRangeNarrowingHint(Scope scope, double low, double high, int hardCap) {
        return String.format(
                "Window [%.0f–%.0f] yields >%d strikes for %s %s. " +
                "Narrow the strikeWindow (e.g. ATM_PCT=4.0 or EXPLICIT_RANGE) or reduce pct/points.",
                low, high, hardCap, scope.underlying(), scope.expiryType()
        );
    }
}
