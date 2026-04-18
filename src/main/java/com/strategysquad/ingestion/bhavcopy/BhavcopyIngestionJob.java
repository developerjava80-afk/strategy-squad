package com.strategysquad.ingestion.bhavcopy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Single-file Bhavcopy ingestion job for Golden Source loading.
 *
 * <p>Processes both option and spot (index) rows from a single Bhavcopy CSV
 * and writes them to {@code instrument_master}, {@code options_historical},
 * and {@code spot_historical} respectively.
 */
public class BhavcopyIngestionJob {
    private static final Logger LOGGER = Logger.getLogger(BhavcopyIngestionJob.class.getName());

    private final BhavcopyCsvReader csvReader;
    private final BhavcopyFilter filter;
    private final BhavcopyNormalizer normalizer;
    private final InstrumentResolver instrumentResolver;
    private final BhavcopyWriter writer;
    private final SpotBhavcopyFilter spotFilter;
    private final SpotBhavcopyNormalizer spotNormalizer;
    private final SpotBhavcopyWriter spotWriter;

    public BhavcopyIngestionJob() {
        this(new BhavcopyCsvReader(), new BhavcopyFilter(), new BhavcopyNormalizer(),
                new InstrumentResolver(), new BhavcopyWriter(),
                new SpotBhavcopyFilter(), new SpotBhavcopyNormalizer(), new SpotBhavcopyWriter());
    }

    public BhavcopyIngestionJob(
            BhavcopyCsvReader csvReader,
            BhavcopyFilter filter,
            BhavcopyNormalizer normalizer,
            InstrumentResolver instrumentResolver,
            BhavcopyWriter writer,
            SpotBhavcopyFilter spotFilter,
            SpotBhavcopyNormalizer spotNormalizer,
            SpotBhavcopyWriter spotWriter
    ) {
        this.csvReader = Objects.requireNonNull(csvReader, "csvReader must not be null");
        this.filter = Objects.requireNonNull(filter, "filter must not be null");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.instrumentResolver = Objects.requireNonNull(instrumentResolver, "instrumentResolver must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.spotFilter = Objects.requireNonNull(spotFilter, "spotFilter must not be null");
        this.spotNormalizer = Objects.requireNonNull(spotNormalizer, "spotNormalizer must not be null");
        this.spotWriter = Objects.requireNonNull(spotWriter, "spotWriter must not be null");
    }

    public IngestionResult ingest(Path csvFile, Connection connection) throws IOException, SQLException {
        Objects.requireNonNull(csvFile, "csvFile must not be null");
        Objects.requireNonNull(connection, "connection must not be null");

        BhavcopyCsvReader.ReadResult readResult = csvReader.read(csvFile);
        List<InvalidRow> invalidRows = new ArrayList<>(readResult.invalidRows());
        List<BhavcopyRecord> normalized = new ArrayList<>();
        List<SpotBhavcopyRecord> spotRecords = new ArrayList<>();

        int relevantOptionRows = 0;
        int relevantSpotRows = 0;
        for (BhavcopyCsvReader.CsvRow row : readResult.rows()) {
            if (filter.isRelevant(row)) {
                relevantOptionRows++;
                try {
                    BhavcopyRecord record = normalizer.normalize(row);
                    String instrumentId = instrumentResolver.resolveInstrumentId(record.instrumentKey());
                    normalized.add(record.withInstrumentId(instrumentId));
                } catch (RuntimeException ex) {
                    invalidRows.add(new InvalidRow(row.lineNumber(), ex.getMessage(), row.rawLine()));
                }
            }
            if (spotFilter.isRelevant(row)) {
                relevantSpotRows++;
                try {
                    spotRecords.add(spotNormalizer.normalize(row));
                } catch (RuntimeException ex) {
                    invalidRows.add(new InvalidRow(row.lineNumber(), ex.getMessage(), row.rawLine()));
                }
            }
        }

        boolean autoCommit = connection.getAutoCommit();
        if (autoCommit) {
            connection.setAutoCommit(false);
        }
        try {
            BhavcopyWriter.WriteResult writeResult = writer.write(connection, normalized);
            List<SpotBhavcopyRecord> dedupedSpot = deduplicatePreferredSpot(spotRecords);
            int spotInserted = spotWriter.write(connection, dedupedSpot);
            if (autoCommit) {
                connection.commit();
            }
            logSummary(csvFile, readResult.totalDataRows(), relevantOptionRows, relevantSpotRows,
                    normalized.size(), dedupedSpot.size(), invalidRows, writeResult, spotInserted);

            return new IngestionResult(
                    readResult.totalDataRows(),
                    relevantOptionRows,
                    normalized.size(),
                    invalidRows,
                    writeResult.instrumentsInserted(),
                    writeResult.optionsHistoricalInserted(),
                    relevantSpotRows,
                    spotInserted
            );
        } catch (SQLException ex) {
            if (autoCommit) {
                connection.rollback();
            }
            throw ex;
        } finally {
            if (autoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    static List<SpotBhavcopyRecord> deduplicatePreferredSpot(List<SpotBhavcopyRecord> records) {
        if (records.size() <= 1) {
            return records;
        }
        Map<String, SpotBhavcopyRecord> best = new LinkedHashMap<>();

        for (SpotBhavcopyRecord record : records) {
            String key = record.underlying() + "|" + record.tradeDate();
            SpotBhavcopyRecord existing = best.get(key);
            if (existing == null) {
                best.put(key, record);
            } else {
                best.put(key, pickPreferred(record, existing));
            }
        }
        return new ArrayList<>(best.values());
    }

    private static SpotBhavcopyRecord pickPreferred(SpotBhavcopyRecord a, SpotBhavcopyRecord b) {
        if (a.source() != b.source()) {
            return a.source() == SpotSource.TRUE_SPOT ? a : b;
        }
        if (a.source() == SpotSource.TRUE_SPOT) {
            return a.lineNumber() <= b.lineNumber() ? a : b;
        }
        return pickNearestProxy(a, b);
    }

    private static SpotBhavcopyRecord pickNearestProxy(SpotBhavcopyRecord a, SpotBhavcopyRecord b) {
        LocalDate trade = a.tradeDate();
        boolean aValid = a.expiryDate() != null && !a.expiryDate().isBefore(trade);
        boolean bValid = b.expiryDate() != null && !b.expiryDate().isBefore(trade);
        if (aValid && !bValid) {
            return a;
        }
        if (!aValid && bValid) {
            return b;
        }
        if (a.expiryDate() == null) {
            return b;
        }
        if (b.expiryDate() == null) {
            return a;
        }
        return a.expiryDate().compareTo(b.expiryDate()) <= 0 ? a : b;
    }

    private void logSummary(
            Path csvFile,
            int totalRows,
            int relevantOptionRows,
            int relevantSpotRows,
            int normalizedOptionRows,
            int normalizedSpotRows,
            List<InvalidRow> invalidRows,
            BhavcopyWriter.WriteResult writeResult,
            int spotInserted
    ) {
        LOGGER.info(() -> "Bhavcopy ingestion completed for " + csvFile
                + " totalRows=" + totalRows
                + " relevantOptionRows=" + relevantOptionRows
                + " normalizedOptionRows=" + normalizedOptionRows
                + " relevantSpotRows=" + relevantSpotRows
                + " normalizedSpotRows=" + normalizedSpotRows
                + " invalidRows=" + invalidRows.size()
                + " instrumentsInserted=" + writeResult.instrumentsInserted()
                + " optionsHistoricalInserted=" + writeResult.optionsHistoricalInserted()
                + " spotHistoricalInserted=" + spotInserted);
        for (InvalidRow invalidRow : invalidRows) {
            LOGGER.warning(() -> "Rejected Bhavcopy row line=" + invalidRow.lineNumber()
                    + " reason=" + invalidRow.reason()
                    + " rawData=" + invalidRow.rawData());
        }
    }

    public record IngestionResult(
            int totalRowsRead,
            int relevantOptionRowsRead,
            int normalizedOptionRows,
            List<InvalidRow> invalidRows,
            int instrumentsInserted,
            int optionsHistoricalInserted,
            int relevantSpotRowsRead,
            int spotHistoricalInserted
    ) {
        public IngestionResult {
            Objects.requireNonNull(invalidRows, "invalidRows must not be null");
            invalidRows = List.copyOf(invalidRows);
        }
    }
}
