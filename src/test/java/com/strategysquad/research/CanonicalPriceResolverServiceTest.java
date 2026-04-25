package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalPriceResolverServiceTest {

    private static final ZoneId IST = MarketSessionStateResolver.EXCHANGE_ZONE;

    @Test
    void liveMarketPrefersLiveCacheWhenAvailable() {
        StubPriceDataSource dataSource = new StubPriceDataSource();
        CanonicalPriceResolverService.InstrumentKey key = optionKey("OPT-1", LocalDate.of(2026, 4, 30), 22500, "CE");
        StubLivePriceSource livePriceSource = new StubLivePriceSource()
                .with(key.instrumentKey(), price("141.25", "2026-04-21T11:30:00+05:30", false, "live_cache", "Live option price"));
        dataSource.latestIntraday.put(key.instrumentKey(), price("139.10", "2026-04-21T11:29:59+05:30", true, "historical_db", "Latest intraday option"));

        CanonicalPriceResolverService service = new CanonicalPriceResolverService(
                dataSource,
                livePriceSource,
                fixedClock("2026-04-21T11:31:00+05:30")
        );

        CanonicalPriceResolverService.CanonicalInstrumentPrice resolved = service.getCanonicalInstrumentPrice(
                key,
                MarketSessionStateResolver.SessionState.LIVE_MARKET,
                instant("2026-04-21T11:31:00+05:30")
        );

        assertEquals(new BigDecimal("141.25"), resolved.price());
        assertEquals(CanonicalPriceResolverService.PriceType.LIVE_LTP, resolved.priceType());
        assertEquals("live_cache", resolved.source());
        assertFalse(resolved.isStale());
    }

    @Test
    void postCloseUsesOfficialCloseInsteadOfStaleLiveCache() {
        StubPriceDataSource dataSource = new StubPriceDataSource();
        CanonicalPriceResolverService.InstrumentKey key = optionKey("OPT-2", LocalDate.of(2026, 4, 30), 22500, "PE");
        dataSource.officialClose.put(key.instrumentKey(), price("132.00", "2026-04-21T15:30:00+05:30", false, "historical_db", "Official close"));
        StubLivePriceSource livePriceSource = new StubLivePriceSource()
                .with(key.instrumentKey(), price("129.90", "2026-04-21T15:29:58+05:30", true, "live_cache", "Stale live"));

        CanonicalPriceResolverService service = new CanonicalPriceResolverService(
                dataSource,
                livePriceSource,
                fixedClock("2026-04-21T15:40:00+05:30")
        );

        CanonicalPriceResolverService.CanonicalInstrumentPrice resolved = service.getCanonicalInstrumentPrice(
                key,
                MarketSessionStateResolver.SessionState.POST_CLOSE,
                instant("2026-04-21T15:40:00+05:30")
        );

        assertEquals(new BigDecimal("132.00"), resolved.price());
        assertEquals(CanonicalPriceResolverService.PriceType.OFFICIAL_CLOSE, resolved.priceType());
        assertEquals("historical_db", resolved.source());
        assertEquals(LocalDate.of(2026, 4, 21), resolved.tradeDate());
        assertFalse(resolved.isStale());
    }

    @Test
    void postCloseFallsBackToLastPreCloseTradeWhenOfficialCloseMissing() {
        StubPriceDataSource dataSource = new StubPriceDataSource();
        CanonicalPriceResolverService.InstrumentKey key = optionKey("OPT-3", LocalDate.of(2026, 4, 30), 22600, "CE");
        dataSource.preClose.put(key.instrumentKey(), price("118.55", "2026-04-21T15:29:59+05:30", false, "historical_db", "Pre-close last trade"));

        CanonicalPriceResolverService service = new CanonicalPriceResolverService(
                dataSource,
                new StubLivePriceSource(),
                fixedClock("2026-04-21T15:45:00+05:30")
        );

        CanonicalPriceResolverService.CanonicalInstrumentPrice resolved = service.getCanonicalInstrumentPrice(
                key,
                MarketSessionStateResolver.SessionState.POST_CLOSE,
                instant("2026-04-21T15:45:00+05:30")
        );

        assertEquals(new BigDecimal("118.55"), resolved.price());
        assertEquals(CanonicalPriceResolverService.PriceType.PRE_CLOSE_LAST_TRADE, resolved.priceType());
        assertTrue(resolved.diagnosticReason().contains("Official close unavailable"));
    }

    @Test
    void eodFinalizedFallsBackToPriorCloseWhenOfficialCloseMissing() {
        StubPriceDataSource dataSource = new StubPriceDataSource();
        CanonicalPriceResolverService.InstrumentKey key = optionKey("OPT-4", LocalDate.of(2026, 4, 30), 22700, "CE");
        dataSource.priorClose.put(key.instrumentKey(), price("99.80", "2026-04-18T15:30:00+05:30", false, "historical_db", "Prior close"));

        CanonicalPriceResolverService service = new CanonicalPriceResolverService(
                dataSource,
                new StubLivePriceSource(),
                fixedClock("2026-04-21T19:00:00+05:30")
        );

        CanonicalPriceResolverService.CanonicalInstrumentPrice resolved = service.getCanonicalInstrumentPrice(
                key,
                MarketSessionStateResolver.SessionState.EOD_FINALIZED,
                instant("2026-04-21T19:00:00+05:30")
        );

        assertEquals(new BigDecimal("99.80"), resolved.price());
        assertEquals(CanonicalPriceResolverService.PriceType.PRIOR_CLOSE, resolved.priceType());
        assertEquals("historical_db", resolved.source());
    }

    @Test
    void holidayUsesPriorCloseForSpot() {
        StubPriceDataSource dataSource = new StubPriceDataSource();
        CanonicalPriceResolverService.InstrumentKey key = CanonicalPriceResolverService.InstrumentKey.spot("NIFTY");
        dataSource.priorClose.put(
                key.instrumentKey(),
                price("22461.40", "2026-04-20T10:00:00+05:30", "2026-04-17", false, "historical_db", "Prior close")
        );

        CanonicalPriceResolverService service = new CanonicalPriceResolverService(
                dataSource,
                new StubLivePriceSource(),
                fixedClock("2026-04-19T10:00:00+05:30")
        );

        CanonicalPriceResolverService.CanonicalInstrumentPrice resolved = service.getCanonicalInstrumentPrice(
                key,
                MarketSessionStateResolver.SessionState.HOLIDAY_NO_SESSION,
                instant("2026-04-19T10:00:00+05:30")
        );

        assertEquals(new BigDecimal("22461.40"), resolved.price());
        assertEquals(CanonicalPriceResolverService.PriceType.PRIOR_CLOSE, resolved.priceType());
        assertEquals(LocalDate.of(2026, 4, 17), resolved.tradeDate());
    }

    @Test
    void postCloseSpotUsesCurrentDayOfficialCloseDate() {
        StubPriceDataSource dataSource = new StubPriceDataSource();
        CanonicalPriceResolverService.InstrumentKey key = CanonicalPriceResolverService.InstrumentKey.spot("NIFTY");
        dataSource.officialClose.put(
                key.instrumentKey(),
                price("25174.60", "2026-04-23T17:05:00+05:30", "2026-04-23", false, "historical_db", "Official close")
        );

        CanonicalPriceResolverService service = new CanonicalPriceResolverService(
                dataSource,
                new StubLivePriceSource(),
                fixedClock("2026-04-23T18:00:00+05:30")
        );

        CanonicalPriceResolverService.CanonicalInstrumentPrice resolved = service.getCanonicalInstrumentPrice(
                key,
                MarketSessionStateResolver.SessionState.EOD_FINALIZED,
                instant("2026-04-23T18:00:00+05:30")
        );

        assertEquals(new BigDecimal("25174.60"), resolved.price());
        assertEquals(CanonicalPriceResolverService.PriceType.OFFICIAL_CLOSE, resolved.priceType());
        assertEquals(LocalDate.of(2026, 4, 23), resolved.tradeDate());
    }

    @Test
    void exactContractMismatchReturnsUnavailableInsteadOfWrongStrikeOrOptionType() {
        StubPriceDataSource dataSource = new StubPriceDataSource();
        CanonicalPriceResolverService.InstrumentKey request = CanonicalPriceResolverService.InstrumentKey.option(
                null,
                "NIFTY",
                null,
                LocalDate.of(2026, 4, 30),
                new BigDecimal("22500"),
                "PE"
        );
        dataSource.resolvedKeys.put(exactKey("NIFTY", LocalDate.of(2026, 4, 30), new BigDecimal("22500"), "CE"),
                optionKey("OPT-5", LocalDate.of(2026, 4, 30), 22500, "CE"));

        CanonicalPriceResolverService service = new CanonicalPriceResolverService(
                dataSource,
                new StubLivePriceSource(),
                fixedClock("2026-04-21T15:45:00+05:30")
        );

        CanonicalPriceResolverService.CanonicalInstrumentPrice resolved = service.getCanonicalInstrumentPrice(
                request,
                MarketSessionStateResolver.SessionState.POST_CLOSE,
                instant("2026-04-21T15:45:00+05:30")
        );

        assertEquals(CanonicalPriceResolverService.PriceType.UNAVAILABLE, resolved.priceType());
        assertNull(resolved.price());
        assertTrue(resolved.diagnosticReason().contains("Exact contract lookup failed"));
    }

    @Test
    void noCrossInstrumentLeakageWhenOtherInstrumentHasLivePrice() {
        StubPriceDataSource dataSource = new StubPriceDataSource();
        CanonicalPriceResolverService.InstrumentKey requested = optionKey("OPT-6", LocalDate.of(2026, 4, 30), 22400, "CE");
        CanonicalPriceResolverService.InstrumentKey other = optionKey("OPT-7", LocalDate.of(2026, 4, 30), 22600, "CE");
        StubLivePriceSource livePriceSource = new StubLivePriceSource()
                .with(other.instrumentKey(), price("151.10", "2026-04-21T11:10:00+05:30", false, "live_cache", "Other contract"));

        CanonicalPriceResolverService service = new CanonicalPriceResolverService(
                dataSource,
                livePriceSource,
                fixedClock("2026-04-21T11:10:05+05:30")
        );

        CanonicalPriceResolverService.CanonicalInstrumentPrice resolved = service.getCanonicalInstrumentPrice(
                requested,
                MarketSessionStateResolver.SessionState.LIVE_MARKET,
                instant("2026-04-21T11:10:05+05:30")
        );

        assertEquals(CanonicalPriceResolverService.PriceType.UNAVAILABLE, resolved.priceType());
        assertNull(resolved.price());
    }

    private static CanonicalPriceResolverService.InstrumentKey optionKey(
            String instrumentId,
            LocalDate expiryDate,
            double strike,
            String optionType
    ) {
        return CanonicalPriceResolverService.InstrumentKey.option(
                instrumentId,
                "NIFTY",
                instrumentId,
                expiryDate,
                BigDecimal.valueOf(strike),
                optionType
        );
    }

    private static String exactKey(String underlying, LocalDate expiryDate, BigDecimal strike, String optionType) {
        return "%s|%s|%s|%s".formatted(
                underlying,
                expiryDate,
                strike.stripTrailingZeros().toPlainString(),
                optionType
        );
    }

    private static CanonicalPriceResolverService.ResolvedPrice price(
            String price,
            String exchangeTs,
            boolean stale,
            String source,
            String reason
    ) {
        return price(price, exchangeTs, exchangeTs.substring(0, 10), stale, source, reason);
    }

    private static CanonicalPriceResolverService.ResolvedPrice price(
            String price,
            String exchangeTs,
            String tradeDate,
            boolean stale,
            String source,
            String reason
    ) {
        return new CanonicalPriceResolverService.ResolvedPrice(
                new BigDecimal(price),
                instant(exchangeTs),
                LocalDate.parse(tradeDate),
                stale,
                source,
                reason
        );
    }

    private static Clock fixedClock(String exchangeLocalTs) {
        return Clock.fixed(instant(exchangeLocalTs), IST);
    }

    private static Instant instant(String exchangeLocalTs) {
        return LocalDateTime.parse(exchangeLocalTs.substring(0, 19))
                .atOffset(java.time.OffsetDateTime.parse(exchangeLocalTs).getOffset())
                .toInstant();
    }

    private static final class StubLivePriceSource implements CanonicalPriceResolverService.LivePriceSource {
        private final Map<String, CanonicalPriceResolverService.ResolvedPrice> prices = new HashMap<>();

        StubLivePriceSource with(String instrumentKey, CanonicalPriceResolverService.ResolvedPrice price) {
            prices.put(instrumentKey, price);
            return this;
        }

        @Override
        public Optional<CanonicalPriceResolverService.ResolvedPrice> loadLivePrice(CanonicalPriceResolverService.InstrumentKey key) {
            return Optional.ofNullable(prices.get(key.instrumentKey()));
        }
    }

    private static final class StubPriceDataSource implements CanonicalPriceResolverService.PriceDataSource {
        private final Map<String, CanonicalPriceResolverService.InstrumentKey> resolvedKeys = new HashMap<>();
        private final Map<String, CanonicalPriceResolverService.ResolvedPrice> latestIntraday = new HashMap<>();
        private final Map<String, CanonicalPriceResolverService.ResolvedPrice> preClose = new HashMap<>();
        private final Map<String, CanonicalPriceResolverService.ResolvedPrice> officialClose = new HashMap<>();
        private final Map<String, CanonicalPriceResolverService.ResolvedPrice> priorClose = new HashMap<>();

        @Override
        public Optional<CanonicalPriceResolverService.InstrumentKey> resolveInstrumentKey(CanonicalPriceResolverService.InstrumentKey key) {
            if (key.spot() || (key.instrumentId() != null && !key.instrumentId().isBlank())) {
                return Optional.of(key);
            }
            return Optional.ofNullable(resolvedKeys.get(exactKey(key.underlying(), key.expiryDate(), key.strike(), key.optionType())));
        }

        @Override
        public Optional<CanonicalPriceResolverService.ResolvedPrice> loadLatestIntradayPrice(
                CanonicalPriceResolverService.InstrumentKey key,
                LocalDate tradeDate
        ) {
            return Optional.ofNullable(latestIntraday.get(key.instrumentKey()));
        }

        @Override
        public Optional<CanonicalPriceResolverService.ResolvedPrice> loadPreCloseLastTrade(
                CanonicalPriceResolverService.InstrumentKey key,
                LocalDate tradeDate,
                Instant closeCutoff
        ) {
            return Optional.ofNullable(preClose.get(key.instrumentKey()));
        }

        @Override
        public Optional<CanonicalPriceResolverService.ResolvedPrice> loadOfficialClosePrice(
                CanonicalPriceResolverService.InstrumentKey key,
                LocalDate tradeDate
        ) {
            return Optional.ofNullable(officialClose.get(key.instrumentKey()));
        }

        @Override
        public Optional<CanonicalPriceResolverService.ResolvedPrice> loadPriorClosePrice(
                CanonicalPriceResolverService.InstrumentKey key,
                LocalDate beforeTradeDate
        ) {
            return Optional.ofNullable(priorClose.get(key.instrumentKey()));
        }

        @Override
        public boolean hasOfficialCloseData(LocalDate tradeDate) {
            return false;
        }

        @Override
        public boolean hasIntradaySessionData(LocalDate tradeDate) {
            return false;
        }
    }
}
