package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BhavcopyCsvReaderTest {

    private final BhavcopyCsvReader reader = new BhavcopyCsvReader();

    @Test
    void readValidCsv(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("test.csv");
        Files.writeString(csv,
                "INSTRUMENT,SYMBOL,EXPIRY_DT,STRIKE_PR,OPTION_TYP,OPEN,HIGH,LOW,CLOSE,SETTLE_PR,CONTRACTS,VAL_INLAKH,OPEN_INT,CHG_IN_OI,TIMESTAMP\n"
                        + "OPTIDX,NIFTY,28-Mar-2024,22000,CE,100.5,110.0,95.0,105.0,105.0,5000,50000.00,100000,5000,27-Mar-2024\n");

        BhavcopyCsvReader.ReadResult result = reader.read(csv);

        assertEquals(1, result.totalDataRows());
        assertEquals(1, result.rows().size());
        assertEquals(0, result.invalidRows().size());
        assertEquals("OPTIDX", result.rows().get(0).column("INSTRUMENT"));
        assertEquals("NIFTY", result.rows().get(0).column("SYMBOL"));
    }

    @Test
    void readEmptyFile(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("empty.csv");
        Files.writeString(csv, "");

        BhavcopyCsvReader.ReadResult result = reader.read(csv);

        assertEquals(0, result.totalDataRows());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void readColumnCountMismatch(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("bad.csv");
        Files.writeString(csv,
                "A,B,C\n"
                        + "1,2\n");

        BhavcopyCsvReader.ReadResult result = reader.read(csv);

        assertEquals(1, result.totalDataRows());
        assertEquals(0, result.rows().size());
        assertEquals(1, result.invalidRows().size());
        assertTrue(result.invalidRows().get(0).reason().contains("column count mismatch"));
    }

    @Test
    void readSkipsBlankLines(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("blanks.csv");
        Files.writeString(csv,
                "A,B\n"
                        + "\n"
                        + "1,2\n"
                        + "\n");

        BhavcopyCsvReader.ReadResult result = reader.read(csv);

        assertEquals(1, result.totalDataRows());
        assertEquals(1, result.rows().size());
    }
}
