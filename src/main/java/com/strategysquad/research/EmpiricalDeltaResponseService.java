package com.strategysquad.research;

import com.strategysquad.support.QuestDbConnectionFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Calculates empirical live delta-response from stored option/underlying ticks.
 *
 * <p>Pairing rule is deterministic: each option tick is matched with the nearest
 * underlying tick at or before the option timestamp, never a future tick.
 */
public final class EmpiricalDeltaResponseService {

    private static final ZoneId EXCHANGE_ZONE = MarketSessionStateResolver.EXCHANGE_ZONE;
    private static final int OUTPUT_SCALE = 6;

    private final TickDataSource tickDataSource;
    private final DeltaResponseConfig config;
    private final Clock clock;

    public EmpiricalDeltaResponseService(String jdbcUrl) {
        this(
                new JdbcTickDataSource(jdbcUrl),
                DeltaResponseConfig.fromSystemProperties(),
                Clock.system(EXCHANGE_ZONE)
        );
    }

    EmpiricalDeltaResponseService(
            TickDataSource tickDataSource,
            DeltaResponseConfig config,
            Clock clock
    ) {
        this.tickDataSource = Objects.requireNonNull(tickDataSource, "tickDataSource must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Returns the signed underlying price move over the last 2 minutes, expressed as a direction
     * signal.  Uses first-vs-last spot tick in the window — not an option-derived proxy.
     *
     * <p>Returns {@code NEUTRAL} when fewer than 2 ticks are available or the price change is
     * within the configured {@code minUnderlyingMovePoints} noise floor.
     */
    public DeltaAdjustmentService.UnderlyingDirection loadUnderlyingDirection(
            String underlying, Instant asOf) throws SQLException {
        Objects.requireNonNull(underlying, "underlying must not be null");
        Instant resolvedAsOf = asOf == null ? clock.instant() : asOf;
        Instant from = resolvedAsOf.minus(Duration.ofMinutes(2));
        List<PriceTick> ticks = tickDataSource.loadUnderlyingTicks(underlying, from, resolvedAsOf);
        if (ticks.size() < 2) {
            return DeltaAdjustmentService.UnderlyingDirection.NEUTRAL;
        }
        double signedMove = ticks.get(ticks.size() - 1).price() - ticks.get(0).price();
        if (signedMove >  config.minUnderlyingMovePoints()) return DeltaAdjustmentService.UnderlyingDirection.BULLISH;
        if (signedMove < -config.minUnderlyingMovePoints()) return DeltaAdjustmentService.UnderlyingDirection.BEARISH;
        return DeltaAdjustmentService.UnderlyingDirection.NEUTRAL;
    }

    public Map<String, ContractDeltaResponse> loadResponses(
            String underlying,
            List<CanonicalPriceResolverService.InstrumentKey> contractKeys,
            Instant asOfTime
    ) throws SQLException {
        Objects.requireNonNull(underlying, "underlying must not be null");
        if (contractKeys == null || contractKeys.isEmpty()) {
            return Map.of();
        }

        Instant resolvedAsOf = asOfTime == null ? clock.instant() : asOfTime;
        LocalDate tradeDate = resolvedAsOf.atZone(EXCHANGE_ZONE).toLocalDate();
        Instant sessionStart = tradeDate.atTime(MarketSessionStateResolver.MARKET_OPEN).atZone(EXCHANGE_ZONE).toInstant();

        List<String> instrumentIds = contractKeys.stream()
                .map(CanonicalPriceResolverService.InstrumentKey::instrumentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }

        Instant spotStart = sessionStart.minusSeconds(config.maxUnderlyingStalenessSeconds());
        List<PriceTick> underlyingTicks = tickDataSource.loadUnderlyingTicks(underlying, spotStart, resolvedAsOf);
        Map<String, List<PriceTick>> optionTicksByInstrument = tickDataSource.loadOptionTicks(instrumentIds, sessionStart, resolvedAsOf);

        Map<String, ContractDeltaResponse> results = new LinkedHashMap<>();
        for (String instrumentId : instrumentIds) {
            List<PairedObservation> paired = pairObservations(
                    optionTicksByInstrument.getOrDefault(instrumentId, List.of()),
                    underlyingTicks
            );
            results.put(instrumentId, computeResponse(instrumentId, paired, resolvedAsOf));
        }
        return results;
    }

    private ContractDeltaResponse computeResponse(
            String instrumentId,
            List<PairedObservation> paired,
            Instant calculatedAt
    ) {
        if (paired.isEmpty()) {
            return new ContractDeltaResponse(instrumentId, null, null, null, calculatedAt);
        }
        Instant anchor = paired.get(paired.size() - 1).optionTs();
        DeltaWindowResponse twoMinute = computeWindow(
                paired,
                anchor.minus(Duration.ofMinutes(2)),
                config.minObservations2m(),
                calculatedAt
        );
        DeltaWindowResponse fiveMinute = computeWindow(
                paired,
                anchor.minus(Duration.ofMinutes(5)),
                config.minObservations5m(),
                calculatedAt
        );
        DeltaWindowResponse startOfDay = computeWindow(
                paired,
                null,
                config.minObservationsSod(),
                calculatedAt
        );
        return new ContractDeltaResponse(instrumentId, twoMinute, fiveMinute, startOfDay, calculatedAt);
    }

    private DeltaWindowResponse computeWindow(
            List<PairedObservation> paired,
            Instant windowStartInclusive,
            int minObservations,
            Instant calculatedAt
    ) {
        List<PairedObservation> window = new ArrayList<>();
        for (PairedObservation observation : paired) {
            if (windowStartInclusive == null || !observation.optionTs().isBefore(windowStartInclusive)) {
                window.add(observation);
            }
        }
        if (window.size() < minObservations) {
            return null;
        }

        double minUnderlying = Double.POSITIVE_INFINITY;
        double maxUnderlying = Double.NEGATIVE_INFINITY;
        double sumX = 0.0d;
        double sumY = 0.0d;
        double sumXX = 0.0d;
        double sumXY = 0.0d;
        for (PairedObservation observation : window) {
            double x = observation.underlyingPrice();
            double y = observation.optionPrice();
            minUnderlying = Math.min(minUnderlying, x);
            maxUnderlying = Math.max(maxUnderlying, x);
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;
        }

        double underlyingMove = maxUnderlying - minUnderlying;
        if (underlyingMove < config.minUnderlyingMovePoints()) {
            return null;
        }

        int n = window.size();
        double varianceDenominator = sumXX - ((sumX * sumX) / n);
        if (Math.abs(varianceDenominator) < config.minUnderlyingVariance()) {
            return null;
        }
        double covarianceNumerator = sumXY - ((sumX * sumY) / n);
        double slope = covarianceNumerator / varianceDenominator;

        return new DeltaWindowResponse(
                scale(slope),
                n,
                scale(underlyingMove),
                calculatedAt
        );
    }

    private List<PairedObservation> pairObservations(
            List<PriceTick> optionTicks,
            List<PriceTick> underlyingTicks
    ) {
        if (optionTicks.isEmpty() || underlyingTicks.isEmpty()) {
            return List.of();
        }
        List<PairedObservation> paired = new ArrayList<>();
        int underlyingIndex = 0;
        PriceTick latestUnderlying = null;
        for (PriceTick optionTick : optionTicks) {
            while (underlyingIndex < underlyingTicks.size()
                    && !underlyingTicks.get(underlyingIndex).ts().isAfter(optionTick.ts())) {
                latestUnderlying = underlyingTicks.get(underlyingIndex);
                underlyingIndex++;
            }
            if (latestUnderlying == null) {
                continue;
            }
            long stalenessMillis = Duration.between(latestUnderlying.ts(), optionTick.ts()).toMillis();
            if (stalenessMillis < 0L || stalenessMillis > (config.maxUnderlyingStalenessSeconds() * 1000L)) {
                continue;
            }
            paired.add(new PairedObservation(
                    optionTick.ts(),
                    latestUnderlying.price(),
                    optionTick.price(),
                    latestUnderlying.ts()
            ));
        }
        return paired;
    }

    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    public record DeltaWindowResponse(
            BigDecimal slope,
            int observationCount,
            BigDecimal underlyingMove,
            Instant calculatedAt
    ) {
    }

    public record ContractDeltaResponse(
            String instrumentId,
            DeltaWindowResponse deltaResponse2m,
            DeltaWindowResponse deltaResponse5m,
            DeltaWindowResponse deltaResponseSod,
            Instant calculatedAt
    ) {
    }

    record PriceTick(Instant ts, double price) {
    }

    private record PairedObservation(
            Instant optionTs,
            double underlyingPrice,
            double optionPrice,
            Instant matchedUnderlyingTs
    ) {
    }

    interface TickDataSource {
        List<PriceTick> loadUnderlyingTicks(String underlying, Instant fromInclusive, Instant toInclusive) throws SQLException;

        Map<String, List<PriceTick>> loadOptionTicks(List<String> instrumentIds, Instant fromInclusive, Instant toInclusive) throws SQLException;
    }

    static final class JdbcTickDataSource implements TickDataSource {
        private static final String SELECT_SPOT_TICKS_SQL =
                "SELECT exchange_ts, last_price"
                        + " FROM spot_live"
                        + " WHERE underlying = ?"
                        + "   AND exchange_ts >= ?"
                        + "   AND exchange_ts <= ?"
                        + "   AND last_price IS NOT NULL"
                        + " ORDER BY exchange_ts ASC";

        private final String jdbcUrl;

        JdbcTickDataSource(String jdbcUrl) {
            this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        }

        @Override
        public List<PriceTick> loadUnderlyingTicks(String underlying, Instant fromInclusive, Instant toInclusive) throws SQLException {
            List<PriceTick> ticks = new ArrayList<>();
            try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(SELECT_SPOT_TICKS_SQL)) {
                statement.setString(1, underlying);
                statement.setTimestamp(2, Timestamp.from(fromInclusive));
                statement.setTimestamp(3, Timestamp.from(toInclusive));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        double price = rs.getDouble("last_price");
                        if (price <= 0.0d) {
                            continue;
                        }
                        ticks.add(new PriceTick(
                                rs.getTimestamp("exchange_ts").toInstant(),
                                price
                        ));
                    }
                }
            }
            return ticks;
        }

        @Override
        public Map<String, List<PriceTick>> loadOptionTicks(
                List<String> instrumentIds,
                Instant fromInclusive,
                Instant toInclusive
        ) throws SQLException {
            if (instrumentIds.isEmpty()) {
                return Map.of();
            }
            String placeholders = instrumentIds.stream().map(ignored -> "?").collect(Collectors.joining(","));
            String sql =
                    "SELECT instrument_id, exchange_ts, last_price"
                            + " FROM options_live"
                            + " WHERE instrument_id IN (" + placeholders + ")"
                            + "   AND exchange_ts >= ?"
                            + "   AND exchange_ts <= ?"
                            + "   AND last_price IS NOT NULL"
                            + " ORDER BY instrument_id ASC, exchange_ts ASC";

            Map<String, List<PriceTick>> ticksByInstrument = new HashMap<>();
            try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameterIndex = 1;
                for (String instrumentId : instrumentIds) {
                    statement.setString(parameterIndex++, instrumentId);
                }
                statement.setTimestamp(parameterIndex++, Timestamp.from(fromInclusive));
                statement.setTimestamp(parameterIndex, Timestamp.from(toInclusive));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        double price = rs.getDouble("last_price");
                        if (price <= 0.0d) {
                            continue;
                        }
                        ticksByInstrument.computeIfAbsent(rs.getString("instrument_id"), ignored -> new ArrayList<>())
                                .add(new PriceTick(
                                        rs.getTimestamp("exchange_ts").toInstant(),
                                        price
                                ));
                    }
                }
            }

            for (String instrumentId : instrumentIds) {
                ticksByInstrument.computeIfAbsent(instrumentId, ignored -> Collections.emptyList());
            }
            return ticksByInstrument;
        }
    }

    public record DeltaResponseConfig(
            int minObservations2m,
            int minObservations5m,
            int minObservationsSod,
            long maxUnderlyingStalenessSeconds,
            double minUnderlyingMovePoints,
            double minUnderlyingVariance
    ) {
        public static DeltaResponseConfig fromSystemProperties() {
            return new DeltaResponseConfig(
                    intProperty("strategysquad.delta.minObservations2m", 4),
                    intProperty("strategysquad.delta.minObservations5m", 8),
                    intProperty("strategysquad.delta.minObservationsSod", 20),
                    longProperty("strategysquad.delta.maxUnderlyingStalenessSeconds", 5L),
                    doubleProperty("strategysquad.delta.minUnderlyingMovePoints", 0.50d),
                    doubleProperty("strategysquad.delta.minUnderlyingVariance", 0.01d)
            );
        }

        private static int intProperty(String key, int defaultValue) {
            String value = System.getProperty(key);
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
        }

        private static long longProperty(String key, long defaultValue) {
            String value = System.getProperty(key);
            return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
        }

        private static double doubleProperty(String key, double defaultValue) {
            String value = System.getProperty(key);
            return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value.trim());
        }
    }
}
