package com.strategysquad.ingestion.kite;

import com.strategysquad.ingestion.live.session.LiveSessionState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes live net premium for a multi-leg options structure using current
 * quotes from {@link LiveSessionState}.
 *
 * <p>Net premium = sum of (lastPrice × side multiplier) across all legs,
 * where LONG = +1, SHORT = −1.
 *
 * <p>This is a presentation-layer service: it reads from the in-memory session
 * state and does not touch the database.
 */
public final class LiveStructureSnapshotService {

    private final LiveSessionState sessionState;

    public LiveStructureSnapshotService(LiveSessionState sessionState) {
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState must not be null");
    }

    /**
     * Computes a live structure snapshot for the given legs.
     *
     * @param legs list of legs, each with an instrumentId and side (LONG or SHORT)
     * @return snapshot with net premium and per-leg quotes, or null if any leg has no quote
     */
    public Snapshot compute(List<LegRequest> legs) {
        Objects.requireNonNull(legs, "legs must not be null");
        if (legs.isEmpty()) return null;

        List<LegQuote> legQuotes = new ArrayList<>(legs.size());
        BigDecimal netPremium = BigDecimal.ZERO;
        boolean anyMissing = false;

        for (LegRequest leg : legs) {
            LiveSessionState.OptionQuote q = sessionState.getLatestQuote(leg.instrumentId());
            if (q == null) {
                anyMissing = true;
                legQuotes.add(new LegQuote(leg.instrumentId(), leg.side(), null, null, null));
                continue;
            }
            BigDecimal contribution = "SHORT".equalsIgnoreCase(leg.side())
                    ? q.lastPrice().negate()
                    : q.lastPrice();
            netPremium = netPremium.add(contribution);
            legQuotes.add(new LegQuote(leg.instrumentId(), leg.side(),
                    q.lastPrice(), q.bidPrice(), q.askPrice()));
        }

        return new Snapshot(netPremium, anyMissing, legQuotes,
                sessionState.getLastTickTs());
    }

    public record LegRequest(String instrumentId, String side) {
    }

    public record LegQuote(
            String instrumentId,
            String side,
            BigDecimal lastPrice,
            BigDecimal bidPrice,
            BigDecimal askPrice
    ) {
    }

    public record Snapshot(
            BigDecimal netPremium,
            boolean partialData,
            List<LegQuote> legs,
            java.time.Instant asOf
    ) {
    }
}
