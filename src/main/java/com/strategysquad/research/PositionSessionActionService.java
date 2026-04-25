package com.strategysquad.research;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies persisted exit and adjustment actions to a position session.
 */
public final class PositionSessionActionService {
    private static final int MAX_AUDIT_ENTRIES = 200;

    public PositionSessionSnapshot apply(PositionSessionSnapshot session, PositionSessionActionRequest request) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Instant actionTs = request.timestamp() == null ? Instant.now() : request.timestamp();
        Map<String, PositionSessionActionRequest.LegAction> actionsByLegId = new LinkedHashMap<>();
        for (PositionSessionActionRequest.LegAction action : request.legs()) {
            if (action == null || action.legId() == null || action.legId().isBlank()) {
                continue;
            }
            actionsByLegId.put(action.legId(), action);
        }

        List<PositionSessionSnapshot.PositionLegSnapshot> updatedLegs = new ArrayList<>(session.legs().size());
        List<PositionSessionSnapshot.PositionAuditEntry> newAuditEntries = new ArrayList<>();
        List<PositionSessionActionRequest.LegAction> unmatchedAdds = new ArrayList<>();
        int runningTotalLots = sessionTotalLots(session.underlying(), session.legs());

        for (PositionSessionSnapshot.PositionLegSnapshot leg : session.legs()) {
            PositionSessionActionRequest.LegAction action = actionsByLegId.get(leg.legId());
            if (action == null) {
                updatedLegs.add(leg);
                continue;
            }

            int oldQuantity = Math.max(0, leg.openQuantity());
            int addedQuantity = Math.max(0, action.addedQuantity());
            int exitedQuantity = Math.max(0, action.exitedQuantity());
            if (addedQuantity > 0 && exitedQuantity > 0) {
                throw new IllegalArgumentException("Add and exit cannot be applied together for " + leg.label());
            }
            if (exitedQuantity > oldQuantity) {
                throw new IllegalArgumentException("Exit quantity exceeds open quantity for " + leg.label());
            }
            if (exitedQuantity > 0 && action.exitPrice() == null) {
                throw new IllegalArgumentException("Exit price is required for exited quantity on " + leg.label());
            }
            if (addedQuantity > 0 && action.entryPrice() == null) {
                throw new IllegalArgumentException("Entry price is required for added quantity on " + leg.label());
            }

            int remainingQuantity = oldQuantity - exitedQuantity + addedQuantity;
            BigDecimal incrementalBookedPnl = exitedQuantity <= 0
                    ? BigDecimal.ZERO
                    : PositionPnlCalculator.bookedPnl(leg.side(), leg.entryPrice(), action.exitPrice(), exitedQuantity);
            BigDecimal totalBookedPnl = PositionPnlCalculator.safe(leg.bookedPnl()).add(incrementalBookedPnl);
            int updatedOriginalQuantity = Math.max(0, leg.originalQuantity()) + addedQuantity;
            BigDecimal updatedEntryPrice = addedQuantity <= 0
                    ? leg.entryPrice()
                    : weightedEntryPrice(leg.entryPrice(), oldQuantity, action.entryPrice(), addedQuantity);
            int totalLotsBefore = runningTotalLots;
            int totalLotsAfter = runningTotalLots + lotCountDelta(session.underlying(), oldQuantity, remainingQuantity);
            PositionSessionSnapshot.PositionLegSnapshot updatedLeg = new PositionSessionSnapshot.PositionLegSnapshot(
                    leg.legId(),
                    leg.label(),
                    leg.optionType(),
                    leg.side(),
                    leg.strike(),
                    action.expiryDate() == null || action.expiryDate().isBlank() ? leg.expiryDate() : action.expiryDate(),
                    action.symbol() == null || action.symbol().isBlank() ? leg.symbol() : action.symbol(),
                    action.instrumentId() == null || action.instrumentId().isBlank() ? leg.instrumentId() : action.instrumentId(),
                    updatedEntryPrice,
                    updatedOriginalQuantity,
                    remainingQuantity,
                    totalBookedPnl,
                    statusForLeg(updatedOriginalQuantity, remainingQuantity),
                    leg.createdAt(),
                    actionTs
            );
            updatedLegs.add(updatedLeg);
            newAuditEntries.add(new PositionSessionSnapshot.PositionAuditEntry(
                    UUID.randomUUID().toString(),
                    actionTs,
                    leg.legId(),
                    leg.label(),
                    request.actionType(),
                    action.adjustmentActionType(),
                    oldQuantity,
                    exitedQuantity,
                    remainingQuantity,
                    totalLotsBefore,
                    totalLotsAfter,
                    addedQuantity > 0 ? action.entryPrice() : leg.entryPrice(),
                    action.exitPrice(),
                    incrementalBookedPnl,
                    action.delta2m(),
                    action.delta5m(),
                    action.deltaSod(),
                    action.currentVolume(),
                    action.dayAverageVolume(),
                    action.triggerType(),
                    action.reasonCode(),
                    action.livePnlPoints(),
                    action.livePnlChange2mPoints(),
                    action.livePnlChange5mPoints(),
                    action.netDelta2m(),
                    action.netDelta5m(),
                    action.netDeltaSod(),
                    action.postAdjNetDelta(),
                    action.improvementAbsDelta(),
                    action.improvementRatio(),
                    action.underlyingDirection(),
                    action.profitAlignment(),
                    action.volumeConfirmed(),
                    action.volumeBypassed(),
                    action.thetaScore(),
                    action.liquidityScore(),
                    action.score(),
                    action.reason(),
                    action.message()
            ));
            runningTotalLots = totalLotsAfter;
        }

        for (PositionSessionActionRequest.LegAction action : request.legs()) {
            if (action == null) {
                continue;
            }
            String legId = action.legId();
            if (legId != null && !legId.isBlank() && actionsByLegId.containsKey(legId)
                    && session.legs().stream().noneMatch(leg -> legId.equals(leg.legId()))) {
                unmatchedAdds.add(action);
            } else if ((legId == null || legId.isBlank()) && Math.max(0, action.addedQuantity()) > 0) {
                unmatchedAdds.add(action);
            }
        }

        for (PositionSessionActionRequest.LegAction action : unmatchedAdds) {
            int addedQuantity = Math.max(0, action.addedQuantity());
            if (addedQuantity <= 0) {
                continue;
            }
            if (action.entryPrice() == null) {
                throw new IllegalArgumentException("Entry price is required for added quantity on new leg");
            }
            if (action.label() == null || action.label().isBlank()
                    || action.optionType() == null || action.optionType().isBlank()
                    || action.side() == null || action.side().isBlank()
                    || action.strike() == null) {
                throw new IllegalArgumentException("New add action is missing leg identity fields");
            }
            String legId = action.legId() == null || action.legId().isBlank()
                    ? UUID.randomUUID().toString()
                    : action.legId();
            int totalLotsBefore = runningTotalLots;
            int totalLotsAfter = runningTotalLots + lotCountDelta(session.underlying(), 0, addedQuantity);
            PositionSessionSnapshot.PositionLegSnapshot newLeg = new PositionSessionSnapshot.PositionLegSnapshot(
                    legId,
                    action.label(),
                    action.optionType(),
                    action.side(),
                    action.strike(),
                    action.expiryDate() == null ? "" : action.expiryDate(),
                    action.symbol() == null ? "" : action.symbol(),
                    action.instrumentId() == null ? "" : action.instrumentId(),
                    action.entryPrice(),
                    addedQuantity,
                    addedQuantity,
                    BigDecimal.ZERO,
                    statusForLeg(addedQuantity, addedQuantity),
                    actionTs,
                    actionTs
            );
            updatedLegs.add(newLeg);
            newAuditEntries.add(new PositionSessionSnapshot.PositionAuditEntry(
                    UUID.randomUUID().toString(),
                    actionTs,
                    legId,
                    action.label(),
                    request.actionType(),
                    action.adjustmentActionType(),
                    0,
                    0,
                    addedQuantity,
                    totalLotsBefore,
                    totalLotsAfter,
                    action.entryPrice(),
                    null,
                    BigDecimal.ZERO,
                    action.delta2m(),
                    action.delta5m(),
                    action.deltaSod(),
                    action.currentVolume(),
                    action.dayAverageVolume(),
                    action.triggerType(),
                    action.reasonCode(),
                    action.livePnlPoints(),
                    action.livePnlChange2mPoints(),
                    action.livePnlChange5mPoints(),
                    action.netDelta2m(),
                    action.netDelta5m(),
                    action.netDeltaSod(),
                    action.postAdjNetDelta(),
                    action.improvementAbsDelta(),
                    action.improvementRatio(),
                    action.underlyingDirection(),
                    action.profitAlignment(),
                    action.volumeConfirmed(),
                    action.volumeBypassed(),
                    action.thetaScore(),
                    action.liquidityScore(),
                    action.score(),
                    action.reason(),
                    action.message()
            ));
            runningTotalLots = totalLotsAfter;
        }

        List<PositionSessionSnapshot.PositionAuditEntry> auditLog = new ArrayList<>(newAuditEntries.size() + session.auditLog().size());
        auditLog.addAll(newAuditEntries);
        auditLog.addAll(session.auditLog());
        if (auditLog.size() > MAX_AUDIT_ENTRIES) {
            auditLog = new ArrayList<>(auditLog.subList(0, MAX_AUDIT_ENTRIES));
        }

        return new PositionSessionSnapshot(
                session.sessionId(),
                session.mode(),
                session.strategyLabel(),
                session.orientation(),
                session.underlying(),
                session.expiryType(),
                session.timeframe(),
                session.dte(),
                session.spot(),
                session.scenarioQty(),
                session.createdAt(),
                actionTs,
                request.lastDeltaAdjustmentTs() == null ? session.lastDeltaAdjustmentTs() : request.lastDeltaAdjustmentTs(),
                statusForSession(updatedLegs),
                updatedLegs,
                auditLog
        );
    }

    private static BigDecimal weightedEntryPrice(
            BigDecimal existingEntryPrice,
            int existingOpenQuantity,
            BigDecimal addEntryPrice,
            int addedQuantity
    ) {
        int safeExistingQuantity = Math.max(0, existingOpenQuantity);
        int safeAddedQuantity = Math.max(0, addedQuantity);
        if (safeAddedQuantity <= 0) {
            return existingEntryPrice;
        }
        if (safeExistingQuantity <= 0 || existingEntryPrice == null) {
            return addEntryPrice;
        }
        BigDecimal existingValue = existingEntryPrice.multiply(BigDecimal.valueOf(safeExistingQuantity));
        BigDecimal addedValue = addEntryPrice.multiply(BigDecimal.valueOf(safeAddedQuantity));
        return existingValue.add(addedValue)
                .divide(BigDecimal.valueOf(safeExistingQuantity + safeAddedQuantity), java.math.MathContext.DECIMAL64);
    }

    private static int sessionTotalLots(String underlying, List<PositionSessionSnapshot.PositionLegSnapshot> legs) {
        return legs.stream()
                .mapToInt(leg -> lotCount(underlying, leg.openQuantity()))
                .sum();
    }

    private static int lotCountDelta(String underlying, int oldQuantity, int newQuantity) {
        return lotCount(underlying, newQuantity) - lotCount(underlying, oldQuantity);
    }

    private static int lotCount(String underlying, int quantity) {
        return quantity <= 0 ? 0 : LotSizingRules.lotCount(underlying, quantity);
    }

    static String statusForLeg(int originalQuantity, int openQuantity) {
        if (openQuantity <= 0) {
            return "CLOSED";
        }
        if (openQuantity < Math.max(0, originalQuantity)) {
            return "PARTIALLY_EXITED";
        }
        return "OPEN";
    }

    static String statusForSession(List<PositionSessionSnapshot.PositionLegSnapshot> legs) {
        boolean anyOpen = false;
        boolean anyExited = false;
        for (PositionSessionSnapshot.PositionLegSnapshot leg : legs) {
            if (leg.openQuantity() > 0) {
                anyOpen = true;
            }
            if (leg.openQuantity() < Math.max(0, leg.originalQuantity())) {
                anyExited = true;
            }
        }
        if (!anyOpen) {
            return "CLOSED";
        }
        return anyExited ? "PARTIALLY_EXITED" : "OPEN";
    }
}
