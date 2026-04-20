package com.strategysquad.ingestion.kite;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.strategysquad.ingestion.live.session.Live15mAggregator;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the Kite Connect REST {@code /quote} API every 2 seconds for all subscribed
 * instruments and feeds ticks into the live ingestion pipeline.
 *
 * <p>Replaces WebSocket (which requires the Kite SDK jar) with standard
 * {@link java.net.http.HttpClient} — no external dependencies beyond Gson.
 *
 * <p>Reliability model:
 * <ul>
 *   <li>HTTP errors → log + retry next poll cycle (no crash)</li>
 *   <li>No response for 30s → status = STALE</li>
 *   <li>Only polls during Indian market hours (09:00–15:35 IST)</li>
 * </ul>
 *
 * <p>Kite quote API: {@code GET https://api.kite.trade/quote?i=NSE:NIFTY 50&i=NFO:NIFTY26APR22500CE&...}
 */
public final class KiteTickerSession {

    private static final String QUOTE_URL = "https://api.kite.trade/quote";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 35);
    private static final int POLL_INTERVAL_SECONDS = 2;
    private static final int STALE_THRESHOLD_SECONDS = 30;
    private static final int CHUNK_SIZE = 500; // Kite max instruments per /quote call

    private final KiteLiveConfig config;
    private final KiteTickerAdapter adapter;
    private final KiteLiveIngestionJob ingestionJob;
    private final LiveSessionState sessionState;
    private final Live15mAggregator aggregator;
    private final HttpClient httpClient;
    private final Gson gson;
    private final List<String> quoteKeys; // e.g. ["NSE:NIFTY 50", "NFO:NIFTY26APR22500CE", ...]
    private final String jdbcUrl;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "kite-poller"); t.setDaemon(true); return t; });

    private volatile boolean shutdown = false;

    public KiteTickerSession(
            KiteLiveConfig config,
            List<KiteInstrumentRecord> instruments,
            Map<Long, String> tokenToInstrumentId,
            LiveSessionState sessionState,
            Live15mAggregator aggregator,
            String jdbcUrl
    ) {
        this.config = Objects.requireNonNull(config);
        this.sessionState = Objects.requireNonNull(sessionState);
        this.aggregator = Objects.requireNonNull(aggregator);
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();

        // Build quoteKey → instrumentId map and quoteKeys list
        Map<String, String> quoteKeyToId = buildQuoteKeyMap(instruments, tokenToInstrumentId);
        this.quoteKeys = buildQuoteKeyList(instruments);
        sessionState.setSubscribedInstruments(quoteKeys.size());

        this.adapter = new KiteTickerAdapter(quoteKeyToId, sessionState);
        this.ingestionJob = new KiteLiveIngestionJob(aggregator);
    }

    /** Starts the polling scheduler. Returns immediately; polling runs on a background thread. */
    public void connect() {
        if (!isMarketHours()) {
            System.out.println("[kite-poller] Outside market hours — not starting.");
            return;
        }
        sessionState.setStatus(LiveSessionState.Status.CONNECTING);
        scheduler.scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkStaleness, 15, 15, TimeUnit.SECONDS);
        System.out.printf("[kite-poller] Started polling %d instruments every %ds%n",
                quoteKeys.size(), POLL_INTERVAL_SECONDS);
    }

    /** Graceful shutdown. */
    public void shutdown() {
        shutdown = true;
        scheduler.shutdownNow();
        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl)) {
            aggregator.flushAll(connection, LocalDate.now(IST));
        } catch (SQLException ex) {
            System.err.println("[kite-poller] Failed to flush live 15m buckets on shutdown: " + ex.getMessage());
        }
        sessionState.setStatus(LiveSessionState.Status.DISCONNECTED);
        sessionState.setDisconnectReason("Session shut down");
        System.out.println("[kite-poller] Shutdown complete.");
    }

    // ── polling ─────────────────────────────────────────────────────────────

    private void poll() {
        if (shutdown || !isMarketHours()) return;
        try {
            // Chunk into batches of CHUNK_SIZE to respect Kite limits
            List<List<String>> chunks = chunk(quoteKeys, CHUNK_SIZE);
            for (List<String> chunk : chunks) {
                String url = buildUrl(chunk);
                String body = fetch(url);
                if (body == null) continue;
                processResponse(body);
            }
        } catch (Exception ex) {
            System.err.println("[kite-poller] Poll error: " + ex.getMessage());
        }
    }

    private String fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Kite-Version", "3")
                    .header("Authorization",
                            "token " + config.credentials().apiKey()
                                    + ":" + config.credentials().accessToken())
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                sessionState.setStatus(LiveSessionState.Status.CONNECTED);
                return response.body();
            }
            if (response.statusCode() == 403) {
                sessionState.setStatus(LiveSessionState.Status.TOKEN_EXPIRED);
                sessionState.setDisconnectReason("Access token expired — regenerate via Kite login");
                shutdown = true;
                scheduler.shutdownNow();
                System.err.println("[kite-poller] Access token expired. Stopping.");
                return null;
            }
            System.err.printf("[kite-poller] HTTP %d from /quote%n", response.statusCode());
        } catch (Exception ex) {
            System.err.println("[kite-poller] Fetch error: " + ex.getMessage());
        }
        return null;
    }

    private void processResponse(String body) {
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || !root.has("data")) return;
            JsonObject data = root.getAsJsonObject("data");
            KiteTickerAdapter.AdaptedTicks adapted = adapter.adapt(data);
            if (adapted.isEmpty()) return;

            try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
                ingestionJob.ingest(conn, adapted.optionTicks(), adapted.spotTicks(),
                        LocalDate.now(IST));
            } catch (SQLException ex) {
                System.err.println("[kite-poller] DB write failed: " + ex.getMessage());
            }
        } catch (Exception ex) {
            System.err.println("[kite-poller] Response parse error: " + ex.getMessage());
        }
    }

    private void checkStaleness() {
        if (shutdown) return;
        Instant last = sessionState.getLastTickTs();
        if (last == null) return;
        long secondsAgo = Duration.between(last, Instant.now()).toSeconds();
        if (secondsAgo >= STALE_THRESHOLD_SECONDS
                && sessionState.getStatus() == LiveSessionState.Status.CONNECTED) {
            sessionState.setStatus(LiveSessionState.Status.STALE);
        }
    }

    // ── URL building ─────────────────────────────────────────────────────────

    private static String buildUrl(List<String> keys) {
        StringBuilder sb = new StringBuilder(QUOTE_URL).append('?');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append("i=").append(URLEncoder.encode(keys.get(i), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ── instrument key helpers ────────────────────────────────────────────────

    private static Map<String, String> buildQuoteKeyMap(
            List<KiteInstrumentRecord> instruments, Map<Long, String> tokenToId) {
        Map<String, String> map = new HashMap<>(instruments.size() * 2 + 4);
        for (KiteInstrumentRecord rec : instruments) {
            String instrumentId = tokenToId.get(rec.instrumentToken());
            if (instrumentId != null) {
                map.put("NFO:" + rec.tradingSymbol(), instrumentId);
            }
        }
        return map;
    }

    private static List<String> buildQuoteKeyList(List<KiteInstrumentRecord> instruments) {
        List<String> keys = new ArrayList<>(instruments.size() + 2);
        keys.add(KiteTickerAdapter.NIFTY_QUOTE_KEY);
        keys.add(KiteTickerAdapter.BANKNIFTY_QUOTE_KEY);
        for (KiteInstrumentRecord rec : instruments) {
            keys.add("NFO:" + rec.tradingSymbol());
        }
        return keys;
    }

    private static <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }

    private static boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(MARKET_OPEN) && !now.isAfter(MARKET_CLOSE);
    }
}
