package com.strategysquad.research;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads real matched cohort rows and computes trust diagnostics plus representative cases.
 */
public class DiagnosticsCohortService {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);
    private static final String MATCHED_OBSERVATIONS_SQL = """
            SELECT curr.instrument_id,
                   curr.exchange_ts,
                   curr.last_price AS entry_price,
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
            ORDER BY curr.exchange_ts
            """;

    private final String jdbcUrl;

    public DiagnosticsCohortService() {
        this(DEFAULT_JDBC_URL);
    }

    public DiagnosticsCohortService(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public DiagnosticsSnapshot loadSnapshot(
            String underlying,
            String optionType,
            BigDecimal spot,
            BigDecimal strike,
            int dte,
            BigDecimal optionPrice
    ) throws SQLException {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(underlying, optionType, spot, strike, dte);
        List<MatchedHistoricalObservation> observations = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(MATCHED_OBSERVATIONS_SQL)) {
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

                    Double nextReturn = null;
                    double nextPrice = rs.getDouble("next_price");
                    if (!rs.wasNull()) {
                        nextReturn = ((nextPrice - entryPrice) / entryPrice) * 100.0d;
                    }

                    Double expiryReturn = null;
                    double expiryPrice = rs.getDouble("expiry_price");
                    if (!rs.wasNull()) {
                        expiryReturn = ((expiryPrice - entryPrice) / entryPrice) * 100.0d;
                    }

                    Timestamp exchangeTimestamp = rs.getTimestamp("exchange_ts");
                    LocalDate tradeDate = toIstLocalDate(exchangeTimestamp.toInstant());
                    observations.add(new MatchedHistoricalObservation(
                            rs.getString("instrument_id"),
                            tradeDate,
                            entryPrice,
                            nextReturn,
                            expiryReturn
                    ));
                }
            }
        }

        return DiagnosticsSnapshotCalculator.calculate(cohort, optionPrice.doubleValue(), observations);
    }

    private static LocalDate toIstLocalDate(Instant instant) {
        return instant.atOffset(IST_OFFSET).toLocalDate();
    }
}
