package com.strategysquad.order.broker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.strategysquad.ingestion.kite.KiteLiveSessionManager;
import com.strategysquad.ingestion.kite.live.auth.KiteCredentials;
import com.strategysquad.order.model.OrderLeg;
import com.strategysquad.order.model.OrderRequest;
import com.strategysquad.order.model.OrderResult;
import com.strategysquad.order.model.OrderStatus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class KiteBrokerAdapter implements BrokerAdapter {
    private static final String ORDER_URL = "https://api.kite.trade/orders/regular";
    private static final String ORDER_HISTORY_URL = "https://api.kite.trade/orders/";

    private final KiteLiveSessionManager sessionManager;
    private final HttpClient httpClient;
    private final Gson gson;

    public KiteBrokerAdapter(KiteLiveSessionManager sessionManager) {
        this(
                sessionManager,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new Gson()
        );
    }

    KiteBrokerAdapter(KiteLiveSessionManager sessionManager, HttpClient httpClient, Gson gson) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.gson = Objects.requireNonNull(gson, "gson must not be null");
    }

    @Override
    public OrderResult placeOrder(OrderRequest request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request must not be null");
        OrderLeg leg = firstLeg(request);
        KiteCredentials credentials = credentials();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("exchange", "NFO");
        // Instrument id is expected to be the broker trading symbol for this adapter.
        params.put("tradingsymbol", leg.instrumentId());
        params.put("transaction_type", leg.action().name());
        params.put("quantity", Integer.toString(leg.quantity()));
        params.put("product", leg.product());
        params.put("order_type", leg.limitPrice() > 0.0d ? "LIMIT" : "MARKET");
        params.put("validity", "DAY");
        params.put("tag", "strategy-squad");
        if (leg.limitPrice() > 0.0d) {
            params.put("price", Double.toString(leg.limitPrice()));
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(ORDER_URL))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Kite order placement failed: HTTP " + response.statusCode());
        }

        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        JsonObject data = root == null ? null : root.getAsJsonObject("data");
        String orderId = data != null && data.has("order_id") ? data.get("order_id").getAsString() : null;
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalStateException("Kite order placement did not return an order_id");
        }

        BrokerSnapshot snapshot = loadOrderSnapshot(credentials, orderId);
        return new OrderResult(
                request.requestId(),
                orderId,
                snapshot != null ? snapshot.status : OrderStatus.SENT,
                snapshot != null ? snapshot.averagePrice : 0.0d,
                Instant.now(),
                null
        );
    }

    @Override
    public void cancelOrder(String brokerOrderId) throws IOException, InterruptedException {
        String orderId = requireNonBlank(brokerOrderId, "brokerOrderId");
        KiteCredentials credentials = credentials();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ORDER_URL + "/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8)))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken())
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Kite order cancel failed: HTTP " + response.statusCode());
        }
    }

    @Override
    public OrderStatus getOrderStatus(String brokerOrderId) throws IOException, InterruptedException {
        String orderId = requireNonBlank(brokerOrderId, "brokerOrderId");
        KiteCredentials credentials = credentials();

        BrokerSnapshot snapshot = loadOrderSnapshot(credentials, orderId);
        return snapshot == null ? OrderStatus.PENDING : snapshot.status;
    }

    private BrokerSnapshot loadOrderSnapshot(KiteCredentials credentials, String orderId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ORDER_HISTORY_URL + URLEncoder.encode(orderId, StandardCharsets.UTF_8)))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken())
                .GET()
                .timeout(Duration.ofSeconds(12))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        JsonArray data = root == null ? null : root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            return null;
        }

        JsonObject latest = data.get(data.size() - 1).getAsJsonObject();
        String kiteStatus = latest.has("status") && !latest.get("status").isJsonNull()
                ? latest.get("status").getAsString()
                : "";
        double averagePrice = latest.has("average_price") && !latest.get("average_price").isJsonNull()
                ? latest.get("average_price").getAsDouble()
                : 0.0d;

        return new BrokerSnapshot(mapStatus(kiteStatus), averagePrice);
    }

    private KiteCredentials credentials() {
        return sessionManager.currentCredentials()
                .orElseThrow(() -> new IllegalStateException("Kite session is not authenticated"));
    }

    private static OrderLeg firstLeg(OrderRequest request) {
        if (request.legs().isEmpty()) {
            throw new IllegalArgumentException("Order request must contain at least one leg");
        }
        return request.legs().get(0);
    }

    private static String formEncode(Map<String, String> params) {
        List<String> parts = new ArrayList<>(params.size());
        params.forEach((key, value) -> parts.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        ));
        return String.join("&", parts);
    }

    private static OrderStatus mapStatus(String kiteStatus) {
        String normalized = kiteStatus == null ? "" : kiteStatus.trim().toUpperCase();
        return switch (normalized) {
            case "COMPLETE" -> OrderStatus.FILLED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "CANCELLED", "CANCELED" -> OrderStatus.CANCELLED;
            case "OPEN", "TRIGGER PENDING" -> OrderStatus.SENT;
            default -> OrderStatus.SENT;
        };
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private record BrokerSnapshot(OrderStatus status, double averagePrice) {
    }
}
