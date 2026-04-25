package com.strategysquad.research;

import java.util.Locale;

/**
 * Central lot-size rules for supported NSE index underlyings.
 */
public final class LotSizingRules {
    public static final int NIFTY_LOT_SIZE = 65;
    public static final int BANKNIFTY_LOT_SIZE = 30;

    private LotSizingRules() {
    }

    public static int lotSizeForUnderlying(String underlying) {
        String normalized = normalizeUnderlying(underlying);
        return switch (normalized) {
            case "NIFTY" -> NIFTY_LOT_SIZE;
            case "BANKNIFTY" -> BANKNIFTY_LOT_SIZE;
            default -> throw new IllegalArgumentException("Unsupported underlying for lot sizing: " + underlying);
        };
    }

    public static int normalizeQuantity(String underlying, Integer quantity) {
        int lotSize = lotSizeForUnderlying(underlying);
        if (quantity == null) {
            return lotSize;
        }
        validateQuantity(underlying, quantity);
        return quantity;
    }

    public static int normalizeOpenQuantity(String underlying, Integer quantity) {
        if (quantity == null) {
            return lotSizeForUnderlying(underlying);
        }
        validateOpenQuantity(underlying, quantity);
        return quantity;
    }

    public static void validateQuantity(String underlying, int quantity) {
        int lotSize = lotSizeForUnderlying(underlying);
        if (quantity < lotSize) {
            throw new IllegalArgumentException("Qty must be at least one lot of " + lotSize + " for " + normalizeUnderlying(underlying));
        }
        if ((quantity % lotSize) != 0) {
            throw new IllegalArgumentException("Qty must be a multiple of " + lotSize + " for " + normalizeUnderlying(underlying));
        }
    }

    public static void validateOpenQuantity(String underlying, int quantity) {
        if (quantity == 0) {
            return;
        }
        validateQuantity(underlying, quantity);
    }

    public static int lotCount(String underlying, Integer quantity) {
        return normalizeOpenQuantity(underlying, quantity) / lotSizeForUnderlying(underlying);
    }

    private static String normalizeUnderlying(String underlying) {
        return String.valueOf(underlying).trim().toUpperCase(Locale.ROOT);
    }
}
