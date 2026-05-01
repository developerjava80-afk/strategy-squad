package com.strategysquad.ingestion.live.session;

import com.strategysquad.research.LiveMarketReadinessService;

import java.time.Instant;

/**
 * Point-in-time status snapshot returned by {@code GET /api/live/status}.
 */
public record LiveStatusReport(
        String status,
        String disconnectReason,
        Instant lastTickTs,
        long secondsSinceLastTick,
        int subscribedInstruments,
        long ticksProcessed,
        LiveMarketReadinessService.LiveMarketReadinessSnapshot dataReadiness,
        // Phase 7 scope gauges
        int subscribedTokenCount,
        int activeScopeCount
) {

    public static LiveStatusReport from(
            LiveSessionState state,
            LiveMarketReadinessService.LiveMarketReadinessSnapshot dataReadiness
    ) {
        Instant lastTick = state.getLastTickTs();
        long secondsAgo = lastTick == null ? -1
                : java.time.Duration.between(lastTick, Instant.now()).toSeconds();
        return new LiveStatusReport(
                state.getStatus().name(),
                state.getDisconnectReason(),
                lastTick,
                secondsAgo,
                state.getSubscribedInstruments(),
                state.getTicksProcessed(),
                dataReadiness,
                0,  // no scope manager available at this call site; use the overload below
                0
        );
    }

    /**
     * Phase 7 overload: includes scope gauges from {@link com.strategysquad.ingestion.kite.KiteSubscriptionManager}
     * and {@link com.strategysquad.scope.ScopeService}.
     */
    public static LiveStatusReport from(
            LiveSessionState state,
            LiveMarketReadinessService.LiveMarketReadinessSnapshot dataReadiness,
            int subscribedTokenCount,
            boolean scopeActive
    ) {
        Instant lastTick = state.getLastTickTs();
        long secondsAgo = lastTick == null ? -1
                : java.time.Duration.between(lastTick, Instant.now()).toSeconds();
        return new LiveStatusReport(
                state.getStatus().name(),
                state.getDisconnectReason(),
                lastTick,
                secondsAgo,
                state.getSubscribedInstruments(),
                state.getTicksProcessed(),
                dataReadiness,
                subscribedTokenCount,
                scopeActive ? 1 : 0
        );
    }

    /**
     * Phase 7 copy-overload: enriches an already-constructed report with
     * live scope gauges.  Used by {@code LiveStatusHandler} which receives a
     * pre-built report from {@link com.strategysquad.research.LiveMarketService}
     * and then overlays subscription/scope counts from the managers.
     */
    public static LiveStatusReport from(
            LiveStatusReport base,
            int subscribedTokenCount,
            boolean scopeActive
    ) {
        return new LiveStatusReport(
                base.status(),
                base.disconnectReason(),
                base.lastTickTs(),
                base.secondsSinceLastTick(),
                base.subscribedInstruments(),
                base.ticksProcessed(),
                base.dataReadiness(),
                subscribedTokenCount,
                scopeActive ? 1 : 0
        );
    }

    /** Returns a report for when live mode is not initialized. */
    public static LiveStatusReport disabled() {
        return new LiveStatusReport("DISABLED", "Live mode not enabled", null, -1, 0, 0, null, 0, 0);
    }
}
