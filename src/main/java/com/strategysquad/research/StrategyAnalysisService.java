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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Derives structure-level strategy-testing metrics from canonical historical cohorts.
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
              AND option_type = ?
              AND time_bucket_15m BETWEEN ? AND ?
              AND moneyness_bucket = ?
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
            StrategyStructureDefinition definition,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD)) {
            HistoricalContext context = new HistoricalContext(connection);
            List<StrategyAnalysisCalculator.StrategyScenario> selectedScenarios = analyzeDefinition(
                    definition,
                    timeframe,
                    customFrom,
                    customTo,
                    context
            );
            List<StrategyAnalysisSnapshot.StrategyRecommendation> recommendations = buildRecommendations(
                    definition,
                    timeframe,
                    customFrom,
                    customTo,
                    context
            );
            return StrategyAnalysisCalculator.calculate(
                    definition.mode(),
                    definition.orientation(),
                    timeframe,
                    currentTotalPremium(definition),
                    selectedScenarios,
                    recommendations
            );
        }
    }

    private List<StrategyAnalysisSnapshot.StrategyRecommendation> buildRecommendations(
            StrategyStructureDefinition selectedDefinition,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo,
            HistoricalContext context
    ) throws SQLException {
        List<StrategyStructureDefinition> candidates = candidateDefinitions(selectedDefinition);
        List<StrategyAnalysisSnapshot.StrategyRecommendation> scored = new ArrayList<>();
        for (StrategyStructureDefinition candidate : candidates) {
            List<StrategyAnalysisCalculator.StrategyScenario> scenarios = analyzeDefinition(
                    candidate,
                    timeframe,
                    customFrom,
                    customTo,
                    context
            );
            if (scenarios.isEmpty()) {
                continue;
            }
            double currentPremium = currentTotalPremium(candidate);
            StrategyAnalysisSnapshot preview = StrategyAnalysisCalculator.calculate(
                    candidate.mode(),
                    candidate.orientation(),
                    timeframe,
                    currentPremium,
                    scenarios,
                    List.of()
            );
            double premiumVsHistory = preview.snapshot().currentVsHistoricalAverage();
            double avgPnl = preview.snapshot().averagePnl();
            double winRate = preview.snapshot().winRatePct();
            double downsideSeverity = Math.abs(preview.expiryOutcome().tailLossP10());
            double sampleScore = Math.min(100.0d, preview.observationCount());
            double richnessScore = isSeller(candidate.orientation()) ? premiumVsHistory : -premiumVsHistory;
            double score = (richnessScore * 0.28d)
                    + (avgPnl * 0.32d)
                    + (winRate * 0.18d)
                    - (downsideSeverity * 0.14d)
                    + (sampleScore * 0.08d);
            scored.add(new StrategyAnalysisSnapshot.StrategyRecommendation(
                    candidate.mode(),
                    candidate.orientation(),
                    score,
                    preview.observationCount(),
                    premiumVsHistory,
                    avgPnl,
                    winRate,
                    downsideSeverity,
                    recommendationReason(candidate, preview)
            ));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(StrategyAnalysisSnapshot.StrategyRecommendation::score).reversed())
                .toList();
    }

    private String recommendationReason(
            StrategyStructureDefinition candidate,
            StrategyAnalysisSnapshot snapshot
    ) {
        return "%s with %.0f observations, %.1f%% win rate, avg pnl %.2f, premium delta %.2f.".formatted(
                labelFor(candidate.mode()),
                (double) snapshot.observationCount(),
                snapshot.snapshot().winRatePct(),
                snapshot.snapshot().averagePnl(),
                snapshot.snapshot().currentVsHistoricalAverage()
        );
    }

    private List<StrategyAnalysisCalculator.StrategyScenario> analyzeDefinition(
            StrategyStructureDefinition definition,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo,
            HistoricalContext context
    ) throws SQLException {
        if (definition.legs().isEmpty()) {
            return List.of();
        }
        List<Map<String, AggregatedLegPoint>> legMaps = new ArrayList<>();
        LocalDate anchorDate = null;
        for (StrategyStructureDefinition.StrategyLeg leg : definition.legs()) {
            CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                    definition.underlying(),
                    leg.optionType(),
                    definition.spot(),
                    leg.strike(),
                    definition.dte()
            );
            int bucketLo = Math.max(0, cohort.timeBucket15m() - 96);
            int bucketHi = cohort.timeBucket15m() + 96;
            List<LegMatch> matches = context.loadLegMatches(
                    definition.underlying(),
                    leg.optionType(),
                    cohort.moneynessBucket(),
                    bucketLo,
                    bucketHi
            );
            if (matches.isEmpty()) {
                return List.of();
            }
            LocalDate legAnchor = matches.stream()
                    .map(item -> item.exchangeTs().atZone(IST).toLocalDate())
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            if (legAnchor != null && (anchorDate == null || legAnchor.isAfter(anchorDate))) {
                anchorDate = legAnchor;
            }
            legMaps.add(aggregateLegMatches(matches, context.expiryValuesFor(matches), timeframe, customFrom, customTo, anchorDate, leg.side()));
        }
        if (anchorDate == null) {
            return List.of();
        }
        List<Map<String, AggregatedLegPoint>> alignedMaps = new ArrayList<>();
        for (int index = 0; index < definition.legs().size(); index++) {
            StrategyStructureDefinition.StrategyLeg leg = definition.legs().get(index);
            CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                    definition.underlying(),
                    leg.optionType(),
                    definition.spot(),
                    leg.strike(),
                    definition.dte()
            );
            int bucketLo = Math.max(0, cohort.timeBucket15m() - 96);
            int bucketHi = cohort.timeBucket15m() + 96;
            List<LegMatch> matches = context.loadLegMatches(
                    definition.underlying(),
                    leg.optionType(),
                    cohort.moneynessBucket(),
                    bucketLo,
                    bucketHi
            );
            alignedMaps.add(aggregateLegMatches(matches, context.expiryValuesFor(matches), timeframe, customFrom, customTo, anchorDate, leg.side()));
        }

        Set<String> commonKeys = alignedMaps.get(0).keySet();
        for (int index = 1; index < alignedMaps.size(); index++) {
            commonKeys = commonKeys.stream()
                    .filter(alignedMaps.get(index)::containsKey)
                    .collect(Collectors.toSet());
        }
        if (commonKeys.isEmpty()) {
            return List.of();
        }

        List<StrategyAnalysisCalculator.StrategyScenario> scenarios = new ArrayList<>();
        for (String key : commonKeys) {
            double totalEntryPremium = 0;
            double expiryValue = 0;
            double selectedPnl = 0;
            for (Map<String, AggregatedLegPoint> legMap : alignedMaps) {
                AggregatedLegPoint point = legMap.get(key);
                totalEntryPremium += point.entryAverage();
                expiryValue += point.expiryAverage();
                double legPnl = isShort(point.side())
                        ? point.entryAverage() - point.expiryAverage()
                        : point.expiryAverage() - point.entryAverage();
                selectedPnl += legPnl;
            }
            LocalDate tradeDate = alignedMaps.get(0).get(key).tradeDate();
            LocalDate expiryDate = alignedMaps.get(0).get(key).expiryDate();
            double buyerPnl = isBuyer(definition.orientation()) ? selectedPnl : -selectedPnl;
            double sellerPnl = -buyerPnl;
            scenarios.add(new StrategyAnalysisCalculator.StrategyScenario(
                    tradeDate,
                    expiryDate,
                    totalEntryPremium,
                    expiryValue,
                    selectedPnl,
                    buyerPnl,
                    sellerPnl
            ));
        }

        return scenarios.stream()
                .sorted(Comparator.comparing(StrategyAnalysisCalculator.StrategyScenario::tradeDate))
                .toList();
    }

    private Map<String, AggregatedLegPoint> aggregateLegMatches(
            List<LegMatch> matches,
            Map<String, Double> expiryValues,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo,
            LocalDate anchorDate,
            String side
    ) {
        DateRange range = resolveRange(timeframe, customFrom, customTo, anchorDate);
        Map<String, AggregateBucket> aggregates = new TreeMap<>();
        for (LegMatch match : matches) {
            Double expiry = expiryValues.get(match.instrumentId());
            if (expiry == null) {
                continue;
            }
            LocalDate tradeDate = match.exchangeTs().atZone(IST).toLocalDate();
            if (tradeDate.isBefore(range.from()) || tradeDate.isAfter(range.to())) {
                continue;
            }
            LocalDate expiryDate = match.expiryTs().atZone(IST).toLocalDate();
            String key = "%s|%s".formatted(tradeDate, expiryDate);
            aggregates.computeIfAbsent(key, ignored -> new AggregateBucket(tradeDate, expiryDate, side))
                    .add(match.entryPrice(), expiry);
        }
        Map<String, AggregatedLegPoint> result = new LinkedHashMap<>();
        for (Map.Entry<String, AggregateBucket> entry : aggregates.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toPoint());
        }
        return result;
    }

    private static double currentTotalPremium(StrategyStructureDefinition definition) {
        return definition.legs().stream()
                .map(StrategyStructureDefinition.StrategyLeg::entryPrice)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
    }

    private List<StrategyStructureDefinition> candidateDefinitions(StrategyStructureDefinition selected) {
        BigDecimal bucket = BigDecimal.valueOf("BANKNIFTY".equalsIgnoreCase(selected.underlying()) ? 100 : 50);
        BigDecimal firstStrike = selected.legs().isEmpty() ? roundToBucket(selected.spot(), bucket) : selected.legs().get(0).strike();
        BigDecimal atmStrike = roundToBucket(selected.spot(), bucket);
        BigDecimal wing = selected.legs().stream()
                .map(leg -> leg.strike().subtract(selected.spot()).abs())
                .max(Comparator.naturalOrder())
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .orElse(bucket);
        BigDecimal upperWing = roundToBucket(atmStrike.add(wing), bucket);
        BigDecimal lowerWing = roundToBucket(atmStrike.subtract(wing), bucket);
        BigDecimal fartherUpperWing = roundToBucket(atmStrike.add(wing.multiply(BigDecimal.valueOf(2))), bucket);
        BigDecimal fartherLowerWing = roundToBucket(atmStrike.subtract(wing.multiply(BigDecimal.valueOf(2))), bucket);

        List<StrategyStructureDefinition> definitions = new ArrayList<>();
        definitions.add(selected);
        definitions.add(new StrategyStructureDefinition(
                "SINGLE_OPTION",
                "BUYER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(new StrategyStructureDefinition.StrategyLeg("Single leg", defaultOptionType(selected), "LONG", firstStrike, defaultEntryPrice(selected, 0)))
        ));
        definitions.add(new StrategyStructureDefinition(
                "LONG_STRADDLE",
                "BUYER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("ATM call", "CE", "LONG", atmStrike, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("ATM put", "PE", "LONG", atmStrike, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "SHORT_STRADDLE",
                "SELLER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("ATM call", "CE", "SHORT", atmStrike, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("ATM put", "PE", "SHORT", atmStrike, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "LONG_STRANGLE",
                "BUYER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("OTM put", "PE", "LONG", lowerWing, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("OTM call", "CE", "LONG", upperWing, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "SHORT_STRANGLE",
                "SELLER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("OTM put", "PE", "SHORT", lowerWing, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("OTM call", "CE", "SHORT", upperWing, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "BULL_CALL_SPREAD",
                "BUYER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("Long call", "CE", "LONG", atmStrike, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("Short call", "CE", "SHORT", upperWing, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "BEAR_PUT_SPREAD",
                "BUYER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("Long put", "PE", "LONG", atmStrike, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("Short put", "PE", "SHORT", lowerWing, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "IRON_CONDOR",
                "SELLER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("Long put wing", "PE", "LONG", fartherLowerWing, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("Short put", "PE", "SHORT", lowerWing, defaultEntryPrice(selected, 1)),
                        new StrategyStructureDefinition.StrategyLeg("Short call", "CE", "SHORT", upperWing, defaultEntryPrice(selected, 2)),
                        new StrategyStructureDefinition.StrategyLeg("Long call wing", "CE", "LONG", fartherUpperWing, defaultEntryPrice(selected, 3))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "IRON_BUTTERFLY",
                "SELLER",
                selected.underlying(),
                selected.expiryType(),
                selected.dte(),
                selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("Long put wing", "PE", "LONG", lowerWing, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("Short put", "PE", "SHORT", atmStrike, defaultEntryPrice(selected, 1)),
                        new StrategyStructureDefinition.StrategyLeg("Short call", "CE", "SHORT", atmStrike, defaultEntryPrice(selected, 2)),
                        new StrategyStructureDefinition.StrategyLeg("Long call wing", "CE", "LONG", upperWing, defaultEntryPrice(selected, 3))
                )
        ));
        return definitions.stream()
                .collect(Collectors.toMap(
                        item -> item.mode() + "|" + item.orientation(),
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    private static String defaultOptionType(StrategyStructureDefinition definition) {
        return definition.legs().isEmpty() ? "CE" : definition.legs().get(0).optionType();
    }

    private static BigDecimal defaultEntryPrice(StrategyStructureDefinition definition, int index) {
        if (definition.legs().isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (index < definition.legs().size()) {
            return definition.legs().get(index).entryPrice();
        }
        return definition.legs().get(definition.legs().size() - 1).entryPrice();
    }

    private static BigDecimal roundToBucket(BigDecimal value, BigDecimal bucket) {
        double rounded = Math.round(value.doubleValue() / bucket.doubleValue()) * bucket.doubleValue();
        return BigDecimal.valueOf(rounded);
    }

    private static boolean isShort(String side) {
        return "SHORT".equalsIgnoreCase(side);
    }

    private static boolean isSeller(String orientation) {
        return "SELLER".equalsIgnoreCase(orientation);
    }

    private static boolean isBuyer(String orientation) {
        return !isSeller(orientation);
    }

    private static String labelFor(String mode) {
        return switch (mode) {
            case "LONG_STRADDLE" -> "Long Straddle";
            case "SHORT_STRADDLE" -> "Short Straddle";
            case "LONG_STRANGLE" -> "Long Strangle";
            case "SHORT_STRANGLE" -> "Short Strangle";
            case "BULL_CALL_SPREAD" -> "Bull Call Spread";
            case "BEAR_PUT_SPREAD" -> "Bear Put Spread";
            case "IRON_CONDOR" -> "Iron Condor";
            case "IRON_BUTTERFLY" -> "Iron Butterfly";
            case "CUSTOM_MULTI_LEG" -> "Custom Multi-Leg";
            default -> "Single Option";
        };
    }

    private static DateRange resolveRange(String timeframe, LocalDate customFrom, LocalDate customTo, LocalDate anchorDate) {
        String normalized = timeframe == null || timeframe.isBlank() ? "1Y" : timeframe.toUpperCase(Locale.ROOT);
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

    private record AggregatedLegPoint(
            LocalDate tradeDate,
            LocalDate expiryDate,
            double entryAverage,
            double expiryAverage,
            String side
    ) {
    }

    private static final class AggregateBucket {
        private final LocalDate tradeDate;
        private final LocalDate expiryDate;
        private final String side;
        private double entrySum;
        private double expirySum;
        private int count;

        private AggregateBucket(LocalDate tradeDate, LocalDate expiryDate, String side) {
            this.tradeDate = tradeDate;
            this.expiryDate = expiryDate;
            this.side = side;
        }

        private void add(double entryPrice, double expiryPrice) {
            entrySum += entryPrice;
            expirySum += expiryPrice;
            count += 1;
        }

        private AggregatedLegPoint toPoint() {
            if (count == 0) {
                return new AggregatedLegPoint(tradeDate, expiryDate, 0, 0, side);
            }
            return new AggregatedLegPoint(tradeDate, expiryDate, entrySum / count, expirySum / count, side);
        }
    }

    private static final class HistoricalContext {
        private final Connection connection;
        private final Map<String, List<LegMatch>> legCache = new LinkedHashMap<>();
        private final Map<String, Double> expiryValueCache = new LinkedHashMap<>();

        private HistoricalContext(Connection connection) {
            this.connection = connection;
        }

        private List<LegMatch> loadLegMatches(
                String underlying,
                String optionType,
                int moneynessBucket,
                int bucketLo,
                int bucketHi
        ) throws SQLException {
            String cacheKey = "%s|%s|%d|%d|%d".formatted(underlying, optionType, moneynessBucket, bucketLo, bucketHi);
            List<LegMatch> cached = legCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            List<LegMatch> matches = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(COHORT_SQL)) {
                ps.setString(1, underlying);
                ps.setString(2, optionType);
                ps.setInt(3, bucketLo);
                ps.setInt(4, bucketHi);
                ps.setInt(5, moneynessBucket);
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
            legCache.put(cacheKey, matches);
            return matches;
        }

        private Map<String, Double> expiryValuesFor(List<LegMatch> matches) throws SQLException {
            Set<String> needed = matches.stream()
                    .map(LegMatch::instrumentId)
                    .filter(id -> !expiryValueCache.containsKey(id))
                    .collect(Collectors.toSet());
            if (!needed.isEmpty()) {
                loadExpiryValues(needed);
            }
            Map<String, Double> values = new LinkedHashMap<>();
            for (LegMatch match : matches) {
                Double expiryValue = expiryValueCache.get(match.instrumentId());
                if (expiryValue != null) {
                    values.put(match.instrumentId(), expiryValue);
                }
            }
            return values;
        }

        private void loadExpiryValues(Set<String> instrumentIds) throws SQLException {
            if (instrumentIds.isEmpty()) {
                return;
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

            try (PreparedStatement ps = connection.prepareStatement(sql.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    expiryValueCache.put(rs.getString("instrument_id"), rs.getDouble("last_price"));
                }
            }
        }
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
