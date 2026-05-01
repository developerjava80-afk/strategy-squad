package com.strategysquad.scope;

import com.strategysquad.ingestion.kite.KiteSubscriptionManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScopeService}.
 *
 * <p>All tests run fully in-memory. A stub {@link ScopeStore} controls persistence
 * state. A real {@link KiteSubscriptionManager} is used to verify subscription changes.
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>Activating a scope persists it and binds the instrument universe.</li>
 *   <li>Activating the same scope ID a second time is a no-op (no extra DB write).</li>
 *   <li>Activating a different scope replaces the previous one atomically.</li>
 *   <li>Deactivating clears persistence and unbinds all option instruments.</li>
 *   <li>Deactivating when no scope is active is a no-op.</li>
 *   <li>ScopeStore throwing SQLException propagates out of activate.</li>
 *   <li>restoreFromStore loads the in-memory state from persistence.</li>
 * </ol>
 */
class ScopeServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 28);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 30);

    // =========================================================================
    // Test 1 — activate persists scope and binds instruments
    // =========================================================================

    @Test
    void activate_persistsScopeAndBindsInstruments() throws SQLException {
        StubScopeStore store = new StubScopeStore();
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        ScopeService svc = new ScopeService(store, mgr);

        Scope scope = scope("NIFTY");
        ResolvedUniverse universe = universe(scope, 2);

        svc.activate(TODAY, universe);

        // Subscription should now have 2 option instruments + 2 spot
        assertEquals(2, mgr.subscribedCount());
        assertFalse(mgr.isEmpty());

        // In-memory state updated
        assertTrue(svc.isActive());
        assertTrue(svc.getActiveScope().isPresent());
        assertEquals(scope.underlying(), svc.getActiveScope().get().scope().underlying());

        // Persistence was called exactly once
        assertEquals(1, store.saveCount);
    }

    // =========================================================================
    // Test 2 — activating same scope ID is a no-op
    // =========================================================================

    @Test
    void activate_sameScopeId_isNoOp() throws SQLException {
        StubScopeStore store = new StubScopeStore();
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        ScopeService svc = new ScopeService(store, mgr);

        Scope scope = scope("NIFTY");
        ResolvedUniverse universe = universe(scope, 2);

        svc.activate(TODAY, universe);
        int savesAfterFirst = store.saveCount;

        // Activate with same scope object (same scopeId)
        svc.activate(TODAY, universe);
        assertEquals(savesAfterFirst, store.saveCount, "No DB write should occur for identical scope");
    }

    // =========================================================================
    // Test 3 — activating different scope replaces subscription atomically
    // =========================================================================

    @Test
    void activate_differentScope_replacesSubscription() throws SQLException {
        StubScopeStore store = new StubScopeStore();
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        ScopeService svc = new ScopeService(store, mgr);

        Scope scopeA = scope("NIFTY");
        svc.activate(TODAY, universe(scopeA, 2));
        assertEquals(2, mgr.subscribedCount());

        Scope scopeB = scope("BANKNIFTY");
        svc.activate(TODAY, universe(scopeB, 4));

        // Subscription replaced to the new universe's instruments
        assertEquals(4, mgr.subscribedCount());
        assertEquals("BANKNIFTY", svc.getActiveScope().get().scope().underlying());
        assertEquals(2, store.saveCount); // two DB writes total
    }

    // =========================================================================
    // Test 4 — deactivate clears persistence and unbinds instruments
    // =========================================================================

    @Test
    void deactivate_clearsPersistenceAndUnbindsInstruments() throws SQLException {
        StubScopeStore store = new StubScopeStore();
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        ScopeService svc = new ScopeService(store, mgr);

        svc.activate(TODAY, universe(scope("NIFTY"), 3));
        assertEquals(3, mgr.subscribedCount());

        svc.deactivate(TODAY);

        assertEquals(0, mgr.subscribedCount());
        assertTrue(mgr.isEmpty());
        assertFalse(svc.isActive());
        assertTrue(svc.getActiveScope().isEmpty());
        assertEquals(1, store.clearCount);
    }

    // =========================================================================
    // Test 5 — deactivate when no scope is active is a no-op
    // =========================================================================

    @Test
    void deactivate_whenNotActive_isNoOp() throws SQLException {
        StubScopeStore store = new StubScopeStore();
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        ScopeService svc = new ScopeService(store, mgr);

        // No activate() call — deactivate should silently do nothing
        assertDoesNotThrow(() -> svc.deactivate(TODAY));
        assertEquals(0, store.clearCount);
        assertEquals(0, mgr.subscribedCount());
    }

    // =========================================================================
    // Test 6 — ScopeStore exception propagates from activate
    // =========================================================================

    @Test
    void activate_storeThrows_sqlExceptionPropagates() {
        ScopeStore throwingStore = throwingScopeStore();
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        ScopeService svc = new ScopeService(throwingStore, mgr);

        Scope scope = scope("NIFTY");
        assertThrows(SQLException.class, () -> svc.activate(TODAY, universe(scope, 1)));

        // Subscription must not have been modified on failure
        assertEquals(0, mgr.subscribedCount());
        assertFalse(svc.isActive());
    }

    // =========================================================================
    // Test 7 — restoreFromStore loads in-memory state from persistence
    // =========================================================================

    @Test
    void restoreFromStore_populatesActiveScopeFromPersistence() throws SQLException {
        Scope scope = scope("NIFTY");
        String scopeId = scope.toScopeId(TODAY);
        ScopeStore.StoredScope stored = new ScopeStore.StoredScope(
                scopeId, scope, TODAY, Instant.EPOCH, Instant.EPOCH);

        StubScopeStore store = new StubScopeStore();
        store.storedScope = Optional.of(stored);

        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        ScopeService svc = new ScopeService(store, mgr);

        svc.restoreFromStore(TODAY);

        assertTrue(svc.isActive());
        assertEquals(scopeId, svc.getActiveScope().get().scopeId());

        // Subscription manager should NOT be populated by restoreFromStore alone
        // (instruments are re-bound only on explicit activate() with a resolved universe)
        assertEquals(0, mgr.subscribedCount());
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    private static Scope scope(String underlying) {
        return Scope.of(underlying, EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.AtmPct(4.0));
    }

    private static ResolvedUniverse universe(Scope scope, int instrumentCount) {
        List<InstrumentRef> instruments = new java.util.ArrayList<>();
        for (int i = 0; i < instrumentCount; i++) {
            String optType = i % 2 == 0 ? "CE" : "PE";
            String id = "INS_" + scope.underlying() + "_20260430_" + (22000 + i * 50) + "_" + optType;
            instruments.add(new InstrumentRef(
                    id, (long)(100 + i),
                    scope.underlying() + "26APR" + (22000 + i * 50) + optType,
                    optType,
                    BigDecimal.valueOf(22000 + i * 50),
                    EXPIRY, ExpiryType.WEEKLY, 50
            ));
        }
        return ResolvedUniverse.of(scope, instruments);
    }

    // =========================================================================
    // Stub ScopeStore
    // =========================================================================

    /**
     * Stub ScopeStore that stores one scope in memory and tracks call counts.
     * Subclasses ScopeStore with a dummy URL so no real DB connection is opened.
     */
    private static final class StubScopeStore extends ScopeStore {

        int saveCount  = 0;
        int clearCount = 0;
        Optional<StoredScope> storedScope = Optional.empty();

        StubScopeStore() {
            super("jdbc:unused");
        }

        @Override
        public void save(LocalDate date, Scope scope) {
            saveCount++;
            String scopeId = scope.toScopeId(date);
            storedScope = Optional.of(new StoredScope(
                    scopeId, scope, date, Instant.now(), Instant.now()));
        }

        @Override
        public Optional<StoredScope> loadActive(LocalDate date) {
            return storedScope;
        }

        @Override
        public void clear(LocalDate date) {
            clearCount++;
            storedScope = Optional.empty();
        }
    }

    /** ScopeStore that throws on save. */
    private static ScopeStore throwingScopeStore() {
        return new ScopeStore("jdbc:unused") {
            @Override
            public void save(LocalDate date, Scope scope) throws SQLException {
                throw new SQLException("simulated DB failure");
            }

            @Override
            public Optional<StoredScope> loadActive(LocalDate date) {
                return Optional.empty();
            }

            @Override
            public void clear(LocalDate date) {
                // no-op
            }
        };
    }
}
