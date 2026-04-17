package com.strategysquad.enrichment;

import com.strategysquad.ingestion.live.SpotLiveTick;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the latest spot tick at or before a given option exchange timestamp.
 */
public class SpotLiveLookup {
    private static final String DEFAULT_SELECT_SQL =
            "SELECT exchange_ts, ingest_ts, underlying, last_price"
                    + " FROM spot_live"
                    + " WHERE underlying = ? AND exchange_ts <= ?"
                    + " ORDER BY exchange_ts DESC"
                    + " LIMIT 1";

    private final String selectSql;

    public SpotLiveLookup() {
        this(DEFAULT_SELECT_SQL);
    }

    public SpotLiveLookup(String selectSql) {
        this.selectSql = Objects.requireNonNull(selectSql, "selectSql must not be null");
    }

    public Optional<SpotLiveTick> findLatestAtOrBefore(
            Connection connection,
            String underlying,
            Instant exchangeTs
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(exchangeTs, "exchangeTs must not be null");

        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, underlying);
            statement.setTimestamp(2, java.sql.Timestamp.from(exchangeTs));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SpotLiveTick(
                        resultSet.getTimestamp("exchange_ts").toInstant(),
                        resultSet.getTimestamp("ingest_ts").toInstant(),
                        resultSet.getString("underlying"),
                        resultSet.getBigDecimal("last_price")
                ));
            }
        }
    }
}
