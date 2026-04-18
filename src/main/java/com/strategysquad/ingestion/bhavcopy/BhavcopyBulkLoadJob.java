package com.strategysquad.ingestion.bhavcopy;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bulk-loads a directory of Bhavcopy CSV files into the historical tables.
 *
 * <p>Designed for loading 2+ years of historical data. Files are sorted by name
 * (Bhavcopy files are typically named by date, e.g. {@code fo01APR2024bhav.csv})
 * and ingested in order so that QuestDB partitions are written sequentially.
 *
 * <p>Each file is processed independently via {@link BhavcopyIngestionJob}.
 * Failures on individual files are recorded but do not abort the batch.
 */
public class BhavcopyBulkLoadJob {
    private static final Logger LOGGER = Logger.getLogger(BhavcopyBulkLoadJob.class.getName());

    private final BhavcopyIngestionJob singleFileJob;

    public BhavcopyBulkLoadJob() {
        this(new BhavcopyIngestionJob());
    }

    public BhavcopyBulkLoadJob(BhavcopyIngestionJob singleFileJob) {
        this.singleFileJob = Objects.requireNonNull(singleFileJob, "singleFileJob must not be null");
    }

    /**
     * Loads all {@code .csv} files from the given directory.
     *
     * @param directory  path to directory containing Bhavcopy CSV files
     * @param connection JDBC connection (caller manages lifecycle)
     * @return aggregated result across all files
     */
    public BulkLoadResult loadDirectory(Path directory, Connection connection) throws IOException, SQLException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(connection, "connection must not be null");
        if (!Files.isDirectory(directory)) {
            throw new IOException("Not a directory: " + directory);
        }

        List<Path> csvFiles = collectCsvFiles(directory);
        csvFiles.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));

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
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.csv")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    files.add(entry);
                }
            }
        }
        return files;
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
