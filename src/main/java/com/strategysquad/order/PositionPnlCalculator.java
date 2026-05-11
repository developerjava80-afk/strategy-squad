package com.strategysquad.order;

import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.order.model.PnlSnapshot;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shared position PnL math for live and booked option legs.
 */
public final class PositionPnlCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private PositionPnlCalculator() {
    }

    public static BigDecimal bookedPnl(String side, BigDecimal entryPrice, BigDecimal exitPrice, int exitedQuantity) {
        if (exitedQuantity <= 0) {
            return ZERO;
        }
        BigDecimal safeEntry = entryPrice == null ? ZERO : entryPrice;
        BigDecimal safeExit = exitPrice == null ? ZERO : exitPrice;
        BigDecimal delta = "SHORT".equalsIgnoreCase(side)
                ? safeEntry.subtract(safeExit)
                : safeExit.subtract(safeEntry);
        return delta.multiply(BigDecimal.valueOf(exitedQuantity));
    }

    public static BigDecimal livePnl(String side, BigDecimal entryPrice, BigDecimal currentPrice, int openQuantity) {
        if (openQuantity <= 0) {
            return ZERO;
        }
        BigDecimal safeEntry = entryPrice == null ? ZERO : entryPrice;
        BigDecimal safeCurrent = currentPrice == null ? ZERO : currentPrice;
        BigDecimal delta = "SHORT".equalsIgnoreCase(side)
                ? safeEntry.subtract(safeCurrent)
                : safeCurrent.subtract(safeEntry);
        return delta.multiply(BigDecimal.valueOf(openQuantity));
    }

    public static BigDecimal totalPnl(BigDecimal bookedPnl, BigDecimal livePnl) {
        return safe(bookedPnl).add(safe(livePnl));
    }

    public static BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    public static PnlSnapshot markToMarket(
            PositionSessionSnapshot positionSession,
            LiveSessionState liveSessionState
    ) {
        if (positionSession == null) {
            throw new IllegalArgumentException("positionSession must not be null");
        }
        if (liveSessionState == null) {
            throw new IllegalArgumentException("liveSessionState must not be null");
        }

        BigDecimal unrealized = ZERO;
        BigDecimal realized = ZERO;
        BigDecimal totalPremiumReceived = ZERO;
        BigDecimal currentStructureValue = ZERO;

        for (PositionSessionSnapshot.PositionLegSnapshot leg : positionSession.legs()) {
            if (leg == null) {
                continue;
            }

            BigDecimal entry = safe(leg.entryPrice());
            BigDecimal booked = safe(leg.bookedPnl());
            realized = realized.add(booked);

            boolean isShort = "SHORT".equalsIgnoreCase(leg.side());
            if (isShort && leg.originalQuantity() > 0) {
                totalPremiumReceived = totalPremiumReceived.add(
                        entry.multiply(BigDecimal.valueOf(leg.originalQuantity()))
                );
            }

            if (leg.openQuantity() <= 0) {
                continue;
            }

            LiveSessionState.OptionQuote liveQuote = liveSessionState.getLatestQuote(leg.instrumentId());
            BigDecimal current = liveQuote != null && liveQuote.lastPrice() != null
                    ? liveQuote.lastPrice()
                    : entry;

            BigDecimal liveLegPnl = livePnl(leg.side(), entry, current, leg.openQuantity());
            unrealized = unrealized.add(liveLegPnl);

            BigDecimal legValue = current.multiply(BigDecimal.valueOf(leg.openQuantity()));
            currentStructureValue = currentStructureValue.add(isShort ? legValue.negate() : legValue);
        }

        return new PnlSnapshot(
                unrealized.doubleValue(),
                realized.doubleValue(),
                totalPremiumReceived.doubleValue(),
                currentStructureValue.doubleValue(),
                Math.max(0, positionSession.scenarioQty()),
                Instant.now()
        );
    }
}

