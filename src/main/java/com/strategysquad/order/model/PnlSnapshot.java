package com.strategysquad.order.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Mark-to-market PnL snapshot for an active position session.
 *
 * @param unrealizedPnl points, summed across open legs using live prices
 * @param realizedPnl points, already booked across legs
 * @param totalPremiumReceived points, premium collected from short entries
 * @param currentStructureValue points, signed current value of open legs
 * @param lotSize lots for the position session
 * @param timestamp exchange/system timestamp for this snapshot
 */
public record PnlSnapshot(
        double unrealizedPnl,
        double realizedPnl,
        double totalPremiumReceived,
        double currentStructureValue,
        int lotSize,
        Instant timestamp
) {
    public PnlSnapshot {
        if (lotSize < 0) {
            throw new IllegalArgumentException("lotSize must not be negative");
        }
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}
