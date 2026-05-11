package com.strategysquad.order;

import com.strategysquad.order.model.OrderStatus;

import java.util.Objects;

public final class OrderStateMachine {

    public OrderStatus transition(OrderStatus current, OrderStatus next) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(next, "next must not be null");

        if (isValidTransition(current, next)) {
            return next;
        }
        throw new IllegalStateException("Cannot transition from " + current + " to " + next);
    }

    private static boolean isValidTransition(OrderStatus current, OrderStatus next) {
        return switch (current) {
            case PENDING -> next == OrderStatus.SENT;
            case SENT -> next == OrderStatus.FILLED || next == OrderStatus.REJECTED;
            case FILLED -> next == OrderStatus.CANCELLED;
            case REJECTED, CANCELLED -> false;
        };
    }
}
