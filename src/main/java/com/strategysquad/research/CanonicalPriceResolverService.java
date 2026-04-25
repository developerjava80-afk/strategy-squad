package com.strategysquad.research;

import com.strategysquad.ingestion.kite.KiteOptionCloseQuoteService;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical backend price resolution for live, post-close, and holiday scenarios.
 */
public final class CanonicalPriceResolverService {

    private final PriceDataSource priceDataSource;
    private final LivePriceSource livePriceSource;
    private final Clock clock;

    public CanonicalPriceResolverService(
            String jdbcUrl,
            LiveSessionState liveSessionState,
            KiteOptionCloseQuoteService closeQuoteService
    ) {
        this(
                new JdbcPriceDataSource(jdbcUrl, closeQuoteService),
                new SessionLivePriceSource(liveSessionState),
                Clock.system(MarketSessionStateResolver.EXCHANGE_ZONE)
        );
    }

    CanonicalPriceResolverService(
            PriceDataSource priceDataSource,
            LivePriceSource livePriceSource,
            Clock clock
    ) {
        this.priceDataSource = Objects.requireNonNull(priceDataSource, "priceDataSource must not be null");
        this.livePriceSource = Objects.requireNonNull(livePriceSource, "livePriceSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CanonicalInstrumentPrice getCanonicalInstrumentPrice(
            InstrumentKey instrumentKey,
            MarketSessionStateResolver.SessionState sessionState,
            Instant asOfTime
    ) {
        Objects.requireNonNull(instrumentKey, "instrumentKey must not be null");
        Objects.requireNonNull(sessionState, "sessionState must not be null");
        Instant resolvedAsOf = asOfTime == null ? clock.instant() : asOfTime;
        LocalDate tradeDate = resolvedAsOf.atZone(MarketSessionStateResolver.EXCHANGE_ZONE).toLocalDate();

        Optional<InstrumentKey> resolvedKey = priceDataSource.resolveInstrumentKey(instrumentKey);
        if (resolvedKey.isEmpty()) {
            CanonicalInstrumentPrice unavailable = CanonicalInstrumentPrice.unavailable(
                    instrumentKey.instrumentKey(),
                    sessionState,
                    "unavailable",
                    resolvedAsOf,
                    "Exact contract lookup failed"
            );
            logResolution(unavailable);
            return unavailable;
        }

        InstrumentKey key = resolvedKey.get();
        CanonicalInstrumentPrice resolved;
        switch (sessionState) {
            case LIVE_MARKET -> resolved = resolveLiveMarket(key, sessionState, tradeDate, resolvedAsOf);
            case POST_CLOSE -> resolved = resolvePostClose(key, sessionState, tradeDate, resolvedAsOf);
            case EOD_FINALIZED -> resolved = resolveEodFinalized(key, sessionState, tradeDate, resolvedAsOf);
            case PREOPEN, HOLIDAY_NO_SESSION -> resolved = resolvePriorCloseOnly(key, sessionState, tradeDate, resolvedAsOf);
            default -> resolved = CanonicalInstrumentPrice.unavailable(
                    key.instrumentKey(),
                    sessionState,
                    "unavailable",
                    resolvedAsOf,
                    "No resolution path configured"
            );
        }
        logResolution(resolved);
        return resolved;
    }

    private CanonicalInstrumentPrice resolveLiveMarket(
            InstrumentKey key,
            MarketSessionStateResolver.SessionState sessionState,
            LocalDate tradeDate,
            Instant asOfTime
    ) {
        Optional<ResolvedPrice> live = livePriceSource.loadLivePrice(key);
        if (live.isPresent()) {
            ResolvedPrice price = live.get();
            return new CanonicalInstrumentPrice(
                    key.instrumentKey(),
                    price.price(),
                    PriceType.LIVE_LTP,
                    price.asOf(),
                    price.tradeDate(),
                    sessionState,
                    price.stale(),
                    "live_cache",
                    price.reason()
            );
        }

        Optional<ResolvedPrice> intraday = priceDataSource.loadLatestIntradayPrice(key, tradeDate);
        if (intraday.isPresent()) {
            ResolvedPrice price = intraday.get();
            return new CanonicalInstrumentPrice(
                    key.instrumentKey(),
                    price.price(),
                    PriceType.LIVE_LTP,
                    price.asOf(),
                    price.tradeDate(),
                    sessionState,
                    true,
                    price.source(),
                    "Live cache unavailable; using latest intraday fallback"
            );
        }

        return CanonicalInstrumentPrice.unavailable(
                key.instrumentKey(),
                sessionState,
                "unavailable",
                asOfTime,
                "No live or intraday price available"
        );
    }

    private CanonicalInstrumentPrice resolvePostClose(
            InstrumentKey key,
            MarketSessionStateResolver.SessionState sessionState,
            LocalDate tradeDate,
            Instant asOfTime
    ) {
        Optional<ResolvedPrice> official = priceDataSource.loadOfficialClosePrice(key, tradeDate);
        if (official.isPresent()) {
            ResolvedPrice price = official.get();
            return new CanonicalInstrumentPrice(
                    key.instrumentKey(),
                    price.price(),
                    PriceType.OFFICIAL_CLOSE,
                    price.asOf(),
                    price.tradeDate(),
                    sessionState,
                    false,
                    price.source(),
                    price.reason()
            );
        }

        Optional<ResolvedPrice> preClose = priceDataSource.loadPreCloseLastTrade(key, tradeDate, MarketSessionStateResolver.closeCutoff(tradeDate));
        if (preClose.isPresent()) {
            ResolvedPrice price = preClose.get();
            return new CanonicalInstrumentPrice(
                    key.instrumentKey(),
                    price.price(),
                    PriceType.PRE_CLOSE_LAST_TRADE,
                    price.asOf(),
                    price.tradeDate(),
                    sessionState,
                    false,
                    price.source(),
                    "Official close unavailable; using last valid trade before close"
            );
        }

        return CanonicalInstrumentPrice.unavailable(
                key.instrumentKey(),
                sessionState,
                "unavailable",
                asOfTime,
                "No official close or pre-close trade available"
        );
    }

    private CanonicalInstrumentPrice resolveEodFinalized(
            InstrumentKey key,
            MarketSessionStateResolver.SessionState sessionState,
            LocalDate tradeDate,
            Instant asOfTime
    ) {
        Optional<ResolvedPrice> official = priceDataSource.loadOfficialClosePrice(key, tradeDate);
        if (official.isPresent()) {
            ResolvedPrice price = official.get();
            return new CanonicalInstrumentPrice(
                    key.instrumentKey(),
                    price.price(),
                    PriceType.OFFICIAL_CLOSE,
                    price.asOf(),
                    price.tradeDate(),
                    sessionState,
                    false,
                    price.source(),
                    price.reason()
            );
        }

        Optional<ResolvedPrice> priorClose = priceDataSource.loadPriorClosePrice(key, tradeDate);
        if (priorClose.isPresent()) {
            ResolvedPrice price = priorClose.get();
            return new CanonicalInstrumentPrice(
                    key.instrumentKey(),
                    price.price(),
                    PriceType.PRIOR_CLOSE,
                    price.asOf(),
                    price.tradeDate(),
                    sessionState,
                    false,
                    price.source(),
                    "Official close missing; using prior close"
            );
        }

        return CanonicalInstrumentPrice.unavailable(
                key.instrumentKey(),
                sessionState,
                "unavailable",
                asOfTime,
                "No official EOD close available"
        );
    }

    private CanonicalInstrumentPrice resolvePriorCloseOnly(
            InstrumentKey key,
            MarketSessionStateResolver.SessionState sessionState,
            LocalDate tradeDate,
            Instant asOfTime
    ) {
        Optional<ResolvedPrice> priorClose = priceDataSource.loadPriorClosePrice(key, tradeDate);
        if (priorClose.isPresent()) {
            ResolvedPrice price = priorClose.get();
            return new CanonicalInstrumentPrice(
                    key.instrumentKey(),
                    price.price(),
                    PriceType.PRIOR_CLOSE,
                    price.asOf(),
                    price.tradeDate(),
                    sessionState,
                    false,
                    price.source(),
                    price.reason()
            );
        }

        return CanonicalInstrumentPrice.unavailable(
                key.instrumentKey(),
                sessionState,
                "unavailable",
                asOfTime,
                "Prior close unavailable"
        );
    }

    private static void logResolution(CanonicalInstrumentPrice price) {
        System.out.printf(
                "[price-resolver] instrumentKey=%s sessionState=%s selectedSource=%s priceType=%s price=%s asOf=%s tradeDate=%s stale=%s reason=%s%n",
                price.instrumentKey(),
                price.sessionState().name(),
                price.source(),
                price.priceType().name(),
                price.price() == null ? "unavailable" : price.price().toPlainString(),
                price.asOf(),
                price.tradeDate(),
                price.isStale(),
                price.diagnosticReason()
        );
    }

    public enum PriceType {
        LIVE_LTP,
        OFFICIAL_CLOSE,
        PRE_CLOSE_LAST_TRADE,
        PRIOR_CLOSE,
        UNAVAILABLE
    }

    public record InstrumentKey(
            String instrumentKey,
            String underlying,
            String instrumentId,
            String tradingSymbol,
            LocalDate expiryDate,
            BigDecimal strike,
            String optionType,
            boolean spot
    ) {
        public static InstrumentKey spot(String underlying) {
            return new InstrumentKey(
                    underlying,
                    underlying,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }

        public static InstrumentKey option(
                String instrumentId,
                String underlying,
                String tradingSymbol,
                LocalDate expiryDate,
                BigDecimal strike,
                String optionType
        ) {
            String key = instrumentId != null && !instrumentId.isBlank()
                    ? instrumentId
                    : "%s-%s-%s-%s".formatted(
                    underlying,
                    expiryDate,
                    strike == null ? "NA" : strike.stripTrailingZeros().toPlainString(),
                    optionType
            );
            return new InstrumentKey(
                    key,
                    underlying,
                    instrumentId,
                    tradingSymbol,
                    expiryDate,
                    strike,
                    optionType,
                    false
            );
        }
    }

    public record CanonicalInstrumentPrice(
            String instrumentKey,
            BigDecimal price,
            PriceType priceType,
            Instant asOf,
            LocalDate tradeDate,
            MarketSessionStateResolver.SessionState sessionState,
            boolean isStale,
            String source,
            String diagnosticReason
    ) {
        public static CanonicalInstrumentPrice unavailable(
                String instrumentKey,
                MarketSessionStateResolver.SessionState sessionState,
                String source,
                Instant asOf,
                String reason
        ) {
            return new CanonicalInstrumentPrice(
                    instrumentKey,
                    null,
                    PriceType.UNAVAILABLE,
                    asOf,
                    null,
                    sessionState,
                    true,
                    source,
                    reason
            );
        }
    }

    interface PriceDataSource extends MarketSessionStateResolver.SessionDataSource {
        Optional<InstrumentKey> resolveInstrumentKey(InstrumentKey key);

        Optional<ResolvedPrice> loadLatestIntradayPrice(InstrumentKey key, LocalDate tradeDate);

        Optional<ResolvedPrice> loadPreCloseLastTrade(InstrumentKey key, LocalDate tradeDate, Instant closeCutoff);

        Optional<ResolvedPrice> loadOfficialClosePrice(InstrumentKey key, LocalDate tradeDate);

        Optional<ResolvedPrice> loadPriorClosePrice(InstrumentKey key, LocalDate beforeTradeDate);
    }

    interface LivePriceSource {
        Optional<ResolvedPrice> loadLivePrice(InstrumentKey key);
    }

    record ResolvedPrice(BigDecimal price, Instant asOf, LocalDate tradeDate, boolean stale, String source, String reason) {
    }

    static final class SessionLivePriceSource implements LivePriceSource {
        private final LiveSessionState liveSessionState;

        SessionLivePriceSource(LiveSessionState liveSessionState) {
            this.liveSessionState = Objects.requireNonNull(liveSessionState, "liveSessionState must not be null");
        }

        @Override
        public Optional<ResolvedPrice> loadLivePrice(InstrumentKey key) {
            if (key.spot()) {
                LiveSessionState.SpotQuote spotQuote = liveSessionState.getLatestSpot(key.underlying());
                if (spotQuote == null || spotQuote.price() == null) {
                    return Optional.empty();
                }
                return Optional.of(new ResolvedPrice(
                        spotQuote.price(),
                        spotQuote.ts(),
                        toTradeDate(spotQuote.ts()),
                        isStale(spotQuote.ts()),
                        "live_cache",
                        "Live spot price"
                ));
            }
            if (key.instrumentId() == null || key.instrumentId().isBlank()) {
                return Optional.empty();
            }
            LiveSessionState.OptionQuote quote = liveSessionState.getLatestQuote(key.instrumentId());
            if (quote == null || quote.lastPrice() == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedPrice(
                    quote.lastPrice(),
                    quote.ts(),
                    toTradeDate(quote.ts()),
                    isStale(quote.ts()),
                    "live_cache",
                    "Live option price"
            ));
        }

        private static boolean isStale(Instant asOf) {
            return asOf == null || ChronoUnit.SECONDS.between(asOf, Instant.now()) > 2;
        }
    }

    static final class JdbcPriceDataSource implements PriceDataSource {
        private static final ZoneId IST = MarketSessionStateResolver.EXCHANGE_ZONE;
        private static final String SESSION_MARKER_SQL =
                "SELECT 1 FROM spot_live WHERE exchange_ts >= ? AND exchange_ts < ? LIMIT 1";
        private static final String OFFICIAL_SPOT_SQL =
                "SELECT trade_ts, close_price, trade_date FROM spot_historical WHERE underlying = ? AND trade_date = ? ORDER BY trade_ts DESC LIMIT 1";
        private static final String PRIOR_SPOT_SQL =
                "SELECT trade_ts, close_price, trade_date FROM spot_historical WHERE underlying = ? AND trade_date < ? ORDER BY trade_date DESC, trade_ts DESC LIMIT 1";
        private static final String LATEST_SPOT_SQL =
                "SELECT exchange_ts, last_price FROM spot_live WHERE underlying = ? AND exchange_ts >= ? AND exchange_ts < ? ORDER BY exchange_ts DESC LIMIT 1";
        private static final String PRECLOSE_SPOT_SQL =
                "SELECT exchange_ts, last_price FROM spot_live WHERE underlying = ? AND exchange_ts >= ? AND exchange_ts <= ? ORDER BY exchange_ts DESC LIMIT 1";
        private static final String OFFICIAL_OPTION_SQL =
                "SELECT trade_ts, close_price, trade_date FROM options_historical WHERE instrument_id = ? AND trade_date = ? ORDER BY trade_ts DESC LIMIT 1";
        private static final String PRIOR_OPTION_SQL =
                "SELECT trade_ts, close_price, trade_date FROM options_historical WHERE instrument_id = ? AND trade_date < ? ORDER BY trade_date DESC, trade_ts DESC LIMIT 1";
        private static final String LATEST_OPTION_SQL =
                "SELECT exchange_ts, last_price FROM options_live WHERE instrument_id = ? AND exchange_ts >= ? AND exchange_ts < ? ORDER BY exchange_ts DESC LIMIT 1";
        private static final String PRECLOSE_OPTION_SQL =
                "SELECT exchange_ts, last_price FROM options_live WHERE instrument_id = ? AND exchange_ts >= ? AND exchange_ts <= ? ORDER BY exchange_ts DESC LIMIT 1";
        private static final String RESOLVE_OPTION_SQL =
                "SELECT instrument_id, trading_symbol, expiry_date, strike, option_type"
                        + " FROM instrument_master"
                        + " WHERE is_active = true"
                        + "   AND underlying = ?"
                        + "   AND expiry_date = ?"
                        + "   AND strike = ?"
                        + "   AND option_type = ?"
                        + " ORDER BY updated_at DESC"
                        + " LIMIT 1";

        private final String jdbcUrl;
        private final KiteOptionCloseQuoteService closeQuoteService;

        JdbcPriceDataSource(String jdbcUrl, KiteOptionCloseQuoteService closeQuoteService) {
            this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
            this.closeQuoteService = closeQuoteService;
        }

        @Override
        public Optional<InstrumentKey> resolveInstrumentKey(InstrumentKey key) {
            if (key.spot()) {
                return Optional.of(key);
            }
            if (key.instrumentId() != null && !key.instrumentId().isBlank()) {
                return Optional.of(key);
            }
            if (key.underlying() == null || key.expiryDate() == null || key.strike() == null || key.optionType() == null) {
                return Optional.empty();
            }
            try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(RESOLVE_OPTION_SQL)) {
                statement.setString(1, key.underlying());
                statement.setTimestamp(2, Timestamp.from(key.expiryDate().atStartOfDay(IST).toInstant()));
                statement.setDouble(3, key.strike().doubleValue());
                statement.setString(4, key.optionType());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        System.out.printf(
                                "[price-resolver] exact-contract-lookup-failed underlying=%s expiry=%s strike=%s optionType=%s%n",
                                key.underlying(),
                                key.expiryDate(),
                                key.strike(),
                                key.optionType()
                        );
                        return Optional.empty();
                    }
                    return Optional.of(InstrumentKey.option(
                            rs.getString("instrument_id"),
                            key.underlying(),
                            rs.getString("trading_symbol"),
                            rs.getTimestamp("expiry_date").toInstant().atZone(IST).toLocalDate(),
                            BigDecimal.valueOf(rs.getDouble("strike")),
                            rs.getString("option_type")
                    ));
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to resolve option instrument key", exception);
            }
        }

        @Override
        public Optional<ResolvedPrice> loadLatestIntradayPrice(InstrumentKey key, LocalDate tradeDate) {
            Instant start = tradeDate.atStartOfDay(IST).toInstant();
            Instant end = tradeDate.plusDays(1).atStartOfDay(IST).toInstant();
            return key.spot()
                    ? queryPrice(prepareSpotStatement(LATEST_SPOT_SQL, key.underlying(), start, end), "historical_db", "Latest intraday spot")
                    : queryPrice(prepareOptionStatement(LATEST_OPTION_SQL, key.instrumentId(), start, end), "historical_db", "Latest intraday option");
        }

        @Override
        public Optional<ResolvedPrice> loadPreCloseLastTrade(InstrumentKey key, LocalDate tradeDate, Instant closeCutoff) {
            Instant start = tradeDate.atStartOfDay(IST).toInstant();
            return key.spot()
                    ? queryPrice(prepareSpotStatement(PRECLOSE_SPOT_SQL, key.underlying(), start, closeCutoff), "historical_db", "Pre-close last trade spot")
                    : queryPrice(prepareOptionStatement(PRECLOSE_OPTION_SQL, key.instrumentId(), start, closeCutoff), "historical_db", "Pre-close last trade option");
        }

        @Override
        public Optional<ResolvedPrice> loadOfficialClosePrice(InstrumentKey key, LocalDate tradeDate) {
            Optional<ResolvedPrice> dbOfficial = key.spot()
                    ? queryPrice(prepareOfficialSpotStatement(OFFICIAL_SPOT_SQL, key.underlying(), tradeDate), "historical_db", "Official close")
                    : queryPrice(prepareOfficialOptionStatement(OFFICIAL_OPTION_SQL, key.instrumentId(), tradeDate), "historical_db", "Official close");
            if (dbOfficial.isPresent()) {
                return dbOfficial;
            }
            if (closeQuoteService == null || !tradeDate.equals(LocalDate.now(IST))) {
                return Optional.empty();
            }
            if (key.spot()) {
                BigDecimal price = closeQuoteService.loadTodaySpotPrice(key.underlying());
                return price == null ? Optional.empty()
                        : Optional.of(new ResolvedPrice(price, Instant.now(clock()), tradeDate, false, "kite", "Official close from Kite"));
            }
            if (key.tradingSymbol() == null || key.instrumentId() == null) {
                return Optional.empty();
            }
            BigDecimal price = closeQuoteService.loadTodayClosePrices(
                    List.of(new KiteOptionCloseQuoteService.InstrumentRef(key.instrumentId(), key.tradingSymbol()))
            ).get(key.instrumentId());
            return price == null ? Optional.empty()
                    : Optional.of(new ResolvedPrice(price, Instant.now(clock()), tradeDate, false, "kite", "Official close from Kite"));
        }

        @Override
        public Optional<ResolvedPrice> loadPriorClosePrice(InstrumentKey key, LocalDate beforeTradeDate) {
            return key.spot()
                    ? queryPrice(prepareOfficialSpotStatement(PRIOR_SPOT_SQL, key.underlying(), beforeTradeDate), "historical_db", "Prior close")
                    : queryPrice(prepareOfficialOptionStatement(PRIOR_OPTION_SQL, key.instrumentId(), beforeTradeDate), "historical_db", "Prior close");
        }

        @Override
        public boolean hasOfficialCloseData(LocalDate tradeDate) {
            String sql = "SELECT 1 FROM spot_historical WHERE trade_date = ? LIMIT 1";
            try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setDate(1, java.sql.Date.valueOf(tradeDate));
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to query official close session data", exception);
            }
        }

        @Override
        public boolean hasIntradaySessionData(LocalDate tradeDate) {
            Instant start = tradeDate.atStartOfDay(IST).toInstant();
            Instant end = tradeDate.plusDays(1).atStartOfDay(IST).toInstant();
            try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(SESSION_MARKER_SQL)) {
                statement.setTimestamp(1, Timestamp.from(start));
                statement.setTimestamp(2, Timestamp.from(end));
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to query intraday session data", exception);
            }
        }

        private PreparedStatement prepareSpotStatement(String sql, String underlying, Instant from, Instant to) {
            try {
                Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setString(1, underlying);
                statement.setTimestamp(2, Timestamp.from(from));
                statement.setTimestamp(3, Timestamp.from(to));
                return statement;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to prepare spot price query", exception);
            }
        }

        private PreparedStatement prepareOptionStatement(String sql, String instrumentId, Instant from, Instant to) {
            try {
                Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setString(1, instrumentId);
                statement.setTimestamp(2, Timestamp.from(from));
                statement.setTimestamp(3, Timestamp.from(to));
                return statement;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to prepare option price query", exception);
            }
        }

        private PreparedStatement prepareOfficialSpotStatement(String sql, String underlying, LocalDate tradeDate) {
            try {
                Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setString(1, underlying);
                statement.setDate(2, java.sql.Date.valueOf(tradeDate));
                return statement;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to prepare official spot query", exception);
            }
        }

        private PreparedStatement prepareOfficialOptionStatement(String sql, String instrumentId, LocalDate tradeDate) {
            try {
                Connection connection = QuestDbConnectionFactory.open(jdbcUrl);
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setString(1, instrumentId);
                statement.setDate(2, java.sql.Date.valueOf(tradeDate));
                return statement;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to prepare official option query", exception);
            }
        }

        private Optional<ResolvedPrice> queryPrice(PreparedStatement statement, String source, String reason) {
            try (Connection ignored = statement.getConnection();
                 PreparedStatement stmt = statement;
                 ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp ts = rs.getTimestamp(1);
                double price = rs.getDouble(2);
                if (!rs.wasNull() && price > 0) {
                    return Optional.of(new ResolvedPrice(
                            BigDecimal.valueOf(price),
                            ts == null ? null : ts.toInstant(),
                            resolveTradeDate(rs, ts),
                            false,
                            source,
                            reason
                    ));
                }
                return Optional.empty();
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to query canonical price", exception);
            }
        }

        private static Clock clock() {
            return Clock.system(IST);
        }

        private static LocalDate resolveTradeDate(ResultSet rs, Timestamp timestamp) throws SQLException {
            LocalDate tradeDate = null;
            if (rs.getMetaData().getColumnCount() >= 3) {
                Date sqlDate = rs.getDate(3);
                if (sqlDate != null) {
                    tradeDate = sqlDate.toLocalDate();
                }
            }
            if (tradeDate != null || timestamp == null) {
                return tradeDate;
            }
            return toTradeDate(timestamp.toInstant());
        }
    }

    private static LocalDate toTradeDate(Instant timestamp) {
        return timestamp == null ? null : timestamp.atZone(MarketSessionStateResolver.EXCHANGE_ZONE).toLocalDate();
    }
}
