package com.strategysquad.ingestion.kite;

import com.strategysquad.scope.ExpiryType;
import com.strategysquad.scope.InstrumentRef;
import com.strategysquad.scope.StrategyKind;
import com.strategysquad.scope.StrikeWindow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KiteSubscriptionManager}.
 *
 * <p>All tests run fully in-memory without any JDBC or Kite HTTP calls.
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>Empty manager contains only the two spot keys.</li>
 *   <li>{@code bind} populates quote keys and quote-key-to-id map correctly.</li>
 *   <li>{@code unbindAll} resets to spot-keys-only state.</li>
 *   <li>Hard cap: binding more instruments than the cap silently truncates.</li>
 *   <li>Snapshot atomicity: concurrent bind + snapshot never produces a hybrid state.</li>
 *   <li>Spot keys are never included in the {@code quoteKeyToId} map.</li>
 *   <li>Spot keys always appear first in the quote-key list.</li>
 * </ol>
 */
class KiteSubscriptionManagerTest {

    private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 30);

    // =========================================================================
    // Test 1 — empty manager
    // =========================================================================

    @Test
    void emptyManager_containsOnlySpotKeys() {
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();

        KiteSubscriptionManager.Subscription sub = mgr.snapshot();
        assertEquals(2, sub.totalCount());
        assertEquals(0, sub.optionCount());
        assertTrue(sub.quoteKeyToId().isEmpty());
        assertTrue(sub.quoteKeys().contains(KiteTickerAdapter.NIFTY_QUOTE_KEY));
        assertTrue(sub.quoteKeys().contains(KiteTickerAdapter.BANKNIFTY_QUOTE_KEY));
    }

    // =========================================================================
    // Test 2 — bind populates correctly
    // =========================================================================

    @Test
    void bind_populatesQuoteKeysAndMap() {
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        List<InstrumentRef> instruments = List.of(
                ref("INS_NIFTY_20260430_22000_CE", 111L, "NIFTY26APR22000CE", "CE"),
                ref("INS_NIFTY_20260430_22000_PE", 222L, "NIFTY26APR22000PE", "PE")
        );

        mgr.bind(instruments);
        KiteSubscriptionManager.Subscription sub = mgr.snapshot();

        assertEquals(2, sub.optionCount());
        assertEquals(4, sub.totalCount()); // 2 spot + 2 option

        // Spot keys must be present in quoteKeys but NOT in the map
        assertTrue(sub.quoteKeys().contains(KiteTickerAdapter.NIFTY_QUOTE_KEY));
        assertTrue(sub.quoteKeys().contains(KiteTickerAdapter.BANKNIFTY_QUOTE_KEY));
        assertFalse(sub.quoteKeyToId().containsKey(KiteTickerAdapter.NIFTY_QUOTE_KEY));
        assertFalse(sub.quoteKeyToId().containsKey(KiteTickerAdapter.BANKNIFTY_QUOTE_KEY));

        // Option keys must be in both lists and the map
        assertTrue(sub.quoteKeys().contains("NFO:NIFTY26APR22000CE"));
        assertTrue(sub.quoteKeys().contains("NFO:NIFTY26APR22000PE"));
        assertEquals("INS_NIFTY_20260430_22000_CE", sub.quoteKeyToId().get("NFO:NIFTY26APR22000CE"));
        assertEquals("INS_NIFTY_20260430_22000_PE", sub.quoteKeyToId().get("NFO:NIFTY26APR22000PE"));
    }

    // =========================================================================
    // Test 3 — unbindAll resets to spot-only
    // =========================================================================

    @Test
    void unbindAll_resetsToSpotKeysOnly() {
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        mgr.bind(List.of(ref("INS_NIFTY_20260430_22000_CE", 111L, "NIFTY26APR22000CE", "CE")));
        assertEquals(1, mgr.subscribedCount());

        mgr.unbindAll();

        KiteSubscriptionManager.Subscription sub = mgr.snapshot();
        assertEquals(0, sub.optionCount());
        assertEquals(2, sub.totalCount());
        assertTrue(sub.quoteKeyToId().isEmpty());
        assertTrue(mgr.isEmpty());
    }

    // =========================================================================
    // Test 4 — hard cap silently truncates
    // =========================================================================

    @Test
    void hardCap_silentlyTruncatesExcessInstruments() {
        int cap = 5;
        KiteSubscriptionManager mgr = new KiteSubscriptionManager(cap);

        List<InstrumentRef> instruments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            instruments.add(ref(
                    "INS_NIFTY_20260430_" + (22000 + i * 50) + "_CE",
                    (long)(100 + i),
                    "NIFTY26APR" + (22000 + i * 50) + "CE",
                    "CE"
            ));
        }

        mgr.bind(instruments);
        KiteSubscriptionManager.Subscription sub = mgr.snapshot();

        assertEquals(cap, sub.optionCount());
        assertEquals(cap + 2, sub.totalCount()); // cap options + 2 spot
    }

    // =========================================================================
    // Test 5 — concurrent bind + snapshot never sees hybrid state
    // =========================================================================

    @Test
    void concurrentSwap_snapshotIsAlwaysConsistent() throws InterruptedException {
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();

        // Two sets of instruments with disjoint trading symbols
        List<InstrumentRef> setA = List.of(
                ref("INS_NIFTY_20260430_22000_CE", 1L, "AAAAAAAACE", "CE"),
                ref("INS_NIFTY_20260430_22000_PE", 2L, "AAAAAAAAAPE", "PE")
        );
        List<InstrumentRef> setB = List.of(
                ref("INS_NIFTY_20260507_22500_CE", 3L, "BBBBBBBBBCE", "CE"),
                ref("INS_NIFTY_20260507_22500_PE", 4L, "BBBBBBBBBPE", "PE")
        );

        AtomicBoolean hybridDetected = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(2);

        // Writer thread: alternately bind setA and setB
        Thread writer = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 500; i++) {
                    mgr.bind(i % 2 == 0 ? setA : setB);
                }
            } catch (InterruptedException ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Reader thread: verifies that every snapshot is internally consistent
        Thread reader = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 500; i++) {
                    KiteSubscriptionManager.Subscription sub = mgr.snapshot();
                    // Each quoteKey in the quoteKeyToId map must appear in quoteKeys
                    for (String key : sub.quoteKeyToId().keySet()) {
                        if (!sub.quoteKeys().contains(key)) {
                            hybridDetected.set(true);
                        }
                    }
                    // The optionCount must match the quoteKeyToId size
                    if (sub.optionCount() != sub.quoteKeyToId().size()) {
                        hybridDetected.set(true);
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        writer.start();
        reader.start();
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Test did not finish in time");
        assertFalse(hybridDetected.get(), "Detected hybrid subscription snapshot — atomicity violated");
    }

    // =========================================================================
    // Test 6 — spot keys always appear first
    // =========================================================================

    @Test
    void spotKeys_alwaysAppearFirstInQuoteKeyList() {
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        mgr.bind(List.of(ref("INS_NIFTY_20260430_22000_CE", 1L, "NIFTY26APR22000CE", "CE")));

        List<String> keys = mgr.quoteKeys();
        assertEquals(KiteTickerAdapter.NIFTY_QUOTE_KEY, keys.get(0));
        assertEquals(KiteTickerAdapter.BANKNIFTY_QUOTE_KEY, keys.get(1));
    }

    // =========================================================================
    // Test 7 — sequential bind replaces previous subscription
    // =========================================================================

    @Test
    void sequentialBind_replacesSubscription() {
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();

        mgr.bind(List.of(ref("INS_NIFTY_20260430_22000_CE", 1L, "NIFTY26APR22000CE", "CE")));
        assertEquals(1, mgr.subscribedCount());
        assertTrue(mgr.quoteKeyToId().containsKey("NFO:NIFTY26APR22000CE"));

        // Bind a completely different set
        mgr.bind(List.of(
                ref("INS_NIFTY_20260430_22500_CE", 3L, "NIFTY26APR22500CE", "CE"),
                ref("INS_NIFTY_20260430_22500_PE", 4L, "NIFTY26APR22500PE", "PE")
        ));
        assertEquals(2, mgr.subscribedCount());
        assertFalse(mgr.quoteKeyToId().containsKey("NFO:NIFTY26APR22000CE"),
                "Old key must not remain after re-bind");
        assertTrue(mgr.quoteKeyToId().containsKey("NFO:NIFTY26APR22500CE"));
        assertTrue(mgr.quoteKeyToId().containsKey("NFO:NIFTY26APR22500PE"));
    }

    // =========================================================================
    // Test 8 — snapshotted map is unmodifiable
    // =========================================================================

    @Test
    void snapshot_returnsUnmodifiableCollections() {
        KiteSubscriptionManager mgr = new KiteSubscriptionManager();
        mgr.bind(List.of(ref("INS_NIFTY_20260430_22000_CE", 1L, "NIFTY26APR22000CE", "CE")));
        KiteSubscriptionManager.Subscription sub = mgr.snapshot();

        assertThrows(UnsupportedOperationException.class,
                () -> sub.quoteKeys().add("NFO:MUTATE"));
        assertThrows(UnsupportedOperationException.class,
                () -> sub.quoteKeyToId().put("NFO:MUTATE", "INS_MUTATE"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static InstrumentRef ref(String instrumentId, long kiteToken,
                                     String tradingSymbol, String optionType) {
        return new InstrumentRef(
                instrumentId,
                kiteToken,
                tradingSymbol,
                optionType,
                BigDecimal.valueOf(22000),
                EXPIRY,
                ExpiryType.WEEKLY,
                50
        );
    }
}
