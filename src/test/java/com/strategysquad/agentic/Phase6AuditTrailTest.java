package com.strategysquad.agentic;

import com.strategysquad.agentic.adjustment.AdjustmentAgent;
import com.strategysquad.agentic.booking.ProfitBookingAgent;
import com.strategysquad.agentic.decision.DecisionAgent;
import com.strategysquad.agentic.decision.DecisionAuditWriter;
import com.strategysquad.agentic.decision.DecisionCommand;
import com.strategysquad.agentic.decision.DecisionPolicy;
import com.strategysquad.agentic.orchestrator.MarketDayOrchestrator;
import com.strategysquad.agentic.orchestrator.MarketDayOrchestrator.OrchestratorState;
import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.risk.RiskGuardService;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.MarketSessionStateResolver;
import com.strategysquad.research.PositionSessionActionService;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 audit trail verification test.
 *
 * <h2>Acceptance criteria</h2>
 * <ol>
 *   <li>Given fixed historical inputs, two identical replays produce exactly the same
 *       {@link DecisionCommand} sequence (same types, reason codes, and explanations).</li>
 *   <li>Every command has a non-blank {@code reasonCode} and a non-blank
 *       {@code explanation}.</li>
 *   <li>Every forced action has {@code overriddenByRiskGuard=true} and
 *       the {@code explanation} contains a non-blank triggered-conditions list.</li>
 * </ol>
 *
 * <p>All collaborators are pure in-memory stubs — no live DB, no broker connection.
 */
class Phase6AuditTrailTest {

    private static final String UNDERLYING = "NIFTY";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 30);

    private static final ZoneId IST = MarketSessionStateResolver.EXCHANGE_ZONE;

    private static final Instant T_MARKET_OPEN =
            LocalDate.of(2026, 4, 26).atTime(LocalTime.of(10, 0))
                    .atZone(IST).toInstant();

    private static final Instant T_END_OF_DAY =
            LocalDate.of(2026, 4, 26).atTime(LocalTime.of(15, 45))
                    .atZone(IST).toInstant();

    private static final BigDecimal CE_ENTRY = new BigDecimal("150.0");
    private static final BigDecimal PE_ENTRY = new BigDecimal("140.0");
    private static final int LOT_SIZE = 65;

    // =========================================================================
    // Test 1 — deterministic replay
    // =========================================================================

    /**
     * Runs the complete market-day scenario twice with identical fixed inputs.
     * Asserts that both runs produce the same {@link DecisionCommand} sequence,
     * and that every command in the sequence has non-blank {@code reasonCode} and
     * {@code explanation}.
     */
    @Test
    void deterministicReplay_producesIdenticalCommandSequence_andAllCommandsHaveAuditFields()
            throws Exception {

        List<DecisionCommand> run1 = new ArrayList<>();
        runNormalMarketDayScenario(run1);

        List<DecisionCommand> run2 = new ArrayList<>();
        runNormalMarketDayScenario(run2);

        // ── Determinism: both runs must produce the same number of commands ──
        assertFalse(run1.isEmpty(), "At least one command must be emitted during a full day");
        assertEquals(run1.size(), run2.size(),
                "Both replays must produce the same number of commands");

        // ── Determinism: same types, reason codes, and explanations in order ──
        for (int i = 0; i < run1.size(); i++) {
            DecisionCommand c1 = run1.get(i);
            DecisionCommand c2 = run2.get(i);

            assertEquals(c1.commandType(), c2.commandType(),
                    "Command[" + i + "] type must match between runs");
            assertEquals(c1.reasonCode(), c2.reasonCode(),
                    "Command[" + i + "] reasonCode must match between runs");
            assertEquals(c1.explanation(), c2.explanation(),
                    "Command[" + i + "] explanation must match between runs");
        }

        // ── Audit field completeness: every command must have non-blank fields ──
        for (DecisionCommand cmd : run1) {
            assertFalse(cmd.reasonCode() == null || cmd.reasonCode().isBlank(),
                    "Every command must have a non-blank reasonCode; got: [" + cmd.reasonCode()
                    + "] for commandType=" + cmd.commandType());
            assertFalse(cmd.explanation() == null || cmd.explanation().isBlank(),
                    "Every command must have a non-blank explanation; got: [" + cmd.explanation()
                    + "] for commandType=" + cmd.commandType());
        }
    }

    /**
     * Drives the orchestrator through a complete normal market day (ENTER + BOOK_PROFIT
     * path) and collects all {@link DecisionCommand} objects emitted by the
     * {@link DecisionAgent}.
     *
     * <p>This method creates a completely fresh set of collaborators each time it is
     * called, ensuring no shared state between runs.
     */
    private void runNormalMarketDayScenario(List<DecisionCommand> capturedCommands)
            throws Exception {

        AtomicReference<Instant> clockInstant = new AtomicReference<>(T_MARKET_OPEN);
        MarketDayOrchestrator.Clock clock = clockInstant::get;

        // State persister captures state transitions but we only care about commands here
        MarketDayOrchestrator.StatePersister noop =
                (newState, priorState, elapsedMs, cmdType, cmdId, haltReason, notes) -> {};

        // Scan candidates: qualified CE and PE for a short straddle
        CandidateOpportunity ce = candidate(
                "INS_NIFTY_20260430_24800_CE", "CE",
                new BigDecimal("24800"), CE_ENTRY, 0.82);
        CandidateOpportunity pe = candidate(
                "INS_NIFTY_20260430_24800_PE", "PE",
                new BigDecimal("24800"), PE_ENTRY, 0.78);
        List<CandidateOpportunity> candidates = List.of(ce, pe);

        // Session holder: empty until ENTER fires
        AtomicReference<PositionSessionSnapshot> activeSession = new AtomicReference<>(null);

        // Signal loader: empty before session; profit-booking signals (80% decay) after
        DecisionAgent.SignalLoader signalLoader = (session, ts) -> {
            if (session == null || session.isEmpty()) return Map.of();
            PositionSessionSnapshot s = session.get();
            Map<String, SignalSnapshot> signals = new LinkedHashMap<>();
            for (PositionSessionSnapshot.PositionLegSnapshot leg : s.legs()) {
                BigDecimal entryPrice =
                        leg.entryPrice() != null ? leg.entryPrice() : new BigDecimal("100.0");
                BigDecimal decay = entryPrice.multiply(new BigDecimal("0.80"));
                signals.put(leg.instrumentId(),
                        monitorSignal(leg.instrumentId(), leg.optionType(),
                                decay, new BigDecimal("0.80")));
            }
            return Map.copyOf(signals);
        };

        // Plan builder delegates to the real PositionBuilderAgent
        com.strategysquad.agentic.builder.PositionBuilderAgent.LotSizeLoader lotLoader =
                u -> "NIFTY".equals(u) ? LOT_SIZE : 30;
        com.strategysquad.agentic.builder.PositionBuilderAgent builderAgent =
                new com.strategysquad.agentic.builder.PositionBuilderAgent(lotLoader);
        DecisionAgent.PlanBuilder planBuilder =
                (u, cands, sigs, ts) -> builderAgent.buildShortStraddle(u, cands, sigs, ts);

        // Decision agent with collecting audit writer
        DecisionAgent decisionAgent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                UNDERLYING,
                /* candidateLoader  */ ts -> candidates,
                /* signalLoader     */ signalLoader,
                /* sessionLoader    */ () -> Optional.ofNullable(activeSession.get()),
                new RiskGuardService(),
                new DecisionPolicy(),
                /* auditWriter      */ collectingAuditWriter(capturedCommands),
                /* connectionSupplier */ Phase6AuditTrailTest::noopConnection,
                /* maxLotCap        */ DecisionAgent.DEFAULT_MAX_LOT_CAP,
                /* planBuilder      */ planBuilder,
                /* sessionPersister */ activeSession::set
        );

        PositionSessionActionService actionService = new PositionSessionActionService();
        ProfitBookingAgent bookingAgent = new ProfitBookingAgent(actionService);
        AdjustmentAgent adjustmentAgent = new AdjustmentAgent(actionService);

        MarketDayOrchestrator orchestrator = new MarketDayOrchestrator(
                "SIMULATION", UNDERLYING,
                decisionAgent, bookingAgent, adjustmentAgent,
                clock, noop);

        // Drive the state machine to END_OF_DAY:
        // tick 1: PRE_OPEN_SCAN  → WAIT_MARKET_OPEN
        // tick 2: WAIT_MARKET_OPEN → EVALUATE_ENTRY
        // tick 3: EVALUATE_ENTRY  → POSITION_OPEN  (ENTER command captured)
        // tick 4: POSITION_OPEN   → MONITOR
        // tick 5: MONITOR         → BOOK_PROFIT    (BOOK_PROFIT command captured)
        // tick 6: BOOK_PROFIT     → RESTART_SCAN
        // [advance clock to after 15:30]
        // tick 7: RESTART_SCAN    → END_OF_DAY
        OrchestratorState s1 = orchestrator.tick();
        assertEquals(OrchestratorState.WAIT_MARKET_OPEN, s1);

        OrchestratorState s2 = orchestrator.tick();
        assertEquals(OrchestratorState.EVALUATE_ENTRY, s2);

        OrchestratorState s3 = orchestrator.tick();
        assertEquals(OrchestratorState.POSITION_OPEN, s3,
                "ENTER decision must advance to POSITION_OPEN");

        OrchestratorState s4 = orchestrator.tick();
        assertEquals(OrchestratorState.MONITOR, s4);

        OrchestratorState s5 = orchestrator.tick();
        assertEquals(OrchestratorState.BOOK_PROFIT, s5,
                "80%% decay signals must trigger BOOK_PROFIT");

        OrchestratorState s6 = orchestrator.tick();
        assertEquals(OrchestratorState.RESTART_SCAN, s6);

        clockInstant.set(T_END_OF_DAY);

        OrchestratorState s7 = orchestrator.tick();
        assertEquals(OrchestratorState.END_OF_DAY, s7,
                "After 15:30 IST, RESTART_SCAN must transition to END_OF_DAY");
    }

    // =========================================================================
    // Test 2 — forced risk-guard override
    // =========================================================================

    /**
     * Drives the orchestrator into a risk-guard override scenario by providing
     * zero-liquidity ({@link SignalSnapshot.VolumeState#ABSENT}) signals after a
     * position session is created.
     *
     * <p>With an active session and absent liquidity, {@link RiskGuardService} fires
     * Stop 4 ({@code ZERO_BID} → {@link RiskGuardDecision#FORCE_EXIT}).
     * The resulting command must have:
     * <ul>
     *   <li>{@code overriddenByRiskGuard == true}</li>
     *   <li>{@code riskGuardDecision != RiskGuardDecision.ALLOW}</li>
     *   <li>A non-blank {@code explanation} containing "Triggered conditions:"</li>
     * </ul>
     */
    @Test
    void forcedRiskGuardOverride_hasOverriddenFlag_andTriggeredConditionsInExplanation()
            throws Exception {

        List<DecisionCommand> captured = new ArrayList<>();

        AtomicReference<Instant> clockInstant = new AtomicReference<>(T_MARKET_OPEN);
        MarketDayOrchestrator.Clock clock = clockInstant::get;

        MarketDayOrchestrator.StatePersister noop =
                (newState, priorState, elapsedMs, cmdType, cmdId, haltReason, notes) -> {};

        CandidateOpportunity ce = candidate(
                "INS_NIFTY_20260430_24800_CE", "CE",
                new BigDecimal("24800"), CE_ENTRY, 0.82);
        CandidateOpportunity pe = candidate(
                "INS_NIFTY_20260430_24800_PE", "PE",
                new BigDecimal("24800"), PE_ENTRY, 0.78);
        List<CandidateOpportunity> candidates = List.of(ce, pe);

        AtomicReference<PositionSessionSnapshot> activeSession = new AtomicReference<>(null);

        // Signal loader: absent volume once a session is active → triggers FORCE_EXIT
        DecisionAgent.SignalLoader forcedSignalLoader = (session, ts) -> {
            if (session == null || session.isEmpty()) return Map.of();
            PositionSessionSnapshot s = session.get();
            Map<String, SignalSnapshot> signals = new LinkedHashMap<>();
            for (PositionSessionSnapshot.PositionLegSnapshot leg : s.legs()) {
                signals.put(leg.instrumentId(),
                        absentVolumeSignal(leg.instrumentId(), leg.optionType()));
            }
            return Map.copyOf(signals);
        };

        com.strategysquad.agentic.builder.PositionBuilderAgent.LotSizeLoader lotLoader =
                u -> "NIFTY".equals(u) ? LOT_SIZE : 30;
        com.strategysquad.agentic.builder.PositionBuilderAgent builderAgent =
                new com.strategysquad.agentic.builder.PositionBuilderAgent(lotLoader);
        DecisionAgent.PlanBuilder planBuilder =
                (u, cands, sigs, ts) -> builderAgent.buildShortStraddle(u, cands, sigs, ts);

        DecisionAgent decisionAgent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                UNDERLYING,
                ts -> candidates,
                forcedSignalLoader,
                () -> Optional.ofNullable(activeSession.get()),
                new RiskGuardService(),
                new DecisionPolicy(),
                collectingAuditWriter(captured),
                Phase6AuditTrailTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP,
                planBuilder,
                activeSession::set
        );

        PositionSessionActionService actionService = new PositionSessionActionService();
        ProfitBookingAgent bookingAgent = new ProfitBookingAgent(actionService);
        AdjustmentAgent adjustmentAgent = new AdjustmentAgent(actionService);

        MarketDayOrchestrator orchestrator = new MarketDayOrchestrator(
                "SIMULATION", UNDERLYING,
                decisionAgent, bookingAgent, adjustmentAgent,
                clock, noop);

        // tick 1: PRE_OPEN_SCAN  → WAIT_MARKET_OPEN
        orchestrator.tick();
        // tick 2: WAIT_MARKET_OPEN → EVALUATE_ENTRY
        orchestrator.tick();
        // tick 3: EVALUATE_ENTRY  → POSITION_OPEN  (ENTER; no session yet, no absent signals)
        OrchestratorState afterEnter = orchestrator.tick();
        assertEquals(OrchestratorState.POSITION_OPEN, afterEnter,
                "ENTER must succeed before session (no absent signals yet)");
        assertNotNull(activeSession.get(), "Session must be set after ENTER");

        // tick 4: POSITION_OPEN → MONITOR
        orchestrator.tick();

        // tick 5: MONITOR — session now active, signals are ABSENT → FORCE_EXIT → EXIT_ALL
        OrchestratorState afterForce = orchestrator.tick();
        assertEquals(OrchestratorState.EXITED, afterForce,
                "FORCE_EXIT override must transition MONITOR → EXITED via EXIT_ALL");

        // ── Two commands must have been captured: ENTER, then forced EXIT_ALL ──
        assertEquals(2, captured.size(),
                "Exactly 2 commands expected (ENTER + forced EXIT_ALL); got: "
                + captured.stream().map(c -> c.commandType().name()).toList());

        DecisionCommand enterCmd  = captured.get(0);
        DecisionCommand forcedCmd = captured.get(1);

        assertEquals(DecisionCommand.CommandType.ENTER, enterCmd.commandType(),
                "First command must be ENTER");
        assertEquals(DecisionCommand.CommandType.EXIT_ALL, forcedCmd.commandType(),
                "Forced command must be EXIT_ALL");

        // ── Forced action: overridden flag must be set ──
        assertTrue(forcedCmd.overriddenByRiskGuard(),
                "Forced command must have overriddenByRiskGuard=true");

        // ── Forced action: risk guard decision must not be ALLOW ──
        assertNotEquals(RiskGuardDecision.ALLOW, forcedCmd.riskGuardDecision(),
                "Forced command must carry a non-ALLOW riskGuardDecision; got: "
                + forcedCmd.riskGuardDecision());

        // ── Forced action: explanation must contain triggered condition codes ──
        assertFalse(forcedCmd.explanation() == null || forcedCmd.explanation().isBlank(),
                "Forced command must have a non-blank explanation");
        assertTrue(forcedCmd.explanation().contains("Triggered conditions:"),
                "Forced command explanation must contain 'Triggered conditions:'; got: "
                + forcedCmd.explanation());

        // ── Audit field completeness on the enter command too ──
        assertFalse(enterCmd.reasonCode() == null || enterCmd.reasonCode().isBlank(),
                "ENTER command must have a non-blank reasonCode");
        assertFalse(enterCmd.explanation() == null || enterCmd.explanation().isBlank(),
                "ENTER command must have a non-blank explanation");
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private static CandidateOpportunity candidate(
            String instrumentId,
            String optionType,
            BigDecimal strike,
            BigDecimal lastPrice,
            double totalScore) {

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

    /**
     * Builds a monitoring signal with high theta progress — enough to trigger
     * BOOK_PROFIT (thetaProgressRatio ≥ 0.75 AND livePnl > 0).
     */
    private static SignalSnapshot monitorSignal(
            String instrumentId,
            String optionType,
            BigDecimal expectedDecay,
            BigDecimal thetaProgressRatio) {

        return new SignalSnapshot(
                T_MARKET_OPEN.plusSeconds(600),
                instrumentId,
                UNDERLYING,
                optionType,
                new BigDecimal("24800"),
                new BigDecimal("0.08"),
                null,
                null,
                new BigDecimal("10.0"),
                new BigDecimal("-5.0"),
                new BigDecimal("4.2"),
                expectedDecay,
                thetaProgressRatio,
                SignalSnapshot.ThetaState.PROFIT_BOOK,
                SignalSnapshot.VolumeState.CONFIRMED,
                false,
                "OK"
        );
    }

    /**
     * Builds a monitoring signal with {@link SignalSnapshot.VolumeState#ABSENT} —
     * used to trigger Stop 4 ({@code ZERO_BID} / liquidity absent) in
     * {@link RiskGuardService}, producing a {@link RiskGuardDecision#FORCE_EXIT}
     * when an active session is present.
     */
    private static SignalSnapshot absentVolumeSignal(
            String instrumentId,
            String optionType) {

        return new SignalSnapshot(
                T_MARKET_OPEN.plusSeconds(600),
                instrumentId,
                UNDERLYING,
                optionType,
                new BigDecimal("24800"),
                new BigDecimal("0.08"),
                null,
                null,
                new BigDecimal("10.0"),
                new BigDecimal("-5.0"),
                new BigDecimal("4.2"),
                BigDecimal.ZERO,          // expectedDecaySinceEntry — zero (adverse)
                new BigDecimal("0.20"),   // thetaProgressRatio — below booking threshold
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.ABSENT,  // triggers FORCE_EXIT with active session
                false,
                "ZERO_BID"
        );
    }

    private static DecisionAuditWriter collectingAuditWriter(List<DecisionCommand> target) {
        return new DecisionAuditWriter() {
            @Override
            public void write(Connection conn, DecisionCommand cmd,
                              DecisionAuditWriter.AuditContext ctx)
                    throws java.sql.SQLException {
                target.add(cmd);
            }
        };
    }

    // =========================================================================
    // No-op JDBC stubs — identical pattern to Phase5EndToEndTest
    // =========================================================================

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
                 "setBoolean", "setNull", "setInt", "setLong" -> null;
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

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class || returnType == long.class
                || returnType == short.class || returnType == byte.class) return 0;
        if (returnType == double.class || returnType == float.class) return 0.0;
        return null;
    }
}
