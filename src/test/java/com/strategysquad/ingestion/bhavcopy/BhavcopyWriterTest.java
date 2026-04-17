package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
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
        BhavcopyJdbcTestSupport.PreparedStatementRecorder instrumentStatement =
                new BhavcopyJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        BhavcopyJdbcTestSupport.PreparedStatementRecorder optionsStatement =
                new BhavcopyJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        BhavcopyJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new BhavcopyJdbcTestSupport.ConnectionRecorder(true, instrumentStatement.proxy(), optionsStatement.proxy());

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
        assertEquals(List.of(Map.of(
                1, "INS_123",
                2, "NIFTY",
                3, "NIFTY",
                4, Timestamp.valueOf(record.expiryDate().atStartOfDay()),
                5, new BigDecimal("22500"),
                6, "CE",
                7, true,
                8, "WEEKLY",
                9, instrumentBatch.get(9),
                10, instrumentBatch.get(9)
        )), instrumentStatement.batchParameters());
        assertInstanceOf(Timestamp.class, instrumentBatch.get(9));
        assertEquals(List.of(Map.of(
                1, Timestamp.valueOf(record.tradeDate().atTime(15, 30)),
                2, Date.valueOf(record.tradeDate()),
                3, "INS_123",
                4, new BigDecimal("110.50"),
                5, new BigDecimal("125.00"),
                6, new BigDecimal("105.25"),
                7, new BigDecimal("120.75"),
                8, 150L,
                9, 2000L
        )), optionsStatement.batchParameters());
    }
}
