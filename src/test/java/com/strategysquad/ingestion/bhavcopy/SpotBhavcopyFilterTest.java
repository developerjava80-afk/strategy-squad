package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotBhavcopyFilterTest {

    private final SpotBhavcopyFilter filter = new SpotBhavcopyFilter();

    @Test
    void acceptsNiftyFuturesAsProxy() {
        BhavcopyCsvReader.CsvRow row = csvRow("FUTIDX", "NIFTY");
        assertTrue(filter.isRelevant(row));
        assertEquals(SpotSource.DERIVATIVE_PROXY, filter.classify(row));
    }

    @Test
    void acceptsUdiffFuturesAsProxy() {
        BhavcopyCsvReader.CsvRow row = udiffRow("IDF", "BANKNIFTY");
        assertTrue(filter.isRelevant(row));
        assertEquals(SpotSource.DERIVATIVE_PROXY, filter.classify(row));
    }

    @Test
    void acceptsIndexRowsAsTrueSpot() {
        BhavcopyCsvReader.CsvRow row = new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "INDEX NAME", "NIFTY 50",
                "INDEX DATE", "2026-04-16",
                "OPEN INDEX VALUE", "24342.00"
        ));

        assertTrue(filter.isRelevant(row));
        assertEquals(SpotSource.TRUE_SPOT, filter.classify(row));
        assertEquals("NIFTY", filter.resolveUnderlying(row));
    }

    @Test
    void rejectsOptions() {
        BhavcopyCsvReader.CsvRow row = csvRow("OPTIDX", "NIFTY");
        assertFalse(filter.isRelevant(row));
    }

    @Test
    void rejectsOtherSymbol() {
        BhavcopyCsvReader.CsvRow row = csvRow("FUTIDX", "RELIANCE");
        assertFalse(filter.isRelevant(row));
    }

    private BhavcopyCsvReader.CsvRow csvRow(String instrument, String symbol) {
        return new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "INSTRUMENT", instrument,
                "SYMBOL", symbol
        ));
    }

    private BhavcopyCsvReader.CsvRow udiffRow(String finInstrmTp, String tckrSymb) {
        return new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "FININSTRMTP", finInstrmTp,
                "TCKRSYMB", tckrSymb
        ));
    }
}
