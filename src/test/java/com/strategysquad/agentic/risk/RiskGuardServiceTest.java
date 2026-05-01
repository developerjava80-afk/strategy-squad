package com.strategysquad.agentic.risk;

import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.PositionSessionSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RiskGuardService}.
 */
class RiskGuardServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-26T10:00:00Z");
    private final RiskGuardService service = new RiskGuardService();

    @Test
    void netDeltaBreach_positive_triggersForceReduce() {
        RiskGuardInput input = normalInput()
                .withNetDelta(0.35)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.FORCE_REDUCE, snap.decision());
        assertTrue(snap.triggeredConditions().contains("NET_DELTA_BREACH"));
        assertFalse(snap.explanation().isBlank());
    }

    @Test
    void netDeltaBreach_negative_triggersForceReduce() {
        RiskGuardInput input = normalInput()
                .withNetDelta(-0.40)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.FORCE_REDUCE, snap.decision());
        assertTrue(snap.triggeredConditions().contains("NET_DELTA_BREACH"));
    }

    @Test
    void netDeltaWithinThreshold_doesNotTriggerStop1() {
        RiskGuardInput input = normalInput()
                .withNetDelta(0.25)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertNotEquals(RiskGuardDecision.FORCE_REDUCE, snap.decision());
    }

    @Test
    void maxLossBreach_triggersForceExit() {
        RiskGuardInput input = normalInput()
                .withLivePnl(-5001.0)
                .withMaxLossPoints(5000.0)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.FORCE_EXIT, snap.decision());
        assertTrue(snap.triggeredConditions().contains("MAX_LOSS_BREACH"));
        assertTrue(snap.maxLossBreached());
    }

    @Test
    void livePnlAboveMaxLoss_doesNotTriggerStop2() {
        RiskGuardInput input = normalInput()
                .withLivePnl(-4999.0)
                .withMaxLossPoints(5000.0)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertNotEquals(RiskGuardDecision.FORCE_EXIT, snap.decision());
    }

    @Test
    void premiumExpanding_oneLeg_triggersWarn() {
        SignalSnapshot defensiveSignal = signal(
                "INS_NIFTY_20260501_24800_CE",
                SignalSnapshot.ThetaState.DEFENSIVE_EXIT,
                SignalSnapshot.VolumeState.CONFIRMED);
        SignalSnapshot normalSignal = signal(
                "INS_NIFTY_20260501_24800_PE",
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.CONFIRMED);

        RiskGuardInput input = normalInput()
                .withSignals(Map.of(
                        defensiveSignal.instrumentId(), defensiveSignal,
                        normalSignal.instrumentId(), normalSignal))
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.WARN, snap.decision());
        assertTrue(snap.triggeredConditions().contains("PREMIUM_EXPANDING"));
        assertTrue(snap.premiumExpansionAlert());
    }

    @Test
    void premiumExpanding_twoLegs_triggersForceReduce() {
        SignalSnapshot defensive1 = signal(
                "INS_NIFTY_20260501_24800_CE",
                SignalSnapshot.ThetaState.DEFENSIVE_EXIT,
                SignalSnapshot.VolumeState.CONFIRMED);
        SignalSnapshot defensive2 = signal(
                "INS_NIFTY_20260501_24800_PE",
                SignalSnapshot.ThetaState.DEFENSIVE_EXIT,
                SignalSnapshot.VolumeState.CONFIRMED);

        RiskGuardInput input = normalInput()
                .withSignals(Map.of(
                        defensive1.instrumentId(), defensive1,
                        defensive2.instrumentId(), defensive2))
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.FORCE_REDUCE, snap.decision());
        assertTrue(snap.triggeredConditions().contains("PREMIUM_EXPANDING"));
        assertTrue(snap.premiumExpansionAlert());
    }

    @Test
    void bidZeroOrLiquidityAbsent_withActiveSession_triggersForceExit() {
        SignalSnapshot absentSignal = signal(
                "INS_NIFTY_20260501_24800_CE",
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.ABSENT);

        RiskGuardInput input = normalInput()
                .withSignals(Map.of(absentSignal.instrumentId(), absentSignal))
                .withActiveSession(minimalSession())
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.FORCE_EXIT, snap.decision());
        assertTrue(snap.triggeredConditions().contains("ZERO_BID"));
        assertTrue(snap.liquidityAlert());
    }

    @Test
    void bidZeroOrLiquidityAbsent_withoutActiveSession_triggersBlockNewEntry() {
        SignalSnapshot absentSignal = signal(
                "INS_NIFTY_20260501_24800_CE",
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.ABSENT);

        RiskGuardInput input = normalInput()
                .withSignals(Map.of(absentSignal.instrumentId(), absentSignal))
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.BLOCK_NEW_ENTRY, snap.decision());
        assertTrue(snap.triggeredConditions().contains("ZERO_BID"));
        assertTrue(snap.liquidityAlert());
    }

    @Test
    void staleData_triggersBlockNewEntry() {
        RiskGuardInput input = normalInput()
                .withLastTickAgeSeconds(150)
                .withStaleDataSeconds(120)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.BLOCK_NEW_ENTRY, snap.decision());
        assertTrue(snap.triggeredConditions().contains("STALE_DATA"));
        assertTrue(snap.dataStale());
    }

    @Test
    void dataFresh_doesNotTriggerStop5() {
        RiskGuardInput input = normalInput()
                .withLastTickAgeSeconds(60)
                .withStaleDataSeconds(120)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertNotEquals(RiskGuardDecision.BLOCK_NEW_ENTRY, snap.decision());
    }

    @Test
    void totalDrawdownBreach_triggersForceExit() {
        RiskGuardInput input = normalInput()
                .withBookedPnl(-3000.0)
                .withLivePnl(-2500.0)
                .withMaxLossPoints(5000.0)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.FORCE_EXIT, snap.decision());
        assertTrue(snap.triggeredConditions().contains("MAX_DRAWDOWN_BREACH"));
        assertTrue(snap.maxLossBreached());
    }

    @Test
    void churnDetected_triggersHaltSession() {
        RiskGuardInput input = normalInput()
                .withRecentCommandCount(RiskGuardService.MAX_COMMANDS_PER_CHURN_WINDOW + 1)
                .withChurnWindowMinutes(5)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.HALT_SESSION, snap.decision());
        assertTrue(snap.triggeredConditions().contains("CHURN_DETECTED"));
        assertTrue(snap.churnDetected());
    }

    @Test
    void totalLotsExceedCap_triggersBlockNewEntry() {
        RiskGuardInput input = normalInput()
                .withLotCount(5)
                .withMaxLotCap(4)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.BLOCK_NEW_ENTRY, snap.decision());
        assertTrue(snap.triggeredConditions().contains("LOT_CAP_BREACH"));
        assertTrue(snap.lotCapBreached());
    }

    @Test
    void missingRequiredSignalData_triggersBlockNewEntry() {
        RiskGuardInput input = normalInput()
                .withActiveSession(sessionWithOpenLeg("INS_NIFTY_20260501_24800_CE"))
                .withSignals(Map.of())
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.BLOCK_NEW_ENTRY, snap.decision());
        assertTrue(snap.triggeredConditions().contains("MISSING_REQUIRED_SIGNAL_DATA"));
    }

    @Test
    void highestSeverityWinsWhenMultipleConditionsActive() {
        RiskGuardInput input = normalInput()
                .withLivePnl(-2500.0)
                .withBookedPnl(-3000.0)
                .withMaxLossPoints(5000.0)
                .withRecentCommandCount(RiskGuardService.MAX_COMMANDS_PER_CHURN_WINDOW + 2)
                .withLotCount(6)
                .withMaxLotCap(4)
                .build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.HALT_SESSION, snap.decision());
        assertTrue(snap.triggeredConditions().contains("MAX_DRAWDOWN_BREACH"));
        assertTrue(snap.triggeredConditions().contains("CHURN_DETECTED"));
        assertTrue(snap.triggeredConditions().contains("LOT_CAP_BREACH"));
    }

    @Test
    void allNormal_returnsAllow() {
        RiskGuardInput input = normalInput().build();

        RiskGuardSnapshot snap = service.evaluate(input);

        assertEquals(RiskGuardDecision.ALLOW, snap.decision());
        assertTrue(snap.triggeredConditions().isEmpty());
        assertEquals("", snap.explanation());
        assertFalse(snap.maxLossBreached());
        assertFalse(snap.premiumExpansionAlert());
        assertFalse(snap.liquidityAlert());
        assertFalse(snap.dataStale());
        assertFalse(snap.churnDetected());
        assertFalse(snap.lotCapBreached());
    }

    @Test
    void evaluate_nullInput_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(null));
    }

    private static InputBuilder normalInput() {
        SignalSnapshot holdSignal = signal(
                "INS_NIFTY_20260501_24800_CE",
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.CONFIRMED);
        return new InputBuilder()
                .withNetDelta(0.05)
                .withLivePnl(1000.0)
                .withBookedPnl(0.0)
                .withMaxLossPoints(5000.0)
                .withLastTickAgeSeconds(10)
                .withStaleDataSeconds(30)
                .withSignals(Map.of(holdSignal.instrumentId(), holdSignal))
                .withActiveSession(null)
                .withLotCount(1)
                .withMaxLotCap(4)
                .withRecentCommandCount(0)
                .withChurnWindowMinutes(5);
    }

    private static SignalSnapshot signal(String instrumentId,
                                         SignalSnapshot.ThetaState thetaState,
                                         SignalSnapshot.VolumeState volumeState) {
        return new SignalSnapshot(
                NOW,
                instrumentId,
                "NIFTY",
                instrumentId.endsWith("_CE") ? "CE" : "PE",
                new BigDecimal("24800"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                thetaState,
                volumeState,
                false,
                "OK"
        );
    }

    private static PositionSessionSnapshot minimalSession() {
        return new PositionSessionSnapshot(
                "test-session-id",
                "SIMULATION",
                "SHORT_STRADDLE",
                "SHORT",
                "NIFTY",
                "WEEKLY",
                "15MIN",
                3,
                new BigDecimal("24800"),
                1,
                NOW,
                NOW,
                null,
                "OPEN",
                List.of(),
                List.of()
        );
    }

    private static PositionSessionSnapshot sessionWithOpenLeg(String instrumentId) {
        return new PositionSessionSnapshot(
                "session-with-leg",
                "SIMULATION",
                "SHORT_STRADDLE",
                "SHORT",
                "NIFTY",
                "WEEKLY",
                "15MIN",
                3,
                new BigDecimal("24800"),
                1,
                NOW,
                NOW,
                null,
                "OPEN",
                List.of(new PositionSessionSnapshot.PositionLegSnapshot(
                        "leg-1",
                        "Short call",
                        "CE",
                        "SHORT",
                        new BigDecimal("24800"),
                        "2026-05-01",
                        instrumentId,
                        instrumentId,
                        new BigDecimal("120"),
                        65,
                        65,
                        BigDecimal.ZERO,
                        "OPEN",
                        NOW,
                        NOW
                )),
                List.of()
        );
    }

    private static class InputBuilder {
        private double netDelta = 0.0;
        private double livePnl = 0.0;
        private double bookedPnl = 0.0;
        private double maxLossPoints = Double.MAX_VALUE;
        private int lastTickAgeSeconds = 0;
        private int staleDataSeconds = 30;
        private Map<String, SignalSnapshot> signals = Map.of();
        private Optional<PositionSessionSnapshot> activeSession = Optional.empty();
        private int lotCount = 1;
        private int maxLotCap = 4;
        private int recentCommandCount = 0;
        private int churnWindowMinutes = 5;

        InputBuilder withNetDelta(double value) { this.netDelta = value; return this; }
        InputBuilder withLivePnl(double value) { this.livePnl = value; return this; }
        InputBuilder withBookedPnl(double value) { this.bookedPnl = value; return this; }
        InputBuilder withMaxLossPoints(double value) { this.maxLossPoints = value; return this; }
        InputBuilder withLastTickAgeSeconds(int value) { this.lastTickAgeSeconds = value; return this; }
        InputBuilder withStaleDataSeconds(int value) { this.staleDataSeconds = value; return this; }
        InputBuilder withSignals(Map<String, SignalSnapshot> value) { this.signals = value; return this; }
        InputBuilder withLotCount(int value) { this.lotCount = value; return this; }
        InputBuilder withMaxLotCap(int value) { this.maxLotCap = value; return this; }
        InputBuilder withRecentCommandCount(int value) { this.recentCommandCount = value; return this; }
        InputBuilder withChurnWindowMinutes(int value) { this.churnWindowMinutes = value; return this; }

        InputBuilder withActiveSession(PositionSessionSnapshot session) {
            this.activeSession = Optional.ofNullable(session);
            return this;
        }

        RiskGuardInput build() {
            return new RiskGuardInput(
                    NOW,
                    activeSession,
                    signals,
                    livePnl,
                    bookedPnl,
                    netDelta,
                    lotCount,
                    maxLotCap,
                    recentCommandCount,
                    churnWindowMinutes,
                    maxLossPoints,
                    staleDataSeconds,
                    lastTickAgeSeconds
            );
        }
    }
}
