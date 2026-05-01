package com.strategysquad.research;

import java.time.Instant;

/**
 * Input DTO for the Theta + Delta Sense Check.
 *
 * <p>All price and move values are in index points (not rupees, not lots).
 * No Black-Scholes inputs (IV, rate, dividend) are accepted — this feature
 * is a historical sense-check only.
 */
public record ThetaDeltaSenseCheckRequest(
        /** NIFTY or BANKNIFTY. */
        String underlying,

        /** Expiry date as ISO-8601 string, e.g. "2026-04-30". */
        String expiry,

        /** Strike price in index points. */
        double strike,

        /** CE or PE. */
        String optionType,

        /** LONG or SHORT. */
        String side,

        /** Premium at entry (must be > 0). */
        double entryPremium,

        /** Premium at current observation (must be > 0). */
        double currentPremium,

        /** Underlying index level at entry (must be > 0). */
        double entryUnderlying,

        /** Underlying index level at current observation (must be > 0). */
        double currentUnderlying,

        /** Minutes elapsed since entry (must be > 0). */
        int elapsedMinutes,

        /** Optional observation timestamp (may be null). */
        Instant timestamp,

        /** Optional caller-supplied session identifier (may be null). */
        String sessionId,

        /** Optional free-text notes (may be null). */
        String notes
) {
    /**
     * Convenience factory from form/query parameters — all required fields.
     */
    public static ThetaDeltaSenseCheckRequest of(
            String underlying,
            String expiry,
            double strike,
            String optionType,
            String side,
            double entryPremium,
            double currentPremium,
            double entryUnderlying,
            double currentUnderlying,
            int elapsedMinutes
    ) {
        return new ThetaDeltaSenseCheckRequest(
                underlying, expiry, strike, optionType, side,
                entryPremium, currentPremium, entryUnderlying, currentUnderlying,
                elapsedMinutes, null, null, null
        );
    }
}
