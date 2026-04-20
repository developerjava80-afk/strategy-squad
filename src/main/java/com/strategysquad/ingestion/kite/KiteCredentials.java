package com.strategysquad.ingestion.kite;

import java.util.Objects;

/**
 * Immutable Zerodha Kite Connect session credentials.
 * Loaded once per trading day from kite.properties before market open.
 */
public record KiteCredentials(String apiKey, String accessToken, String userId) {

    public KiteCredentials {
        apiKey = requireNonBlank(apiKey, "apiKey");
        accessToken = requireNonBlank(accessToken, "accessToken");
        userId = requireNonBlank(userId, "userId");
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
