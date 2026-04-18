package com.strategysquad.ingestion.bhavcopy;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                    + "  lot_size, tick_size, exchange_token, trading_symbol,"
                    + "  is_active, expiry_type, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String DEFAULT_INSERT_OPTIONS_SQL =
            "INSERT INTO options_historical"
                    + " (trade_ts, trade_date, instrument_id, open_price, high_price,"
                    + "  low_price, close_price, settle_price, volume, value_in_lakhs,"
                    + "  open_interest, change_in_oi)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Market close time used to normalize trade_date into trade_ts (IST 15:30). */
    private static final LocalTime MARKET_CLOSE_IST = LocalTime.of(15, 30);

    private final String insertInstrumentSql;
    private final String insertOptionsSql;
    private Set<String> knownInstrumentIds;

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
        if (autoCommit) {
            connection.setAutoCommit(false);
        }
        try {
            int instrumentsInserted = insertInstruments(connection, records);
            int historicalInserted = insertOptionsHistorical(connection, records);
            if (autoCommit) {
                connection.commit();
            }
            return new WriteResult(instrumentsInserted, historicalInserted);
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
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

        Set<String> cachedIds = knownInstrumentIds(connection);
        uniqueByInstrumentId.keySet().removeIf(cachedIds::contains);

        if (uniqueByInstrumentId.isEmpty()) {
            return 0;
        }

        Timestamp now = Timestamp.valueOf(java.time.LocalDateTime.now());
        try (PreparedStatement statement = connection.prepareStatement(insertInstrumentSql)) {
            for (BhavcopyRecord record : uniqueByInstrumentId.values()) {
                statement.setString(1, record.instrumentId());
                statement.setString(2, record.underlying());
                statement.setString(3, record.underlying());          // symbol = underlying for index options
                statement.setTimestamp(4, Timestamp.valueOf(record.expiryDate().atStartOfDay()));
                statement.setDouble(5, record.strike().doubleValue());
                statement.setString(6, record.optionType());
                if (record.lotSize() != null) {
                    statement.setInt(7, record.lotSize());                    // lot_size: from UDiFF NewBrdLotQty
                } else {
                    statement.setNull(7, Types.INTEGER);                     // lot_size: not available
                }
                statement.setNull(8, Types.DOUBLE);                          // tick_size: not available
                if (record.exchangeToken() != null) {
                    statement.setString(9, record.exchangeToken());          // exchange_token: from UDiFF FinInstrmId
                } else {
                    statement.setNull(9, Types.VARCHAR);                     // exchange_token: not available
                }
                if (record.tradingSymbol() != null) {
                    statement.setString(10, record.tradingSymbol());         // trading_symbol: from UDiFF FinInstrmNm
                } else {
                    statement.setNull(10, Types.VARCHAR);                    // trading_symbol: not available
                }
                statement.setBoolean(11, !record.expiryDate().isBefore(record.tradeDate())); // is_active
                statement.setString(12, deriveExpiryType(record.expiryDate()));               // expiry_type
                statement.setTimestamp(13, now);                       // created_at
                statement.setTimestamp(14, now);                       // updated_at
                statement.addBatch();
            }
            int inserted = successfulBatchCount(statement.executeBatch());
            cachedIds.addAll(uniqueByInstrumentId.keySet());
            return inserted;
        }
    }

    private Set<String> knownInstrumentIds(Connection connection) throws SQLException {
        if (knownInstrumentIds != null) {
            return knownInstrumentIds;
        }

        Set<String> existing = new HashSet<>();
        try (PreparedStatement stmt = connection.prepareStatement("SELECT instrument_id FROM instrument_master");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                existing.add(rs.getString(1));
            }
        }
        knownInstrumentIds = existing;
        return knownInstrumentIds;
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
                statement.setDouble(4, record.open().doubleValue());         // open_price
                statement.setDouble(5, record.high().doubleValue());         // high_price
                statement.setDouble(6, record.low().doubleValue());          // low_price
                statement.setDouble(7, record.close().doubleValue());        // close_price
                statement.setDouble(8, record.settlePrice().doubleValue());  // settle_price
                statement.setLong(9, record.contracts());                    // volume (contracts from Bhavcopy)
                statement.setDouble(10, record.valueInLakhs().doubleValue());// value_in_lakhs
                statement.setLong(11, record.openInterest());                // open_interest
                statement.setLong(12, record.changeInOi());                  // change_in_oi
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
