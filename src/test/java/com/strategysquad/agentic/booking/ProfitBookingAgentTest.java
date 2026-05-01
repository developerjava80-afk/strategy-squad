package com.strategysquad.agentic.booking;

import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.PositionSessionActionService;
import com.strategysquad.research.PositionSessionSnapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProfitBookingAgent}.
 *
 * <p>All tests are pure in-memory: no database, no HTTP, no file I/O.
 * Exit prices are injected via a lambda {@link ProfitBookingAgent.CurrentPriceSource}.
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>Partial booking triggered — ratio 0.80 with positive PnL and ALLOW risk.</li>
 *   <li>Full booking triggered — ratio 0.92 with positive PnL and ALLOW risk;
 *       signal is RESTART_SCAN and all legs are fully closed.</li>
 *   <li>No trigger — ratio 0.60 (below 0.75 threshold).</li>
 *   <li>No trigger — live_pnl &le; 0 (negative PnL).</li>
 *   <li>No trigger — risk is BLOCK_NEW_ENTRY.</li>
 *   <li>No trigger — risk is FORCE_REDUCE.</li>
 *   <li>No trigger — risk is FORCE_EXIT.</li>
 *   <li>No trigger — risk is HALT_SESSION.</li>
 *   <li>Booked PnL correct after partial booking.</li>
 *   <li>Booked PnL correct after full booking.</li>
 *   <li>tryBookFromSignals — derives ratio from SignalSnapshot.</li>
 *   <li>tryBookFromSignals — empty signal map returns noAction.</li>
 * </ol>
 */
class ProfitBookingAgentTest {

    private static final Instant BOOKING_TS = Instant.parse("2026-04-26T10:00:00Z");
    private static final String SESSION_ID = "test-session-001";

    /** Using real PositionSessionActionService — no DB required (pure in-memory logic). */
    private ProfitBookingAgent agent;

    @BeforeEach
    void setUp() {
        agent = new ProfitBookingAgent(new PositionSessionActionService());
    }

    // =========================================================================
    // Scenario 1 — Partial booking triggered
    // =========================================================================

    @Test
    void partialBooking_triggered_whenRatioAbovePartialThreshold() {
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65); // 1 NIFTY lot each

        // ratio=0.80 ∈ [0.75, 0.90) → partial booking; live PnL positive
        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.80, 500.0, RiskGuardDecision.ALLOW,
                instrumentId -> new BigDecimal("100.0"),  // exit price = 100 for all legs
                BOOKING_TS);

        assertTrue(result.booked(), "Partial booking must be triggered");
        assertEquals(ProfitBookingAgent.Signal.CONTINUE, result.signal(),
                "Partial booking must return CONTINUE signal");
        assertEquals(ProfitBookingAgent.AUDIT_REASON_PARTIAL, result.reasonCode(),
                "Partial booking must use partial reason code");
        assertNotNull(result.updatedSession(), "Updated session must not be null");
    }

    // =========================================================================
    // Scenario 2 — Full booking triggered
    // =========================================================================

    @Test
    void fullBooking_triggered_whenRatioAboveFullThreshold() {
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65);

        // ratio=0.92 >= 0.90 → full booking
        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.92, 800.0, RiskGuardDecision.ALLOW,
                instrumentId -> new BigDecimal("80.0"),
                BOOKING_TS);

        assertTrue(result.booked(), "Full booking must be triggered");
        assertEquals(ProfitBookingAgent.Signal.RESTART_SCAN, result.signal(),
                "Full booking must return RESTART_SCAN signal");
        assertEquals(ProfitBookingAgent.AUDIT_REASON_FULL, result.reasonCode(),
                "Full booking must use full reason code");

        // All legs must be fully closed after full booking
        PositionSessionSnapshot updated = result.updatedSession();
        for (PositionSessionSnapshot.PositionLegSnapshot leg : updated.legs()) {
            assertEquals(0, leg.openQuantity(),
                    "All leg openQuantity must be 0 after full booking — leg: " + leg.legId());
            assertEquals("CLOSED", leg.status(),
                    "All legs must be CLOSED after full booking — leg: " + leg.legId());
        }
        assertEquals("CLOSED", updated.status(), "Session must be CLOSED after full booking");
    }

    // =========================================================================
    // Scenario 3 — No trigger: ratio below threshold
    // =========================================================================

    @Test
    void noBooking_whenRatioBelowPartialThreshold() {
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65);

        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.60, 500.0, RiskGuardDecision.ALLOW,
                instrumentId -> new BigDecimal("100.0"),
                BOOKING_TS);

        assertFalse(result.booked(), "No booking must occur when ratio < 0.75");
        assertSame(session, result.updatedSession(), "Session must be unchanged when no booking");
        assertEquals(ProfitBookingAgent.Signal.CONTINUE, result.signal());
    }

    // =========================================================================
    // Scenario 4 — No trigger: negative live PnL
    // =========================================================================

    @Test
    void noBooking_whenLivePnlIsNegative() {
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65);

        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.80, -200.0, RiskGuardDecision.ALLOW,
                instrumentId -> new BigDecimal("100.0"),
                BOOKING_TS);

        assertFalse(result.booked(), "No booking must occur when live PnL is negative");
    }

    @Test
    void noBooking_whenLivePnlIsZero() {
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65);

        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.80, 0.0, RiskGuardDecision.ALLOW,
                instrumentId -> new BigDecimal("100.0"),
                BOOKING_TS);

        assertFalse(result.booked(), "No booking must occur when live PnL is zero");
    }

    // =========================================================================
    // Scenario 5–8 — No trigger: risk guard blocks
    // =========================================================================

    @Test
    void noBooking_whenRiskIsBlockNewEntry() {
        assertNoBookingForRisk(RiskGuardDecision.BLOCK_NEW_ENTRY);
    }

    @Test
    void noBooking_whenRiskIsForceReduce() {
        assertNoBookingForRisk(RiskGuardDecision.FORCE_REDUCE);
    }

    @Test
    void noBooking_whenRiskIsForceExit() {
        assertNoBookingForRisk(RiskGuardDecision.FORCE_EXIT);
    }

    @Test
    void noBooking_whenRiskIsHaltSession() {
        assertNoBookingForRisk(RiskGuardDecision.HALT_SESSION);
    }

    @Test
    void bookingAllowed_whenRiskIsWarn() {
        // WARN must NOT block booking — booking is still permitted on WARN
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65);
        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.80, 500.0, RiskGuardDecision.WARN,
                instrumentId -> new BigDecimal("100.0"),
                BOOKING_TS);

        assertTrue(result.booked(), "Booking must proceed when risk is WARN");
    }

    // =========================================================================
    // Scenario 9 — Booked PnL correct after partial booking
    // =========================================================================

    @Test
    void bookedPnl_correctAfterPartialBooking() {
        // CE leg: entry=150, PE leg: entry=120 — each 1 NIFTY lot (65 units)
        PositionSessionSnapshot session = sessionWithEntryPrices(SESSION_ID,
                new BigDecimal("150.0"), 65,
                new BigDecimal("120.0"), 65);

        // Partial booking: exits 1 lot (65 units) per leg
        // CE exit=100 → PnL = (150-100)*65 = 3250
        // PE exit=90  → PnL = (120-90)*65  = 1950
        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.80, 2000.0, RiskGuardDecision.ALLOW,
                instrumentId -> instrumentId.contains("CE")
                        ? new BigDecimal("100.0")
                        : new BigDecimal("90.0"),
                BOOKING_TS);

        assertTrue(result.booked(), "Partial booking must be triggered");

        PositionSessionSnapshot updated = result.updatedSession();
        BigDecimal totalBooked = updated.legs().stream()
                .map(leg -> leg.bookedPnl() == null ? BigDecimal.ZERO : leg.bookedPnl())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Expected: 3250 + 1950 = 5200
        assertEquals(0, new BigDecimal("5200").compareTo(totalBooked),
                "Total booked PnL must equal sum of (entry-exit)*qty per leg: got " + totalBooked);

        // Legs must still have open quantity (partial booking only reduces by 1 lot)
        // With 1 lot (65 units) and reduction of 65 units → openQuantity = 0 (1 lot fully reduced)
        // This is correct for a 1-lot position: partial = exit 1 lot = all
        // The booking is still classified as PARTIAL because ratio=0.80 < fullThreshold=0.90
        assertEquals(ProfitBookingAgent.AUDIT_REASON_PARTIAL, result.reasonCode());
    }

    // =========================================================================
    // Scenario 10 — Booked PnL correct after full booking
    // =========================================================================

    @Test
    void bookedPnl_correctAfterFullBooking() {
        // CE: entry=160, PE: entry=140; 2 NIFTY lots each (130 units)
        PositionSessionSnapshot session = sessionWithEntryPrices(SESSION_ID,
                new BigDecimal("160.0"), 130,
                new BigDecimal("140.0"), 130);

        // Full booking: exits all 130 units per leg
        // CE exit=110 → PnL = (160-110)*130 = 6500
        // PE exit=95  → PnL = (140-95)*130  = 5850
        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.92, 5000.0, RiskGuardDecision.ALLOW,
                instrumentId -> instrumentId.contains("CE")
                        ? new BigDecimal("110.0")
                        : new BigDecimal("95.0"),
                BOOKING_TS);

        assertTrue(result.booked(), "Full booking must be triggered");
        assertEquals(ProfitBookingAgent.Signal.RESTART_SCAN, result.signal());

        PositionSessionSnapshot updated = result.updatedSession();
        BigDecimal totalBooked = updated.legs().stream()
                .map(leg -> leg.bookedPnl() == null ? BigDecimal.ZERO : leg.bookedPnl())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Expected: 6500 + 5850 = 12350
        assertEquals(0, new BigDecimal("12350").compareTo(totalBooked),
                "Total booked PnL must equal sum of (entry-exit)*qty for full booking: got " + totalBooked);
    }

    // =========================================================================
    // Scenario 11 — tryBookFromSignals derives ratio from SignalSnapshot
    // =========================================================================

    @Test
    void tryBookFromSignals_derivesRatioAndTriggers() {
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65);

        String ceId = "INS_NIFTY_20260430_24800_CE";
        String peId = "INS_NIFTY_20260430_24800_PE";

        // Signal has thetaProgressRatio=0.88 → partial booking
        Map<String, SignalSnapshot> signals = Map.of(
                ceId, signalWithRatio(ceId, "CE", new BigDecimal("0.88")),
                peId, signalWithRatio(peId, "PE", new BigDecimal("0.85"))
        );

        // Override session leg instrumentIds to match the signal keys
        PositionSessionSnapshot patchedSession = sessionWithInstrumentIds(SESSION_ID,
                ceId, 65, peId, 65,
                new BigDecimal("150.0"), new BigDecimal("120.0"));

        ProfitBookingAgent.BookingResult result = agent.tryBookFromSignals(
                patchedSession, signals, 500.0, RiskGuardDecision.ALLOW,
                instrumentId -> new BigDecimal("100.0"),
                BOOKING_TS);

        assertTrue(result.booked(), "Booking must be triggered from signal ratio");
        assertEquals(ProfitBookingAgent.Signal.CONTINUE, result.signal(),
                "Ratio 0.88 should trigger partial booking (CONTINUE)");
    }

    // =========================================================================
    // Scenario 12 — tryBookFromSignals: empty signal map → no action
    // =========================================================================

    @Test
    void tryBookFromSignals_emptySignalMap_returnsNoAction() {
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65);

        ProfitBookingAgent.BookingResult result = agent.tryBookFromSignals(
                session, Map.of(), 500.0, RiskGuardDecision.ALLOW,
                instrumentId -> new BigDecimal("100.0"),
                BOOKING_TS);

        assertFalse(result.booked(), "No booking when signal map is empty");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void assertNoBookingForRisk(RiskGuardDecision riskDecision) {
        PositionSessionSnapshot session = twoLegSession(SESSION_ID, 65, 65);
        ProfitBookingAgent.BookingResult result = agent.tryBook(
                session, 0.85, 500.0, riskDecision,
                instrumentId -> new BigDecimal("100.0"),
                BOOKING_TS);

        assertFalse(result.booked(),
                "No booking must occur when risk=" + riskDecision);
        assertEquals(ProfitBookingAgent.Signal.CONTINUE, result.signal());
    }

    /**
     * Builds an active two-leg session (CE + PE) with NIFTY as underlying.
     * Entry prices are 150 (CE) and 120 (PE) by default.
     */
    private static PositionSessionSnapshot twoLegSession(
            String sessionId, int ceQty, int peQty) {

        return sessionWithEntryPrices(sessionId,
                new BigDecimal("150.0"), ceQty,
                new BigDecimal("120.0"), peQty);
    }

    private static PositionSessionSnapshot sessionWithEntryPrices(
            String sessionId,
            BigDecimal ceEntryPrice, int ceQty,
            BigDecimal peEntryPrice, int peQty) {

        Instant now = BOOKING_TS;
        PositionSessionSnapshot.PositionLegSnapshot ceLeg =
                new PositionSessionSnapshot.PositionLegSnapshot(
                        sessionId + "-CE",
                        "Short CE 24800",
                        "CE", "SHORT",
                        new BigDecimal("24800"),
                        "2026-04-30",
                        "NIFTY26APR24800CE",
                        "INS_NIFTY_20260430_24800_CE",
                        ceEntryPrice,
                        ceQty, ceQty,
                        BigDecimal.ZERO,
                        "OPEN",
                        now, now
                );

        PositionSessionSnapshot.PositionLegSnapshot peLeg =
                new PositionSessionSnapshot.PositionLegSnapshot(
                        sessionId + "-PE",
                        "Short PE 24800",
                        "PE", "SHORT",
                        new BigDecimal("24800"),
                        "2026-04-30",
                        "NIFTY26APR24800PE",
                        "INS_NIFTY_20260430_24800_PE",
                        peEntryPrice,
                        peQty, peQty,
                        BigDecimal.ZERO,
                        "OPEN",
                        now, now
                );

        return new PositionSessionSnapshot(
                sessionId, "SIMULATION",
                "Short Straddle 24800", "SHORT", "NIFTY", "WEEKLY", "DAY",
                4, new BigDecimal("24750.00"), 1,
                now, now, null,
                "ACTIVE",
                List.of(ceLeg, peLeg),
                List.of()
        );
    }

    /**
     * Builds a session where leg instrumentIds match the signal map keys.
     */
    private static PositionSessionSnapshot sessionWithInstrumentIds(
            String sessionId,
            String ceInstrumentId, int ceQty,
            String peInstrumentId, int peQty,
            BigDecimal ceEntryPrice, BigDecimal peEntryPrice) {

        Instant now = BOOKING_TS;
        PositionSessionSnapshot.PositionLegSnapshot ceLeg =
                new PositionSessionSnapshot.PositionLegSnapshot(
                        sessionId + "-CE",
                        "Short CE",
                        "CE", "SHORT",
                        new BigDecimal("24800"),
                        "2026-04-30",
                        ceInstrumentId,
                        ceInstrumentId,
                        ceEntryPrice,
                        ceQty, ceQty,
                        BigDecimal.ZERO,
                        "OPEN",
                        now, now
                );

        PositionSessionSnapshot.PositionLegSnapshot peLeg =
                new PositionSessionSnapshot.PositionLegSnapshot(
                        sessionId + "-PE",
                        "Short PE",
                        "PE", "SHORT",
                        new BigDecimal("24800"),
                        "2026-04-30",
                        peInstrumentId,
                        peInstrumentId,
                        peEntryPrice,
                        peQty, peQty,
                        BigDecimal.ZERO,
                        "OPEN",
                        now, now
                );

        return new PositionSessionSnapshot(
                sessionId, "SIMULATION",
                "Short Straddle", "SHORT", "NIFTY", "WEEKLY", "DAY",
                4, new BigDecimal("24750.00"), 1,
                now, now, null,
                "ACTIVE",
                List.of(ceLeg, peLeg),
                List.of()
        );
    }

    private static SignalSnapshot signalWithRatio(
            String instrumentId, String optionType, BigDecimal thetaProgressRatio) {

        return new SignalSnapshot(
                BOOKING_TS,
                instrumentId,
                "NIFTY",
                optionType,
                new BigDecimal("24800"),
                new BigDecimal("-0.08"),
                null, null,
                new BigDecimal("10.0"),
                new BigDecimal("-0.8"),
                new BigDecimal("0.5"),
                new BigDecimal("120.0"),
                thetaProgressRatio,
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.CONFIRMED,
                false,
                "OK"
        );
    }
}
