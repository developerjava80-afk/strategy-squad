package com.strategysquad.ingestion.kite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Validates that the configured Kite API key and submitted access token form a usable pair.
 */
public final class KiteTokenValidator {

    private static final String PROBE_URL = "https://api.kite.trade/quote?i=NSE:NIFTY%2050";

    private final HttpClient httpClient;

    public KiteTokenValidator() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    KiteTokenValidator(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    public void validate(KiteCredentials credentials) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PROBE_URL))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken())
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return;
        }
        if (response.statusCode() == 403) {
            throw new IllegalArgumentException("Incorrect Kite API key / access token pair. Generate the token from the same app as kite.api.key.");
        }
        throw new IllegalStateException("Kite validation failed with HTTP " + response.statusCode());
    }
}
