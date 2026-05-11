package com.strategysquad.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable execution intent emitted by the monitor; execution is handled by the order layer.
 *
 * <p>Use the static factory methods {@link #forReduce} and {@link #forEnter} rather than
 * the canonical constructor directly — they supply sensible defaults for unused fields.
 *
 * <h2>REDUCE fields</h2>
 * <ul>
 *   <li>{@code legId} — id of the existing session leg to close (required)</li>
 *   <li>{@code reduceQuantity} — lots to close (must be &gt; 0)</li>
 *   <li>{@code currentLtp} — last traded price used as the fill price</li>
 * </ul>
 *
 * <h2>ENTER fields</h2>
 * <ul>
 *   <li>{@code enterUnderlying}, {@code enterExpiry}, {@code enterStrike}, {@code enterOptionType} — contract to open</li>
 *   <li>{@code enterTransactionType} — BUY or SELL</li>
 *   <li>{@code enterLots} — lots to open (must be &gt; 0)</li>
 *   <li>{@code enterMode} — "paper" or "real"</li>
 *   <li>{@code enterStrategyId/Type/Label} — optional strategy grouping metadata</li>
 * </ul>
 */
public record MonitorAdjustmentIntent(
        String strategyId,
        String sessionId,
        // --- REDUCE fields ---
        String legId,
        Action action,
        int reduceQuantity,
        String reason,
        BigDecimal currentLtp,
        Instant timestamp,
        // --- ENTER fields (null / 0 for REDUCE) ---
        String enterUnderlying,
        String enterExpiry,
        BigDecimal enterStrike,
        String enterOptionType,
        String enterTransactionType,
        int enterLots,
        String enterMode,
        String enterStrategyId,
        String enterStrategyType,
        String enterStrategyLabel
) {
    public enum Action {
        /** Close (buy-back) lots on an existing short leg. */
        REDUCE,
        /** Open new lots on a new or existing leg. */
        ENTER
    }

    public MonitorAdjustmentIntent {
        action = Objects.requireNonNull(action, "action must not be null");
        strategyId = normalize(strategyId);
        sessionId = requireNonBlank(sessionId, "sessionId");
        reason = normalize(reason);
        timestamp = timestamp == null ? Instant.now() : timestamp;

        if (action == Action.REDUCE) {
            legId = requireNonBlank(legId, "legId");
            if (reduceQuantity <= 0) {
                throw new IllegalArgumentException("reduceQuantity must be greater than zero");
            }
        } else if (action == Action.ENTER) {
            requireNonBlankStatic(enterUnderlying, "enterUnderlying");
            requireNonBlankStatic(enterExpiry, "enterExpiry");
            Objects.requireNonNull(enterStrike, "enterStrike must not be null");
            requireNonBlankStatic(enterOptionType, "enterOptionType");
            requireNonBlankStatic(enterTransactionType, "enterTransactionType");
            if (enterLots <= 0) {
                throw new IllegalArgumentException("enterLots must be greater than zero");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a REDUCE intent — closes {@code quantity} lots on an existing session leg.
     */
    public static MonitorAdjustmentIntent forReduce(
            String strategyId, String sessionId, String legId,
            int quantity, String reason, BigDecimal ltp, Instant ts) {
        return new MonitorAdjustmentIntent(
                strategyId, sessionId, legId, Action.REDUCE,
                quantity, reason, ltp, ts,
                null, null, null, null, null, 0, null, null, null, null);
    }

    /**
     * Creates an ENTER intent — opens new lots via {@code OptionOrderService.placeOrder()}.
     *
     * @param transactionType "BUY" or "SELL"
     * @param mode "paper" or "real"
     */
    public static MonitorAdjustmentIntent forEnter(
            String strategyId, String sessionId,
            String underlying, String expiry, BigDecimal strike, String optionType,
            String transactionType, int lots, String mode, BigDecimal ltp, String reason,
            Instant ts, String enterStrategyId, String enterStrategyType, String enterStrategyLabel) {
        return new MonitorAdjustmentIntent(
                strategyId, sessionId, null, Action.ENTER,
                0, reason, ltp, ts,
                underlying, expiry, strike, optionType, transactionType, lots, mode,
                enterStrategyId, enterStrategyType, enterStrategyLabel);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String requireNonBlank(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static void requireNonBlankStatic(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}