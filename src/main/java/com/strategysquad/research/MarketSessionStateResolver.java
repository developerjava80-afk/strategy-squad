package com.strategysquad.research;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the exchange-local market session state for canonical price selection.
 */
public final class MarketSessionStateResolver {

    public static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    public static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final SessionDataSource sessionDataSource;
    private final Clock clock;
    private final AtomicReference<SessionState> lastLoggedState = new AtomicReference<>();

    public MarketSessionStateResolver(SessionDataSource sessionDataSource) {
        this(sessionDataSource, Clock.system(EXCHANGE_ZONE));
    }

    MarketSessionStateResolver(SessionDataSource sessionDataSource, Clock clock) {
        this.sessionDataSource = Objects.requireNonNull(sessionDataSource, "sessionDataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SessionState resolveNow() {
        return resolve(clock.instant());
    }

    public SessionState resolve(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        ZonedDateTime exchangeTs = asOf.atZone(EXCHANGE_ZONE);
        LocalDate tradeDate = exchangeTs.toLocalDate();
        LocalTime tradeTime = exchangeTs.toLocalTime();
        SessionState state;

        if (isWeekend(tradeDate)) {
            state = SessionState.HOLIDAY_NO_SESSION;
        } else if (tradeDate.isBefore(LocalDate.now(clock.withZone(EXCHANGE_ZONE)))) {
            state = sessionDataSource.hasOfficialCloseData(tradeDate)
                    ? SessionState.EOD_FINALIZED
                    : SessionState.HOLIDAY_NO_SESSION;
        } else if (tradeTime.isBefore(MARKET_OPEN)) {
            state = SessionState.PREOPEN;
        } else if (tradeTime.isBefore(MARKET_CLOSE)) {
            state = SessionState.LIVE_MARKET;
        } else if (sessionDataSource.hasOfficialCloseData(tradeDate)) {
            state = SessionState.EOD_FINALIZED;
        } else {
            // Market has closed but today's bhavcopy has not yet been ingested.
            // Use POST_CLOSE regardless of whether live ticks were captured so that
            // the Kite API fallback in loadOfficialClosePrice() can supply today's
            // closing price via last_price.  True exchange holidays produce no
            // Kite last_price > 0 and will fall through to unavailable, which is safe.
            state = SessionState.POST_CLOSE;
        }

        SessionState previous = lastLoggedState.getAndSet(state);
        if (previous != state) {
            System.out.printf(
                    "[session-state] transition %s -> %s at %s%n",
                    previous == null ? "UNKNOWN" : previous.name(),
                    state.name(),
                    exchangeTs
            );
        }
        return state;
    }

    public static Instant closeCutoff(LocalDate tradeDate) {
        return tradeDate.atTime(MARKET_CLOSE).atZone(EXCHANGE_ZONE).minusSeconds(1).toInstant();
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    public interface SessionDataSource {
        boolean hasOfficialCloseData(LocalDate tradeDate);

        boolean hasIntradaySessionData(LocalDate tradeDate);
    }

    public enum SessionState {
        PREOPEN,
        LIVE_MARKET,
        POST_CLOSE,
        EOD_FINALIZED,
        HOLIDAY_NO_SESSION
    }
}
