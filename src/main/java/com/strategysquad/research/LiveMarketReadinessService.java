package com.strategysquad.research;

import com.strategysquad.support.QuestDbConnectionFactory;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes whether today's live market data is sufficient to price and monitor
 * common CE/PE structures such as NIFTY or BANKNIFTY straddles.
 */
public final class LiveMarketReadinessService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration FEED_FRESHNESS_WINDOW = Duration.ofSeconds(120);
    private static final List<String> TRACKED_UNDERLYINGS = List.of("NIFTY", "BANKNIFTY");

    private final DataSource dataSource;
    private final Clock clock;

    public LiveMarketReadinessService(String jdbcUrl) {
        this(new JdbcDataSource(jdbcUrl), Clock.systemUTC());
    }

    LiveMarketReadinessService(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public LiveMarketReadinessSnapshot load(Instant sessionLastTickTs) {
        Instant generatedAt = clock.instant();
        LocalDate sessionDate = LocalDate.now(clock.withZone(IST));
        Instant sessionStart = sessionDate.atStartOfDay(IST).toInstant();
        Instant sessionEnd = sessionDate.plusDays(1).atStartOfDay(IST).toInstant();

        MarketDataWindow dataWindow;
        try {
            dataWindow = dataSource.load(sessionStart, sessionEnd);
        } catch (SQLException exception) {
            return LiveMarketReadinessSnapshot.unavailable(
                    sessionDate,
                    generatedAt,
                    "Unable to load live market readiness: " + exception.getMessage()
            );
        }

        Map<String, Long> spotCounts = new LinkedHashMap<>();
        for (SpotCount count : dataWindow.spotCounts()) {
            spotCounts.put(count.underlying(), count.tickCount());
        }

        Map<String, Map<String, Long>> optionCounts = new LinkedHashMap<>();
        for (OptionCount count : dataWindow.optionCounts()) {
            optionCounts.computeIfAbsent(count.underlying(), ignored -> new LinkedHashMap<>())
                    .put(count.optionType(), count.tickCount());
        }

        List<UnderlyingReadiness> underlyings = new ArrayList<>(TRACKED_UNDERLYINGS.size());
        for (String underlying : TRACKED_UNDERLYINGS) {
            long spotTickCount = spotCounts.getOrDefault(underlying, 0L);
            Map<String, Long> byType = optionCounts.getOrDefault(underlying, Map.of());
            long callTickCount = byType.getOrDefault("CE", 0L);
            long putTickCount = byType.getOrDefault("PE", 0L);
            underlyings.add(new UnderlyingReadiness(
                    underlying,
                    spotTickCount,
                    callTickCount,
                    putTickCount,
                    spotTickCount > 0 && callTickCount > 0 && putTickCount > 0
            ));
        }

        boolean feedFreshNow = sessionLastTickTs != null
                && !sessionLastTickTs.isAfter(generatedAt)
                && Duration.between(sessionLastTickTs, generatedAt).compareTo(FEED_FRESHNESS_WINDOW) <= 0;
        boolean anyStraddleReadyToday = underlyings.stream().anyMatch(UnderlyingReadiness::straddleReadyToday);
        boolean niftyStraddleReadyToday = readinessFor("NIFTY", underlyings);
        boolean bankNiftyStraddleReadyToday = readinessFor("BANKNIFTY", underlyings);

        return new LiveMarketReadinessSnapshot(
                sessionDate,
                generatedAt,
                true,
                null,
                feedFreshNow,
                anyStraddleReadyToday,
                niftyStraddleReadyToday,
                bankNiftyStraddleReadyToday,
                dataWindow.latestSpotTickTs(),
                dataWindow.latestOptionTickTs(),
                underlyings,
                buildSummary(feedFreshNow, niftyStraddleReadyToday, bankNiftyStraddleReadyToday)
        );
    }

    private static boolean readinessFor(String underlying, List<UnderlyingReadiness> underlyings) {
        for (UnderlyingReadiness item : underlyings) {
            if (underlying.equalsIgnoreCase(item.underlying())) {
                return item.straddleReadyToday();
            }
        }
        return false;
    }

    private static String buildSummary(
            boolean feedFreshNow,
            boolean niftyStraddleReadyToday,
            boolean bankNiftyStraddleReadyToday
    ) {
        String feed = feedFreshNow ? "feed fresh now" : "feed not fresh now";
        String nifty = niftyStraddleReadyToday ? "NIFTY straddle ready" : "NIFTY straddle incomplete";
        String bankNifty = bankNiftyStraddleReadyToday ? "BANKNIFTY straddle ready" : "BANKNIFTY straddle incomplete";
        return String.join("; ", feed, nifty, bankNifty);
    }

    interface DataSource {
        MarketDataWindow load(Instant sessionStart, Instant sessionEnd) throws SQLException;
    }

    static final class JdbcDataSource implements DataSource {
        private static final String SPOT_COUNTS_SQL =
                "SELECT underlying, count() AS tick_count"
                        + " FROM spot_live"
                        + " WHERE exchange_ts >= ? AND exchange_ts < ?"
                        + " GROUP BY underlying";
        private static final String OPTION_COUNTS_SQL =
                "SELECT im.underlying, im.option_type, count() AS tick_count"
                        + " FROM options_live ol"
                        + " JOIN instrument_master im ON im.instrument_id = ol.instrument_id"
                        + " WHERE ol.exchange_ts >= ? AND ol.exchange_ts < ?"
                        + " GROUP BY im.underlying, im.option_type";
        private static final String LATEST_SPOT_SQL =
                "SELECT max(exchange_ts) FROM spot_live WHERE exchange_ts >= ? AND exchange_ts < ?";
        private static final String LATEST_OPTION_SQL =
                "SELECT max(exchange_ts) FROM options_live WHERE exchange_ts >= ? AND exchange_ts < ?";

        private final String jdbcUrl;

        private JdbcDataSource(String jdbcUrl) {
            this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        }

        @Override
        public MarketDataWindow load(Instant sessionStart, Instant sessionEnd) throws SQLException {
            try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl)) {
                return new MarketDataWindow(
                        loadSpotCounts(connection, sessionStart, sessionEnd),
                        loadOptionCounts(connection, sessionStart, sessionEnd),
                        scalarInstant(connection, LATEST_SPOT_SQL, sessionStart, sessionEnd),
                        scalarInstant(connection, LATEST_OPTION_SQL, sessionStart, sessionEnd)
                );
            }
        }

        private static List<SpotCount> loadSpotCounts(
                Connection connection,
                Instant sessionStart,
                Instant sessionEnd
        ) throws SQLException {
            List<SpotCount> counts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SPOT_COUNTS_SQL)) {
                bindWindow(statement, sessionStart, sessionEnd);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        counts.add(new SpotCount(
                                rs.getString("underlying"),
                                rs.getLong("tick_count")
                        ));
                    }
                }
            }
            return counts;
        }

        private static List<OptionCount> loadOptionCounts(
                Connection connection,
                Instant sessionStart,
                Instant sessionEnd
        ) throws SQLException {
            List<OptionCount> counts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(OPTION_COUNTS_SQL)) {
                bindWindow(statement, sessionStart, sessionEnd);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        counts.add(new OptionCount(
                                rs.getString("underlying"),
                                rs.getString("option_type"),
                                rs.getLong("tick_count")
                        ));
                    }
                }
            }
            return counts;
        }

        private static Instant scalarInstant(
                Connection connection,
                String sql,
                Instant sessionStart,
                Instant sessionEnd
        ) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindWindow(statement, sessionStart, sessionEnd);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Timestamp value = rs.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                }
            }
        }

        private static void bindWindow(
                PreparedStatement statement,
                Instant sessionStart,
                Instant sessionEnd
        ) throws SQLException {
            statement.setTimestamp(1, Timestamp.from(sessionStart));
            statement.setTimestamp(2, Timestamp.from(sessionEnd));
        }
    }

    record MarketDataWindow(
            List<SpotCount> spotCounts,
            List<OptionCount> optionCounts,
            Instant latestSpotTickTs,
            Instant latestOptionTickTs
    ) {
    }

    record SpotCount(String underlying, long tickCount) {
    }

    record OptionCount(String underlying, String optionType, long tickCount) {
    }

    public record UnderlyingReadiness(
            String underlying,
            long spotTickCount,
            long callTickCount,
            long putTickCount,
            boolean straddleReadyToday
    ) {
    }

    public record LiveMarketReadinessSnapshot(
            LocalDate sessionDate,
            Instant generatedAt,
            boolean available,
            String unavailableReason,
            boolean feedFreshNow,
            boolean anyStraddleReadyToday,
            boolean niftyStraddleReadyToday,
            boolean bankNiftyStraddleReadyToday,
            Instant latestSpotTickTs,
            Instant latestOptionTickTs,
            List<UnderlyingReadiness> underlyings,
            String summary
    ) {
        public LiveMarketReadinessSnapshot {
            underlyings = underlyings == null ? List.of() : List.copyOf(underlyings);
        }

        static LiveMarketReadinessSnapshot unavailable(
                LocalDate sessionDate,
                Instant generatedAt,
                String reason
        ) {
            return new LiveMarketReadinessSnapshot(
                    sessionDate,
                    generatedAt,
                    false,
                    reason,
                    false,
                    false,
                    false,
                    false,
                    null,
                    null,
                    List.of(),
                    reason
            );
        }
    }
}
