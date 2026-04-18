package com.strategysquad.ingestion.bhavcopy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bulk-loads a directory of Bhavcopy CSV files into the historical tables.
 *
 * <p>Designed for loading 2+ years of historical data. Files are sorted by date
 * token and then by source priority so true index spot files load before
 * derivative proxy files for the same trade date.
 */
public class BhavcopyBulkLoadJob {
    private static final Logger LOGGER = Logger.getLogger(BhavcopyBulkLoadJob.class.getName());
    private static final Pattern UDIFF_DATE_TOKEN = Pattern.compile("(\\d{8})");
    private static final Pattern LEGACY_DATE_TOKEN = Pattern.compile("fo(\\d{2}[A-Z]{3}\\d{4})bhav\\.csv", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter UDIFF_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
        private static final DateTimeFormatter LEGACY_DATE_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("ddMMMyyyy")
            .toFormatter(Locale.ENGLISH);

    private final BhavcopyIngestionJob singleFileJob;

    public BhavcopyBulkLoadJob() {
        this(new BhavcopyIngestionJob());
    }

    public BhavcopyBulkLoadJob(BhavcopyIngestionJob singleFileJob) {
        this.singleFileJob = Objects.requireNonNull(singleFileJob, "singleFileJob must not be null");
    }

    public BulkLoadResult loadDirectory(Path directory, Connection connection) throws IOException, SQLException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(connection, "connection must not be null");
        if (!Files.isDirectory(directory)) {
            throw new IOException("Not a directory: " + directory);
        }

        List<Path> csvFiles = new ArrayList<>(collectCsvFiles(directory));
        csvFiles.sort(Comparator
            .comparing(BhavcopyBulkLoadJob::extractFileDate, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparingInt(BhavcopyBulkLoadJob::sourcePriority)
                .thenComparing(path -> path.toString().toLowerCase()));

        LOGGER.info(() -> "Bulk load: found " + csvFiles.size() + " CSV file(s) in " + directory);

        int totalFiles = csvFiles.size();
        int successfulFiles = 0;
        int totalRowsRead = 0;
        int totalOptionsInserted = 0;
        int totalInstrumentsInserted = 0;
        int totalSpotInserted = 0;
        List<FileError> fileErrors = new ArrayList<>();

        for (int i = 0; i < csvFiles.size(); i++) {
            Path csvFile = csvFiles.get(i);
            try {
                BhavcopyIngestionJob.IngestionResult result = singleFileJob.ingest(csvFile, connection);
                successfulFiles++;
                totalRowsRead += result.totalRowsRead();
                totalOptionsInserted += result.optionsHistoricalInserted();
                totalInstrumentsInserted += result.instrumentsInserted();
                totalSpotInserted += result.spotHistoricalInserted();
                int pct = totalFiles > 0 ? ((i + 1) * 100 / totalFiles) : 0;
                LOGGER.info("Bulk load: completed " + csvFile.getFileName() + " (" + pct + "%)");
            } catch (Exception ex) {
                fileErrors.add(new FileError(csvFile, ex.getMessage()));
                LOGGER.log(Level.WARNING, "Bulk load: failed to process " + csvFile.getFileName(), ex);
            }
        }

        LOGGER.info("Bulk load complete: " + successfulFiles + "/" + totalFiles + " files"
                + " totalRows=" + totalRowsRead
                + " optionsInserted=" + totalOptionsInserted
                + " instrumentsInserted=" + totalInstrumentsInserted
                + " spotInserted=" + totalSpotInserted
                + " errors=" + fileErrors.size());

        return new BulkLoadResult(
                totalFiles, successfulFiles, totalRowsRead,
                totalOptionsInserted, totalInstrumentsInserted, totalSpotInserted,
                fileErrors
        );
    }

    private List<Path> collectCsvFiles(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .toList();
        }
    }

    private static LocalDate extractFileDate(Path path) {
        String fileName = path.getFileName().toString();

        Matcher udiffMatcher = UDIFF_DATE_TOKEN.matcher(fileName);
        if (udiffMatcher.find()) {
            return LocalDate.parse(udiffMatcher.group(1), UDIFF_DATE_FORMATTER);
        }

        Matcher legacyMatcher = LEGACY_DATE_TOKEN.matcher(fileName);
        if (legacyMatcher.find()) {
            return LocalDate.parse(legacyMatcher.group(1), LEGACY_DATE_FORMATTER);
        }

        return null;
    }

    private static int sourcePriority(Path path) {
        String normalized = path.toString().toLowerCase();
        if (normalized.contains("index") || normalized.contains("indices") || normalized.contains("spot")) {
            return 0;
        }
        return 1;
    }

    public record BulkLoadResult(
            int totalFiles,
            int successfulFiles,
            int totalRowsRead,
            int totalOptionsInserted,
            int totalInstrumentsInserted,
            int totalSpotInserted,
            List<FileError> fileErrors
    ) {
        public BulkLoadResult {
            Objects.requireNonNull(fileErrors, "fileErrors must not be null");
            fileErrors = List.copyOf(fileErrors);
        }
    }

    public record FileError(Path file, String reason) {
        public FileError {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
