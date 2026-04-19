package com.strategysquad.research;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads cohort observations and computes timeframe-window behavior.
 */
public class TimeframeAnalysisService {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";
    private static final String HISTORICAL_OBSERVATION_SQL = """
            SELECT exchange_ts, instrument_id, last_price
            FROM options_enriched
            WHERE underlying = ?
              AND option_type = ?
              AND time_bucket_15m BETWEEN ? AND ?
              AND moneyness_bucket = ?
            ORDER BY exchange_ts
            """;

    private final String jdbcUrl;

    public TimeframeAnalysisService() {
        this(DEFAULT_JDBC_URL);
    }

    public TimeframeAnalysisService(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public TimeframeAnalysisSnapshot loadSnapshot(
            String underlying,
            String optionType,
            BigDecimal spot,
            BigDecimal strike,
            int dte,
            BigDecimal optionPrice,
            String selectedTimeframe,
            LocalDate customFrom,
            LocalDate customTo
    ) throws SQLException {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(underlying, optionType, spot, strike, dte);
        int bucketLo = Math.max(0, cohort.timeBucket15m() - 96);
        int bucketHi = cohort.timeBucket15m() + 96;
        List<TimeframeAnalysisSnapshotCalculator.Observation> observations = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(HISTORICAL_OBSERVATION_SQL)) {
            statement.setString(1, cohort.underlying());
            statement.setString(2, cohort.optionType());
            statement.setInt(3, bucketLo);
            statement.setInt(4, bucketHi);
            statement.setInt(5, cohort.moneynessBucket());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    observations.add(new TimeframeAnalysisSnapshotCalculator.Observation(
                            rs.getTimestamp("exchange_ts").toInstant(),
                            rs.getString("instrument_id"),
                            rs.getDouble("last_price")
                    ));
                }
            }
        }
        return TimeframeAnalysisSnapshotCalculator.calculate(
                cohort,
                observations,
                optionPrice.doubleValue(),
                selectedTimeframe,
                customFrom,
                customTo
        );
    }
}
