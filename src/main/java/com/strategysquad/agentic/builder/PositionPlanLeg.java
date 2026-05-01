package com.strategysquad.agentic.builder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable representation of a single leg in a proposed {@link PositionPlan}.
 *
 * <p>Each leg corresponds to one option contract that the Position Builder Agent
 * intends to short-sell as part of a short straddle or short strangle structure.
 * A {@link PositionPlan} carries exactly one {@code PositionPlanLeg} per contract
 * side (one CE leg and one PE leg for a two-legged structure).
 *
 * <h2>Sign and unit conventions</h2>
 * <ul>
 *   <li>All price fields ({@link #entryPrice()}) are in <b>NSE index points</b> —
 *       never rupees.</li>
 *   <li>{@link #side()} is always {@link Side#SHORT} in the current implementation.
 *       Long-side legs are not supported until defined-risk variants are added in a
 *       later phase.</li>
 *   <li>{@link #lotSize()} is the per-lot contract multiplier for the underlying
 *       (NIFTY = 65, BANKNIFTY = 30) as read from {@code instrument_master}. It is
 *       never hard-coded in the builder — always sourced from the database.</li>
 *   <li>{@link #lots()} is the number of lots this leg proposes to trade. In Phase 3
 *       the builder starts at 1 lot per leg (minimum conservative entry).</li>
 * </ul>
 *
 * <h2>Immutability</h2>
 * <p>This is a pure value object. No setters. No logic. All invariants are enforced
 * in the compact constructor.
 */
public record PositionPlanLeg(

        /**
         * Canonical instrument identifier from {@code instrument_master}.
         * Format: {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>}.
         * Never null or blank.
         */
        String instrumentId,

        /**
         * Option type. One of {@code CE} (call) or {@code PE} (put).
         * Never null or blank.
         */
        String optionType,

        /**
         * Strike price of this contract.
         * Units: NSE index points (e.g., 24800.00 for NIFTY 24800 CE).
         * Never null.
         */
        BigDecimal strike,

        /**
         * Expiry date of this contract (NSE calendar date, no time component).
         * Never null.
         */
        LocalDate expiryDate,

        /**
         * Contract multiplier for this underlying, sourced from
         * {@code instrument_master.lot_size}.
         * Units: contracts per lot (e.g., 65 for NIFTY, 30 for BANKNIFTY).
         * Never hard-coded — always read from the database at plan construction time.
         * Must be >= 1.
         */
        int lotSize,

        /**
         * Number of lots this leg proposes to trade.
         * Units: lots (each lot covers {@link #lotSize()} contracts).
         * In Phase 3 the builder starts at 1 lot per leg. Must be >= 1.
         */
        int lots,

        /**
         * Expected entry price for this leg at plan construction time, sourced from
         * the scanner candidate's {@code last_price} or {@code bid_price} field.
         * Units: NSE index points. Positive value.
         * Never null.
         */
        BigDecimal entryPrice,

        /**
         * Direction of this leg. Always {@link Side#SHORT} in the current
         * implementation — the Position Builder only constructs short-option legs.
         * Never null.
         */
        Side side

) {

    // =========================================================================
    // Nested enum
    // =========================================================================

    /**
     * Direction of an option position leg.
     *
     * <p>In Phase 3 only {@link #SHORT} is used. Long-side legs will be added
     * when defined-risk variants (e.g., iron condor) are supported in a later
     * phase, at which point this enum will be extended.
     */
    public enum Side {

        /**
         * Short option: the leg represents a sold option contract.
         * Premium collected at entry; maximum gain is the collected premium;
         * theoretical maximum loss is unbounded (call) or strike-bounded (put).
         */
        SHORT
    }

    // =========================================================================
    // Compact constructor — non-null and range invariants
    // =========================================================================

    /**
     * Compact constructor enforcing all non-null and range invariants.
     *
     * @throws IllegalArgumentException if any required field is null, blank,
     *         or out of range
     */
    public PositionPlanLeg {
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId must not be blank");
        }
        if (optionType == null || (!optionType.equals("CE") && !optionType.equals("PE"))) {
            throw new IllegalArgumentException("optionType must be CE or PE");
        }
        if (strike == null) {
            throw new IllegalArgumentException("strike must not be null");
        }
        if (expiryDate == null) {
            throw new IllegalArgumentException("expiryDate must not be null");
        }
        if (lotSize < 1) {
            throw new IllegalArgumentException("lotSize must be >= 1, got: " + lotSize);
        }
        if (lots < 1) {
            throw new IllegalArgumentException("lots must be >= 1, got: " + lots);
        }
        if (entryPrice == null) {
            throw new IllegalArgumentException("entryPrice must not be null");
        }
        if (entryPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("entryPrice must not be negative, got: " + entryPrice);
        }
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
    }
}
