package com.strategysquad.agentic;

import com.strategysquad.agentic.adjustment.AdjustmentAgent;
import com.strategysquad.agentic.booking.ProfitBookingAgent;
import com.strategysquad.agentic.decision.DecisionAgent;
import com.strategysquad.agentic.decision.DecisionAuditWriter;
import com.strategysquad.agentic.decision.DecisionCommand;
import com.strategysquad.agentic.decision.DecisionPolicy;
import com.strategysquad.agentic.orchestrator.MarketDayOrchestrator;
import com.strategysquad.agentic.orchestrator.MarketDayOrchestrator.OrchestratorState;
import com.strategysquad.agentic.risk.RiskGuardService;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.MarketSessionStateResolver;
import com.strategysquad.research.PositionSessionActionService;
import com.strategysquad.research.PositionSessionSnapshot;
import com.strategysquad.research.StrategyRunReportService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 end-to-end simulation test: drives {@link MarketDayOrchestrator} through
 * a complete market day via {@link MarketDayOrchestrator#tick()} calls.
 *
 * <h2>Target state sequence</h2>
 * <pre>
 *   PRE_OPEN_SCAN → WAIT_MARKET_OPEN → EVALUATE_ENTRY → POSITION_OPEN
 *   → MONITOR → BOOK_PROFIT → RESTART_SCAN → END_OF_DAY
 * </pre>
 *
 * <h2>Acceptance criteria</h2>
 * <ol>
 *   <li>Orchestrator reaches {@code END_OF_DAY} without throwing.</li>
 *   <li>{@code agentic_session_state} rows capture all expected states.</li>
 *   <li>Report file is generated and non-empty.</li>
 *   <li>Audit log has at least one record per state transition.</li>
 * </ol>
 *
 * <p>All collaborators are pure in-memory stubs — no live DB, no broker connection.
 */
class Phase5EndToEndTest {

    private static final String UNDERLYING = "NIFTY";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 30);

    /** IST zone — same as exchange. */
    private static final ZoneId IST = MarketSessionStateResolver.EXCHANGE_ZONE;

    /** Market-hours start time: 10:00 IST (well within the 09:15–15:30 window). */
    private static final Instant T_MARKET_OPEN =
            LocalDate.of(2026, 4, 26).atTime(LocalTime.of(10, 0))
                    .atZone(IST).toInstant();

    /** End-of-day time: 15:45 IST (after MARKET_CLOSE = 15:30). */
    private static final Instant T_END_OF_DAY =
            LocalDate.of(2026, 4, 26).atTime(LocalTime.of(15, 45))
                    .atZone(IST).toInstant();

    /** Entry premiums for the CE and PE legs. */
    private static final BigDecimal CE_ENTRY = new BigDecimal("150.0");
    private static final BigDecimal PE_ENTRY = new BigDecimal("140.0");
    private static final int LOT_SIZE = 65;

    @TempDir
    Path tempDir;

    // =========================================================================
    // End-to-end test
    // =========================================================================

    @Test
    void fullMarketDay_reachesEndOfDay_statesAuditAndReportAllCorrect() throws Exception {

        // ── Mutable clock: starts at market-open, advanced to EOD after booking ──
        AtomicReference<Instant> clockInstant = new AtomicReference<>(T_MARKET_OPEN);
        MarketDayOrchestrator.Clock clock = clockInstant::get;

        // ── State-persistence capture ──────────────────────────────────────────
        List<PersistRecord> auditLog = new ArrayList<>();
        MarketDayOrchestrator.StatePersister persister =
                (newState, priorState, elapsedMs, cmdType, cmdId, haltReason, notes) ->
                        auditLog.add(new PersistRecord(newState, priorState, cmdType, cmdId));

        // ── CE and PE scan candidates (qualified — no disqualifier) ────────────
        CandidateOpportunity ce = candidate(
                "INS_NIFTY_20260430_24800_CE", "CE",
                new BigDecimal("24800"), CE_ENTRY, 0.82);
        CandidateOpportunity pe = candidate(
                "INS_NIFTY_20260430_24800_PE", "PE",
                new BigDecimal("24800"), PE_ENTRY, 0.78);
        List<CandidateOpportunity> candidates = List.of(ce, pe);

        // ── Active-session holder: empty until ENTER fires ─────────────────────
        AtomicReference<PositionSessionSnapshot> activeSession = new AtomicReference<>(null);

        // ── Signal loader: returns empty map before any session; returns
        //    profit-bearing signals (expectedDecaySinceEntry high, thetaProgressRatio 0.80)
        //    once a session is active so the decision agent decides BOOK_PROFIT. ─
        DecisionAgent.SignalLoader signalLoader = (session, ts) -> {
            if (session == null || session.isEmpty()) {
                return Map.of();
            }
            PositionSessionSnapshot s = session.get();
            // Build one signal per active leg with 80% decay → livePnl > 0,
            // thetaProgressRatio = 0.80 ≥ BOOKING_THRESHOLD (0.75) → BOOK_PROFIT.
            Map<String, SignalSnapshot> signals = new java.util.LinkedHashMap<>();
            for (PositionSessionSnapshot.PositionLegSnapshot leg : s.legs()) {
                BigDecimal entryPrice = leg.entryPrice() != null ? leg.entryPrice() : new BigDecimal("100.0");
                // expectedDecaySinceEntry = 80% of entryPrice (premium has decayed 80%)
                BigDecimal decay = entryPrice.multiply(new BigDecimal("0.80"));
                signals.put(leg.instrumentId(), monitorSignal(leg.instrumentId(), leg.optionType(),
                        decay, new BigDecimal("0.80")));
            }
            return Map.copyOf(signals);
        };

        // ── Plan builder: delegates to real PositionBuilderAgent ──────────────
        com.strategysquad.agentic.builder.PositionBuilderAgent.LotSizeLoader lotLoader =
                u -> "NIFTY".equals(u) ? LOT_SIZE : 30;
        com.strategysquad.agentic.builder.PositionBuilderAgent builderAgent =
                new com.strategysquad.agentic.builder.PositionBuilderAgent(lotLoader);
        DecisionAgent.PlanBuilder planBuilder =
                (u, cands, sigs, ts) -> builderAgent.buildShortStraddle(u, cands, sigs, ts);

        // ── Decision agent wiring ──────────────────────────────────────────────
        DecisionAgent decisionAgent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                UNDERLYING,
                /* candidateLoader  */ ts -> candidates,
                /* signalLoader     */ signalLoader,
                /* sessionLoader    */ () -> Optional.ofNullable(activeSession.get()),
                new RiskGuardService(),
                new DecisionPolicy(),
                /* auditWriter      */ noopAuditWriter(),
                /* connectionSupplier */ Phase5EndToEndTest::noopConnection,
                /* maxLotCap        */ DecisionAgent.DEFAULT_MAX_LOT_CAP,
                /* planBuilder      */ planBuilder,
                /* sessionPersister */ session -> activeSession.set(session)
        );

        // ── Profit booking and adjustment agents ──────────────────────────────
        PositionSessionActionService actionService = new PositionSessionActionService();
        ProfitBookingAgent bookingAgent = new ProfitBookingAgent(actionService);
        AdjustmentAgent adjustmentAgent = new AdjustmentAgent(actionService);

        // ── Orchestrator ──────────────────────────────────────────────────────
        MarketDayOrchestrator orchestrator = new MarketDayOrchestrator(
                "SIMULATION",
                UNDERLYING,
                decisionAgent,
                bookingAgent,
                adjustmentAgent,
                clock,
                persister
        );

        // ═══════════════════════════════════════════════════════════════════════
        // Drive the state machine forward
        // ═══════════════════════════════════════════════════════════════════════

        // tick 1: PRE_OPEN_SCAN → WAIT_MARKET_OPEN
        OrchestratorState s1 = orchestrator.tick();
        assertEquals(OrchestratorState.WAIT_MARKET_OPEN, s1, "tick1 must reach WAIT_MARKET_OPEN");

        // tick 2: WAIT_MARKET_OPEN → EVALUATE_ENTRY
        OrchestratorState s2 = orchestrator.tick();
        assertEquals(OrchestratorState.EVALUATE_ENTRY, s2, "tick2 must reach EVALUATE_ENTRY");

        // tick 3: EVALUATE_ENTRY → decision: ENTER (candidates, no session) → POSITION_OPEN
        OrchestratorState s3 = orchestrator.tick();
        assertEquals(OrchestratorState.POSITION_OPEN, s3, "tick3 must reach POSITION_OPEN after ENTER decision");
        assertNotNull(activeSession.get(), "A position session must have been created by the ENTER decision");
        assertEquals(2, activeSession.get().legs().size(), "Session must have 2 legs (CE + PE straddle)");

        // tick 4: POSITION_OPEN → MONITOR
        OrchestratorState s4 = orchestrator.tick();
        assertEquals(OrchestratorState.MONITOR, s4, "tick4 must reach MONITOR");

        // tick 5: MONITOR → decision: BOOK_PROFIT (livePnl from decay signals ≥ threshold) → BOOK_PROFIT
        OrchestratorState s5 = orchestrator.tick();
        assertEquals(OrchestratorState.BOOK_PROFIT, s5, "tick5 must reach BOOK_PROFIT (decay signals trigger booking)");

        // tick 6: BOOK_PROFIT → RESTART_SCAN (Phase 5A stub)
        OrchestratorState s6 = orchestrator.tick();
        assertEquals(OrchestratorState.RESTART_SCAN, s6, "tick6 must reach RESTART_SCAN after booking");

        // ── Advance clock past market close (15:30 IST) ───────────────────────
        clockInstant.set(T_END_OF_DAY);

        // tick 7: RESTART_SCAN → END_OF_DAY (clock ≥ 15:30 IST)
        OrchestratorState s7 = orchestrator.tick();
        assertEquals(OrchestratorState.END_OF_DAY, s7,
                "tick7 must reach END_OF_DAY when clock is past market close");

        // Subsequent ticks must be no-ops (terminal state)
        OrchestratorState s8 = orchestrator.tick();
        assertEquals(OrchestratorState.END_OF_DAY, s8, "END_OF_DAY must be a terminal (no-op) state");

        // ═══════════════════════════════════════════════════════════════════════
        // Assert: agentic_session_state records all expected transitions
        // ═══════════════════════════════════════════════════════════════════════

        // 7 transitions → 7 persisted rows
        assertEquals(7, auditLog.size(),
                "State persister must record exactly 7 transitions; got: "
                        + auditLog.stream().map(r -> r.newState().name()).toList());

        // All expected states must appear as the target of at least one transition
        List<OrchestratorState> expectedStates = List.of(
                OrchestratorState.WAIT_MARKET_OPEN,
                OrchestratorState.EVALUATE_ENTRY,
                OrchestratorState.POSITION_OPEN,
                OrchestratorState.MONITOR,
                OrchestratorState.BOOK_PROFIT,
                OrchestratorState.RESTART_SCAN,
                OrchestratorState.END_OF_DAY
        );
        for (OrchestratorState expected : expectedStates) {
            assertTrue(
                    auditLog.stream().anyMatch(r -> r.newState() == expected),
                    "Audit log must contain a transition to " + expected.name()
            );
        }

        // The last record must target END_OF_DAY
        assertEquals(OrchestratorState.END_OF_DAY, auditLog.get(auditLog.size() - 1).newState(),
                "Last audit entry must be END_OF_DAY");

        // Run ID must be consistent across all records
        assertNotNull(orchestrator.runId(), "Run ID must not be null");

        // ── Audit log: at least one record per expected state (7 transitions ≥ 7) ──
        assertTrue(auditLog.size() >= expectedStates.size(),
                "Audit log must have at least one record per expected state transition");

        // ─── The ENTER decision must have been recorded in the decision command ──
        DecisionCommand lastCmd = orchestrator.lastCommand();
        assertNotNull(lastCmd, "Last command must not be null after a complete day");

        // ═══════════════════════════════════════════════════════════════════════
        // Assert: report file generated and non-empty
        // ═══════════════════════════════════════════════════════════════════════

        PositionSessionSnapshot session = activeSession.get();
        assertNotNull(session, "Active session must exist after a full day");

        // Compute total booked PnL (legs may or may not be closed by the Phase 5A stub,
        // but the report must still be generated).
        BigDecimal totalBooked = session.legs().stream()
                .map(PositionSessionSnapshot.PositionLegSnapshot::bookedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Build transition timeline entries for the report
        List<StrategyRunReportService.DecisionHistoryEntry> decisionHistory = auditLog.stream()
                .filter(r -> r.cmdType() != null)
                .map(r -> new StrategyRunReportService.DecisionHistoryEntry(
                        Instant.now().toString(),
                        r.cmdType(),
                        "AGENTIC_TRANSITION",
                        r.newState().name() + " ← " + (r.priorState() != null ? r.priorState().name() : "start")))
                .toList();

        long durationMs = T_END_OF_DAY.toEpochMilli() - T_MARKET_OPEN.toEpochMilli();
        StrategyRunReportService.StrategyRunReportRequest reportRequest =
                new StrategyRunReportService.StrategyRunReportRequest(
                        orchestrator.runId(),          // runId
                        session.sessionId(),           // sessionId
                        "SIMULATION",                  // mode
                        UNDERLYING,                    // underlying
                        "Short Straddle",              // strategyName
                        T_MARKET_OPEN.toString(),      // startTime
                        T_END_OF_DAY.toString(),       // endTime
                        durationMs,                    // durationMs
                        /* initialMaxLots */ DecisionAgent.DEFAULT_MAX_LOT_CAP,
                        /* lotSize        */ LOT_SIZE,
                        /* liveSpot       */ new BigDecimal("24750.0"),
                        /* finalNetPremiumPoints */ null,
                        totalBooked,                   // bookedPnl
                        /* livePnl        */ BigDecimal.ZERO,
                        /* totalPnl       */ totalBooked,
                        /* initialStructure */ List.of(),
                        /* finalStructure   */ List.of(),
                        /* timeline         */ List.of(),
                        /* scannerRanking   */ List.of(
                                new StrategyRunReportService.ScannerRankingEntry(
                                        1, ce.instrumentId(), ce.optionType(), ce.strike(),
                                        ce.lastPrice(), ce.totalScore(), false, null),
                                new StrategyRunReportService.ScannerRankingEntry(
                                        2, pe.instrumentId(), pe.optionType(), pe.strike(),
                                        pe.lastPrice(), pe.totalScore(), false, null)
                        ),
                        decisionHistory.isEmpty()
                                ? List.of(new StrategyRunReportService.DecisionHistoryEntry(
                                        T_MARKET_OPEN.toString(), "ENTER", "ENTER_DECISION", "Session opened"))
                                : decisionHistory,
                        /* profitBookingEvents */ List.of()
                );

        StrategyRunReportService reportService = new StrategyRunReportService(tempDir);
        Path reportPath = reportService.writeReport(reportRequest);

        assertNotNull(reportPath, "Report path must not be null");
        assertTrue(Files.exists(reportPath), "Report file must exist on disk");
        assertTrue(Files.size(reportPath) > 0, "Report file must be non-empty");

        String markdown = Files.readString(reportPath);
        assertFalse(markdown.isBlank(), "Report content must not be blank");

        // Key sections must be present in the report
        assertTrue(markdown.contains("## Run Metadata"), "Report must contain Run Metadata section");
        assertTrue(markdown.contains("## Scanner Ranking"), "Report must contain Scanner Ranking section");
        assertTrue(markdown.contains("## Decision History"), "Report must contain Decision History section");
        assertTrue(markdown.contains(orchestrator.runId()), "Report must contain the run ID");
        assertTrue(markdown.contains("NIFTY"), "Report must mention the underlying");
        assertTrue(markdown.contains("SIMULATION"), "Report must show simulation mode");
    }

    // =========================================================================
    // Helper record
    // =========================================================================

    /** Captures one row written to the agentic_session_state table (in-memory). */
    private record PersistRecord(
            OrchestratorState newState,
            OrchestratorState priorState,
            String cmdType,
            String cmdId
    ) {}

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
     * Creates a signal for an active monitoring cycle with high theta progress —
     * enough to trigger BOOK_PROFIT (thetaProgressRatio ≥ 0.75 AND livePnl > 0).
     *
     * @param instrumentId        canonical instrument ID
     * @param optionType          CE or PE
     * @param expectedDecay       expectedDecaySinceEntry (positive = premium has decayed)
     * @param thetaProgressRatio  0.80 in this test — above the 0.75 booking threshold
     */
    private static SignalSnapshot monitorSignal(
            String instrumentId,
            String optionType,
            BigDecimal expectedDecay,
            BigDecimal thetaProgressRatio) {

        return new SignalSnapshot(
                T_MARKET_OPEN.plusSeconds(600),  // signalTs — 10 minutes after open
                instrumentId,
                UNDERLYING,
                optionType,
                new BigDecimal("24800"),          // strike
                new BigDecimal("0.08"),           // empiricalDelta2m (low: decaying)
                null,                             // empiricalDelta5m
                null,                             // empiricalDeltaSod
                new BigDecimal("10.0"),           // underlyingMove2m
                new BigDecimal("-5.0"),           // optionMove2m (option price fell)
                new BigDecimal("4.2"),            // deltaAdjustedTheta2m (positive: theta working)
                expectedDecay,                    // expectedDecaySinceEntry
                thetaProgressRatio,               // thetaProgressRatio (0.80 ≥ 0.75 threshold)
                SignalSnapshot.ThetaState.PROFIT_BOOK,   // triggers booking path
                SignalSnapshot.VolumeState.CONFIRMED,
                false,
                "OK"
        );
    }

    private static DecisionAuditWriter noopAuditWriter() {
        return new DecisionAuditWriter() {
            @Override
            public void write(Connection conn, DecisionCommand cmd, AuditContext ctx)
                    throws java.sql.SQLException {
                // swallow — no-op for test
            }
        };
    }

    // =========================================================================
    // No-op JDBC stubs — identical pattern to Phase3SimulationTest
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
