package com.strategysquad.ingestion.kite;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Persists the Zerodha access token for the active trading day only.
 *
 * <p>The token is stored in {@code kite.local.properties}, which is gitignored.
 */
public final class KiteDailyTokenStore {

    private static final String TOKEN_KEY = "kite.access.token";
    private static final String TOKEN_DATE_KEY = "kite.access.token.date";

    private final Path tokenFile;

    public KiteDailyTokenStore(Path tokenFile) {
        this.tokenFile = Objects.requireNonNull(tokenFile, "tokenFile must not be null");
    }

    public Optional<String> loadForDate(LocalDate date) throws IOException {
        Properties properties = loadProperties();
        String storedDate = properties.getProperty(TOKEN_DATE_KEY, "").trim();
        String token = properties.getProperty(TOKEN_KEY, "").trim();
        if (token.isBlank() || !date.toString().equals(storedDate)) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    public void saveForDate(String token, LocalDate date) throws IOException {
        Properties properties = loadProperties();
        properties.setProperty(TOKEN_KEY, requireNonBlank(token, "token"));
        properties.setProperty(TOKEN_DATE_KEY, date.toString());
        store(properties);
    }

    public void clear() throws IOException {
        if (Files.exists(tokenFile)) {
            Files.delete(tokenFile);
        }
    }

    public Path tokenFile() {
        return tokenFile;
    }

    private Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        if (Files.notExists(tokenFile)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(tokenFile)) {
            properties.load(inputStream);
        }
        return properties;
    }

    private void store(Properties properties) throws IOException {
        Path parent = tokenFile.getParent();
        if (parent != null && Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        try (OutputStream outputStream = Files.newOutputStream(tokenFile)) {
            properties.store(outputStream, "Strategy Squad local Kite session");
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
