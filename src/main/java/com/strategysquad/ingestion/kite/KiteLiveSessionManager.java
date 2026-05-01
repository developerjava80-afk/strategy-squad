package com.strategysquad.ingestion.kite;

import com.strategysquad.ingestion.live.session.Live15mAggregator;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the day-scoped Zerodha login state and starts/stops the live ticker session.
 *
 * <p>Phase 6 (scope-first): the two-phase ATM auto-expansion has been removed.
 * On login the session starts with an <em>empty</em> instrument subscription — spot keys
 * only ({@code NSE:NIFTY 50}, {@code NSE:NIFTY BANK}) for display purposes.
 * Instrument subscriptions are bound exclusively by
 * {@link com.strategysquad.scope.ScopeService} when the user activates a scope via
 * {@code POST /api/scope}. The JVM never holds the full NFO instrument list after
 * {@link KiteInstrumentsDumpJob} writes it to {@code instrument_master}.
 */
public final class KiteLiveSessionManager {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 35);

    private static final String SELECT_LAST_SPOT_SQL =
            "SELECT close_price FROM spot_historical WHERE underlying = ? ORDER BY trade_ts DESC LIMIT 1";

    private final KiteLiveConfig baseConfig;
    private final KiteDailyTokenStore tokenStore;
    private final KiteInstrumentsDumpJob instrumentsDumpJob;
    private final KiteSessionTokenExchange sessionTokenExchange;
    private final KiteTokenValidator tokenValidator;
    private final KiteSpotQuoteService spotQuoteService;
    private final LiveSessionState sessionState;
    private final Live15mAggregator live15mAggregator;
    private final String jdbcUrl;

    private volatile KiteTickerSession activeSession;

    public KiteLiveSessionManager(
            KiteLiveConfig baseConfig,
            KiteDailyTokenStore tokenStore,
            LiveSessionState sessionState,
            Live15mAggregator live15mAggregator,
            String jdbcUrl
    ) {
        this.baseConfig            = Objects.requireNonNull(baseConfig,        "baseConfig must not be null");
        this.tokenStore            = Objects.requireNonNull(tokenStore,         "tokenStore must not be null");
        this.instrumentsDumpJob    = new KiteInstrumentsDumpJob();
        this.sessionTokenExchange  = new KiteSessionTokenExchange();
        this.tokenValidator        = new KiteTokenValidator();
        this.spotQuoteService      = new KiteSpotQuoteService();
        this.sessionState          = Objects.requireNonNull(sessionState,       "sessionState must not be null");
        this.live15mAggregator     = Objects.requireNonNull(live15mAggregator,  "live15mAggregator must not be null");
        this.jdbcUrl               = Objects.requireNonNull(jdbcUrl,            "jdbcUrl must not be null");
    }

    /**
     * Called at JVM startup. If a valid today-scoped token exists and the market is open,
     * starts a live ticker session (spot-keys-only subscription). Outside market hours,
     * refreshes {@code instrument_master} only.
     */
    public synchronized void initialize() throws IOException {
        LocalDate today = LocalDate.now(IST);
        Optional<String> token = tokenStore.loadForDate(today);
        if (token.isPresent()) {
            if (!isMarketHours()) {
                try {
                    refreshInstrumentsWithToken(token.get());
                } catch (Exception ex) {
                    System.err.printf("[kite-live] Instrument refresh at startup failed: %s%n", ex.getMessage());
                }
                sessionState.resetForLogin();
                sessionState.setStatus(LiveSessionState.Status.DISCONNECTED);
                sessionState.setDisconnectReason("Outside market hours. Live polling will start after market open.");
                return;
            }
            try {
                validateAndStartSession(token.get());
            } catch (RuntimeException ex) {
                tokenStore.clear();
                sessionState.resetForLogin();
                sessionState.setDisconnectReason("Stored token failed validation. Login required.");
            }
        }
    }

    /**
     * Refreshes {@code instrument_master} from the Kite NFO dump without starting or
     * restarting the live ticker. Safe to call at any time when a valid token is available.
     *
     * @return number of newly inserted instruments, or -1 if no token is available for today
     */
    public synchronized int refreshInstruments() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Optional<String> token = tokenStore.loadForDate(today);
        if (token.isEmpty()) return -1;
        return refreshInstrumentsWithToken(token.get());
    }

    private int refreshInstrumentsWithToken(String accessToken) throws Exception {
        KiteLiveConfig runtimeConfig = baseConfig.withAccessToken(accessToken);
        double niftyAtm;
        double bankNiftyAtm;
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
            niftyAtm     = latestSpotEstimate(conn, "NIFTY");
            bankNiftyAtm = latestSpotEstimate(conn, "BANKNIFTY");
        }
        List<KiteInstrumentRecord> fullRecords = instrumentsDumpJob.downloadFull(
                runtimeConfig, LocalDate.now(IST), niftyAtm, bankNiftyAtm);
        Map<Long, String> tokenToId = instrumentsDumpJob.buildTokenMap(fullRecords);
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
            int inserted = instrumentsDumpJob.insertNew(conn, fullRecords, tokenToId, LocalDate.now(IST));
            System.out.printf("[kite-live] Instrument refresh: %d total, %d newly inserted.%n",
                    fullRecords.size(), inserted);
            return inserted;
        }
    }

    public synchronized KiteAuthStatus authStatus() throws IOException {
        LocalDate today = LocalDate.now(IST);
        boolean tokenForToday = tokenStore.loadForDate(today).isPresent();
        boolean tokenExpired  = sessionState.getStatus() == LiveSessionState.Status.TOKEN_EXPIRED;
        boolean authenticated = tokenForToday && !tokenExpired;
        String message;
        if (tokenExpired) {
            message = "Stored token expired. Login with Kite and provide a fresh request_token.";
        } else if (authenticated) {
            message = "Authenticated for today's live session.";
        } else {
            message = "Login with Kite and provide today's request_token to start the live console.";
        }
        return new KiteAuthStatus(
                authenticated,
                !authenticated,
                baseConfig.userId(),
                today,
                sessionState.getStatus().name(),
                message,
                "https://kite.zerodha.com/connect/login?v=3&api_key=" + baseConfig.apiKey(),
                tokenStore.tokenFile()
        );
    }

    public synchronized KiteAuthStatus login(String requestToken) throws IOException {
        String accessToken = exchangeRequestToken(requestToken);
        // Persist token before starting session so it survives a rate-limited instrument download.
        tokenStore.saveForDate(accessToken, LocalDate.now(IST));
        try {
            validateAndStartSession(accessToken);
        } catch (IllegalStateException ex) {
            sessionState.setDisconnectReason("Session start failed: " + ex.getMessage()
                    + ". Token is saved — call /api/admin/instruments/refresh to retry.");
        }
        return authStatus();
    }

    public Optional<KiteCredentials> currentCredentials() {
        try {
            return tokenStore.loadForDate(LocalDate.now(IST))
                    .map(token -> new KiteCredentials(baseConfig.apiKey(), token, baseConfig.userId()));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    public LiveSessionState.OptionQuote latestOptionQuote(String instrumentId) {
        return instrumentId == null || instrumentId.isBlank() ? null : sessionState.getLatestQuote(instrumentId);
    }

    public LiveSessionState.SpotQuote latestSpotQuote(String underlying) {
        return underlying == null || underlying.isBlank() ? null : sessionState.getLatestSpot(underlying);
    }

    public synchronized void shutdown() {
        if (activeSession != null) {
            activeSession.shutdown();
            activeSession = null;
        }
    }

    /**
     * Scope-first session start (Phase 6).
     *
     * <ol>
     *   <li>Refresh {@code instrument_master} from the Kite NFO dump (cached for the day).</li>
     *   <li>Start a {@link KiteTickerSession} with an <em>empty</em> subscription set.</li>
     * </ol>
     *
     * <p>No ATM instruments are subscribed here. The {@link com.strategysquad.scope.ScopeService}
     * binds the actual instrument set when the user activates a scope via {@code POST /api/scope}.
     */
    private void startSession(String accessToken) {
        shutdown();
        sessionState.resetForLogin();
        KiteLiveConfig runtimeConfig = baseConfig.withAccessToken(accessToken);

        // Refresh instrument_master so the scope picker can show available expiries.
        try {
            refreshInstrumentsWithToken(accessToken);
        } catch (Exception ex) {
            // Non-fatal: instrument_master may have yesterday's data; scope picker will still work.
            System.err.printf("[kite-live] Instrument refresh on login failed (%s) — using cached instrument_master.%n",
                    ex.getMessage());
        }

        // Start ticker with empty subscription — scope activation will bind instruments.
        KiteSubscriptionManager subManager = new KiteSubscriptionManager();
        activeSession = new KiteTickerSession(runtimeConfig, subManager,
                sessionState, live15mAggregator, jdbcUrl);
        activeSession.connect();
        System.out.println("[kite-live] Ticker started (scope-first: 0 instruments subscribed). " +
                "Activate a scope via POST /api/scope to begin live data.");
    }

    private void validateAndStartSession(String accessToken) {
        KiteCredentials credentials = new KiteCredentials(baseConfig.apiKey(), accessToken, baseConfig.userId());
        try {
            tokenValidator.validate(credentials);
        } catch (IllegalArgumentException ex) {
            sessionState.resetForLogin();
            sessionState.setStatus(LiveSessionState.Status.TOKEN_EXPIRED);
            sessionState.setDisconnectReason(ex.getMessage());
            throw ex;
        } catch (IOException | InterruptedException ex) {
            sessionState.resetForLogin();
            sessionState.setDisconnectReason("Unable to validate Kite token: " + ex.getMessage());
            throw new IllegalStateException("Unable to validate Kite token: " + ex.getMessage(), ex);
        }
        startSession(accessToken);
    }

    private String exchangeRequestToken(String requestToken) throws IOException {
        try {
            return sessionTokenExchange.exchange(baseConfig, requestToken);
        } catch (IllegalArgumentException ex) {
            sessionState.resetForLogin();
            sessionState.setStatus(LiveSessionState.Status.TOKEN_EXPIRED);
            sessionState.setDisconnectReason(ex.getMessage());
            throw ex;
        } catch (IOException | InterruptedException ex) {
            sessionState.resetForLogin();
            sessionState.setDisconnectReason("Unable to exchange request_token: " + ex.getMessage());
            throw new IllegalStateException("Unable to exchange request_token: " + ex.getMessage(), ex);
        }
    }

    private double latestSpotEstimate(Connection connection, String underlying) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(SELECT_LAST_SPOT_SQL)) {
            stmt.setString(1, underlying);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("close_price");
                }
            }
        }
        double defaultAtm = "BANKNIFTY".equals(underlying) ? 52000.0 : 24000.0;
        System.out.printf("[kite-live] No historical spot found for %s — using default ATM estimate %.0f%n",
                underlying, defaultAtm);
        return defaultAtm;
    }

    private static boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(MARKET_OPEN) && !now.isAfter(MARKET_CLOSE);
    }
}
