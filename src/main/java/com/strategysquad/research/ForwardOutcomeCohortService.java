package com.strategysquad.research;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads matched historical cohort rows and computes forward premium behavior from real observations.
 */
public class ForwardOutcomeCohortService {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String COHORT_SQL = """
            SELECT instrument_id, exchange_ts, last_price
            FROM options_enriched
            WHERE underlying = ?
              AND option_type = ?
              AND time_bucket_15m BETWEEN ? AND ?
              AND moneyness_bucket = ?
            """;
    private static final Pattern INSTRUMENT_ID_PATTERN = Pattern.compile("INS_[A-Z]+_\\d{8}_\\d+_[A-Z]+");

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
        int bucketLo = Math.max(0, cohort.timeBucket15m() - 96);
        int bucketHi = cohort.timeBucket15m() + 96;

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "admin", "quest")) {
            List<CohortMatch> matches = new ArrayList<>();
            Set<String> instrumentIds = new LinkedHashSet<>();

            try (PreparedStatement ps = connection.prepareStatement(COHORT_SQL)) {
                ps.setString(1, cohort.underlying());
                ps.setString(2, cohort.optionType());
                ps.setInt(3, bucketLo);
                ps.setInt(4, bucketHi);
                ps.setInt(5, cohort.moneynessBucket());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("instrument_id");
                        Instant ts = rs.getTimestamp("exchange_ts").toInstant();
                        double price = rs.getDouble("last_price");
                        matches.add(new CohortMatch(id, ts, price));
                        instrumentIds.add(id);
                    }
                }
            }

            if (matches.isEmpty()) {
                return ForwardOutcomeSnapshotCalculator.calculate(cohort, List.of(), List.of());
            }

            Map<String, List<PriceTick>> series = loadInstrumentSeries(connection, instrumentIds);

            List<Double> nextDayReturns = new ArrayList<>();
            List<Double> expiryReturns = new ArrayList<>();
            for (CohortMatch match : matches) {
                if (match.entryPrice <= 0) continue;
                List<PriceTick> ticks = series.getOrDefault(match.instrumentId, List.of());
                Double nextPrice = findNextPrice(ticks, match.exchangeTs);
                if (nextPrice != null) {
                    nextDayReturns.add(((nextPrice - match.entryPrice) / match.entryPrice) * 100.0d);
                }
                Double expiryPrice = findExpiryPrice(ticks);
                if (expiryPrice != null) {
                    expiryReturns.add(((expiryPrice - match.entryPrice) / match.entryPrice) * 100.0d);
                }
            }

            return ForwardOutcomeSnapshotCalculator.calculate(cohort, nextDayReturns, expiryReturns);
        }
    }

    private Map<String, List<PriceTick>> loadInstrumentSeries(
            Connection connection, Set<String> instrumentIds
    ) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT instrument_id, exchange_ts, last_price FROM options_enriched WHERE instrument_id IN (");
        boolean first = true;
        for (String id : instrumentIds) {
            if (!INSTRUMENT_ID_PATTERN.matcher(id).matches()) {
                throw new IllegalStateException("Invalid instrument_id format: " + id);
            }
            if (!first) sql.append(',');
            sql.append('\'').append(id).append('\'');
            first = false;
        }
        sql.append(") ORDER BY instrument_id, exchange_ts");

        Map<String, List<PriceTick>> map = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.computeIfAbsent(rs.getString("instrument_id"), k -> new ArrayList<>())
                        .add(new PriceTick(rs.getTimestamp("exchange_ts").toInstant(), rs.getDouble("last_price")));
            }
        }
        return map;
    }

    private static Double findNextPrice(List<PriceTick> ticks, Instant afterTs) {
        for (PriceTick tick : ticks) {
            if (tick.ts.isAfter(afterTs)) {
                return tick.price;
            }
        }
        return null;
    }

    private static Double findExpiryPrice(List<PriceTick> ticks) {
        return ticks.isEmpty() ? null : ticks.get(ticks.size() - 1).price;
    }

    private record CohortMatch(String instrumentId, Instant exchangeTs, double entryPrice) {}
    private record PriceTick(Instant ts, double price) {}
}
