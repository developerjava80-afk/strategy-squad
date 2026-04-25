package com.strategysquad.research;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Persisted position session for the scenario research console.
 */
public record PositionSessionSnapshot(
        String sessionId,
        String mode,
        String strategyLabel,
        String orientation,
        String underlying,
        String expiryType,
        String timeframe,
        int dte,
        BigDecimal spot,
        int scenarioQty,
        Instant createdAt,
        Instant updatedAt,
        Instant lastDeltaAdjustmentTs,
        String status,
        List<PositionLegSnapshot> legs,
        List<PositionAuditEntry> auditLog
) {
    public PositionSessionSnapshot {
        legs = legs == null ? List.of() : List.copyOf(legs);
        auditLog = auditLog == null ? List.of() : List.copyOf(auditLog);
    }

    public record PositionLegSnapshot(
            String legId,
            String label,
            String optionType,
            String side,
            BigDecimal strike,
            String expiryDate,
            String symbol,
            String instrumentId,
            BigDecimal entryPrice,
            int originalQuantity,
            int openQuantity,
            BigDecimal bookedPnl,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PositionAuditEntry(
            String actionId,
            Instant timestamp,
            String legId,
            String legLabel,
            String actionType,
            String adjustmentActionType,
            int oldQuantity,
            int exitedQuantity,
            int remainingQuantity,
            int totalLotsBefore,
            int totalLotsAfter,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal bookedPnl,
            BigDecimal delta2m,
            BigDecimal delta5m,
            BigDecimal deltaSod,
            Long currentVolume,
            BigDecimal dayAverageVolume,
            String triggerType,
            String reasonCode,
            BigDecimal livePnlPoints,
            BigDecimal livePnlChange2mPoints,
            BigDecimal livePnlChange5mPoints,
            BigDecimal netDelta2m,
            BigDecimal netDelta5m,
            BigDecimal netDeltaSod,
            BigDecimal postAdjNetDelta,
            BigDecimal improvementAbsDelta,
            BigDecimal improvementRatio,
            String underlyingDirection,
            String profitAlignment,
            Boolean volumeConfirmed,
            Boolean volumeBypassed,
            BigDecimal thetaScore,
            BigDecimal liquidityScore,
            BigDecimal score,
            String reason,
            String message
    ) {
        public PositionAuditEntry(
                String actionId,
                Instant timestamp,
                String legId,
                String legLabel,
                String actionType,
                int oldQuantity,
                int exitedQuantity,
                int remainingQuantity,
                BigDecimal entryPrice,
                BigDecimal exitPrice,
                BigDecimal bookedPnl,
                BigDecimal delta2m,
                BigDecimal delta5m,
                BigDecimal deltaSod,
                Long currentVolume,
                BigDecimal dayAverageVolume,
                String reason,
                String message
        ) {
            this(
                    actionId,
                    timestamp,
                    legId,
                    legLabel,
                    actionType,
                    null,
                    oldQuantity,
                    exitedQuantity,
                    remainingQuantity,
                    0,
                    0,
                    entryPrice,
                    exitPrice,
                    bookedPnl,
                    delta2m,
                    delta5m,
                    deltaSod,
                    currentVolume,
                    dayAverageVolume,
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
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    reason,
                    message
            );
        }
    }
}
