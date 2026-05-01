package com.strategysquad.agentic.decision;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionAgentRiskGuardGatingTest {

    private static final Instant DECISION_TS = Instant.parse("2026-04-26T10:15:00Z");

    @Test
    void policyHold_guardForceExit_emitsExitAllWithOverride() {
        AuditCaptor auditCaptor = new AuditCaptor();
        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.PAPER,
                "NIFTY",
                ts -> List.of(),
                (session, ts) -> java.util.Collections.emptyMap(),
                Optional::empty,
                fixedGuard(RiskGuardDecision.FORCE_EXIT, "MAX_LOSS_BREACH"),
                fixedPolicy(DecisionCommand.CommandType.HOLD, "POLICY_HOLD"),
                auditCaptor,
                DecisionAgentRiskGuardGatingTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP
        );

        DecisionCommand cmd = agent.decide(DECISION_TS);

        assertEquals(DecisionCommand.CommandType.EXIT_ALL, cmd.commandType());
        assertTrue(cmd.overriddenByRiskGuard());
        assertEquals(RiskGuardDecision.FORCE_EXIT, cmd.riskGuardDecision());
        assertEquals("RISK_GUARD_FORCE_EXIT", cmd.reasonCode());
        assertEquals(1, auditCaptor.commands.size());
        assertEquals(DecisionCommand.CommandType.EXIT_ALL, auditCaptor.commands.get(0).commandType());
    }

    @Test
    void policyEnter_guardBlockNewEntry_emitsSkipWithOverride() {
        AuditCaptor auditCaptor = new AuditCaptor();
        CandidateOpportunity candidate = qualifiedCandidate();
        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.PAPER,
                "NIFTY",
                ts -> List.of(candidate),
                (session, ts) -> java.util.Collections.emptyMap(),
                Optional::empty,
                fixedGuard(RiskGuardDecision.BLOCK_NEW_ENTRY, "LOT_CAP_BREACH"),
                fixedPolicy(DecisionCommand.CommandType.ENTER, "POLICY_ENTER"),
                auditCaptor,
                DecisionAgentRiskGuardGatingTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP
        );

        DecisionCommand cmd = agent.decide(DECISION_TS);

        assertEquals(DecisionCommand.CommandType.SKIP, cmd.commandType());
        assertTrue(cmd.overriddenByRiskGuard());
        assertEquals(RiskGuardDecision.BLOCK_NEW_ENTRY, cmd.riskGuardDecision());
        assertEquals("RISK_GUARD_BLOCK_NEW_ENTRY", cmd.reasonCode());
        assertTrue(cmd.selectedCandidateIds().isEmpty());
        assertFalse(cmd.positionSessionId().isPresent());
        assertEquals(1, auditCaptor.commands.size());
        assertEquals(DecisionCommand.CommandType.SKIP, auditCaptor.commands.get(0).commandType());
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
                        List.of("CANDIDATE-1"),
                        Optional.empty(),
                        reasonCode,
                        "Policy chose " + type,
                        ctx.riskGuardSnapshot().decision(),
                        false
                );
            }
        };
    }

    private static RiskGuardService fixedGuard(RiskGuardDecision decision, String conditionCode) {
        return new RiskGuardService() {
            @Override
            public RiskGuardSnapshot evaluate(RiskGuardInput input) {
                return new RiskGuardSnapshot(
                        input.evaluationTs(),
                        decision,
                        List.of(conditionCode),
                        "Fixed guard " + decision,
                        input.netDelta(),
                        input.livePnl(),
                        decision == RiskGuardDecision.FORCE_EXIT,
                        false,
                        false,
                        false,
                        decision == RiskGuardDecision.HALT_SESSION,
                        decision == RiskGuardDecision.BLOCK_NEW_ENTRY
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

    private static final class AuditCaptor extends DecisionAuditWriter {
        private final List<DecisionCommand> commands = new ArrayList<>();

        @Override
        public void write(Connection conn, DecisionCommand cmd, AuditContext ctx) {
            commands.add(cmd);
        }
    }
}
