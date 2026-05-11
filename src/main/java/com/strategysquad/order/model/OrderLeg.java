package com.strategysquad.order.model;

import java.util.Objects;

public record OrderLeg(
        String instrumentId,
        Action action,
        int quantity,
        double limitPrice,
        String product
) {
    public enum Action {
        BUY,
        SELL
    }

    public OrderLeg {
        instrumentId = requireNonBlank(instrumentId, "instrumentId");
        action = Objects.requireNonNull(action, "action must not be null");
        product = requireNonBlank(product, "product");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
        if (limitPrice < 0.0d) {
            throw new IllegalArgumentException("limitPrice must not be negative");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
