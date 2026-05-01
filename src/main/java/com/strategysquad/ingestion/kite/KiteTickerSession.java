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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the Kite Connect REST {@code /quote} API every second for all subscribed
 * instruments and feeds ticks into the live ingestion pipeline.
 *
 * <p>Replaces WebSocket (which requires the Kite SDK jar) with standard
 * {@link java.net.http.HttpClient} — no external dependencies beyond Gson.
 *
 * <h2>Subscription model (Phase 3 scope-first)</h2>
 * The subscribed instrument set is now owned by a {@link KiteSubscriptionManager}.
 * The session holds a reference to the manager and calls {@link KiteSubscriptionManager#snapshot()}
 * once per poll cycle. This means:
 * <ul>
 *   <li>The quote-key list and the key-to-id map are always consistent within a cycle.</li>
 *   <li>A scope swap ({@link KiteSubscriptionManager#bind}) takes effect on the
 *       next poll without restarting the poller.</li>
 *   <li>The session no longer owns the subscription list; it only owns the polling schedule.</li>
 * </ul>
 *
 * <h2>Reliability model</h2>
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
    private static final int POLL_INTERVAL_SECONDS = 1;
    private static final int STALE_THRESHOLD_SECONDS = 30;
    private static final int CHUNK_SIZE = 500; // Kite max instruments per /quote call

    private final KiteLiveConfig config;
    private final KiteSubscriptionManager subscriptionManager;
    private final KiteTickerAdapter adapter;
    private final KiteLiveIngestionJob ingestionJob;
    private final LiveSessionState sessionState;
    private final Live15mAggregator aggregator;
    private final HttpClient httpClient;
    private final Gson gson;
    private final String jdbcUrl;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "kite-poller"); t.setDaemon(true); return t; });

    private volatile boolean shutdown = false;

    /**
     * Creates a session backed by a {@link KiteSubscriptionManager}.
     *
     * <p>The session starts with whatever instruments are currently bound in
     * {@code subscriptionManager}. The subscription can be swapped at any time
     * by calling {@link KiteSubscriptionManager#bind} or {@link KiteSubscriptionManager#unbindAll}
     * from another thread; the change takes effect on the next poll cycle.
     *
     * @param config              Kite credentials and configuration
     * @param subscriptionManager source of truth for the subscribed instrument set
     * @param sessionState        shared live session state (status, last tick, etc.)
     * @param aggregator          15-minute aggregator for flush-on-shutdown
     * @param jdbcUrl             QuestDB JDBC URL for ingestion writes
     */
    public KiteTickerSession(
            KiteLiveConfig config,
            KiteSubscriptionManager subscriptionManager,
            LiveSessionState sessionState,
            Live15mAggregator aggregator,
            String jdbcUrl
    ) {
        this.config              = Objects.requireNonNull(config);
        this.subscriptionManager = Objects.requireNonNull(subscriptionManager);
        this.sessionState        = Objects.requireNonNull(sessionState);
        this.aggregator          = Objects.requireNonNull(aggregator);
        this.jdbcUrl             = Objects.requireNonNull(jdbcUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();

        this.adapter     = new KiteTickerAdapter(sessionState);
        this.ingestionJob = new KiteLiveIngestionJob(aggregator, sessionState, jdbcUrl);

        // Initialise subscribed-instrument count in session state
        sessionState.setSubscribedInstruments(subscriptionManager.snapshot().totalCount());
    }

    /** Starts the polling scheduler. Returns immediately; polling runs on a background thread. */
    public void connect() {
        sessionState.setStatus(LiveSessionState.Status.CONNECTING);
        // Always fetch prices once immediately so the UI shows last-traded prices on startup,
        // even outside market hours (prices will be static closing LTPs).
        scheduler.schedule(this::pollUnguarded, 1, TimeUnit.SECONDS);
        // Continuous polling at 1s cadence — poll() itself only runs during market hours
        // to avoid hammering the Kite API and exhausting JVM heap outside 09:00–15:35 IST.
        scheduler.scheduleAtFixedRate(this::poll, 5, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkStaleness, 15, 15, TimeUnit.SECONDS);
        System.out.printf("[kite-poller] Started polling %d instruments every %ds%n",
                subscriptionManager.snapshot().totalCount(), POLL_INTERVAL_SECONDS);
        if (!isMarketHours()) {
            System.out.println("[kite-poller] Outside market hours — prices seeded once; continuous polling resumes at 09:00 IST.");
        }
    }

    /** Graceful shutdown. Flushes any in-progress 15m buckets to QuestDB. */
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

    /** Market-hours-guarded poll — runs only during 09:00–15:35 IST. */
    private void poll() {
        if (shutdown || !isMarketHours()) return;
        doPoll();
    }

    /** One-shot unguarded poll — used at startup to seed UI prices regardless of time. */
    private void pollUnguarded() {
        if (shutdown) return;
        doPoll();
    }

    private void doPoll() {
        try {
            // Take a single atomic snapshot for this poll cycle.
            // Both quoteKeys and quoteKeyToId are consistent within the snapshot.
            KiteSubscriptionManager.Subscription sub = subscriptionManager.snapshot();

            // Update session state with the current subscription size
            sessionState.setSubscribedInstruments(sub.totalCount());

            // Chunk into batches of CHUNK_SIZE to respect Kite limits
            List<List<String>> chunks = chunk(sub.quoteKeys(), CHUNK_SIZE);
            for (List<String> chunkKeys : chunks) {
                String url = buildUrl(chunkKeys);
                String body = fetch(url);
                if (body == null) continue;
                processResponse(body, sub);
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

    private void processResponse(String body, KiteSubscriptionManager.Subscription sub) {
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || !root.has("data")) return;
            JsonObject data = root.getAsJsonObject("data");
            // Pass the snapshot's quoteKeyToId map so adapter never sees a hybrid state
            KiteTickerAdapter.AdaptedTicks adapted = adapter.adapt(data, sub.quoteKeyToId());
            if (adapted.isEmpty()) return;

            // Retry up to 3 times on transient connection failures (e.g. QuestDB not yet ready)
            SQLException lastEx = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
                    ingestionJob.ingest(conn, adapted.optionTicks(), adapted.spotTicks(),
                            LocalDate.now(IST));
                    return; // success
                } catch (SQLException ex) {
                    lastEx = ex;
                    if (attempt < 3) {
                        try { Thread.sleep(200L * attempt); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); return;
                        }
                    }
                }
            }
            System.err.println("[kite-poller] DB write failed after 3 attempts: " + lastEx.getMessage());
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
            // Use %20 for spaces (RFC 3986 percent-encoding), not + (form encoding).
            // Kite's /quote API requires %20; + causes silent key mismatch in the response.
            sb.append("i=").append(URLEncoder.encode(keys.get(i), StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return sb.toString();
    }

    // ── utility ──────────────────────────────────────────────────────────────

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
