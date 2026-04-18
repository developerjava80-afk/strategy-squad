package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpotBhavcopyNormalizerTest {

    private final SpotBhavcopyNormalizer normalizer = new SpotBhavcopyNormalizer();

    @Test
    void normalizesValidTrueSpotRow() {
        BhavcopyCsvReader.CsvRow row = validSpotRow();
        SpotBhavcopyRecord record = normalizer.normalize(row);

        assertEquals("NIFTY", record.underlying());
        assertEquals(LocalDate.of(2024, 3, 27), record.tradeDate());
        assertEquals(0, new BigDecimal("22100.5").compareTo(record.open()));
        assertEquals(0, new BigDecimal("22250.0").compareTo(record.high()));
        assertEquals(0, new BigDecimal("22050.0").compareTo(record.low()));
        assertEquals(0, new BigDecimal("22200.0").compareTo(record.close()));
        assertEquals(SpotSource.TRUE_SPOT, record.source());
        assertNull(record.expiryDate());
    }

    @Test
    void normalizesProxyRowWithExpiryDate() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(3, "raw", Map.ofEntries(
                Map.entry("SYMBOL", "NIFTY"),
                Map.entry("TIMESTAMP", "27-Mar-2024"),
                Map.entry("OPEN", "22100.5"),
                Map.entry("HIGH", "22250.0"),
                Map.entry("LOW", "22050.0"),
                Map.entry("CLOSE", "22200.0"),
                Map.entry("EXPIRY_DT", "28-Mar-2024"),
                Map.entry("INSTRUMENT", "FUTIDX")
        ));
        SpotBhavcopyRecord record = normalizer.normalize(row);

        assertEquals(SpotSource.DERIVATIVE_PROXY, record.source());
        assertEquals(LocalDate.of(2024, 3, 28), record.expiryDate());
    }

    @Test
    void normalizesIndexFileShape() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(3, "raw", Map.ofEntries(
                Map.entry("INDEX NAME", "NIFTY BANK"),
                Map.entry("INDEX DATE", "2026-04-16"),
                Map.entry("OPEN INDEX VALUE", "54120.00"),
                Map.entry("HIGH INDEX VALUE", "54400.25"),
                Map.entry("LOW INDEX VALUE", "53780.50"),
                Map.entry("CLOSING INDEX VALUE", "54225.80")
        ));
        SpotBhavcopyRecord record = normalizer.normalize(row);

        assertEquals("BANKNIFTY", record.underlying());
        assertEquals(SpotSource.TRUE_SPOT, record.source());
        assertEquals(LocalDate.of(2026, 4, 16), record.tradeDate());
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
