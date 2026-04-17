package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpotBhavcopyFilterTest {

    private final SpotBhavcopyFilter filter = new SpotBhavcopyFilter();

    @Test
    void acceptsNiftyFutures() {
        BhavcopyCsvReader.CsvRow row = csvRow("FUTIDX", "NIFTY");
        assertTrue(filter.isRelevant(row));
    }

    @Test
    void acceptsBankNiftyFutures() {
        BhavcopyCsvReader.CsvRow row = csvRow("FUTIDX", "BANKNIFTY");
        assertTrue(filter.isRelevant(row));
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

    @Test
    void handlesLowerCaseInput() {
        BhavcopyCsvReader.CsvRow row = csvRow("futidx", "nifty");
        assertTrue(filter.isRelevant(row));
    }

    private BhavcopyCsvReader.CsvRow csvRow(String instrument, String symbol) {
        return new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "INSTRUMENT", instrument,
                "SYMBOL", symbol
        ));
    }
}
