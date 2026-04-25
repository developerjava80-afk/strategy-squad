package com.strategysquad.ingestion.kite;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads option close prices from Kite quote API and caches them for the trading day.
 */
public final class KiteOptionCloseQuoteService {

    private static final String QUOTE_URL = "https://api.kite.trade/quote";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int CHUNK_SIZE = 500;
    private static final String NIFTY_SPOT_KEY = "NSE:NIFTY 50";
    private static final String BANKNIFTY_SPOT_KEY = "NSE:NIFTY BANK";

    private final String apiKey;
    private final KiteDailyTokenStore tokenStore;
    private final HttpClient httpClient;
    private final Gson gson;

    private final AtomicReference<LocalDate> cacheDate = new AtomicReference<>(null);
    private final ConcurrentHashMap<String, BigDecimal> closePriceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> spotPriceCache = new ConcurrentHashMap<>();

    public KiteOptionCloseQuoteService(String apiKey, KiteDailyTokenStore tokenStore) {
        this(
                apiKey,
                tokenStore,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new Gson()
        );
    }

    KiteOptionCloseQuoteService(
            String apiKey,
            KiteDailyTokenStore tokenStore,
            HttpClient httpClient,
            Gson gson
    ) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.gson = Objects.requireNonNull(gson, "gson must not be null");
    }

    public Map<String, BigDecimal> loadTodayClosePrices(List<InstrumentRef> instruments) {
        Objects.requireNonNull(instruments, "instruments must not be null");
        if (instruments.isEmpty()) {
            return Map.of();
        }

        LocalDate today = LocalDate.now(IST);
        resetCacheIfDateChanged(today);

        List<InstrumentRef> pending = new ArrayList<>();
        Map<String, BigDecimal> resolved = new LinkedHashMap<>();
        for (InstrumentRef instrument : instruments) {
            if (instrument == null || instrument.instrumentId() == null || instrument.tradingSymbol() == null) {
                continue;
            }
            BigDecimal cached = closePriceCache.get(instrument.instrumentId());
            if (cached != null) {
                resolved.put(instrument.instrumentId(), cached);
            } else {
                pending.add(instrument);
            }
        }

        if (pending.isEmpty()) {
            return resolved;
        }

        Optional<String> accessToken;
        try {
            accessToken = tokenStore.loadForDate(today);
        } catch (IOException exception) {
            return resolved;
        }
        if (accessToken.isEmpty()) {
            return resolved;
        }

        for (int i = 0; i < pending.size(); i += CHUNK_SIZE) {
            List<InstrumentRef> chunk = pending.subList(i, Math.min(i + CHUNK_SIZE, pending.size()));
            Map<String, BigDecimal> fetched = fetchChunk(chunk, accessToken.get());
            if (fetched.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> entry : fetched.entrySet()) {
                closePriceCache.put(entry.getKey(), entry.getValue());
                resolved.put(entry.getKey(), entry.getValue());
            }
        }

        return resolved;
    }

    public BigDecimal loadTodaySpotPrice(String underlying) {
        if (underlying == null || underlying.isBlank()) {
            return null;
        }
        String normalized = underlying.trim().toUpperCase();
        LocalDate today = LocalDate.now(IST);
        resetCacheIfDateChanged(today);

        BigDecimal cached = spotPriceCache.get(normalized);
        if (cached != null) {
            return cached;
        }

        String quoteKey = toSpotQuoteKey(normalized);
        if (quoteKey == null) {
            return null;
        }

        Optional<String> accessToken;
        try {
            accessToken = tokenStore.loadForDate(today);
        } catch (IOException exception) {
            return null;
        }
        if (accessToken.isEmpty()) {
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(List.of(quoteKey))))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + apiKey + ":" + accessToken.get())
                .GET()
                .timeout(Duration.ofSeconds(12))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonObject root = gson.fromJson(response.body(), JsonObject.class);
            JsonObject data = root == null ? null : root.getAsJsonObject("data");
            JsonObject quote = data == null ? null : data.getAsJsonObject(quoteKey);
            if (quote == null) {
                return null;
            }
            BigDecimal value = extractClosePrice(quote);
            if (value != null) {
                spotPriceCache.put(normalized, value);
            }
            return value;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String toSpotQuoteKey(String underlying) {
        if ("NIFTY".equals(underlying)) {
            return NIFTY_SPOT_KEY;
        }
        if ("BANKNIFTY".equals(underlying)) {
            return BANKNIFTY_SPOT_KEY;
        }
        return null;
    }

    private Map<String, BigDecimal> fetchChunk(List<InstrumentRef> instruments, String accessToken) {
        Map<String, String> quoteKeyToInstrument = new HashMap<>();
        List<String> quoteKeys = new ArrayList<>(instruments.size());
        for (InstrumentRef instrument : instruments) {
            String quoteKey = "NFO:" + instrument.tradingSymbol();
            quoteKeys.add(quoteKey);
            quoteKeyToInstrument.put(quoteKey, instrument.instrumentId());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(quoteKeys)))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .GET()
                .timeout(Duration.ofSeconds(12))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Map.of();
            }
            return parseResponse(response.body(), quoteKeyToInstrument);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        }
    }

    private Map<String, BigDecimal> parseResponse(String body, Map<String, String> quoteKeyToInstrument) {
        JsonObject root = gson.fromJson(body, JsonObject.class);
        JsonObject data = root == null ? null : root.getAsJsonObject("data");
        if (data == null) {
            return Map.of();
        }

        Map<String, BigDecimal> prices = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            String instrumentId = quoteKeyToInstrument.get(entry.getKey());
            if (instrumentId == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject quote = entry.getValue().getAsJsonObject();
            BigDecimal close = extractClosePrice(quote);
            if (close != null) {
                prices.put(instrumentId, close);
            }
        }
        return prices;
    }

    private static BigDecimal extractClosePrice(JsonObject quote) {
        try {
            // last_price is today's last traded price; after market close it equals today's close.
            // ohlc.close in the Kite REST API is the PREVIOUS session's close, not today's,
            // so we must NOT prefer it over last_price.
            if (quote.has("last_price")) {
                double last = quote.get("last_price").getAsDouble();
                if (last > 0) {
                    return BigDecimal.valueOf(last);
                }
            }
            // Fallback: prior-session close — only reached when last_price is unavailable.
            JsonObject ohlc = quote.getAsJsonObject("ohlc");
            if (ohlc != null && ohlc.has("close")) {
                double close = ohlc.get("close").getAsDouble();
                if (close > 0) {
                    return BigDecimal.valueOf(close);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String buildUrl(List<String> quoteKeys) {
        StringBuilder sb = new StringBuilder(QUOTE_URL).append('?');
        for (int i = 0; i < quoteKeys.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append("i=")
                    .append(URLEncoder.encode(quoteKeys.get(i), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private void resetCacheIfDateChanged(LocalDate today) {
        LocalDate existing = cacheDate.get();
        if (!today.equals(existing) && cacheDate.compareAndSet(existing, today)) {
            closePriceCache.clear();
            spotPriceCache.clear();
        }
    }

    public record InstrumentRef(String instrumentId, String tradingSymbol) {
    }
}