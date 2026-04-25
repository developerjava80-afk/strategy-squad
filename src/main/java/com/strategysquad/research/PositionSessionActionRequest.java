package com.strategysquad.research;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Mutable position-session action request applied by the backend so booked PnL
 * and audit state stay consistent across refreshes.
 */
public record PositionSessionActionRequest(
        String sessionId,
        String actionType,
        Instant timestamp,
        Instant lastDeltaAdjustmentTs,
        List<LegAction> legs
) {
    public PositionSessionActionRequest {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    public record LegAction(
            String legId,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            int addedQuantity,
            int exitedQuantity,
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
            String adjustmentActionType,
            String label,
            String optionType,
            String side,
            BigDecimal strike,
            String reason,
            String message,
            String symbol,
            String instrumentId,
            String expiryDate
    ) {
        public LegAction(
                String legId,
                BigDecimal entryPrice,
                BigDecimal exitPrice,
                int addedQuantity,
                int exitedQuantity,
                BigDecimal delta2m,
                BigDecimal delta5m,
                BigDecimal deltaSod,
                Long currentVolume,
                BigDecimal dayAverageVolume,
                String adjustmentActionType,
                String label,
                String optionType,
                String side,
                BigDecimal strike,
                String reason,
                String message,
                String symbol,
                String instrumentId,
                String expiryDate
        ) {
            this(
                    legId,
                    entryPrice,
                    exitPrice,
                    addedQuantity,
                    exitedQuantity,
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
                    adjustmentActionType,
                    label,
                    optionType,
                    side,
                    strike,
                    reason,
                    message,
                    symbol,
                    instrumentId,
                    expiryDate
            );
        }
    }
}
