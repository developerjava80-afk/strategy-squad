package com.strategysquad.ingestion.bhavcopy;

import com.strategysquad.enrichment.OptionEnrichedTick;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HistoricalDerivedDiagnosticsMain {
    private static final Logger LOGGER = Logger.getLogger(HistoricalDerivedDiagnosticsMain.class.getName());

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java com.strategysquad.ingestion.bhavcopy.HistoricalDerivedDiagnosticsMain <yyyy-MM-dd> [jdbc-url]");
            System.exit(1);
        }

        LocalDate tradeDate = LocalDate.parse(args[0]);
        String jdbcUrl = args.length > 1 ? args[1] : DEFAULT_JDBC_URL;
        LOGGER.info("Historical derived diagnostics: tradeDate=" + tradeDate + " jdbcUrl=" + jdbcUrl);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD)) {
            List<OptionEnrichedTick> enrichedTicks = HistoricalDerivedSupport.buildEnrichedTicks(connection, tradeDate);
            int sourceRows = 0;
            List<String> failures = new ArrayList<>();
            List<String> samples = new ArrayList<>();

            sourceRows = enrichedTicks.size();
            for (OptionEnrichedTick enrichedTick : enrichedTicks) {
                if (samples.size() < 10) {
                    samples.add(enrichedTick.exchangeTs() + " | " + enrichedTick.instrumentId()
                            + " | minutesToExpiry=" + enrichedTick.minutesToExpiry()
                            + " | bucket=" + enrichedTick.timeBucket15m());
                }
            }

            LOGGER.info("Historical derived diagnostics complete: sourceRows=" + sourceRows
                    + " enrichedRows=" + enrichedTicks.size()
                    + " failedRows=" + failures.size());
            for (String sample : samples) {
                LOGGER.info("Sample enriched row: " + sample);
            }
            for (String failure : failures) {
                LOGGER.warning("Sample failure: " + failure);
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Historical derived diagnostics failed", ex);
            System.exit(2);
        }
    }
}