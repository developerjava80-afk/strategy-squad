package com.strategysquad.agentic;

import com.strategysquad.agentic.booking.ProfitBookingAgent;
import com.strategysquad.agentic.builder.PositionBuilderAgent;
import com.strategysquad.agentic.decision.DecisionAgent;
import com.strategysquad.agentic.decision.DecisionAuditWriter;
import com.strategysquad.agentic.decision.DecisionCommand;
import com.strategysquad.agentic.decision.DecisionPolicy;
import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.risk.RiskGuardService;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 end-to-end simulation test: full agentic loop
 * scan → ENTER → profit booking (FULL) → RESTART_SCAN → report.
 *
 * <p>All collaborators are pure in-memory stubs — no live DB, no file I/O beyond
 * the report file written via {@link StrategyRunReportService}.
 */
class Phase3SimulationTest {

    private static final String UNDERLYING = "NIFTY";
    private static final Instant SCAN_TS = Instant.parse("2026-04-26T09:30:00Z");
    private static final Instant BOOKING_TS = Instant.parse("2026-04-26T10:45:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 30);

    @TempDir
    Path tempDir;

    // =========================================================================
    // Full loop test
    // =========================================================================

    @Test
    void fullLoop_scan_enter_fullBook_restartScan_reportContainsAllThreeSections() throws Exception {

        // ── 1. Candidates: qualified CE and PE at same strike (short straddle) ──
        CandidateOpportunity ce = candidate(
                "INS_NIFTY_20260430_24800_CE", "CE",
                new BigDecimal("24800"), new BigDecimal("150.0"), 0.82);
        CandidateOpportunity pe = candidate(
                "INS_NIFTY_20260430_24800_PE", "PE",
                new BigDecimal("24800"), new BigDecimal("140.0"), 0.78);

        List<CandidateOpportunity> candidates = List.of(ce, pe);

        // ── 2. Signals at entry time: small delta for both legs ────────────────
        Map<String, SignalSnapshot> entrySignals = Map.of(
                "INS_NIFTY_20260430_24800_CE",
                signal("INS_NIFTY_20260430_24800_CE", "CE", "0.08",
                        new BigDecimal("0.50")),
                "INS_NIFTY_20260430_24800_PE",
                signal("INS_NIFTY_20260430_24800_PE", "PE", "-0.07",
                        new BigDecimal("0.45"))
        );

        // ── 3. Session capture ─────────────────────────────────────────────────
        List<PositionSessionSnapshot> createdSessions = new ArrayList<>();

        // ── 4. Plan builder: backed by real PositionBuilderAgent (short straddle) ──
        PositionBuilderAgent.LotSizeLoader lotLoader = u -> "NIFTY".equals(u) ? 65 : 30;
        PositionBuilderAgent builderAgent = new PositionBuilderAgent(lotLoader);
        DecisionAgent.PlanBuilder planBuilder =
                (u, cands, sigs, ts) -> builderAgent.buildShortStraddle(u, cands, sigs, ts);

        // ── 5. No-op audit writer ──────────────────────────────────────────────
        DecisionAuditWriter noopAudit = new DecisionAuditWriter() {
            @Override
            public void write(Connection conn, DecisionCommand cmd,
                              DecisionAuditWriter.AuditContext ctx) {
                // swallow — audit writes not needed for simulation test
            }
        };

        // ── 6. Decision Agent ──────────────────────────────────────────────────
        DecisionAgent agent = new DecisionAgent(
                DecisionCommand.Mode.SIMULATION,
                UNDERLYING,
                ts -> candidates,
                (session, ts) -> entrySignals,
                Optional::empty,       // no active session → triggers ENTER
                new RiskGuardService(),
                new DecisionPolicy(),
                noopAudit,
                Phase3SimulationTest::noopConnection,
                DecisionAgent.DEFAULT_MAX_LOT_CAP,
                planBuilder,
                createdSessions::add
        );

        // ════════════════════════════════════════════════════════════════════════
        // STEP 1 — Scan → ENTER → session created
        // ════════════════════════════════════════════════════════════════════════
        DecisionCommand enterCmd = agent.decide(SCAN_TS);

        assertEquals(DecisionCommand.CommandType.ENTER, enterCmd.commandType(),
                "Decision must be ENTER when qualified candidates exist and no session is active");
        assertEquals(1, createdSessions.size(),
                "Exactly one position session must be created on ENTER");

        PositionSessionSnapshot session = createdSessions.get(0);

        assertEquals(2, session.legs().size(),
                "Session must have exactly 2 legs");
        assertEquals("ACTIVE", session.status(),
                "New session must have status ACTIVE");

        long ceLegs = session.legs().stream().filter(l -> "CE".equals(l.optionType())).count();
        long peLegs = session.legs().stream().filter(l -> "PE".equals(l.optionType())).count();
        assertEquals(1, ceLegs, "Session must have exactly 1 CE leg");
        assertEquals(1, peLegs, "Session must have exactly 1 PE leg");

        // ════════════════════════════════════════════════════════════════════════
        // STEP 2 — Monitor cycle (skipped: represented by a ratio below threshold)
        // We go directly to profit booking in this simulation.
        // ════════════════════════════════════════════════════════════════════════

        // ════════════════════════════════════════════════════════════════════════
        // STEP 3 — Profit Booking (full: ratio = 0.92 >= 0.90)
        // Exit prices: CE → 100, PE → 90 (premium decayed from 150/140 → profit)
        // ════════════════════════════════════════════════════════════════════════
        Map<String, BigDecimal> exitPrices = Map.of(
                "INS_NIFTY_20260430_24800_CE", new BigDecimal("100.0"),
                "INS_NIFTY_20260430_24800_PE", new BigDecimal("90.0")
        );

        // livePnl at booking: (150-100)*65 + (140-90)*65 = 3250 + 3250 = 6500
        double livePnlAtBooking = 6500.0;
        double thetaProgressRatio = 0.92;

        ProfitBookingAgent bookingAgent =
                new ProfitBookingAgent(new PositionSessionActionService());

        ProfitBookingAgent.BookingResult bookingResult = bookingAgent.tryBook(
                session,
                thetaProgressRatio,
                livePnlAtBooking,
                RiskGuardDecision.ALLOW,
                id -> exitPrices.get(id),
                BOOKING_TS
        );

        // ── Booking must have fired ─────────────────────────────────────────────
        assertTrue(bookingResult.booked(),
                "Booking must fire when ratio >= full threshold and livePnl > 0");
        assertEquals(ProfitBookingAgent.Signal.RESTART_SCAN, bookingResult.signal(),
                "Full booking must return RESTART_SCAN signal");

        // ── Booked PnL must be positive ────────────────────────────────────────
        PositionSessionSnapshot bookedSession = bookingResult.updatedSession();
        BigDecimal totalBooked = bookedSession.legs().stream()
                .map(PositionSessionSnapshot.PositionLegSnapshot::bookedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertTrue(totalBooked.compareTo(BigDecimal.ZERO) > 0,
                "Total booked PnL must be positive after full booking; got: " + totalBooked);

        // All legs must be CLOSED after full booking
        long closedLegs = bookedSession.legs().stream()
                .filter(l -> "CLOSED".equalsIgnoreCase(l.status()))
                .count();
        assertEquals(2, closedLegs,
                "All 2 legs must be CLOSED after full booking");

        // ════════════════════════════════════════════════════════════════════════
        // STEP 4 — Generate report with all three new Phase 3 sections
        // ════════════════════════════════════════════════════════════════════════

        // Scanner ranking: top 2 qualified candidates
        List<StrategyRunReportService.ScannerRankingEntry> scannerRanking = List.of(
                new StrategyRunReportService.ScannerRankingEntry(
                        1, ce.instrumentId(), ce.optionType(), ce.strike(),
                        ce.lastPrice(), ce.totalScore(), false, null),
                new StrategyRunReportService.ScannerRankingEntry(
                        2, pe.instrumentId(), pe.optionType(), pe.strike(),
                        pe.lastPrice(), pe.totalScore(), false, null)
        );

        // Decision history: one ENTER command
        List<StrategyRunReportService.DecisionHistoryEntry> decisionHistory = List.of(
                new StrategyRunReportService.DecisionHistoryEntry(
                        SCAN_TS.toString(),
                        enterCmd.commandType().name(),
                        enterCmd.reasonCode(),
                        enterCmd.explanation())
        );

        // Profit booking events: one FULL booking
        List<StrategyRunReportService.ProfitBookingEvent> profitBookingEvents = List.of(
                new StrategyRunReportService.ProfitBookingEvent(
                        BOOKING_TS.toString(),
                        thetaProgressRatio,
                        livePnlAtBooking,
                        "FULL",
                        bookingResult.reasonCode(),
                        totalBooked)
        );

        StrategyRunReportService.StrategyRunReportRequest reportRequest =
                new StrategyRunReportService.StrategyRunReportRequest(
                        "sim-run-phase3",
                        session.sessionId(),
                        "SIMULATION",
                        UNDERLYING,
                        "Short Straddle",
                        SCAN_TS.toString(),
                        BOOKING_TS.toString(),
                        4_500_000L,
                        10,
                        65,
                        null,
                        null,
                        totalBooked,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        scannerRanking,
                        decisionHistory,
                        profitBookingEvents
                );

        StrategyRunReportService reportService = new StrategyRunReportService(tempDir);
        Path reportPath = reportService.writeReport(reportRequest);
        assertNotNull(reportPath, "Report file must be written");
        assertTrue(Files.exists(reportPath), "Report file must exist on disk");

        String markdown = Files.readString(reportPath);

        // ── All three Phase 3 sections must be present ─────────────────────────
        assertTrue(markdown.contains("## Scanner Ranking"),
                "Report must contain Scanner Ranking section");
        assertTrue(markdown.contains("## Decision History"),
                "Report must contain Decision History section");
        assertTrue(markdown.contains("## Profit Booking"),
                "Report must contain Profit Booking section");

        // ── Scanner ranking must show both candidate instruments ───────────────
        assertTrue(markdown.contains("INS_NIFTY_20260430_24800_CE"),
                "Scanner ranking must show CE instrument");
        assertTrue(markdown.contains("INS_NIFTY_20260430_24800_PE"),
                "Scanner ranking must show PE instrument");

        // ── Decision history must show ENTER command ───────────────────────────
        assertTrue(markdown.contains("ENTER"),
                "Decision history must show ENTER command");

        // ── Profit booking section must show FULL action and reason code ───────
        assertTrue(markdown.contains("FULL"),
                "Profit booking section must show FULL action");
        assertTrue(markdown.contains(ProfitBookingAgent.AUDIT_REASON_FULL),
                "Profit booking section must show full booking reason code");

        // ── Standard sections must also be present ─────────────────────────────
        assertTrue(markdown.contains("## Run Metadata"),
                "Report must contain Run Metadata section");
        assertTrue(markdown.contains("## PnL Summary"),
                "Report must contain PnL Summary section");
        assertTrue(markdown.contains("sim-run-phase3"),
                "Report must include the run ID");
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

    private static SignalSnapshot signal(
            String instrumentId,
            String optionType,
            String delta2m,
            BigDecimal thetaProgressRatio) {

        return new SignalSnapshot(
                SCAN_TS,
                instrumentId,
                UNDERLYING,
                optionType,
                new BigDecimal("24800"),
                new BigDecimal(delta2m),
                null,
                null,
                new BigDecimal("10.0"),
                new BigDecimal("0.8"),
                new BigDecimal("0.5"),
                thetaProgressRatio,
                null,
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.CONFIRMED,
                false,
                "OK"
        );
    }

    // =========================================================================
    // No-op JDBC stubs (identical to Phase3EnterSessionTest)
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

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class || returnType == long.class
                || returnType == short.class || returnType == byte.class) return 0;
        if (returnType == double.class || returnType == float.class) return 0.0;
        return null;
    }
}
