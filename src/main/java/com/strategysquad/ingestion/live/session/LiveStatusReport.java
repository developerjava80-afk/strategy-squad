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
        LiveMarketReadinessService.LiveMarketReadinessSnapshot dataReadiness
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
                dataReadiness
        );
    }

    /** Returns a report for when live mode is not initialized. */
    public static LiveStatusReport disabled() {
        return new LiveStatusReport("DISABLED", "Live mode not enabled", null, -1, 0, 0, null);
    }
}
