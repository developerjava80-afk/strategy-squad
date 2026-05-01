package com.strategysquad.ingestion.kite;

import com.strategysquad.scope.InstrumentRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the mutable set of Kite instrument tokens subscribed for live polling.
 *
 * <p>Lifetime matches the active {@link com.strategysquad.scope.Scope}: a new
 * subscription is created when a scope is activated and torn down when the scope
 * is cleared. The two spot quote keys ({@code NSE:NIFTY 50} and
 * {@code NSE:NIFTY BANK}) are always included and do not count against the cap.
 *
 * <h2>Thread safety</h2>
 * The current subscription snapshot (quote-key list + quote-key-to-instrument-id map)
 * is published via an {@link AtomicReference}. Callers that need an atomic snapshot
 * of both lists — e.g. the poller before building a Kite {@code /quote} URL — should
 * call {@link #snapshot()} once and hold the result for the duration of the poll cycle.
 * This prevents the poller from seeing a hybrid of the old and new subscription when
 * a scope swap races with an in-flight poll.
 *
 * <h2>Hard cap</h2>
 * Option instrument tokens are capped at {@value #DEFAULT_TOKEN_CAP}. The two
 * spot keys are always appended after the cap is applied and are never counted.
 * If {@link #bind(List)} is called with more instruments than the cap allows,
 * the list is silently truncated. This mirrors the hard-cap behaviour in
 * {@link com.strategysquad.scope.UniverseResolver}.
 */
public final class KiteSubscriptionManager {

    /** Maximum option instrument tokens that may be subscribed at once. */
    public static final int DEFAULT_TOKEN_CAP = 250;

    private final int tokenCap;

    /**
     * Immutable snapshot of the current subscription.
     * The poller replaces this atomically on every scope swap.
     */
    private final AtomicReference<Subscription> current;

    /**
     * Creates a manager with the default token cap ({@value #DEFAULT_TOKEN_CAP}).
     */
    public KiteSubscriptionManager() {
        this(DEFAULT_TOKEN_CAP);
    }

    /**
     * Creates a manager with a custom token cap. Useful in tests.
     *
     * @param tokenCap maximum number of option instrument tokens (excluding spot keys)
     */
    public KiteSubscriptionManager(int tokenCap) {
        if (tokenCap < 1) throw new IllegalArgumentException("tokenCap must be >= 1");
        this.tokenCap = tokenCap;
        this.current = new AtomicReference<>(Subscription.EMPTY);
    }

    // =========================================================================
    // Mutation API
    // =========================================================================

    /**
     * Replaces the entire subscription with the instruments from a resolved universe.
     *
     * <p>If the instrument list exceeds {@link #tokenCap}, the list is truncated to
     * exactly {@code tokenCap} entries (ordered by the list's own iteration order).
     * The two spot quote keys are appended unconditionally.
     *
     * <p>This is the primary entry point: called by
     * {@link com.strategysquad.scope.ScopeService} when a new scope is activated.
     *
     * @param instruments the instruments resolved for the new scope
     */
    public void bind(List<InstrumentRef> instruments) {
        Objects.requireNonNull(instruments, "instruments must not be null");
        current.set(buildSubscription(instruments));
    }

    /**
     * Clears the subscription entirely.
     *
     * <p>After this call, {@link #snapshot()} returns a subscription containing
     * only the two spot quote keys. Called by
     * {@link com.strategysquad.scope.ScopeService} when a scope is deactivated.
     */
    public void unbindAll() {
        current.set(Subscription.EMPTY);
    }

    // =========================================================================
    // Query API
    // =========================================================================

    /**
     * Returns an atomic snapshot of the current subscription.
     *
     * <p>Callers that need a consistent view of both {@code quoteKeys} and
     * {@code quoteKeyToId} for a single poll cycle must call this method once and
     * use the returned {@link Subscription} exclusively. Calling
     * {@link #quoteKeys()} and {@link #quoteKeyToId()} separately is not atomic.
     */
    public Subscription snapshot() {
        return current.get();
    }

    /**
     * Convenience: returns the quote-key list from the current snapshot.
     * Use {@link #snapshot()} when atomicity with the ID map is required.
     */
    public List<String> quoteKeys() {
        return current.get().quoteKeys();
    }

    /**
     * Convenience: returns the quote-key-to-instrument-id map from the current snapshot.
     * Use {@link #snapshot()} when atomicity with the key list is required.
     */
    public Map<String, String> quoteKeyToId() {
        return current.get().quoteKeyToId();
    }

    /** Returns the number of option instruments currently subscribed (excluding spot keys). */
    public int subscribedCount() {
        return current.get().optionCount();
    }

    /** Returns true when no scope instruments are subscribed (only spot keys or empty). */
    public boolean isEmpty() {
        return current.get().optionCount() == 0;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private Subscription buildSubscription(List<InstrumentRef> instruments) {
        // Apply hard cap — silently truncate; callers (UniverseResolver) already flag truncation
        List<InstrumentRef> capped = instruments.size() > tokenCap
                ? instruments.subList(0, tokenCap)
                : instruments;

        // quoteKeyToId: NFO:<tradingSymbol> → instrumentId
        Map<String, String> keyToId = new HashMap<>(capped.size() * 2 + 4);
        // quoteKeys: spot keys first, then option keys (preserves deterministic order)
        List<String> keys = new ArrayList<>(capped.size() + 2);
        keys.add(KiteTickerAdapter.NIFTY_QUOTE_KEY);
        keys.add(KiteTickerAdapter.BANKNIFTY_QUOTE_KEY);

        for (InstrumentRef ref : capped) {
            String quoteKey = "NFO:" + ref.tradingSymbol();
            keys.add(quoteKey);
            keyToId.put(quoteKey, ref.instrumentId());
        }

        return new Subscription(
                Collections.unmodifiableList(keys),
                Collections.unmodifiableMap(keyToId),
                capped.size()
        );
    }

    // =========================================================================
    // Subscription snapshot
    // =========================================================================

    /**
     * An immutable, consistent snapshot of the subscription at a point in time.
     *
     * <p>The poller takes one snapshot per poll cycle so the quote-key list and
     * the ID map are always aligned. The snapshot is replaced atomically when a
     * scope swap occurs.
     */
    public static final class Subscription {

        /** Sentinel used when no scope is active. Contains only the two spot keys. */
        static final Subscription EMPTY = new Subscription(
                List.of(KiteTickerAdapter.NIFTY_QUOTE_KEY, KiteTickerAdapter.BANKNIFTY_QUOTE_KEY),
                Map.of(),
                0
        );

        private final List<String> quoteKeys;
        private final Map<String, String> quoteKeyToId;
        private final int optionCount;

        private Subscription(List<String> quoteKeys, Map<String, String> quoteKeyToId, int optionCount) {
            this.quoteKeys    = quoteKeys;
            this.quoteKeyToId = quoteKeyToId;
            this.optionCount  = optionCount;
        }

        /**
         * Quote keys to pass to the Kite {@code /quote} API.
         * Spot keys are always first; option keys follow.
         */
        public List<String> quoteKeys() { return quoteKeys; }

        /**
         * Map from {@code "NFO:<tradingSymbol>"} to {@code instrumentId}.
         * Spot keys are never present in this map.
         */
        public Map<String, String> quoteKeyToId() { return quoteKeyToId; }

        /** Number of option instrument keys (excluding the two spot keys). */
        public int optionCount() { return optionCount; }

        /** Total keys including the two spot keys. */
        public int totalCount() { return quoteKeys.size(); }
    }
}
