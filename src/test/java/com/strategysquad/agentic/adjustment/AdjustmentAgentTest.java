package com.strategysquad.agentic.adjustment;

import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.risk.RiskGuardSnapshot;
import com.strategysquad.research.PositionSessionActionService;
import com.strategysquad.research.PositionSessionSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AdjustmentAgent}.
 *
 * <p>All tests run in-memory — no QuestDB connection required.
 */
class AdjustmentAgentTest {

    private static final Instant BASE_TS = Instant.parse("2026-04-30T09:00:00Z");

    // Canonical instrument IDs used in the test fixture (Short Straddle 24800)
    private static final String CE_24800 = "INS_NIFTY_20260430_24800_CE";
    private static final String PE_24800 = "INS_NIFTY_20260430_24800_PE";

    // Shifted strike instrument IDs
    private static final String CE_25000 = "INS_NIFTY_20260430_25000_CE";
    private static final String PE_25000 = "INS_NIFTY_20260430_25000_PE";

    // =========================================================================
    // SHIFT_STRIKE tests
    // =========================================================================

    @Test
    void shiftStrike_success_exitOldLegAndEnterNew() {
        PositionSessionActionService actionService = new PositionSessionActionService();
        AdjustmentAgent agent = new AdjustmentAgent(actionService);

        PositionSessionSnapshot session = sessionWithEntryPrices("S1",
                new BigDecimal("150.0"), 65,
                new BigDecimal("120.0"), 65);

        AdjustmentAgent.NewLegDescriptor newCe = newLeg(
                CE_25000, "NIFTY26APR25000CE", "CE", "25000", 65);

        // Exit old CE at 80, enter new CE at 90
        AdjustmentAgent.CurrentPriceSource prices = instrumentId -> switch (instrumentId) {
            case CE_24800 -> new BigDecimal("80");
            case CE_25000 -> new BigDecimal("90");
            default -> throw new IllegalStateException("Unexpected: " + instrumentId);
        };

        AdjustmentAgent.AdjustmentResult result =
                agent.shiftStrike(session, CE_24800, newCe, riskAllow(), prices, BASE_TS);

        assertTrue(result.applied(), "SHIFT_STRIKE should be applied");
        assertEquals(AdjustmentAgent.AUDIT_ACTION_SHIFT_EXIT, result.reasonCode());

        PositionSessionSnapshot updated = result.updatedSession();

        // Old CE leg should still be present but fully closed
        PositionSessionSnapshot.PositionLegSnapshot oldCeLeg = findLegByInstrumentId(updated, CE_24800);
        assertNotNull(oldCeLeg, "Old CE leg must still be in the session snapshot");
        assertEquals(0, oldCeLeg.openQuantity(), "Old CE leg must have openQuantity=0 after shift");
        assertEquals("CLOSED", oldCeLeg.status());

        // New CE leg must have been added with full open quantity
        PositionSessionSnapshot.PositionLegSnapshot newCeLeg = findLegByInstrumentId(updated, CE_25000);
        assertNotNull(newCeLeg, "New CE leg must appear in the updated session");
        assertEquals(65, newCeLeg.openQuantity(), "New CE leg must have full openQuantity");

        // PE leg must be unchanged
        PositionSessionSnapshot.PositionLegSnapshot peLeg = findLegByInstrumentId(updated, PE_24800);
        assertNotNull(peLeg, "PE leg must still be present");
        assertEquals(65, peLeg.openQuantity(), "PE leg openQuantity must be unchanged");

        // Cooldown must be recorded
        assertEquals(BASE_TS, agent.lastShiftTs(), "lastShiftTs must be set after a successful shift");
    }

    @Test
    void shiftStrike_cooldown_blocksSecondShift() {
        PositionSessionActionService actionService = new PositionSessionActionService();
        // Use a short cooldown to make the test deterministic — 30 minutes is plenty
        AdjustmentAgent agent = new AdjustmentAgent(actionService, Duration.ofMinutes(30));

        PositionSessionSnapshot session = sessionWithEntryPrices("S2",
                new BigDecimal("150.0"), 65,
                new BigDecimal("120.0"), 65);

        AdjustmentAgent.NewLegDescriptor newCe = newLeg(
                CE_25000, "NIFTY26APR25000CE", "CE", "25000", 65);

        AdjustmentAgent.CurrentPriceSource prices = instrumentId -> new BigDecimal("100");

        Instant ts1 = BASE_TS;
        Instant ts2 = ts1.plusSeconds(60); // 1 minute later — still within 30-minute cooldown

        // First shift: must succeed
        AdjustmentAgent.AdjustmentResult first =
                agent.shiftStrike(session, CE_24800, newCe, riskAllow(), prices, ts1);
        assertTrue(first.applied(), "First SHIFT_STRIKE should succeed");

        // Second shift on the same session — blocked by cooldown
        AdjustmentAgent.NewLegDescriptor newPe = newLeg(
                PE_25000, "NIFTY26APR25000PE", "PE", "25000", 65);

        AdjustmentAgent.AdjustmentResult second =
                agent.shiftStrike(session, PE_24800, newPe, riskAllow(), prices, ts2);

        assertFalse(second.applied(), "Second SHIFT_STRIKE should be blocked by cooldown");
        assertEquals("COOLDOWN_ACTIVE", second.reasonCode(),
                "Reason code must be COOLDOWN_ACTIVE when blocked by cooldown");
    }

    @Test
    void shiftStrike_riskBlocks_forceReduceBlocksShift() {
        PositionSessionActionService actionService = new PositionSessionActionService();
        AdjustmentAgent agent = new AdjustmentAgent(actionService);

        PositionSessionSnapshot session = sessionWithEntryPrices("S3",
                new BigDecimal("150.0"), 65,
                new BigDecimal("120.0"), 65);

        AdjustmentAgent.NewLegDescriptor newCe = newLeg(
                CE_25000, "NIFTY26APR25000CE", "CE", "25000", 65);

        AdjustmentAgent.CurrentPriceSource prices = instrumentId -> new BigDecimal("100");

        AdjustmentAgent.AdjustmentResult result = agent.shiftStrike(
                session, CE_24800, newCe,
                riskSnapshot(RiskGuardDecision.FORCE_REDUCE),
                prices, BASE_TS);

        assertFalse(result.applied(), "SHIFT_STRIKE should be blocked when Risk Guard is FORCE_REDUCE");
        assertEquals("RISK_GUARD_BLOCKS_SHIFT_FORCE_REDUCE", result.reasonCode(),
                "Reason code must identify the FORCE_REDUCE block");

        // No cooldown should be recorded when blocked
        assertFalse(result.applied());
        // lastShiftTs should not have been set
        assertEquals(null, agent.lastShiftTs(), "lastShiftTs must not be set when shift is blocked");
    }

    // =========================================================================
    // EXIT_LEG tests
    // =========================================================================

    @Test
    void exitLeg_success_sessionRemainsOpen() {
        PositionSessionActionService actionService = new PositionSessionActionService();
        AdjustmentAgent agent = new AdjustmentAgent(actionService);

        PositionSessionSnapshot session = sessionWithEntryPrices("S4",
                new BigDecimal("150.0"), 65,
                new BigDecimal("120.0"), 65);

        AdjustmentAgent.CurrentPriceSource prices = instrumentId -> new BigDecimal("80");

        AdjustmentAgent.AdjustmentResult result =
                agent.exitLeg(session, CE_24800, prices, BASE_TS);

        assertTrue(result.applied(), "EXIT_LEG should be applied");
        assertEquals(AdjustmentAgent.AUDIT_ACTION_EXIT_LEG, result.reasonCode());

        PositionSessionSnapshot updated = result.updatedSession();

        // CE leg should be closed
        PositionSessionSnapshot.PositionLegSnapshot ceLeg = findLegByInstrumentId(updated, CE_24800);
        assertNotNull(ceLeg, "CE leg must still be present in the snapshot");
        assertEquals(0, ceLeg.openQuantity(), "CE leg must have openQuantity=0 after EXIT_LEG");
        assertEquals("CLOSED", ceLeg.status());

        // PE leg must remain open (full 65 contracts still open)
        PositionSessionSnapshot.PositionLegSnapshot peLeg = findLegByInstrumentId(updated, PE_24800);
        assertNotNull(peLeg, "PE leg must still be present");
        assertEquals(65, peLeg.openQuantity(), "PE leg must still have full open quantity");

        // Session must NOT be CLOSED — one leg still open
        assertNotEquals("CLOSED", updated.status(),
                "Session must not be CLOSED after EXIT_LEG when other legs remain");
    }

    // =========================================================================
    // EXIT_ALL tests
    // =========================================================================

    @Test
    void exitAll_success_sessionClosed() {
        PositionSessionActionService actionService = new PositionSessionActionService();
        AdjustmentAgent agent = new AdjustmentAgent(actionService);

        PositionSessionSnapshot session = sessionWithEntryPrices("S5",
                new BigDecimal("150.0"), 65,
                new BigDecimal("120.0"), 65);

        AdjustmentAgent.CurrentPriceSource prices = instrumentId -> new BigDecimal("80");

        AdjustmentAgent.AdjustmentResult result = agent.exitAll(session, prices, BASE_TS);

        assertTrue(result.applied(), "EXIT_ALL should be applied");
        assertEquals(AdjustmentAgent.AUDIT_ACTION_EXIT_ALL, result.reasonCode());

        PositionSessionSnapshot updated = result.updatedSession();
        assertEquals("CLOSED", updated.status(),
                "Session must be CLOSED after EXIT_ALL");

        // Both legs must have openQuantity=0
        for (PositionSessionSnapshot.PositionLegSnapshot leg : updated.legs()) {
            assertEquals(0, leg.openQuantity(),
                    "All legs must have openQuantity=0 after EXIT_ALL: " + leg.instrumentId());
        }
    }

    @Test
    void exitAll_closedSession_rejectsCommand() {
        PositionSessionActionService actionService = new PositionSessionActionService();
        AdjustmentAgent agent = new AdjustmentAgent(actionService);

        PositionSessionSnapshot session = sessionWithEntryPrices("S6",
                new BigDecimal("150.0"), 65,
                new BigDecimal("120.0"), 65);

        AdjustmentAgent.CurrentPriceSource prices = instrumentId -> new BigDecimal("80");

        // First EXIT_ALL: must succeed and return a CLOSED session
        AdjustmentAgent.AdjustmentResult firstResult = agent.exitAll(session, prices, BASE_TS);
        assertTrue(firstResult.applied(), "First EXIT_ALL must succeed");
        PositionSessionSnapshot closedSession = firstResult.updatedSession();
        assertEquals("CLOSED", closedSession.status());

        // Second EXIT_ALL on the CLOSED session: must be blocked
        AdjustmentAgent.AdjustmentResult secondResult =
                agent.exitAll(closedSession, prices, BASE_TS.plusSeconds(5));
        assertFalse(secondResult.applied(),
                "Further EXIT_ALL commands on a CLOSED session must be rejected");
        assertEquals("SESSION_CLOSED", secondResult.reasonCode(),
                "Reason code must be SESSION_CLOSED when session is already closed");

        // Also verify that exitLeg on the closed session is rejected
        AdjustmentAgent.AdjustmentResult exitLegResult =
                agent.exitLeg(closedSession, CE_24800, prices, BASE_TS.plusSeconds(5));
        assertFalse(exitLegResult.applied(),
                "EXIT_LEG on a CLOSED session must be rejected");
        assertEquals("SESSION_CLOSED", exitLegResult.reasonCode());
    }

    // =========================================================================
    // Booked PnL accumulation test
    // =========================================================================

    @Test
    void bookedPnl_accumulatesCorrectly() {
        PositionSessionActionService actionService = new PositionSessionActionService();
        AdjustmentAgent agent = new AdjustmentAgent(actionService);

        // CE entry=150, exit=80 → booked PnL = (150-80)*65 = 4550 pts (SHORT)
        // PE entry=120, exit=60 → booked PnL = (120-60)*65 = 3900 pts (SHORT)
        PositionSessionSnapshot session = sessionWithEntryPrices("S7",
                new BigDecimal("150.0"), 65,
                new BigDecimal("120.0"), 65);

        AdjustmentAgent.CurrentPriceSource prices = instrumentId -> switch (instrumentId) {
            case CE_24800 -> new BigDecimal("80.0");
            case PE_24800 -> new BigDecimal("60.0");
            default -> throw new IllegalStateException("Unexpected: " + instrumentId);
        };

        AdjustmentAgent.AdjustmentResult result = agent.exitAll(session, prices, BASE_TS);
        assertTrue(result.applied(), "EXIT_ALL must succeed");

        PositionSessionSnapshot updated = result.updatedSession();

        PositionSessionSnapshot.PositionLegSnapshot ceLeg = findLegByInstrumentId(updated, CE_24800);
        PositionSessionSnapshot.PositionLegSnapshot peLeg = findLegByInstrumentId(updated, PE_24800);
        assertNotNull(ceLeg, "CE leg must be present");
        assertNotNull(peLeg, "PE leg must be present");

        // Verify individual booked PnL (per-lot amounts: 70 pts/contract * 65 contracts)  
        assertEquals(0, new BigDecimal("4550.0").compareTo(ceLeg.bookedPnl()),
                "CE booked PnL should be 4550.0 pts (70*65). Actual: " + ceLeg.bookedPnl());
        assertEquals(0, new BigDecimal("3900.0").compareTo(peLeg.bookedPnl()),
                "PE booked PnL should be 3900.0 pts (60*65). Actual: " + peLeg.bookedPnl());

        // Verify total
        BigDecimal totalBookedPnl = updated.legs().stream()
                .filter(leg -> leg.bookedPnl() != null)
                .map(PositionSessionSnapshot.PositionLegSnapshot::bookedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("8450.0").compareTo(totalBookedPnl),
                "Total booked PnL should be 8450.0 pts (4550+3900). Actual: " + totalBookedPnl);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a two-leg SHORT STRADDLE session at strike 24800.
     * Entry prices are injected for PnL assertions.
     */
    private static PositionSessionSnapshot sessionWithEntryPrices(
            String sessionId,
            BigDecimal ceEntryPrice, int ceQty,
            BigDecimal peEntryPrice, int peQty) {

        Instant now = BASE_TS;
        PositionSessionSnapshot.PositionLegSnapshot ceLeg =
                new PositionSessionSnapshot.PositionLegSnapshot(
                        sessionId + "-CE",
                        "Short CE 24800",
                        "CE", "SHORT",
                        new BigDecimal("24800"),
                        "2026-04-30",
                        "NIFTY26APR24800CE",
                        CE_24800,
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
                        PE_24800,
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
     * Creates a {@link AdjustmentAgent.NewLegDescriptor} for a strike shift target.
     */
    private static AdjustmentAgent.NewLegDescriptor newLeg(
            String instrumentId,
            String tradingSymbol,
            String optionType,
            String strike,
            int quantity) {
        return new AdjustmentAgent.NewLegDescriptor(
                instrumentId,
                tradingSymbol,
                optionType,
                new BigDecimal(strike),
                "2026-04-30",
                quantity
        );
    }

    /**
     * Builds an ALLOW {@link RiskGuardSnapshot} that permits all operations.
     */
    private static RiskGuardSnapshot riskAllow() {
        return riskSnapshot(RiskGuardDecision.ALLOW);
    }

    /**
     * Builds a minimal {@link RiskGuardSnapshot} with the given decision.
     */
    private static RiskGuardSnapshot riskSnapshot(RiskGuardDecision decision) {
        return new RiskGuardSnapshot(
                BASE_TS,
                decision,
                decision == RiskGuardDecision.ALLOW ? List.of() : List.of(decision.name()),
                decision == RiskGuardDecision.ALLOW ? "" : "test condition: " + decision,
                0.0,   // netDelta
                0.0,   // livePnl
                false, // maxLossBreached
                false, // premiumExpansionAlert
                false, // liquidityAlert
                false, // dataStale
                false, // churnDetected
                false  // lotCapBreached
        );
    }

    /**
     * Finds a leg by instrument ID in the session, returning null if not found.
     */
    private static PositionSessionSnapshot.PositionLegSnapshot findLegByInstrumentId(
            PositionSessionSnapshot session, String instrumentId) {
        return session.legs().stream()
                .filter(leg -> instrumentId.equals(leg.instrumentId()))
                .findFirst()
                .orElse(null);
    }
}
