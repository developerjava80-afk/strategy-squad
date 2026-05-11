package com.strategysquad.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Persisted position session for the scenario research console.
 *
 * <h2>Adjustment state fields</h2>
 * <ul>
 *   <li>{@link #lastAdjustmentTs()} — UTC instant of the last applied adjustment
 *       (reduce, shift, add, hedge). Used as the cooldown reference by the
 *       autonomous adjustment engine. Null when no adjustment has been applied.</li>
 *   <li>{@link #lastAdjustmentPnlPerLot()} — live PnL per lot at the time of the
 *       last adjustment. Used to compute PnL deterioration for emergency cooldown
 *       bypass. Null when no adjustment has been applied.</li>
 *   <li>{@link #hedgePremiumPaidCe()} — total premium paid for CE hedge lots since
 *       session open. Deducted from booked PnL when hedge legs are exited or expire.</li>
 *   <li>{@link #hedgePremiumPaidPe()} — total premium paid for PE hedge lots since
 *       session open.</li>
 * </ul>
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
        List<PositionAuditEntry> auditLog,

        /**
         * UTC instant of the last autonomously applied adjustment (reduce, shift,
         * add lots, or add hedge). Null when no adjustment has yet been applied.
         * This is the source of truth for cooldown — never stored in agent memory.
         */
        Instant lastAdjustmentTs,

        /**
         * Live PnL per lot (across all open short legs) at the time of the last
         * adjustment. Used to measure PnL deterioration for emergency bypass.
         * Units: NSE index points per contract-unit. Null when no adjustment applied.
         */
        BigDecimal lastAdjustmentPnlPerLot,

        /**
         * Cumulative premium paid for CE-side hedge (LONG) lots since session open.
         * Units: NSE index points × contracts. Zero when no CE hedge has been added.
         */
        BigDecimal hedgePremiumPaidCe,

        /**
         * Cumulative premium paid for PE-side hedge (LONG) lots since session open.
         * Units: NSE index points × contracts. Zero when no PE hedge has been added.
         */
        BigDecimal hedgePremiumPaidPe
) {
    public PositionSessionSnapshot {
        legs = legs == null ? List.of() : List.copyOf(legs);
        auditLog = auditLog == null ? List.of() : List.copyOf(auditLog);
        hedgePremiumPaidCe = hedgePremiumPaidCe == null ? BigDecimal.ZERO : hedgePremiumPaidCe;
        hedgePremiumPaidPe = hedgePremiumPaidPe == null ? BigDecimal.ZERO : hedgePremiumPaidPe;
    }

    /**
     * Convenience factory: creates a session with no adjustment state (fresh entry).
     * Use this when constructing a session from a new {@code PositionPlan} so that
     * callers don't need to supply all the new nullable fields explicitly.
     */
    public static PositionSessionSnapshot withNoAdjustmentState(
            String sessionId, String mode, String strategyLabel, String orientation,
            String underlying, String expiryType, String timeframe, int dte,
            BigDecimal spot, int scenarioQty, Instant createdAt, Instant updatedAt,
            Instant lastDeltaAdjustmentTs, String status,
            List<PositionLegSnapshot> legs, List<PositionAuditEntry> auditLog) {
        return new PositionSessionSnapshot(
                sessionId, mode, strategyLabel, orientation, underlying, expiryType,
                timeframe, dte, spot, scenarioQty, createdAt, updatedAt,
                lastDeltaAdjustmentTs, status, legs, auditLog,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Returns a copy of this session with {@link #lastAdjustmentTs()} and
     * {@link #lastAdjustmentPnlPerLot()} updated. Used by the adjustment engine
     * to persist cooldown state inside the session record after every action.
     */
    public PositionSessionSnapshot withAdjustmentState(
            Instant adjustmentTs, BigDecimal pnlPerLot) {
        return new PositionSessionSnapshot(
                sessionId, mode, strategyLabel, orientation, underlying, expiryType,
                timeframe, dte, spot, scenarioQty, createdAt, updatedAt,
                lastDeltaAdjustmentTs, status, legs, auditLog,
                adjustmentTs, pnlPerLot, hedgePremiumPaidCe, hedgePremiumPaidPe);
    }

    /**
     * Returns a copy of this session with hedge premium accumulators updated.
     * {@code optionType} must be {@code "CE"} or {@code "PE"}.
     */
    public PositionSessionSnapshot withAddedHedgePremium(String optionType, BigDecimal premiumPaid) {
        BigDecimal newCe = hedgePremiumPaidCe;
        BigDecimal newPe = hedgePremiumPaidPe;
        if ("CE".equals(optionType)) {
            newCe = newCe.add(premiumPaid == null ? BigDecimal.ZERO : premiumPaid);
        } else {
            newPe = newPe.add(premiumPaid == null ? BigDecimal.ZERO : premiumPaid);
        }
        return new PositionSessionSnapshot(
                sessionId, mode, strategyLabel, orientation, underlying, expiryType,
                timeframe, dte, spot, scenarioQty, createdAt, updatedAt,
                lastDeltaAdjustmentTs, status, legs, auditLog,
                lastAdjustmentTs, lastAdjustmentPnlPerLot, newCe, newPe);
    }

    /**
     * Leg role tag — distinguishes premium-collection legs from hedge legs.
     * Stored in {@link PositionLegSnapshot#legRole()}.
     */
    public enum LegRole {
        /** Short option collecting premium (core position leg). */
        SHORT_PREMIUM,
        /** Long option purchased as a directional hedge (CE or PE side). */
        LONG_HEDGE
    }

    public record PositionLegSnapshot(
            String legId,
            String label,
            String optionType,
            /** {@code "SHORT"} or {@code "LONG"} — matches {@code PositionPlanLeg.Side}. */
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
            Instant updatedAt,
            /** Optional order/execution store identifier. Never interchangeable with {@link #legId()}. */
            String executionId,
            /**
             * Role of this leg in the structure. {@code "SHORT_PREMIUM"} for the core
             * short legs; {@code "LONG_HEDGE"} for hedge legs added by the adjustment
             * engine. Null for legacy legs created before this field existed.
             */
            String legRole
    ) {
        /** Convenience constructor for legacy callers that do not set {@code legRole}. */
        public PositionLegSnapshot(
                String legId, String label, String optionType, String side,
                BigDecimal strike, String expiryDate, String symbol, String instrumentId,
                BigDecimal entryPrice, int originalQuantity, int openQuantity,
                BigDecimal bookedPnl, String status, Instant createdAt, Instant updatedAt) {
            this(legId, label, optionType, side, strike, expiryDate, symbol, instrumentId,
                 entryPrice, originalQuantity, openQuantity, bookedPnl, status,
                 createdAt, updatedAt, null, null);
        }

        /** Compatibility constructor for callers that set role but not execution id. */
        public PositionLegSnapshot(
                String legId, String label, String optionType, String side,
                BigDecimal strike, String expiryDate, String symbol, String instrumentId,
                BigDecimal entryPrice, int originalQuantity, int openQuantity,
                BigDecimal bookedPnl, String status, Instant createdAt, Instant updatedAt,
                String legRole) {
            this(legId, label, optionType, side, strike, expiryDate, symbol, instrumentId,
                 entryPrice, originalQuantity, openQuantity, bookedPnl, status,
                 createdAt, updatedAt, null, legRole);
        }

        /** Returns true when this leg is a hedge (LONG side). */
        public boolean isHedgeLeg() {
            return LegRole.LONG_HEDGE.name().equals(legRole) || "LONG".equalsIgnoreCase(side);
        }

        /** Returns true when this leg is a short premium-collection leg. */
        public boolean isShortLeg() {
            return !isHedgeLeg();
        }
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

