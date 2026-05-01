package com.strategysquad.agentic.scanner;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScannerQuery#fetchScoped}.
 *
 * <p>All tests are pure in-memory — no live database required. JDBC objects are
 * supplied via dynamic proxies following the project's existing test-double pattern.
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>All scoped instruments have live prices → all returned as RawContractRow.</li>
 *   <li>Subset of instruments lack live price data → only priced instruments returned.</li>
 *   <li>No live price data available → empty list returned (no exception).</li>
 *   <li>Only the scoped instrument IDs appear in the IN clause — not others.</li>
 *   <li>Spot unavailable → empty list returned gracefully.</li>
 *   <li>Empty universe → empty list without DB call.</li>
 * </ol>
 */
class ScannerQueryScopedTest {

    private static final LocalDate TODAY  = LocalDate.of(2026, 4, 28);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 30);

    // =========================================================================
    // Test 1 — all instruments priced → all returned
    // =========================================================================

    @Test
    void allInstrumentsPriced_allReturnedAsRows() throws Exception {
        List<InstrumentRef> instruments = List.of(
                ref("INS_NIFTY_20260430_22000_CE", "NIFTY26APR22000CE", "CE", 22000),
                ref("INS_NIFTY_20260430_22000_PE", "NIFTY26APR22000PE", "PE", 22000)
        );
        Scope scope = scope("NIFTY");
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        // Stub: returns prices for both IDs, spot for NIFTY
        StubDb stub = new StubDb()
                .withScopedPrices(Map.of(
                        "INS_NIFTY_20260430_22000_CE", price(120.0, 50.0, 4, 8, 3000),
                        "INS_NIFTY_20260430_22000_PE", price(110.0, -50.0, -4, 8, 2500)
                ))
                .withSpot("NIFTY", 22050.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchScoped(scope, universe);

        assertEquals(2, rows.size());

        ScannerQuery.RawContractRow ce = rows.stream()
                .filter(r -> "CE".equals(r.optionType())).findFirst().orElseThrow();
        assertEquals("INS_NIFTY_20260430_22000_CE", ce.instrumentId());
        assertEquals("NIFTY", ce.underlying());
        assertEquals("NIFTY26APR22000CE", ce.tradingSymbol());
        assertEquals(0, BigDecimal.valueOf(22000).compareTo(ce.strike()));
        assertEquals(0, BigDecimal.valueOf(22050.0).compareTo(ce.spot()));
        assertEquals(0, BigDecimal.valueOf(120.0).compareTo(ce.lastPrice()));
        assertEquals(EXPIRY, ce.expiryDate());
        assertEquals("WEEKLY", ce.expiryType());
    }

    // =========================================================================
    // Test 2 — subset missing prices → only priced instruments returned
    // =========================================================================

    @Test
    void someMissingPrices_onlyPricedInstrumentsReturned() throws Exception {
        List<InstrumentRef> instruments = List.of(
                ref("INS_NIFTY_20260430_22000_CE", "NIFTY26APR22000CE", "CE", 22000),
                ref("INS_NIFTY_20260430_22000_PE", "NIFTY26APR22000PE", "PE", 22000),
                ref("INS_NIFTY_20260430_22500_CE", "NIFTY26APR22500CE", "CE", 22500)
        );
        Scope scope = scope("NIFTY");
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        // Only 2 of 3 instruments have prices
        StubDb stub = new StubDb()
                .withScopedPrices(Map.of(
                        "INS_NIFTY_20260430_22000_CE", price(120.0, 50.0, 4, 8, 3000),
                        "INS_NIFTY_20260430_22000_PE", price(110.0, -50.0, -4, 8, 2500)
                        // INS_NIFTY_20260430_22500_CE has no price → skipped
                ))
                .withSpot("NIFTY", 22050.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchScoped(scope, universe);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().noneMatch(r -> "INS_NIFTY_20260430_22500_CE".equals(r.instrumentId())),
                "Instrument without price data must not appear in results");
    }

    // =========================================================================
    // Test 3 — no live prices → empty list
    // =========================================================================

    @Test
    void noLivePrices_emptyListReturned() throws Exception {
        List<InstrumentRef> instruments = List.of(
                ref("INS_NIFTY_20260430_22000_CE", "NIFTY26APR22000CE", "CE", 22000)
        );
        Scope scope = scope("NIFTY");
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        StubDb stub = new StubDb()
                .withScopedPrices(Map.of())    // no prices
                .withSpot("NIFTY", 22050.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchScoped(scope, universe);

        assertTrue(rows.isEmpty());
    }

    // =========================================================================
    // Test 4 — only scoped IDs queried (isolation check)
    // =========================================================================

    @Test
    void onlyScopedInstrumentIdsQueried_noExtraInstruments() throws Exception {
        // Two instruments in scope
        List<InstrumentRef> instruments = List.of(
                ref("INS_NIFTY_20260430_22000_CE", "NIFTY26APR22000CE", "CE", 22000),
                ref("INS_NIFTY_20260430_22000_PE", "NIFTY26APR22000PE", "PE", 22000)
        );
        Scope scope = scope("NIFTY");
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        // DB stub returns an extra instrument ID that was NOT in the scope
        StubDb stub = new StubDb()
                .withScopedPrices(Map.of(
                        "INS_NIFTY_20260430_22000_CE", price(120.0, 50.0, 4, 8, 3000),
                        "INS_NIFTY_20260430_22000_PE", price(110.0, -50.0, -4, 8, 2500),
                        "INS_NIFTY_20260430_99999_CE", price(5.0, 0.0, 50, 1, 0)  // outside scope
                ))
                .withSpot("NIFTY", 22050.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchScoped(scope, universe);

        // Only the 2 scoped instruments should appear
        assertEquals(2, rows.size());
        assertTrue(rows.stream().noneMatch(r -> "INS_NIFTY_20260430_99999_CE".equals(r.instrumentId())),
                "Instruments outside scope must not appear in results");
    }

    // =========================================================================
    // Test 5 — spot unavailable → empty list
    // =========================================================================

    @Test
    void spotUnavailable_emptyListReturnedGracefully() throws Exception {
        List<InstrumentRef> instruments = List.of(
                ref("INS_NIFTY_20260430_22000_CE", "NIFTY26APR22000CE", "CE", 22000)
        );
        Scope scope = scope("NIFTY");
        ResolvedUniverse universe = ResolvedUniverse.of(scope, instruments);

        StubDb stub = new StubDb()
                .withScopedPrices(Map.of(
                        "INS_NIFTY_20260430_22000_CE", price(120.0, 50.0, 4, 8, 3000)
                ))
                // No spot registered → returns null → service returns empty list
                .withSpot(null, 0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchScoped(scope, universe);

        assertTrue(rows.isEmpty(), "Missing spot data must result in empty list, not exception");
    }

    // =========================================================================
    // Test 6 — empty universe → empty list without DB call
    // =========================================================================

    @Test
    void emptyUniverse_emptyListWithoutDbCall() throws Exception {
        Scope scope = scope("NIFTY");
        ResolvedUniverse universe = ResolvedUniverse.of(scope, List.of());

        // DB should not be called at all for empty universe
        AtomicInteger dbCallCount = new AtomicInteger(0);
        ScannerQuery query = new ScannerQuery(() -> {
            dbCallCount.incrementAndGet();
            throw new java.sql.SQLException("should not reach DB");
        }, null);

        List<ScannerQuery.RawContractRow> rows = query.fetchScoped(scope, universe);
        assertTrue(rows.isEmpty());
        assertEquals(0, dbCallCount.get(), "DB must not be called for empty universe");
    }

    // =========================================================================
    // Test helpers — scope / universe builders
    // =========================================================================

    private static Scope scope(String underlying) {
        return new Scope(underlying, EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.AtmPct(4.0), 30);
    }

    private static InstrumentRef ref(String instrumentId, String tradingSymbol,
                                      String optionType, int strike) {
        return new InstrumentRef(
                instrumentId, 100L, tradingSymbol, optionType,
                BigDecimal.valueOf(strike), EXPIRY, ExpiryType.WEEKLY, 50
        );
    }

    // =========================================================================
    // Stub DB infrastructure
    // =========================================================================

    /** Simple value holder for price data returned by the stub. */
    private record PriceData(double last, double moneyness, int moneynessBucket,
                              int timeBucket, long volume) {}

    private static PriceData price(double last, double moneyness, int moneynessBucket,
                                    int timeBucket, long volume) {
        return new PriceData(last, moneyness, moneynessBucket, timeBucket, volume);
    }

    /**
     * Stub DB that answers two query types:
     * 1. Scoped price batch query (IN clause) — returns rows keyed by instrument ID.
     * 2. Spot query — returns a single spot price for the named underlying.
     */
    private static final class StubDb {

        private Map<String, PriceData> scopedPrices = new HashMap<>();
        private String spotUnderlying;
        private double spotPrice;

        StubDb withScopedPrices(Map<String, PriceData> prices) {
            this.scopedPrices = new HashMap<>(prices);
            return this;
        }

        StubDb withSpot(String underlying, double price) {
            this.spotUnderlying = underlying;
            this.spotPrice = price;
            return this;
        }

        Connection open() throws java.sql.SQLException {
            // We need to serve two PreparedStatement executions per fetchScoped call:
            //   1. The IN(...) price batch query
            //   2. The spot query
            // Track which query we're on via a call counter.
            AtomicInteger queryIndex = new AtomicInteger(0);

            InvocationHandler stmtHandler = (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("setString") || name.equals("close")) return null;
                if (name.equals("executeQuery")) {
                    int idx = queryIndex.getAndIncrement();
                    if (idx == 0) {
                        // Scoped price batch result
                        return buildScopedPriceResultSet();
                    } else {
                        // Spot query result
                        return buildSpotResultSet();
                    }
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

        private ResultSet buildScopedPriceResultSet() {
            List<Map.Entry<String, PriceData>> entries = new ArrayList<>(scopedPrices.entrySet());
            AtomicInteger row = new AtomicInteger(-1);

            return (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{ResultSet.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("next")) {
                            return row.incrementAndGet() < entries.size();
                        }
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
