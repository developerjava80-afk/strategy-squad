package com.strategysquad.research;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads matched historical cohort rows and computes forward premium behavior from real observations.
 */
public class ForwardOutcomeCohortService {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String FORWARD_OUTCOME_SQL = """
            SELECT curr.last_price AS entry_price,
                   (
                       SELECT next_tick.last_price
                       FROM options_enriched next_tick
                       WHERE next_tick.instrument_id = curr.instrument_id
                         AND next_tick.exchange_ts > curr.exchange_ts
                       ORDER BY next_tick.exchange_ts ASC
                       LIMIT 1
                   ) AS next_price,
                   (
                       SELECT expiry_tick.last_price
                       FROM options_enriched expiry_tick
                       WHERE expiry_tick.instrument_id = curr.instrument_id
                       ORDER BY expiry_tick.exchange_ts DESC
                       LIMIT 1
                   ) AS expiry_price
            FROM options_enriched curr
            WHERE curr.underlying = ?
              AND curr.option_type = ?
              AND curr.time_bucket_15m = ?
              AND curr.moneyness_bucket = ?
            """;

    private final String jdbcUrl;

    public ForwardOutcomeCohortService() {
        this(DEFAULT_JDBC_URL);
    }

    public ForwardOutcomeCohortService(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public ForwardOutcomeSnapshot loadSnapshot(
            String underlying,
            String optionType,
            BigDecimal spot,
            BigDecimal strike,
            int dte
    ) throws SQLException {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(underlying, optionType, spot, strike, dte);
        List<Double> nextDayReturns = new ArrayList<>();
        List<Double> expiryReturns = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(FORWARD_OUTCOME_SQL)) {
            statement.setString(1, cohort.underlying());
            statement.setString(2, cohort.optionType());
            statement.setInt(3, cohort.timeBucket15m());
            statement.setInt(4, cohort.moneynessBucket());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    double entryPrice = rs.getDouble("entry_price");
                    if (entryPrice <= 0) {
                        continue;
                    }

                    double nextPrice = rs.getDouble("next_price");
                    if (!rs.wasNull()) {
                        nextDayReturns.add(((nextPrice - entryPrice) / entryPrice) * 100.0d);
                    }

                    double expiryPrice = rs.getDouble("expiry_price");
                    if (!rs.wasNull()) {
                        expiryReturns.add(((expiryPrice - entryPrice) / entryPrice) * 100.0d);
                    }
                }
            }
        }

        return ForwardOutcomeSnapshotCalculator.calculate(cohort, nextDayReturns, expiryReturns);
    }
}
