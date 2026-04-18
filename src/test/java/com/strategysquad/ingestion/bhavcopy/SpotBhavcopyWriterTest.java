package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpotBhavcopyWriterTest {

    @Test
    void writesCanonicalHistoricalColumns() throws Exception {
        BhavcopyJdbcTestSupport.QueryStatementRecorder queryRecorder =
                new BhavcopyJdbcTestSupport.QueryStatementRecorder(List.of());
        BhavcopyJdbcTestSupport.PreparedStatementRecorder statementRecorder =
                new BhavcopyJdbcTestSupport.PreparedStatementRecorder(new int[]{1});
        BhavcopyJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new BhavcopyJdbcTestSupport.ConnectionRecorder(true,
                        queryRecorder.proxy(), statementRecorder.proxy());

        SpotBhavcopyRecord record = new SpotBhavcopyRecord(
                4,
                "BANKNIFTY",
                LocalDate.of(2024, 4, 17),
                new BigDecimal("48200.10"),
                new BigDecimal("48510.25"),
                new BigDecimal("48090.75"),
                new BigDecimal("48455.40"),
                SpotSource.TRUE_SPOT,
                null
        );

        int inserted = new SpotBhavcopyWriter().write(connectionRecorder.proxy(), List.of(record));

        assertEquals(1, inserted);
        assertEquals(List.of(Map.of(
                1, Timestamp.valueOf(record.tradeDate().atTime(15, 30)),
                2, Date.valueOf(record.tradeDate()),
                3, "BANKNIFTY",
                4, new BigDecimal("48200.1"),
                5, new BigDecimal("48510.25"),
                6, new BigDecimal("48090.75"),
                7, new BigDecimal("48455.4")
        )), statementRecorder.batchParameters());
    }

    @Test
    void skipsSpotInsertWhenAlreadyExists() throws Exception {
        // Query returns BANKNIFTY as already existing for this trade_date
        BhavcopyJdbcTestSupport.QueryStatementRecorder queryRecorder =
                new BhavcopyJdbcTestSupport.QueryStatementRecorder(List.of(List.of("BANKNIFTY")));
        BhavcopyJdbcTestSupport.ConnectionRecorder connectionRecorder =
                new BhavcopyJdbcTestSupport.ConnectionRecorder(true, queryRecorder.proxy());

        SpotBhavcopyRecord record = new SpotBhavcopyRecord(
                4,
                "BANKNIFTY",
                LocalDate.of(2024, 4, 17),
                new BigDecimal("48200.10"),
                new BigDecimal("48510.25"),
                new BigDecimal("48090.75"),
                new BigDecimal("48455.40"),
                SpotSource.TRUE_SPOT,
                null
        );

        int inserted = new SpotBhavcopyWriter().write(connectionRecorder.proxy(), List.of(record));

        assertEquals(0, inserted);
    }
}
