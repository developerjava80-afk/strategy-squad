package com.strategysquad.ingestion.bhavcopy;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * Normalized report descriptor discovered from the NSE derivatives reports workflow.
 */
public record BhavcopyReport(
        LocalDate tradeDate,
        String reportName,
        URI downloadUri,
        String fileName,
        FileType fileType
) {
    public BhavcopyReport {
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(reportName, "reportName must not be null");
        Objects.requireNonNull(downloadUri, "downloadUri must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(fileType, "fileType must not be null");
    }

    static BhavcopyReport fromLink(LocalDate tradeDate, String reportName, URI downloadUri) {
        Objects.requireNonNull(downloadUri, "downloadUri must not be null");
        String path = downloadUri.getPath();
        String fileName = path == null || path.isBlank()
                ? sanitizeReportName(reportName)
                : Path.of(path).getFileName().toString();
        return new BhavcopyReport(
                tradeDate,
                reportName == null || reportName.isBlank() ? fileName : reportName.trim(),
                downloadUri,
                fileName,
                FileType.fromFileName(fileName)
        );
    }

    private static String sanitizeReportName(String reportName) {
        String normalized = reportName == null ? "report" : reportName.trim();
        if (normalized.isEmpty()) {
            return "report";
        }
        return normalized.replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    public enum FileType {
        ZIP,
        CSV,
        OTHER;

        static FileType fromFileName(String fileName) {
            String normalized = Objects.requireNonNull(fileName, "fileName must not be null")
                    .toLowerCase(Locale.ENGLISH);
            if (normalized.endsWith(".zip")) {
                return ZIP;
            }
            if (normalized.endsWith(".csv")) {
                return CSV;
            }
            return OTHER;
        }
    }
}
