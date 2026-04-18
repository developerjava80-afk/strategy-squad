package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpotBhavcopyNormalizerTest {

    private final SpotBhavcopyNormalizer normalizer = new SpotBhavcopyNormalizer();

    @Test
    void normalizesValidSpotRow() {
        BhavcopyCsvReader.CsvRow row = validSpotRow();
        SpotBhavcopyRecord record = normalizer.normalize(row);

        assertEquals("NIFTY", record.underlying());
        assertEquals(LocalDate.of(2024, 3, 27), record.tradeDate());
        assertEquals(0, new BigDecimal("22100.5").compareTo(record.open()));
        assertEquals(0, new BigDecimal("22250.0").compareTo(record.high()));
        assertEquals(0, new BigDecimal("22050.0").compareTo(record.low()));
        assertEquals(0, new BigDecimal("22200.0").compareTo(record.close()));
        assertNull(record.expiryDate());
    }

    @Test
    void normalizesSpotRowWithExpiryDate() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(3, "raw", Map.ofEntries(
                Map.entry("SYMBOL", "NIFTY"),
                Map.entry("TIMESTAMP", "27-Mar-2024"),
                Map.entry("OPEN", "22100.5"),
                Map.entry("HIGH", "22250.0"),
                Map.entry("LOW", "22050.0"),
                Map.entry("CLOSE", "22200.0"),
                Map.entry("EXPIRY_DT", "28-Mar-2024")
        ));
        SpotBhavcopyRecord record = normalizer.normalize(row);

        assertEquals(LocalDate.of(2024, 3, 28), record.expiryDate());
    }

    @Test
    void rejectsMissingColumn() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "SYMBOL", "NIFTY"
        ));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(row));
    }

    @Test
    void normalizesUdiffSpotRow() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(3, "raw", Map.ofEntries(
                Map.entry("TCKRSYMB", "NIFTY"),
                Map.entry("TRADDT", "2026-04-16"),
                Map.entry("OPNPRIC", "24342.00"),
                Map.entry("HGHPRIC", "24390.00"),
                Map.entry("LWPRIC", "24114.00"),
                Map.entry("CLSPRIC", "24195.80"),
                Map.entry("XPRYDT", "2026-04-28"),
                Map.entry("FININSTRMTP", "IDF")
        ));
        SpotBhavcopyRecord record = normalizer.normalize(row);

        assertEquals("NIFTY", record.underlying());
        assertEquals(LocalDate.of(2026, 4, 16), record.tradeDate());
        assertEquals(0, new BigDecimal("24342.00").compareTo(record.open()));
        assertEquals(0, new BigDecimal("24390.00").compareTo(record.high()));
        assertEquals(0, new BigDecimal("24114.00").compareTo(record.low()));
        assertEquals(0, new BigDecimal("24195.80").compareTo(record.close()));
        assertEquals(LocalDate.of(2026, 4, 28), record.expiryDate());
    }

    private BhavcopyCsvReader.CsvRow validSpotRow() {
        return new BhavcopyCsvReader.CsvRow(2, "raw", Map.ofEntries(
                Map.entry("SYMBOL", "NIFTY"),
                Map.entry("TIMESTAMP", "27-Mar-2024"),
                Map.entry("OPEN", "22100.5"),
                Map.entry("HIGH", "22250.0"),
                Map.entry("LOW", "22050.0"),
                Map.entry("CLOSE", "22200.0")
        ));
    }
}
