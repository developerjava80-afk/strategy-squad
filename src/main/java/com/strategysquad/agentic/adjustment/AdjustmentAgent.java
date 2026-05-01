package com.strategysquad.agentic.adjustment;

import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.risk.RiskGuardSnapshot;
import com.strategysquad.research.PositionSessionActionRequest;
import com.strategysquad.research.PositionSessionActionService;
import com.strategysquad.research.PositionSessionSnapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Applies strike-shift and leg-exit adjustments to an active position session.
 *
 * <h2>Supported operations</h2>
 * <ul>
 *   <li><b>SHIFT_STRIKE</b> — exit a specified leg and re-enter at a scanner-recommended
 *       strike. Both the exit and the re-entry produce audit records via
 *       {@link PositionSessionActionService}. A cooldown window is enforced after every
 *       successful shift so that the agent cannot thrash between strikes. One leg per
 *       call only.</li>
 *   <li><b>EXIT_LEG</b> — exit a single leg identified by instrument ID. The session
 *       remains open as long as at least one leg has non-zero open quantity.</li>
 *   <li><b>EXIT_ALL</b> — exit every open leg at their current market prices. The session
 *       is marked {@code CLOSED} by {@link PositionSessionActionService}. Further calls to
 *       any method on a {@code CLOSED} session return a blocked result immediately.</li>
 * </ul>
 *
 * <h2>Risk compatibility input</h2>
 * <p>SHIFT_STRIKE accepts a {@link RiskGuardSnapshot} for interface compatibility,
 * but runtime Risk Guard gating is disabled in current backend behavior.
 * EXIT_LEG and EXIT_ALL remain risk-reduction actions.
 *
 * <h2>Audit-before-action contract</h2>
 * <p>Audit entries are produced by {@link PositionSessionActionService#apply} as part of
 * the updated session snapshot. The caller must persist the updated snapshot before
 * returning to the orchestrator.
 *
 * <h2>Booked PnL semantics</h2>
 * <p>Booked PnL for each exited unit = {@code (entryPrice − exitPrice) × exitedQuantity}
 * for SHORT legs, delegated to {@link com.strategysquad.research.PositionPnlCalculator#bookedPnl}.
 * Exit prices are sourced from the injected {@link CurrentPriceSource} — this agent never
 * invents prices.
 *
 * <h2>Simulation-first</h2>
 * <p>This agent does not call any broker order API. It produces an updated session
 * snapshot for the caller to persist.
 *
 * <h2>Thread safety</h2>
 * <p>This class is <b>not</b> thread-safe. It maintains mutable cooldown state
 * ({@code lastShiftTs}). Use one instance per decision loop.
 */
public class AdjustmentAgent {

    private static final Logger LOG = Logger.getLogger(AdjustmentAgent.class.getName());

    // =========================================================================
    // Configuration constants
    // =========================================================================

    /**
     * Default cooldown window after a successful SHIFT_STRIKE.
     * A second shift on the same session within this window is blocked.
     */
    public static final Duration DEFAULT_SHIFT_COOLDOWN = Duration.ofMinutes(30);

    /** Audit action type written for the exit half of a SHIFT_STRIKE. */
    public static final String AUDIT_ACTION_SHIFT_EXIT   = "SHIFT_STRIKE_EXIT";
    /** Audit action type written for the re-entry half of a SHIFT_STRIKE. */
    public static final String AUDIT_ACTION_SHIFT_ENTRY  = "SHIFT_STRIKE_ENTRY";
    /** Audit action type written for EXIT_LEG. */
    public static final String AUDIT_ACTION_EXIT_LEG     = "EXIT_LEG";
    /** Audit action type written for EXIT_ALL. */
    public static final String AUDIT_ACTION_EXIT_ALL     = "EXIT_ALL";

    // =========================================================================
    // Embedded types
    // =========================================================================

    /**
     * Describes the new leg that SHIFT_STRIKE should enter after exiting the old one.
     *
     * <p>Typically built from the top-ranked scanner candidate at the recommended strike.
     */
    public record NewLegDescriptor(
            /**
             * Canonical instrument ID of the new leg.
             * Format: {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>}.
             */
            String instrumentId,

            /**
             * NSE trading symbol (e.g., {@code NIFTY26APR25000CE}).
             */
            String tradingSymbol,

            /**
             * Option type: {@code CE} or {@code PE}.
             */
            String optionType,

            /**
             * Strike price in NSE index points.
             */
            BigDecimal strike,

            /**
             * Expiry date string in ISO format (e.g., {@code 2026-04-30}).
             */
            String expiryDate,

            /**
             * Quantity to enter in contract units (not lots).
             * Must match the quantity of the exited leg to maintain structure balance.
             */
            int quantity
    ) {
        public NewLegDescriptor {
            Objects.requireNonNull(instrumentId, "instrumentId must not be null");
            Objects.requireNonNull(tradingSymbol, "tradingSymbol must not be null");
            Objects.requireNonNull(optionType, "optionType must not be null");
            Objects.requireNonNull(strike, "strike must not be null");
            Objects.requireNonNull(expiryDate, "expiryDate must not be null");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        }
    }

    /**
     * Immutable result of one adjustment operation.
     *
     * <p>When {@link #applied()} is {@code false}, the session is unchanged and
     * {@link #reasonCode()} carries a machine-readable block reason.
     */
    public record AdjustmentResult(
            /**
             * {@code true} when the adjustment was successfully applied.
             */
            boolean applied,

            /**
             * Session snapshot after the adjustment.
             * Equal to the input session when {@code applied = false}.
             */
            PositionSessionSnapshot updatedSession,

            /**
             * Machine-readable reason code for the outcome.
             * For blocked results this is the block reason (e.g., {@code COOLDOWN_ACTIVE}).
             * For applied results this is the audit action type.
             */
            String reasonCode,

            /**
             * Trader-readable explanation for the outcome.
             */
            String explanation
    ) {
        /**
         * Factory for a blocked (not applied) result.
         */
        public static AdjustmentResult blocked(
                PositionSessionSnapshot session,
                String reasonCode,
                String explanation) {
            return new AdjustmentResult(false, session, reasonCode, explanation);
        }
    }

    /**
     * Supplies the current market price for a given instrument.
     *
     * <p>In live/paper mode the implementation reads from the live tick feed.
     * In simulation mode a replay price is injected. Tests inject a lambda.
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
    // State
    // =========================================================================

    private final PositionSessionActionService actionService;
    private final Duration shiftCooldown;
    /** UTC instant of the last successful SHIFT_STRIKE; null if no shift has occurred. */
    private Instant lastShiftTs = null;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates an {@code AdjustmentAgent} with all configuration injected.
     *
     * @param actionService  applies leg-level exits and adds to the session snapshot
     * @param shiftCooldown  minimum duration that must elapse between successive
     *                       SHIFT_STRIKE operations on the same agent instance
     */
    public AdjustmentAgent(PositionSessionActionService actionService, Duration shiftCooldown) {
        this.actionService = Objects.requireNonNull(actionService, "actionService must not be null");
        Objects.requireNonNull(shiftCooldown, "shiftCooldown must not be null");
        if (shiftCooldown.isNegative()) {
            throw new IllegalArgumentException("shiftCooldown must not be negative");
        }
        this.shiftCooldown = shiftCooldown;
    }

    /**
     * Convenience constructor using {@link #DEFAULT_SHIFT_COOLDOWN} (30 minutes).
     *
     * @param actionService applies leg-level exits and adds to the session snapshot
     */
    public AdjustmentAgent(PositionSessionActionService actionService) {
        this(actionService, DEFAULT_SHIFT_COOLDOWN);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Exits the leg identified by {@code exitInstrumentId} and re-enters at the
     * strike described by {@code newLeg}, subject to cooldown rules.
     *
     * <p>Both the exit and the re-entry produce audit records in the returned session
     * snapshot. One leg per call.
     *
     * <p>Blocked (returns {@code applied=false}) when:
     * <ul>
     *   <li>Session status is {@code CLOSED}.</li>
     *   <li>The shift cooldown window has not expired since the last successful shift.</li>
     *   <li>No leg with {@code exitInstrumentId} is found in the active session.</li>
     *   <li>Price lookup fails for either leg.</li>
     * </ul>
     *
     * @param session            active position session; must not be null
     * @param exitInstrumentId   instrument ID of the leg to exit; must not be null or blank
     * @param newLeg             descriptor for the new leg to enter; must not be null
     * @param riskSnapshot       compatibility risk snapshot; accepted but not used for shift gating
     * @param priceSource        source for current market prices; must not be null
     * @param actionTs           UTC instant of the action; must not be null
     * @return adjustment result — never null
     */
    public AdjustmentResult shiftStrike(
            PositionSessionSnapshot session,
            String exitInstrumentId,
            NewLegDescriptor newLeg,
            RiskGuardSnapshot riskSnapshot,
            CurrentPriceSource priceSource,
            Instant actionTs) {

        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(exitInstrumentId, "exitInstrumentId must not be null");
        Objects.requireNonNull(newLeg, "newLeg must not be null");
        Objects.requireNonNull(riskSnapshot, "riskSnapshot must not be null");
        Objects.requireNonNull(priceSource, "priceSource must not be null");
        Objects.requireNonNull(actionTs, "actionTs must not be null");

        // Guard 1 — Session must be open
        if ("CLOSED".equals(session.status())) {
            return AdjustmentResult.blocked(session,
                    "SESSION_CLOSED",
                    "Session " + session.sessionId() + " is already CLOSED — commands rejected");
        }

                // Guard 2 — Cooldown: must wait at least shiftCooldown since last shift
        if (lastShiftTs != null) {
            Instant cooldownExpiry = lastShiftTs.plus(shiftCooldown);
            if (!actionTs.isAfter(cooldownExpiry)) {
                long secondsRemaining = java.time.temporal.ChronoUnit.SECONDS.between(actionTs, cooldownExpiry);
                LOG.info("[AdjustmentAgent] SHIFT_STRIKE blocked — cooldown active, " +
                         secondsRemaining + "s remaining");
                return AdjustmentResult.blocked(session,
                        "COOLDOWN_ACTIVE",
                        "Shift strike cooldown active — " + secondsRemaining +
                                "s remaining since last shift at " + lastShiftTs);
            }
        }

        // Guard 3 — Find the leg to exit
        PositionSessionSnapshot.PositionLegSnapshot exitLeg = session.legs().stream()
                .filter(leg -> exitInstrumentId.equals(leg.instrumentId()) && leg.openQuantity() > 0)
                .findFirst()
                .orElse(null);

        if (exitLeg == null) {
            return AdjustmentResult.blocked(session,
                    "LEG_NOT_FOUND",
                    "No open leg found with instrumentId=" + exitInstrumentId +
                            " in session " + session.sessionId());
        }

        // Resolve exit price for old leg
        BigDecimal exitPrice;
        try {
            exitPrice = priceSource.currentPrice(exitInstrumentId);
        } catch (Exception e) {
            LOG.warning("[AdjustmentAgent] Price lookup failed for exit leg " + exitInstrumentId +
                        ": " + e.getMessage());
            return AdjustmentResult.blocked(session,
                    "EXIT_PRICE_LOOKUP_FAILED_" + exitInstrumentId,
                    "Exit price lookup failed for " + exitInstrumentId + ": " + e.getMessage());
        }
        if (exitPrice == null) {
            return AdjustmentResult.blocked(session,
                    "NULL_EXIT_PRICE_" + exitInstrumentId,
                    "Exit price is null for " + exitInstrumentId);
        }

        // Resolve entry price for new leg
        BigDecimal entryPrice;
        try {
            entryPrice = priceSource.currentPrice(newLeg.instrumentId());
        } catch (Exception e) {
            LOG.warning("[AdjustmentAgent] Price lookup failed for new leg " + newLeg.instrumentId() +
                        ": " + e.getMessage());
            return AdjustmentResult.blocked(session,
                    "ENTRY_PRICE_LOOKUP_FAILED_" + newLeg.instrumentId(),
                    "Entry price lookup failed for " + newLeg.instrumentId() + ": " + e.getMessage());
        }
        if (entryPrice == null) {
            return AdjustmentResult.blocked(session,
                    "NULL_ENTRY_PRICE_" + newLeg.instrumentId(),
                    "Entry price is null for " + newLeg.instrumentId());
        }

        // Build exit action for old leg (closes it fully)
        PositionSessionActionRequest.LegAction exitAction = new PositionSessionActionRequest.LegAction(
                exitLeg.legId(),
                null,                       // entryPrice unchanged
                exitPrice,
                0,                          // addedQuantity
                exitLeg.openQuantity(),     // exit all open quantity
                null, null, null,           // delta fields
                null, null,                 // volume fields
                AUDIT_ACTION_SHIFT_EXIT,
                exitLeg.label(),
                exitLeg.optionType(),
                exitLeg.side(),
                exitLeg.strike(),
                "shift_strike_exit",
                "Exiting " + exitLeg.label() + " at " + exitPrice +
                        " to shift strike from " + exitLeg.strike() + " to " + newLeg.strike(),
                exitLeg.symbol(),
                exitLeg.instrumentId(),
                exitLeg.expiryDate()
        );

        // Build add action for new leg (re-entry)
        // legId = null → PositionSessionActionService creates a new leg
        String newLegLabel = newLeg.optionType() + " " + newLeg.strike();
        PositionSessionActionRequest.LegAction entryAction = new PositionSessionActionRequest.LegAction(
                null,                       // null legId → new leg
                entryPrice,
                null,                       // exitPrice — not exiting
                newLeg.quantity(),          // addedQuantity
                0,                          // exitedQuantity
                null, null, null,           // delta fields
                null, null,                 // volume fields
                AUDIT_ACTION_SHIFT_ENTRY,
                newLegLabel,
                newLeg.optionType(),
                exitLeg.side(),             // same side (SHORT) as the exited leg
                newLeg.strike(),
                "shift_strike_entry",
                "Entering " + newLegLabel + " at " + entryPrice +
                        " (shifted from strike " + exitLeg.strike() + ")",
                newLeg.tradingSymbol(),
                newLeg.instrumentId(),
                newLeg.expiryDate()
        );

        PositionSessionActionRequest request = new PositionSessionActionRequest(
                session.sessionId(),
                "SHIFT_STRIKE",
                actionTs,
                null,          // lastDeltaAdjustmentTs unchanged
                List.of(exitAction, entryAction)
        );

        PositionSessionSnapshot updatedSession = actionService.apply(session, request);

        // Record cooldown start
        lastShiftTs = actionTs;

        String explanation = "SHIFT_STRIKE applied: exited " + exitLeg.label() +
                " at strike " + exitLeg.strike() + " (exit price " + exitPrice +
                "), entered " + newLegLabel + " at strike " + newLeg.strike() +
                " (entry price " + entryPrice + "). Cooldown active for " +
                shiftCooldown.toMinutes() + " minutes.";

        LOG.info("[AdjustmentAgent] " + explanation + " | session=" + session.sessionId());

        return new AdjustmentResult(true, updatedSession, AUDIT_ACTION_SHIFT_EXIT, explanation);
    }

    /**
     * Exits the single leg identified by {@code instrumentId}.
     *
     * <p>The session remains open as long as at least one other leg has non-zero
     * open quantity. The exit produces one audit record in the returned session snapshot.
     *
     * <p>Blocked (returns {@code applied=false}) when:
     * <ul>
     *   <li>Session status is {@code CLOSED}.</li>
     *   <li>No open leg with {@code instrumentId} is found.</li>
     *   <li>Price lookup fails.</li>
     * </ul>
     *
     * <p>Note: EXIT_LEG is not Risk-Guard gated — it is a risk-reduction action that
     * should always be permitted when explicitly requested.
     *
     * @param session        active position session; must not be null
     * @param instrumentId   instrument ID of the leg to exit; must not be null or blank
     * @param priceSource    source for current market prices; must not be null
     * @param actionTs       UTC instant of the action; must not be null
     * @return adjustment result — never null
     */
    public AdjustmentResult exitLeg(
            PositionSessionSnapshot session,
            String instrumentId,
            CurrentPriceSource priceSource,
            Instant actionTs) {

        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        Objects.requireNonNull(priceSource, "priceSource must not be null");
        Objects.requireNonNull(actionTs, "actionTs must not be null");

        // Guard 1 — Session must not be closed
        if ("CLOSED".equals(session.status())) {
            return AdjustmentResult.blocked(session,
                    "SESSION_CLOSED",
                    "Session " + session.sessionId() + " is already CLOSED — commands rejected");
        }

        // Guard 2 — Find the leg
        PositionSessionSnapshot.PositionLegSnapshot targetLeg = session.legs().stream()
                .filter(leg -> instrumentId.equals(leg.instrumentId()) && leg.openQuantity() > 0)
                .findFirst()
                .orElse(null);

        if (targetLeg == null) {
            return AdjustmentResult.blocked(session,
                    "LEG_NOT_FOUND",
                    "No open leg found with instrumentId=" + instrumentId +
                            " in session " + session.sessionId());
        }

        // Resolve exit price
        BigDecimal exitPrice;
        try {
            exitPrice = priceSource.currentPrice(instrumentId);
        } catch (Exception e) {
            LOG.warning("[AdjustmentAgent] EXIT_LEG price lookup failed for " + instrumentId +
                        ": " + e.getMessage());
            return AdjustmentResult.blocked(session,
                    "EXIT_PRICE_LOOKUP_FAILED_" + instrumentId,
                    "Exit price lookup failed for " + instrumentId + ": " + e.getMessage());
        }
        if (exitPrice == null) {
            return AdjustmentResult.blocked(session,
                    "NULL_EXIT_PRICE_" + instrumentId,
                    "Exit price is null for " + instrumentId);
        }

        PositionSessionActionRequest.LegAction legAction = new PositionSessionActionRequest.LegAction(
                targetLeg.legId(),
                null,                        // entryPrice unchanged
                exitPrice,
                0,                           // addedQuantity
                targetLeg.openQuantity(),    // exit all open quantity
                null, null, null,            // delta fields
                null, null,                  // volume fields
                AUDIT_ACTION_EXIT_LEG,
                targetLeg.label(),
                targetLeg.optionType(),
                targetLeg.side(),
                targetLeg.strike(),
                "exit_leg",
                "Exiting leg " + targetLeg.label() + " at " + exitPrice,
                targetLeg.symbol(),
                targetLeg.instrumentId(),
                targetLeg.expiryDate()
        );

        PositionSessionActionRequest request = new PositionSessionActionRequest(
                session.sessionId(),
                "EXIT",
                actionTs,
                null,
                List.of(legAction)
        );

        PositionSessionSnapshot updatedSession = actionService.apply(session, request);

        String explanation = "EXIT_LEG: exited " + targetLeg.label() +
                " (" + instrumentId + ") at " + exitPrice +
                ". Session status: " + updatedSession.status();

        LOG.info("[AdjustmentAgent] " + explanation + " | session=" + session.sessionId());

        return new AdjustmentResult(true, updatedSession, AUDIT_ACTION_EXIT_LEG, explanation);
    }

    /**
     * Exits all open legs of the session at their current market prices.
     *
     * <p>After this call, {@link PositionSessionActionService} will compute the session
     * status as {@code CLOSED} (all legs have zero open quantity). Subsequent calls to
     * any method on the returned session will be blocked by the {@code CLOSED} guard.
     *
     * <p>Booked PnL = sum of {@code (entryPrice − exitPrice) × exitedQuantity} per leg.
     *
     * <p>Blocked (returns {@code applied=false}) when:
     * <ul>
     *   <li>Session status is already {@code CLOSED}.</li>
     *   <li>Price lookup fails for any open leg.</li>
     * </ul>
     *
     * <p>Note: EXIT_ALL is not Risk-Guard gated — it is a forced risk-reduction
     * action invoked when the Decision Agent emits {@code EXIT_ALL}.
     *
     * @param session     active position session; must not be null
     * @param priceSource source for current market prices; must not be null
     * @param actionTs    UTC instant of the action; must not be null
     * @return adjustment result — never null; when applied, session status is {@code CLOSED}
     */
    public AdjustmentResult exitAll(
            PositionSessionSnapshot session,
            CurrentPriceSource priceSource,
            Instant actionTs) {

        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(priceSource, "priceSource must not be null");
        Objects.requireNonNull(actionTs, "actionTs must not be null");

        // Guard — Session must not already be closed
        if ("CLOSED".equals(session.status())) {
            return AdjustmentResult.blocked(session,
                    "SESSION_CLOSED",
                    "Session " + session.sessionId() + " is already CLOSED — commands rejected");
        }

        List<PositionSessionSnapshot.PositionLegSnapshot> openLegs = session.legs().stream()
                .filter(leg -> leg.openQuantity() > 0)
                .toList();

        if (openLegs.isEmpty()) {
            return AdjustmentResult.blocked(session,
                    "NO_OPEN_LEGS",
                    "No open legs found in session " + session.sessionId());
        }

        // Resolve exit prices for all open legs
        List<PositionSessionActionRequest.LegAction> legActions = new ArrayList<>();
        for (PositionSessionSnapshot.PositionLegSnapshot leg : openLegs) {
            BigDecimal exitPrice;
            try {
                exitPrice = priceSource.currentPrice(leg.instrumentId());
            } catch (Exception e) {
                LOG.warning("[AdjustmentAgent] EXIT_ALL price lookup failed for " +
                            leg.instrumentId() + ": " + e.getMessage());
                return AdjustmentResult.blocked(session,
                        "EXIT_PRICE_LOOKUP_FAILED_" + leg.instrumentId(),
                        "Exit price lookup failed for " + leg.instrumentId() +
                                " during EXIT_ALL: " + e.getMessage());
            }
            if (exitPrice == null) {
                return AdjustmentResult.blocked(session,
                        "NULL_EXIT_PRICE_" + leg.instrumentId(),
                        "Exit price is null for " + leg.instrumentId() + " during EXIT_ALL");
            }

            legActions.add(new PositionSessionActionRequest.LegAction(
                    leg.legId(),
                    null,                    // entryPrice unchanged
                    exitPrice,
                    0,                       // addedQuantity
                    leg.openQuantity(),      // exit all open quantity
                    null, null, null,        // delta fields
                    null, null,              // volume fields
                    AUDIT_ACTION_EXIT_ALL,
                    leg.label(),
                    leg.optionType(),
                    leg.side(),
                    leg.strike(),
                    "exit_all",
                    "Exiting all legs — EXIT_ALL triggered for " + leg.label() +
                            " at " + exitPrice,
                    leg.symbol(),
                    leg.instrumentId(),
                    leg.expiryDate()
            ));
        }

        PositionSessionActionRequest request = new PositionSessionActionRequest(
                session.sessionId(),
                "EXIT",
                actionTs,
                null,
                legActions
        );

        PositionSessionSnapshot updatedSession = actionService.apply(session, request);

        // Sum booked PnL across all legs
        BigDecimal totalBookedPnl = updatedSession.legs().stream()
                .filter(leg -> leg.bookedPnl() != null)
                .map(PositionSessionSnapshot.PositionLegSnapshot::bookedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String explanation = "EXIT_ALL: exited " + openLegs.size() + " leg(s). " +
                "Session status: " + updatedSession.status() +
                ". Total booked PnL: " + totalBookedPnl + " pts.";

        LOG.info("[AdjustmentAgent] " + explanation + " | session=" + session.sessionId());

        return new AdjustmentResult(true, updatedSession, AUDIT_ACTION_EXIT_ALL, explanation);
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the UTC instant of the last successful SHIFT_STRIKE, or {@code null} if
     * no shift has been applied since this agent was created.
     */
    public Instant lastShiftTs() {
        return lastShiftTs;
    }
}
