package com.strategysquad.ingestion.kite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * All live-session configuration loaded from kite.properties.
 */
public final class KiteLiveConfig {

    private final String apiKey;
    private final String apiSecret;
    private final String accessToken;
    private final String userId;
    private final double niftyStrikeWindowPoints;
    private final double bankNiftyStrikeWindowPoints;
    private final boolean subscribeNextWeekly;
    private final boolean subscribeNextMonthly;
    private final int tickBufferMs;
    private final int tickBufferMaxSize;
    private final String jdbcUrl;
    private final int consolePort;

    private KiteLiveConfig(
            String apiKey,
            String apiSecret,
            String accessToken,
            String userId,
            double niftyStrikeWindowPoints,
            double bankNiftyStrikeWindowPoints,
            boolean subscribeNextWeekly,
            boolean subscribeNextMonthly,
            int tickBufferMs,
            int tickBufferMaxSize,
            String jdbcUrl,
            int consolePort
    ) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.accessToken = accessToken;
        this.userId = userId;
        this.niftyStrikeWindowPoints = niftyStrikeWindowPoints;
        this.bankNiftyStrikeWindowPoints = bankNiftyStrikeWindowPoints;
        this.subscribeNextWeekly = subscribeNextWeekly;
        this.subscribeNextMonthly = subscribeNextMonthly;
        this.tickBufferMs = tickBufferMs;
        this.tickBufferMaxSize = tickBufferMaxSize;
        this.jdbcUrl = jdbcUrl;
        this.consolePort = consolePort;
    }

    public static KiteLiveConfig loadFromFile(Path propertiesFile) throws IOException {
        Properties props = KiteCredentialsLoader.loadProperties(propertiesFile);
        return new KiteLiveConfig(
                KiteCredentialsLoader.required(props, "kite.api.key"),
                KiteCredentialsLoader.required(props, "kite.api.secret"),
                blankToNull(KiteCredentialsLoader.optional(props, "kite.access.token", "")),
                KiteCredentialsLoader.required(props, "kite.user.id"),
                parseDouble(props, "kite.nifty.strike.window.points", 2000.0),
                parseDouble(props, "kite.banknifty.strike.window.points", 4000.0),
                parseBoolean(props, "kite.subscribe.next.weekly", false),
                parseBoolean(props, "kite.subscribe.next.monthly", false),
                parseInt(props, "kite.tick.buffer.ms", 200),
                parseInt(props, "kite.tick.buffer.max.size", 50),
                KiteCredentialsLoader.optional(props, "kite.jdbc.url", "jdbc:postgresql://localhost:8812/qdb"),
                parseInt(props, "kite.console.port", 8080)
        );
    }

    public KiteCredentials credentials() {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Kite access token is not available for the current session");
        }
        return new KiteCredentials(apiKey, accessToken, userId);
    }

    public KiteLiveConfig withAccessToken(String newAccessToken) {
        return new KiteLiveConfig(
            apiKey,
            apiSecret,
            newAccessToken,
            userId,
                niftyStrikeWindowPoints,
                bankNiftyStrikeWindowPoints,
                subscribeNextWeekly,
                subscribeNextMonthly,
                tickBufferMs,
                tickBufferMaxSize,
                jdbcUrl,
                consolePort
        );
    }

    public String apiKey() { return apiKey; }
    public String apiSecret() { return apiSecret; }
    public String accessToken() { return accessToken; }
    public String userId() { return userId; }
    public double niftyStrikeWindowPoints() { return niftyStrikeWindowPoints; }
    public double bankNiftyStrikeWindowPoints() { return bankNiftyStrikeWindowPoints; }
    public boolean subscribeNextWeekly() { return subscribeNextWeekly; }
    public boolean subscribeNextMonthly() { return subscribeNextMonthly; }
    public int tickBufferMs() { return tickBufferMs; }
    public int tickBufferMaxSize() { return tickBufferMaxSize; }
    public String jdbcUrl() { return jdbcUrl; }
    public int consolePort() { return consolePort; }

    private static double parseDouble(Properties props, String key, double defaultValue) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? defaultValue : Double.parseDouble(v.trim());
    }

    private static int parseInt(Properties props, String key, int defaultValue) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? defaultValue : Integer.parseInt(v.trim());
    }

    private static boolean parseBoolean(Properties props, String key, boolean defaultValue) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
