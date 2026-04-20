package com.strategysquad.ingestion.live.session;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe shared state for the live Kite session.
 * Written by the ticker thread; read by REST handler threads.
 */
public final class LiveSessionState {

    public enum Status { DISCONNECTED, CONNECTING, CONNECTED, STALE, TOKEN_EXPIRED }

    private final AtomicReference<Status> status = new AtomicReference<>(Status.DISCONNECTED);
    private final AtomicReference<Instant> lastTickTs = new AtomicReference<>(null);
    private final AtomicInteger subscribedInstruments = new AtomicInteger(0);
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    private final AtomicReference<String> disconnectReason = new AtomicReference<>("");

    // Latest spot price per underlying (e.g. "NIFTY" -> 22450.50)
    private final ConcurrentHashMap<String, SpotQuote> latestSpot = new ConcurrentHashMap<>();

    // Latest option quote per instrumentId
    private final ConcurrentHashMap<String, OptionQuote> latestQuotes = new ConcurrentHashMap<>();

    public void setStatus(Status s) { status.set(s); }
    public Status getStatus() { return status.get(); }

    public void setDisconnectReason(String reason) { disconnectReason.set(reason == null ? "" : reason); }
    public String getDisconnectReason() { return disconnectReason.get(); }

    public void resetForLogin() {
        status.set(Status.DISCONNECTED);
        lastTickTs.set(null);
        ticksProcessed.set(0);
        disconnectReason.set("");
        latestSpot.clear();
        latestQuotes.clear();
    }

    public void recordTick(Instant exchangeTs) {
        lastTickTs.set(exchangeTs);
        ticksProcessed.incrementAndGet();
        if (status.get() == Status.STALE) {
            status.set(Status.CONNECTED);
        }
    }

    public Instant getLastTickTs() { return lastTickTs.get(); }
    public long getTicksProcessed() { return ticksProcessed.get(); }

    public void setSubscribedInstruments(int count) { subscribedInstruments.set(count); }
    public int getSubscribedInstruments() { return subscribedInstruments.get(); }

    public void updateSpot(String underlying, BigDecimal price, Instant ts) {
        latestSpot.put(underlying, new SpotQuote(underlying, price, ts));
    }

    public SpotQuote getLatestSpot(String underlying) {
        return latestSpot.get(underlying);
    }

    public Map<String, SpotQuote> getAllSpot() {
        return Map.copyOf(latestSpot);
    }

    public void updateQuote(String instrumentId, BigDecimal lastPrice, BigDecimal bidPrice,
                            BigDecimal askPrice, long volume, long openInterest, Instant ts) {
        latestQuotes.put(instrumentId, new OptionQuote(
                instrumentId, lastPrice, bidPrice, askPrice, volume, openInterest, ts));
    }

    public OptionQuote getLatestQuote(String instrumentId) {
        return latestQuotes.get(instrumentId);
    }

    public record SpotQuote(String underlying, BigDecimal price, Instant ts) {}

    public record OptionQuote(
            String instrumentId,
            BigDecimal lastPrice,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            long volume,
            long openInterest,
            Instant ts
    ) {}
}
