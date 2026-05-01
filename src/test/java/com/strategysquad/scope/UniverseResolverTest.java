package com.strategysquad.scope;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UniverseResolver}.
 *
 * <p>All tests are pure in-memory — no live database required. JDBC objects are
 * supplied via dynamic proxies that return fixture data, following the project's
 * established test-double pattern (see {@code ScannerQueryTest}).
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>ATM_PCT window — bounds derived from spot, correct instruments returned.</li>
 *   <li>ATM_POINTS window — bounds derived from spot, correct instruments returned.</li>
 *   <li>ExplicitRange window — literal bounds, correct instruments returned.</li>
 *   <li>LegsOnly window — instruments matched by ID, no truncation.</li>
 *   <li>Hard cap exceeded — truncated flag set, hint populated, capped at HARD_CAP.</li>
 *   <li>Missing expiry — EXPIRY_NOT_IN_MASTER thrown when check query returns 0.</li>
 *   <li>LegsOnly unknown ID — LEGS_INSTRUMENT_NOT_FOUND thrown.</li>
 *   <li>LegsOnly incomplete CE/PE pair — INSTRUMENT_PAIR_INCOMPLETE thrown for
 *       strategies that require pairing.</li>
 *   <li>LegsOnly incomplete CE/PE pair — ANALYSIS_ONLY skips pairing check.</li>
 *   <li>Empty result within a valid expiry — empty universe, not truncated.</li>
 * </ol>
 */
class UniverseResolverTest {

    private static final LocalDate EXPIRY    = LocalDate.of(2026, 4, 30);
    private static final LocalDate TODAY     = LocalDate.of(2026, 4, 28);
    private static final double    NIFTY_SPOT = 24_800.0;

    // =========================================================================
    // Test 1 — ATM_PCT resolves correct bounds
    // =========================================================================

    @Test
    void atmPct_returnsBoundedInstruments() throws Exception {
        // ±4% of 24800 = [23808, 25792]; stub returns 2 instruments within range
        List<InstrumentRow> rows = List.of(
                row("INS_NIFTY_20260430_24800_CE", "987654321", "NIFTY26APR24800CE", "CE", 24800.0, 75),
                row("INS_NIFTY_20260430_24800_PE", "987654322", "NIFTY26APR24800PE", "PE", 24800.0, 75)
        );

        UniverseResolver resolver = resolverWith(
                expiryExists(1),   // check query: 1 row
                instrumentRows(rows) // range query
        );

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.AtmPct(4.0));

        ResolvedUniverse universe = resolver.resolve(scope, NIFTY_SPOT);

        assertFalse(universe.truncated());
        assertEquals(2, universe.size());
        assertEquals("INS_NIFTY_20260430_24800_CE", universe.instruments().get(0).instrumentId());
        assertEquals(987654321L, universe.instruments().get(0).kiteToken());
        assertEquals("CE", universe.instruments().get(0).optionType());
        assertEquals(ExpiryType.WEEKLY, universe.instruments().get(0).expiryType());
        assertEquals(75, universe.instruments().get(0).lotSize());
        assertEquals(EXPIRY, universe.instruments().get(0).expiry());
    }

    // =========================================================================
    // Test 2 — ATM_POINTS resolves correct bounds
    // =========================================================================

    @Test
    void atmPoints_returnsBoundedInstruments() throws Exception {
        // ±500 pts of 24800 = [24300, 25300]
        List<InstrumentRow> rows = List.of(
                row("INS_NIFTY_20260430_24500_CE", "111", "NIFTY26APR24500CE", "CE", 24500.0, 75),
                row("INS_NIFTY_20260430_24500_PE", "112", "NIFTY26APR24500PE", "PE", 24500.0, 75)
        );

        UniverseResolver resolver = resolverWith(expiryExists(1), instrumentRows(rows));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.AtmPoints(500.0));

        ResolvedUniverse universe = resolver.resolve(scope, NIFTY_SPOT);

        assertFalse(universe.truncated());
        assertEquals(2, universe.size());
    }

    // =========================================================================
    // Test 3 — ExplicitRange uses literal bounds
    // =========================================================================

    @Test
    void explicitRange_usesLiteralBounds() throws Exception {
        List<InstrumentRow> rows = List.of(
                row("INS_NIFTY_20260430_24200_CE", "201", "NIFTY26APR24200CE", "CE", 24200.0, 75),
                row("INS_NIFTY_20260430_24200_PE", "202", "NIFTY26APR24200PE", "PE", 24200.0, 75)
        );

        UniverseResolver resolver = resolverWith(expiryExists(1), instrumentRows(rows));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.ExplicitRange(24000.0, 25000.0));

        ResolvedUniverse universe = resolver.resolve(scope, NIFTY_SPOT);

        assertFalse(universe.truncated());
        assertEquals(2, universe.size());
        assertEquals("INS_NIFTY_20260430_24200_CE", universe.instruments().get(0).instrumentId());
    }

    // =========================================================================
    // Test 4 — LegsOnly returns exact instruments by ID
    // =========================================================================

    @Test
    void legsOnly_returnsExactInstrumentsByIds() throws Exception {
        List<InstrumentRow> rows = List.of(
                row("INS_NIFTY_20260430_24800_CE", "301", "NIFTY26APR24800CE", "CE", 24800.0, 75),
                row("INS_NIFTY_20260430_24800_PE", "302", "NIFTY26APR24800PE", "PE", 24800.0, 75)
        );

        // LegsOnly uses only ONE query (no expiry check); stub returns direct instrument rows
        UniverseResolver resolver = resolverWith(instrumentRows(rows));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE,
                new StrikeWindow.LegsOnly(List.of(
                        "INS_NIFTY_20260430_24800_CE",
                        "INS_NIFTY_20260430_24800_PE")));

        ResolvedUniverse universe = resolver.resolve(scope, NIFTY_SPOT);

        assertFalse(universe.truncated());
        assertEquals(2, universe.size());
    }

    // =========================================================================
    // Test 5 — Hard cap exceeded → truncated = true
    // =========================================================================

    @Test
    void hardCap_exceeded_truncatedWithHint() throws Exception {
        // Generate HARD_CAP+2 rows to exceed the cap
        List<InstrumentRow> rows = new ArrayList<>();
        for (int i = 0; i < UniverseResolver.HARD_CAP + 2; i++) {
            double strike = 24000.0 + i * 50;
            rows.add(row("INS_NIFTY_20260430_" + (int) strike + "_CE",
                    String.valueOf(1000 + i), "SYM" + i, "CE", strike, 75));
        }

        UniverseResolver resolver = resolverWith(expiryExists(1), instrumentRows(rows));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.AtmPct(20.0));

        ResolvedUniverse universe = resolver.resolve(scope, NIFTY_SPOT);

        assertTrue(universe.truncated());
        assertEquals(UniverseResolver.HARD_CAP, universe.size());
        assertNotNull(universe.narrowingHint());
        assertFalse(universe.narrowingHint().isBlank());
    }

    // =========================================================================
    // Test 6 — Missing expiry → EXPIRY_NOT_IN_MASTER
    // =========================================================================

    @Test
    void missingExpiry_throwsScopeValidationException() {
        // expiry check returns 0
        UniverseResolver resolver = resolverWith(expiryExists(0));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.AtmPct(4.0));

        ScopeValidationException ex = assertThrows(ScopeValidationException.class,
                () -> resolver.resolve(scope, NIFTY_SPOT));

        assertEquals("EXPIRY_NOT_IN_MASTER", ex.errorCode());
        assertNotNull(ex.hint());
    }

    // =========================================================================
    // Test 7 — LegsOnly with unknown ID → LEGS_INSTRUMENT_NOT_FOUND
    // =========================================================================

    @Test
    void legsOnly_unknownId_throwsScopeValidationException() throws Exception {
        // DB only returns ONE of the two requested instruments
        List<InstrumentRow> rows = List.of(
                row("INS_NIFTY_20260430_24800_CE", "301", "SYM_CE", "CE", 24800.0, 75)
                // PE deliberately absent
        );

        UniverseResolver resolver = resolverWith(instrumentRows(rows));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE,
                new StrikeWindow.LegsOnly(List.of(
                        "INS_NIFTY_20260430_24800_CE",
                        "INS_NIFTY_20260430_24800_PE")));  // PE not in DB

        ScopeValidationException ex = assertThrows(ScopeValidationException.class,
                () -> resolver.resolve(scope, NIFTY_SPOT));

        assertEquals("LEGS_INSTRUMENT_NOT_FOUND", ex.errorCode());
        assertTrue(ex.details().contains("INS_NIFTY_20260430_24800_PE"));
    }

    // =========================================================================
    // Test 8 — LegsOnly with unpaired CE/PE for paired strategy → INSTRUMENT_PAIR_INCOMPLETE
    // =========================================================================

    @Test
    void legsOnly_unpairedCeOnly_throwsForPairedStrategy() throws Exception {
        // Both IDs found in DB, but same strike → CE+CE, no PE match
        List<InstrumentRow> rows = List.of(
                row("INS_NIFTY_20260430_24800_CE", "301", "CE1", "CE", 24800.0, 75),
                row("INS_NIFTY_20260430_24900_CE", "303", "CE2", "CE", 24900.0, 75)
        );

        UniverseResolver resolver = resolverWith(instrumentRows(rows));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE,
                new StrikeWindow.LegsOnly(List.of(
                        "INS_NIFTY_20260430_24800_CE",
                        "INS_NIFTY_20260430_24900_CE")));

        ScopeValidationException ex = assertThrows(ScopeValidationException.class,
                () -> resolver.resolve(scope, NIFTY_SPOT));

        assertEquals("INSTRUMENT_PAIR_INCOMPLETE", ex.errorCode());
    }

    // =========================================================================
    // Test 9 — LegsOnly with ANALYSIS_ONLY skips pairing check
    // =========================================================================

    @Test
    void legsOnly_analysisOnly_skipsPairingCheck() throws Exception {
        // CE-only is fine for ANALYSIS_ONLY
        List<InstrumentRow> rows = List.of(
                row("INS_NIFTY_20260430_24800_CE", "301", "CE1", "CE", 24800.0, 75),
                row("INS_NIFTY_20260430_24900_CE", "303", "CE2", "CE", 24900.0, 75)
        );

        UniverseResolver resolver = resolverWith(instrumentRows(rows));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.ANALYSIS_ONLY,
                new StrikeWindow.LegsOnly(List.of(
                        "INS_NIFTY_20260430_24800_CE",
                        "INS_NIFTY_20260430_24900_CE")));

        ResolvedUniverse universe = resolver.resolve(scope, NIFTY_SPOT);
        assertEquals(2, universe.size());
        assertFalse(universe.truncated());
    }

    // =========================================================================
    // Test 10 — Empty result within valid expiry → empty universe, not truncated
    // =========================================================================

    @Test
    void emptyResultWithinValidExpiry_returnsEmptyUniverse() throws Exception {
        // Expiry check returns 1 (expiry exists), but range query returns 0 instruments
        UniverseResolver resolver = resolverWith(expiryExists(1), instrumentRows(List.of()));

        Scope scope = Scope.of("NIFTY", EXPIRY, ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE, new StrikeWindow.ExplicitRange(20000.0, 20001.0));

        ResolvedUniverse universe = resolver.resolve(scope, NIFTY_SPOT);

        assertFalse(universe.truncated());
        assertTrue(universe.isEmpty());
    }

    // =========================================================================
    // Stub builder helpers
    // =========================================================================

    private record InstrumentRow(
            String instrumentId, String exchangeToken, String tradingSymbol,
            String optionType, double strike, int lotSize) {}

    private static InstrumentRow row(String id, String token, String sym,
                                     String type, double strike, int lotSize) {
        return new InstrumentRow(id, token, sym, type, strike, lotSize);
    }

    /**
     * Builds a UniverseResolver backed by a stub connection that serves the given
     * query stubs in order of {@code prepareStatement()} calls.
     */
    private static UniverseResolver resolverWith(QueryStub... stubs) {
        AtomicInteger callCount = new AtomicInteger(0);

        UniverseResolver.ConnectionSupplier supplier = () -> {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    int idx = callCount.getAndIncrement();
                    if (idx >= stubs.length) {
                        throw new IllegalStateException(
                                "Unexpected prepareStatement call #" + idx +
                                " (only " + stubs.length + " stubs registered). SQL: " + args[0]);
                    }
                    return stubs[idx].makeStatement();
                }
                if ("close".equals(method.getName())) return null;
                return defaultPrimitive(method.getReturnType());
            };
            return proxy(Connection.class, handler);
        };

        return new UniverseResolver(supplier);
    }

    // =========================================================================
    // Query stubs
    // =========================================================================

    /** Stub for the expiry-existence COUNT(*) check. Returns {@code count} rows with cnt=count. */
    private static QueryStub expiryExists(long count) {
        return () -> {
            boolean[] consumed = {false};
            InvocationHandler rsHandler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    if (!consumed[0]) { consumed[0] = true; yield true; }
                    yield false;
                }
                case "getLong" -> count;
                case "close" -> null;
                default -> defaultPrimitive(method.getReturnType());
            };
            ResultSet rs = proxy(ResultSet.class, rsHandler);

            InvocationHandler stmtHandler = (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setTimestamp" -> null;
                case "executeQuery" -> rs;
                case "close" -> null;
                default -> defaultPrimitive(method.getReturnType());
            };
            return proxy(PreparedStatement.class, stmtHandler);
        };
    }

    /** Stub for instrument SELECT queries. Returns the given rows from the ResultSet. */
    private static QueryStub instrumentRows(List<InstrumentRow> rows) {
        return () -> {
            int[] cursor = {-1};
            InvocationHandler rsHandler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    cursor[0]++;
                    yield cursor[0] < rows.size();
                }
                case "getString" -> {
                    String col = (String) args[0];
                    InstrumentRow r = rows.get(cursor[0]);
                    yield switch (col) {
                        case "instrument_id"  -> r.instrumentId();
                        case "exchange_token" -> r.exchangeToken();
                        case "trading_symbol" -> r.tradingSymbol();
                        case "option_type"    -> r.optionType();
                        case "expiry_type"    -> "WEEKLY";
                        default -> "";
                    };
                }
                case "getDouble" -> {
                    String col = (String) args[0];
                    yield "strike".equals(col) ? rows.get(cursor[0]).strike() : 0.0;
                }
                case "getInt" -> {
                    String col = (String) args[0];
                    yield "lot_size".equals(col) ? rows.get(cursor[0]).lotSize() : 0;
                }
                case "getTimestamp" -> {
                    // expiry_date — return a Timestamp for 2026-04-30 IST midnight
                    Instant instant = EXPIRY.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
                    yield Timestamp.from(instant);
                }
                case "close" -> null;
                default -> defaultPrimitive(method.getReturnType());
            };
            ResultSet rs = proxy(ResultSet.class, rsHandler);

            InvocationHandler stmtHandler = (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setTimestamp", "setDouble", "setInt" -> null;
                case "executeQuery" -> rs;
                case "close" -> null;
                default -> defaultPrimitive(method.getReturnType());
            };
            return proxy(PreparedStatement.class, stmtHandler);
        };
    }

    // =========================================================================
    // Proxy utilities
    // =========================================================================

    @FunctionalInterface
    private interface QueryStub {
        PreparedStatement makeStatement() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static Object defaultPrimitive(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class)     return 0;
        if (type == long.class)    return 0L;
        if (type == double.class)  return 0.0;
        if (type == float.class)   return 0.0f;
        if (type == byte.class)    return (byte) 0;
        if (type == short.class)   return (short) 0;
        if (type == char.class)    return '\0';
        return null;
    }
}
