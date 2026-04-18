package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BhavcopyFilterTest {

    private final BhavcopyFilter filter = new BhavcopyFilter();

    @Test
    void acceptsNiftyCallOption() {
        BhavcopyCsvReader.CsvRow row = csvRow("OPTIDX", "NIFTY", "CE");
        assertTrue(filter.isRelevant(row));
    }

    @Test
    void acceptsBankNiftyPutOption() {
        BhavcopyCsvReader.CsvRow row = csvRow("OPTIDX", "BANKNIFTY", "PE");
        assertTrue(filter.isRelevant(row));
    }

    @Test
    void rejectsFuturesRow() {
        BhavcopyCsvReader.CsvRow row = csvRow("FUTIDX", "NIFTY", "XX");
        assertFalse(filter.isRelevant(row));
    }

    @Test
    void rejectsOtherSymbol() {
        BhavcopyCsvReader.CsvRow row = csvRow("OPTIDX", "RELIANCE", "CE");
        assertFalse(filter.isRelevant(row));
    }

    @Test
    void rejectsNonOptionType() {
        BhavcopyCsvReader.CsvRow row = csvRow("OPTIDX", "NIFTY", "XX");
        assertFalse(filter.isRelevant(row));
    }

    @Test
    void handlesLowerCaseInput() {
        BhavcopyCsvReader.CsvRow row = csvRow("optidx", "nifty", "ce");
        assertTrue(filter.isRelevant(row));
    }

    @Test
    void acceptsUdiffNiftyCallOption() {
        BhavcopyCsvReader.CsvRow row = udiffRow("IDO", "NIFTY", "CE");
        assertTrue(filter.isRelevant(row));
    }

    @Test
    void acceptsUdiffBankNiftyPutOption() {
        BhavcopyCsvReader.CsvRow row = udiffRow("IDO", "BANKNIFTY", "PE");
        assertTrue(filter.isRelevant(row));
    }

    @Test
    void rejectsUdiffFuturesRow() {
        BhavcopyCsvReader.CsvRow row = udiffRow("IDF", "NIFTY", "");
        assertFalse(filter.isRelevant(row));
    }

    @Test
    void rejectsUdiffOtherSymbol() {
        BhavcopyCsvReader.CsvRow row = udiffRow("IDO", "RELIANCE", "CE");
        assertFalse(filter.isRelevant(row));
    }

    private BhavcopyCsvReader.CsvRow csvRow(String instrument, String symbol, String optionType) {
        return new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "INSTRUMENT", instrument,
                "SYMBOL", symbol,
                "OPTION_TYP", optionType
        ));
    }

    private BhavcopyCsvReader.CsvRow udiffRow(String finInstrmTp, String tckrSymb, String optnTp) {
        return new BhavcopyCsvReader.CsvRow(1, "raw", Map.of(
                "FININSTRMTP", finInstrmTp,
                "TCKRSYMB", tckrSymb,
                "OPTNTP", optnTp
        ));
    }
}
