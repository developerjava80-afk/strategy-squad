package com.strategysquad.scope;

import com.strategysquad.ingestion.kite.KiteSubscriptionManager;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * Single owner of the active {@link Scope} for the current trading day.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Persist scope changes to {@link ScopeStore} (day-scoped WAL table).</li>
 *   <li>Drive the live subscription via {@link KiteSubscriptionManager}: bind
 *       the resolved universe on activation, unbind on deactivation.</li>
 *   <li>Enforce the "one active scope per day" invariant: activating when a scope
 *       is already active replaces it atomically.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * All public methods are {@code synchronized}. The service is a singleton
 * shared between the HTTP request handlers and the live session.
 *
 * <h2>Scope lifecycle</h2>
 * <pre>
 *   POST /api/scope  → activate(scope, universe)
 *   GET  /api/scope  → getActiveScope()
 *   DELETE /api/scope → deactivate(tradingDate)
 * </pre>
 *
 * <h2>Relationship with KiteSubscriptionManager</h2>
 * {@code ScopeService} does not start or stop the Kite ticker session. It only
 * mutates the subscription set inside the manager. The session polls
 * {@link KiteSubscriptionManager#snapshot()} on every cycle, so the new
 * universe is picked up automatically on the next poll without a session restart.
 */
public final class ScopeService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ScopeStore scopeStore;
    private final KiteSubscriptionManager subscriptionManager;

    /**
     * The scope that is currently active (null when no scope has been activated
     * for this trading day, or after an explicit deactivation).
     */
    private volatile ScopeStore.StoredScope activeScope;

    /**
     * Creates a ScopeService.
     *
     * @param scopeStore          day-scoped persistence layer
     * @param subscriptionManager live instrument subscription surface
     */
    public ScopeService(ScopeStore scopeStore, KiteSubscriptionManager subscriptionManager) {
        this.scopeStore          = Objects.requireNonNull(scopeStore,          "scopeStore must not be null");
        this.subscriptionManager = Objects.requireNonNull(subscriptionManager, "subscriptionManager must not be null");
        this.activeScope         = null;
    }

    // =========================================================================
    // Activation
    // =========================================================================

    /**
     * Activates the given scope for today, replacing any previously active scope.
     *
     * <p>Steps performed atomically (under {@code this} lock):
     * <ol>
     *   <li>Persist the new scope to {@link ScopeStore} (delete-then-insert WAL pattern).</li>
     *   <li>Bind the resolved universe's instrument list to {@link KiteSubscriptionManager}.</li>
     *   <li>Update the in-memory active scope.</li>
     * </ol>
     *
     * <p>If the scope is already active with the same scope ID (same underlying, expiry,
     * strategy and strike window on the same trading day), this is a no-op: no DB write
     * is issued and no subscription change is made.
     *
     * @param tradingDate the current IST trading date
     * @param universe    the resolved universe for the scope (produced by
     *                    {@link UniverseResolver#resolve})
     * @throws SQLException if the persistence write fails
     */
    public synchronized void activate(LocalDate tradingDate, ResolvedUniverse universe)
            throws SQLException {
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");
        Objects.requireNonNull(universe,    "universe must not be null");

        Scope scope = universe.scope();
        String newScopeId = scope.toScopeId(tradingDate);

        // No-op if the exact same scope is already active
        if (activeScope != null && newScopeId.equals(activeScope.scopeId())) {
            return;
        }

        // 1. Persist — delete-then-insert (QuestDB WAL tables do not support ON CONFLICT)
        scopeStore.save(tradingDate, scope);

        // 2. Swap subscription atomically — takes effect on the next poll cycle
        subscriptionManager.bind(universe.instruments());

        // 3. Update in-memory state directly — do NOT read back from the WAL table.
        //    QuestDB WAL writes are async: the row may not be visible immediately after
        //    INSERT, so loadActive() can return empty and leave activeScope=null even
        //    though the scope was just persisted. Build the StoredScope from the data
        //    we already hold; the DB row is only needed for cross-restart recovery.
        Instant now = Instant.now();
        activeScope = new ScopeStore.StoredScope(newScopeId, scope, tradingDate, now, now);

        System.out.printf(
                "[scope-service] scope.activated underlying=%s expiry=%s expiryType=%s " +
                "strategy=%s universe_size=%d subscribed=%d%n",
                scope.underlying(),
                scope.expiry(),
                scope.expiryType().name(),
                scope.strategy().name(),
                universe.instruments().size(),
                subscriptionManager.subscribedCount());
    }

    // =========================================================================
    // Deactivation
    // =========================================================================

    /**
     * Deactivates the current scope for today.
     *
     * <p>Clears the {@code scope_state} row for today, unbinds all option
     * instruments from the subscription (spot keys remain), and clears the
     * in-memory active scope. This is a no-op when no scope is currently active.
     *
     * @param tradingDate the current IST trading date
     * @throws SQLException if the persistence clear fails
     */
    public synchronized void deactivate(LocalDate tradingDate) throws SQLException {
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");

        if (activeScope == null) {
            return; // nothing to deactivate
        }

        String scopeId = activeScope.scopeId();

        // 1. Remove from persistence
        scopeStore.clear(tradingDate);

        // 2. Unbind all option instruments — spot keys remain
        subscriptionManager.unbindAll();

        // 3. Clear in-memory state
        activeScope = null;

        System.out.printf("[scope-service] Deactivated scope %s. Subscription reduced to 2 (spot keys only).%n",
                scopeId);
    }

    // =========================================================================
    // Query
    // =========================================================================

    /**
     * Returns the currently active scope, or {@link Optional#empty()} if no scope
     * is active for today.
     */
    public synchronized Optional<ScopeStore.StoredScope> getActiveScope() {
        if (activeScope == null) {
            try {
                activeScope = scopeStore.loadActive(LocalDate.now(IST)).orElse(null);
            } catch (SQLException ignored) {
                activeScope = null;
            }
        }
        return Optional.ofNullable(activeScope);
    }

    /**
     * Returns true when a scope is currently active.
     */
    public synchronized boolean isActive() {
        return activeScope != null;
    }

    /**
     * Restores the active scope from persistence on application startup.
     *
     * <p>Called by {@link com.strategysquad.research.ResearchConsoleServer} after
     * the HTTP server is wired up. If a scope row exists for today and its expiry
     * is still valid (verified by the caller through
     * {@link BootstrapMetadataService}), the caller should pass the stored scope
     * back through {@link #activate(LocalDate, ResolvedUniverse)} so the
     * subscription manager is populated.
     *
     * <p>This method only restores the in-memory state from the DB without touching
     * the subscription manager — useful when the subscription manager starts empty
     * (scope.enabled=true, login but no scope picked yet).
     *
     * @param tradingDate the current IST trading date
     * @throws SQLException if the DB load fails
     */
    public synchronized void restoreFromStore(LocalDate tradingDate) throws SQLException {
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");
        activeScope = scopeStore.loadActive(tradingDate).orElse(null);
        if (activeScope != null) {
            System.out.printf("[scope-service] Restored scope %s from persistence.%n",
                    activeScope.scopeId());
        }
    }
}
