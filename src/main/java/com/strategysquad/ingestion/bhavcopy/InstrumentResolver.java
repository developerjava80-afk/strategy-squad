package com.strategysquad.ingestion.bhavcopy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Resolves a stable instrument_id for Bhavcopy option contracts.
 */
public class InstrumentResolver {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String ID_PREFIX = "INS_";

    public String resolveInstrumentId(InstrumentKey key) {
        Objects.requireNonNull(key, "key must not be null");
        String identity = canonicalIdentity(key);
        return ID_PREFIX + sha256Hex(identity);
    }

    private String canonicalIdentity(InstrumentKey key) {
        return String.join(
                "|",
                key.underlying(),
                key.expiryDate().format(DATE_FORMATTER),
                key.strike().toPlainString(),
                key.optionType()
        );
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
