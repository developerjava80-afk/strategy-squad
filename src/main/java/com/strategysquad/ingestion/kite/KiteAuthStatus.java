package com.strategysquad.ingestion.kite;

import com.strategysquad.ingestion.live.session.LiveSessionState;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Daily auth/login state for the live console.
 */
public record KiteAuthStatus(
        boolean authenticatedForToday,
        boolean requiresLogin,
        String userId,
        LocalDate tradingDate,
        String liveStatus,
        String message,
        String loginUrl,
        Path tokenFile
) {
    public static KiteAuthStatus loggedOut(String userId, LocalDate tradingDate, String message, Path tokenFile) {
        return new KiteAuthStatus(false, true, userId, tradingDate, LiveSessionState.Status.DISCONNECTED.name(), message, null, tokenFile);
    }
}
