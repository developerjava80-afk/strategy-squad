package com.strategysquad.scope;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The boundary object for all scoped operations.
 *
 * <p>A {@code Scope} is the single authoritative statement of what the user
 * is trading today: which underlying, which expiry, which strategy, how wide
 * a strike window, and how many candidates to surface. Every controller method
 * that touches live data takes a {@code Scope} (or a {@code scopeId} referencing
 * the active scope) as required input. Unbounded operations are rejected at the
 * controller boundary before this object is constructed.
 *
 * <p><strong>Domain invariants preserved by construction:</strong>
 * <ul>
 *   <li>Underlying must be {@code "NIFTY"} or {@code "BANKNIFTY"}.
 *   <li>{@code expiryType} must match the actual expiry in {@code instrument_master};
 *       the resolver enforces this — weekly and monthly are never mixed.
 *   <li>{@code maxCandidates} is clamped to [1, {@value #HARD_CAP}].
 * </ul>
 *
 * <p>Scopes are day-bounded. A scope persisted on 2026-04-28 is invalid on
 * 2026-04-29 and must be re-activated by the user.
 */
public record Scope(
        String underlying,
        LocalDate expiry,
        ExpiryType expiryType,
        StrategyKind strategy,
        StrikeWindow strikeWindow,
        int maxCandidates
) {

    /** Hard cap on candidates. Requests above this are rejected with {@code MAX_CANDIDATES_EXCEEDED}. */
    public static final int HARD_CAP = 100;

    /** Default candidate count used when the caller does not specify one. */
    public static final int DEFAULT_MAX_CANDIDATES = 30;

    /** Valid underlying values. */
    private static final java.util.Set<String> VALID_UNDERLYINGS =
            java.util.Set.of("NIFTY", "BANKNIFTY");

    public Scope {
        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(expiry, "expiry must not be null");
        Objects.requireNonNull(expiryType, "expiryType must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(strikeWindow, "strikeWindow must not be null");

        if (!VALID_UNDERLYINGS.contains(underlying)) {
            throw new IllegalArgumentException(
                    "underlying must be one of " + VALID_UNDERLYINGS + ", got: " + underlying);
        }
        if (maxCandidates < 1 || maxCandidates > HARD_CAP) {
            throw new IllegalArgumentException(
                    "maxCandidates must be in [1, " + HARD_CAP + "], got: " + maxCandidates);
        }
    }

    /**
     * Builds a Scope with the default candidate count of {@value #DEFAULT_MAX_CANDIDATES}.
     */
    public static Scope of(
            String underlying,
            LocalDate expiry,
            ExpiryType expiryType,
            StrategyKind strategy,
            StrikeWindow strikeWindow
    ) {
        return new Scope(underlying, expiry, expiryType, strategy, strikeWindow, DEFAULT_MAX_CANDIDATES);
    }

    /**
     * Returns a deterministic scope ID for use as the {@code scope_id} column in
     * {@code scope_state}.
     *
     * <p>Format: {@code S_<YYYYMMDD>_<UNDERLYING>_<EXPIRYDATE>_<W|M>_001}
     * <br>Example: {@code S_20260428_NIFTY_20260430_W_001}
     */
    public String toScopeId(LocalDate tradingDate) {
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");
        String datePart  = tradingDate.toString().replace("-", "");
        String expiryPart = expiry.toString().replace("-", "");
        String typePart  = expiryType == ExpiryType.WEEKLY ? "W" : "M";
        return "S_" + datePart + "_" + underlying + "_" + expiryPart + "_" + typePart + "_001";
    }
}
