package com.strategysquad.enrichment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads option instrument metadata from {@code instrument_master}.
 */
public class InstrumentMasterLookup {
    private static final String DEFAULT_SELECT_SQL =
            "SELECT instrument_id, underlying, option_type, strike, expiry_date"
                    + " FROM instrument_master"
                    + " WHERE instrument_id = ?";

    private final String selectSql;

    public InstrumentMasterLookup() {
        this(DEFAULT_SELECT_SQL);
    }

    public InstrumentMasterLookup(String selectSql) {
        this.selectSql = Objects.requireNonNull(selectSql, "selectSql must not be null");
    }

    public Optional<OptionInstrument> findByInstrumentId(Connection connection, String instrumentId) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(instrumentId, "instrumentId must not be null");

        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, instrumentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OptionInstrument(
                        resultSet.getString("instrument_id"),
                        resultSet.getString("underlying"),
                        resultSet.getString("option_type"),
                        resultSet.getBigDecimal("strike"),
                        resultSet.getTimestamp("expiry_date").toInstant()
                ));
            }
        }
    }
}
