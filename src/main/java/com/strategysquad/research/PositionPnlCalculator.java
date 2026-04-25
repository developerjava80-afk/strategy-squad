package com.strategysquad.research;

import java.math.BigDecimal;

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
}
