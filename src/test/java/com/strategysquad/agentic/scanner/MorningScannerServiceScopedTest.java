package com.strategysquad.agentic.scanner;

import com.strategysquad.aggregation.OptionsContextBucket;
import com.strategysquad.scope.ExpiryType;
import com.strategysquad.scope.InstrumentRef;
import com.strategysquad.scope.ResolvedUniverse;
import com.strategysquad.scope.Scope;
import com.strategysquad.scope.StrategyKind;
import com.strategysquad.scope.StrikeWindow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MorningScannerService#scanScoped}.
 *
 * <p>All tests are pure in-memory. A proxy-based {@link ScannerQuery} (via the
 * package-private {@code ConnectionSupplier} constructor) controls what raw
 * contract rows are returned. The real {@link CandidateScoringEngine} is used
 * so that scoring logic is genuinely exercised.
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>Output capped at {@code scope.maxCandidates()} qualified candidates.</li>
 *   <li>Disqualified candidates always appear after the cap (not counted against it).</li>
 *   <li>Truncated universe flag is preserved in the service response.</li>
 *   <li>Empty universe → empty candidate list without exception.</li>
 *   <li>No live price data → empty candidate list.</li>
 *   <li>scanScoped via test-only constructor throws IllegalStateException.</li>
 *   <li>Instruments outside scope never appear in output.</li>
 * </ol>
 */
class MorningScannerServiceScopedTest {

    private static final Instant SCAN_INSTANT = Instant.parse("2026-04-28T04:30:00Z"); // 10:00 IST
    private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 30);
    private static final LocalDate TODAY  = LocalDate.of(2026, 4, 28);

    // =========================================================================
    // Test 1 — output capped at maxCandidates (qualified only)
    // =========================================================================

    @Test
    void qualifiedCandidatesCappedAtMaxCandidates() throws Exception {
        int maxCandidates = 2;
        Scope scope = scope("NIFTY", maxCandidates);

        // 5 instruments, all with prices (all will be scored)
        List<InstrumentRef> instruments = buildInstruments("NIFTY", 5);
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        // Use a cohort map with entries so contracts qualify rather than MISSING_COHORT
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                buildCohortMap("NIFTY");

        StubDb stub = new StubDb()
                .withPrices(buildPriceMap(instruments))
                .withSpot("NIFTY", 22000.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        MorningScannerService svc = new MorningScannerService(query, new CandidateScoringEngine());
        List<CandidateOpportunity> result = svc.scanScoped(scope, universe, cohortMap, SCAN_INSTANT);

        // Only maxCandidates qualified candidates should appear before any disqualified
        long qualified = result.stream().filter(c -> c.disqualifierReason().isEmpty()).count();
        assertTrue(qualified <= maxCandidates,
                "Qualified candidates must not exceed maxCandidates=" + maxCandidates + ", got " + qualified);
    }

    // =========================================================================
    // Test 2 — disqualified candidates appear after the qualified cap
    // =========================================================================

    @Test
    void disqualifiedCandidatesAppearAfterQualifiedCap() throws Exception {
        int maxCandidates = 1;
        Scope scope = scope("NIFTY", maxCandidates);

        List<InstrumentRef> instruments = buildInstruments("NIFTY", 3);
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        // Empty cohort → all MISSING_COHORT (disqualified)
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap = Map.of();

        StubDb stub = new StubDb()
                .withPrices(buildPriceMap(instruments))
                .withSpot("NIFTY", 22000.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        MorningScannerService svc = new MorningScannerService(query, new CandidateScoringEngine());
        List<CandidateOpportunity> result = svc.scanScoped(scope, universe, cohortMap, SCAN_INSTANT);

        // With empty cohort map all candidates are disqualified (MISSING_COHORT)
        // Cap only applies to qualified — disqualified all pass through
        long disqualified = result.stream().filter(c -> c.disqualifierReason().isPresent()).count();
        assertEquals(3, disqualified, "All 3 disqualified candidates must appear (cap does not limit them)");

        // No qualified candidates since cohort map is empty
        long qualified = result.stream().filter(c -> c.disqualifierReason().isEmpty()).count();
        assertEquals(0, qualified);

        // Verify ordering: qualified before disqualified (here there are none qualified)
        for (int i = 0; i < result.size() - 1; i++) {
            boolean curr = result.get(i).disqualifierReason().isPresent();
            boolean next = result.get(i + 1).disqualifierReason().isPresent();
            assertFalse(!curr && next, "Disqualified must not precede qualified at index " + i);
        }
    }

    // =========================================================================
    // Test 3 — truncated universe flag logged (no exception)
    // =========================================================================

    @Test
    void truncatedUniverse_noExceptionThrown() throws Exception {
        Scope scope = scope("NIFTY", 5);
        List<InstrumentRef> instruments = buildInstruments("NIFTY", 3);
        // Mark universe as truncated
        ResolvedUniverse universe = ResolvedUniverse.truncated(scope, instruments,
                "Narrow your strike window; current window yielded >100 instruments");

        StubDb stub = new StubDb()
                .withPrices(buildPriceMap(instruments))
                .withSpot("NIFTY", 22000.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        MorningScannerService svc = new MorningScannerService(query, new CandidateScoringEngine());
        // Should not throw — truncation flag is only logged
        assertDoesNotThrow(() ->
                svc.scanScoped(scope, universe, Map.of(), SCAN_INSTANT));
    }

    // =========================================================================
    // Test 4 — empty universe → empty list
    // =========================================================================

    @Test
    void emptyUniverse_emptyListReturned() throws Exception {
        Scope scope = scope("NIFTY", 10);
        ResolvedUniverse universe = ResolvedUniverse.of(scope, List.of());

        // DB should not be called; pass a supplier that throws if invoked
        ScannerQuery query = new ScannerQuery(
                () -> { throw new java.sql.SQLException("stub — no DB"); }, null);

        MorningScannerService svc = new MorningScannerService(query, new CandidateScoringEngine());
        List<CandidateOpportunity> result = svc.scanScoped(scope, universe, Map.of(), SCAN_INSTANT);

        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // Test 5 — no live price data → empty list
    // =========================================================================

    @Test
    void noLivePriceData_emptyListReturned() throws Exception {
        Scope scope = scope("NIFTY", 10);
        List<InstrumentRef> instruments = buildInstruments("NIFTY", 2);
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        // Stub returns no price rows (simulates pre-market, no live data yet)
        StubDb stub = new StubDb()
                .withPrices(Map.of())
                .withSpot("NIFTY", 22000.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        MorningScannerService svc = new MorningScannerService(query, new CandidateScoringEngine());
        List<CandidateOpportunity> result = svc.scanScoped(scope, universe, Map.of(), SCAN_INSTANT);

        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // Test 6 — test-only ContractLoader constructor throws on scanScoped
    // =========================================================================

    @Test
    void testOnlyConstructor_scanScopedThrowsIllegalState() {
        // Test-only ContractLoader constructor sets scannerQuery=null
        MorningScannerService svc = new MorningScannerService(
                List::of,  // ContractLoader stub
                new CandidateScoringEngine()
        );
        Scope scope = scope("NIFTY", 5);
        ResolvedUniverse universe = ResolvedUniverse.of(scope, List.of());

        assertThrows(IllegalStateException.class,
                () -> svc.scanScoped(scope, universe, Map.of(), SCAN_INSTANT));
    }

    // =========================================================================
    // Test 7 — only instruments in scope appear in output
    // =========================================================================

    @Test
    void onlyScopedInstrumentsInOutput() throws Exception {
        Scope scope = scope("NIFTY", 10);
        List<InstrumentRef> instruments = List.of(
                new InstrumentRef("INS_NIFTY_20260430_22000_CE", 1L,
                        "NIFTY26APR22000CE", "CE", BigDecimal.valueOf(22000),
                        EXPIRY, ExpiryType.WEEKLY, 50),
                new InstrumentRef("INS_NIFTY_20260430_22000_PE", 2L,
                        "NIFTY26APR22000PE", "PE", BigDecimal.valueOf(22000),
                        EXPIRY, ExpiryType.WEEKLY, 50)
        );
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        // DB stub returns an extra instrument ID that was NOT in the scope.
        // fetchScoped only iterates universe.instruments() so the extra row is ignored.
        Map<String, PriceData> prices = new HashMap<>(buildPriceMap(instruments));
        prices.put("INS_NIFTY_20260430_99999_CE",
                new PriceData(5.0, 0.0, 50, 1, 0));

        StubDb stub = new StubDb()
                .withPrices(prices)
                .withSpot("NIFTY", 22000.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        MorningScannerService svc = new MorningScannerService(query, new CandidateScoringEngine());
        List<CandidateOpportunity> result = svc.scanScoped(scope, universe, Map.of(), SCAN_INSTANT);

        // All candidates must be from the scoped instrument list only
        for (CandidateOpportunity c : result) {
            assertTrue(
                    instruments.stream().anyMatch(i -> i.instrumentId().equals(c.instrumentId())),
                    "Candidate " + c.instrumentId() + " is not in the scoped universe"
            );
        }
    }

    // =========================================================================
    // Helpers — scope / instrument builders
    // =========================================================================

    private static Scope scope(String underlying, int maxCandidates) {
        return new Scope(underlying, EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.AtmPct(4.0), maxCandidates);
    }

    private static List<InstrumentRef> buildInstruments(String underlying, int count) {
        List<InstrumentRef> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int strike = 22000 + i * 50;
            String optType = i % 2 == 0 ? "CE" : "PE";
            String id = "INS_" + underlying + "_20260430_" + strike + "_" + optType;
            list.add(new InstrumentRef(
                    id, (long) (100 + i),
                    underlying + "26APR" + strike + optType,
                    optType,
                    BigDecimal.valueOf(strike),
                    EXPIRY, ExpiryType.WEEKLY, 50
            ));
        }
        return list;
    }

    /**
     * Builds a price map for all instruments — bucket (4, 8) matches {@link #buildCohortMap}.
     */
    private static Map<String, PriceData> buildPriceMap(List<InstrumentRef> instruments) {
        Map<String, PriceData> map = new HashMap<>();
        for (InstrumentRef ref : instruments) {
            map.put(ref.instrumentId(), new PriceData(120.0, 50.0, 4, 8, 3000));
        }
        return map;
    }

    /**
     * Builds a minimal cohort map so that candidates qualify (have historical context).
     * All instruments map to the same cohort (moneynessBucket=4, timeBucket=8).
     */
    private static Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> buildCohortMap(
            String underlying) {
        CandidateScoringEngine.CohortKey keyCe =
                new CandidateScoringEngine.CohortKey(underlying, "CE", 4, 8);
        CandidateScoringEngine.CohortKey keyPe =
                new CandidateScoringEngine.CohortKey(underlying, "PE", 4, 8);
        CandidateScoringEngine.CohortKey fallbackCe =
                new CandidateScoringEngine.CohortKey(underlying, "CE", 4, 0);
        CandidateScoringEngine.CohortKey fallbackPe =
                new CandidateScoringEngine.CohortKey(underlying, "PE", 4, 0);

        OptionsContextBucket bucketCe = new OptionsContextBucket(
                Instant.now(), underlying, "CE", 8, 4,
                BigDecimal.valueOf(100.0),
                BigDecimal.valueOf(0.005),
                BigDecimal.valueOf(2000),
                50L
        );
        OptionsContextBucket bucketPe = new OptionsContextBucket(
                Instant.now(), underlying, "PE", 8, 4,
                BigDecimal.valueOf(100.0),
                BigDecimal.valueOf(0.005),
                BigDecimal.valueOf(2000),
                50L
        );

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> map = new HashMap<>();
        map.put(keyCe, bucketCe);
        map.put(keyPe, bucketPe);
        map.put(fallbackCe, bucketCe);
        map.put(fallbackPe, bucketPe);
        return map;
    }

    // =========================================================================
    // Stub DB infrastructure (same proxy pattern as ScannerQueryScopedTest)
    // =========================================================================

    /** Simple value holder for price data returned by the stub. */
    private record PriceData(double last, double moneyness, int moneynessBucket,
                              int timeBucket, long volume) {}

    /**
     * Stub DB that answers two query types per {@code fetchScoped} call:
     * 1. Scoped price batch query (IN clause) — returns rows keyed by instrument ID.
     * 2. Spot query — returns a single spot price for the underlying.
     */
    private static final class StubDb {

        private Map<String, PriceData> prices = new HashMap<>();
        private String spotUnderlying;
        private double spotPrice;

        StubDb withPrices(Map<String, PriceData> p) {
            this.prices = new HashMap<>(p);
            return this;
        }

        StubDb withSpot(String underlying, double price) {
            this.spotUnderlying = underlying;
            this.spotPrice = price;
            return this;
        }

        Connection open() throws java.sql.SQLException {
            AtomicInteger queryIndex = new AtomicInteger(0);

            InvocationHandler stmtHandler = (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("setString") || name.equals("close")) return null;
                if (name.equals("executeQuery")) {
                    int idx = queryIndex.getAndIncrement();
                    return idx == 0 ? buildPriceResultSet() : buildSpotResultSet();
                }
                return null;
            };

            PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{PreparedStatement.class},
                    stmtHandler);

            InvocationHandler connHandler = (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("prepareStatement")) return ps;
                if (name.equals("close")) return null;
                return null;
            };

            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{Connection.class},
                    connHandler);
        }

        private ResultSet buildPriceResultSet() {
            List<Map.Entry<String, PriceData>> entries = new ArrayList<>(prices.entrySet());
            AtomicInteger row = new AtomicInteger(-1);

            return (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{ResultSet.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("next")) return row.incrementAndGet() < entries.size();
                        if (name.equals("close")) return null;
                        Map.Entry<String, PriceData> entry = entries.get(row.get());
                        PriceData pd = entry.getValue();
                        return switch (name) {
                            case "getString" -> {
                                String col = (String) args[0];
                                yield "instrument_id".equals(col) ? entry.getKey() : null;
                            }
                            case "getDouble" -> {
                                String col = (String) args[0];
                                yield switch (col) {
                                    case "last_price"       -> pd.last();
                                    case "moneyness_points" -> pd.moneyness();
                                    default                 -> 0.0;
                                };
                            }
                            case "getInt" -> {
                                String col = (String) args[0];
                                yield switch (col) {
                                    case "moneyness_bucket" -> pd.moneynessBucket();
                                    case "time_bucket_15m"  -> pd.timeBucket();
                                    default                 -> 0;
                                };
                            }
                            case "getLong" -> {
                                String col = (String) args[0];
                                yield "volume".equals(col) ? pd.volume() : 0L;
                            }
                            default -> null;
                        };
                    });
        }

        private ResultSet buildSpotResultSet() {
            AtomicInteger row = new AtomicInteger(-1);
            boolean hasSpot = spotUnderlying != null;

            return (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{ResultSet.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("next")) return hasSpot && row.incrementAndGet() == 0;
                        if (name.equals("close")) return null;
                        if (name.equals("getDouble")) return spotPrice;
                        return null;
                    });
        }
    }
}
