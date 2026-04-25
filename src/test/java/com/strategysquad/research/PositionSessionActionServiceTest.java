package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionSessionActionServiceTest {
    private final PositionSessionActionService service = new PositionSessionActionService();

    @Test
    void partialExitBooksPnlAndKeepsRemainingOpenQuantity() {
        PositionSessionSnapshot updated = service.apply(
                sampleSession(),
                new PositionSessionActionRequest(
                        "session-1",
                        "manual_exit_selected",
                        Instant.parse("2026-04-23T10:00:00Z"),
                        null,
                        List.of(new PositionSessionActionRequest.LegAction(
                                "leg-short-call",
                                null,
                                new BigDecimal("120"),
                                0,
                                65,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "manual exit",
                                "Exited Short call qty 65 at price 120",
                                null,
                                null,
                                null
                        ))
                )
        );

        PositionSessionSnapshot.PositionLegSnapshot leg = updated.legs().get(0);
        assertEquals(65, leg.openQuantity());
        assertEquals("PARTIALLY_EXITED", leg.status());
        assertEquals(new BigDecimal("-1300"), leg.bookedPnl());
        assertEquals("PARTIALLY_EXITED", updated.status());
        assertEquals(1, updated.auditLog().size());
        assertEquals("manual_exit_selected", updated.auditLog().get(0).actionType());
    }

    @Test
    void exitAllClosesAllOpenLegs() {
        PositionSessionSnapshot updated = service.apply(
                sampleSession(),
                new PositionSessionActionRequest(
                        "session-1",
                        "manual_exit_all",
                        Instant.parse("2026-04-23T10:02:00Z"),
                        null,
                        List.of(
                                new PositionSessionActionRequest.LegAction(
                                        "leg-short-call",
                                        null,
                                        new BigDecimal("105"),
                                        0,
                                        130,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "manual exit all",
                                        "Exit all short call",
                                        null,
                                        null,
                                        null
                                ),
                                new PositionSessionActionRequest.LegAction(
                                        "leg-long-put",
                                        null,
                                        new BigDecimal("140"),
                                        0,
                                        65,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "manual exit all",
                                        "Exit all long put",
                                        null,
                                        null,
                                        null
                                )
                        )
                )
        );

        assertEquals("CLOSED", updated.status());
        assertEquals("CLOSED", updated.legs().get(0).status());
        assertEquals("CLOSED", updated.legs().get(1).status());
        assertEquals(0, updated.legs().get(0).openQuantity());
        assertEquals(0, updated.legs().get(1).openQuantity());
        assertEquals(2, updated.auditLog().size());
    }

    @Test
    void deltaAdjustmentReducesExactlyOneLotAndUpdatesTimestamp() {
        Instant adjustmentTs = Instant.parse("2026-04-23T10:05:00Z");

        PositionSessionSnapshot updated = service.apply(
                sampleSession(),
                new PositionSessionActionRequest(
                        "session-1",
                        "delta_adjustment",
                        adjustmentTs,
                        adjustmentTs,
                        List.of(new PositionSessionActionRequest.LegAction(
                                "leg-short-call",
                                null,
                                new BigDecimal("115"),
                                0,
                                65,
                                new BigDecimal("0.80"),
                                new BigDecimal("0.60"),
                                new BigDecimal("0.40"),
                                2100L,
                                new BigDecimal("1200"),
                                "REDUCE",
                                null,
                                null,
                                null,
                                null,
                                "delta_trend_and_volume_spike",
                                "Delta adjustment applied: reduced Short call qty from 130 to 65",
                                null,
                                null,
                                null
                        ))
                )
        );

        PositionSessionSnapshot.PositionLegSnapshot leg = updated.legs().get(0);
        assertEquals(65, leg.openQuantity());
        assertEquals("PARTIALLY_EXITED", leg.status());
        assertEquals(adjustmentTs, updated.lastDeltaAdjustmentTs());
        assertEquals("delta_adjustment", updated.auditLog().get(0).actionType());
        assertEquals("REDUCE", updated.auditLog().get(0).adjustmentActionType());
        assertEquals(65, updated.auditLog().get(0).exitedQuantity());
    }

    @Test
    void deltaAdjustmentAddCreatesNewLegWithoutBookedPnl() {
        Instant adjustmentTs = Instant.parse("2026-04-23T10:06:00Z");

        PositionSessionSnapshot updated = service.apply(
                sampleSession(),
                new PositionSessionActionRequest(
                        "session-1",
                        "delta_adjustment",
                        adjustmentTs,
                        adjustmentTs,
                        List.of(new PositionSessionActionRequest.LegAction(
                                "leg-added-put",
                                new BigDecimal("80"),
                                null,
                                65,
                                0,
                                new BigDecimal("-0.25"),
                                new BigDecimal("-0.20"),
                                new BigDecimal("-0.18"),
                                2200L,
                                new BigDecimal("1200"),
                                "ADD",
                                "Added short put",
                                "PE",
                                "SHORT",
                                new BigDecimal("22300"),
                                "rebalance_add",
                                "Added short put by one lot",
                                "NIFTY26APR22300PE",
                                "OPT3",
                                "2026-04-30"
                        ))
                )
        );

        assertEquals(3, updated.legs().size());
        PositionSessionSnapshot.PositionLegSnapshot newLeg = updated.legs().get(2);
        assertEquals("Added short put", newLeg.label());
        assertEquals(65, newLeg.openQuantity());
        assertEquals(new BigDecimal("80"), newLeg.entryPrice());
        assertEquals(BigDecimal.ZERO, newLeg.bookedPnl());
        assertEquals("ADD", updated.auditLog().get(0).adjustmentActionType());
        assertEquals(0, updated.auditLog().get(0).exitedQuantity());
        assertEquals(65, updated.auditLog().get(0).remainingQuantity());
    }

    @Test
    void deltaAdjustmentAddBlendsEntryPriceForExistingLeg() {
        Instant adjustmentTs = Instant.parse("2026-04-23T10:07:00Z");

        PositionSessionSnapshot updated = service.apply(
                sampleSession(),
                new PositionSessionActionRequest(
                        "session-1",
                        "delta_adjustment",
                        adjustmentTs,
                        adjustmentTs,
                        List.of(new PositionSessionActionRequest.LegAction(
                                "leg-short-call",
                                new BigDecimal("130"),
                                null,
                                65,
                                0,
                                new BigDecimal("0.50"),
                                new BigDecimal("0.40"),
                                new BigDecimal("0.30"),
                                2500L,
                                new BigDecimal("1200"),
                                "ADD",
                                "Short call",
                                "CE",
                                "SHORT",
                                new BigDecimal("22500"),
                                "rebalance_add",
                                "Added one more short call lot",
                                "NIFTY26APR22500CE",
                                "OPT1",
                                "2026-04-30"
                        ))
                )
        );

        PositionSessionSnapshot.PositionLegSnapshot leg = updated.legs().get(0);
        assertEquals(195, leg.openQuantity());
        assertEquals(195, leg.originalQuantity());
        assertEquals(new BigDecimal("110"), leg.entryPrice());
        assertEquals(BigDecimal.ZERO, leg.bookedPnl());
    }

    private static PositionSessionSnapshot sampleSession() {
        Instant createdAt = Instant.parse("2026-04-23T09:55:00Z");
        return new PositionSessionSnapshot(
                "session-1",
                "SHORT_STRANGLE",
                "Short Strangle",
                "SELLER",
                "NIFTY",
                "WEEKLY",
                "1Y",
                3,
                new BigDecimal("22350"),
                130,
                createdAt,
                createdAt,
                null,
                "OPEN",
                List.of(
                        new PositionSessionSnapshot.PositionLegSnapshot(
                                "leg-short-call",
                                "Short call",
                                "CE",
                                "SHORT",
                                new BigDecimal("22500"),
                                "2026-04-30",
                                "NIFTY26APR22500CE",
                                "OPT1",
                                new BigDecimal("100"),
                                130,
                                130,
                                BigDecimal.ZERO,
                                "OPEN",
                                createdAt,
                                createdAt
                        ),
                        new PositionSessionSnapshot.PositionLegSnapshot(
                                "leg-long-put",
                                "Long put",
                                "PE",
                                "LONG",
                                new BigDecimal("22200"),
                                "2026-04-30",
                                "NIFTY26APR22200PE",
                                "OPT2",
                                new BigDecimal("120"),
                                65,
                                65,
                                BigDecimal.ZERO,
                                "OPEN",
                                createdAt,
                                createdAt
                        )
                ),
                List.of()
        );
    }
}
