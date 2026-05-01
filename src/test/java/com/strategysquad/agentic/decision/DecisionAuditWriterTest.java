package com.strategysquad.agentic.decision;

import com.strategysquad.agentic.risk.RiskGuardDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DecisionAuditWriter}.
 *
 * <p>All tests are pure in-memory: no database, no network. A JDBC proxy recorder
 * captures the exact SQL parameters passed to {@link PreparedStatement#setXxx} calls
 * so the round-trip can be verified without a live QuestDB instance.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Normal write — all 19 parameters are set correctly.</li>
 *   <li>Null optional fields — {@code position_session_id}, {@code live_spot},
 *       {@code theta_state}, {@code theta_progress_ratio}, {@code liquidity_score}
 *       produce SQL NULL without throwing.</li>
 *   <li>Overridden command — {@code overridden_by_risk_guard = true} is set.</li>
 *   <li>{@link DecisionAuditWriter.AuditContext} rejects blank {@code stateMachineState}.</li>
 * </ul>
 */
class DecisionAuditWriterTest {

    private DecisionAuditWriter writer;

    @BeforeEach
    void setUp() {
        writer = new DecisionAuditWriter();
    }

    // =========================================================================
    // Happy-path write — all fields present
    // =========================================================================

    @Test
    void write_allFieldsPresent_setsAllParametersCorrectly() throws SQLException {
        UUID commandId = UUID.randomUUID();
        Instant ts = Instant.parse("2026-04-26T09:15:00Z");

        DecisionCommand cmd = new DecisionCommand(
                commandId,
                ts,
                DecisionCommand.Mode.PAPER,
                DecisionCommand.CommandType.ENTER,
                List.of("INS_NIFTY_20260430_24800_CE", "INS_NIFTY_20260430_24800_PE"),
                Optional.of("SES-20260426-001"),
                "PREMIUM_RICH_LOW_DELTA",
                "CE 24800 premium 18% above historical average, entering short straddle",
                RiskGuardDecision.ALLOW,
                false
        );

        DecisionAuditWriter.AuditContext ctx = new DecisionAuditWriter.AuditContext(
                "EVALUATE_ENTRY",
                24800.5,        // liveSpot
                0.05,           // netDeltaBefore
                0.05,           // netDeltaAfter
                "HOLD",         // thetaState
                0.45,           // thetaProgressRatio
                120.0,          // livePnl
                50.0,           // bookedPnl
                0.82            // liquidityScore
        );

        StatementRecorder recorder = new StatementRecorder();
        Connection conn = fakeConnection(recorder);

        writer.write(conn, cmd, ctx);

        Map<Integer, Object> params = recorder.capturedParams();

        // Parameter positions (1-based as per INSERT_SQL order):
        // 1=command_id, 2=timestamp, 3=mode, 4=state_machine_state, 5=command_type,
        // 6=selected_candidate_ids, 7=position_session_id,
        // 8=live_spot, 9=net_delta_before, 10=net_delta_after,
        // 11=theta_state, 12=theta_progress_ratio,
        // 13=live_pnl, 14=booked_pnl, 15=liquidity_score,
        // 16=risk_guard_decision, 17=reason_code, 18=explanation, 19=overridden_by_risk_guard

        assertEquals(commandId.toString(), params.get(1), "command_id");
        assertNotNull(params.get(2), "timestamp must not be null");
        assertEquals("PAPER", params.get(3), "mode");
        assertEquals("EVALUATE_ENTRY", params.get(4), "state_machine_state");
        assertEquals("ENTER", params.get(5), "command_type");
        assertEquals("INS_NIFTY_20260430_24800_CE,INS_NIFTY_20260430_24800_PE",
                params.get(6), "selected_candidate_ids");
        assertEquals("SES-20260426-001", params.get(7), "position_session_id");
        assertEquals(24800.5, (double) params.get(8), 1e-9, "live_spot");
        assertEquals(0.05, (double) params.get(9), 1e-9, "net_delta_before");
        assertEquals(0.05, (double) params.get(10), 1e-9, "net_delta_after");
        assertEquals("HOLD", params.get(11), "theta_state");
        assertEquals(0.45, (double) params.get(12), 1e-9, "theta_progress_ratio");
        assertEquals(120.0, (double) params.get(13), 1e-9, "live_pnl");
        assertEquals(50.0, (double) params.get(14), 1e-9, "booked_pnl");
        assertEquals(0.82, (double) params.get(15), 1e-9, "liquidity_score");
        assertEquals("ALLOW", params.get(16), "risk_guard_decision");
        assertEquals("PREMIUM_RICH_LOW_DELTA", params.get(17), "reason_code");
        assertEquals("CE 24800 premium 18% above historical average, entering short straddle",
                params.get(18), "explanation");
        assertEquals(false, params.get(19), "overridden_by_risk_guard");
    }

    // =========================================================================
    // Null optional fields — must NOT throw
    // =========================================================================

    @Test
    void write_nullOptionalFields_setsNullWithoutThrowing() throws SQLException {
        UUID commandId = UUID.randomUUID();
        Instant ts = Instant.parse("2026-04-26T10:00:00Z");

        DecisionCommand cmd = new DecisionCommand(
                commandId,
                ts,
                DecisionCommand.Mode.PAPER,
                DecisionCommand.CommandType.SKIP,
                List.of(),          // no candidates
                Optional.empty(),   // no session
                "NO_QUALIFIED_CANDIDATES",
                "No qualified candidates found; skipping this cycle",
                RiskGuardDecision.ALLOW,
                false
        );

        // All nullable optional fields are null
        DecisionAuditWriter.AuditContext ctx = new DecisionAuditWriter.AuditContext(
                "EVALUATE_ENTRY",
                null,   // liveSpot — null
                0.0,    // netDeltaBefore
                0.0,    // netDeltaAfter
                null,   // thetaState — null
                null,   // thetaProgressRatio — null
                0.0,    // livePnl
                0.0,    // bookedPnl
                null    // liquidityScore — null
        );

        NullTrackingStatementRecorder recorder = new NullTrackingStatementRecorder();
        Connection conn = fakeConnection(recorder);

        // Must not throw even with all optional fields null
        assertDoesNotThrow(() -> writer.write(conn, cmd, ctx));

        // Verify that nullable columns were set to SQL NULL
        assertTrue(recorder.nullColumns.contains(7),  "position_session_id must be NULL");
        assertTrue(recorder.nullColumns.contains(8),  "live_spot must be NULL");
        assertTrue(recorder.nullColumns.contains(11), "theta_state must be NULL");
        assertTrue(recorder.nullColumns.contains(12), "theta_progress_ratio must be NULL");
        assertTrue(recorder.nullColumns.contains(15), "liquidity_score must be NULL");
    }

    // =========================================================================
    // Overridden command
    // =========================================================================

    @Test
    void write_overriddenCommand_setsOverriddenFlagTrue() throws SQLException {
        UUID commandId = UUID.randomUUID();
        Instant ts = Instant.parse("2026-04-26T11:00:00Z");

        DecisionCommand cmd = new DecisionCommand(
                commandId,
                ts,
                DecisionCommand.Mode.SIMULATION,
                DecisionCommand.CommandType.EXIT_ALL,
                List.of(),
                Optional.of("SES-001"),
                "RISK_GUARD_HALT_SESSION",
                "Churn detected — halting session and exiting all legs",
                RiskGuardDecision.HALT_SESSION,
                true    // overridden
        );

        DecisionAuditWriter.AuditContext ctx = new DecisionAuditWriter.AuditContext(
                "MONITOR",
                24700.0,
                -0.28,  // netDeltaBefore
                0.0,    // netDeltaAfter (after exit all)
                "DEFENSIVE_EXIT",
                0.55,
                -80.0,  // live loss
                0.0,
                0.40
        );

        StatementRecorder recorder = new StatementRecorder();
        Connection conn = fakeConnection(recorder);

        writer.write(conn, cmd, ctx);

        Map<Integer, Object> params = recorder.capturedParams();
        assertEquals("HALT_SESSION", params.get(16), "risk_guard_decision");
        assertEquals(true, params.get(19), "overridden_by_risk_guard must be true");
        assertEquals("RISK_GUARD_HALT_SESSION", params.get(17), "reason_code");
    }

    // =========================================================================
    // Empty selected_candidate_ids → empty string (not null)
    // =========================================================================

    @Test
    void write_emptyCandidateIds_writesEmptyString() throws SQLException {
        DecisionCommand cmd = new DecisionCommand(
                UUID.randomUUID(),
                Instant.now(),
                DecisionCommand.Mode.PAPER,
                DecisionCommand.CommandType.HOLD,
                List.of(),          // empty list
                Optional.empty(),
                "ALL_SIGNALS_HOLD",
                "All signals in HOLD state; no action required",
                RiskGuardDecision.ALLOW,
                false
        );

        DecisionAuditWriter.AuditContext ctx = new DecisionAuditWriter.AuditContext(
                "MONITOR",
                null, 0.02, 0.02, "HOLD", 0.50, 30.0, 0.0, null
        );

        StatementRecorder recorder = new StatementRecorder();
        writer.write(fakeConnection(recorder), cmd, ctx);

        assertEquals("", recorder.capturedParams().get(6),
                "Empty candidate list must produce empty string, not null");
    }

    // =========================================================================
    // AuditContext validation
    // =========================================================================

    @Test
    void auditContext_blankStateMachineState_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new DecisionAuditWriter.AuditContext("", null, 0, 0, null, null, 0, 0, null),
                "Blank stateMachineState must throw");
        assertThrows(IllegalArgumentException.class, () ->
                new DecisionAuditWriter.AuditContext(null, null, 0, 0, null, null, 0, 0, null),
                "Null stateMachineState must throw");
    }

    @Test
    void auditContext_validFields_doesNotThrow() {
        assertDoesNotThrow(() ->
                new DecisionAuditWriter.AuditContext(
                        "EVALUATE_ENTRY", 24000.0, 0.0, 0.0, null, null, 0.0, 0.0, null));
    }

    // =========================================================================
    // Writer null-argument guards
    // =========================================================================

    @Test
    void write_nullCommand_throws() {
        DecisionAuditWriter.AuditContext ctx = new DecisionAuditWriter.AuditContext(
                "EVALUATE_ENTRY", null, 0, 0, null, null, 0, 0, null);
        Connection conn = fakeConnection(new StatementRecorder());
        assertThrows(NullPointerException.class, () -> writer.write(conn, null, ctx));
    }

    @Test
    void write_nullContext_throws() throws SQLException {
        DecisionCommand cmd = minimalCmd(DecisionCommand.CommandType.SKIP, RiskGuardDecision.ALLOW, false);
        Connection conn = fakeConnection(new StatementRecorder());
        assertThrows(NullPointerException.class, () -> writer.write(conn, cmd, null));
    }

    @Test
    void write_nullConnection_throws() {
        DecisionCommand cmd = minimalCmd(DecisionCommand.CommandType.SKIP, RiskGuardDecision.ALLOW, false);
        DecisionAuditWriter.AuditContext ctx = new DecisionAuditWriter.AuditContext(
                "EVALUATE_ENTRY", null, 0, 0, null, null, 0, 0, null);
        assertThrows(NullPointerException.class, () -> writer.write(null, cmd, ctx));
    }

    // =========================================================================
    // AuditRecord round-trip — structural equality check
    // =========================================================================

    @Test
    void auditRecord_fieldsMatchCommand() {
        // Verify that AuditRecord carries the same values that were written.
        // This simulates what a read() would return for the given command.
        UUID id = UUID.randomUUID();
        Instant ts = Instant.parse("2026-04-26T09:30:00Z");

        DecisionAuditWriter.AuditRecord record = new DecisionAuditWriter.AuditRecord(
                id.toString(),
                ts,
                "PAPER",
                "MONITOR",
                "HOLD",
                "",
                null,
                null,
                0.01,
                0.01,
                "HOLD",
                0.55,
                40.0,
                20.0,
                0.75,
                "ALLOW",
                "ALL_SIGNALS_HOLD",
                "All signals in HOLD state",
                false
        );

        assertEquals(id.toString(), record.commandId());
        assertEquals(ts, record.timestamp());
        assertEquals("PAPER", record.mode());
        assertEquals("HOLD", record.commandType());
        assertNull(record.positionSessionId());
        assertNull(record.liveSpot());
        assertEquals(0.01, record.netDeltaBefore(), 1e-9);
        assertEquals(0.55, record.thetaProgressRatio(), 1e-9);
        assertEquals(40.0, record.livePnl(), 1e-9);
        assertFalse(record.overriddenByRiskGuard());
    }

    // =========================================================================
    // JDBC proxy infrastructure
    // =========================================================================

    /** Records parameters set on a PreparedStatement via setXxx calls. */
    static class StatementRecorder {
        private final Map<Integer, Object> params = new LinkedHashMap<>();
        private int executeUpdateResult = 1;

        Map<Integer, Object> capturedParams() {
            return params;
        }

        PreparedStatement proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setString"    -> { params.put((Integer) args[0], args[1]); yield null; }
                case "setTimestamp" -> { params.put((Integer) args[0], args[1]); yield null; }
                case "setDouble"    -> { params.put((Integer) args[0], (double) args[1]); yield null; }
                case "setBoolean"   -> { params.put((Integer) args[0], (boolean) args[1]); yield null; }
                case "setNull"      -> { params.put((Integer) args[0], null); yield null; }
                case "executeUpdate" -> executeUpdateResult;
                case "close"        -> null;
                default             -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    handler);
        }
    }

    /** Extends StatementRecorder to also track which column indices received setNull(). */
    static class NullTrackingStatementRecorder extends StatementRecorder {
        final List<Integer> nullColumns = new ArrayList<>();

        @Override
        PreparedStatement proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setString"    -> { capturedParams().put((Integer) args[0], args[1]); yield null; }
                case "setTimestamp" -> { capturedParams().put((Integer) args[0], args[1]); yield null; }
                case "setDouble"    -> { capturedParams().put((Integer) args[0], (double) args[1]); yield null; }
                case "setBoolean"   -> { capturedParams().put((Integer) args[0], (boolean) args[1]); yield null; }
                case "setNull"      -> { nullColumns.add((Integer) args[0]); yield null; }
                case "executeUpdate" -> 1;
                case "close"        -> null;
                default             -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    handler);
        }
    }

    /** Wraps a StatementRecorder in a Connection proxy. */
    private Connection fakeConnection(StatementRecorder recorder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> recorder.proxy();
            case "close"           -> null;
            default                -> defaultValue(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class)     return 0;
        if (type == long.class)    return 0L;
        if (type == double.class)  return 0.0;
        return null;
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private static DecisionCommand minimalCmd(
            DecisionCommand.CommandType type,
            RiskGuardDecision guard,
            boolean overridden) {
        return new DecisionCommand(
                UUID.randomUUID(),
                Instant.now(),
                DecisionCommand.Mode.PAPER,
                type,
                List.of(),
                Optional.empty(),
                "REASON",
                "Explanation text",
                guard,
                overridden
        );
    }
}
