package com.strategysquad.agentic;

import com.strategysquad.agentic.decision.DecisionAgent;
import com.strategysquad.agentic.decision.DecisionAuditWriter;
import com.strategysquad.agentic.decision.DecisionCommand;
import com.strategysquad.agentic.decision.DecisionContext;
import com.strategysquad.agentic.decision.DecisionPolicy;
import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.risk.RiskGuardInput;
import com.strategysquad.agentic.risk.RiskGuardService;
import com.strategysquad.agentic.risk.RiskGuardSnapshot;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 risk integration tests.
 *
 * <p>Each scenario drives {@link DecisionAgent} with a real {@link RiskGuardService}
 * subclass ({@link InputModifyingGuard}) that injects test-controlled values into the
 * {@link RiskGuardInput} before calling {@code super.evaluate()}. This exercises the
 * actual stop evaluation logic — not a stub — while keeping tests deterministic.
 *
 * <h2>Integration contract verified</h2>
 * <ol>
 *   <li>Real {@code RiskGuardService.evaluate()} runs and produces the correct
 *       {@code RiskGuardDecision} and {@code triggeredConditions} list.</li>
 *   <li>{@code DecisionAgent.applyRiskGuardOverride()} maps each decision to the
 *       correct command type.</li>
 *   <li>The audit record carries {@code overridden_by_risk_guard=true} and the
 *       appropriate {@code reason_code} for all four stops.</li>
 * </ol>
 *
 * <h2>No I/O</h2>
 * <p>All tests run in-memory — no QuestDB connection, no filesystem access.
 */
class Phase4RiskIntegrationTest {

    private static final Instant DECISION_TS = Instant.parse("2026-04-30T10:00:00Z");

    // =========================================================================
    // Scenario 1 — Net delta breach → FORCE_REDUCE overrides HOLD
    //
    // Stop 1: abs(netDelta) > 0.30 → FORCE_REDUCE + NET_DELTA_BREACH
    // Policy returns HOLD; Risk Guard overrides to REDUCE.
    // =========================================================================

    @Test
    void scenario1_deltaBreach_forceReduceOverridesHold() {
        AuditCaptor audit = new AuditCaptor();

        InputModifyingGuard guard = new InputModifyingGuard() {
            @Override
            RiskGuardInput modify(RiskGuardInput in) {
                // Inject netDelta = 0.45, which exceeds the 0.30 threshold (Stop 1)
                return new RiskGuardInput(
                        in.evaluationTs(), in.activeSession(), in.signalSnapshots(),
                        in.livePnl(), in.bookedPnl(),
                        0.45,  // netDelta — breaches MAX_NET_DELTA_THRESHOLD = 0.30
                        in.lotCount(), in.maxLotCap(),
                        in.recentCommandCount(), in.churnWindowMinutes(),
                        in.maxLossPoints(), in.staleDataSeconds(), in.lastTickAgeSeconds()
                );
            }
        };

        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                "NIFTY",
                ts -> List.of(),
                (session, ts) -> Collections.emptyMap(),
                Optional::empty,
                guard,
                fixedPolicy(DecisionCommand.CommandType.HOLD, "POLICY_HOLD"),
                audit,
                Phase4RiskIntegrationTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP
        );

        DecisionCommand cmd = agent.decide(DECISION_TS);

        // Command type: HOLD → overridden to REDUCE
        assertEquals(DecisionCommand.CommandType.REDUCE, cmd.commandType(),
                "Delta breach must override HOLD → REDUCE");
        assertTrue(cmd.overriddenByRiskGuard(),
                "overridden_by_risk_guard must be true");
        assertEquals(RiskGuardDecision.FORCE_REDUCE, cmd.riskGuardDecision(),
                "Risk guard decision must be FORCE_REDUCE");
        assertEquals("RISK_GUARD_FORCE_REDUCE", cmd.reasonCode(),
                "reasonCode must identify the FORCE_REDUCE override");

        // Risk guard snapshot: correct condition code
        RiskGuardSnapshot snap = guard.lastSnapshot();
        assertTrue(snap.triggeredConditions().contains("NET_DELTA_BREACH"),
                "NET_DELTA_BREACH must appear in triggered conditions. Actual: "
                        + snap.triggeredConditions());
        assertFalse(snap.maxLossBreached(), "maxLossBreached flag must be false");

        // Audit: one record, override reflected
        assertEquals(1, audit.commands.size(), "Exactly one audit record expected");
        DecisionCommand audited = audit.commands.get(0);
        assertEquals(DecisionCommand.CommandType.REDUCE, audited.commandType(),
                "Audited command type must be REDUCE");
        assertTrue(audited.overriddenByRiskGuard(),
                "Audit record must carry overridden_by_risk_guard=true");
        assertEquals("RISK_GUARD_FORCE_REDUCE", audited.reasonCode(),
                "Audit reason_code must be RISK_GUARD_FORCE_REDUCE");
    }

    // =========================================================================
    // Scenario 2 — Max loss breach → FORCE_EXIT
    //
    // Stop 2: livePnl < -maxLossPoints → FORCE_EXIT + MAX_LOSS_BREACH
    // Policy returns HOLD; Risk Guard overrides to EXIT_ALL.
    // =========================================================================

    @Test
    void scenario2_maxLossBreach_forceExitOverridesHold() {
        AuditCaptor audit = new AuditCaptor();

        InputModifyingGuard guard = new InputModifyingGuard() {
            @Override
            RiskGuardInput modify(RiskGuardInput in) {
                // livePnl = -600 pts, maxLossPoints = 500 pts → -600 < -500 → Stop 2 fires
                return new RiskGuardInput(
                        in.evaluationTs(), in.activeSession(), in.signalSnapshots(),
                        -600.0,  // livePnl — below the loss limit
                        in.bookedPnl(),
                        in.netDelta(),
                        in.lotCount(), in.maxLotCap(),
                        in.recentCommandCount(), in.churnWindowMinutes(),
                        500.0,   // maxLossPoints — threshold
                        in.staleDataSeconds(), in.lastTickAgeSeconds()
                );
            }
        };

        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                "NIFTY",
                ts -> List.of(),
                (session, ts) -> Collections.emptyMap(),
                Optional::empty,
                guard,
                fixedPolicy(DecisionCommand.CommandType.HOLD, "POLICY_HOLD"),
                audit,
                Phase4RiskIntegrationTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP
        );

        DecisionCommand cmd = agent.decide(DECISION_TS);

        // Command type: HOLD → overridden to EXIT_ALL
        assertEquals(DecisionCommand.CommandType.EXIT_ALL, cmd.commandType(),
                "Max loss breach must override HOLD → EXIT_ALL");
        assertTrue(cmd.overriddenByRiskGuard(),
                "overridden_by_risk_guard must be true for MAX_LOSS_BREACH");
        assertEquals(RiskGuardDecision.FORCE_EXIT, cmd.riskGuardDecision(),
                "Risk guard decision must be FORCE_EXIT");
        assertEquals("RISK_GUARD_FORCE_EXIT", cmd.reasonCode(),
                "reasonCode must identify the FORCE_EXIT override");

        // Risk guard snapshot: correct condition code and flag
        RiskGuardSnapshot snap = guard.lastSnapshot();
        assertTrue(snap.triggeredConditions().contains("MAX_LOSS_BREACH"),
                "MAX_LOSS_BREACH must appear in triggered conditions. Actual: "
                        + snap.triggeredConditions());
        assertTrue(snap.maxLossBreached(),
                "maxLossBreached flag must be true when max loss stop fires");

        // Audit: one record, override reflected
        assertEquals(1, audit.commands.size(), "Exactly one audit record expected");
        DecisionCommand audited = audit.commands.get(0);
        assertEquals(DecisionCommand.CommandType.EXIT_ALL, audited.commandType(),
                "Audited command type must be EXIT_ALL");
        assertTrue(audited.overriddenByRiskGuard(),
                "Audit record must carry overridden_by_risk_guard=true");
        assertEquals("RISK_GUARD_FORCE_EXIT", audited.reasonCode(),
                "Audit reason_code must be RISK_GUARD_FORCE_EXIT");
    }

    // =========================================================================
    // Scenario 3 — Churn detected → HALT_SESSION overrides any command
    //
    // Stop 7: recentCommandCount > MAX_COMMANDS_PER_CHURN_WINDOW (3) →
    //         HALT_SESSION + CHURN_DETECTED
    // Policy returns HOLD; Risk Guard overrides to EXIT_ALL (HALT_SESSION mapping).
    // =========================================================================

    @Test
    void scenario3_churnDetected_haltSessionOverridesHold() {
        AuditCaptor audit = new AuditCaptor();

        InputModifyingGuard guard = new InputModifyingGuard() {
            @Override
            RiskGuardInput modify(RiskGuardInput in) {
                // recentCommandCount = 5, threshold MAX_COMMANDS_PER_CHURN_WINDOW = 3
                // 5 > 3 → Stop 7 fires → HALT_SESSION
                return new RiskGuardInput(
                        in.evaluationTs(), in.activeSession(), in.signalSnapshots(),
                        in.livePnl(), in.bookedPnl(), in.netDelta(),
                        in.lotCount(), in.maxLotCap(),
                        5,   // recentCommandCount — exceeds threshold of 3
                        5,   // churnWindowMinutes
                        in.maxLossPoints(), in.staleDataSeconds(), in.lastTickAgeSeconds()
                );
            }
        };

        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                "NIFTY",
                ts -> List.of(),
                (session, ts) -> Collections.emptyMap(),
                Optional::empty,
                guard,
                fixedPolicy(DecisionCommand.CommandType.HOLD, "POLICY_HOLD"),
                audit,
                Phase4RiskIntegrationTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP
        );

        DecisionCommand cmd = agent.decide(DECISION_TS);

        // HALT_SESSION maps to EXIT_ALL in applyRiskGuardOverride
        assertEquals(DecisionCommand.CommandType.EXIT_ALL, cmd.commandType(),
                "Churn detection must override HOLD → EXIT_ALL (HALT_SESSION)");
        assertTrue(cmd.overriddenByRiskGuard(),
                "overridden_by_risk_guard must be true for CHURN_DETECTED");
        assertEquals(RiskGuardDecision.HALT_SESSION, cmd.riskGuardDecision(),
                "Risk guard decision must be HALT_SESSION");
        assertEquals("RISK_GUARD_HALT_SESSION", cmd.reasonCode(),
                "reasonCode must identify the HALT_SESSION override");

        // Risk guard snapshot: correct condition code and flag
        RiskGuardSnapshot snap = guard.lastSnapshot();
        assertTrue(snap.triggeredConditions().contains("CHURN_DETECTED"),
                "CHURN_DETECTED must appear in triggered conditions. Actual: "
                        + snap.triggeredConditions());
        assertTrue(snap.churnDetected(),
                "churnDetected flag must be true when churn stop fires");

        // Audit: one record, override reflected
        assertEquals(1, audit.commands.size(), "Exactly one audit record expected");
        DecisionCommand audited = audit.commands.get(0);
        assertEquals(DecisionCommand.CommandType.EXIT_ALL, audited.commandType(),
                "Audited command type must be EXIT_ALL");
        assertTrue(audited.overriddenByRiskGuard(),
                "Audit record must carry overridden_by_risk_guard=true");
        assertEquals("RISK_GUARD_HALT_SESSION", audited.reasonCode(),
                "Audit reason_code must be RISK_GUARD_HALT_SESSION");
    }

    // =========================================================================
    // Scenario 4 — Data stale → BLOCK_NEW_ENTRY prevents re-entry after booking
    //
    // Stop 5: lastTickAgeSeconds > staleDataSeconds → BLOCK_NEW_ENTRY + STALE_DATA
    // Simulates the scenario where profit has been booked, no active session,
    // and the policy recommends ENTER (re-entry) — but stale data blocks it.
    // Expected outcome: SKIP with overridden_by_risk_guard=true.
    // =========================================================================

    @Test
    void scenario4_dataStale_blockNewEntryPreventsReEntry() {
        AuditCaptor audit = new AuditCaptor();

        InputModifyingGuard guard = new InputModifyingGuard() {
            @Override
            RiskGuardInput modify(RiskGuardInput in) {
                // lastTickAgeSeconds = 120 s, staleDataSeconds = 30 s
                // 120 > 30 → Stop 5 fires → BLOCK_NEW_ENTRY
                return new RiskGuardInput(
                        in.evaluationTs(), in.activeSession(), in.signalSnapshots(),
                        in.livePnl(), in.bookedPnl(), in.netDelta(),
                        in.lotCount(), in.maxLotCap(),
                        in.recentCommandCount(), in.churnWindowMinutes(),
                        in.maxLossPoints(),
                        30,   // staleDataSeconds — threshold
                        120   // lastTickAgeSeconds — exceeds threshold → STALE_DATA
                );
            }
        };

        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                "NIFTY",
                ts -> List.of(qualifiedCandidate()),  // candidate present → policy would ENTER
                (session, ts) -> Collections.emptyMap(),
                Optional::empty,                      // no active session (profit was booked)
                guard,
                fixedPolicy(DecisionCommand.CommandType.ENTER, "POLICY_ENTER"),
                audit,
                Phase4RiskIntegrationTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP
        );

        DecisionCommand cmd = agent.decide(DECISION_TS);

        // ENTER downgraded to SKIP because BLOCK_NEW_ENTRY is active
        assertEquals(DecisionCommand.CommandType.SKIP, cmd.commandType(),
                "Stale data must block ENTER → SKIP");
        assertTrue(cmd.overriddenByRiskGuard(),
                "overridden_by_risk_guard must be true for STALE_DATA block");
        assertEquals(RiskGuardDecision.BLOCK_NEW_ENTRY, cmd.riskGuardDecision(),
                "Risk guard decision must be BLOCK_NEW_ENTRY");
        assertEquals("RISK_GUARD_BLOCK_NEW_ENTRY", cmd.reasonCode(),
                "reasonCode must identify the BLOCK_NEW_ENTRY override");
        assertTrue(cmd.selectedCandidateIds().isEmpty(),
                "Blocked ENTER must have no selected candidates");

        // Risk guard snapshot: correct condition code and flag
        RiskGuardSnapshot snap = guard.lastSnapshot();
        assertTrue(snap.triggeredConditions().contains("STALE_DATA"),
                "STALE_DATA must appear in triggered conditions. Actual: "
                        + snap.triggeredConditions());
        assertTrue(snap.dataStale(),
                "dataStale flag must be true when stale-data stop fires");

        // Audit: one record, override reflected
        assertEquals(1, audit.commands.size(), "Exactly one audit record expected");
        DecisionCommand audited = audit.commands.get(0);
        assertEquals(DecisionCommand.CommandType.SKIP, audited.commandType(),
                "Audited command type must be SKIP");
        assertTrue(audited.overriddenByRiskGuard(),
                "Audit record must carry overridden_by_risk_guard=true");
        assertEquals("RISK_GUARD_BLOCK_NEW_ENTRY", audited.reasonCode(),
                "Audit reason_code must be RISK_GUARD_BLOCK_NEW_ENTRY");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Abstract {@link RiskGuardService} subclass that intercepts evaluate(),
     * lets the subclass modify the input, then delegates to the real evaluation
     * logic via {@code super.evaluate()}. Records the returned snapshot for
     * assertion.
     */
    private abstract static class InputModifyingGuard extends RiskGuardService {

        private RiskGuardSnapshot lastSnapshot;

        @Override
        public final RiskGuardSnapshot evaluate(RiskGuardInput input) {
            lastSnapshot = super.evaluate(modify(input));
            return lastSnapshot;
        }

        /**
         * Called before {@code super.evaluate()}. Subclass replaces specific fields
         * of the input to trigger a particular hard stop.
         */
        abstract RiskGuardInput modify(RiskGuardInput input);

        /**
         * Returns the {@link RiskGuardSnapshot} produced by the most recent
         * {@code evaluate()} call. Useful for asserting triggered conditions.
         */
        RiskGuardSnapshot lastSnapshot() {
            return lastSnapshot;
        }
    }

    private static DecisionPolicy fixedPolicy(DecisionCommand.CommandType type, String reasonCode) {
        return new DecisionPolicy() {
            @Override
            public DecisionCommand evaluate(DecisionContext ctx) {
                return new DecisionCommand(
                        UUID.randomUUID(),
                        DECISION_TS,
                        ctx.mode(),
                        type,
                        type == DecisionCommand.CommandType.ENTER
                                ? List.of("CANDIDATE-1")
                                : List.of(),
                        Optional.empty(),
                        reasonCode,
                        "Policy chose " + type,
                        ctx.riskGuardSnapshot().decision(),
                        false
                );
            }
        };
    }

    private static CandidateOpportunity qualifiedCandidate() {
        return new CandidateOpportunity(
                "CANDIDATE-1",
                "NIFTY",
                "INS_NIFTY_20260430_24800_CE",
                "NIFTY26APR24800CE",
                "CE",
                new BigDecimal("24800"),
                LocalDate.of(2026, 4, 30),
                "WEEKLY",
                new BigDecimal("24750"),
                new BigDecimal("130"),
                new BigDecimal("105"),
                new BigDecimal("110"),
                BigDecimal.ZERO,
                40,
                6,
                new BigDecimal("130"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0.85,
                0.70,
                0.75,
                0.82,
                Optional.empty()
        );
    }

    // -------------------------------------------------------------------------
    // Noop JDBC connection (avoids DB dependency in unit tests)
    // -------------------------------------------------------------------------

    private static Connection noopConnection() {
        InvocationHandler connectionHandler = (proxy, method, args) -> {
            String name = method.getName();
            if ("prepareStatement".equals(name)) {
                InvocationHandler psHandler = (p, psMethod, psArgs) -> {
                    String psName = psMethod.getName();
                    if ("executeUpdate".equals(psName)) return 1;
                    if ("close".equals(psName)) return null;
                    if ("getResultSet".equals(psName)) return resultSetProxy();
                    return defaultValue(psMethod.getReturnType());
                };
                return Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[]{PreparedStatement.class},
                        psHandler
                );
            }
            if ("close".equals(name)) return null;
            return defaultValue(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                connectionHandler
        );
    }

    private static ResultSet resultSetProxy() {
        InvocationHandler rsHandler = (proxy, method, args) -> {
            String name = method.getName();
            if ("next".equals(name)) return false;
            if ("close".equals(name)) return null;
            return defaultValue(method.getReturnType());
        };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class[]{ResultSet.class},
                rsHandler
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (!returnType.isPrimitive()) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Double.TYPE) return 0.0d;
        if (returnType == Float.TYPE) return 0.0f;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Character.TYPE) return '\0';
        return null;
    }

    // -------------------------------------------------------------------------
    // Audit captor — captures all written commands without DB I/O
    // -------------------------------------------------------------------------

    private static final class AuditCaptor extends DecisionAuditWriter {
        private final List<DecisionCommand> commands = new ArrayList<>();

        @Override
        public void write(Connection conn, DecisionCommand cmd, AuditContext ctx) {
            commands.add(cmd);
        }
    }
}
