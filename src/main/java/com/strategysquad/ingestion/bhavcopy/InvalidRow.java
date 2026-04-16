package com.strategysquad.ingestion.bhavcopy;

import java.util.Objects;

/**
 * Represents an invalid Bhavcopy row and rejection reason.
 */
public record InvalidRow(
        long lineNumber,
        String reason,
        String rawData
) {
    public InvalidRow {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(rawData, "rawData must not be null");
    }
}
