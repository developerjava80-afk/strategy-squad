package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketSessionStateResolverTest {

    private static final ZoneId IST = MarketSessionStateResolver.EXCHANGE_ZONE;

    @Test
    void resolvesPreopenBeforeMarketStarts() {
        MarketSessionStateResolver resolver = new MarketSessionStateResolver(
                new StubSessionDataSource(false, false),
                fixedClock("2026-04-21T08:30:00+05:30")
        );

        assertEquals(MarketSessionStateResolver.SessionState.PREOPEN, resolver.resolveNow());
    }

    @Test
    void resolvesLiveMarketDuringTradingHours() {
        MarketSessionStateResolver resolver = new MarketSessionStateResolver(
                new StubSessionDataSource(false, true),
                fixedClock("2026-04-21T11:00:00+05:30")
        );

        assertEquals(MarketSessionStateResolver.SessionState.LIVE_MARKET, resolver.resolveNow());
    }

    @Test
    void resolvesPostCloseWhenIntradayExistsButOfficialCloseNotFinalized() {
        MarketSessionStateResolver resolver = new MarketSessionStateResolver(
                new StubSessionDataSource(false, true),
                fixedClock("2026-04-21T15:45:00+05:30")
        );

        assertEquals(MarketSessionStateResolver.SessionState.POST_CLOSE, resolver.resolveNow());
    }

    @Test
    void resolvesEodFinalizedWhenOfficialCloseIsAvailableAfterHours() {
        MarketSessionStateResolver resolver = new MarketSessionStateResolver(
                new StubSessionDataSource(true, true),
                fixedClock("2026-04-21T16:15:00+05:30")
        );

        assertEquals(MarketSessionStateResolver.SessionState.EOD_FINALIZED, resolver.resolveNow());
    }

    @Test
    void resolvesHolidayNoSessionOnWeekend() {
        MarketSessionStateResolver resolver = new MarketSessionStateResolver(
                new StubSessionDataSource(false, false),
                fixedClock("2026-04-19T10:00:00+05:30")
        );

        assertEquals(MarketSessionStateResolver.SessionState.HOLIDAY_NO_SESSION, resolver.resolveNow());
    }

    @Test
    void resolvesPastTradingDayAsEodFinalizedWhenOfficialHistoryExists() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-21T12:00:00Z"), IST);
        MarketSessionStateResolver resolver = new MarketSessionStateResolver(
                new MarketSessionStateResolver.SessionDataSource() {
                    @Override
                    public boolean hasOfficialCloseData(LocalDate tradeDate) {
                        return LocalDate.of(2026, 4, 20).equals(tradeDate);
                    }

                    @Override
                    public boolean hasIntradaySessionData(LocalDate tradeDate) {
                        return false;
                    }
                },
                clock
        );

        Instant pastDay = LocalDate.of(2026, 4, 20).atTime(20, 0).atZone(IST).toInstant();
        assertEquals(MarketSessionStateResolver.SessionState.EOD_FINALIZED, resolver.resolve(pastDay));
    }

    private static Clock fixedClock(String exchangeLocalDateTime) {
        return Clock.fixed(Instant.parse(
                java.time.OffsetDateTime.parse(exchangeLocalDateTime).toInstant().toString()
        ), IST);
    }

    private record StubSessionDataSource(boolean officialClose, boolean intradayData)
            implements MarketSessionStateResolver.SessionDataSource {
        @Override
        public boolean hasOfficialCloseData(LocalDate tradeDate) {
            return officialClose;
        }

        @Override
        public boolean hasIntradaySessionData(LocalDate tradeDate) {
            return intradayData;
        }
    }
}
