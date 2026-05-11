package com.strategysquad.order.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OrderResult(
        UUID requestId,
        String brokerOrderId,
        OrderStatus status,
        double filledPrice,
        Instant filledAt,
        String errorMessage
) {
    public OrderResult {
        requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if (filledPrice < 0.0d) {
            throw new IllegalArgumentException("filledPrice must not be negative");
        }
        brokerOrderId = normalizeNullable(brokerOrderId);
        errorMessage = normalizeNullable(errorMessage);
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
