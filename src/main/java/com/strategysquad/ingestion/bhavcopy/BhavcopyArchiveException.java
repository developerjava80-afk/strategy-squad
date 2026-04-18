package com.strategysquad.ingestion.bhavcopy;

import java.net.URI;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Explicit failure raised while downloading or extracting a historical Bhavcopy archive.
 */
public class BhavcopyArchiveException extends Exception {
    private final Reason reason;
    private final LocalDate tradeDate;
    private final URI archiveUri;

    public BhavcopyArchiveException(Reason reason, LocalDate tradeDate, URI archiveUri, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.tradeDate = tradeDate;
        this.archiveUri = archiveUri;
    }

    public BhavcopyArchiveException(Reason reason, LocalDate tradeDate, URI archiveUri, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.tradeDate = tradeDate;
        this.archiveUri = archiveUri;
    }

    public Reason reason() {
        return reason;
    }

    public LocalDate tradeDate() {
        return tradeDate;
    }

    public URI archiveUri() {
        return archiveUri;
    }

    public enum Reason {
        INVALID_DATE,
        ARCHIVE_NOT_FOUND,
        DOWNLOAD_FAILED,
        EXTRACTION_FAILED
    }
}
