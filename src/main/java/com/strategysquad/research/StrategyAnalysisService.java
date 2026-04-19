package com.strategysquad.research;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Derives compact strategy-testing metrics from canonical historical cohorts.
 */
public class StrategyAnalysisService {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";
    private static final ZoneId IST = ZoneId.of("Asia/Calcutta");
    private static final Pattern INSTRUMENT_ID_PATTERN = Pattern.compile("INS_[A-Z]+_\\d{8}_\\d+_[A-Z]+");
    private static final String COHORT_SQL = """
            SELECT instrument_id, exchange_ts, option_type, strike, last_price, expiry_date, moneyness_bucket
            FROM options_enriched
            WHERE underlying = ?
              AND option_type IN (%s)
              AND time_bucket_15m BETWEEN ? AND ?
              AND moneyness_bucket IN (%s)
            ORDER BY exchange_ts
            """;

    private final String jdbcUrl;

    public StrategyAnalysisService() {
        this(DEFAULT_JDBC_URL);
    }

    public StrategyAnalysisService(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public StrategyAnalysisSnapshot loadSnapshot(
            String underlying,
            String optionType,
            BigDecimal spot,
            BigDecimal strike,
            int dte,
            String mode,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo
    ) throws SQLException {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(underlying, optionType, spot, strike, dte);
        int bucketLo = Math.max(0, cohort.timeBucket15m() - 96);
        int bucketHi = cohort.timeBucket15m() + 96;
        int selectedBucket = cohort.moneynessBucket();
        int wingBucket = Math.abs(selectedBucket);
        if (wingBucket == 0) {
            wingBucket = "BANKNIFTY".equalsIgnoreCase(underlying) ? 100 : 50;
        }

        List<String> optionTypes = "SINGLE_OPTION".equalsIgnoreCase(mode)
                ? List.of(optionType)
                : List.of("CE", "PE");
        List<Integer> buckets = switch (mode) {
            case "STRANGLE" -> List.of(wingBucket, -wingBucket);
            default -> List.of(selectedBucket);
        };

        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD)) {
            List<LegMatch> matches = loadMatches(connection, cohort.underlying(), optionTypes, buckets, bucketLo, bucketHi);
            if (matches.isEmpty()) {
                return StrategyAnalysisCalculator.calculate(mode, timeframe, List.of());
            }
            LocalDate anchorDate = matches.stream()
                    .map(item -> item.exchangeTs().atZone(IST).toLocalDate())
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            DateRange range = resolveRange(timeframe, customFrom, customTo, anchorDate);
            List<LegMatch> filtered = matches.stream()
                    .filter(item -> {
                        LocalDate tradeDate = item.exchangeTs().atZone(IST).toLocalDate();
                        return !tradeDate.isBefore(range.from()) && !tradeDate.isAfter(range.to());
                    })
                    .toList();
            if (filtered.isEmpty()) {
                return StrategyAnalysisCalculator.calculate(mode, timeframe, List.of());
            }

            Map<String, Double> expiryValues = loadExpiryValues(connection, filtered.stream().map(LegMatch::instrumentId).collect(java.util.stream.Collectors.toSet()));
            List<StrategyAnalysisCalculator.StrategyScenario> scenarios = switch (mode) {
                case "STRADDLE" -> buildStraddles(filtered, expiryValues);
                case "STRANGLE" -> buildStrangles(filtered, expiryValues, wingBucket);
                default -> buildSingleOptions(filtered, expiryValues);
            };
            return StrategyAnalysisCalculator.calculate(mode, timeframe, scenarios);
        }
    }

    private List<LegMatch> loadMatches(
            Connection connection,
            String underlying,
            List<String> optionTypes,
            List<Integer> buckets,
            int bucketLo,
            int bucketHi
    ) throws SQLException {
        String optionTypePlaceholders = String.join(",", java.util.Collections.nCopies(optionTypes.size(), "?"));
        String bucketPlaceholders = String.join(",", java.util.Collections.nCopies(buckets.size(), "?"));
        String sql = COHORT_SQL.formatted(optionTypePlaceholders, bucketPlaceholders);
        List<LegMatch> matches = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, underlying);
            for (String optionType : optionTypes) {
                ps.setString(index++, optionType);
            }
            ps.setInt(index++, bucketLo);
            ps.setInt(index++, bucketHi);
            for (Integer bucket : buckets) {
                ps.setInt(index++, bucket);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(new LegMatch(
                            rs.getString("instrument_id"),
                            rs.getTimestamp("exchange_ts").toInstant(),
                            rs.getString("option_type"),
                            rs.getDouble("strike"),
                            rs.getDouble("last_price"),
                            rs.getTimestamp("expiry_date").toInstant(),
                            rs.getInt("moneyness_bucket")
                    ));
                }
            }
        }
        return matches;
    }

    private Map<String, Double> loadExpiryValues(Connection connection, Set<String> instrumentIds) throws SQLException {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT instrument_id, exchange_ts, last_price FROM options_enriched WHERE instrument_id IN (");
        boolean first = true;
        for (String id : instrumentIds) {
            if (!INSTRUMENT_ID_PATTERN.matcher(id).matches()) {
                throw new IllegalStateException("Invalid instrument_id format: " + id);
            }
            if (!first) {
                sql.append(',');
            }
            sql.append('\'').append(id).append('\'');
            first = false;
        }
        sql.append(") ORDER BY instrument_id, exchange_ts");

        Map<String, Double> expiryValues = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                expiryValues.put(rs.getString("instrument_id"), rs.getDouble("last_price"));
            }
        }
        return expiryValues;
    }

    private List<StrategyAnalysisCalculator.StrategyScenario> buildSingleOptions(
            List<LegMatch> matches,
            Map<String, Double> expiryValues
    ) {
        List<StrategyAnalysisCalculator.StrategyScenario> scenarios = new ArrayList<>();
        for (LegMatch match : matches) {
            Double expiryValue = expiryValues.get(match.instrumentId());
            if (expiryValue == null) {
                continue;
            }
            double pnl = match.entryPrice() - expiryValue;
            scenarios.add(new StrategyAnalysisCalculator.StrategyScenario(
                    match.entryPrice(),
                    expiryValue,
                    pnl
            ));
        }
        return scenarios;
    }

    private List<StrategyAnalysisCalculator.StrategyScenario> buildStraddles(
            List<LegMatch> matches,
            Map<String, Double> expiryValues
    ) {
        Map<String, PairCandidate> candidates = new LinkedHashMap<>();
        for (LegMatch match : matches) {
            String key = straddleKey(match);
            PairCandidate candidate = candidates.computeIfAbsent(key, ignored -> new PairCandidate());
            if ("CE".equalsIgnoreCase(match.optionType())) {
                candidate.ce = match;
            } else if ("PE".equalsIgnoreCase(match.optionType())) {
                candidate.pe = match;
            }
        }

        List<StrategyAnalysisCalculator.StrategyScenario> scenarios = new ArrayList<>();
        for (PairCandidate candidate : candidates.values()) {
            if (candidate.ce == null || candidate.pe == null) {
                continue;
            }
            Double ceExpiry = expiryValues.get(candidate.ce.instrumentId());
            Double peExpiry = expiryValues.get(candidate.pe.instrumentId());
            if (ceExpiry == null || peExpiry == null) {
                continue;
            }
            double premium = candidate.ce.entryPrice() + candidate.pe.entryPrice();
            double expiryValue = ceExpiry + peExpiry;
            scenarios.add(new StrategyAnalysisCalculator.StrategyScenario(
                    premium,
                    expiryValue,
                    premium - expiryValue
            ));
        }
        return scenarios;
    }

    private List<StrategyAnalysisCalculator.StrategyScenario> buildStrangles(
            List<LegMatch> matches,
            Map<String, Double> expiryValues,
            int wingBucket
    ) {
        Map<String, SideAggregate> ceByKey = new TreeMap<>();
        Map<String, SideAggregate> peByKey = new TreeMap<>();
        for (LegMatch match : matches) {
            Double expiryValue = expiryValues.get(match.instrumentId());
            if (expiryValue == null) {
                continue;
            }
            String key = dayExpiryKey(match);
            if ("CE".equalsIgnoreCase(match.optionType()) && match.moneynessBucket() == wingBucket) {
                ceByKey.computeIfAbsent(key, ignored -> new SideAggregate()).add(match.entryPrice(), expiryValue);
            }
            if ("PE".equalsIgnoreCase(match.optionType()) && match.moneynessBucket() == -wingBucket) {
                peByKey.computeIfAbsent(key, ignored -> new SideAggregate()).add(match.entryPrice(), expiryValue);
            }
        }

        List<StrategyAnalysisCalculator.StrategyScenario> scenarios = new ArrayList<>();
        for (Map.Entry<String, SideAggregate> entry : ceByKey.entrySet()) {
            SideAggregate ce = entry.getValue();
            SideAggregate pe = peByKey.get(entry.getKey());
            if (pe == null) {
                continue;
            }
            double premium = ce.averageEntry() + pe.averageEntry();
            double expiryValue = ce.averageExpiry() + pe.averageExpiry();
            scenarios.add(new StrategyAnalysisCalculator.StrategyScenario(
                    premium,
                    expiryValue,
                    premium - expiryValue
            ));
        }
        return scenarios;
    }

    private static String straddleKey(LegMatch match) {
        return "%s|%s|%.4f".formatted(
                match.exchangeTs().atZone(IST).toLocalDate(),
                match.expiryTs().atZone(IST).toLocalDate(),
                match.strike()
        );
    }

    private static String dayExpiryKey(LegMatch match) {
        return "%s|%s".formatted(
                match.exchangeTs().atZone(IST).toLocalDate(),
                match.expiryTs().atZone(IST).toLocalDate()
        );
    }

    private static DateRange resolveRange(String timeframe, LocalDate customFrom, LocalDate customTo, LocalDate anchorDate) {
        String normalized = timeframe == null || timeframe.isBlank() ? "1Y" : timeframe;
        if ("CUSTOM".equalsIgnoreCase(normalized)) {
            if (customFrom == null || customTo == null) {
                throw new IllegalArgumentException("Custom timeframe requires both from and to dates");
            }
            if (customTo.isBefore(customFrom)) {
                throw new IllegalArgumentException("Custom timeframe end date must be on or after start date");
            }
            return new DateRange(customFrom, customTo);
        }
        return switch (normalized) {
            case "5Y" -> new DateRange(anchorDate.minusYears(5).plusDays(1), anchorDate);
            case "2Y" -> new DateRange(anchorDate.minusYears(2).plusDays(1), anchorDate);
            case "6M" -> new DateRange(anchorDate.minusMonths(6).plusDays(1), anchorDate);
            case "3M" -> new DateRange(anchorDate.minusMonths(3).plusDays(1), anchorDate);
            case "1M" -> new DateRange(anchorDate.minusMonths(1).plusDays(1), anchorDate);
            default -> new DateRange(anchorDate.minusYears(1).plusDays(1), anchorDate);
        };
    }

    private record LegMatch(
            String instrumentId,
            Instant exchangeTs,
            String optionType,
            double strike,
            double entryPrice,
            Instant expiryTs,
            int moneynessBucket
    ) {
    }

    private static final class PairCandidate {
        private LegMatch ce;
        private LegMatch pe;
    }

    private static final class SideAggregate {
        private double entrySum;
        private double expirySum;
        private int count;

        private void add(double entry, double expiry) {
            entrySum += entry;
            expirySum += expiry;
            count += 1;
        }

        private double averageEntry() {
            return count == 0 ? 0 : entrySum / count;
        }

        private double averageExpiry() {
            return count == 0 ? 0 : expirySum / count;
        }
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
