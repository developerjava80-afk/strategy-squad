package com.strategysquad.ingestion.kite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads Kite Connect credentials from a Java properties file.
 *
 * <p>Expected keys: {@code kite.api.key}, {@code kite.access.token}, {@code kite.user.id}.
 */
public final class KiteCredentialsLoader {

    private KiteCredentialsLoader() {
    }

    public static KiteCredentials loadFromFile(Path propertiesFile) throws IOException {
        Properties props = loadProperties(propertiesFile);
        return new KiteCredentials(
                required(props, "kite.api.key"),
                required(props, "kite.access.token"),
                required(props, "kite.user.id")
        );
    }

    static Properties loadProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(file)) {
            props.load(is);
        }
        return props;
    }

    static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required property '" + key + "' in kite.properties");
        }
        return value.trim();
    }

    static String optional(Properties props, String key, String defaultValue) {
        String value = props.getProperty(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
