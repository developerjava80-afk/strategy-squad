package com.strategysquad.ingestion.kite;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Exchanges a one-time Kite {@code request_token} for the daily {@code access_token}.
 */
public final class KiteSessionTokenExchange {

    private static final String SESSION_TOKEN_URL = "https://api.kite.trade/session/token";

    private final HttpClient httpClient;
    private final Gson gson;

    public KiteSessionTokenExchange() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new Gson());
    }

    KiteSessionTokenExchange(HttpClient httpClient, Gson gson) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.gson = Objects.requireNonNull(gson, "gson must not be null");
    }

    public String exchange(KiteLiveConfig config, String requestToken) throws IOException, InterruptedException {
        String token = requireNonBlank(requestToken, "requestToken");
        String checksum = sha256(config.apiKey() + token + config.apiSecret());
        String body = "api_key=" + urlEncode(config.apiKey())
                + "&request_token=" + urlEncode(token)
                + "&checksum=" + urlEncode(checksum);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SESSION_TOKEN_URL))
                .header("X-Kite-Version", "3")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        if (response.statusCode() == 200
                && root != null
                && root.has("data")
                && root.getAsJsonObject("data").has("access_token")) {
            return root.getAsJsonObject("data").get("access_token").getAsString();
        }

        String message = root != null && root.has("message")
                ? root.get("message").getAsString()
                : "Kite session exchange failed with HTTP " + response.statusCode();
        throw new IllegalArgumentException(message);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute Kite session checksum", exception);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
