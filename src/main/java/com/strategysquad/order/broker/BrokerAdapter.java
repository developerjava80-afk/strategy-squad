package com.strategysquad.order.broker;

import com.strategysquad.order.model.OrderRequest;
import com.strategysquad.order.model.OrderResult;
import com.strategysquad.order.model.OrderStatus;

import java.io.IOException;

public interface BrokerAdapter {

    OrderResult placeOrder(OrderRequest request) throws IOException, InterruptedException;

    void cancelOrder(String brokerOrderId) throws IOException, InterruptedException;

    OrderStatus getOrderStatus(String brokerOrderId) throws IOException, InterruptedException;
}
