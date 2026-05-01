package com.strategysquad.agentic.booking;

import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.PositionPnlCalculator;
import com.strategysquad.research.PositionSessionActionRequest;
import com.strategysquad.research.PositionSessionActionService;
import com.strategysquad.research.PositionSessionSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.logging.Logger;

/**
 * Applies theta-decay profit booking to an active position session.
 *
 * <h2>Booking modes</h2>
 * <ul>
 *   <li><b>Partial booking</b> ({@link #PARTIAL_BOOKING_THRESHOLD} &le; ratio &lt;
 *       {@link #FULL_BOOKING_THRESHOLD}): reduce each open leg by one lot to lock in
 *       some premium. Audit reason: {@code theta_capture_profit_book_partial}.</li>
 *   <li><b>Full booking</b> (ratio &ge; {@link #FULL_BOOKING_THRESHOLD}): exit all
 *       legs entirely. Audit reason: {@code theta_capture_profit_book_full}.
 *       The result signal {@link BookingResult#signal()} returns
 *       {@link Signal#RESTART_SCAN}.</li>
 * </ul>
 *
 * <h2>Trigger conditions</h2>
 * <p>Booking is attempted when ALL of the following hold:
 * <ol>
 *   <li>{@code theta_progress_ratio &ge; PARTIAL_BOOKING_THRESHOLD} (default 0.75).</li>
 *   <li>{@code live_pnl &gt; 0} — never book at a loss.</li>
 * </ol>
 *
 * <p>Risk parameters are accepted as compatibility inputs but do not gate booking
 * decisions in the current backend behavior.
 *
 * <h2>Booked PnL semantics</h2>
 * <p>Booked PnL for each exited unit = {@code (entryPrice - exitPrice) * exitedQuantity}
 * for SHORT legs, delegated to {@link PositionPnlCalculator#bookedPnl}. Exit price is
 * sourced from the {@link CurrentPriceSource} seam — the booking agent never invents prices.
 *
 * <h2>Audit-before-action</h2>
 * <p>The updated session snapshot returned inside {@link BookingResult#updatedSession()}
 * carries the new audit entries produced by {@link PositionSessionActionService#apply}.
 * The caller must persist this updated snapshot before returning the result to the
 * caller's own layer.
 *
 * <h2>Simulation-first</h2>
 * <p>This agent does not call any broker order API. It produces an updated session
 * snapshot for the Decision Agent to persist — it never applies position mutation itself.
 */
public class ProfitBookingAgent {

    private static final Logger LOG = Logger.getLogger(ProfitBookingAgent.class.getName());

    // =========================================================================
    // Configuration constants
    // =========================================================================

    /**
     * Minimum {@code theta_progress_ratio} that triggers any profit booking.
     * Below this value the agent returns {@link BookingResult#noAction()}.
     * Configurable at construction time.
     */
    public static final double PARTIAL_BOOKING_THRESHOLD = 0.75;

    /**
     * {@code theta_progress_ratio} at or above which full booking is triggered.
     * Below this (but at or above {@link #PARTIAL_BOOKING_THRESHOLD}), partial
     * booking is triggered.
     * Configurable at construction time.
     */
    public static final double FULL_BOOKING_THRESHOLD = 0.90;

    /** Action type label written to the session audit log for partial booking. */
    public static final String AUDIT_REASON_PARTIAL = "theta_capture_profit_book_partial";

    /** Action type label written to the session audit log for full booking. */
    public static final String AUDIT_REASON_FULL = "theta_capture_profit_book_full";

    // =========================================================================
    // Enums
    // =========================================================================

    /**
     * Signal returned to the caller after a completed booking.
     *
     * <p>{@link #CONTINUE} means the position remains open (partial booking).
     * {@link #RESTART_SCAN} means the session is fully closed and the scanner
     * should be restarted for the next entry.
     */
    public enum Signal {
        /** Position still open — continue monitoring. */
        CONTINUE,
        /** All legs exited — restart the scanner for the next entry. */
        RESTART_SCAN
    }

    // =========================================================================
    // Functional interface seams — allow test injection
    // =========================================================================

    /**
     * Supplies the current market price for a given instrument.
     *
     * <p>In live/paper mode the implementation reads from the live tick feed.
     * In simulation mode a replay price is injected. Tests inject a pre-built map
     * so no live feed is required.
     */
    @FunctionalInterface
    public interface CurrentPriceSource {
        /**
         * Returns the current market price (last traded price) for the instrument.
         *
         * @param instrumentId canonical instrument identifier
         * @return current price in NSE index points; must not be null
         * @throws Exception if the price lookup fails
         */
        BigDecimal currentPrice(String instrumentId) throws Exception;
    }

    // =========================================================================
    // BookingResult
    // =========================================================================

    /**
     * Immutable result of one booking attempt by {@link ProfitBookingAgent}.
     *
     * <p>When {@link #booked()} is {@code false} the caller should treat this as a
     * no-op; the session snapshot is unchanged and {@link #signal()} is
     * {@link Signal#CONTINUE}.
     */
    public record BookingResult(
            /** Whether any booking action was applied. */
            boolean booked,
            /**
             * Session snapshot after applying the booking action.
             * Equal to the input session when {@code booked = false}.
             */
            PositionSessionSnapshot updatedSession,
            /**
             * Signal to the orchestrator.
             * {@link Signal#RESTART_SCAN} after full booking; {@link Signal#CONTINUE} otherwise.
             */
            Signal signal,
            /** Machine-readable reason code written to the audit log. */
            String reasonCode,
            /** Trader-readable explanation for the booking decision. */
            String explanation
    ) {
        /**
         * Convenience factory for a no-action result (no booking performed).
         *
         * @param session the unmodified input session
         * @param reason  short machine-readable explanation of why no booking occurred
         * @return a no-action {@code BookingResult}
         */
        static BookingResult noAction(PositionSessionSnapshot session, String reason) {
            return new BookingResult(false, session, Signal.CONTINUE, reason,
                    "No booking — " + reason);
        }
    }

    // =========================================================================
    // State
    // =========================================================================

    private final PositionSessionActionService actionService;
    private final double partialThreshold;
    private final double fullThreshold;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates a {@code ProfitBookingAgent} with all configuration injected.
     *
     * @param actionService    applies leg exits/reductions to the session snapshot
     * @param partialThreshold theta_progress_ratio at/above which partial booking triggers
     * @param fullThreshold    theta_progress_ratio at/above which full booking triggers
     */
    public ProfitBookingAgent(
            PositionSessionActionService actionService,
            double partialThreshold,
            double fullThreshold) {

        this.actionService = Objects.requireNonNull(actionService, "actionService must not be null");
        if (partialThreshold <= 0.0 || partialThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "partialThreshold must be in (0, 1], got: " + partialThreshold);
        }
        if (fullThreshold <= partialThreshold || fullThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "fullThreshold must be in (" + partialThreshold + ", 1], got: " + fullThreshold);
        }
        this.partialThreshold = partialThreshold;
        this.fullThreshold = fullThreshold;
    }

    /**
     * Convenience constructor using default thresholds (0.75 / 0.90).
     *
     * @param actionService applies leg exits/reductions to the session snapshot
     */
    public ProfitBookingAgent(PositionSessionActionService actionService) {
        this(actionService, PARTIAL_BOOKING_THRESHOLD, FULL_BOOKING_THRESHOLD);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Attempts to apply profit booking to the given session.
     *
     * <p>The method is deterministic given the same inputs. It does not call any broker
     * API or modify any external state — it returns an updated {@link PositionSessionSnapshot}
     * inside the result for the caller to persist.
     *
     * @param session           the active position session; must not be null
     * @param thetaProgressRatio the ratio {@code expected_decay / entry_premium} for the session;
     *                          must be &ge; 0.0
     * @param livePnl           current live PnL for the session in NSE index points;
     *                          negative means unrealised loss
     * @param riskDecision      compatibility risk flag; accepted but not used for booking gates
     * @param priceSource       source for current market prices (used for exit price); must not be null
     * @param bookingTs         UTC instant at which booking is being evaluated; must not be null
     * @return a {@link BookingResult} — never null; check {@link BookingResult#booked()} to
     *         determine whether an action was applied
     */
    public BookingResult tryBook(
            PositionSessionSnapshot session,
            double thetaProgressRatio,
            double livePnl,
            RiskGuardDecision riskDecision,
            CurrentPriceSource priceSource,
            Instant bookingTs) {

        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(riskDecision, "riskDecision must not be null");
        Objects.requireNonNull(priceSource, "priceSource must not be null");
        Objects.requireNonNull(bookingTs, "bookingTs must not be null");

        // ------------------------------------------------------------------
        // Guard 1 — Minimum ratio threshold
        // ------------------------------------------------------------------
        if (thetaProgressRatio < partialThreshold) {
            return BookingResult.noAction(session,
                    "RATIO_BELOW_THRESHOLD: " + thetaProgressRatio + " < " + partialThreshold);
        }

        // ------------------------------------------------------------------
        // Guard 2 — Never book at a loss
        // ------------------------------------------------------------------
        if (livePnl <= 0.0) {
            return BookingResult.noAction(session,
                    "NEGATIVE_OR_ZERO_LIVE_PNL: " + livePnl);
        }

        // ------------------------------------------------------------------
        // Determine booking mode
        // ------------------------------------------------------------------
        boolean fullBooking = thetaProgressRatio >= fullThreshold;
        String reasonCode = fullBooking ? AUDIT_REASON_FULL : AUDIT_REASON_PARTIAL;

        // ------------------------------------------------------------------
        // Get only open legs (openQuantity > 0)
        // ------------------------------------------------------------------
        List<PositionSessionSnapshot.PositionLegSnapshot> openLegs = session.legs().stream()
                .filter(leg -> leg.openQuantity() > 0)
                .toList();

        if (openLegs.isEmpty()) {
            return BookingResult.noAction(session, "NO_OPEN_LEGS");
        }

        // ------------------------------------------------------------------
        // Resolve current exit prices and build leg actions
        // ------------------------------------------------------------------
        List<PositionSessionActionRequest.LegAction> legActions = new ArrayList<>();
        for (PositionSessionSnapshot.PositionLegSnapshot leg : openLegs) {
            BigDecimal exitPrice;
            try {
                exitPrice = priceSource.currentPrice(leg.instrumentId());
            } catch (Exception e) {
                LOG.warning("[ProfitBookingAgent] Price lookup failed for " + leg.instrumentId() +
                            " — skipping booking for this leg: " + e.getMessage());
                return BookingResult.noAction(session, "PRICE_LOOKUP_FAILED_" + leg.instrumentId());
            }
            if (exitPrice == null) {
                return BookingResult.noAction(session, "NULL_EXIT_PRICE_" + leg.instrumentId());
            }

            int exitedQuantity;
            if (fullBooking) {
                exitedQuantity = leg.openQuantity(); // exit everything
            } else {
                // Partial: reduce by exactly one lot (lotSize units)
                // We derive lot size from openQuantity: openQuantity must be a multiple of lot size.
                // Use openQuantity as a conservative floor — reduce by minimum 1 unit if < lotSize,
                // otherwise reduce by 1 lot.
                int lotSize = resolveLotSize(leg);
                exitedQuantity = Math.min(lotSize, leg.openQuantity());
            }

            legActions.add(new PositionSessionActionRequest.LegAction(
                    leg.legId(),
                    null,           // entryPrice — not changed
                    exitPrice,
                    0,              // addedQuantity
                    exitedQuantity,
                    null, null, null, // delta fields — not set here
                    null, null,      // volume fields
                    null,            // adjustmentActionType
                    leg.label(),
                    leg.optionType(),
                    leg.side(),
                    leg.strike(),
                    reasonCode,
                    buildExplanation(fullBooking, thetaProgressRatio, livePnl, leg),
                    leg.symbol(),
                    leg.instrumentId(),
                    leg.expiryDate()
            ));
        }

        PositionSessionActionRequest request = new PositionSessionActionRequest(
                session.sessionId(),
                fullBooking ? "EXIT" : "REDUCE",
                bookingTs,
                null,        // lastDeltaAdjustmentTs unchanged
                legActions
        );

        PositionSessionSnapshot updatedSession = actionService.apply(session, request);

        String explanation = buildSummaryExplanation(fullBooking, thetaProgressRatio, livePnl,
                openLegs.size());

        LOG.info("[ProfitBookingAgent] " + (fullBooking ? "FULL" : "PARTIAL") + " booking applied" +
                 " | session=" + session.sessionId() +
                 " | ratio=" + thetaProgressRatio +
                 " | livePnl=" + livePnl);

        return new BookingResult(
                true,
                updatedSession,
                fullBooking ? Signal.RESTART_SCAN : Signal.CONTINUE,
                reasonCode,
                explanation
        );
    }

    /**
     * Convenience overload that reads {@code thetaProgressRatio} from the first
     * available signal in the provided map.
     *
     * <p>When no signal is available, returns {@link BookingResult#noAction(PositionSessionSnapshot, String)}
     * with reason {@code NO_SIGNAL_FOR_RATIO}. The caller should treat this as HOLD.
     *
     * @param session       active session; must not be null
     * @param signalMap     map of {@code instrumentId → SignalSnapshot}; may be empty
     * @param livePnl       current live PnL in NSE index points
     * @param riskDecision  compatibility risk flag; accepted but not used for booking gates
     * @param priceSource   source for current market prices; must not be null
     * @param bookingTs     UTC instant at booking evaluation time; must not be null
     * @return booking result; never null
     */
    public BookingResult tryBookFromSignals(
            PositionSessionSnapshot session,
            Map<String, SignalSnapshot> signalMap,
            double livePnl,
            RiskGuardDecision riskDecision,
            CurrentPriceSource priceSource,
            Instant bookingTs) {

        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(signalMap, "signalMap must not be null");

        // Derive theta_progress_ratio from session's active legs — use the maximum
        // ratio across all legs as the most conservative trigger for booking.
        OptionalDouble maxRatio = session.legs().stream()
                .filter(leg -> leg.openQuantity() > 0)
                .mapToDouble(leg -> {
                    SignalSnapshot sig = signalMap.get(leg.instrumentId());
                    if (sig == null || sig.thetaProgressRatio() == null) {
                        return 0.0;
                    }
                    return sig.thetaProgressRatio().doubleValue();
                })
                .max();

        if (maxRatio.isEmpty()) {
            return BookingResult.noAction(session, "NO_SIGNAL_FOR_RATIO");
        }

        return tryBook(session, maxRatio.getAsDouble(), livePnl, riskDecision, priceSource, bookingTs);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Resolves the lot size for a leg from its open quantity.
     *
     * <p>Phase 3 infers lot size as the GCD between openQuantity and known NSE lot sizes.
     * If open quantity doesn't match any known lot size multiple, falls back to
     * openQuantity (i.e. treat the whole position as one lot).
     */
    private static int resolveLotSize(PositionSessionSnapshot.PositionLegSnapshot leg) {
        int q = leg.openQuantity();
        // Known NSE lot sizes in order of preference
        for (int lotSize : new int[]{30, 65}) {
            if (q % lotSize == 0) {
                return lotSize;
            }
        }
        return q; // fallback: treat entire open position as one lot
    }

    private static String buildExplanation(
            boolean fullBooking,
            double ratio,
            double livePnl,
            PositionSessionSnapshot.PositionLegSnapshot leg) {

        String type = fullBooking ? "Full booking" : "Partial booking";
        return String.format(
                "%s for leg %s (%s %s) — theta_progress_ratio=%.3f live_pnl=%.2f",
                type, leg.legId(), leg.optionType(), leg.strike(),
                ratio, livePnl);
    }

    private static String buildSummaryExplanation(
            boolean fullBooking,
            double ratio,
            double livePnl,
            int legCount) {

        String type = fullBooking ? "Full booking" : "Partial booking";
        return String.format(
                "%s applied to %d leg(s) — theta_progress_ratio=%.3f live_pnl=%.2f",
                type, legCount, ratio, livePnl);
    }
}
