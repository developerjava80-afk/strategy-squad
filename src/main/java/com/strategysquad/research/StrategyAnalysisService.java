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
 * Derives canonical raw metrics and converts them into economic metrics for downstream consumers.
 */
public class StrategyAnalysisService {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "quest";
    private static final ZoneId IST = ZoneId.of("Asia/Calcutta");
    private static final Pattern INSTRUMENT_ID_PATTERN = Pattern.compile("INS_[A-Z]+_\\d{8}_\\d+_[A-Z]+");
    private static final String COHORT_SQL = """
            SELECT oe.instrument_id, oe.exchange_ts, oe.option_type, oe.strike, oe.last_price, oe.expiry_date, oe.moneyness_bucket
            FROM options_enriched oe
            JOIN instrument_master im ON im.instrument_id = oe.instrument_id
            WHERE oe.underlying = ?
              AND im.expiry_type = ?
              AND oe.option_type = ?
              AND oe.time_bucket_15m BETWEEN ? AND ?
              AND oe.moneyness_bucket = ?
            ORDER BY exchange_ts
            """;
    private static final String EXACT_COHORT_SQL = """
            SELECT oe.instrument_id, oe.exchange_ts, oe.option_type, oe.strike, oe.last_price, oe.expiry_date, oe.moneyness_bucket
            FROM options_enriched oe
            JOIN instrument_master im ON im.instrument_id = oe.instrument_id
            WHERE oe.underlying = ?
              AND im.expiry_type = ?
              AND oe.option_type = ?
              AND oe.time_bucket_15m BETWEEN ? AND ?
              AND oe.moneyness_bucket = ?
              AND oe.strike >= ?
              AND oe.strike <= ?
            ORDER BY exchange_ts
            """;
    private static final double STRIKE_BAND_HALF_WIDTH = 25.0d;

    private final String jdbcUrl;

    public StrategyAnalysisService() {
        this(DEFAULT_JDBC_URL);
    }

    public StrategyAnalysisService(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public EconomicMetrics loadSnapshot(
            StrategyStructureDefinition definition,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo
    ) throws SQLException {
        if (definition.dte() < 0 || definition.dte() > 365) {
            throw new IllegalArgumentException("DTE must be between 0 and 365");
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD)) {
            HistoricalContext context = new HistoricalContext(connection);
            RawStrategyMetrics raw = buildRawMetrics(definition, timeframe, customFrom, customTo, context);
            EconomicMetrics.RecommendationContext recommendation = buildRecommendationContext(
                    definition,
                    timeframe,
                    customFrom,
                    customTo,
                    context
            );
            return EconomicMetricsTransformer.transform(raw, recommendation);
        }
    }

    private RawStrategyMetrics buildRawMetrics(
            StrategyStructureDefinition definition,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo,
            HistoricalContext context
    ) throws SQLException {
        List<StrategyAnalysisCalculator.StrategyScenario> scenarios = analyzeDefinition(
                definition,
                timeframe,
                customFrom,
                customTo,
                context,
                StructureMatchMode.CONTEXTUAL_ANALOG
        );
        double currentNetPremium = currentNetPremium(definition);
        double historicalBest = scenarios.stream().mapToDouble(StrategyAnalysisCalculator.StrategyScenario::selectedPnl).max().orElse(0);
        double historicalWorst = scenarios.stream().mapToDouble(StrategyAnalysisCalculator.StrategyScenario::selectedPnl).min().orElse(0);
        StrategyAnalysisCalculator.TheoreticalBoundsInput boundsInput = buildBoundsInput(definition, historicalBest, historicalWorst);
        return StrategyAnalysisCalculator.calculate(
                definition.mode(),
                definition.orientation(),
                timeframe,
                currentNetPremium,
                scenarios,
                boundsInput
        );
    }

    private EconomicMetrics.RecommendationContext buildRecommendationContext(
            StrategyStructureDefinition selectedDefinition,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo,
            HistoricalContext context
    ) throws SQLException {
        List<StrategyStructureDefinition> candidates = candidateDefinitions(selectedDefinition);
        List<CandidateScore> scored = new ArrayList<>();
        for (StrategyStructureDefinition candidate : candidates) {
            RawStrategyMetrics rawCandidate = buildRawMetrics(candidate, timeframe, customFrom, customTo, context);
            if (rawCandidate.observationCount() == 0) {
                continue;
            }
            EconomicMetrics.RecommendationCandidate economicCandidate =
                    EconomicMetricsTransformer.toRecommendationCandidate(candidate.mode(), candidate.orientation(), rawCandidate);
            double normalizedAttractiveness = economicCandidate.economicPercentile() / 100.0d;
            double normalizedWinRate = economicCandidate.winRatePct() / 100.0d;
            double sampleSupport = Math.min(1.0d, economicCandidate.observationCount() / 120.0d);
            double downsidePenalty = Math.min(1.0d, economicCandidate.downsideSeverityPoints() / 200.0d);
            double pnlScore = economicCandidate.averagePnlPoints();
            double lowSamplePenalty = economicCandidate.lowSampleWarning().isBlank() ? 0.0d : 0.10d;
            double score = (normalizedAttractiveness * 0.28d)
                    + (pnlScore * 0.015d)
                    + (normalizedWinRate * 0.18d)
                    + (sampleSupport * 0.18d)
                    + ((1.0d - downsidePenalty) * 0.16d)
                    - lowSamplePenalty;
            scored.add(new CandidateScore(economicCandidate, score));
        }
        if (scored.isEmpty()) {
            return EconomicMetricsTransformer.emptyRecommendationContext();
        }

        List<EconomicMetrics.RecommendationCandidate> ranked = scored.stream()
                .sorted(Comparator.comparingDouble(CandidateScore::score).reversed())
                .map(item -> new EconomicMetrics.RecommendationCandidate(
                        item.candidate().mode(),
                        item.candidate().orientation(),
                        item.candidate().title(),
                        item.score(),
                        item.candidate().observationCount(),
                        item.candidate().lowSampleWarning(),
                        item.candidate().rawPricePercentile(),
                        item.candidate().economicPercentile(),
                        item.candidate().premiumVsAveragePoints(),
                        item.candidate().averagePnlPoints(),
                        item.candidate().winRatePct(),
                        item.candidate().downsideSeverityPoints(),
                        recommendationVerdict(item.candidate(), item.score()),
                        recommendationReason(item.candidate(), item.score())
                ))
                .toList();

        EconomicMetrics.RecommendationCandidate preferred = ranked.get(0);
        EconomicMetrics.RecommendationCandidate alternative = ranked.size() > 1
                ? ranked.get(1)
                : fallbackCandidate(preferred, "No second strategy cleared the confidence bar.");
        EconomicMetrics.RecommendationCandidate avoid = ranked.size() > 2
                ? ranked.get(ranked.size() - 1)
                : fallbackCandidate(preferred, "No clear avoid candidate because only one strategy matched.");

        return new EconomicMetrics.RecommendationContext(
                preferred,
                alternative,
                avoid,
                "Recommendations are deterministic rankings over a controlled candidate set, not a full optimizer."
        );
    }

    private String recommendationVerdict(EconomicMetrics.RecommendationCandidate candidate, double score) {
        if (!candidate.lowSampleWarning().isBlank()) {
            return "Low-confidence candidate";
        }
        if (score >= 0.70d) {
            return "Preferred candidate";
        }
        if (score <= 0.35d) {
            return "Avoid candidate";
        }
        return "Alternative candidate";
    }

    private String recommendationReason(EconomicMetrics.RecommendationCandidate candidate, double score) {
        return "%s. Economic percentile %dth, avg pnl %.2f pts, win rate %.1f%%, downside %.2f pts, score %.2f.%s".formatted(
                candidate.verdict(),
                candidate.economicPercentile(),
                candidate.averagePnlPoints(),
                candidate.winRatePct(),
                candidate.downsideSeverityPoints(),
                score,
                candidate.lowSampleWarning().isBlank() ? "" : " " + candidate.lowSampleWarning()
        );
    }

    private EconomicMetrics.RecommendationCandidate fallbackCandidate(
            EconomicMetrics.RecommendationCandidate candidate,
            String reason
    ) {
        return new EconomicMetrics.RecommendationCandidate(
                candidate.mode(),
                candidate.orientation(),
                candidate.title(),
                candidate.score(),
                candidate.observationCount(),
                candidate.lowSampleWarning(),
                candidate.rawPricePercentile(),
                candidate.economicPercentile(),
                candidate.premiumVsAveragePoints(),
                candidate.averagePnlPoints(),
                candidate.winRatePct(),
                candidate.downsideSeverityPoints(),
                candidate.verdict(),
                reason
        );
    }

    private record CandidateScore(EconomicMetrics.RecommendationCandidate candidate, double score) {
    }

    private List<StrategyAnalysisCalculator.StrategyScenario> analyzeDefinition(
            StrategyStructureDefinition definition,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo,
            HistoricalContext context,
            StructureMatchMode matchMode
    ) throws SQLException {
        if (definition.legs().isEmpty()) {
            return List.of();
        }

        LocalDate anchorDate = null;
        for (StrategyStructureDefinition.StrategyLeg leg : definition.legs()) {
            CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                    definition.underlying(), leg.optionType(), definition.spot(), leg.strike(), definition.dte());
            int bucketLo = Math.max(0, cohort.timeBucket15m() - 96);
            int bucketHi = cohort.timeBucket15m() + 96;
            List<LegMatch> matches = loadMatchesForMode(
                    context,
                    matchMode,
                    definition,
                    leg,
                    cohort,
                    bucketLo,
                    bucketHi
            );
            if (matches.isEmpty()) {
                return List.of();
            }
            LocalDate legAnchor = matches.stream()
                    .map(item -> item.exchangeTs().atZone(IST).toLocalDate())
                    .max(Comparator.naturalOrder()).orElse(null);
            if (legAnchor != null && (anchorDate == null || legAnchor.isAfter(anchorDate))) {
                anchorDate = legAnchor;
            }
        }
        if (anchorDate == null) {
            return List.of();
        }

        List<Map<String, AggregatedLegPoint>> alignedMaps = new ArrayList<>();
        for (StrategyStructureDefinition.StrategyLeg leg : definition.legs()) {
            CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                    definition.underlying(), leg.optionType(), definition.spot(), leg.strike(), definition.dte());
            int bucketLo = Math.max(0, cohort.timeBucket15m() - 96);
            int bucketHi = cohort.timeBucket15m() + 96;
            List<LegMatch> matches = loadMatchesForMode(
                    context,
                    matchMode,
                    definition,
                    leg,
                    cohort,
                    bucketLo,
                    bucketHi
            );
            alignedMaps.add(aggregateLegMatches(matches, context.expiryValuesFor(matches),
                    timeframe, customFrom, customTo, anchorDate, leg.side()));
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
            double netEntryPremium = 0;
            double netExpiryValue = 0;
            double selectedPnl = 0;
            for (Map<String, AggregatedLegPoint> legMap : alignedMaps) {
                AggregatedLegPoint point = legMap.get(key);
                double sign = isShort(point.side()) ? -1.0 : 1.0;
                netEntryPremium += sign * point.entryAverage();
                netExpiryValue += sign * point.expiryAverage();
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
                    tradeDate, expiryDate, netEntryPremium, netExpiryValue,
                    selectedPnl, buyerPnl, sellerPnl));
        }

        return scenarios.stream()
                .sorted(Comparator.comparing(StrategyAnalysisCalculator.StrategyScenario::tradeDate))
                .toList();
    }

    private List<LegMatch> loadMatchesForMode(
            HistoricalContext context,
            StructureMatchMode matchMode,
            StrategyStructureDefinition definition,
            StrategyStructureDefinition.StrategyLeg leg,
            CanonicalCohortKey cohort,
            int bucketLo,
            int bucketHi
    ) throws SQLException {
        return switch (matchMode) {
            case EXACT_STRUCTURE -> context.loadLegMatchesExact(
                    definition.underlying(),
                    definition.expiryType(),
                    leg.optionType(),
                    cohort.moneynessBucket(),
                    bucketLo,
                    bucketHi,
                    leg.strike().doubleValue() - STRIKE_BAND_HALF_WIDTH,
                    leg.strike().doubleValue() + STRIKE_BAND_HALF_WIDTH
            );
            case CONTEXTUAL_ANALOG -> context.loadLegMatchesContextual(
                    definition.underlying(),
                    definition.expiryType(),
                    leg.optionType(),
                    cohort.moneynessBucket(),
                    bucketLo,
                    bucketHi
            );
        };
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

    private static double currentNetPremium(StrategyStructureDefinition definition) {
        double net = 0;
        for (StrategyStructureDefinition.StrategyLeg leg : definition.legs()) {
            double sign = isShort(leg.side()) ? -1.0 : 1.0;
            net += sign * leg.entryPrice().doubleValue();
        }
        return net;
    }

    private static StrategyAnalysisCalculator.TheoreticalBoundsInput buildBoundsInput(
            StrategyStructureDefinition definition,
            double historicalBestPnl,
            double historicalWorstPnl
    ) {
        String firstLegSide = definition.legs().isEmpty() ? "LONG" : definition.legs().get(0).side();
        double spreadWidth = computeSpreadWidth(definition);
        return new StrategyAnalysisCalculator.TheoreticalBoundsInput(
                definition.mode(), firstLegSide, spreadWidth,
                historicalBestPnl, historicalWorstPnl);
    }

    private static double computeSpreadWidth(StrategyStructureDefinition definition) {
        List<BigDecimal> strikes = definition.legs().stream()
                .map(StrategyStructureDefinition.StrategyLeg::strike)
                .sorted()
                .toList();
        if (strikes.size() < 2) {
            return 0;
        }
        if ("IRON_CONDOR".equals(definition.mode()) || "IRON_BUTTERFLY".equals(definition.mode())) {
            if (strikes.size() == 4) {
                double putSpread = strikes.get(1).subtract(strikes.get(0)).doubleValue();
                double callSpread = strikes.get(3).subtract(strikes.get(2)).doubleValue();
                return Math.max(putSpread, callSpread);
            }
        }
        return strikes.get(strikes.size() - 1).subtract(strikes.get(0)).doubleValue();
    }

    private List<StrategyStructureDefinition> candidateDefinitions(StrategyStructureDefinition selected) {
        BigDecimal bucket = BigDecimal.valueOf(50);
        BigDecimal firstStrike = selected.legs().isEmpty() ? roundToBucket(selected.spot(), bucket) : selected.legs().get(0).strike();
        BigDecimal atmStrike = roundToBucket(selected.spot(), bucket);
        BigDecimal wing = selected.legs().stream()
                .map(leg -> leg.strike().subtract(selected.spot()).abs())
                .max(Comparator.naturalOrder())
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .orElse(bucket);
        BigDecimal upperWing = roundToBucket(atmStrike.add(wing), bucket);
        BigDecimal lowerWing = roundToBucket(atmStrike.subtract(wing), bucket);
        BigDecimal strangleWing = wing.max(bucket.multiply(BigDecimal.valueOf(2)));
        BigDecimal strangleUpperWing = roundToBucket(atmStrike.add(strangleWing), bucket);
        BigDecimal strangleLowerWing = roundToBucket(atmStrike.subtract(strangleWing), bucket);
        BigDecimal fartherUpperWing = roundToBucket(atmStrike.add(wing.multiply(BigDecimal.valueOf(2))), bucket);
        BigDecimal fartherLowerWing = roundToBucket(atmStrike.subtract(wing.multiply(BigDecimal.valueOf(2))), bucket);

        List<StrategyStructureDefinition> definitions = new ArrayList<>();
        definitions.add(selected);
        definitions.add(new StrategyStructureDefinition(
                "SINGLE_OPTION", "BUYER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
                List.of(new StrategyStructureDefinition.StrategyLeg("Single leg", defaultOptionType(selected), "LONG", firstStrike, defaultEntryPrice(selected, 0)))
        ));
        definitions.add(new StrategyStructureDefinition(
                "LONG_STRADDLE", "BUYER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("ATM call", "CE", "LONG", atmStrike, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("ATM put", "PE", "LONG", atmStrike, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "SHORT_STRADDLE", "SELLER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("ATM call", "CE", "SHORT", atmStrike, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("ATM put", "PE", "SHORT", atmStrike, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "LONG_STRANGLE", "BUYER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("OTM put", "PE", "LONG", strangleLowerWing, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("OTM call", "CE", "LONG", strangleUpperWing, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "SHORT_STRANGLE", "SELLER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("OTM put", "PE", "SHORT", strangleLowerWing, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("OTM call", "CE", "SHORT", strangleUpperWing, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "BULL_CALL_SPREAD", "BUYER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("Long call", "CE", "LONG", atmStrike, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("Short call", "CE", "SHORT", upperWing, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "BEAR_PUT_SPREAD", "BUYER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("Long put", "PE", "LONG", atmStrike, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("Short put", "PE", "SHORT", lowerWing, defaultEntryPrice(selected, 1))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "IRON_CONDOR", "SELLER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg("Long put wing", "PE", "LONG", fartherLowerWing, defaultEntryPrice(selected, 0)),
                        new StrategyStructureDefinition.StrategyLeg("Short put", "PE", "SHORT", lowerWing, defaultEntryPrice(selected, 1)),
                        new StrategyStructureDefinition.StrategyLeg("Short call", "CE", "SHORT", upperWing, defaultEntryPrice(selected, 2)),
                        new StrategyStructureDefinition.StrategyLeg("Long call wing", "CE", "LONG", fartherUpperWing, defaultEntryPrice(selected, 3))
                )
        ));
        definitions.add(new StrategyStructureDefinition(
                "IRON_BUTTERFLY", "SELLER", selected.underlying(), selected.expiryType(), selected.dte(), selected.spot(),
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

        private List<LegMatch> loadLegMatchesContextual(
                String underlying,
                String expiryType,
                String optionType,
                int moneynessBucket,
                int bucketLo,
                int bucketHi
        ) throws SQLException {
            String cacheKey = "%s|%s|%s|%d|%d|%d".formatted(
                    underlying, expiryType, optionType, moneynessBucket, bucketLo, bucketHi);
            List<LegMatch> cached = legCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            List<LegMatch> matches = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(COHORT_SQL)) {
                ps.setString(1, underlying);
                ps.setString(2, expiryType);
                ps.setString(3, optionType);
                ps.setInt(4, bucketLo);
                ps.setInt(5, bucketHi);
                ps.setInt(6, moneynessBucket);
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

        private List<LegMatch> loadLegMatchesExact(
                String underlying,
                String expiryType,
                String optionType,
                int moneynessBucket,
                int bucketLo,
                int bucketHi,
                double strikeLo,
                double strikeHi
        ) throws SQLException {
            String cacheKey = "%s|%s|%s|%d|%d|%d|%.2f|%.2f".formatted(
                    underlying, expiryType, optionType, moneynessBucket, bucketLo, bucketHi, strikeLo, strikeHi);
            List<LegMatch> cached = legCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            List<LegMatch> matches = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(EXACT_COHORT_SQL)) {
                ps.setString(1, underlying);
                ps.setString(2, expiryType);
                ps.setString(3, optionType);
                ps.setInt(4, bucketLo);
                ps.setInt(5, bucketHi);
                ps.setInt(6, moneynessBucket);
                ps.setDouble(7, strikeLo);
                ps.setDouble(8, strikeHi);
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

    private enum StructureMatchMode {
        CONTEXTUAL_ANALOG,
        EXACT_STRUCTURE
    }
}
