package com.strategysquad.order;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.strategysquad.ingestion.kite.live.auth.KiteCredentials;
import com.strategysquad.ingestion.kite.KiteLiveSessionManager;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.order.PositionPnlCalculator;
import com.strategysquad.platform.db.QuestDbConnectionFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.TimeZone;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.stream.Collectors;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Option-only order support for the Orders desk.
 *
 * <p>Scope is intentionally narrow: NFO index options for NIFTY and BANKNIFTY only.
 * Instrument metadata comes from the locally cached Kite instrument dump
 * ({@code instrument_master}) and live/current prices come from the active Kite session
 * cache first, with on-demand REST quotes as fallback.
 */
public final class OptionOrderService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> ALLOWED_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY");
    private static final Duration GREEK_SAMPLE_WINDOW = Duration.ofMinutes(5);
    private static final Duration GREEK_SAMPLE_RETENTION = Duration.ofMinutes(12);
    private static final double MIN_SPOT_MOVE_FOR_OBSERVED_DELTA = 0.50d;
    private static final double NIFTY_STRIKE_STEP = 50.0d;
    private static final String SELECT_OPTIONS_SQL =
            "SELECT instrument_id, trading_symbol, option_type, strike, expiry_date, lot_size, updated_at "
                    + "FROM instrument_master "
                    + "WHERE is_active = true "
                    + "  AND underlying = ? "
                    + "  AND expiry_date >= ? "
                    + "ORDER BY expiry_date ASC, strike ASC, option_type ASC";
    private static final String SELECT_ONE_OPTION_SQL =
            "SELECT instrument_id, trading_symbol, option_type, strike, expiry_date, lot_size "
                    + "FROM instrument_master "
                    + "WHERE is_active = true "
                    + "  AND underlying = ? "
                    + "  AND expiry_date >= ? AND expiry_date < ? "
                    + "  AND strike = ? "
                    + "  AND option_type = ? "
                    + "LIMIT 1";
    private static String quoteUrl() {
        return com.strategysquad.platform.config.KiteEndpoints.instance().quoteUrl();
    }

    private static String orderUrl() {
        return com.strategysquad.platform.config.KiteEndpoints.instance().orderRegularUrl();
    }

    private static String orderHistoryUrl() {
        return com.strategysquad.platform.config.KiteEndpoints.instance().orderHistoryBaseUrl();
    }
    private static final DateTimeFormatter KITE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String jdbcUrl;
    private final KiteLiveSessionManager sessionManager;
    private final Path storeRoot;
    private final Gson gson;
    private final HttpClient httpClient;
    private final Map<String, ConcurrentSkipListMap<Long, QuoteSample>> quoteSampleHistory = new ConcurrentHashMap<>();
    /** Append-only audit ledger of order actions (PLACE/REDUCE/CLOSE). */
    private final OrderEventStore eventStore;

    public OptionOrderService(String jdbcUrl, KiteLiveSessionManager sessionManager, Path storeRoot) {
        this(
                jdbcUrl,
                sessionManager,
                storeRoot,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        );
    }

    OptionOrderService(
            String jdbcUrl,
            KiteLiveSessionManager sessionManager,
            Path storeRoot,
            HttpClient httpClient
    ) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.sessionManager = sessionManager;
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.gson = new GsonBuilder()
                .serializeNulls()
                .registerTypeAdapter(Instant.class, new InstantJsonCodec())
                .registerTypeAdapter(LocalDate.class, new LocalDateJsonCodec())
                .create();
        // Audit ledger lives next to the execution store: run/orders/  → run/order-events/
        this.eventStore = new OrderEventStore(storeRoot.getParent() != null
                ? storeRoot.getParent().resolve("order-events")
                : storeRoot.resolveSibling("order-events"));
    }

    /** Read-only access to the order-event audit ledger. */
    public OrderEventStore eventStore() {
        return eventStore;
    }

    public OptionMetadataResponse loadMetadata(String underlying)
            throws SQLException, IOException, InterruptedException {
        return loadMetadata(underlying, false);
    }

    public OptionMetadataResponse loadMetadata(String underlying, boolean dummyMode)
            throws SQLException, IOException, InterruptedException {
        // Provider-agnostic: same code path regardless of underlying market-data source.
        // The `dummyMode` flag now only signals "persisted greeks unavailable" downstream.
        dummyMode = dummyMode || isPersistenceDisabled();
        String normalized = normalizeUnderlying(underlying);
        Map<LocalDate, LinkedHashSet<BigDecimal>> strikesByExpiry = new LinkedHashMap<>();
        Map<LocalDate, Integer> lotSizeByExpiry = new LinkedHashMap<>();
        Instant lastUpdatedAt = null;

        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SELECT_OPTIONS_SQL)) {
            statement.setString(1, normalized);
            statement.setTimestamp(2, Timestamp.from(LocalDate.now(IST).atStartOfDay(IST).toInstant()), utcCal());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate expiry = rs.getTimestamp("expiry_date", utcCal()).toInstant().atZone(IST).toLocalDate();
                    BigDecimal strike = BigDecimal.valueOf(rs.getDouble("strike")).stripTrailingZeros();
                    strikesByExpiry.computeIfAbsent(expiry, ignored -> new LinkedHashSet<>()).add(strike);
                    lotSizeByExpiry.putIfAbsent(expiry, rs.getInt("lot_size"));
                    Timestamp updatedTs = rs.getTimestamp("updated_at");
                    Instant rowUpdated = updatedTs == null ? null : updatedTs.toInstant();
                    if (rowUpdated != null && (lastUpdatedAt == null || rowUpdated.isAfter(lastUpdatedAt))) {
                        lastUpdatedAt = rowUpdated;
                    }
                }
            }
        }

        List<ExpiryBucket> expiries = new ArrayList<>();
        strikesByExpiry.forEach((expiry, strikes) -> expiries.add(new ExpiryBucket(
                expiry,
                new ArrayList<>(strikes),
                lotSizeByExpiry.getOrDefault(expiry, 0)
        )));

        return new OptionMetadataResponse(
                normalized,
                expiries,
                loadSpot(normalized),
                lastUpdatedAt,
                sessionManager != null && sessionManager.currentCredentials().isPresent(),
                "kite_instrument_master"
        );
    }

    public QuoteResponse loadQuote(String underlying, String expiry, String strike, String optionType)
            throws SQLException, IOException, InterruptedException {
        return loadQuote(underlying, expiry, strike, optionType, false);
    }

    public QuoteResponse loadQuote(String underlying, String expiry, String strike, String optionType, boolean dummyMode)
            throws SQLException, IOException, InterruptedException {
        // dummyMode parameter retained for API compatibility; quote source is now provider-agnostic.
        OptionContract contract = resolveContract(underlying, expiry, strike, optionType);
        QuoteSnapshot quote = loadOptionQuote(contract.instrumentId(), contract.tradingSymbol());
        return new QuoteResponse(contract, quote);
    }

    public ExecutionView placeOrder(PlaceOrderRequest request)
            throws SQLException, IOException, InterruptedException {
        Objects.requireNonNull(request, "request must not be null");
        // `dummyMode` here only governs whether persisted (DB) greeks should be skipped
        // downstream when persistence is disabled. The quote-source path is provider-agnostic.
        boolean dummyMode = isDummySource(request.source()) || isPersistenceDisabled();
        OptionContract contract = resolveContract(
                request.underlying(),
                request.expiry(),
                decimalString(request.strike()),
                request.optionType()
        );
        int lots = request.lots();
        if (lots <= 0) {
            throw new IllegalArgumentException("Lots must be greater than zero");
        }
        String transactionType = normalizeTransactionType(request.transactionType());
        String mode = normalizeMode(request.mode());
        String orderType = normalizeOrderType(request.orderType());
        String product = normalizeProduct(request.product());
        int quantity = Math.multiplyExact(lots, contract.lotSize());

        // Single quote-loading path. No paper-mode synthetic fallback — if the configured
        // market-data provider has no quote, the order entry will fail explicitly below.
        QuoteSnapshot quote = loadExecutionQuote(contract.instrumentId(), contract.tradingSymbol(), mode);
        BigDecimal fallbackEntry = determineEntryPrice(orderType, request.price(), quote);
        if ("paper".equals(mode) && (fallbackEntry == null || fallbackEntry.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new com.strategysquad.marketdata.MissingMarketDataException(
                    contract.instrumentId(),
                    "No live quote available for paper order entry: " + contract.tradingSymbol()
                            + ". Configured market-data provider returned no price.");
        }
        Instant now = Instant.now();

        BrokerOrderSnapshot brokerSnapshot = null;
        String status;
        String externalOrderId = null;
        BigDecimal entryPrice;
        int filledQuantity;
        int pendingQuantity;
        if ("real".equals(mode)) {
            if (sessionManager == null) {
                throw new IllegalStateException("Kite session is not available in the sim console");
            }
            KiteCredentials credentials = sessionManager.currentCredentials()
                    .orElseThrow(() -> new IllegalStateException("Kite session is not authenticated"));
            brokerSnapshot = placeRealOrder(credentials, contract, transactionType, orderType, product, quantity, request.price());
            externalOrderId = brokerSnapshot.orderId();
            status = brokerSnapshot.status();
            entryPrice = brokerSnapshot.averagePrice() != null ? brokerSnapshot.averagePrice() : fallbackEntry;
            filledQuantity = brokerSnapshot.filledQuantity() > 0 ? brokerSnapshot.filledQuantity() : quantity;
            pendingQuantity = Math.max(0, quantity - filledQuantity);
        } else {
            status = "COMPLETE";
            entryPrice = fallbackEntry;
            filledQuantity = quantity;
            pendingQuantity = 0;
        }

        ManualExecution execution = new ManualExecution(
                UUID.randomUUID().toString(),
                externalOrderId,
                mode,
                status,
                contract.underlying(),
                contract.instrumentId(),
                contract.tradingSymbol(),
                contract.optionType(),
                contract.strike(),
                contract.expiry(),
                transactionType,
                orderType,
                product,
                lots,
                quantity,
                filledQuantity,
                pendingQuantity,
                contract.lotSize(),
                entryPrice,
                request.price(),
                BigDecimal.ZERO,
                brokerSnapshot == null ? null : brokerSnapshot.statusMessage(),
                now,
                now,
                request.strategyId(),
                request.strategyType(),
                request.strategyLabel()
        );
        saveExecution(execution);
        // Append PLACE event to the audit ledger (powers the Order Log table on the Orders desk).
        // Source defaults to MANUAL here; baskets from Strategy Lab can be re-tagged via the
        // request.strategyId — Strategy Lab places via the same /api/orders/place path.
        eventStore.record(new OrderEventStore.OrderEvent(
                OrderEventStore.newId(), now, "PLACE",
                "REJECTED".equalsIgnoreCase(status) ? "REJECTED" : "COMPLETED",
                request.strategyId() != null && !request.strategyId().isBlank() ? "STRATEGY_LAB" : "MANUAL",
                execution.executionId(), request.strategyId(),
                contract.underlying(), contract.instrumentId(), contract.tradingSymbol(),
                contract.optionType(), contract.strike(),
                transactionType, lots, contract.lotSize(),
                entryPrice, BigDecimal.ZERO,
                brokerSnapshot == null ? null : brokerSnapshot.statusMessage()
        ));
        // Compute initial greeks so the placement response already has delta/theta
        Map<String, QuoteSnapshot> placementQuotes = quote != null
                ? java.util.Collections.singletonMap(contract.instrumentId(), quote) : new HashMap<>();
        Map<String, QuoteSnapshot> placementSpots = new HashMap<>();
        try {
            QuoteSnapshot spotSnapshot = loadSpot(contract.underlying());
            if (spotSnapshot != null) placementSpots.put(contract.underlying(), spotSnapshot);
        } catch (Exception ignored) {
            // Spot miss at placement time is non-fatal; greeks will populate on next poll.
        }
        recordQuoteSamples(List.of(execution), placementQuotes, placementSpots);
        Map<String, double[]> placementGreeks = computeExecutionGreeks(
                List.of(execution), placementQuotes, placementSpots, dummyMode);
        double[] g = placementGreeks.get(contract.instrumentId());
        Double d5m = (g != null && !Double.isNaN(g[0])) ? g[0] : null;
        Double prem5mChg = (g != null) ? g[1] : null;
        return toView(execution, true, d5m, prem5mChg, dummyMode, quote);
    }

    public List<ExecutionView> loadExecutions() throws IOException, SQLException, InterruptedException {
        return loadExecutions(false);
    }

    public List<ExecutionView> loadExecutions(boolean dummyMode) throws IOException, SQLException, InterruptedException {
        // Provider-agnostic quote loading; dummyMode only flags whether persisted greeks should be skipped.
        dummyMode = dummyMode || isPersistenceDisabled();
        List<ManualExecution> executions = loadAllExecutions();
        List<ExecutionView> views = new ArrayList<>(executions.size());
        Map<String, QuoteSnapshot> quotes = loadExecutionQuotes(executions);
        Map<String, QuoteSnapshot> spots = loadExecutionSpots(executions);
        recordQuoteSamples(executions, quotes, spots);
        Map<String, double[]> greeksMap = computeExecutionGreeks(executions, quotes, spots, dummyMode);
        for (ManualExecution execution : executions) {
            double[] g = greeksMap.get(execution.instrumentId());
            Double d5m = (g != null && !Double.isNaN(g[0])) ? g[0] : null;
            Double prem5mChg = (g != null) ? g[1] : null;
            views.add(toView(execution, true, d5m, prem5mChg, dummyMode, quotes.get(execution.instrumentId())));
        }
        views.sort(Comparator.comparing(ExecutionView::createdAt).reversed());
        return views;
    }

    /**
     * Groups today's open executions by strategyId and computes per-strategy
     * net delta, net theta benefit, live P&L, risk state and adjustment suggestions.
     *
     * <p>Executions with a null strategyId are grouped under a synthetic
     * {@code "single-leg"} bucket — one bucket per instrument so each shows up
     * as its own single-leg "strategy". This keeps legacy executions visible.
     *
     * <p>Adjustment logic is heuristic and advisory only — see
     * {@code docs/strategy-tracking-and-adjustment-logic.md}.
     */
    public List<StrategyView> loadStrategies() throws IOException, SQLException, InterruptedException {
        return loadStrategies(false);
    }

    public List<StrategyView> loadStrategies(boolean dummyMode) throws IOException, SQLException, InterruptedException {
        dummyMode = dummyMode || isPersistenceDisabled();
        List<ExecutionView> execs = loadExecutions(dummyMode);
        if (execs.isEmpty()) return List.of();

        // Group by strategyId; null strategyId becomes "single-leg::<executionId>"
        LinkedHashMap<String, List<ExecutionView>> groups = new LinkedHashMap<>();
        for (ExecutionView ev : execs) {
            String key = (ev.strategyId() != null && !ev.strategyId().isBlank())
                    ? ev.strategyId()
                    : "single-leg::" + ev.executionId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(ev);
        }

        List<StrategyView> result = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<ExecutionView>> entry : groups.entrySet()) {
            String groupId = entry.getKey();
            List<ExecutionView> legs = entry.getValue();
            result.add(buildStrategyView(groupId, legs));
        }
        // Most recently opened strategy first
        result.sort(Comparator.comparing(StrategyView::openedAt).reversed());
        return result;
    }

    private StrategyView buildStrategyView(String groupId, List<ExecutionView> legs) {
        ExecutionView first = legs.get(0);
        boolean isSingle = groupId.startsWith("single-leg::");

        String strategyId = isSingle ? groupId : first.strategyId();
        String strategyType = isSingle ? "SINGLE_LEG"
                : (first.strategyType() != null ? first.strategyType() : "CUSTOM");
        String strategyLabel = first.strategyLabel() != null
                ? first.strategyLabel()
                : (isSingle ? first.tradingSymbol() : strategyType + " " + first.underlying());

        // Aggregates
        double netDelta = 0.0;        // sum(d5m × qty × sideSign)
        double netThetaBenefit = 0.0; // positive = portfolio premiums fell in your favor (rupees per 5 min)
        double livePnl = 0.0;
        double bookedPnl = 0.0;
        boolean hasDelta = false;
        boolean hasTheta = false;
        boolean hasLive  = false;
        int totalLots = 0;
        int totalLotSize = 0;
        Instant openedAt = first.createdAt();
        String underlying = first.underlying();
        java.time.LocalDate expiry = first.expiry();
        boolean shortPremium = false;
        int shortLegs = 0;
        int longLegs = 0;

        List<StrategyLegView> legViews = new ArrayList<>(legs.size());
        for (ExecutionView ev : legs) {
            int qty = Math.max(0, ev.filledQuantity());
            int sideSign = "SELL".equalsIgnoreCase(ev.transactionType()) ? -1 : 1;
            if ("SELL".equalsIgnoreCase(ev.transactionType())) shortLegs++; else longLegs++;
            totalLots += ev.lots();
            totalLotSize += ev.lotSize() * ev.lots();
            if (ev.createdAt() != null && ev.createdAt().isBefore(openedAt)) openedAt = ev.createdAt();

            Double legNetDelta = null;
            Double legNetTheta = null;
            if (ev.d5m() != null && qty > 0) {
                legNetDelta = ev.d5m() * qty * sideSign;
                netDelta += legNetDelta;
                hasDelta = true;
            }
            if (ev.prem5mChg() != null && qty > 0) {
                // For SELL: premium drop (negative prem5mChg) = profit, so benefit = -prem5mChg × qty
                // For BUY:  premium rise (positive)            = profit, so benefit = +prem5mChg × qty
                double benefit = thetaBenefit(ev.prem5mChg(), qty, ev.transactionType());
                legNetTheta = benefit;
                netThetaBenefit += benefit;
                hasTheta = true;
            }
            if (ev.unbookedPnl() != null) { livePnl += ev.unbookedPnl().doubleValue(); hasLive = true; }
            if (ev.bookedPnl()   != null) bookedPnl += ev.bookedPnl().doubleValue();

            legViews.add(new StrategyLegView(
                    ev.executionId(), ev.instrumentId(), ev.tradingSymbol(),
                    ev.optionType(), ev.strike(), ev.expiry(),
                    ev.transactionType(), ev.lots(), ev.lotSize(), ev.quantity(),
                    ev.entryPrice(), ev.currentPrice(),
                    ev.unbookedPnl(), ev.bookedPnl(),
                    ev.d5m(), ev.prem5mChg(),
                    legNetDelta, legNetTheta,
                    ev.status()
            ));
        }
        // Heuristic: if every leg is SELL it's a short-premium book; if every leg BUY it's long premium
        shortPremium = shortLegs > 0 && longLegs == 0;

        // Per-share net delta normalised by total contract size — comparable across NIFTY/BANKNIFTY
        Double netDeltaPerShare = (hasDelta && totalLotSize > 0) ? (netDelta / totalLotSize) : null;

        StrategyRiskState risk = classifyRisk(netDeltaPerShare, netThetaBenefit, hasDelta, hasTheta);

        List<AdjustmentSuggestion> suggestions = StrategyAdjustmentEngine.suggest(
                legViews, netDelta, netDeltaPerShare, netThetaBenefit,
                hasDelta, hasTheta, shortPremium, risk);

        return new StrategyView(
                strategyId, strategyType, strategyLabel, underlying, expiry,
                openedAt, legViews.size(), totalLots, totalLotSize,
                hasDelta ? netDelta : null,
                netDeltaPerShare,
                hasTheta ? netThetaBenefit : null,
                hasLive ? livePnl : null,
                bookedPnl,
                shortPremium,
                risk, suggestions, legViews
        );
    }

    private static StrategyRiskState classifyRisk(
            Double netDeltaPerShare, double netThetaBenefit,
            boolean hasDelta, boolean hasTheta
    ) {
        // Per-share absolute delta thresholds (calibrated for index options where
        // a single leg's delta lies in [-1, +1]):
        //   |Δ/share| ≤ 0.15 → OK         (close to neutral)
        //   ≤ 0.30           → WATCH      (drifting)
        //   ≤ 0.50           → BREACH     (one tested leg dominating)
        //   >  0.50          → CRITICAL   (delta exposure exceeds short-premium thesis)
        if (!hasDelta || netDeltaPerShare == null) {
            return new StrategyRiskState("UNKNOWN", "No reliable delta yet (need a > 0.50 pt spot move in last 5 min).",
                    0.30, 0.50);
        }
        double abs = Math.abs(netDeltaPerShare);
        String state;
        String reason;
        if (abs <= 0.15) {
            state = "OK";
            reason = "Net delta within neutral band; theta thesis intact.";
        } else if (abs <= 0.30) {
            state = "WATCH";
            reason = "Net delta drifting "
                    + (netDeltaPerShare > 0 ? "long (CE side stressed)" : "short (PE side stressed)") + ".";
        } else if (abs <= 0.50) {
            state = "BREACH";
            reason = "Net delta breach — directional exposure now dominates theta. Adjust or trim.";
        } else {
            state = "CRITICAL";
            reason = "Critical delta exposure — recommended to exit or hedge immediately.";
        }
        if (hasTheta && netThetaBenefit < 0 && abs > 0.15) {
            reason += " Premium also expanding against the book.";
        }
        return new StrategyRiskState(state, reason, 0.30, 0.50);
    }

    public record StrategyLegView(
            String executionId,
            String instrumentId,
            String tradingSymbol,
            String optionType,
            BigDecimal strike,
            LocalDate expiry,
            String transactionType,
            int lots,
            int lotSize,
            int quantity,
            BigDecimal entryPrice,
            BigDecimal currentPrice,
            BigDecimal unbookedPnl,
            BigDecimal bookedPnl,
            Double d5m,
            Double prem5mChg,
            Double netDeltaContribution,
            Double netThetaContribution,
            String status
    ) {}

    public record StrategyRiskState(
            String state,            // OK | WATCH | BREACH | CRITICAL | UNKNOWN
            String reason,
            double warnDeltaPerShare,
            double breachDeltaPerShare
    ) {}

    public record AdjustmentSuggestion(
            String kind,             // ROLL | HEDGE | REDUCE | EXIT | HOLD
            String action,           // ROLL_UP | ROLL_DOWN | ADD_LONG_HEDGE | REDUCE_LOTS | CLOSE_ALL | HOLD
            int priority,            // 1 = highest
            String targetInstrumentId, // nullable
            String rationale,
            String expectedDeltaImpact // human label, e.g. "−0.20 per share"
    ) {}

    public record StrategyView(
            String strategyId,
            String strategyType,
            String strategyLabel,
            String underlying,
            LocalDate expiry,
            Instant openedAt,
            int legCount,
            int totalLots,
            int totalQuantity,
            Double netDelta,           // sum(Δ × qty × sideSign)
            Double netDeltaPerShare,   // netDelta / totalQuantity (comparable across symbols)
            Double netThetaBenefit,    // rupees per ~5 min (positive = book benefiting)
            Double livePnl,
            double bookedPnl,
            boolean shortPremium,      // true = pure short-premium book
            StrategyRiskState risk,
            List<AdjustmentSuggestion> adjustments,
            List<StrategyLegView> legs
    ) {}

    public String toJson(Object value) {
        return gson.toJson(value);
    }

    public boolean isSimulationOnly() {
        return sessionManager == null;
    }

    /**
     * Returns {@code true} when {@link com.strategysquad.platform.config.KiteEndpoints#isPersistenceEnabled()}
     * is {@code false} — i.e. tick data is not being written to {@code options_live_enriched}
     * (typical for the dummy-feed sim console). When disabled, downstream greek aggregation
     * skips the persisted-DB read and relies solely on the in-memory transient model.
     *
     * <p>This is the only "is the underlying market data source dummy?" signal that
     * remains in this class — and even that signal is stated in terms of persistence,
     * not in terms of provider identity.
     */
    private static boolean isPersistenceDisabled() {
        return !com.strategysquad.platform.config.KiteEndpoints.instance().isPersistenceEnabled();
    }

    private void recordQuoteSamples(
            List<ManualExecution> executions,
            Map<String, QuoteSnapshot> quotes,
            Map<String, QuoteSnapshot> spots
    ) {
        long nowMs = Instant.now().toEpochMilli();
        long retentionCutoff = nowMs - GREEK_SAMPLE_RETENTION.toMillis();
        for (ManualExecution execution : executions) {
            QuoteSnapshot quote = quotes.get(execution.instrumentId());
            QuoteSnapshot spot = spots.get(execution.underlying());
            if (quote == null || quote.price() == null || spot == null || spot.price() == null) {
                continue;
            }
            // Use wall-clock time as the sample timestamp.
            // Kite REST returns "1970-01-01 05:30:00" (Instant.EPOCH) pre-market for instruments
            // not yet traded today. Using the quote's asOf directly would collapse all polls
            // into a single map entry (key=0) and make elapsed-time calculations wildly wrong.
            Instant sampleTs = Instant.now();
            QuoteSample sample = new QuoteSample(
                    sampleTs,
                    quote.price().doubleValue(),
                    spot.price().doubleValue()
            );
            ConcurrentSkipListMap<Long, QuoteSample> series = quoteSampleHistory.computeIfAbsent(
                    execution.instrumentId(),
                    ignored -> new ConcurrentSkipListMap<>()
            );
            series.put(sampleTs.toEpochMilli(), sample);
            series.headMap(retentionCutoff).clear();
        }
    }

    private Map<String, double[]> computeExecutionGreeks(
            List<ManualExecution> executions,
            Map<String, QuoteSnapshot> quotes,
            Map<String, QuoteSnapshot> spots,
            boolean dummyMode
    ) {
        Map<String, double[]> result = dummyMode ? new HashMap<>() : new HashMap<>(computeLiveGreeks(executions));
        Map<String, double[]> modelGreeks = computeTransientGreeks(executions, quotes, spots);
        modelGreeks.forEach((instrumentId, fallback) -> {
            double[] existing = result.get(instrumentId);
            if (existing == null || Double.isNaN(existing[0]) || Double.isNaN(existing[1])) {
                result.put(instrumentId, fallback);
            }
        });
        return result;
    }

    private Map<String, double[]> computeTransientGreeks(
            List<ManualExecution> executions,
            Map<String, QuoteSnapshot> quotes,
            Map<String, QuoteSnapshot> spots
    ) {
        Map<String, double[]> result = new HashMap<>();
        Instant now = Instant.now();
        for (ManualExecution execution : executions) {
            QuoteSnapshot quote = quotes.get(execution.instrumentId());
            QuoteSnapshot spot = spots.get(execution.underlying());
            if (quote == null || quote.price() == null || spot == null || spot.price() == null) {
                continue;
            }
            double spotNow = spot.price().doubleValue();
            double priceNow = quote.price().doubleValue();
            double modelDelta = quote.delta() != null
                    ? signCorrectOrderDelta(execution.optionType(), quote.delta())
                    : derivedOrderDelta(execution.optionType(), execution.strike().doubleValue(), spotNow, 0.50d);
            double expectedDecay5m = expectedDecayPerMinute(execution, quote, spotNow) * 5.0d;
            double premiumMoveForTheta = -expectedDecay5m;

            QuoteSample prior = sampleAtOrBefore(execution.instrumentId(), now.minus(GREEK_SAMPLE_WINDOW));
            if (prior != null) {
                double spotMove = spotNow - prior.spot();
                double premiumMove = priceNow - prior.price();
                if (Math.abs(spotMove) >= MIN_SPOT_MOVE_FOR_OBSERVED_DELTA) {
                    modelDelta = signCorrectOrderDelta(execution.optionType(), premiumMove / spotMove);
                }
                double deltaAdjustedMove = premiumMove - (modelDelta * spotMove);
                long elapsedMs = Math.max(1L, Duration.between(prior.ts(), now).toMillis());
                double scaleTo5m = GREEK_SAMPLE_WINDOW.toMillis() / (double) elapsedMs;
                premiumMoveForTheta = clamp(deltaAdjustedMove * scaleTo5m, -expectedDecay5m * 3.0d, expectedDecay5m);
            }
            result.put(execution.instrumentId(), new double[]{modelDelta, premiumMoveForTheta});
        }
        return result;
    }

    private QuoteSample sampleAtOrBefore(String instrumentId, Instant target) {
        ConcurrentSkipListMap<Long, QuoteSample> series = quoteSampleHistory.get(instrumentId);
        if (series == null || series.isEmpty()) {
            return null;
        }
        Map.Entry<Long, QuoteSample> entry = series.floorEntry(target.toEpochMilli());
        return entry == null ? null : entry.getValue();
    }

    static double thetaBenefit(double premiumMove, int quantity, String transactionType) {
        int sideSign = "SELL".equalsIgnoreCase(transactionType) ? -1 : 1;
        return premiumMove * Math.max(0, quantity) * sideSign;
    }

    static double derivedOrderDelta(String optionType, double strike, double spot, double atmAbsDelta) {
        double moneyness = strike - spot;
        double intrinsicDirection = "CE".equalsIgnoreCase(optionType) ? -moneyness : moneyness;
        double absDelta = atmAbsDelta + (intrinsicDirection / 400.0d) * 0.35d;
        return signCorrectOrderDelta(optionType, clamp(absDelta, 0.06d, 0.92d));
    }

    static double estimateOrderThetaDecayPerMinute(double price, int timeBucket15m, int moneynessBucket) {
        double expiryFactor = expiryAcceleration(timeBucket15m);
        double atmFactor = Math.max(0.30d, 1.0d - Math.abs(moneynessBucket) / 500.0d);
        return Math.max(0.0001d, price * 0.00055d * expiryFactor * atmFactor);
    }

    private double expectedDecayPerMinute(ManualExecution execution, QuoteSnapshot quote, double spot) {
        if (quote.thetaDecayPerMinute() != null && quote.thetaDecayPerMinute() > 0.0d) {
            return quote.thetaDecayPerMinute();
        }
        double basePrice = quote.historicalAvgPrice() != null && quote.historicalAvgPrice().compareTo(BigDecimal.ZERO) > 0
                ? quote.historicalAvgPrice().doubleValue()
                : Math.max(0.05d, quote.price().doubleValue());
        int bucket = computeTimeBucket15m(Instant.now(), execution.expiry());
        int moneynessBucket = moneynessBucket(execution.strike().doubleValue(), spot);
        return estimateOrderThetaDecayPerMinute(basePrice, bucket, moneynessBucket);
    }

    private static int moneynessBucket(double strike, double spot) {
        return BigDecimal.valueOf(strike - spot)
                .divide(BigDecimal.valueOf(NIFTY_STRIKE_STEP), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(NIFTY_STRIKE_STEP))
                .intValueExact();
    }

    private static int computeTimeBucket15m(Instant now, LocalDate expiryDate) {
        Instant expiryTs = LocalDateTime.of(expiryDate, java.time.LocalTime.of(15, 30)).atZone(IST).toInstant();
        long minutes = Math.max(0L, Duration.between(now, expiryTs).toMinutes());
        return (int) Math.min(Integer.MAX_VALUE, minutes / 15L);
    }

    private static double expiryAcceleration(int timeBucket15m) {
        if (timeBucket15m <= 8) {
            return 2.0d;
        }
        if (timeBucket15m >= 26) {
            return 0.65d;
        }
        double t = (26.0d - timeBucket15m) / (26.0d - 8.0d);
        return 0.65d + t * 1.35d;
    }

    private static double signCorrectOrderDelta(String optionType, double delta) {
        double abs = Math.abs(delta);
        return "PE".equalsIgnoreCase(optionType) ? -abs : abs;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Computes observed 5-minute delta and raw 5-minute premium change for each
     * execution instrument using the QuestDB HTTP REST API (avoids JDBC contention
     * with the PG-wire connection used by the scanner).
     * Returns a map from instrumentId to double[]{d5m, prem5mChg}.
     * d5m is NaN when spot move is too small. Returns empty map on any error.
     */
    private Map<String, double[]> computeLiveGreeks(List<ManualExecution> executions) {
        Map<String, double[]> result = new HashMap<>();
        if (executions.isEmpty()) return result;
        Instant now = Instant.now();
        long nowMs = now.toEpochMilli();
        long staleMs = 120_000L;
        long ago5mMs = nowMs - 300_000L;
        String cutoff5m = new Timestamp(ago5mMs).toInstant().toString();

        Map<String, List<ManualExecution>> byUnderlying = new HashMap<>();
        for (ManualExecution ex : executions) {
            if (ex.instrumentId() != null && ex.underlying() != null) {
                byUnderlying.computeIfAbsent(ex.underlying(), k -> new ArrayList<>()).add(ex);
            }
        }

        for (Map.Entry<String, List<ManualExecution>> entry : byUnderlying.entrySet()) {
            String underlying = entry.getKey();
            List<ManualExecution> execs = entry.getValue();
            String safeUl = underlying.replace("'", "''");
            String inClause = execs.stream()
                    .map(e -> "'" + e.instrumentId().replace("'", "''") + "'")
                    .collect(Collectors.joining(","));
            try {
                // 1. Current prices + timestamps
                Map<String, Double> premNow = new HashMap<>();
                Map<String, Long> premTs = new HashMap<>();
                String sqlNow = "SELECT instrument_id, last_price, exchange_ts FROM options_live_enriched " +
                        "WHERE underlying = '" + safeUl + "' AND instrument_id IN (" + inClause + ") " +
                        "LATEST ON exchange_ts PARTITION BY instrument_id";
                com.google.gson.JsonObject respNow = questHttpQuery(sqlNow);
                if (respNow != null && respNow.has("dataset")) {
                    int[] cols = jsonCols(respNow, "instrument_id", "last_price", "exchange_ts");
                    for (com.google.gson.JsonElement row : respNow.getAsJsonArray("dataset")) {
                        com.google.gson.JsonArray r = row.getAsJsonArray();
                        String iid = r.get(cols[0]).getAsString();
                        premNow.put(iid, r.get(cols[1]).getAsDouble());
                        try {
                            premTs.put(iid, Instant.parse(r.get(cols[2]).getAsString()).toEpochMilli());
                        } catch (Exception ignore) { premTs.put(iid, 0L); }
                    }
                }

                // 2. Prices 5m ago
                Map<String, Double> prem5m = new HashMap<>();
                String sql5m = "SELECT instrument_id, last_price FROM options_live_enriched " +
                        "WHERE underlying = '" + safeUl + "' AND instrument_id IN (" + inClause + ") " +
                        "  AND exchange_ts <= '" + cutoff5m + "' " +
                        "LATEST ON exchange_ts PARTITION BY instrument_id";
                com.google.gson.JsonObject resp5m = questHttpQuery(sql5m);
                if (resp5m != null && resp5m.has("dataset")) {
                    int[] cols = jsonCols(resp5m, "instrument_id", "last_price");
                    for (com.google.gson.JsonElement row : resp5m.getAsJsonArray("dataset")) {
                        com.google.gson.JsonArray r = row.getAsJsonArray();
                        prem5m.put(r.get(cols[0]).getAsString(), r.get(cols[1]).getAsDouble());
                    }
                }

                // 3. Spot now
                double spotNow = Double.NaN;
                String sqlSpotNow = "SELECT last_price FROM spot_live " +
                        "WHERE underlying = '" + safeUl + "' LATEST ON exchange_ts PARTITION BY underlying";
                com.google.gson.JsonObject rSN = questHttpQuery(sqlSpotNow);
                if (rSN != null && rSN.has("dataset")) {
                    com.google.gson.JsonArray ds = rSN.getAsJsonArray("dataset");
                    if (ds.size() > 0) spotNow = ds.get(0).getAsJsonArray().get(0).getAsDouble();
                }

                // 4. Spot 5m ago
                double spot5m = Double.NaN;
                String sqlSpot5m = "SELECT last_price FROM spot_live " +
                        "WHERE underlying = '" + safeUl + "' AND exchange_ts <= '" + cutoff5m + "' " +
                        "LATEST ON exchange_ts PARTITION BY underlying";
                com.google.gson.JsonObject rS5 = questHttpQuery(sqlSpot5m);
                if (rS5 != null && rS5.has("dataset")) {
                    com.google.gson.JsonArray ds = rS5.getAsJsonArray("dataset");
                    if (ds.size() > 0) spot5m = ds.get(0).getAsJsonArray().get(0).getAsDouble();
                }

                // 5. Compute per-instrument
                for (ManualExecution ex : execs) {
                    String iid = ex.instrumentId();
                    Double pn = premNow.get(iid);
                    Long ts = premTs.get(iid);
                    Double p5 = prem5m.get(iid);
                    if (pn == null || ts == null || (nowMs - ts) > staleMs) continue;
                    if (p5 == null || Double.isNaN(spotNow) || Double.isNaN(spot5m)) continue;
                    double premMove = pn - p5;
                    double spotMove = spotNow - spot5m;
                    double d5mVal = Math.abs(spotMove) >= 0.50 ? premMove / spotMove : Double.NaN;
                    result.put(iid, new double[]{d5mVal, premMove});
                }
            } catch (Exception ex) {
                // greeks are supplemental — silently skip on any error
            }
        }
        return result;
    }

    /** Calls QuestDB HTTP /exec endpoint and returns parsed JSON, or null on any error. */
    private com.google.gson.JsonObject questHttpQuery(String sql) {
        try {
            String encoded = URLEncoder.encode(sql, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:9000/exec?query=" + encoded))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return gson.fromJson(resp.body(), com.google.gson.JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns column index array for the named columns from a QuestDB JSON response. */
    private int[] jsonCols(com.google.gson.JsonObject response, String... names) {
        com.google.gson.JsonArray cols = response.getAsJsonArray("columns");
        int[] idx = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < cols.size(); j++) {
                if (names[i].equals(cols.get(j).getAsJsonObject().get("name").getAsString())) {
                    idx[i] = j;
                    break;
                }
            }
        }
        return idx;
    }

    private ExecutionView toView(
            ManualExecution persisted,
            boolean refreshRealStatus,
            Double d5m,
            Double prem5mChg,
            boolean dummyMode,
            QuoteSnapshot resolvedQuote
    )
            throws IOException, SQLException, InterruptedException {
        ManualExecution execution = persisted;
        if (refreshRealStatus && "real".equals(execution.mode()) && execution.externalOrderId() != null) {
            execution = refreshBrokerState(execution);
        }
        QuoteSnapshot currentQuote = resolvedQuote != null
                ? resolvedQuote
                : loadExecutionQuote(execution.instrumentId(), execution.tradingSymbol(), execution.mode());
        BigDecimal currentPrice = currentQuote == null ? null : currentQuote.price();
        int liveQty = Math.max(0, execution.filledQuantity());
        BigDecimal unbooked = currentPrice == null
                ? null
                : PositionPnlCalculator.livePnl(
                        "SELL".equals(execution.transactionType()) ? "SHORT" : "LONG",
                        execution.entryPrice(),
                        currentPrice,
                        liveQty
                );
        return new ExecutionView(
                execution.executionId(),
                execution.externalOrderId(),
                execution.mode(),
                execution.status(),
                execution.underlying(),
                execution.instrumentId(),
                execution.tradingSymbol(),
                execution.optionType(),
                execution.strike(),
                execution.expiry(),
                execution.transactionType(),
                execution.orderType(),
                execution.product(),
                execution.lots(),
                execution.quantity(),
                execution.filledQuantity(),
                execution.pendingQuantity(),
                execution.lotSize(),
                execution.entryPrice(),
                currentPrice,
                unbooked,
                execution.bookedPnl(),
                currentQuote == null ? null : currentQuote.asOf(),
                currentQuote == null ? "unavailable" : currentQuote.source(),
                execution.statusMessage(),
                execution.createdAt(),
                execution.updatedAt(),
                d5m,
                prem5mChg,
                execution.strategyId(),
                execution.strategyType(),
                execution.strategyLabel()
        );
    }

    private ManualExecution refreshBrokerState(ManualExecution execution) throws IOException, InterruptedException {
        if (sessionManager == null) {
            return execution;
        }
        KiteCredentials credentials = sessionManager.currentCredentials().orElse(null);
        if (credentials == null) {
            return execution;
        }
        BrokerOrderSnapshot snapshot = loadOrderHistory(credentials, execution.externalOrderId());
        if (snapshot == null) {
            return execution;
        }
        ManualExecution updated = new ManualExecution(
                execution.executionId(),
                execution.externalOrderId(),
                execution.mode(),
                snapshot.status() != null ? snapshot.status() : execution.status(),
                execution.underlying(),
                execution.instrumentId(),
                execution.tradingSymbol(),
                execution.optionType(),
                execution.strike(),
                execution.expiry(),
                execution.transactionType(),
                execution.orderType(),
                execution.product(),
                execution.lots(),
                execution.quantity(),
                snapshot.filledQuantity() > 0 ? snapshot.filledQuantity() : execution.filledQuantity(),
                Math.max(0, execution.quantity() - (snapshot.filledQuantity() > 0 ? snapshot.filledQuantity() : execution.filledQuantity())),
                execution.lotSize(),
                snapshot.averagePrice() != null ? snapshot.averagePrice() : execution.entryPrice(),
                execution.requestedPrice(),
                execution.bookedPnl(),
                snapshot.statusMessage(),
                execution.createdAt(),
                Instant.now(),
                execution.strategyId(),
                execution.strategyType(),
                execution.strategyLabel()
        );
        if (!updated.equals(execution)) {
            saveExecution(updated);
        }
        return updated;
    }

    private BrokerOrderSnapshot placeRealOrder(
            KiteCredentials credentials,
            OptionContract contract,
            String transactionType,
            String orderType,
            String product,
            int quantity,
            BigDecimal limitPrice
    ) throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("exchange", "NFO");
        params.put("tradingsymbol", contract.tradingSymbol());
        params.put("transaction_type", transactionType);
        params.put("quantity", Integer.toString(quantity));
        params.put("product", product);
        params.put("order_type", orderType);
        params.put("validity", "DAY");
        params.put("tag", "strategy-squad");
        if ("LIMIT".equals(orderType) && limitPrice != null) {
            params.put("price", limitPrice.stripTrailingZeros().toPlainString());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(orderUrl()))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Kite order placement failed: HTTP " + response.statusCode()
                    + " " + extractError(response.body()));
        }
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        JsonObject data = root == null ? null : root.getAsJsonObject("data");
        String orderId = data != null && data.has("order_id") ? data.get("order_id").getAsString() : null;
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalStateException("Kite order placement did not return an order_id");
        }
        BrokerOrderSnapshot history = loadOrderHistory(credentials, orderId);
        return history != null ? history : new BrokerOrderSnapshot(orderId, "SUBMITTED", null, 0, null);
    }

    private BrokerOrderSnapshot loadOrderHistory(KiteCredentials credentials, String orderId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(orderHistoryUrl() + URLEncoder.encode(orderId, StandardCharsets.UTF_8)))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken())
                .GET()
                .timeout(Duration.ofSeconds(12))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        JsonArray data = root == null ? null : root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            return null;
        }
        JsonObject latest = data.get(data.size() - 1).getAsJsonObject();
        String status = getString(latest, "status");
        BigDecimal averagePrice = getDecimal(latest, "average_price");
        int filledQuantity = getInt(latest, "filled_quantity");
        String statusMessage = getString(latest, "status_message");
        return new BrokerOrderSnapshot(orderId, status, averagePrice, filledQuantity, statusMessage);
    }

    private OptionContract resolveContract(String underlying, String expiry, String strike, String optionType)
            throws SQLException {
        String normalizedUnderlying = normalizeUnderlying(underlying);
        String normalizedOptionType = normalizeOptionType(optionType);
        LocalDate parsedExpiry = parseExpiry(expiry);
        BigDecimal parsedStrike = parseStrike(strike);
        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SELECT_ONE_OPTION_SQL)) {
            statement.setString(1, normalizedUnderlying);
            statement.setTimestamp(2, Timestamp.from(parsedExpiry.atStartOfDay(IST).toInstant()), utcCal());
            statement.setTimestamp(3, Timestamp.from(parsedExpiry.plusDays(1).atStartOfDay(IST).toInstant()), utcCal());
            statement.setDouble(4, parsedStrike.doubleValue());
            statement.setString(5, normalizedOptionType);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Instrument not found for the selected expiry/strike/type");
                }
                return new OptionContract(
                        normalizedUnderlying,
                        rs.getString("instrument_id"),
                        rs.getString("trading_symbol"),
                        rs.getString("option_type"),
                        BigDecimal.valueOf(rs.getDouble("strike")).stripTrailingZeros(),
                        rs.getTimestamp("expiry_date", utcCal()).toInstant().atZone(IST).toLocalDate(),
                        rs.getInt("lot_size")
                );
            }
        }
    }

    private Map<String, QuoteSnapshot> loadExecutionQuotes(List<ManualExecution> executions) {
        Map<String, QuoteSnapshot> quotes = new HashMap<>();
        for (ManualExecution execution : executions) {
            if (execution.instrumentId() == null || execution.instrumentId().isBlank()) {
                continue;
            }
            try {
                QuoteSnapshot quote = loadExecutionQuote(
                        execution.instrumentId(),
                        execution.tradingSymbol(),
                        execution.mode()
                );
                if (quote != null) {
                    quotes.put(execution.instrumentId(), quote);
                }
            } catch (com.strategysquad.marketdata.MissingMarketDataException ignored) {
                // Explicit missing-data signal; leg shows null price in the UI.
            } catch (Exception ignored) {
                // Per-leg quote failures should not blank the entire order page.
            }
        }
        return quotes;
    }

    /**
     * Single, provider-agnostic execution-quote loader. Routes through
     * {@link #loadOptionQuote(String, String)} regardless of whether the underlying
     * market-data provider is live Kite or the dummy feed. The {@code mode} parameter
     * (paper/real) is retained for signature compatibility but no longer triggers any
     * synthetic-pricing fallback.
     */
    private QuoteSnapshot loadExecutionQuote(String instrumentId, String tradingSymbol, String mode)
            throws IOException, SQLException, InterruptedException {
        return loadOptionQuote(instrumentId, tradingSymbol);
    }

    private Map<String, QuoteSnapshot> loadExecutionSpots(List<ManualExecution> executions) {
        Set<String> underlyings = executions.stream()
                .map(ManualExecution::underlying)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, QuoteSnapshot> spots = new HashMap<>();
        for (String underlying : underlyings) {
            try {
                QuoteSnapshot spot = loadSpot(underlying);
                if (spot != null && spot.price() != null) {
                    spots.put(underlying, spot);
                }
            } catch (com.strategysquad.marketdata.MissingMarketDataException ignored) {
                // Explicit missing-data signal; greeks fall back to whatever the cache has.
            } catch (Exception ignored) {
                // Greek estimates are supplemental; a spot miss should not fail P&L refresh.
            }
        }
        return spots;
    }

    /**
     * Provider-agnostic spot loader: live cache first, then a single REST fallback to
     * {@link KiteEndpoints#quoteUrl()} (which the launcher has already configured to point
     * at either the real Kite endpoint or the dummy-kite-feed). Returns {@code null} only
     * when no session manager exists (test environments).
     */
    private QuoteSnapshot loadSpot(String underlying) throws IOException, InterruptedException {
        if (sessionManager == null) {
            return null;
        }
        LiveSessionState.SpotQuote liveSpot = sessionManager.latestSpotQuote(underlying);
        if (liveSpot != null && liveSpot.price() != null) {
            return new QuoteSnapshot(liveSpot.price(), null, null, liveSpot.ts(), "live_cache", false, null, null, null);
        }
        KiteCredentials credentials = sessionManager.currentCredentials().orElse(null);
        if (credentials == null) {
            return null;
        }
        String quoteKey = "NIFTY".equals(underlying) ? "NSE:NIFTY 50" : "NSE:NIFTY BANK";
        return fetchQuote(quoteKey, credentials, "kite_rest");
    }

    // ── REMOVED: dummy-only quote synthesis ──────────────────────────────────────
    // The methods loadDummySpot, loadDummyOptionQuote, fallbackDummyOptionAnchor,
    // syntheticDummyPrice, syntheticDummySpotPrice, tryFetchConfiguredDummyQuote
    // and isConfiguredDummyKiteApi were removed as part of the market-connectivity
    // refactor. All quote loading now goes through loadOptionQuote/loadSpot, which
    // talk to the same MarketDataProvider regardless of source. Missing data is
    // surfaced explicitly (null QuoteSnapshot or MissingMarketDataException),
    // never substituted with a synthetic Black-Scholes-like value.
    // (loadDummySpot / loadDummyOptionQuote bodies removed; see comment above)

    private QuoteSnapshot loadOptionQuote(String instrumentId, String tradingSymbol)
            throws IOException, InterruptedException {
        if (sessionManager == null) {
            return null;
        }
        LiveSessionState.OptionQuote liveQuote = sessionManager.latestOptionQuote(instrumentId);
        if (liveQuote != null && liveQuote.lastPrice() != null) {
            return new QuoteSnapshot(
                    liveQuote.lastPrice(),
                    liveQuote.bidPrice(),
                    liveQuote.askPrice(),
                    liveQuote.ts(),
                    "live_cache",
                    false,
                    null,
                    null,
                    null
            );
        }
        KiteCredentials credentials = sessionManager.currentCredentials().orElse(null);
        if (credentials == null) {
            return null;
        }
        return fetchQuote("NFO:" + tradingSymbol, credentials, "kite_rest");
    }

    private QuoteSnapshot fetchQuote(String quoteKey, KiteCredentials credentials, String source)
            throws IOException, InterruptedException {
        String url = quoteUrl() + "?i=" + URLEncoder.encode(quoteKey, StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Kite-Version", "3")
                .GET()
                .timeout(Duration.ofSeconds(12));
        if (credentials != null) {
            builder.header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken());
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Quote lookup failed: HTTP " + response.statusCode());
        }
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        JsonObject data = root == null ? null : root.getAsJsonObject("data");
        JsonObject quote = data == null ? null : data.getAsJsonObject(quoteKey);
        if (quote == null) {
            return null;
        }
        BigDecimal price = getDecimal(quote, "last_price");
        BigDecimal bidPrice = null;
        BigDecimal askPrice = null;
        JsonObject depth = quote.getAsJsonObject("depth");
        if (depth != null) {
            bidPrice = firstDepthPrice(depth.getAsJsonArray("buy"));
            askPrice = firstDepthPrice(depth.getAsJsonArray("sell"));
        }
        Instant asOf = parseKiteInstant(getString(quote, "timestamp"));
        if (asOf == null) {
            asOf = parseKiteInstant(getString(quote, "last_trade_time"));
        }
        if (asOf == null) {
            asOf = Instant.now();
        }
        Double delta = getDoubleObject(quote, "dummy_delta");
        Double theta = getDoubleObject(quote, "dummy_theta_decay_per_min");
        BigDecimal historicalAvg = getDecimal(quote, "dummy_historical_avg_price");
        return new QuoteSnapshot(price, bidPrice, askPrice, asOf, source, false, delta, theta, historicalAvg);
    }

    /**
     * Phase 1.5: Lightweight snapshot of execution lots for per-tick invariant checks.
     * Reads only the JSON files — no network calls, no greeks, no enrichment.
     *
     * @return map from executionId → current lots (today's executions only)
     */
    public java.util.Map<String, Integer> loadExecutionLots() throws IOException {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        for (ManualExecution exec : loadAllExecutions()) {
            if (exec.executionId() != null && !exec.executionId().isBlank()) {
                result.put(exec.executionId(), exec.lots());
            }
        }
        return result;
    }

    /**
     * Lightweight record used to backfill {@code PositionLegSnapshot.executionId} when the UI
     * forgot to link a session leg to its placed order. Contains only the identity fields
     * needed to match a session leg to an execution; no greeks, no quotes.
     */
    public record ExecutionLinkKey(
            String executionId,
            String instrumentId,
            String transactionType,   // BUY | SELL
            String strategyId,        // may be null
            int lots,
            java.time.Instant createdAt
    ) {}

    /**
     * Returns lightweight identity tuples for today's executions (no enrichment, no quotes).
     * Used by the position-sessions GET path to rescue legacy / UI-bug sessions whose legs
     * have a null {@code executionId} and would otherwise be unreachable by Monitor REDUCE.
     */
    public java.util.List<ExecutionLinkKey> loadTodayExecutionLinkKeys() throws IOException {
        java.util.List<ManualExecution> all = loadAllExecutions();
        java.util.List<ExecutionLinkKey> out = new java.util.ArrayList<>(all.size());
        for (ManualExecution exec : all) {
            if (exec.executionId() == null || exec.executionId().isBlank()) continue;
            out.add(new ExecutionLinkKey(
                    exec.executionId(),
                    exec.instrumentId(),
                    exec.transactionType(),
                    exec.strategyId(),
                    exec.lots(),
                    exec.createdAt()
            ));
        }
        return out;
    }

    private List<ManualExecution> loadAllExecutions() throws IOException {
        if (Files.notExists(storeRoot)) {
            return List.of();
        }
        LocalDate today = LocalDate.now(IST);
        List<ManualExecution> results = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(storeRoot)) {
            for (Path file : stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                    .toList()) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    ManualExecution execution = gson.fromJson(json, ManualExecution.class);
                    if (execution != null && execution.createdAt() != null
                            && execution.createdAt().atZone(IST).toLocalDate().equals(today)) {
                        results.add(execution);
                    }
                } catch (Exception ignored) {
                    // Skip corrupted rows so the desk remains usable.
                }
            }
        }
        return results;
    }

    private void saveExecution(ManualExecution execution) throws IOException {
        Files.createDirectories(storeRoot);
        Path target = storeRoot.resolve(execution.executionId().replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, gson.toJson(execution), StandardCharsets.UTF_8);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public boolean executionExists(String executionId) throws IOException {
        return loadExecutionById(executionId) != null;
    }

    /**
     * Finds a single execution by its ID from the file store.
     * Returns {@code null} if not found or if the file cannot be parsed.
     */
    private ManualExecution loadExecutionById(String executionId) throws IOException {
        if (executionId == null || executionId.isBlank() || Files.notExists(storeRoot)) return null;
        String safeId = executionId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = storeRoot.resolve(safeId + ".json");
        if (Files.notExists(target)) return null;
        try {
            String json = Files.readString(target, StandardCharsets.UTF_8);
            return gson.fromJson(json, ManualExecution.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Partially or fully closes an existing execution at the given {@code closePrice}.
     *
     * <p>Reduces {@code lots} and {@code filledQuantity} by the closed amount, computes
     * booked P&amp;L for the closed portion and accumulates it into {@code bookedPnl}.
     * When all lots are closed the status transitions to {@code "CLOSED"}.
     *
     * @param executionId  id of the execution to adjust
     * @param lotsToClose  positive number of lots to close (must not exceed open lots)
     * @param closePrice   exit price; if {@code null} falls back to entry price (zero P&L close)
     * @throws IllegalArgumentException if the execution is not found or lots are invalid
     */
    public ExecutionView closeLots(String executionId, int lotsToClose, BigDecimal closePrice)
            throws IOException, SQLException, InterruptedException {
        return closeLots(executionId, lotsToClose, closePrice, "MANUAL");
    }

    /**
     * Close (or partially close) lots and record an audit event with the given source.
     *
     * @param source one of {@code "MANUAL"} (Orders / Strategy Lab buttons), {@code "MONITOR"}
     *               (Adjustment Monitor reduce path), or {@code "STRATEGY_LAB"} (basket exit).
     */
    public ExecutionView closeLots(String executionId, int lotsToClose, BigDecimal closePrice, String source)
            throws IOException, SQLException, InterruptedException {
        ManualExecution existing = loadExecutionById(executionId);
        if (existing == null) throw new IllegalArgumentException("Execution not found: " + executionId);
        if (lotsToClose <= 0) throw new IllegalArgumentException("lotsToClose must be a positive integer");
        if (lotsToClose > existing.lots()) throw new IllegalArgumentException(
                "Cannot close " + lotsToClose + " lots — position only has " + existing.lots() + " open lots");

        int closedQty = lotsToClose * existing.lotSize();
        int newLots = existing.lots() - lotsToClose;
        int newQuantity = newLots * existing.lotSize();
        int newFilledQty = Math.max(0, existing.filledQuantity() - closedQty);

        // Use entry price as fallback so a missing price produces zero P&L rather than an error.
        BigDecimal effectiveClosePrice = closePrice != null ? closePrice : existing.entryPrice();
        String side = "SELL".equalsIgnoreCase(existing.transactionType()) ? "SHORT" : "LONG";
        BigDecimal pnlForClosed = PositionPnlCalculator.bookedPnl(
                side, existing.entryPrice(), effectiveClosePrice, closedQty);
        BigDecimal newBookedPnl = PositionPnlCalculator.safe(existing.bookedPnl()).add(pnlForClosed);

        String newStatus = newLots <= 0 ? "CLOSED" : existing.status();

        ManualExecution updated = new ManualExecution(
                existing.executionId(), existing.externalOrderId(), existing.mode(), newStatus,
                existing.underlying(), existing.instrumentId(), existing.tradingSymbol(),
                existing.optionType(), existing.strike(), existing.expiry(),
                existing.transactionType(), existing.orderType(), existing.product(),
                newLots, newQuantity, newFilledQty, existing.pendingQuantity(),
                existing.lotSize(), existing.entryPrice(), existing.requestedPrice(),
                newBookedPnl, existing.statusMessage(), existing.createdAt(), Instant.now(),
                existing.strategyId(), existing.strategyType(), existing.strategyLabel()
        );
        saveExecution(updated);

        // Record REDUCE (partial close) or CLOSE (full close) in the audit ledger.
        String evType = newLots <= 0 ? "CLOSE" : "REDUCE";
        // The closing transaction is the opposite side of the original — sell-to-cover-long
        // or buy-to-cover-short. This makes the Order Log read like a real broker tape.
        String closeSide = "SELL".equalsIgnoreCase(existing.transactionType()) ? "BUY" : "SELL";
        eventStore.record(new OrderEventStore.OrderEvent(
                OrderEventStore.newId(), Instant.now(), evType, "COMPLETED",
                source == null || source.isBlank() ? "MANUAL" : source,
                executionId, existing.strategyId(),
                existing.underlying(), existing.instrumentId(), existing.tradingSymbol(),
                existing.optionType(), existing.strike(),
                closeSide, lotsToClose, existing.lotSize(),
                effectiveClosePrice, pnlForClosed,
                null
        ));

        boolean dummyMode = isPersistenceDisabled();
        Map<String, QuoteSnapshot> quotes = newLots > 0
                ? loadExecutionQuotes(List.of(updated)) : new HashMap<>();
        Map<String, QuoteSnapshot> spots = newLots > 0
                ? loadExecutionSpots(List.of(updated)) : new HashMap<>();
        Map<String, double[]> greeks = computeExecutionGreeks(List.of(updated), quotes, spots, dummyMode);
        double[] g = greeks.get(updated.instrumentId());
        return toView(updated, false,
                (g != null && !Double.isNaN(g[0])) ? g[0] : null,
                (g != null) ? g[1] : null,
                dummyMode,
                quotes.get(updated.instrumentId()));
    }

    /**
     * Deletes all execution files whose {@code createdAt} timestamp falls on today (IST).
     * Used by the Orders desk "Clear All" action to reset positions and trade log for a
     * fresh strategy session.
     *
     * @return the number of files deleted
     * @throws IOException if file deletion fails
     */
    public void clearQuoteSampleHistory() {
        quoteSampleHistory.clear();
    }

    public int clearTodaysExecutions() throws IOException {
        if (Files.notExists(storeRoot)) return 0;
        LocalDate today = LocalDate.now(IST);
        int deleted = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(storeRoot)) {
            for (Path file : stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                    .toList()) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    ManualExecution execution = gson.fromJson(json, ManualExecution.class);
                    if (execution != null && execution.createdAt() != null
                            && execution.createdAt().atZone(IST).toLocalDate().equals(today)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (Exception ignored) {
                    // Skip files that cannot be parsed — leave them on disk.
                }
            }
        }
        // Clear today's order-event audit ledger to keep Order Log in sync with Position table.
        try { eventStore.clearToday(); } catch (Exception ignored) {}
        return deleted;
    }

    /**
     * Deletes execution files from trading days before {@code tradingDay}. This applies to
     * both paper and real executions, and also clears stale temp/corrupted files by mtime.
     */
    public int purgeExecutionsBefore(LocalDate tradingDay, ZoneId zoneId) throws IOException {
        Objects.requireNonNull(tradingDay, "tradingDay must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        if (Files.notExists(storeRoot)) return 0;
        int deleted = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(storeRoot)) {
            for (Path file : stream
                    .filter(this::isExecutionStoreFile)
                    .toList()) {
                try {
                    if (executionFileIsBefore(file, tradingDay, zoneId)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (Exception ignored) {
                    // Keep unreadable files rather than blocking login/startup.
                }
            }
        }
        return deleted;
    }

    /**
     * Returns a fresh UTC Calendar for use with JDBC {@code getTimestamp}/{@code setTimestamp}.
     * Using an explicit UTC Calendar prevents the PostgreSQL JDBC driver from applying
     * the JVM's local timezone (IST) when converting TIMESTAMP WITHOUT TIME ZONE values,
     * which would cause a double-offset: values stored as IST-midnight-in-UTC would be
     * misread by one full IST offset (5h30m).
     * Calendar is not thread-safe, so a new instance is created per call.
     */
    private static Calendar utcCal() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static String normalizeUnderlying(String underlying) {
        String normalized = safeUpper(underlying);
        if (!ALLOWED_UNDERLYINGS.contains(normalized)) {
            throw new IllegalArgumentException("Underlying must be NIFTY or BANKNIFTY");
        }
        return normalized;
    }

    private static String normalizeOptionType(String optionType) {
        String normalized = safeUpper(optionType);
        if (!"CE".equals(normalized) && !"PE".equals(normalized)) {
            throw new IllegalArgumentException("Option type must be CE or PE");
        }
        return normalized;
    }

    private static String normalizeTransactionType(String transactionType) {
        String normalized = safeUpper(transactionType);
        if (!"BUY".equals(normalized) && !"SELL".equals(normalized)) {
            throw new IllegalArgumentException("Side must be BUY or SELL");
        }
        return normalized;
    }

    private static String normalizeOrderType(String orderType) {
        String normalized = safeUpper(orderType);
        if (!"MARKET".equals(normalized) && !"LIMIT".equals(normalized)) {
            throw new IllegalArgumentException("Order type must be MARKET or LIMIT");
        }
        return normalized;
    }

    private static String normalizeProduct(String product) {
        String normalized = safeUpper(product);
        if (!"NRML".equals(normalized) && !"MIS".equals(normalized)) {
            throw new IllegalArgumentException("Product must be NRML or MIS");
        }
        return normalized;
    }

    private static String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase();
        if (!"paper".equals(normalized) && !"real".equals(normalized)) {
            throw new IllegalArgumentException("Mode must be paper or real");
        }
        return normalized;
    }

    private static boolean isDummySource(String source) {
        return source != null && "DUMMY".equalsIgnoreCase(source.trim());
    }

    private static LocalDate parseExpiry(String expiry) {
        try {
            return LocalDate.parse(Objects.requireNonNull(expiry, "expiry must not be null"));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Expiry must be in YYYY-MM-DD format");
        }
    }

    private static BigDecimal parseStrike(String strike) {
        try {
            return new BigDecimal(Objects.requireNonNull(strike, "strike must not be null"));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Strike is required");
        }
    }

    private static BigDecimal determineEntryPrice(String orderType, BigDecimal requestedPrice, QuoteSnapshot liveQuote) {
        if ("LIMIT".equals(orderType) && requestedPrice != null && requestedPrice.compareTo(BigDecimal.ZERO) > 0) {
            return requestedPrice;
        }
        if (liveQuote != null && liveQuote.price() != null && liveQuote.price().compareTo(BigDecimal.ZERO) > 0) {
            return liveQuote.price();
        }
        return requestedPrice;
    }

    // Removed: syntheticDummyPrice / syntheticDummySpotPrice (sin-wave price oscillators).
    // Replaced by direct provider quotes via loadOptionQuote / loadSpot.

    private boolean isExecutionStoreFile(Path path) {
        String name = path == null || path.getFileName() == null ? "" : path.getFileName().toString();
        return name.endsWith(".json") || name.endsWith(".json.tmp") || name.endsWith(".tmp");
    }

    private boolean executionFileIsBefore(Path file, LocalDate tradingDay, ZoneId zoneId) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(".json")) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                ManualExecution execution = gson.fromJson(json, ManualExecution.class);
                if (execution != null && execution.createdAt() != null) {
                    return execution.createdAt().atZone(zoneId).toLocalDate().isBefore(tradingDay);
                }
            } catch (Exception ignored) {
                // Fall back to file mtime below for corrupted legacy rows.
            }
        }
        return Files.getLastModifiedTime(file).toInstant().atZone(zoneId).toLocalDate().isBefore(tradingDay);
    }

    // Removed: fallbackDummyOptionAnchor (BS-style intrinsic + extrinsic calculator).
    // The provider must supply the price; missing data is now surfaced explicitly.

    private static String escapeSql(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String decimalString(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static String formEncode(Map<String, String> params) {
        List<String> parts = new ArrayList<>(params.size());
        params.forEach((key, value) -> parts.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        ));
        return String.join("&", parts);
    }

    private static String extractError(String body) {
        try {
            JsonObject root = new Gson().fromJson(body, JsonObject.class);
            if (root != null && root.has("message")) {
                return root.get("message").getAsString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return body == null ? "" : body;
    }

    private static BigDecimal firstDepthPrice(JsonArray levels) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        JsonObject first = levels.get(0).getAsJsonObject();
        return getDecimal(first, "price");
    }

    private static String getString(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : null;
    }

    private static BigDecimal getDecimal(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? BigDecimal.valueOf(object.get(key).getAsDouble())
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int getInt(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsInt()
                    : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Double getDoubleObject(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsDouble()
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant parseKiteInstant(String value) {
        try {
            return value == null || value.isBlank()
                    ? null
                    : LocalDateTime.parse(value, KITE_TS).atZone(IST).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    public record ExpiryBucket(LocalDate expiry, List<BigDecimal> strikes, int lotSize) {}

    public record QuoteSnapshot(
            BigDecimal price,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            Instant asOf,
            String source,
            boolean stale,
            Double delta,
            Double thetaDecayPerMinute,
            BigDecimal historicalAvgPrice
    ) {}

    public record OptionMetadataResponse(
            String underlying,
            List<ExpiryBucket> expiries,
            QuoteSnapshot spot,
            Instant instrumentUpdatedAt,
            boolean realTradingEnabled,
            String source
    ) {}

    public record QuoteResponse(OptionContract contract, QuoteSnapshot quote) {}

    public record OptionContract(
            String underlying,
            String instrumentId,
            String tradingSymbol,
            String optionType,
            BigDecimal strike,
            LocalDate expiry,
            int lotSize
    ) {}

    public record PlaceOrderRequest(
            String mode,
            String underlying,
            String expiry,
            BigDecimal strike,
            String optionType,
            String transactionType,
            int lots,
            String orderType,
            String product,
            BigDecimal price,
            String strategyId,
            String strategyType,
            String strategyLabel,
            String source
    ) {}

    public record ManualExecution(
            String executionId,
            String externalOrderId,
            String mode,
            String status,
            String underlying,
            String instrumentId,
            String tradingSymbol,
            String optionType,
            BigDecimal strike,
            LocalDate expiry,
            String transactionType,
            String orderType,
            String product,
            int lots,
            int quantity,
            int filledQuantity,
            int pendingQuantity,
            int lotSize,
            BigDecimal entryPrice,
            BigDecimal requestedPrice,
            BigDecimal bookedPnl,
            String statusMessage,
            Instant createdAt,
            Instant updatedAt,
            String strategyId,
            String strategyType,
            String strategyLabel
    ) {}

    public record ExecutionView(
            String executionId,
            String externalOrderId,
            String mode,
            String status,
            String underlying,
            String instrumentId,
            String tradingSymbol,
            String optionType,
            BigDecimal strike,
            LocalDate expiry,
            String transactionType,
            String orderType,
            String product,
            int lots,
            int quantity,
            int filledQuantity,
            int pendingQuantity,
            int lotSize,
            BigDecimal entryPrice,
            BigDecimal currentPrice,
            BigDecimal unbookedPnl,
            BigDecimal bookedPnl,
            Instant quoteAsOf,
            String quoteSource,
            String statusMessage,
            Instant createdAt,
            Instant updatedAt,
            Double d5m,
            Double prem5mChg,
            String strategyId,
            String strategyType,
            String strategyLabel
    ) {}

    private record BrokerOrderSnapshot(
            String orderId,
            String status,
            BigDecimal averagePrice,
            int filledQuantity,
            String statusMessage
    ) {}

    private record QuoteSample(
            Instant ts,
            double price,
            double spot
    ) {}

    private static final class InstantJsonCodec implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
            return src == null ? null : new JsonPrimitive(src.toString());
        }

        @Override
        public Instant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            String value = json.getAsString();
            return value == null || value.isBlank() ? null : Instant.parse(value);
        }
    }

    private static final class LocalDateJsonCodec implements JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {
        @Override
        public JsonElement serialize(LocalDate src, Type typeOfSrc, JsonSerializationContext context) {
            return src == null ? null : new JsonPrimitive(src.toString());
        }

        @Override
        public LocalDate deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            String value = json.getAsString();
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        }
    }
}

