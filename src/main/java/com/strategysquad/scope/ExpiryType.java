package com.strategysquad.scope;

/**
 * Distinguishes weekly from monthly NSE option expiries.
 *
 * <p><strong>Domain invariant:</strong> weekly and monthly expiries must never
 * be mixed in the same scope, cohort, or metric. This is enforced at the
 * {@link Scope} boundary — {@link com.strategysquad.scope.ScopeStore} persists
 * the type alongside each scope row so it can be validated on restore.
 *
 * <p>Matches the {@code expiry_type} SYMBOL column in {@code instrument_master}
 * and {@code scope_state}.
 */
public enum ExpiryType {

    /**
     * Thursday weekly expiry (or Wednesday when Thursday is a holiday).
     * Short-duration, higher time-decay — the primary expiry type for
     * most theta-decay strategies on this platform.
     */
    WEEKLY,

    /**
     * Last-Thursday-of-month monthly expiry.
     * Longer duration, lower time-decay premium per day — used for
     * longer-dated research cohorts. Never mixed with WEEKLY in a scope.
     */
    MONTHLY
}
