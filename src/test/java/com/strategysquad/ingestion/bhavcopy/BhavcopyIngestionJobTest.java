package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BhavcopyIngestionJobTest {

    @Test
    void ingestsOptionsAndSpotAndCollectsInvalidRows() throws Exception {
        InvalidRow malformedCsvRow = new InvalidRow(1, "column count mismatch", "bad,row");
        BhavcopyCsvReader.ReadResult readResult = new BhavcopyCsvReader.ReadResult(
                List.of(
                        optionRow(2, "110.50"),
                        optionRow(3, "-"),
                        spotRow(4)
                ),
                List.of(malformedCsvRow),
                3
        );
        TrackingBhavcopyWriter writer = new TrackingBhavcopyWriter(new BhavcopyWriter.WriteResult(1, 1));
        TrackingSpotBhavcopyWriter spotWriter = new TrackingSpotBhavcopyWriter(1);
        BhavcopyIngestionJob job = new BhavcopyIngestionJob(
                new StubBhavcopyCsvReader(readResult),
                new BhavcopyFilter(),
                new BhavcopyNormalizer(),
                new InstrumentResolver(),
                writer,
                new SpotBhavcopyFilter(),
                new SpotBhavcopyNormalizer(),
                spotWriter
        );

        BhavcopyIngestionJob.IngestionResult result = job.ingest(
                Path.of("/tmp/sample-bhavcopy.csv"),
                new BhavcopyJdbcTestSupport.ConnectionRecorder(true).proxy()
        );

        assertEquals(new BhavcopyIngestionJob.IngestionResult(3, 2, 1, result.invalidRows(), 1, 1, 1, 1), result);
        assertEquals(List.of(malformedCsvRow, new InvalidRow(3, "OPEN is missing", "option-3")), result.invalidRows());
        assertEquals(1, writer.records.size());
        BhavcopyRecord optionRecord = writer.records.get(0);
        assertEquals("NIFTY", optionRecord.underlying());
        assertEquals(
                new InstrumentResolver().resolveInstrumentId(optionRecord.instrumentKey()),
                optionRecord.instrumentId()
        );
        assertEquals(List.of(new SpotBhavcopyRecord(
                4,
                "NIFTY",
                LocalDate.of(2024, 4, 17),
                new BigDecimal("22450.00"),
                new BigDecimal("22610.50"),
                new BigDecimal("22390.25"),
                new BigDecimal("22595.75")
        )), spotWriter.records);
    }

    private static BhavcopyCsvReader.CsvRow optionRow(long lineNumber, String open) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("INSTRUMENT", "OPTIDX");
        columns.put("SYMBOL", "NIFTY");
        columns.put("EXPIRY_DT", "18-APR-2024");
        columns.put("STRIKE_PR", "22500");
        columns.put("OPTION_TYP", "CE");
        columns.put("TIMESTAMP", "17-APR-2024");
        columns.put("OPEN", open);
        columns.put("HIGH", "125.00");
        columns.put("LOW", "105.25");
        columns.put("CLOSE", "120.75");
        columns.put("SETTLE_PR", "119.50");
        columns.put("CONTRACTS", "150");
        columns.put("VAL_INLAKH", "1234.56");
        columns.put("OPEN_INT", "2000");
        columns.put("CHG_IN_OI", "250");
        return new BhavcopyCsvReader.CsvRow(lineNumber, "option-" + lineNumber, columns);
    }

    private static BhavcopyCsvReader.CsvRow spotRow(long lineNumber) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("INSTRUMENT", "FUTIDX");
        columns.put("SYMBOL", "NIFTY");
        columns.put("TIMESTAMP", "17-APR-2024");
        columns.put("OPEN", "22450.00");
        columns.put("HIGH", "22610.50");
        columns.put("LOW", "22390.25");
        columns.put("CLOSE", "22595.75");
        return new BhavcopyCsvReader.CsvRow(lineNumber, "spot-" + lineNumber, columns);
    }

    private static final class StubBhavcopyCsvReader extends BhavcopyCsvReader {
        private final ReadResult readResult;

        private StubBhavcopyCsvReader(ReadResult readResult) {
            this.readResult = readResult;
        }

        @Override
        public ReadResult read(Path csvFile) {
            return readResult;
        }
    }

    private static final class TrackingBhavcopyWriter extends BhavcopyWriter {
        private final WriteResult writeResult;
        private List<BhavcopyRecord> records = List.of();

        private TrackingBhavcopyWriter(WriteResult writeResult) {
            this.writeResult = writeResult;
        }

        @Override
        public WriteResult write(Connection connection, List<BhavcopyRecord> records) {
            this.records = List.copyOf(records);
            return writeResult;
        }
    }

    private static final class TrackingSpotBhavcopyWriter extends SpotBhavcopyWriter {
        private final int inserted;
        private List<SpotBhavcopyRecord> records = List.of();

        private TrackingSpotBhavcopyWriter(int inserted) {
            this.inserted = inserted;
        }

        @Override
        public int write(Connection connection, List<SpotBhavcopyRecord> records) {
            this.records = List.copyOf(records);
            return inserted;
        }
    }
}
