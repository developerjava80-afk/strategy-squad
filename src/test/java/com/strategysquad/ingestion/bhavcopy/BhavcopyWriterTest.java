package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BhavcopyWriterTest {

    @Test
    void deriveExpiryTypeMonthly() {
        // Last Thursday of March 2024 = 28 Mar 2024
        assertEquals("MONTHLY", BhavcopyWriter.deriveExpiryType(LocalDate.of(2024, 3, 28)));
    }

    @Test
    void deriveExpiryTypeWeekly() {
        // 21 Mar 2024 is a Thursday but not the last Thursday of March
        assertEquals("WEEKLY", BhavcopyWriter.deriveExpiryType(LocalDate.of(2024, 3, 21)));
    }

    @Test
    void deriveExpiryTypeLastThursdayDecember2024() {
        // Last Thursday of December 2024 = 26 Dec 2024
        assertEquals("MONTHLY", BhavcopyWriter.deriveExpiryType(LocalDate.of(2024, 12, 26)));
    }

    @Test
    void deriveExpiryTypeNonThursday() {
        // A non-Thursday is always weekly
        assertEquals("WEEKLY", BhavcopyWriter.deriveExpiryType(LocalDate.of(2024, 3, 25)));
    }

    @Test
    void writesCanonicalInstrumentAndHistoricalColumns() throws Exception {
        BhavcopyJdbcTestSupport.QueryStatementRecorder queryStatement =
                new BhavcopyJdbcTestSupport.QueryStatementRecorder(List.of());
        BhavcopyJdbcTestSupport.PreparedStatementRecorder instrumentStatement =
                new BhavcopyJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        BhavcopyJdbcTestSupport.PreparedStatementRecorder optionsStatement =
                new BhavcopyJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        BhavcopyJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new BhavcopyJdbcTestSupport.ConnectionRecorder(true,
                        queryStatement.proxy(), instrumentStatement.proxy(), optionsStatement.proxy());

        BhavcopyRecord record = new BhavcopyRecord(
                2,
                "NIFTY",
                LocalDate.of(2024, 4, 18),
                new BigDecimal("22500"),
                "CE",
                LocalDate.of(2024, 4, 17),
                new BigDecimal("110.50"),
                new BigDecimal("125.00"),
                new BigDecimal("105.25"),
                new BigDecimal("120.75"),
                new BigDecimal("119.50"),
                150,
                new BigDecimal("1234.56"),
                2000,
                250,
                "INS_123"
        );

        BhavcopyWriter.WriteResult result = new BhavcopyWriter().write(connectionRecorder.proxy(), List.of(record));
        Map<Integer, Object> instrumentBatch = instrumentStatement.batchParameters().get(0);

        assertEquals(new BhavcopyWriter.WriteResult(1, 1), result);
        assertEquals(List.of("setAutoCommit:false", "commit", "setAutoCommit:true"), connectionRecorder.events());

        Map<Integer, Object> expectedInstrument = new LinkedHashMap<>();
        expectedInstrument.put(1, "INS_123");
        expectedInstrument.put(2, "NIFTY");
        expectedInstrument.put(3, "NIFTY");
        expectedInstrument.put(4, Timestamp.valueOf(record.expiryDate().atStartOfDay()));
        expectedInstrument.put(5, new BigDecimal("22500"));
        expectedInstrument.put(6, "CE");
        expectedInstrument.put(7, null);  // lot_size
        expectedInstrument.put(8, null);  // tick_size
        expectedInstrument.put(9, null);  // exchange_token
        expectedInstrument.put(10, null); // trading_symbol
        expectedInstrument.put(11, true);
        expectedInstrument.put(12, "WEEKLY");
        expectedInstrument.put(13, instrumentBatch.get(13));
        expectedInstrument.put(14, instrumentBatch.get(13));
        assertEquals(List.of(expectedInstrument), instrumentStatement.batchParameters());
        assertInstanceOf(Timestamp.class, instrumentBatch.get(13));
        assertEquals(List.of(Map.ofEntries(
                Map.entry(1, Timestamp.valueOf(record.tradeDate().atTime(15, 30))),
                Map.entry(2, Date.valueOf(record.tradeDate())),
                Map.entry(3, "INS_123"),
                Map.entry(4, new BigDecimal("110.50")),
                Map.entry(5, new BigDecimal("125.00")),
                Map.entry(6, new BigDecimal("105.25")),
                Map.entry(7, new BigDecimal("120.75")),
                Map.entry(8, new BigDecimal("119.50")),
                Map.entry(9, 150L),
                Map.entry(10, new BigDecimal("1234.56")),
                Map.entry(11, 2000L),
                Map.entry(12, 250L)
        )), optionsStatement.batchParameters());
    }

    @Test
    void skipsInstrumentInsertWhenAlreadyExists() throws Exception {
        // Query returns the instrument as already existing
        BhavcopyJdbcTestSupport.QueryStatementRecorder queryStatement =
                new BhavcopyJdbcTestSupport.QueryStatementRecorder(List.of(List.of("INS_EXISTING")));
        BhavcopyJdbcTestSupport.PreparedStatementRecorder optionsStatement =
                new BhavcopyJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        // No instrument insert statement needed — dedup will skip it entirely
        BhavcopyJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new BhavcopyJdbcTestSupport.ConnectionRecorder(true,
                        queryStatement.proxy(), optionsStatement.proxy());

        BhavcopyRecord record = new BhavcopyRecord(
                2,
                "NIFTY",
                LocalDate.of(2024, 4, 18),
                new BigDecimal("22500"),
                "CE",
                LocalDate.of(2024, 4, 17),
                new BigDecimal("110.50"),
                new BigDecimal("125.00"),
                new BigDecimal("105.25"),
                new BigDecimal("120.75"),
                new BigDecimal("119.50"),
                150,
                new BigDecimal("1234.56"),
                2000,
                250,
                "INS_EXISTING"
        );

        BhavcopyWriter.WriteResult result = new BhavcopyWriter().write(connectionRecorder.proxy(), List.of(record));

        assertEquals(0, result.instrumentsInserted());
        assertEquals(1, result.optionsHistoricalInserted());
    }
}
