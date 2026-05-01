package com.strategysquad.research;

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
import com.strategysquad.ingestion.kite.KiteCredentials;
import com.strategysquad.ingestion.kite.KiteLiveSessionManager;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private static final String QUOTE_URL = "https://api.kite.trade/quote";
    private static final String ORDER_URL = "https://api.kite.trade/orders/regular";
    private static final String ORDER_HISTORY_URL = "https://api.kite.trade/orders/";
    private static final DateTimeFormatter KITE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String jdbcUrl;
    private final KiteLiveSessionManager sessionManager;
    private final Path storeRoot;
    private final Gson gson;
    private final HttpClient httpClient;

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
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.gson = new GsonBuilder()
                .serializeNulls()
                .registerTypeAdapter(Instant.class, new InstantJsonCodec())
                .registerTypeAdapter(LocalDate.class, new LocalDateJsonCodec())
                .create();
    }

    public OptionMetadataResponse loadMetadata(String underlying)
            throws SQLException, IOException, InterruptedException {
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
                sessionManager.currentCredentials().isPresent(),
                "kite_instrument_master"
        );
    }

    public QuoteResponse loadQuote(String underlying, String expiry, String strike, String optionType)
            throws SQLException, IOException, InterruptedException {
        OptionContract contract = resolveContract(underlying, expiry, strike, optionType);
        QuoteSnapshot quote = loadOptionQuote(contract.instrumentId(), contract.tradingSymbol());
        return new QuoteResponse(contract, quote);
    }

    public ExecutionView placeOrder(PlaceOrderRequest request)
            throws SQLException, IOException, InterruptedException {
        Objects.requireNonNull(request, "request must not be null");
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

        QuoteSnapshot liveQuote = loadOptionQuote(contract.instrumentId(), contract.tradingSymbol());
        BigDecimal fallbackEntry = determineEntryPrice(orderType, request.price(), liveQuote);
        Instant now = Instant.now();

        BrokerOrderSnapshot brokerSnapshot = null;
        String status;
        String externalOrderId = null;
        BigDecimal entryPrice;
        int filledQuantity;
        int pendingQuantity;
        if ("real".equals(mode)) {
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
                now
        );
        saveExecution(execution);
        return toView(execution, true);
    }

    public List<ExecutionView> loadExecutions() throws IOException, SQLException, InterruptedException {
        List<ManualExecution> executions = loadAllExecutions();
        List<ExecutionView> views = new ArrayList<>(executions.size());
        for (ManualExecution execution : executions) {
            views.add(toView(execution, true));
        }
        views.sort(Comparator.comparing(ExecutionView::createdAt).reversed());
        return views;
    }

    public String toJson(Object value) {
        return gson.toJson(value);
    }

    private ExecutionView toView(ManualExecution persisted, boolean refreshRealStatus)
            throws IOException, SQLException, InterruptedException {
        ManualExecution execution = persisted;
        if (refreshRealStatus && "real".equals(execution.mode()) && execution.externalOrderId() != null) {
            execution = refreshBrokerState(execution);
        }
        QuoteSnapshot currentQuote = loadOptionQuote(execution.instrumentId(), execution.tradingSymbol());
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
                execution.updatedAt()
        );
    }

    private ManualExecution refreshBrokerState(ManualExecution execution) throws IOException, InterruptedException {
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
                Instant.now()
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
                .uri(URI.create(ORDER_URL))
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
                .uri(URI.create(ORDER_HISTORY_URL + URLEncoder.encode(orderId, StandardCharsets.UTF_8)))
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

    private QuoteSnapshot loadSpot(String underlying) throws IOException, InterruptedException {
        LiveSessionState.SpotQuote liveSpot = sessionManager.latestSpotQuote(underlying);
        if (liveSpot != null && liveSpot.price() != null) {
            return new QuoteSnapshot(liveSpot.price(), null, null, liveSpot.ts(), "live_cache", false);
        }
        KiteCredentials credentials = sessionManager.currentCredentials().orElse(null);
        if (credentials == null) {
            return null;
        }
        String quoteKey = "NIFTY".equals(underlying) ? "NSE:NIFTY 50" : "NSE:NIFTY BANK";
        return fetchQuote(quoteKey, credentials, "kite_rest");
    }

    private QuoteSnapshot loadOptionQuote(String instrumentId, String tradingSymbol)
            throws IOException, InterruptedException {
        LiveSessionState.OptionQuote liveQuote = sessionManager.latestOptionQuote(instrumentId);
        if (liveQuote != null && liveQuote.lastPrice() != null) {
            return new QuoteSnapshot(
                    liveQuote.lastPrice(),
                    liveQuote.bidPrice(),
                    liveQuote.askPrice(),
                    liveQuote.ts(),
                    "live_cache",
                    false
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
        String url = QUOTE_URL + "?i=" + URLEncoder.encode(quoteKey, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken())
                .GET()
                .timeout(Duration.ofSeconds(12))
                .build();
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
        return new QuoteSnapshot(price, bidPrice, askPrice, asOf, source, false);
    }

    private List<ManualExecution> loadAllExecutions() throws IOException {
        if (Files.notExists(storeRoot)) {
            return List.of();
        }
        List<ManualExecution> results = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(storeRoot)) {
            for (Path file : stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                    .toList()) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    ManualExecution execution = gson.fromJson(json, ManualExecution.class);
                    if (execution != null) {
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
            boolean stale
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
            BigDecimal price
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
            Instant updatedAt
    ) {}

    public record ExecutionView(
            String executionId,
            String externalOrderId,
            String mode,
            String status,
            String underlying,
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
            Instant updatedAt
    ) {}

    private record BrokerOrderSnapshot(
            String orderId,
            String status,
            BigDecimal averagePrice,
            int filledQuantity,
            String statusMessage
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
