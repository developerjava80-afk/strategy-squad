package com.strategysquad.agentic.scanner;

import com.strategysquad.research.SimulationClock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ScannerQuery}.
 *
 * <p>All tests are pure in-memory — no live database required. JDBC {@link Connection}
 * and {@link PreparedStatement} objects are supplied via dynamic proxies that return
 * fixture data, following the project's existing test-double pattern.
 *
 * <h2>Stub design</h2>
 * <p>{@link StubConnection} serves three SQL query types in order:
 * <ol>
 *   <li>Instrument query — no bound parameters; recognised by {@code boundId == null}.</li>
 *   <li>Option price query — {@code setString(1, instrumentId)} bound first.</li>
 *   <li>Spot query — {@code setString(1, underlying)} bound first; key not found in
 *       optionPrices map so the stub falls through to the spot map.</li>
 * </ol>
 */
class ScannerQueryTest {

    // -------------------------------------------------------------------------
    // Scenario 1: found contracts — live mode returns populated rows
    // -------------------------------------------------------------------------

    @Test
    void foundContracts_liveMode_returnsPopulatedRows() throws Exception {
        StubConnection stub = new StubConnection()
                .withInstruments(List.<Object[]>of(
                        instrument("INS_NIFTY_20260501_24800_CE", "NIFTY", "NIFTY26APR24800CE",
                                "CE", 24800.0, "2026-05-01T10:00:00Z", "WEEKLY", 75),
                        instrument("INS_NIFTY_20260501_24500_PE", "NIFTY", "NIFTY26APR24500PE",
                                "PE", 24500.0, "2026-05-01T10:00:00Z", "WEEKLY", 75)
                ))
                .withOptionPrice("INS_NIFTY_20260501_24800_CE", optionPrice(120.0, 200.0, 4, 8, 3000))
                .withOptionPrice("INS_NIFTY_20260501_24500_PE", optionPrice(95.0, -300.0, -3, 8, 2500))
                .withSpot("NIFTY", 24600.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchActiveWeeklyContracts();

        assertEquals(2, rows.size());

        ScannerQuery.RawContractRow ce = rows.stream()
                .filter(r -> "CE".equals(r.optionType())).findFirst().orElseThrow();
        assertEquals("INS_NIFTY_20260501_24800_CE", ce.instrumentId());
        assertEquals("NIFTY", ce.underlying());
        assertEquals("WEEKLY", ce.expiryType());
        assertEquals(0, new BigDecimal("24800.0").compareTo(ce.strike()));
        assertEquals(0, new BigDecimal("24600.0").compareTo(ce.spot()));
        assertEquals(0, new BigDecimal("120.0").compareTo(ce.lastPrice()));
        assertTrue(ce.bidPrice().compareTo(BigDecimal.ZERO) > 0, "bid must be positive");
        assertTrue(ce.askPrice().compareTo(ce.bidPrice()) > 0, "ask must exceed bid");
        assertEquals(75, ce.lotSize());
        assertEquals(3000L, ce.volume());
        assertEquals(LocalDate.of(2026, 5, 1), ce.expiryDate());

        ScannerQuery.RawContractRow pe = rows.stream()
                .filter(r -> "PE".equals(r.optionType())).findFirst().orElseThrow();
        assertEquals("INS_NIFTY_20260501_24500_PE", pe.instrumentId());
        assertEquals(2500L, pe.volume());
    }

    // -------------------------------------------------------------------------
    // Scenario 2: no active contracts — instrument_master returns empty result
    // -------------------------------------------------------------------------

    @Test
    void noContracts_instrumentMasterEmpty_returnsEmptyList() throws Exception {
        StubConnection stub = new StubConnection().withInstruments(List.of());

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchActiveWeeklyContracts();

        assertNotNull(rows, "result must never be null");
        assertTrue(rows.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Scenario 3: instrument exists but no option price data — row skipped
    // -------------------------------------------------------------------------

    @Test
    void noContracts_noOptionPriceData_returnsEmptyList() throws Exception {
        StubConnection stub = new StubConnection()
                .withInstruments(List.<Object[]>of(
                        instrument("INS_NIFTY_20260501_24800_CE", "NIFTY", "NIFTY26APR24800CE",
                                "CE", 24800.0, "2026-05-01T10:00:00Z", "WEEKLY", 75)
                ))
                // No option price registered → price ResultSet will be empty → row skipped
                .withSpot("NIFTY", 24600.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchActiveWeeklyContracts();

        assertNotNull(rows);
        assertTrue(rows.isEmpty(), "row must be skipped when no option price data exists");
    }

    // -------------------------------------------------------------------------
    // Scenario 4: simulation mode — clock active, timestamp bound for both queries
    // -------------------------------------------------------------------------

    @Test
    void simulationMode_clockActive_usesHistoricalTableWithTimestampBound() throws Exception {
        SimulationClock clock = new SimulationClock();
        clock.setSimulatedInstant(Instant.parse("2026-04-24T09:15:00Z"));

        AtomicInteger timestampBindCount = new AtomicInteger(0);

        StubConnection stub = new StubConnection()
                .withInstruments(List.<Object[]>of(
                        instrument("INS_NIFTY_20260425_24700_CE", "NIFTY", "NIFTY25APR24700CE",
                                "CE", 24700.0, "2026-04-25T10:00:00Z", "WEEKLY", 75)
                ))
                .withOptionPrice("INS_NIFTY_20260425_24700_CE", optionPrice(88.0, 150.0, 2, 12, 1800))
                .withSpot("NIFTY", 24550.0)
                .onTimestampBound(timestampBindCount::incrementAndGet);

        ScannerQuery query = new ScannerQuery(stub::open, clock);
        List<ScannerQuery.RawContractRow> rows = query.fetchActiveWeeklyContracts();

        assertEquals(1, rows.size(), "expected one contract in simulation mode");
        ScannerQuery.RawContractRow row = rows.get(0);
        assertEquals("INS_NIFTY_20260425_24700_CE", row.instrumentId());
        assertEquals(0, new BigDecimal("24550.0").compareTo(row.spot()));
        assertEquals(0, new BigDecimal("88.0").compareTo(row.lastPrice()));

        // Timestamp must be bound at least twice: once for options query, once for spot
        assertTrue(timestampBindCount.get() >= 2,
                "SimulationClock timestamp must be bound for both option and spot queries; "
                + "got " + timestampBindCount.get());
    }

    // -------------------------------------------------------------------------
    // Scenario 5: simulation mode, no data — empty list, no exception
    // -------------------------------------------------------------------------

    @Test
    void simulationMode_noData_returnsEmptyListWithoutException() throws Exception {
        SimulationClock clock = new SimulationClock();
        clock.setSimulatedInstant(Instant.parse("2026-04-01T09:00:00Z"));

        StubConnection stub = new StubConnection().withInstruments(List.of());

        ScannerQuery query = new ScannerQuery(stub::open, clock);
        List<ScannerQuery.RawContractRow> rows = query.fetchActiveWeeklyContracts();

        assertNotNull(rows);
        assertTrue(rows.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Scenario 6: every returned row has expiry_type WEEKLY
    // -------------------------------------------------------------------------

    @Test
    void allReturnedRows_haveWeeklyExpiryType() throws Exception {
        StubConnection stub = new StubConnection()
                .withInstruments(List.<Object[]>of(
                        instrument("INS_NIFTY_20260501_24800_CE", "NIFTY", "NIFTY26APR24800CE",
                                "CE", 24800.0, "2026-05-01T10:00:00Z", "WEEKLY", 75)
                ))
                .withOptionPrice("INS_NIFTY_20260501_24800_CE", optionPrice(120.0, 200.0, 4, 8, 3000))
                .withSpot("NIFTY", 24600.0);

        ScannerQuery query = new ScannerQuery(stub::open, null);
        List<ScannerQuery.RawContractRow> rows = query.fetchActiveWeeklyContracts();

        assertFalse(rows.isEmpty());
        rows.forEach(row ->
                assertEquals("WEEKLY", row.expiryType(), "every row must be WEEKLY"));
    }

    // =========================================================================
    // Fixture builders
    // =========================================================================

    /** Builds an instrument row fixture: [id, underlying, symbol, type, strike, expiryTs, expiryType, lotSize] */
    private static Object[] instrument(String id, String underlying, String symbol,
            String type, double strike, String expiryIso, String expiryType, int lotSize) {
        return new Object[]{id, underlying, symbol, type,
                strike, Timestamp.from(Instant.parse(expiryIso)), expiryType, lotSize};
    }

    /** Builds an option price row fixture: [lastPrice, moneynessPoints, moneynessBucket, timeBucket15m, volume] */
    private static double[] optionPrice(double lastPrice, double moneynessPoints,
            int moneynessBucket, int timeBucket15m, long volume) {
        return new double[]{lastPrice, moneynessPoints, moneynessBucket, timeBucket15m, volume};
    }

    // =========================================================================
    // Stub JDBC infrastructure
    // =========================================================================

    /**
     * Supplies a stub {@link Connection} whose {@code prepareStatement} returns a proxy
     * {@link PreparedStatement} that routes query results by what string parameter was
     * bound before {@code executeQuery} was called:
     * <ul>
     *   <li>No string bound (param 1 never set) → instrument result set</li>
     *   <li>String bound that matches an entry in {@code optionPrices} → option price result set</li>
     *   <li>String bound that matches an entry in {@code spots} → spot result set</li>
     *   <li>No match → empty result set</li>
     * </ul>
     */
    static final class StubConnection {

        private List<Object[]> instruments = List.of();
        private final Map<String, double[]> optionPrices = new HashMap<>();
        private final Map<String, Double> spots = new HashMap<>();
        private Runnable onTimestampBound = () -> {};

        StubConnection withInstruments(List<Object[]> rows) {
            this.instruments = rows;
            return this;
        }

        StubConnection withOptionPrice(String instrumentId, double[] row) {
            optionPrices.put(instrumentId, row);
            return this;
        }

        StubConnection withSpot(String underlying, double spotPrice) {
            spots.put(underlying, spotPrice);
            return this;
        }

        StubConnection onTimestampBound(Runnable callback) {
            this.onTimestampBound = callback;
            return this;
        }

        Connection open() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    return makeStatement();
                }
                if ("close".equals(method.getName())) return null;
                return primitive(method.getReturnType());
            };
            return proxy(Connection.class, handler);
        }

        // ------------------------------------------------------------------
        // Statement proxy — one per prepareStatement() call
        // ------------------------------------------------------------------

        private PreparedStatement makeStatement() {
            // boundId[0]: the string bound to parameter 1 (null = instrument query, no params)
            final String[] boundId = {null};
            // rowCursor[0]: position within the result set for this statement
            final int[] rowCursor = {0};

            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setString" -> {
                    if ((int) args[0] == 1) boundId[0] = (String) args[1];
                    yield null;
                }
                case "setTimestamp" -> {
                    onTimestampBound.run();
                    yield null;
                }
                case "executeQuery" -> makeResultSet(boundId[0], rowCursor);
                case "close" -> null;
                default -> primitive(method.getReturnType());
            };
            return proxy(PreparedStatement.class, handler);
        }

        // ------------------------------------------------------------------
        // ResultSet proxy — routes by boundId
        // ------------------------------------------------------------------

        private ResultSet makeResultSet(String boundId, int[] cursor) {
            // Classify what type of result set to serve
            final boolean isInstrument = (boundId == null);
            final double[] optRow = (!isInstrument) ? optionPrices.get(boundId) : null;
            final Double spotPrice = (!isInstrument && optRow == null) ? spots.get(boundId) : null;

            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    int pos = cursor[0]++;
                    if (isInstrument) yield pos < instruments.size();
                    else if (optRow != null || spotPrice != null) yield pos == 0;
                    else yield false;
                }
                case "getString" -> {
                    String col = (String) args[0];
                    if (isInstrument) {
                        Object[] row = instruments.get(cursor[0] - 1);
                        yield switch (col) {
                            case "instrument_id"  -> (String) row[0];
                            case "underlying"     -> (String) row[1];
                            case "trading_symbol" -> (String) row[2];
                            case "option_type"    -> (String) row[3];
                            case "expiry_type"    -> (String) row[6];
                            default -> null;
                        };
                    }
                    yield null;
                }
                case "getDouble" -> {
                    String col = (String) args[0];
                    if (isInstrument) {
                        Object[] row = instruments.get(cursor[0] - 1);
                        yield "strike".equals(col) ? (double) row[4] : 0.0;
                    }
                    if (optRow != null) {
                        yield switch (col) {
                            case "last_price"       -> optRow[0];
                            case "moneyness_points" -> optRow[1];
                            default -> 0.0;
                        };
                    }
                    if (spotPrice != null) {
                        yield ("last_price".equals(col) || "close_price".equals(col))
                                ? spotPrice : 0.0;
                    }
                    yield 0.0;
                }
                case "getInt" -> {
                    String col = (String) args[0];
                    if (isInstrument) {
                        Object[] row = instruments.get(cursor[0] - 1);
                        yield "lot_size".equals(col) ? (int) row[7] : 0;
                    }
                    if (optRow != null) {
                        yield switch (col) {
                            case "moneyness_bucket" -> (int) optRow[2];
                            case "time_bucket_15m"  -> (int) optRow[3];
                            default -> 0;
                        };
                    }
                    yield 0;
                }
                case "getLong" -> {
                    String col = (String) args[0];
                    if (optRow != null && "volume".equals(col)) yield (long) optRow[4];
                    yield 0L;
                }
                case "getTimestamp" -> {
                    String col = (String) args[0];
                    if (isInstrument && "expiry_date".equals(col)) {
                        Object[] row = instruments.get(cursor[0] - 1);
                        yield (Timestamp) row[5];
                    }
                    yield null;
                }
                case "close" -> null;
                default -> primitive(method.getReturnType());
            };
            return proxy(ResultSet.class, handler);
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> iface, InvocationHandler h) {
            return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
        }

        private static Object primitive(Class<?> t) {
            if (!t.isPrimitive() || t == void.class) return null;
            if (t == boolean.class) return false;
            if (t == int.class)     return 0;
            if (t == long.class)    return 0L;
            if (t == double.class)  return 0.0;
            if (t == float.class)   return 0.0f;
            if (t == byte.class)    return (byte) 0;
            if (t == short.class)   return (short) 0;
            if (t == char.class)    return '\0';
            return null;
        }
    }
}
