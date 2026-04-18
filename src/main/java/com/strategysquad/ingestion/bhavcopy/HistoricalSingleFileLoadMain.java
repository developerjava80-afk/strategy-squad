package com.strategysquad.ingestion.bhavcopy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runnable local entrypoint for loading a single historical Bhavcopy/UDiFF CSV file.
 */
public class HistoricalSingleFileLoadMain {
    private static final Logger LOGGER = Logger.getLogger(HistoricalSingleFileLoadMain.class.getName());

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java com.strategysquad.ingestion.bhavcopy.HistoricalSingleFileLoadMain <csv-file> [jdbc-url]");
            System.exit(1);
        }

        Path csvFile = Paths.get(args[0]);
        String jdbcUrl = args.length > 1 ? args[1] : DEFAULT_JDBC_URL;
        LOGGER.info("Historical single-file load: csvFile=" + csvFile.toAbsolutePath() + " jdbcUrl=" + jdbcUrl);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD)) {
            BhavcopyIngestionJob.IngestionResult result = new BhavcopyIngestionJob().ingest(csvFile, connection);
            LOGGER.info("Historical single-file load complete:"
                    + " totalRows=" + result.totalRowsRead()
                    + " relevantOptionRows=" + result.relevantOptionRowsRead()
                    + " normalizedOptionRows=" + result.normalizedOptionRows()
                    + " instrumentsInserted=" + result.instrumentsInserted()
                    + " optionsInserted=" + result.optionsHistoricalInserted()
                    + " relevantSpotRows=" + result.relevantSpotRowsRead()
                    + " spotInserted=" + result.spotHistoricalInserted()
                    + " invalidRows=" + result.invalidRows().size());
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Historical single-file load failed", ex);
            System.exit(2);
        }
    }
}