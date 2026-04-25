package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaAdjustmentServiceTest {
    private final DeltaAdjustmentService service = new DeltaAdjustmentService();

    @Test
    void maxLotsCapSkipsAddWhenOnlyAddWouldHelp() {
        List<DeltaAdjustmentService.LegState> legs = IntStream.range(0, 20)
                .mapToObj(index -> leg(
                        "Short call " + index,
                        "CE",
                        "SHORT",
                        65,
                        65,
                        "22500",
                        "110",
                        "0.40",
                        "0.40",
                        "0.40",
                        3200L,
                        "1200",
                        "-2",
                        false
                ))
                .toList();

        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                Instant.parse("2026-04-24T10:15:00Z"),
                legs,
                List.of(leg(
                        "Balancing short put",
                        "PE",
                        "SHORT",
                        65,
                        65,
                        "22450",
                        "140",
                        "-0.30",
                        "-0.30",
                        "-0.30",
                        3300L,
                        "1200",
                        "0",
                        false
                )),
                DeltaAdjustmentService.UnderlyingDirection.NEUTRAL,
                "-10",
                "-2",
                "-4",
                20,
                null
        ));

        assertNotNull(outcome);
        assertFalse(outcome.applied());
        assertEquals("adjustment_skipped_max_lots_cap", outcome.code());
        assertEquals(20, outcome.currentTotalLots());
        assertEquals(0, outcome.availableLots());
    }

    @Test
    void addImprovesNetDelta() {
        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                Instant.parse("2026-04-24T10:16:00Z"),
                List.of(leg("Short call", "CE", "SHORT", 65, 65, "22500", "120", "0.60", "0.60", "0.60", 3200L, "1200", "-4", false)),
                List.of(leg("Balancing short put", "PE", "SHORT", 65, 65, "22450", "150", "-0.30", "-0.30", "-0.30", 3400L, "1200", "0", false)),
                DeltaAdjustmentService.UnderlyingDirection.NEUTRAL,
                "-2",
                "-1",
                "-2",
                20,
                null
        ));

        assertNotNull(outcome);
        assertTrue(outcome.applied());
        assertEquals("ADD", outcome.actionType());
        assertEquals("Balancing short put", outcome.leg());
        assertEquals(0, outcome.oldQuantity());
        assertEquals(65, outcome.newQuantity());
        assertEquals(new BigDecimal("-19.50"), outcome.postAdjNetDelta());
    }

    @Test
    void reduceImprovesNetDelta() {
        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                Instant.parse("2026-04-24T10:17:00Z"),
                List.of(
                        leg("Short call", "CE", "SHORT", 130, 65, "22500", "110", "0.60", "0.55", "0.50", 3400L, "1200", "-6", false),
                        leg("Short put", "PE", "SHORT", 65, 65, "22350", "95", "-0.20", "-0.18", "-0.16", 3100L, "1200", "5", false)
                ),
                List.of(),
                DeltaAdjustmentService.UnderlyingDirection.NEUTRAL,
                "-1",
                "-1",
                "-2",
                3,
                null
        ));

        assertNotNull(outcome);
        assertTrue(outcome.applied());
        assertEquals("REDUCE", outcome.actionType());
        assertEquals("Short call", outcome.leg());
        assertEquals(130, outcome.oldQuantity());
        assertEquals(65, outcome.newQuantity());
    }

    @Test
    void choosesAddOverReduceWhenScoreBetter() {
        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                Instant.parse("2026-04-24T10:18:00Z"),
                List.of(
                        leg("Long put hedge", "PE", "LONG", 130, 65, "22300", "40", "-0.40", "-0.35", "-0.30", 2800L, "1200", "12", false),
                        leg("Short put", "PE", "SHORT", 65, 65, "22350", "160", "-0.25", "-0.22", "-0.20", 3600L, "1200", "7", false)
                ),
                List.of(),
                DeltaAdjustmentService.UnderlyingDirection.NEUTRAL,
                "4",
                "1",
                "2",
                20,
                null
        ));

        assertNotNull(outcome);
        assertTrue(outcome.applied());
        assertEquals("ADD", outcome.actionType());
        assertEquals("Short put", outcome.leg());
        assertEquals(65, outcome.oldQuantity());
        assertEquals(130, outcome.newQuantity());
    }

    @Test
    void hardModeFavorsDeltaImprovement() {
        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                Instant.parse("2026-04-24T10:19:00Z"),
                List.of(
                        leg("Long put hedge", "PE", "LONG", 130, 65, "22300", "45", "-0.40", "-0.35", "-0.30", 2900L, "1200", "6", false),
                        leg("Short put", "PE", "SHORT", 65, 65, "22350", "180", "-0.10", "-0.09", "-0.08", 3700L, "1200", "8", false)
                ),
                List.of(),
                DeltaAdjustmentService.UnderlyingDirection.BULLISH,
                "-8",
                "-9",
                "-16",
                20,
                null
        ));

        assertNotNull(outcome);
        assertTrue(outcome.applied());
        assertEquals("HARD", outcome.triggerType());
        assertEquals("REDUCE", outcome.actionType());
        assertEquals("Long put hedge", outcome.leg());
    }

    @Test
    void sideAwareDeltaRespectsLongAndShortCePeSigns() {
        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                Instant.parse("2026-04-24T10:20:00Z"),
                List.of(
                        leg("Long put", "PE", "LONG", 130, 65, "22300", "42", "-0.40", "-0.35", "-0.30", 2900L, "1200", "5", false),
                        leg("Short put", "PE", "SHORT", 65, 65, "22350", "140", "-0.25", "-0.22", "-0.20", 3200L, "1200", "4", false),
                        leg("Short call", "CE", "SHORT", 65, 65, "22500", "110", "0.20", "0.18", "0.15", 3300L, "1200", "-2", false)
                ),
                List.of(),
                DeltaAdjustmentService.UnderlyingDirection.NEUTRAL,
                "1",
                "0",
                "1",
                4,
                null
        ));

        assertNotNull(outcome);
        assertTrue(outcome.applied());
        assertEquals("REDUCE", outcome.actionType());
        assertEquals("Long put", outcome.leg());
    }

    @Test
    void thetaAffectsNormalModeScoring() {
        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                Instant.parse("2026-04-24T10:21:00Z"),
                List.of(
                        leg("Short call", "CE", "SHORT", 65, 65, "22500", "115", "0.60", "0.55", "0.50", 3200L, "1200", "-3", false),
                        leg("Short put", "PE", "SHORT", 65, 65, "22350", "90", "-0.20", "-0.18", "-0.16", 1000L, "1200", "2", false)
                ),
                List.of(
                        leg("Thin short put", "PE", "SHORT", 65, 65, "22450", "60", "-0.20", "-0.18", "-0.16", 3200L, "1200", "0", false),
                        leg("Rich short put", "PE", "SHORT", 65, 65, "22400", "180", "-0.20", "-0.18", "-0.16", 3200L, "1200", "0", false)
                ),
                DeltaAdjustmentService.UnderlyingDirection.NEUTRAL,
                "-1",
                "-1",
                "-2",
                20,
                null
        ));

        assertNotNull(outcome);
        assertTrue(outcome.applied());
        assertEquals("NORMAL", outcome.triggerType());
        assertEquals("ADD", outcome.actionType());
        assertEquals("Rich short put", outcome.leg());
    }

    @Test
    void cooldownSkipsAdjustment() {
        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                Instant.parse("2026-04-24T10:21:30Z"),
                List.of(
                        leg("Short call", "CE", "SHORT", 130, 65, "22500", "110", "0.60", "0.55", "0.50", 3400L, "1200", "-6", false),
                        leg("Short put", "PE", "SHORT", 65, 65, "22350", "95", "-0.20", "-0.18", "-0.16", 3100L, "1200", "5", false)
                ),
                List.of(),
                DeltaAdjustmentService.UnderlyingDirection.NEUTRAL,
                "-1",
                "-1",
                "-2",
                3,
                null,
                Instant.parse("2026-04-24T10:21:00Z"),
                null
        ));

        assertNotNull(outcome);
        assertFalse(outcome.applied());
        assertEquals("adjustment_skipped_cooldown", outcome.code());
    }

    @Test
    void churnGuardBlocksImmediateFlipFlop() {
        Instant evaluationTs = Instant.parse("2026-04-24T10:23:00Z");
        DeltaAdjustmentService.AdjustmentOutcome outcome = service.evaluate(context(
                evaluationTs,
                List.of(leg("Short call", "CE", "SHORT", 65, 65, "22500", "120", "0.60", "0.60", "0.60", 3200L, "1200", "-4", false)),
                List.of(leg("Balancing short put", "PE", "SHORT", 65, 65, "22450", "150", "-0.30", "-0.30", "-0.30", 3400L, "1200", "0", false)),
                DeltaAdjustmentService.UnderlyingDirection.NEUTRAL,
                "-2",
                "-1",
                "-2",
                20,
                new DeltaAdjustmentService.LastAdjustment(
                        DeltaAdjustmentService.ActionType.REDUCE,
                        "PE",
                        "SHORT",
                        new BigDecimal("22450"),
                        evaluationTs.minusSeconds(90)
                ),
                evaluationTs.minusSeconds(90),
                null
        ));

        assertNotNull(outcome);
        assertFalse(outcome.applied());
        assertEquals("adjustment_skipped_churn_guard", outcome.code());
    }

    private static DeltaAdjustmentService.AdjustmentContext context(
            Instant evaluationTs,
            List<DeltaAdjustmentService.LegState> legs,
            List<DeltaAdjustmentService.LegState> addCandidates,
            DeltaAdjustmentService.UnderlyingDirection direction,
            String livePnl,
            String pnl2m,
            String pnl5m,
            int maxLots,
            DeltaAdjustmentService.LastAdjustment lastAdjustment
    ) {
        return context(evaluationTs, legs, addCandidates, direction, livePnl, pnl2m, pnl5m, maxLots, lastAdjustment, null, null);
    }

    private static DeltaAdjustmentService.AdjustmentContext context(
            Instant evaluationTs,
            List<DeltaAdjustmentService.LegState> legs,
            List<DeltaAdjustmentService.LegState> addCandidates,
            DeltaAdjustmentService.UnderlyingDirection direction,
            String livePnl,
            String pnl2m,
            String pnl5m,
            int maxLots,
            DeltaAdjustmentService.LastAdjustment lastAdjustment,
            Instant lastAdjustmentTs,
            Instant pendingAdjustmentSinceTs
    ) {
        return new DeltaAdjustmentService.AdjustmentContext(
                evaluationTs,
                lastAdjustmentTs,
                pendingAdjustmentSinceTs,
                legs,
                addCandidates,
                direction,
                new BigDecimal(livePnl),
                new BigDecimal(pnl2m),
                new BigDecimal(pnl5m),
                maxLots,
                lastAdjustment
        );
    }

    private static DeltaAdjustmentService.LegState leg(
            String label,
            String optionType,
            String side,
            int quantity,
            int lotSize,
            String strike,
            String currentPrice,
            String delta2m,
            String delta5m,
            String deltaSod,
            Long currentVolume,
            String dayAverageVolume,
            String livePnlPoints,
            boolean stale
    ) {
        String token = label.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase();
        return new DeltaAdjustmentService.LegState(
                label,
                optionType,
                side,
                new BigDecimal(strike),
                quantity,
                lotSize,
                new BigDecimal(currentPrice),
                new BigDecimal(currentPrice),
                new BigDecimal(delta2m),
                new BigDecimal(delta5m),
                new BigDecimal(deltaSod),
                currentVolume,
                new BigDecimal(dayAverageVolume),
                new BigDecimal(livePnlPoints),
                "INS_" + token,
                "SYM_" + token,
                "2026-04-30",
                stale
        );
    }
}
