package com.strategysquad.agentic.scanner;

import com.strategysquad.aggregation.OptionsContextBucket;
import com.strategysquad.research.SimulationClock;
import com.strategysquad.scope.InstrumentRef;
import com.strategysquad.scope.ResolvedUniverse;
import com.strategysquad.scope.Scope;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

/**
 * JDBC query component for the morning scanner pipeline.
 *
 * <p>Provides two public methods used by the scanner:
 * <ul>
 *   <li>{@link #fetchActiveWeeklyContracts()} — loads the active instrument universe with
 *       latest live or historical option and spot prices.</li>
 *   <li>{@link #loadCohortMap(String)} — loads historical cohort context from
 *       {@code options_context_buckets} for scoring.</li>
 * </ul>
 *
 * <p>Supports both live mode (default) and simulation mode (via {@link SimulationClock}).
 * In simulation mode all price reads are point-in-time constrained to prevent
 * forward-looking data leakage.
 */
public final class ScannerQuery {

    private static final Calendar UTC_CALENDAR =
            Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    // -------------------------------------------------------------------------
    // SQL: active tradable option universe (WEEKLY and MONTHLY, both underlyings).
    // Excludes past expiries via the date filter; expiry_type constraint removed
    // so BANKNIFTY MONTHLY and NIFTY MONTHLY near-expiry contracts are included.
    // -------------------------------------------------------------------------
    private static final String SELECT_ACTIVE_WEEKLY_INSTRUMENTS_SQL =
            "SELECT instrument_id, underlying, trading_symbol, option_type,"
            + " strike, expiry_date, expiry_type, lot_size"
            + " FROM instrument_master"
            + " WHERE is_active = true"
            + "   AND underlying IN ('NIFTY', 'BANKNIFTY')"
            + "   AND expiry_date >= dateadd('d', -1, now())"
            + " ORDER BY underlying, expiry_date, option_type, strike";

    // SQL: active contracts filtered to a single underlying (performance optimisation
    // for per-underlying scanner calls — avoids loading and scoring instruments
    // for the other underlying that will be filtered out anyway).
    private static final String SELECT_ACTIVE_INSTRUMENTS_BY_UNDERLYING_SQL =
            "SELECT instrument_id, underlying, trading_symbol, option_type,"
            + " strike, expiry_date, expiry_type, lot_size"
            + " FROM instrument_master"
            + " WHERE is_active = true"
            + "   AND underlying = ?"
            + "   AND expiry_date >= dateadd('d', -1, now())"
            + " ORDER BY expiry_date, option_type, strike";

    // -------------------------------------------------------------------------
    // SQL: latest live option price + moneyness + bucket + volume
    // -------------------------------------------------------------------------
    private static final String SELECT_LIVE_OPTION_PRICE_SQL =
            "SELECT last_price, moneyness_points, moneyness_bucket, time_bucket_15m, volume"
            + " FROM options_live_enriched"
            + " WHERE instrument_id = ?"
            + " ORDER BY exchange_ts DESC"
            + " LIMIT 1";

    // -------------------------------------------------------------------------
    // SQL: latest historical option price (simulation mode, point-in-time)
    // -------------------------------------------------------------------------
    private static final String SELECT_HISTORICAL_OPTION_PRICE_SQL =
            "SELECT last_price, moneyness_points, moneyness_bucket, time_bucket_15m, volume"
            + " FROM options_enriched"
            + " WHERE instrument_id = ?"
            + "   AND exchange_ts <= ?"
            + " ORDER BY exchange_ts DESC"
            + " LIMIT 1";

    // -------------------------------------------------------------------------
    // SQL: latest live spot price
    // -------------------------------------------------------------------------
    private static final String SELECT_LIVE_SPOT_SQL =
            "SELECT last_price"
            + " FROM spot_live"
            + " WHERE underlying = ?"
            + " ORDER BY exchange_ts DESC"
            + " LIMIT 1";

    // -------------------------------------------------------------------------
    // SQL: latest historical spot price (simulation mode, point-in-time)
    // -------------------------------------------------------------------------
    private static final String SELECT_HISTORICAL_SPOT_SQL =
            "SELECT close_price"
            + " FROM spot_historical"
            + " WHERE underlying = ?"
            + "   AND trade_ts <= ?"
            + " ORDER BY trade_ts DESC"
            + " LIMIT 1";

    // -------------------------------------------------------------------------
    // SQL: context bucket cohort (options_context_buckets, per underlying)
    // -------------------------------------------------------------------------
    private static final String SELECT_CONTEXT_BUCKETS_SQL =
            "SELECT underlying, option_type, time_bucket_15m, moneyness_bucket,"
            + "       avg_option_price, avg_price_to_spot_ratio, avg_volume, sample_count,"
            + "       bucket_ts"
            + " FROM options_context_buckets"
            + " WHERE underlying = ?";

    // SQL: aggregated fallback cohort — collapses time_bucket_15m dimension.
    // Used as a live-mode fallback when the exact TTE bucket in options_live_enriched
    // does not match any historical bucket (they differ because historical bhavcopy
    // data is timestamped at market close while live ticks arrive during trading
    // hours, producing slightly different TTE bucket numbers).
    // Keyed with time_bucket_15m = 0 as a sentinel value.
    private static final String SELECT_CONTEXT_BUCKETS_AGGREGATED_SQL =
            "SELECT underlying, option_type, moneyness_bucket,"
            + "       avg(avg_option_price) AS avg_option_price,"
            + "       avg(avg_price_to_spot_ratio) AS avg_price_to_spot_ratio,"
            + "       avg(avg_volume) AS avg_volume,"
            + "       sum(sample_count) AS sample_count,"
            + "       max(bucket_ts) AS bucket_ts"
            + " FROM options_context_buckets"
            + " WHERE underlying = ?"
            + " GROUP BY underlying, option_type, moneyness_bucket";

    // -------------------------------------------------------------------------
    // SQL: scoped option price batch query
    // Fetches the latest live option price for a batch of instrument IDs using
    // the LATEST ON ... PARTITION BY syntax which QuestDB supports for
    // time-series latest-by queries. Falls back to per-instrument queries
    // when the batch size is 1 (avoids unnecessary complexity for single-leg scopes).
    //
    // Template: replace %s with comma-separated '?' placeholders.
    // -------------------------------------------------------------------------
    private static final String SELECT_SCOPED_LIVE_PRICES_TEMPLATE =
            "SELECT instrument_id, last_price, moneyness_points, moneyness_bucket,"
            + "       time_bucket_15m, volume"
            + " FROM options_live_enriched"
            + " WHERE instrument_id IN (%s)"
            + " LATEST ON exchange_ts PARTITION BY instrument_id";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final ConnectionSupplier connectionSupplier;
    private final SimulationClock simulationClock;

    // -------------------------------------------------------------------------
    // Public constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a ScannerQuery that always reads live data.
     *
     * @param jdbcUrl JDBC URL for QuestDB (PostgreSQL wire protocol)
     */
    public ScannerQuery(String jdbcUrl) {
        this(jdbcUrl, null);
    }

    /**
     * Creates a ScannerQuery with optional simulation-clock support.
     *
     * @param jdbcUrl         JDBC URL for QuestDB (PostgreSQL wire protocol)
     * @param simulationClock optional clock; when non-null and
     *                        {@link SimulationClock#isSimulating()} returns {@code true},
     *                        historical tables are used with a point-in-time constraint
     */
    public ScannerQuery(String jdbcUrl, SimulationClock simulationClock) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.connectionSupplier = () -> QuestDbConnectionFactory.open(jdbcUrl);
        this.simulationClock = simulationClock;
    }

    // -------------------------------------------------------------------------
    // Package-private constructor for unit testing
    // -------------------------------------------------------------------------

    /**
     * Test-only constructor. Accepts a {@link ConnectionSupplier} so unit tests
     * can inject stub {@link Connection} objects without a live database.
     *
     * @param connectionSupplier supplier of JDBC connections
     * @param simulationClock    optional simulation clock; may be {@code null}
     */
    ScannerQuery(ConnectionSupplier connectionSupplier, SimulationClock simulationClock) {
        this.connectionSupplier = Objects.requireNonNull(connectionSupplier,
                "connectionSupplier must not be null");
        this.simulationClock = simulationClock;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches the universe of active NIFTY and BANKNIFTY contracts (WEEKLY and MONTHLY)
     * enriched with the latest available option price and spot price.
     *
     * <p>In live mode the result reflects current market data.
     * In simulation mode every price lookup is constrained to
     * {@code <= simulationClock.instant()} to prevent forward-looking data leakage.
     *
     * @return list of raw contract rows; empty if no active contracts exist or
     *         no price data is available for any contract. Never {@code null}.
     * @throws java.sql.SQLException if a database error occurs
     */
    public List<RawContractRow> fetchActiveWeeklyContracts() throws java.sql.SQLException {
        return fetchActiveWeeklyContracts(null);
    }

    /**
     * Fetches the universe of active contracts for a single underlying, enriched
     * with the latest available option price and spot price.
     *
     * <p>This is a performance-optimised variant of {@link #fetchActiveWeeklyContracts()}
     * for single-underlying scanner calls — it avoids loading and scoring instruments
     * for the other underlying that would be filtered out by the caller anyway.
     *
     * @param underlying the index name ({@code NIFTY} or {@code BANKNIFTY});
     *                   if {@code null}, loads all underlyings (same as {@link #fetchActiveWeeklyContracts()})
     * @return list of raw contract rows; never {@code null}
     * @throws java.sql.SQLException if a database error occurs
     */
    public List<RawContractRow> fetchActiveWeeklyContracts(String underlying) throws java.sql.SQLException {
        boolean simulating = simulationClock != null && simulationClock.isSimulating();
        Instant asOf = simulating ? simulationClock.instant() : null;

        List<RawContractRow> results = new ArrayList<>();

        try (Connection conn = connectionSupplier.getConnection()) {
            List<InstrumentRow> instruments = underlying != null
                    ? loadInstrumentsForUnderlying(conn, underlying)
                    : loadInstruments(conn);
            if (instruments.isEmpty()) {
                return results;
            }

            for (InstrumentRow instrument : instruments) {
                OptionPriceRow optionPrice = loadOptionPrice(
                        conn, instrument.instrumentId(), simulating, asOf);
                if (optionPrice == null) {
                    // No price data available — skip; scoring handles disqualification
                    continue;
                }
                SpotRow spot = loadSpot(conn, instrument.underlying(), simulating, asOf);
                if (spot == null) {
                    continue;
                }
                results.add(buildRow(instrument, optionPrice, spot));
            }
        }
        return results;
    }

    /**
     * Fetches the scoped contract universe for a resolved scope, enriched with the
     * latest live option prices and spot price.
     *
     * <p>Unlike {@link #fetchActiveWeeklyContracts()}, this method does <em>not</em>
     * query {@code instrument_master} — the instrument metadata is already available
     * in the {@link ResolvedUniverse}. Only the live price data is fetched from DB,
     * using a single {@code IN(...)} batch query against {@code options_live_enriched}
     * for efficiency.
     *
     * <p>Instruments with no live price data are silently skipped (same behaviour as
     * the legacy path). Spot price is fetched once per underlying using the existing
     * {@link #loadSpot} helper.
     *
     * <p>This method always uses live data (ignores any simulation clock). Simulation
     * mode is not supported for scoped scanning — the agentic loop's simulation path
     * continues to use {@link #fetchActiveWeeklyContracts()}.
     *
     * @param scope    the active scope (used for underlying and expiry context)
     * @param universe the resolved universe whose instruments define the candidate set
     * @return list of enriched contract rows, one per instrument that has live price data;
     *         never null; may be empty
     * @throws java.sql.SQLException if a database error occurs
     */
    public List<RawContractRow> fetchScoped(Scope scope, ResolvedUniverse universe)
            throws java.sql.SQLException {
        Objects.requireNonNull(scope,    "scope must not be null");
        Objects.requireNonNull(universe, "universe must not be null");

        List<InstrumentRef> instruments = universe.instruments();
        if (instruments.isEmpty()) {
            return List.of();
        }

        List<RawContractRow> results = new ArrayList<>(instruments.size());

        try (Connection conn = connectionSupplier.getConnection()) {
            // Fetch all live prices in one batch query (avoids N+1 per-instrument queries)
            Map<String, OptionPriceRow> priceByInstrumentId =
                    loadScopedPrices(conn, instruments);

            // Fetch spot once for the scope's underlying (spot keys are always subscribed)
            SpotRow spot = loadSpot(conn, scope.underlying(), false, null);
            if (spot == null) {
                // No spot data — cannot score any candidate
                return List.of();
            }

            for (InstrumentRef ref : instruments) {
                OptionPriceRow price = priceByInstrumentId.get(ref.instrumentId());
                if (price == null) {
                    // No live price yet for this instrument — skip
                    continue;
                }
                results.add(buildScopedRow(ref, price, spot));
            }
        }
        return results;
    }

    /**
     * Loads the historical cohort context for the given underlying from
     * {@code options_context_buckets}.
     *
     * <p>The returned map contains two types of entries:
     * <ol>
     *   <li>Exact TTE-bucket entries keyed by the actual {@code time_bucket_15m} value.
     *       These are used in simulation mode where bhavcopy timestamps align with
     *       historical context exactly.</li>
     *   <li>Aggregated fallback entries keyed with {@code timeBucket15m = 0}. These
     *       serve as a live-mode fallback for cases where the intraday live tick's TTE
     *       bucket does not exactly match any historical bhavcopy bucket.</li>
     * </ol>
     *
     * <p>Returns an empty map if no context data is available.
     *
     * @param underlying the index name ({@code NIFTY} or {@code BANKNIFTY})
     * @return cohort map; never null; may be empty
     * @throws java.sql.SQLException if a database error occurs
     */
    public Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> loadCohortMap(
            String underlying
    ) throws java.sql.SQLException {
        Objects.requireNonNull(underlying, "underlying must not be null");
        String canon = underlying.trim().toUpperCase(java.util.Locale.ROOT);
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> result = new HashMap<>();

        try (Connection conn = connectionSupplier.getConnection()) {
            boolean simulating = simulationClock != null && simulationClock.isSimulating();

            // Pass 1 — exact TTE-bucket entries. Only used in simulation mode where
            // bhavcopy timestamps align with historical context exactly. In live mode
            // this pass is skipped: loading 1.87M+ rows via JDBC is too slow, and
            // exact bucket matching fails anyway because live ticks arrive at intraday
            // time while historical bhavcopy is timestamped at market close.
            if (simulating) {
                try (PreparedStatement ps = conn.prepareStatement(SELECT_CONTEXT_BUCKETS_SQL)) {
                    ps.setString(1, canon);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String optionType = rs.getString("option_type");
                            int timeBucket15m = rs.getInt("time_bucket_15m");
                            int moneynessBucket = rs.getInt("moneyness_bucket");
                            CandidateScoringEngine.CohortKey key =
                                    new CandidateScoringEngine.CohortKey(
                                            canon, optionType, moneynessBucket, timeBucket15m);
                            OptionsContextBucket bucket = new OptionsContextBucket(
                                    rs.getTimestamp("bucket_ts").toInstant(),
                                    rs.getString("underlying"),
                                    optionType,
                                    timeBucket15m,
                                    moneynessBucket,
                                    BigDecimal.valueOf(rs.getDouble("avg_option_price")),
                                    BigDecimal.valueOf(rs.getDouble("avg_price_to_spot_ratio")),
                                    BigDecimal.valueOf(rs.getDouble("avg_volume")),
                                    rs.getLong("sample_count")
                            );
                            result.put(key, bucket);
                        }
                    }
                }
            }

            // Pass 2 — aggregated fallback entries keyed with timeBucket15m = 0.
            // These cover live-mode scans where the live TTE bucket (computed at
            // intraday tick time) does not exactly match any historical bucket
            // (computed at market-close bhavcopy time). putIfAbsent preserves any
            // exact match already loaded in pass 1 (simulation mode only).
            try (PreparedStatement ps = conn.prepareStatement(SELECT_CONTEXT_BUCKETS_AGGREGATED_SQL)) {
                ps.setString(1, canon);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String optionType = rs.getString("option_type");
                        int moneynessBucket = rs.getInt("moneyness_bucket");
                        CandidateScoringEngine.CohortKey fallbackKey =
                                new CandidateScoringEngine.CohortKey(
                                        canon, optionType, moneynessBucket, 0);
                        OptionsContextBucket bucket = new OptionsContextBucket(
                                rs.getTimestamp("bucket_ts").toInstant(),
                                canon,
                                optionType,
                                0,
                                moneynessBucket,
                                BigDecimal.valueOf(rs.getDouble("avg_option_price")),
                                BigDecimal.valueOf(rs.getDouble("avg_price_to_spot_ratio")),
                                BigDecimal.valueOf(rs.getDouble("avg_volume")),
                                rs.getLong("sample_count")
                        );
                        result.putIfAbsent(fallbackKey, bucket);
                    }
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers — DB reads
    // -------------------------------------------------------------------------

    private List<InstrumentRow> loadInstruments(Connection conn) throws java.sql.SQLException {
        List<InstrumentRow> instruments = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTIVE_WEEKLY_INSTRUMENTS_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                instruments.add(new InstrumentRow(
                        rs.getString("instrument_id"),
                        rs.getString("underlying"),
                        rs.getString("trading_symbol"),
                        rs.getString("option_type"),
                        BigDecimal.valueOf(rs.getDouble("strike")),
                        rs.getTimestamp("expiry_date", UTC_CALENDAR).toInstant(),
                        rs.getString("expiry_type"),
                        rs.getInt("lot_size")
                ));
            }
        }
        return instruments;
    }

    private List<InstrumentRow> loadInstrumentsForUnderlying(
            Connection conn, String underlying
    ) throws java.sql.SQLException {
        List<InstrumentRow> instruments = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTIVE_INSTRUMENTS_BY_UNDERLYING_SQL)) {
            ps.setString(1, underlying.trim().toUpperCase(java.util.Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    instruments.add(new InstrumentRow(
                            rs.getString("instrument_id"),
                            rs.getString("underlying"),
                            rs.getString("trading_symbol"),
                            rs.getString("option_type"),
                            BigDecimal.valueOf(rs.getDouble("strike")),
                            rs.getTimestamp("expiry_date", UTC_CALENDAR).toInstant(),
                            rs.getString("expiry_type"),
                            rs.getInt("lot_size")
                    ));
                }
            }
        }
        return instruments;
    }

    private OptionPriceRow loadOptionPrice(
            Connection conn, String instrumentId, boolean simulating, Instant asOf
    ) throws java.sql.SQLException {
        String sql = simulating ? SELECT_HISTORICAL_OPTION_PRICE_SQL : SELECT_LIVE_OPTION_PRICE_SQL;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, instrumentId);
            if (simulating) {
                ps.setTimestamp(2, Timestamp.from(asOf));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                double lastPrice = rs.getDouble("last_price");
                // bid/ask approximation: ±0.5% of last_price.
                BigDecimal last = BigDecimal.valueOf(lastPrice);
                BigDecimal bid  = BigDecimal.valueOf(lastPrice * 0.995)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                BigDecimal ask  = BigDecimal.valueOf(lastPrice * 1.005)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                return new OptionPriceRow(
                        last,
                        bid,
                        ask,
                        BigDecimal.valueOf(rs.getDouble("moneyness_points")),
                        rs.getInt("moneyness_bucket"),
                        rs.getInt("time_bucket_15m"),
                        rs.getLong("volume")
                );
            }
        }
    }

    private SpotRow loadSpot(
            Connection conn, String underlying, boolean simulating, Instant asOf
    ) throws java.sql.SQLException {
        String sql = simulating ? SELECT_HISTORICAL_SPOT_SQL : SELECT_LIVE_SPOT_SQL;
        String priceColumn = simulating ? "close_price" : "last_price";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, underlying);
            if (simulating) {
                ps.setTimestamp(2, Timestamp.from(asOf));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SpotRow(BigDecimal.valueOf(rs.getDouble(priceColumn)));
            }
        }
    }

    private static RawContractRow buildRow(
            InstrumentRow ins, OptionPriceRow price, SpotRow spot
    ) {
        LocalDate expiryDate = ins.expiryTs()
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toLocalDate();
        return new RawContractRow(
                ins.instrumentId(),
                ins.underlying(),
                ins.tradingSymbol(),
                ins.optionType(),
                ins.strike(),
                expiryDate,
                ins.expiryType(),
                ins.lotSize(),
                spot.spotPrice(),
                price.lastPrice(),
                price.bidPrice(),
                price.askPrice(),
                price.moneynessPoints(),
                price.moneynessBucket(),
                price.timeBucket15m(),
                price.volume()
        );
    }

    /**
     * Fetches the latest live price for each instrument in the scoped universe using
     * a single {@code IN(...)} batch query.
     *
     * <p>The SQL template is instantiated with the correct number of {@code ?}
     * placeholders for the instrument ID list. Results are indexed by instrument ID
     * for O(1) lookup in the calling loop.
     *
     * @param conn        open JDBC connection
     * @param instruments the instruments to fetch prices for
     * @return map from instrumentId → OptionPriceRow; missing entries = no live data
     */
    private Map<String, OptionPriceRow> loadScopedPrices(
            Connection conn, List<InstrumentRef> instruments
    ) throws java.sql.SQLException {
        // Build "?,?,?..." placeholder string for the IN clause
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < instruments.size(); i++) {
            if (i > 0) ph.append(',');
            ph.append('?');
        }

        String sql = String.format(SELECT_SCOPED_LIVE_PRICES_TEMPLATE, ph.toString());
        Map<String, OptionPriceRow> result = new HashMap<>(instruments.size() * 2);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < instruments.size(); i++) {
                ps.setString(i + 1, instruments.get(i).instrumentId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("instrument_id");
                    double lastPrice = rs.getDouble("last_price");
                    BigDecimal last = BigDecimal.valueOf(lastPrice);
                    BigDecimal bid  = BigDecimal.valueOf(lastPrice * 0.995)
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    BigDecimal ask  = BigDecimal.valueOf(lastPrice * 1.005)
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    result.put(id, new OptionPriceRow(
                            last, bid, ask,
                            BigDecimal.valueOf(rs.getDouble("moneyness_points")),
                            rs.getInt("moneyness_bucket"),
                            rs.getInt("time_bucket_15m"),
                            rs.getLong("volume")
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Builds a {@link RawContractRow} from an {@link InstrumentRef} (scope domain type)
     * and live price data. The instrument metadata is taken directly from the ref —
     * no DB round-trip to {@code instrument_master} is needed.
     *
     * <p>The underlying is derived from the instrument ID using the canonical format
     * {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>}.
     */
    private static RawContractRow buildScopedRow(
            InstrumentRef ref, OptionPriceRow price, SpotRow spot
    ) {
        // Underlying is the second segment of the canonical instrument ID
        // e.g. INS_NIFTY_20260430_22000_CE → "NIFTY"
        String underlying = deriveUnderlyingFromId(ref.instrumentId());
        return new RawContractRow(
                ref.instrumentId(),
                underlying,
                ref.tradingSymbol(),
                ref.optionType(),
                ref.strike(),
                ref.expiry(),
                ref.expiryType().name(),
                ref.lotSize(),
                spot.spotPrice(),
                price.lastPrice(),
                price.bidPrice(),
                price.askPrice(),
                price.moneynessPoints(),
                price.moneynessBucket(),
                price.timeBucket15m(),
                price.volume()
        );
    }

    /**
     * Extracts the underlying name from a canonical instrument ID.
     * Format: {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>}
     */
    private static String deriveUnderlyingFromId(String instrumentId) {
        // Split on "_" with limit 3: ["INS", "NIFTY", "20260430_22000_CE"]
        String[] parts = instrumentId.split("_", 3);
        return parts.length >= 2 ? parts[1] : "UNKNOWN";
    }

    // -------------------------------------------------------------------------
    // Connection seam — package-private for testing
    // -------------------------------------------------------------------------

    /**
     * Functional interface that supplies JDBC {@link Connection} objects.
     * The production implementation opens a real QuestDB connection;
     * tests inject a stub.
     */
    @FunctionalInterface
    interface ConnectionSupplier {
        Connection getConnection() throws java.sql.SQLException;
    }

    // -------------------------------------------------------------------------
    // Internal value types — package-private for test access
    // -------------------------------------------------------------------------

    record InstrumentRow(
            String instrumentId,
            String underlying,
            String tradingSymbol,
            String optionType,
            BigDecimal strike,
            Instant expiryTs,
            String expiryType,
            int lotSize
    ) {}

    record OptionPriceRow(
            BigDecimal lastPrice,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            BigDecimal moneynessPoints,
            int moneynessBucket,
            int timeBucket15m,
            long volume
    ) {}

    record SpotRow(BigDecimal spotPrice) {}

    /**
     * Enriched contract row combining instrument metadata, current option price,
     * and spot context. Produced by {@link #fetchActiveWeeklyContracts()} and
     * consumed by {@link CandidateScoringEngine}.
     */
    public record RawContractRow(
            String instrumentId,
            String underlying,
            String tradingSymbol,
            String optionType,
            BigDecimal strike,
            LocalDate expiryDate,
            String expiryType,
            int lotSize,
            BigDecimal spot,
            BigDecimal lastPrice,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            BigDecimal moneynessPoints,
            int moneynessBucket,
            int timeBucket15m,
            long volume
    ) {}
}

