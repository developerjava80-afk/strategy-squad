package com.strategysquad.ingestion.bhavcopy;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes normalized Bhavcopy data into instrument and historical tables in batches.
 */
public class BhavcopyWriter {
    private static final String DEFAULT_INSERT_INSTRUMENT_SQL =
            "INSERT INTO instrument_master (instrument_id, underlying, expiry_date, strike, option_type) VALUES (?, ?, ?, ?, ?)";
    private static final String DEFAULT_INSERT_OPTIONS_SQL =
            "INSERT INTO options_historical (instrument_id, trade_date, open, high, low, close, settle_price, contracts, value_in_lakhs, open_interest, change_in_oi) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String insertInstrumentSql;
    private final String insertOptionsSql;

    public BhavcopyWriter() {
        this(DEFAULT_INSERT_INSTRUMENT_SQL, DEFAULT_INSERT_OPTIONS_SQL);
    }

    public BhavcopyWriter(String insertInstrumentSql, String insertOptionsSql) {
        this.insertInstrumentSql = Objects.requireNonNull(insertInstrumentSql, "insertInstrumentSql must not be null");
        this.insertOptionsSql = Objects.requireNonNull(insertOptionsSql, "insertOptionsSql must not be null");
    }

    public WriteResult write(Connection connection, List<BhavcopyRecord> records) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(records, "records must not be null");
        if (records.isEmpty()) {
            return new WriteResult(0, 0);
        }
        int instrumentsInserted = insertInstruments(connection, records);
        int historicalInserted = insertOptionsHistorical(connection, records);
        return new WriteResult(instrumentsInserted, historicalInserted);
    }

    private int insertInstruments(Connection connection, List<BhavcopyRecord> records) throws SQLException {
        Map<String, BhavcopyRecord> uniqueByInstrumentId = new LinkedHashMap<>();
        for (BhavcopyRecord record : records) {
            uniqueByInstrumentId.putIfAbsent(record.instrumentId(), record);
        }

        try (PreparedStatement statement = connection.prepareStatement(insertInstrumentSql)) {
            for (BhavcopyRecord record : uniqueByInstrumentId.values()) {
                statement.setString(1, record.instrumentId());
                statement.setString(2, record.underlying());
                statement.setDate(3, Date.valueOf(record.expiryDate()));
                statement.setBigDecimal(4, record.strike());
                statement.setString(5, record.optionType());
                statement.addBatch();
            }
            return successfulBatchCount(statement.executeBatch());
        }
    }

    private int insertOptionsHistorical(Connection connection, List<BhavcopyRecord> records) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(insertOptionsSql)) {
            for (BhavcopyRecord record : records) {
                statement.setString(1, record.instrumentId());
                statement.setDate(2, Date.valueOf(record.tradeDate()));
                statement.setBigDecimal(3, record.open());
                statement.setBigDecimal(4, record.high());
                statement.setBigDecimal(5, record.low());
                statement.setBigDecimal(6, record.close());
                statement.setBigDecimal(7, record.settlePrice());
                statement.setLong(8, record.contracts());
                statement.setBigDecimal(9, record.valueInLakhs());
                statement.setLong(10, record.openInterest());
                statement.setLong(11, record.changeInOi());
                statement.addBatch();
            }
            return successfulBatchCount(statement.executeBatch());
        }
    }

    private int successfulBatchCount(int[] results) {
        int count = 0;
        for (int result : results) {
            if (result == Statement.SUCCESS_NO_INFO || result >= 0) {
                count++;
            }
        }
        return count;
    }

    public record WriteResult(int instrumentsInserted, int optionsHistoricalInserted) {
    }
}
