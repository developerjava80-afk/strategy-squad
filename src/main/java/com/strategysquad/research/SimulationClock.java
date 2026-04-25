package com.strategysquad.research;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable clock that powers simulation (replay) mode.
 *
 * <p>When {@link #setSimulatedDate} is called the clock returns a fixed instant during
 * market hours on that date so that {@link MarketSessionStateResolver} resolves to
 * {@code LIVE_MARKET} and all downstream price resolution reads from
 * {@link com.strategysquad.ingestion.live.session.LiveSessionState} – exactly the same
 * path used during live trading.
 *
 * <p>When {@link #reset} is called the clock delegates back to the real system clock
 * and every component behaves exactly as normal.
 *
 * <p>Thread-safe: a single instance is shared across services.
 */
public final class SimulationClock extends Clock {

    /** Market-open anchor used before the replay publishes its first real batch. */
    private static final LocalTime SIM_ANCHOR = MarketSessionStateResolver.MARKET_OPEN;

    private final AtomicReference<Instant> fixedInstant = new AtomicReference<>(null);

    // -------------------------------------------------------------------------
    // Simulation control
    // -------------------------------------------------------------------------

    /**
     * Fixes this clock to market open on the given replay date so that all session-
     * state resolvers see LIVE_MARKET for that date.
     */
    public void setSimulatedDate(LocalDate replayDate) {
        setSimulatedInstant(replayDate.atTime(SIM_ANCHOR)
                .atZone(MarketSessionStateResolver.EXCHANGE_ZONE)
                .toInstant());
    }

    /** Fixes this clock to an explicit replay instant. */
    public void setSimulatedInstant(Instant replayInstant) {
        fixedInstant.set(replayInstant);
    }

    /** Resets the clock to system time, ending simulation mode. */
    public void reset() {
        fixedInstant.set(null);
    }

    /** Returns {@code true} while the clock is fixed to a replay date. */
    public boolean isSimulating() {
        return fixedInstant.get() != null;
    }

    /**
     * Returns the currently simulated date, or {@code null} when not simulating.
     */
    public LocalDate simulatedDate() {
        Instant fixed = fixedInstant.get();
        return fixed == null ? null : fixed.atZone(MarketSessionStateResolver.EXCHANGE_ZONE).toLocalDate();
    }

    // -------------------------------------------------------------------------
    // java.time.Clock contract
    // -------------------------------------------------------------------------

    @Override
    public ZoneId getZone() {
        return MarketSessionStateResolver.EXCHANGE_ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Instant fixed = fixedInstant.get();
        if (fixed != null) {
            return Clock.fixed(fixed, zone);
        }
        return Clock.system(zone);
    }

    @Override
    public Instant instant() {
        Instant fixed = fixedInstant.get();
        return fixed != null ? fixed : Instant.now();
    }
}
