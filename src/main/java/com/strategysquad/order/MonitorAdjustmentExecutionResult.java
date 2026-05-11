package com.strategysquad.order;

import java.math.BigDecimal;
import java.time.Instant;

public record MonitorAdjustmentExecutionResult(
        Status status,
        String strategyId,
        String sessionId,
        String legId,
        String executionId,
        String action,
        String brokerOrderId,
        int requestedQuantity,
        int filledQuantity,
        int lotsBefore,
        int lotsAfter,
        BigDecimal fillPrice,
        BigDecimal bookedPnl,
        Instant timestamp,
        String failureReason,
        String message
) {
    public enum Status {
        SIMULATED_FILLED,
        FILLED,
        PENDING_APPROVAL,
        SENT,
        REJECTED,
        CANCELLED,
        FAILED
    }

    public boolean filled() {
        return status == Status.SIMULATED_FILLED || status == Status.FILLED;
    }

    public boolean pending() {
        return status == Status.PENDING_APPROVAL || status == Status.SENT;
    }
}