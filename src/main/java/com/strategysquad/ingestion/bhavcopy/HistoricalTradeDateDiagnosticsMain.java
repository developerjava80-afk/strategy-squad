package com.strategysquad.ingestion.bhavcopy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HistoricalTradeDateDiagnosticsMain {
    private static final Logger LOGGER = Logger.getLogger(HistoricalTradeDateDiagnosticsMain.class.getName());
    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";
    private static final String SQL = "SELECT trade_ts FROM spot_historical ORDER BY trade_ts";

    public static void main(String[] args) {
        String jdbcUrl = args.length > 0 ? args[0] : DEFAULT_JDBC_URL;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(SQL);
             ResultSet rs = statement.executeQuery()) {
            List<LocalDate> tradeDates = new ArrayList<>();
            LocalDate lastTradeDate = null;
            while (rs.next()) {
                LocalDate tradeDate = toIstLocalDate(rs.getTimestamp("trade_ts").toInstant());
                if (!tradeDate.equals(lastTradeDate)) {
                    tradeDates.add(tradeDate);
                    lastTradeDate = tradeDate;
                }
            }

            LOGGER.info("Loaded trade dates count=" + tradeDates.size());
            for (int index = 0; index < Math.min(40, tradeDates.size()); index++) {
                LOGGER.info("tradeDates[" + index + "]=" + tradeDates.get(index));
            }
            LOGGER.info("Contains 2021-04-23=" + tradeDates.contains(LocalDate.parse("2021-04-23")));
            LOGGER.info("Contains 2021-04-20=" + tradeDates.contains(LocalDate.parse("2021-04-20")));
            LOGGER.info("Contains 2021-04-30=" + tradeDates.contains(LocalDate.parse("2021-04-30")));
            LOGGER.info("Contains 2025-07-16=" + tradeDates.contains(LocalDate.parse("2025-07-16")));
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Historical trade-date diagnostics failed", ex);
            System.exit(2);
        }
    }

    private static LocalDate toIstLocalDate(Instant instant) {
        return instant.atOffset(IST_OFFSET).toLocalDate();
    }
}