package com.strategysquad.agentic.builder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable output contract of the {@link PositionBuilderAgent}.
 *
 * <p>One {@code PositionPlan} is produced per build attempt. It describes the
 * proposed short-option structure (straddle or strangle) in enough detail for
 * the Decision Agent to open a position session via
 * {@code PositionSessionActionService}.
 *
 * <h2>Rejected plans</h2>
 * <p>When the builder cannot construct a valid structure (no qualified candidates,
 * delta threshold breached, lot cap exceeded, etc.) it returns a rejected plan:
 * <ul>
 *   <li>{@link #riskGuardApproved()} is {@code false}</li>
 *   <li>{@link #rejectionReason()} is present with a machine-readable code</li>
 *   <li>{@link #legs()} is an empty list — never null</li>
 *   <li>{@link #estimatedNetDelta()} and {@link #estimatedTotalPremium()} are zero</li>
 * </ul>
 *
 * <h2>Unit conventions</h2>
 * <ul>
 *   <li>All premium values are in <b>NSE index points</b> — never rupees.</li>
 *   <li>Net delta is a dimensionless signed ratio (sum of per-leg empirical deltas
 *       weighted by lots). A near-zero net delta is the target for short straddles
 *       and strangles.</li>
 *   <li>{@link #lotCountPerLeg()} is the number of lots on each leg (symmetric across
 *       CE and PE in Phase 3).</li>
 * </ul>
 *
 * <h2>Immutability</h2>
 * <p>This is a pure value object. No setters. No logic. The legs list is made
 * unmodifiable at construction time. The compact constructor enforces all
 * non-null invariants.
 */
public record PositionPlan(

        /**
         * Stable unique identifier for this plan.
         * Generated as a random UUID at construction time.
         * Used to correlate the plan with the resulting position session and audit record.
         * Never null.
         */
        UUID planId,

        /**
         * UTC instant at which this plan was constructed by the builder.
         * In simulation mode this is the {@code SimulationClock} time, not wall clock.
         * Never null.
         */
        Instant plannedTs,

        /**
         * Underlying index name. One of {@code NIFTY} or {@code BANKNIFTY}.
         * Never null or blank.
         */
        String underlying,

        /**
         * Proposed legs of this structure. Each leg represents one contract side
         * (typically one CE leg and one PE leg for a two-legged structure).
         *
         * <p>Never null. For rejected plans this list is empty. For accepted plans
         * this list contains at least one leg.
         */
        List<PositionPlanLeg> legs,

        /**
         * Estimated net delta of the proposed structure at plan construction time.
         * Computed as the sum of per-leg empirical deltas weighted by lots and sign:
         * {@code CE_delta * lots + PE_delta * lots}.
         *
         * <p>For a short straddle, the CE empirical delta is typically positive
         * (option price rises when underlying rises) and the PE delta is typically
         * negative, so the net should be near zero. A value outside
         * {@code [-max_net_delta_threshold, +max_net_delta_threshold]} causes
         * rejection.
         *
         * <p>Units: dimensionless signed ratio. Zero for rejected plans.
         */
        double estimatedNetDelta,

        /**
         * Estimated total premium collected across all legs of this structure.
         * Computed as: {@code sum(leg.entryPrice * leg.lots * leg.lotSize)} for all legs.
         *
         * <p>Units: NSE index points × lots × lot-size = total points. Zero for
         * rejected plans.
         *
         * <p>Note: to convert to rupees, multiply by the lot size and the per-point
         * rupee value — this is not done here to keep the plan unit-clean.
         */
        double estimatedTotalPremium,

        /**
         * Structure type of this plan.
         * Allowed values: {@code SHORT_STRADDLE}, {@code SHORT_STRANGLE}.
         * Never null or blank.
         */
        String structureType,

        /**
         * Number of lots to trade on each leg. In Phase 3 this is always 1
         * (minimum conservative entry). Must be >= 1 for accepted plans.
         * Zero is allowed for rejected plans only.
         */
        int lotCountPerLeg,

        /**
         * {@code true} when this plan passed all structural and delta-threshold checks
         * and is ready to be submitted to the position session service.
         * {@code false} when the plan is rejected (see {@link #rejectionReason()}).
         */
        boolean riskGuardApproved,

        /**
         * If present, the machine-readable reason this plan was rejected.
         * Examples: {@code NO_QUALIFIED_CANDIDATES}, {@code NET_DELTA_EXCEEDS_THRESHOLD},
         * {@code LOT_CAP_EXCEEDED}, {@code MISSING_CE_CANDIDATE},
         * {@code MISSING_PE_CANDIDATE}.
         *
         * <p>{@link Optional#empty()} for accepted plans.
         * Never null — use {@link Optional#empty()} for accepted plans (not null).
         */
        Optional<String> rejectionReason

) {

    // =========================================================================
    // Compact constructor — non-null and consistency invariants
    // =========================================================================

    /**
     * Compact constructor enforcing all non-null invariants and ensuring
     * {@link #legs()} is never null (it is converted to an unmodifiable list).
     *
     * @throws IllegalArgumentException if required fields are null, blank, or
     *         inconsistent (e.g., accepted plan with empty legs, or missing
     *         rejection reason on a rejected plan)
     */
    public PositionPlan {
        if (planId == null) {
            throw new IllegalArgumentException("planId must not be null");
        }
        if (plannedTs == null) {
            throw new IllegalArgumentException("plannedTs must not be null");
        }
        if (underlying == null || underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        // Normalize legs to an unmodifiable list — never null
        legs = (legs == null) ? List.of() : List.copyOf(legs);
        if (structureType == null || structureType.isBlank()) {
            throw new IllegalArgumentException("structureType must not be blank");
        }
        if (rejectionReason == null) {
            throw new IllegalArgumentException(
                    "rejectionReason must not be null; use Optional.empty() for accepted plans");
        }
        // Consistency: accepted plans must have at least one leg
        if (riskGuardApproved && legs.isEmpty()) {
            throw new IllegalArgumentException(
                    "An accepted plan (riskGuardApproved=true) must have at least one leg");
        }
        // Consistency: rejected plans must have a rejection reason
        if (!riskGuardApproved && rejectionReason.isEmpty()) {
            throw new IllegalArgumentException(
                    "A rejected plan (riskGuardApproved=false) must have a rejectionReason");
        }
    }

    // =========================================================================
    // Factory helpers
    // =========================================================================

    /**
     * Creates a rejected {@code PositionPlan} with the given reason and no legs.
     *
     * <p>Convenience factory so callers do not need to supply empty/zero values
     * for all metric fields when the plan cannot be constructed.
     *
     * @param underlying    underlying name (e.g., {@code NIFTY})
     * @param structureType intended structure type (e.g., {@code SHORT_STRADDLE})
     * @param reason        machine-readable rejection reason; must not be blank
     * @return a rejected plan with no legs and all metrics at zero
     * @throws IllegalArgumentException if any parameter is null or blank
     */
    public static PositionPlan rejected(String underlying, String structureType, String reason) {
        if (underlying == null || underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        if (structureType == null || structureType.isBlank()) {
            throw new IllegalArgumentException("structureType must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("rejection reason must not be blank");
        }
        return new PositionPlan(
                UUID.randomUUID(),
                Instant.now(),
                underlying,
                List.of(),
                0.0,
                0.0,
                structureType,
                0,
                false,
                Optional.of(reason)
        );
    }
}
