package com.strategysquad.ingestion.bhavcopy;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes normalized Bhavcopy data into instrument and historical tables in batches.
 *
 * <p>SQL statements match the frozen Golden Source DDL (V001). Column names and
 * ordering align with {@code instrument_master} and {@code options_historical}.
 */
public class BhavcopyWriter {
    private static final String DEFAULT_INSERT_INSTRUMENT_SQL =
            "INSERT INTO instrument_master"
                    + " (instrument_id, underlying, symbol, expiry_date, strike, option_type,"
                    + "  is_active, expiry_type, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String DEFAULT_INSERT_OPTIONS_SQL =
            "INSERT INTO options_historical"
                    + " (trade_ts, trade_date, instrument_id, open_price, high_price,"
                    + "  low_price, close_price, volume, open_interest)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Market close time used to normalize trade_date into trade_ts (IST 15:30). */
    private static final LocalTime MARKET_CLOSE_IST = LocalTime.of(15, 30);

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
        boolean autoCommit = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (autoCommit) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint("bhavcopy_ingestion_write");
        }
        try {
            int instrumentsInserted = insertInstruments(connection, records);
            int historicalInserted = insertOptionsHistorical(connection, records);
            if (autoCommit) {
                connection.commit();
            }
            return new WriteResult(instrumentsInserted, historicalInserted);
        } catch (SQLException ex) {
            if (autoCommit) {
                connection.rollback();
            } else if (savepoint != null) {
                connection.rollback(savepoint);
            }
            throw ex;
        } finally {
            if (!autoCommit && savepoint != null) {
                connection.releaseSavepoint(savepoint);
            }
            if (autoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private int insertInstruments(Connection connection, List<BhavcopyRecord> records) throws SQLException {
        Map<String, BhavcopyRecord> uniqueByInstrumentId = new LinkedHashMap<>();
        for (BhavcopyRecord record : records) {
            uniqueByInstrumentId.putIfAbsent(record.instrumentId(), record);
        }

        Timestamp now = Timestamp.valueOf(java.time.LocalDateTime.now());
        try (PreparedStatement statement = connection.prepareStatement(insertInstrumentSql)) {
            for (BhavcopyRecord record : uniqueByInstrumentId.values()) {
                statement.setString(1, record.instrumentId());
                statement.setString(2, record.underlying());
                statement.setString(3, record.underlying());          // symbol = underlying for index options
                statement.setTimestamp(4, Timestamp.valueOf(record.expiryDate().atStartOfDay()));
                statement.setBigDecimal(5, record.strike());
                statement.setString(6, record.optionType());
                statement.setBoolean(7, !record.expiryDate().isBefore(record.tradeDate())); // is_active
                statement.setString(8, deriveExpiryType(record.expiryDate()));               // expiry_type
                statement.setTimestamp(9, now);                        // created_at
                statement.setTimestamp(10, now);                       // updated_at
                statement.addBatch();
            }
            return successfulBatchCount(statement.executeBatch());
        }
    }

    /**
     * Derives expiry type from the expiry date. Monthly expiries fall on the last
     * Thursday of the month; all others are treated as weekly.
     */
    static String deriveExpiryType(LocalDate expiryDate) {
        // Find last Thursday of the expiry month
        LocalDate lastDay = expiryDate.withDayOfMonth(expiryDate.lengthOfMonth());
        while (lastDay.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
            lastDay = lastDay.minusDays(1);
        }
        return expiryDate.equals(lastDay) ? "MONTHLY" : "WEEKLY";
    }

    private int insertOptionsHistorical(Connection connection, List<BhavcopyRecord> records) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(insertOptionsSql)) {
            for (BhavcopyRecord record : records) {
                // trade_ts: normalized to market close (IST 15:30) on trade_date
                Timestamp tradeTs = Timestamp.valueOf(record.tradeDate().atTime(MARKET_CLOSE_IST));
                statement.setTimestamp(1, tradeTs);
                statement.setDate(2, Date.valueOf(record.tradeDate()));
                statement.setString(3, record.instrumentId());
                statement.setBigDecimal(4, record.open());         // open_price
                statement.setBigDecimal(5, record.high());         // high_price
                statement.setBigDecimal(6, record.low());          // low_price
                statement.setBigDecimal(7, record.close());        // close_price
                statement.setLong(8, record.contracts());          // volume (contracts from Bhavcopy)
                statement.setLong(9, record.openInterest());       // open_interest
                statement.addBatch();
            }
            return successfulBatchCount(statement.executeBatch());
        }
    }

    private int successfulBatchCount(int[] results) throws SQLException {
        int count = 0;
        for (int result : results) {
            if (result == Statement.EXECUTE_FAILED) {
                throw new SQLException("Batch execution failed for one or more rows");
            }
            if (result == Statement.SUCCESS_NO_INFO || result >= 0) {
                count++;
            }
        }
        return count;
    }

    public record WriteResult(int instrumentsInserted, int optionsHistoricalInserted) {
    }
}
