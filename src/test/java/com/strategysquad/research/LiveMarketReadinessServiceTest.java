package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveMarketReadinessServiceTest {

    @Test
    void marksNiftyAndBankNiftyReadyWhenSpotCallAndPutTicksExist() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-23T10:10:00Z"), ZoneOffset.UTC);
        LiveMarketReadinessService service = new LiveMarketReadinessService(
                (sessionStart, sessionEnd) -> new LiveMarketReadinessService.MarketDataWindow(
                        List.of(
                                new LiveMarketReadinessService.SpotCount("NIFTY", 10816),
                                new LiveMarketReadinessService.SpotCount("BANKNIFTY", 10816)
                        ),
                        List.of(
                                new LiveMarketReadinessService.OptionCount("NIFTY", "CE", 865280),
                                new LiveMarketReadinessService.OptionCount("NIFTY", "PE", 865280),
                                new LiveMarketReadinessService.OptionCount("BANKNIFTY", "CE", 865280),
                                new LiveMarketReadinessService.OptionCount("BANKNIFTY", "PE", 865280)
                        ),
                        Instant.parse("2026-04-23T10:04:58Z"),
                        Instant.parse("2026-04-23T10:00:04Z")
                ),
                clock
        );

        LiveMarketReadinessService.LiveMarketReadinessSnapshot snapshot =
                service.load(Instant.parse("2026-04-23T10:09:10Z"));

        assertTrue(snapshot.available());
        assertEquals(LocalDate.of(2026, 4, 23), snapshot.sessionDate());
        assertTrue(snapshot.feedFreshNow());
        assertTrue(snapshot.anyStraddleReadyToday());
        assertTrue(snapshot.niftyStraddleReadyToday());
        assertTrue(snapshot.bankNiftyStraddleReadyToday());
        assertEquals(2, snapshot.underlyings().size());
        assertEquals(10816L, snapshot.underlyings().get(0).spotTickCount());
        assertEquals(865280L, snapshot.underlyings().get(0).callTickCount());
        assertEquals(865280L, snapshot.underlyings().get(0).putTickCount());
    }

    @Test
    void marksUnderlyingIncompleteWhenPutSideIsMissing() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-23T10:10:00Z"), ZoneOffset.UTC);
        LiveMarketReadinessService service = new LiveMarketReadinessService(
                (sessionStart, sessionEnd) -> new LiveMarketReadinessService.MarketDataWindow(
                        List.of(new LiveMarketReadinessService.SpotCount("NIFTY", 25)),
                        List.of(new LiveMarketReadinessService.OptionCount("NIFTY", "CE", 300)),
                        Instant.parse("2026-04-23T10:04:58Z"),
                        Instant.parse("2026-04-23T10:00:04Z")
                ),
                clock
        );

        LiveMarketReadinessService.LiveMarketReadinessSnapshot snapshot =
                service.load(Instant.parse("2026-04-23T10:09:00Z"));

        assertTrue(snapshot.available());
        assertFalse(snapshot.anyStraddleReadyToday());
        assertFalse(snapshot.niftyStraddleReadyToday());
        assertFalse(snapshot.bankNiftyStraddleReadyToday());
        assertTrue(snapshot.feedFreshNow());
        assertEquals("NIFTY", snapshot.underlyings().get(0).underlying());
        assertFalse(snapshot.underlyings().get(0).straddleReadyToday());
        assertEquals(0L, snapshot.underlyings().get(0).putTickCount());
    }

    @Test
    void returnsUnavailableSnapshotWhenQueryFails() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-23T10:10:00Z"), ZoneOffset.UTC);
        LiveMarketReadinessService service = new LiveMarketReadinessService(
                new FailingDataSource(),
                clock
        );

        LiveMarketReadinessService.LiveMarketReadinessSnapshot snapshot = service.load(null);

        assertFalse(snapshot.available());
        assertFalse(snapshot.feedFreshNow());
        assertTrue(snapshot.summary().contains("Unable to load live market readiness"));
    }

    private static final class FailingDataSource implements LiveMarketReadinessService.DataSource {
        @Override
        public LiveMarketReadinessService.MarketDataWindow load(Instant sessionStart, Instant sessionEnd) throws SQLException {
            throw new SQLException("boom");
        }
    }
}
