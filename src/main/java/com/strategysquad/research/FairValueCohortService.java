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
 * Loads historical cohort prices from the canonical database and computes valuation statistics.
 */
public class FairValueCohortService {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String HISTORICAL_PRICE_SQL = """
            SELECT last_price
            FROM options_enriched
            WHERE underlying = ?
              AND option_type = ?
              AND time_bucket_15m = ?
              AND moneyness_bucket = ?
            ORDER BY last_price
            """;

    private final String jdbcUrl;

    public FairValueCohortService() {
        this(DEFAULT_JDBC_URL);
    }

    public FairValueCohortService(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public FairValueSnapshot loadSnapshot(
            String underlying,
            String optionType,
            BigDecimal spot,
            BigDecimal strike,
            int dte,
            BigDecimal optionPrice
    ) throws SQLException {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(underlying, optionType, spot, strike, dte);
        List<Double> historicalPrices = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(HISTORICAL_PRICE_SQL)) {
            statement.setString(1, cohort.underlying());
            statement.setString(2, cohort.optionType());
            statement.setInt(3, cohort.timeBucket15m());
            statement.setInt(4, cohort.moneynessBucket());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    historicalPrices.add(rs.getDouble(1));
                }
            }
        }
        return FairValueSnapshotCalculator.calculate(cohort, historicalPrices, optionPrice.doubleValue());
    }
}
