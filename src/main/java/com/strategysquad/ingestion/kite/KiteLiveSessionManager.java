package com.strategysquad.ingestion.kite;

import com.strategysquad.ingestion.live.session.Live15mAggregator;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the day-scoped Zerodha login state and starts/stops the live ticker session.
 */
public final class KiteLiveSessionManager {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 35);
    private static final String SELECT_LAST_SPOT_SQL =
            "SELECT close_price"
                    + " FROM spot_historical"
                    + " WHERE underlying = ?"
                    + " ORDER BY trade_ts DESC"
                    + " LIMIT 1";

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
        this.baseConfig = Objects.requireNonNull(baseConfig, "baseConfig must not be null");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore must not be null");
        this.instrumentsDumpJob = new KiteInstrumentsDumpJob();
        this.sessionTokenExchange = new KiteSessionTokenExchange();
        this.tokenValidator = new KiteTokenValidator();
        this.spotQuoteService = new KiteSpotQuoteService();
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState must not be null");
        this.live15mAggregator = Objects.requireNonNull(live15mAggregator, "live15mAggregator must not be null");
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public synchronized void initialize() throws IOException {
        LocalDate today = LocalDate.now(IST);
        Optional<String> token = tokenStore.loadForDate(today);
        if (token.isPresent()) {
            if (!isMarketHours()) {
                sessionState.resetForLogin();
                sessionState.setStatus(LiveSessionState.Status.DISCONNECTED);
                sessionState.setDisconnectReason("Outside market hours. Live polling will start after market open.");
                return;
            }
            try {
                validateAndStartSession(token.get());
            } catch (RuntimeException exception) {
                tokenStore.clear();
                sessionState.resetForLogin();
                sessionState.setDisconnectReason("Stored token failed validation. Login required.");
            }
        }
    }

    public synchronized KiteAuthStatus authStatus() throws IOException {
        LocalDate today = LocalDate.now(IST);
        boolean tokenForToday = tokenStore.loadForDate(today).isPresent();
        boolean tokenExpired = sessionState.getStatus() == LiveSessionState.Status.TOKEN_EXPIRED;
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
        validateAndStartSession(accessToken);
        tokenStore.saveForDate(accessToken, LocalDate.now(IST));
        return authStatus();
    }

    public synchronized void shutdown() {
        if (activeSession != null) {
            activeSession.shutdown();
            activeSession = null;
        }
    }

    private void startSession(String accessToken) {
        shutdown();
        sessionState.resetForLogin();
        KiteLiveConfig runtimeConfig = baseConfig.withAccessToken(accessToken);
        KiteInstrumentsDumpJob.DumpResult dumpResult;
        KiteSpotQuoteService.SpotBaseline spotBaseline;
        try {
            spotBaseline = spotQuoteService.load(runtimeConfig.credentials());
        } catch (Exception exception) {
            spotBaseline = null;
        }
        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl)) {
            double niftyEstimate = spotBaseline != null
                    ? spotBaseline.niftySpot()
                    : latestSpotEstimate(connection, "NIFTY");
            double bankNiftyEstimate = spotBaseline != null
                    ? spotBaseline.bankNiftySpot()
                    : latestSpotEstimate(connection, "BANKNIFTY");
            dumpResult = instrumentsDumpJob.run(
                    connection,
                    LocalDate.now(IST),
                    niftyEstimate,
                    bankNiftyEstimate,
                    runtimeConfig
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize live instrument universe: " + exception.getMessage(), exception);
        }
        System.out.printf("[kite-live] Using ATM baseline NIFTY=%.2f BANKNIFTY=%.2f%n",
                spotBaseline != null ? spotBaseline.niftySpot() : Double.NaN,
                spotBaseline != null ? spotBaseline.bankNiftySpot() : Double.NaN);
        activeSession = new KiteTickerSession(
                runtimeConfig,
                dumpResult.filteredInstruments(),
                dumpResult.tokenToInstrumentId(),
                sessionState,
                live15mAggregator,
                jdbcUrl
        );
        activeSession.connect();
    }

    private void validateAndStartSession(String accessToken) {
        KiteCredentials credentials = new KiteCredentials(baseConfig.apiKey(), accessToken, baseConfig.userId());
        try {
            tokenValidator.validate(credentials);
        } catch (IllegalArgumentException exception) {
            sessionState.resetForLogin();
            sessionState.setStatus(LiveSessionState.Status.TOKEN_EXPIRED);
            sessionState.setDisconnectReason(exception.getMessage());
            throw exception;
        } catch (IOException | InterruptedException exception) {
            sessionState.resetForLogin();
            sessionState.setDisconnectReason("Unable to validate Kite token: " + exception.getMessage());
            throw new IllegalStateException("Unable to validate Kite token: " + exception.getMessage(), exception);
        }
        startSession(accessToken);
    }

    private String exchangeRequestToken(String requestToken) throws IOException {
        try {
            return sessionTokenExchange.exchange(baseConfig, requestToken);
        } catch (IllegalArgumentException exception) {
            sessionState.resetForLogin();
            sessionState.setStatus(LiveSessionState.Status.TOKEN_EXPIRED);
            sessionState.setDisconnectReason(exception.getMessage());
            throw exception;
        } catch (IOException | InterruptedException exception) {
            sessionState.resetForLogin();
            sessionState.setDisconnectReason("Unable to exchange request_token: " + exception.getMessage());
            throw new IllegalStateException("Unable to exchange request_token: " + exception.getMessage(), exception);
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
        throw new IllegalStateException("Missing historical spot baseline for " + underlying);
    }

    private static boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(MARKET_OPEN) && !now.isAfter(MARKET_CLOSE);
    }
}
