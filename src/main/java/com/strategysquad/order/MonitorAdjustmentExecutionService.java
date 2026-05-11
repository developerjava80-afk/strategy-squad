package com.strategysquad.order;

import com.strategysquad.order.broker.BrokerAdapter;
import com.strategysquad.order.model.OrderLeg;
import com.strategysquad.order.model.OrderRequest;
import com.strategysquad.order.model.OrderResult;
import com.strategysquad.order.model.OrderStatus;
import com.strategysquad.platform.config.AppConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Executes monitor adjustment intents after the monitor has made a decision.
 */
public final class MonitorAdjustmentExecutionService {
    private static final Logger LOG = Logger.getLogger(MonitorAdjustmentExecutionService.class.getName());
    private static final String REASON_EXECUTION_ID_MISSING = "EXECUTION_ID_MISSING";
    private static final String REASON_EXECUTION_NOT_FOUND = "EXECUTION_NOT_FOUND";
    private static final String REASON_MISSING_FILL_PRICE = "MISSING_FILL_PRICE";
    private static final String REASON_INVALID_FILL_QTY = "INVALID_FILL_QTY";
    private static final String REASON_REDUCE_QTY_EXCEEDS_OPEN_QTY = "REDUCE_QTY_EXCEEDS_OPEN_QTY";

    /**
     * Test seam: allows injecting a deterministic closeLots behaviour without relying on
     * filesystem permissions or subclassing (both of which are unreliable on Windows).
     * Production code always uses {@code orderService::closeLots}.
     */
    @FunctionalInterface
    public interface CloseLotsFn {
        OptionOrderService.ExecutionView apply(String execId, int lots, java.math.BigDecimal price)
                throws java.io.IOException, java.sql.SQLException, InterruptedException;
    }

    private final OptionOrderService orderService;
    /** Mutable seam — default is {@code orderService::closeLots}; tests may inject a fault. */
    private CloseLotsFn closeLotsFn;
    private final ResearchPositionSessionService sessionService;
    private final PositionSessionActionService actionService;
    private final AppConfig.ExecutionMode executionMode;
    private final BrokerAdapter brokerAdapter;

    public MonitorAdjustmentExecutionService(
            OptionOrderService orderService,
            ResearchPositionSessionService sessionService,
            PositionSessionActionService actionService,
            AppConfig.ExecutionMode executionMode,
            BrokerAdapter brokerAdapter
    ) {
        this.orderService = Objects.requireNonNull(orderService, "orderService must not be null");
        // Tag every Monitor-driven reduction with source=MONITOR so the Order Log shows
        // who triggered the action. Tests may still override via withCloseLotsFn().
        this.closeLotsFn = (id, qty, price) -> orderService.closeLots(id, qty, price, "MONITOR");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService must not be null");
        this.actionService = Objects.requireNonNull(actionService, "actionService must not be null");
        this.executionMode = executionMode == null ? AppConfig.ExecutionMode.SIMULATED : executionMode;
        this.brokerAdapter = brokerAdapter;
    }

    /**
     * Test seam only. Replaces the closeLots implementation with a custom function.
     * Use to inject deterministic faults without filesystem permission tricks.
     */
    public MonitorAdjustmentExecutionService withCloseLotsFn(CloseLotsFn fn) {
        this.closeLotsFn = Objects.requireNonNull(fn, "fn must not be null");
        return this;
    }

    public static MonitorAdjustmentExecutionService simulated(
            OptionOrderService orderService,
            ResearchPositionSessionService sessionService,
            PositionSessionActionService actionService
    ) {
        return new MonitorAdjustmentExecutionService(
                orderService,
                sessionService,
                actionService,
                AppConfig.ExecutionMode.SIMULATED,
                null);
    }

    public MonitorAdjustmentExecutionResult execute(MonitorAdjustmentIntent intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        return switch (intent.action()) {
            case REDUCE -> executeReduce(intent);
            case ENTER -> executeEnter(intent);
        };
    }

    private MonitorAdjustmentExecutionResult executeReduce(MonitorAdjustmentIntent intent) {
        try {
            PositionSessionSnapshot session = requireSession(intent.sessionId());
            PositionSessionSnapshot.PositionLegSnapshot leg = requireLeg(session, intent.legId());
            MonitorAdjustmentExecutionResult invalid = validateReduceRequest(intent, leg, intent.reduceQuantity(), intent.currentLtp());
            if (invalid != null) {
                return invalid;
            }

            return switch (executionMode) {
                case SIMULATED -> applySimulatedReduceFill(
                        intent,
                        intent.reduceQuantity(),
                        intent.currentLtp(),
                        "sim-" + UUID.randomUUID(),
                        MonitorAdjustmentExecutionResult.Status.SIMULATED_FILLED,
                        "Simulated monitor reduce fill");
                case LIVE_APPROVAL_REQUIRED -> new MonitorAdjustmentExecutionResult(
                        MonitorAdjustmentExecutionResult.Status.PENDING_APPROVAL,
                        intent.strategyId(),
                        intent.sessionId(),
                        intent.legId(),
                        leg.executionId(),
                        intent.action().name(),
                        null,
                        intent.reduceQuantity(),
                        0,
                        leg.openQuantity(),
                        leg.openQuantity(),
                        null,
                        null,
                        intent.timestamp(),
                        null,
                        "Reduce intent recorded; live approval is required before placement");
                case LIVE_AUTO -> executeLiveAuto(intent, session, leg);
            };
        } catch (Exception exception) {
            return unfilled(intent, null, MonitorAdjustmentExecutionResult.Status.FAILED,
                    "REDUCE_FAILED", exception.getMessage());
        }
    }

    /**
     * Enters a new position via {@link OptionOrderService#placeOrder} and adds the resulting
     * execution as a new leg in the position session.
     * Works in all execution modes: SIMULATED places a paper order, LIVE_AUTO places a real one.
     */
    private MonitorAdjustmentExecutionResult executeEnter(MonitorAdjustmentIntent intent) {
        try {
            String mode = intent.enterMode() != null && !intent.enterMode().isBlank()
                    ? intent.enterMode() : "paper";
            OptionOrderService.PlaceOrderRequest req = new OptionOrderService.PlaceOrderRequest(
                    mode,
                    intent.enterUnderlying(),
                    intent.enterExpiry(),
                    intent.enterStrike(),
                    intent.enterOptionType(),
                    intent.enterTransactionType(),
                    intent.enterLots(),
                    "MARKET",
                    "MIS",
                    intent.currentLtp(),
                    intent.enterStrategyId(),
                    intent.enterStrategyType(),
                    intent.enterStrategyLabel(),
                    "MONITOR");
            OptionOrderService.ExecutionView execution = orderService.placeOrder(req);

            // Add the new execution as a leg in the session so the monitor tracks it
            PositionSessionSnapshot session = requireSession(intent.sessionId());
            PositionSessionSnapshot updatedSession = addLegToSession(session, execution, intent);
            sessionService.save(updatedSession);

            LOG.info(String.format("[MonitorAdjustmentExecutionService] ENTER placed: %s %s x%d @ %s",
                    intent.enterTransactionType(), execution.tradingSymbol(),
                    intent.enterLots(), execution.entryPrice()));
            return new MonitorAdjustmentExecutionResult(
                    MonitorAdjustmentExecutionResult.Status.SIMULATED_FILLED,
                    intent.strategyId(),
                    intent.sessionId(),
                    execution.instrumentId(),
                    execution.executionId(),
                    intent.action().name(),
                    execution.executionId(),
                    intent.enterLots(),
                    intent.enterLots(),
                    0,
                    intent.enterLots(),
                    execution.entryPrice(),
                    null,
                    intent.timestamp(),
                    null,
                    "Monitor ENTER placed: " + execution.tradingSymbol());
        } catch (Exception e) {
            LOG.warning("[MonitorAdjustmentExecutionService] ENTER failed: " + e.getMessage());
            return new MonitorAdjustmentExecutionResult(
                    MonitorAdjustmentExecutionResult.Status.FAILED,
                    intent.strategyId(),
                    intent.sessionId(),
                    null,
                    null,
                    intent.action().name(),
                    null,
                    intent.enterLots(),
                    0,
                    0,
                    0,
                    intent.currentLtp(),
                    null,
                    intent.timestamp(),
                    "ENTER_FAILED",
                    "Monitor ENTER failed: " + e.getMessage());
        }
    }

    public MonitorAdjustmentExecutionResult applySimulatedReduceFill(
            MonitorAdjustmentIntent intent,
            int filledQuantity,
            BigDecimal fillPrice,
            String brokerOrderId,
            MonitorAdjustmentExecutionResult.Status filledStatus,
            String message
    ) throws IOException {
        Objects.requireNonNull(intent, "intent must not be null");
        if (filledStatus != MonitorAdjustmentExecutionResult.Status.SIMULATED_FILLED) {
            throw new IllegalArgumentException("filledStatus must be SIMULATED_FILLED");
        }
        PositionSessionSnapshot session = requireSession(intent.sessionId());
        PositionSessionSnapshot.PositionLegSnapshot leg = requireLeg(session, intent.legId());
        MonitorAdjustmentExecutionResult invalid = validateReduceRequest(intent, leg, filledQuantity, fillPrice);
        if (invalid != null) {
            return invalid;
        }

        // Phase 1: Execution record is canonical. Close execution FIRST, then project to session.
        // Phase 1.5: If closeLots throws, abort immediately — do NOT mutate session.
        //   This preserves execution-session consistency: we never update the session when the
        //   execution write fails. The session is only mutated AFTER the execution is confirmed.
        String execId = normalize(leg.executionId());
        if (execId != null) {
            if (orderService.executionExists(execId)) {
                try {
                    closeLotsFn.apply(execId, filledQuantity, fillPrice);
                } catch (Exception ex) {
                    LOG.severe("[MonitorAdjustmentExecutionService] closeLots failed for execId=" + execId
                            + "; aborting reduce to preserve execution-session consistency: " + ex.getMessage());
                    return unfilled(intent, leg, MonitorAdjustmentExecutionResult.Status.FAILED,
                            "EXECUTION_CLOSE_FAILED",
                            "Execution record update failed; session not mutated: " + ex.getMessage());
                }
            }
        }

        PositionSessionSnapshot updatedSession = actionService.apply(session, reduceActionRequest(intent, leg, filledQuantity, fillPrice));
        sessionService.save(updatedSession);

        PositionSessionSnapshot.PositionLegSnapshot updatedLeg = requireLeg(updatedSession, leg.legId());

        // Phase 1.5: Post-reduce invariant check — execution and session must agree on lots.
        if (execId != null) {
            logPostReduceInvariantCheck(execId, leg.legId(), updatedLeg.openQuantity(), intent.sessionId());
        }
        return new MonitorAdjustmentExecutionResult(
                filledStatus,
                intent.strategyId(),
                intent.sessionId(),
                intent.legId(),
                leg.executionId(),
                intent.action().name(),
                brokerOrderId,
                intent.reduceQuantity(),
                filledQuantity,
                leg.openQuantity(),
                updatedLeg.openQuantity(),
                fillPrice,
                updatedLeg.bookedPnl(),
                intent.timestamp(),
                null,
                message == null || message.isBlank() ? "Simulated reduce fill applied" : message);
    }

    public MonitorAdjustmentExecutionResult applyConfirmedReduceFill(
            MonitorAdjustmentIntent intent,
            int filledQuantity,
            BigDecimal fillPrice,
            String brokerOrderId,
            MonitorAdjustmentExecutionResult.Status filledStatus,
            String message
    ) throws IOException, SQLException, InterruptedException {
        Objects.requireNonNull(intent, "intent must not be null");
        if (filledStatus != MonitorAdjustmentExecutionResult.Status.SIMULATED_FILLED
                && filledStatus != MonitorAdjustmentExecutionResult.Status.FILLED) {
            throw new IllegalArgumentException("filledStatus must be SIMULATED_FILLED or FILLED");
        }

        PositionSessionSnapshot session = requireSession(intent.sessionId());
        PositionSessionSnapshot.PositionLegSnapshot leg = requireLeg(session, intent.legId());
        MonitorAdjustmentExecutionResult invalid = validateReduceRequest(intent, leg, filledQuantity, fillPrice);
        if (invalid != null) {
            return invalid;
        }
        String executionId = normalize(leg.executionId());
        if (executionId == null) {
            return unfilled(intent, leg, MonitorAdjustmentExecutionResult.Status.FAILED,
                    REASON_EXECUTION_ID_MISSING, "Missing executionId for execution-backed reduce");
        }
        if (!orderService.executionExists(executionId)) {
            return unfilled(intent, leg, MonitorAdjustmentExecutionResult.Status.FAILED,
                    REASON_EXECUTION_NOT_FOUND, "Execution record not found: " + executionId);
        }

        OptionOrderService.ExecutionView updatedExecution = orderService.closeLots(executionId, filledQuantity, fillPrice, "MONITOR");
        PositionSessionSnapshot updatedSession = actionService.apply(session, reduceActionRequest(intent, leg, filledQuantity, fillPrice));
        sessionService.save(updatedSession);

        PositionSessionSnapshot.PositionLegSnapshot updatedLeg = requireLeg(updatedSession, leg.legId());

        // Phase 1.5: Post-reduce invariant check — execution and session must agree on lots.
        if (updatedExecution.lots() != updatedLeg.openQuantity()) {
            LOG.severe(String.format("[INVARIANT VIOLATION] Execution-session mismatch after live reduce:"
                    + " execId=%s executionLots=%d sessionOpenQty=%d legId=%s sessionId=%s",
                    executionId, updatedExecution.lots(), updatedLeg.openQuantity(),
                    leg.legId(), intent.sessionId()));
        }

        return new MonitorAdjustmentExecutionResult(
                filledStatus,
                intent.strategyId(),
                intent.sessionId(),
                intent.legId(),
                executionId,
                intent.action().name(),
                brokerOrderId,
                intent.reduceQuantity(),
                filledQuantity,
                leg.openQuantity(),
                updatedLeg.openQuantity(),
                fillPrice,
                updatedExecution.bookedPnl(),
                intent.timestamp(),
                null,
                message == null || message.isBlank() ? "Reduce fill applied" : message);
    }

    public MonitorAdjustmentExecutionResult recordUnfilledResult(
            MonitorAdjustmentIntent intent,
            MonitorAdjustmentExecutionResult.Status status,
            String message
    ) {
        if (status == MonitorAdjustmentExecutionResult.Status.SIMULATED_FILLED
                || status == MonitorAdjustmentExecutionResult.Status.FILLED) {
            throw new IllegalArgumentException("Use applyConfirmedReduceFill for filled results");
        }
        return unfilled(intent, null, status, status.name(), message);
    }

    private MonitorAdjustmentExecutionResult executeLiveAuto(
            MonitorAdjustmentIntent intent,
            PositionSessionSnapshot session,
            PositionSessionSnapshot.PositionLegSnapshot leg
    ) throws IOException, InterruptedException, SQLException {
        if (brokerAdapter == null) {
            return unfilled(intent, leg, MonitorAdjustmentExecutionResult.Status.REJECTED, "BROKER_ADAPTER_MISSING",
                    "LIVE_AUTO requires an approved broker adapter; no broker order was placed");
        }
        OrderLeg.Action closeAction = "SHORT".equalsIgnoreCase(leg.side()) ? OrderLeg.Action.BUY : OrderLeg.Action.SELL;
        OrderRequest request = new OrderRequest(
                UUID.randomUUID(),
                session.sessionId(),
                List.of(new OrderLeg(
                        leg.symbol() == null || leg.symbol().isBlank() ? leg.instrumentId() : leg.symbol(),
                        closeAction,
                        intent.reduceQuantity() * lotSize(leg),
                        0.0d,
                        "MIS")),
                intent.timestamp(),
                intent.reason());
        OrderResult result = brokerAdapter.placeOrder(request);
        if (result.status() == OrderStatus.FILLED) {
            return applyConfirmedReduceFill(
                    intent,
                    intent.reduceQuantity(),
                    BigDecimal.valueOf(result.filledPrice()),
                    result.brokerOrderId(),
                    MonitorAdjustmentExecutionResult.Status.FILLED,
                    "Live broker fill confirmed");
        }
        MonitorAdjustmentExecutionResult.Status status = switch (result.status()) {
            case PENDING, SENT -> MonitorAdjustmentExecutionResult.Status.SENT;
            case CANCELLED -> MonitorAdjustmentExecutionResult.Status.CANCELLED;
            case REJECTED -> MonitorAdjustmentExecutionResult.Status.REJECTED;
            case FILLED -> MonitorAdjustmentExecutionResult.Status.FILLED;
        };
        return unfilled(intent, leg, status, status.name(),
                result.errorMessage() == null ? "Live order not filled" : result.errorMessage());
    }

    private PositionSessionActionRequest reduceActionRequest(
            MonitorAdjustmentIntent intent,
            PositionSessionSnapshot.PositionLegSnapshot leg,
            int filledQuantity,
            BigDecimal fillPrice
    ) {
        PositionSessionActionRequest.LegAction legAction = new PositionSessionActionRequest.LegAction(
                leg.legId(),
                null,
                fillPrice,
                0,
                filledQuantity,
                null,
                null,
                null,
                null,
                null,
                "AUTONOMOUS_REDUCE",
                leg.label(),
                leg.optionType(),
                leg.side(),
                leg.strike(),
                intent.reason(),
                intent.reason(),
                leg.symbol(),
                leg.instrumentId(),
                leg.expiryDate()
        );
        return new PositionSessionActionRequest(
                intent.sessionId(),
                "PARTIAL_EXIT",
                intent.timestamp(),
                intent.timestamp(),
                List.of(legAction));
    }

    /**
     * Creates a new {@link PositionSessionSnapshot.PositionLegSnapshot} for a freshly placed
     * execution and appends it to the session's leg list.
     */
    private static PositionSessionSnapshot addLegToSession(
            PositionSessionSnapshot session,
            OptionOrderService.ExecutionView execution,
            MonitorAdjustmentIntent intent) {
        String side = "BUY".equalsIgnoreCase(intent.enterTransactionType()) ? "LONG" : "SHORT";
        String legRole = "LONG".equals(side)
                ? PositionSessionSnapshot.LegRole.LONG_HEDGE.name()
                : PositionSessionSnapshot.LegRole.SHORT_PREMIUM.name();
        String expiryDate = execution.expiry() != null
                ? execution.expiry().toString() : intent.enterExpiry();
        String strikeStr = execution.strike() != null
                ? execution.strike().toPlainString() : intent.enterStrike().toPlainString();
        Instant now = Instant.now();
        String legId = "MON-LEG-" + execution.executionId();
        String label = intent.enterOptionType() + " " + strikeStr;

        PositionSessionSnapshot.PositionLegSnapshot newLeg = new PositionSessionSnapshot.PositionLegSnapshot(
                legId, label, intent.enterOptionType(), side,
                execution.strike() != null ? execution.strike() : intent.enterStrike(),
                expiryDate,
                execution.tradingSymbol(),
                execution.instrumentId(),
                execution.entryPrice(),
                execution.lots(),
                execution.lots(),
                BigDecimal.ZERO,
                "OPEN",
                now,
                now,
                execution.executionId(),
                legRole);

        List<PositionSessionSnapshot.PositionLegSnapshot> updatedLegs = new ArrayList<>(session.legs());
        updatedLegs.add(newLeg);
        return new PositionSessionSnapshot(
                session.sessionId(), session.mode(), session.strategyLabel(), session.orientation(),
                session.underlying(), session.expiryType(), session.timeframe(), session.dte(),
                session.spot(), session.scenarioQty(), session.createdAt(), now,
                session.lastDeltaAdjustmentTs(), session.status(), updatedLegs, session.auditLog(),
                now, null, session.hedgePremiumPaidCe(), session.hedgePremiumPaidPe());
    }

    private PositionSessionSnapshot requireSession(String sessionId) throws IOException {
        PositionSessionSnapshot session = sessionService.load(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Position session not found: " + sessionId);
        }
        return session;
    }

    private static PositionSessionSnapshot.PositionLegSnapshot requireLeg(
            PositionSessionSnapshot session,
            String legId
    ) {
        return session.legs().stream()
                .filter(leg -> leg != null && legId.equals(leg.legId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Session leg not found: " + legId));
    }

        private MonitorAdjustmentExecutionResult validateReduceRequest(
            MonitorAdjustmentIntent intent,
            PositionSessionSnapshot.PositionLegSnapshot leg,
            int quantity,
            BigDecimal fillPrice
    ) {
        if (quantity <= 0) {
            return unfilled(intent, leg, MonitorAdjustmentExecutionResult.Status.FAILED,
                REASON_INVALID_FILL_QTY, "filledQuantity must be greater than zero");
        }
        if (fillPrice == null || fillPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return unfilled(intent, leg, MonitorAdjustmentExecutionResult.Status.FAILED,
                REASON_MISSING_FILL_PRICE, "A positive fill price is required before applying a reduce fill");
        }
        int openQuantity = Math.max(0, leg.openQuantity());
        if (quantity > openQuantity) {
            return unfilled(intent, leg, MonitorAdjustmentExecutionResult.Status.FAILED,
                REASON_REDUCE_QTY_EXCEEDS_OPEN_QTY,
                "Cannot reduce " + quantity + " lots; leg only has " + openQuantity + " open lots");
        }
        if (intent.reduceQuantity() > openQuantity) {
            return unfilled(intent, leg, MonitorAdjustmentExecutionResult.Status.FAILED,
                REASON_REDUCE_QTY_EXCEEDS_OPEN_QTY,
                "Cannot request reduce of " + intent.reduceQuantity() + " lots; leg only has " + openQuantity + " open lots");
        }
        return null;
    }

    private static int lotSize(PositionSessionSnapshot.PositionLegSnapshot leg) {
        String symbol = leg.symbol() == null ? "" : leg.symbol().toUpperCase();
        String instrumentId = leg.instrumentId() == null ? "" : leg.instrumentId().toUpperCase();
        return symbol.contains("BANKNIFTY") || instrumentId.contains("BANKNIFTY") ? 30 : 65;
    }

    /**
     * Phase 1.5: Logs a SEVERE message if the execution ledger and session snapshot disagree on lots.
     * Called after each simulated reduce where the leg has an executionId.
     */
    private void logPostReduceInvariantCheck(String execId, String legId, int sessionOpenQty, String sessionId) {
        try {
            java.util.Map<String, Integer> executionLots = orderService.loadExecutionLots();
            if (!executionLots.containsKey(execId)) {
                LOG.warning(String.format("[Invariant check] execId=%s not found in today's execution ledger"
                        + " | legId=%s sessionId=%s", execId, legId, sessionId));
                return;
            }
            int execLots = executionLots.get(execId);
            if (execLots != sessionOpenQty) {
                LOG.severe(String.format("[INVARIANT VIOLATION] Execution-session mismatch after simulated reduce:"
                        + " execId=%s executionLots=%d sessionOpenQty=%d legId=%s sessionId=%s",
                        execId, execLots, sessionOpenQty, legId, sessionId));
            } else {
                LOG.fine(String.format("[Invariant OK] execId=%s lots=%d == sessionOpenQty=%d",
                        execId, execLots, sessionOpenQty));
            }
        } catch (Exception ex) {
            LOG.warning("[Invariant check] loadExecutionLots failed: " + ex.getMessage());
        }
    }

    private MonitorAdjustmentExecutionResult unfilled(
            MonitorAdjustmentIntent intent,
            PositionSessionSnapshot.PositionLegSnapshot knownLeg,
            MonitorAdjustmentExecutionResult.Status status,
            String failureReason,
            String message
    ) {
        int openLots = 0;
        String executionId = knownLeg == null ? null : knownLeg.executionId();
        try {
            PositionSessionSnapshot session = sessionService.load(intent.sessionId());
            if (session != null) {
                PositionSessionSnapshot.PositionLegSnapshot leg = requireLeg(session, intent.legId());
                openLots = leg.openQuantity();
                executionId = leg.executionId();
            }
        } catch (Exception ignored) {
            // Best-effort diagnostic only; never mutate state for unfilled results.
        }
        String reason = failureReason == null || failureReason.isBlank() ? status.name() : failureReason;
        return new MonitorAdjustmentExecutionResult(
                status,
                intent.strategyId(),
                intent.sessionId(),
                intent.legId(),
                executionId,
                intent.action().name(),
                null,
                intent.reduceQuantity(),
                0,
                openLots,
                openLots,
                intent.currentLtp(),
                null,
                intent.timestamp(),
                reason,
                diagnosticMessage(intent, executionId, openLots, reason, message));
    }

    private static String diagnosticMessage(
            MonitorAdjustmentIntent intent,
            String executionId,
            int openLots,
            String failureReason,
            String message
    ) {
        return "action=" + intent.action()
                + "; legId=" + intent.legId()
                + "; executionId=" + (executionId == null || executionId.isBlank() ? "<missing>" : executionId)
                + "; requestedQuantity=" + intent.reduceQuantity()
                + "; openQuantity=" + openLots
                + "; fillPrice=" + (intent.currentLtp() == null ? "<missing>" : intent.currentLtp())
                + "; reason=" + failureReason
                + (message == null || message.isBlank() ? "" : "; message=" + message);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}