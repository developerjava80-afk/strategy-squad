package com.strategysquad.order.broker;

import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.order.model.OrderLeg;
import com.strategysquad.order.model.OrderRequest;
import com.strategysquad.order.model.OrderResult;
import com.strategysquad.order.model.OrderStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PaperBrokerAdapter implements BrokerAdapter {

    private final LiveSessionState liveSessionState;
    private final ConcurrentHashMap<String, OrderStatus> statusByBrokerOrderId = new ConcurrentHashMap<>();

    public PaperBrokerAdapter(LiveSessionState liveSessionState) {
        this.liveSessionState = Objects.requireNonNull(liveSessionState, "liveSessionState must not be null");
    }

    @Override
    public OrderResult placeOrder(OrderRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.legs().isEmpty()) {
            throw new IllegalArgumentException("Order request must contain at least one leg");
        }

        OrderLeg firstLeg = request.legs().get(0);
        LiveSessionState.OptionQuote quote = liveSessionState.getLatestQuote(firstLeg.instrumentId());
        BigDecimal lastPrice = quote != null ? quote.lastPrice() : null;
        if (lastPrice == null) {
            throw new IllegalStateException("Live lastPrice unavailable for instrument: " + firstLeg.instrumentId());
        }

        String brokerOrderId = "paper-" + UUID.randomUUID();
        statusByBrokerOrderId.put(brokerOrderId, OrderStatus.FILLED);

        return new OrderResult(
                request.requestId(),
                brokerOrderId,
                OrderStatus.FILLED,
                lastPrice.doubleValue(),
                Instant.now(),
                null
        );
    }

    @Override
    public void cancelOrder(String brokerOrderId) {
        String orderId = requireNonBlank(brokerOrderId, "brokerOrderId");
        statusByBrokerOrderId.computeIfPresent(orderId, (id, status) -> {
            if (status == OrderStatus.REJECTED || status == OrderStatus.CANCELLED) {
                return status;
            }
            return OrderStatus.CANCELLED;
        });
    }

    @Override
    public OrderStatus getOrderStatus(String brokerOrderId) {
        String orderId = requireNonBlank(brokerOrderId, "brokerOrderId");
        return statusByBrokerOrderId.getOrDefault(orderId, OrderStatus.PENDING);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
