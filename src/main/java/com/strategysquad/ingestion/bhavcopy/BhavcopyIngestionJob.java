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
 * Single-file Bhavcopy ingestion job for v1 Golden Source loading.
 */
public class BhavcopyIngestionJob {
    private static final Logger LOGGER = Logger.getLogger(BhavcopyIngestionJob.class.getName());

    private final BhavcopyCsvReader csvReader;
    private final BhavcopyFilter filter;
    private final BhavcopyNormalizer normalizer;
    private final InstrumentResolver instrumentResolver;
    private final BhavcopyWriter writer;

    public BhavcopyIngestionJob() {
        this(new BhavcopyCsvReader(), new BhavcopyFilter(), new BhavcopyNormalizer(), new InstrumentResolver(), new BhavcopyWriter());
    }

    public BhavcopyIngestionJob(
            BhavcopyCsvReader csvReader,
            BhavcopyFilter filter,
            BhavcopyNormalizer normalizer,
            InstrumentResolver instrumentResolver,
            BhavcopyWriter writer
    ) {
        this.csvReader = Objects.requireNonNull(csvReader, "csvReader must not be null");
        this.filter = Objects.requireNonNull(filter, "filter must not be null");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.instrumentResolver = Objects.requireNonNull(instrumentResolver, "instrumentResolver must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
    }

    public IngestionResult ingest(Path csvFile, Connection connection) throws IOException, SQLException {
        Objects.requireNonNull(csvFile, "csvFile must not be null");
        Objects.requireNonNull(connection, "connection must not be null");

        BhavcopyCsvReader.ReadResult readResult = csvReader.read(csvFile);
        List<InvalidRow> invalidRows = new ArrayList<>(readResult.invalidRows());
        List<BhavcopyRecord> normalized = new ArrayList<>();

        int relevantRows = 0;
        for (BhavcopyCsvReader.CsvRow row : readResult.rows()) {
            if (!filter.isRelevant(row)) {
                continue;
            }
            relevantRows++;
            try {
                BhavcopyRecord record = normalizer.normalize(row);
                String instrumentId = instrumentResolver.resolveInstrumentId(record.instrumentKey());
                normalized.add(record.withInstrumentId(instrumentId));
            } catch (RuntimeException ex) {
                invalidRows.add(new InvalidRow(row.lineNumber(), ex.getMessage(), row.rawLine()));
            }
        }

        BhavcopyWriter.WriteResult writeResult = writer.write(connection, normalized);
        logSummary(csvFile, readResult.totalDataRows(), relevantRows, normalized.size(), invalidRows, writeResult);

        return new IngestionResult(
                readResult.totalDataRows(),
                relevantRows,
                normalized.size(),
                invalidRows,
                writeResult.instrumentsInserted(),
                writeResult.optionsHistoricalInserted()
        );
    }

    private void logSummary(
            Path csvFile,
            int totalRows,
            int relevantRows,
            int normalizedRows,
            List<InvalidRow> invalidRows,
            BhavcopyWriter.WriteResult writeResult
    ) {
        LOGGER.info(() -> "Bhavcopy ingestion completed for " + csvFile
                + " totalRows=" + totalRows
                + " relevantRows=" + relevantRows
                + " normalizedRows=" + normalizedRows
                + " invalidRows=" + invalidRows.size()
                + " instrumentsInserted=" + writeResult.instrumentsInserted()
                + " optionsHistoricalInserted=" + writeResult.optionsHistoricalInserted());
        for (InvalidRow invalidRow : invalidRows) {
            LOGGER.warning(() -> "Rejected Bhavcopy row line=" + invalidRow.lineNumber()
                    + " reason=" + invalidRow.reason()
                    + " rawData=" + invalidRow.rawData());
        }
    }

    public record IngestionResult(
            int totalRowsRead,
            int relevantRowsRead,
            int normalizedRows,
            List<InvalidRow> invalidRows,
            int instrumentsInserted,
            int optionsHistoricalInserted
    ) {
        public IngestionResult {
            Objects.requireNonNull(invalidRows, "invalidRows must not be null");
            invalidRows = List.copyOf(invalidRows);
        }
    }
}
