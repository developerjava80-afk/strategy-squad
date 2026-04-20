package com.strategysquad.ingestion.kite;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Loads current live index spot from Kite quote API for option-universe selection.
 */
public final class KiteSpotQuoteService {

    private static final String QUOTE_URL =
            "https://api.kite.trade/quote?i=NSE:NIFTY%2050&i=NSE:NIFTY%20BANK";

    private final HttpClient httpClient;
    private final Gson gson;

    public KiteSpotQuoteService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new Gson());
    }

    KiteSpotQuoteService(HttpClient httpClient, Gson gson) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.gson = Objects.requireNonNull(gson, "gson must not be null");
    }

    public SpotBaseline load(KiteCredentials credentials) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(QUOTE_URL))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken())
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Kite spot quote failed with HTTP " + response.statusCode());
        }

        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        JsonObject data = root == null ? null : root.getAsJsonObject("data");
        if (data == null) {
            throw new IOException("Kite spot quote response missing data");
        }

        BigDecimal nifty = extractPrice(data, "NSE:NIFTY 50");
        BigDecimal bankNifty = extractPrice(data, "NSE:NIFTY BANK");
        if (nifty == null || bankNifty == null) {
            throw new IOException("Kite spot quote response missing NIFTY/BANKNIFTY price");
        }
        return new SpotBaseline(nifty.doubleValue(), bankNifty.doubleValue());
    }

    private static BigDecimal extractPrice(JsonObject data, String key) {
        JsonObject quote = data.getAsJsonObject(key);
        if (quote == null || !quote.has("last_price")) {
            return null;
        }
        return BigDecimal.valueOf(quote.get("last_price").getAsDouble());
    }

    public record SpotBaseline(double niftySpot, double bankNiftySpot) {
    }
}
