package com.strategysquad.ingestion.bhavcopy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
            // Options path
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
            // Spot path
            if (spotFilter.isRelevant(row)) {
                relevantSpotRows++;
                try {
                    spotRecords.add(spotNormalizer.normalize(row));
                } catch (RuntimeException ex) {
                    invalidRows.add(new InvalidRow(row.lineNumber(), ex.getMessage(), row.rawLine()));
                }
            }
        }

        BhavcopyWriter.WriteResult writeResult = writer.write(connection, normalized);
        int spotInserted = spotWriter.write(connection, spotRecords);
        logSummary(csvFile, readResult.totalDataRows(), relevantOptionRows, relevantSpotRows,
                normalized.size(), spotRecords.size(), invalidRows, writeResult, spotInserted);

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
