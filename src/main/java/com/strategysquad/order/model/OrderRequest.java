package com.strategysquad.order.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrderRequest(
        UUID requestId,
        String positionSessionId,
        List<OrderLeg> legs,
        Instant requestedAt,
        String notes
) {
    public OrderRequest {
        requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        positionSessionId = requireNonBlank(positionSessionId, "positionSessionId");
        legs = List.copyOf(Objects.requireNonNull(legs, "legs must not be null"));
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("legs must not be empty");
        }
        notes = notes == null ? "" : notes.trim();
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
