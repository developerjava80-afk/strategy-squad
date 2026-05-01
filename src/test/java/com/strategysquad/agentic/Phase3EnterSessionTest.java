package com.strategysquad.agentic;

import com.strategysquad.agentic.builder.PositionBuilderAgent;
import com.strategysquad.agentic.decision.DecisionAgent;
import com.strategysquad.agentic.decision.DecisionCommand;
import com.strategysquad.agentic.decision.DecisionPolicy;
import com.strategysquad.agentic.decision.DecisionAuditWriter;
import com.strategysquad.agentic.risk.RiskGuardService;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.PositionSessionSnapshot;

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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 integration test: a simulated ENTER decision must create a position
 * session with exactly two legs (one CE, one PE).
 *
 * <p>All collaborators are pure in-memory stubs — no live DB, no file I/O.
 */
class Phase3EnterSessionTest {

    private static final String UNDERLYING = "NIFTY";
    private static final Instant DECISION_TS = Instant.parse("2026-04-26T09:30:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 30);

    // =========================================================================
    // Test: simulated ENTER creates a position session with exactly 2 legs
    // =========================================================================

    @Test
    void simulatedEnter_createsPositionSession_withExactlyTwoLegs() throws Exception {

        // ── Candidates: one qualified CE and one qualified PE at same strike ──
        CandidateOpportunity ce = candidate("INS_NIFTY_20260430_24800_CE", "CE",
                new BigDecimal("24800"), new BigDecimal("150.0"), 0.80);
        CandidateOpportunity pe = candidate("INS_NIFTY_20260430_24800_PE", "PE",
                new BigDecimal("24800"), new BigDecimal("140.0"), 0.78);

        List<CandidateOpportunity> candidates = List.of(ce, pe);

        // ── Signals: small delta for both legs ─────────────────────────────
        Map<String, SignalSnapshot> signals = Map.of(
                "INS_NIFTY_20260430_24800_CE",
                signal("INS_NIFTY_20260430_24800_CE", "CE", "0.08"),
                "INS_NIFTY_20260430_24800_PE",
                signal("INS_NIFTY_20260430_24800_PE", "PE", "-0.07")
        );

        // ── SessionPersister: captures the created session ──────────────────
        List<PositionSessionSnapshot> createdSessions = new ArrayList<>();
        DecisionAgent.SessionPersister capturer = createdSessions::add;

        // ── PlanBuilder: backed by real PositionBuilderAgent.buildShortStraddle ──
        PositionBuilderAgent.LotSizeLoader lotLoader =
                u -> "NIFTY".equals(u) ? 65 : 30;
        PositionBuilderAgent builderAgent = new PositionBuilderAgent(lotLoader);
        DecisionAgent.PlanBuilder planBuilder =
                (u, cands, sigs, ts) -> builderAgent.buildShortStraddle(u, cands, sigs, ts);

        // ── AuditCaptor: no-op writes ───────────────────────────────────────
        DecisionAuditWriter noopAudit = new DecisionAuditWriter() {
            @Override
            public void write(Connection conn, DecisionCommand cmd,
                              DecisionAuditWriter.AuditContext ctx) {
                // swallow — we don't need audit verification here
            }
        };

        // ── DecisionAgent with SIMULATION mode and planBuilder injected ─────
        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                UNDERLYING,
                ts -> candidates,          // candidateLoader
                (session, ts) -> signals,  // signalLoader
                Optional::empty,           // sessionLoader — no active session → triggers ENTER
                new RiskGuardService(),
                new DecisionPolicy(),
                noopAudit,
                Phase3EnterSessionTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP,
                planBuilder,
                capturer
        );

        DecisionCommand cmd = agent.decide(DECISION_TS);

        // ── The command must be ENTER ───────────────────────────────────────
        assertEquals(DecisionCommand.CommandType.ENTER, cmd.commandType(),
                "Decision must be ENTER when qualified candidates are present and no session is active");

        // ── Exactly one session must have been created ──────────────────────
        assertEquals(1, createdSessions.size(),
                "Exactly one position session must be created on ENTER");

        PositionSessionSnapshot session = createdSessions.get(0);

        // ── Session must have exactly 2 legs: one CE and one PE ─────────────
        assertEquals(2, session.legs().size(),
                "Created session must have exactly 2 legs");

        long ceLegs = session.legs().stream()
                .filter(l -> "CE".equals(l.optionType())).count();
        long peLegs = session.legs().stream()
                .filter(l -> "PE".equals(l.optionType())).count();

        assertEquals(1, ceLegs, "Session must have exactly 1 CE leg");
        assertEquals(1, peLegs, "Session must have exactly 1 PE leg");

        // ── Session state must be ACTIVE ────────────────────────────────────
        assertEquals("ACTIVE", session.status(), "New session must have status ACTIVE");

        // ── Command's positionSessionId must link to the created session ─────
        assertTrue(cmd.positionSessionId().isPresent(),
                "ENTER command must carry the new session ID");
        assertEquals(session.sessionId(), cmd.positionSessionId().get(),
                "Command positionSessionId must match the created session");
    }

    // =========================================================================
    // Test: ENTER with no qualified candidates → SKIP (no session created)
    // =========================================================================

    @Test
    void enterWithNoQualifiedCandidates_emitsSkip_noSessionCreated() {
        // All candidates are disqualified
        CandidateOpportunity disqualified = new CandidateOpportunity(
                "SCAN_1", UNDERLYING, "INS_NIFTY_20260430_24800_CE", "NIFTY26APR24800CE",
                "CE", new BigDecimal("24800"), EXPIRY, "WEEKLY",
                new BigDecimal("24750"), new BigDecimal("0"),
                new BigDecimal("0"), new BigDecimal("0"),
                BigDecimal.ZERO, 0, 8,
                new BigDecimal("0"), BigDecimal.ZERO, BigDecimal.ZERO,
                0.0, 0.0, 0.0, 0.0,
                Optional.of("ZERO_BID")
        );

        List<PositionSessionSnapshot> createdSessions = new ArrayList<>();
        PositionBuilderAgent.LotSizeLoader lotLoader = u -> 65;
        PositionBuilderAgent builderAgent = new PositionBuilderAgent(lotLoader);
        DecisionAgent.PlanBuilder planBuilder =
                (u, cands, sigs, ts) -> builderAgent.buildShortStraddle(u, cands, sigs, ts);

        DecisionAuditWriter noopAudit = new DecisionAuditWriter() {
            @Override
            public void write(Connection conn, DecisionCommand cmd,
                              DecisionAuditWriter.AuditContext ctx) { }
        };

        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                UNDERLYING,
                ts -> List.of(disqualified),
                (session, ts) -> Collections.emptyMap(),
                Optional::empty,
                new RiskGuardService(),
                new DecisionPolicy(),
                noopAudit,
                Phase3EnterSessionTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP,
                planBuilder,
                createdSessions::add
        );

        DecisionCommand cmd = agent.decide(DECISION_TS);

        // No qualified candidates → policy emits SKIP (Rule 10 / no-entry)
        assertNotEquals(DecisionCommand.CommandType.ENTER, cmd.commandType(),
                "With no qualified candidates, command must not be ENTER");
        assertEquals(0, createdSessions.size(),
                "No session must be created when candidates are disqualified");
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private static CandidateOpportunity candidate(
            String instrumentId, String optionType,
            BigDecimal strike, BigDecimal lastPrice, double totalScore) {

        return new CandidateOpportunity(
                "SCAN_" + instrumentId,
                UNDERLYING,
                instrumentId,
                instrumentId,
                optionType,
                strike,
                EXPIRY,
                "WEEKLY",
                new BigDecimal("24750.0"),
                lastPrice,
                lastPrice,
                lastPrice.add(BigDecimal.ONE),
                BigDecimal.ZERO,
                50,
                8,
                lastPrice,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0.80,
                0.65,
                0.70,
                totalScore,
                Optional.empty()
        );
    }

    private static SignalSnapshot signal(String instrumentId, String optionType, String delta2m) {
        return new SignalSnapshot(
                DECISION_TS,
                instrumentId,
                UNDERLYING,
                optionType,
                new BigDecimal("24800"),
                new BigDecimal(delta2m),
                null, null,
                new BigDecimal("10.0"),
                new BigDecimal("0.8"),
                new BigDecimal("0.5"),
                null, null,
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.CONFIRMED,
                false,
                "OK"
        );
    }

    static Connection noopConnection() throws java.sql.SQLException {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> noopPreparedStatement();
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }

    private static PreparedStatement noopPreparedStatement() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "setString", "setTimestamp", "setDouble",
                 "setBoolean", "setNull", "setInt" -> null;
            case "executeUpdate" -> 1;
            case "close" -> null;
            case "executeQuery" -> noopResultSet();
            default -> defaultValue(method.getReturnType());
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    private static ResultSet noopResultSet() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> false;
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, h);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class)     return 0;
        if (type == long.class)    return 0L;
        if (type == double.class)  return 0.0;
        return null;
    }
}
