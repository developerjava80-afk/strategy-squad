package com.strategysquad.ingestion.bhavcopy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runnable local entrypoint for historical DB loading from Bhavcopy/UDiFF CSV files.
 *
 * <p>Usage: {@code java com.strategysquad.ingestion.bhavcopy.HistoricalLoadMain [directory] [jdbc-url]}
 * <ul>
 *   <li>{@code directory} — path to folder containing CSV files
 *       (default: {@code data/bhavcopy/historical/derivatives})</li>
 *   <li>{@code jdbc-url} — JDBC connection URL
 *       (default: {@code jdbc:postgresql://localhost:8812/qdb})</li>
 * </ul>
 *
 * <p>Connects to QuestDB via the PostgreSQL wire protocol and bulk-loads
 * every {@code .csv} file in the directory into {@code instrument_master},
 * {@code options_historical}, and {@code spot_historical}.
 */
public class HistoricalLoadMain {
    private static final Logger LOGGER = Logger.getLogger(HistoricalLoadMain.class.getName());

    private static final String DEFAULT_DIR = "data/bhavcopy/historical/derivatives";
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : DEFAULT_DIR;
        String jdbcUrl = args.length > 1 ? args[1] : DEFAULT_JDBC_URL;

        Path directory = Paths.get(dir);
        LOGGER.info("Historical load: directory=" + directory.toAbsolutePath() + " jdbcUrl=" + jdbcUrl);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD)) {
            BhavcopyBulkLoadJob bulkJob = new BhavcopyBulkLoadJob();
            BhavcopyBulkLoadJob.BulkLoadResult result = bulkJob.loadDirectory(directory, connection);

            LOGGER.info("Historical load complete:"
                    + " files=" + result.successfulFiles() + "/" + result.totalFiles()
                    + " rows=" + result.totalRowsRead()
                    + " optionsInserted=" + result.totalOptionsInserted()
                    + " instrumentsInserted=" + result.totalInstrumentsInserted()
                    + " spotInserted=" + result.totalSpotInserted()
                    + " errors=" + result.fileErrors().size());

            for (BhavcopyBulkLoadJob.FileError error : result.fileErrors()) {
                LOGGER.warning("Failed: " + error.file().getFileName() + " — " + error.reason());
            }

            if (!result.fileErrors().isEmpty()) {
                System.exit(1);
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Historical load failed", ex);
            System.exit(2);
        }
    }
}
