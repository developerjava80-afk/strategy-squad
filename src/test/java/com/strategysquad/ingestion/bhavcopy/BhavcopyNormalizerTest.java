package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BhavcopyNormalizerTest {

    private final BhavcopyNormalizer normalizer = new BhavcopyNormalizer();

    @Test
    void normalizesValidRow() {
        BhavcopyCsvReader.CsvRow row = validOptionRow();
        BhavcopyRecord record = normalizer.normalize(row);

        assertEquals("NIFTY", record.underlying());
        assertEquals(LocalDate.of(2024, 3, 28), record.expiryDate());
        assertEquals(new BigDecimal("22000"), record.strike());
        assertEquals("CE", record.optionType());
        assertEquals(LocalDate.of(2024, 3, 27), record.tradeDate());
        assertEquals(0, new BigDecimal("100.5").compareTo(record.open()));
        assertEquals(0, new BigDecimal("110.0").compareTo(record.high()));
        assertEquals(0, new BigDecimal("95.0").compareTo(record.low()));
        assertEquals(0, new BigDecimal("105.0").compareTo(record.close()));
        assertEquals(5000, record.contracts());
        assertEquals(100000, record.openInterest());
        assertNull(record.instrumentId());
    }

    @Test
    void rejectsMissingColumn() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "SYMBOL", "NIFTY"
                // missing other required columns
        ));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(row));
    }

    @Test
    void rejectsDashAsMissing() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(1, "raw", Map.ofEntries(
                Map.entry("SYMBOL", "NIFTY"),
                Map.entry("EXPIRY_DT", "28-Mar-2024"),
                Map.entry("STRIKE_PR", "-"),
                Map.entry("OPTION_TYP", "CE"),
                Map.entry("TIMESTAMP", "27-Mar-2024"),
                Map.entry("OPEN", "100"),
                Map.entry("HIGH", "110"),
                Map.entry("LOW", "95"),
                Map.entry("CLOSE", "105"),
                Map.entry("SETTLE_PR", "105"),
                Map.entry("CONTRACTS", "5000"),
                Map.entry("VAL_INLAKH", "50000"),
                Map.entry("OPEN_INT", "100000"),
                Map.entry("CHG_IN_OI", "5000")
        ));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(row));
    }

    @Test
    void parsesIsoDateFormat() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(1, "raw", Map.ofEntries(
                Map.entry("SYMBOL", "NIFTY"),
                Map.entry("EXPIRY_DT", "2024-03-28"),
                Map.entry("STRIKE_PR", "22000"),
                Map.entry("OPTION_TYP", "CE"),
                Map.entry("TIMESTAMP", "2024-03-27"),
                Map.entry("OPEN", "100"),
                Map.entry("HIGH", "110"),
                Map.entry("LOW", "95"),
                Map.entry("CLOSE", "105"),
                Map.entry("SETTLE_PR", "105"),
                Map.entry("CONTRACTS", "5000"),
                Map.entry("VAL_INLAKH", "50000"),
                Map.entry("OPEN_INT", "100000"),
                Map.entry("CHG_IN_OI", "5000")
        ));
        BhavcopyRecord record = normalizer.normalize(row);
        assertEquals(LocalDate.of(2024, 3, 28), record.expiryDate());
    }

    @Test
    void normalizesUdiffRow() {
        BhavcopyCsvReader.CsvRow row = udiffOptionRow();
        BhavcopyRecord record = normalizer.normalize(row);

        assertEquals("BANKNIFTY", record.underlying());
        assertEquals(LocalDate.of(2026, 4, 28), record.expiryDate());
        assertEquals(new BigDecimal("61900.00"), record.strike());
        assertEquals("CE", record.optionType());
        assertEquals(LocalDate.of(2026, 4, 1), record.tradeDate());
        assertEquals(0, new BigDecimal("20.10").compareTo(record.open()));
        assertEquals(0, new BigDecimal("20.10").compareTo(record.high()));
        assertEquals(0, new BigDecimal("11.85").compareTo(record.low()));
        assertEquals(0, new BigDecimal("12.45").compareTo(record.close()));
        assertEquals(0, new BigDecimal("12.45").compareTo(record.settlePrice()));
        assertEquals(358, record.contracts());
        // TtlTrfVal 664973079.00 / 100000 = 6649.73
        assertEquals(0, new BigDecimal("6649.73").compareTo(record.valueInLakhs()));
        assertEquals(4560, record.openInterest());
        assertEquals(2940, record.changeInOi());
        assertNull(record.instrumentId());
        assertEquals("67797", record.exchangeToken());
        assertEquals("BANKNIFTY26APR61900CE", record.tradingSymbol());
        assertEquals(Integer.valueOf(30), record.lotSize());
    }

    private BhavcopyCsvReader.CsvRow validOptionRow() {
        return new BhavcopyCsvReader.CsvRow(2, "raw", Map.ofEntries(
                Map.entry("SYMBOL", "NIFTY"),
                Map.entry("EXPIRY_DT", "28-Mar-2024"),
                Map.entry("STRIKE_PR", "22000"),
                Map.entry("OPTION_TYP", "CE"),
                Map.entry("TIMESTAMP", "27-Mar-2024"),
                Map.entry("OPEN", "100.5"),
                Map.entry("HIGH", "110.0"),
                Map.entry("LOW", "95.0"),
                Map.entry("CLOSE", "105.0"),
                Map.entry("SETTLE_PR", "105.0"),
                Map.entry("CONTRACTS", "5000"),
                Map.entry("VAL_INLAKH", "50000.00"),
                Map.entry("OPEN_INT", "100000"),
                Map.entry("CHG_IN_OI", "5000")
        ));
    }

    private BhavcopyCsvReader.CsvRow udiffOptionRow() {
        return new BhavcopyCsvReader.CsvRow(2, "raw", Map.ofEntries(
                Map.entry("TRADDT", "2026-04-01"),
                Map.entry("FININSTRMTP", "IDO"),
                Map.entry("FININSTRMID", "67797"),
                Map.entry("TCKRSYMB", "BANKNIFTY"),
                Map.entry("XPRYDT", "2026-04-28"),
                Map.entry("STRKPRIC", "61900.00"),
                Map.entry("OPTNTP", "CE"),
                Map.entry("FININSTRNM", "BANKNIFTY26APR61900CE"),
                Map.entry("OPNPRIC", "20.10"),
                Map.entry("HGHPRIC", "20.10"),
                Map.entry("LWPRIC", "11.85"),
                Map.entry("CLSPRIC", "12.45"),
                Map.entry("STTLMPRIC", "12.45"),
                Map.entry("TTLTRADGVOL", "358"),
                Map.entry("TTLTRFVAL", "664973079.00"),
                Map.entry("OPNINTRST", "4560"),
                Map.entry("CHNGINOPNINTRST", "2940"),
                Map.entry("NEWBRDLOTQTY", "30")
        ));
    }
}
