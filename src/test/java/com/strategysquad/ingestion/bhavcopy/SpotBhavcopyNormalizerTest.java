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
    }

    @Test
    void rejectsMissingColumn() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "SYMBOL", "NIFTY"
        ));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(row));
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
