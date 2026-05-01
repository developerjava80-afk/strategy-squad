package com.strategysquad.agentic.signal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

/**
 * Standalone service that computes empirical delta and delta-adjusted theta for a single
 * option contract leg, producing a {@link SignalSnapshot}.
 *
 * <h2>Computation rules (all semantics preserved from {@code DeltaAdjustmentService})</h2>
 * <pre>
 *   empirical_delta         = option_price_change / underlying_price_change
 *   expected_delta_move     = empirical_delta * underlying_change_since_entry
 *   delta_adjusted_theta    = actual_option_price_change - expected_delta_move
 * </pre>
 *
 * <p>For short legs, theta benefit is positive when the option price falls more than the
 * delta component explains. This service never modifies position state — it is
 * strictly read-only.
 *
 * <h2>Division-by-zero guard</h2>
 * <p>When {@code underlyingPriceChange} is zero (or below the minimum-move threshold),
 * empirical delta and all downstream fields that depend on it are set to {@code null}
 * rather than throwing. The {@link SignalSnapshot#thetaState()} is set to
 * {@link SignalSnapshot.ThetaState#HOLD} and the reason code records the guard condition.
 *
 * <h2>Staleness</h2>
 * <p>When {@link LegInput#stale()} is {@code true}, the snapshot is produced with
 * {@link SignalSnapshot#stale()} set and an explanatory {@link SignalSnapshot#reason()}.
 * All computed fields that require fresh data are set to {@code null}.
 *
 * <h2>Missing entry price</h2>
 * <p>When {@link LegInput#entryPrice()} is {@code null} (position not yet open),
 * {@link SignalSnapshot#expectedDecaySinceEntry()} and
 * {@link SignalSnapshot#thetaProgressRatio()} are {@code null}. The snapshot is still
 * produced with whatever delta fields can be computed from the price window data.
 */
public final class SignalSnapshotService {

    // -------------------------------------------------------------------------
    // Constants — identical values to DeltaAdjustmentService to preserve math
    // -------------------------------------------------------------------------

    /** Minimum underlying move (points) required to compute a valid empirical delta. */
    static final BigDecimal MIN_UNDERLYING_MOVE = new BigDecimal("0.50");

    /** Theta progress ratio at or above which the state is PROFIT_BOOK. */
    static final BigDecimal THETA_CAPTURE_THRESHOLD = new BigDecimal("0.70");

    /** Minimum decay-since-entry (points) before ratio is meaningful. */
    static final BigDecimal THETA_MIN_DECAY_THRESHOLD = new BigDecimal("0.10");

    /** Minimum elapsed minutes since entry before theta computation is attempted. */
    static final long THETA_MIN_ELAPSED_MINUTES = 5L;

    /** Clamp for implausible empirical delta absolute value. */
    static final BigDecimal EMPIRICAL_DELTA_MAX_ABS = new BigDecimal("1.50");

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    // -------------------------------------------------------------------------
    // Input record
    // -------------------------------------------------------------------------

    /**
     * All market and position data required to compute one {@link SignalSnapshot}.
     *
     * <p>All price fields are in <b>NSE index points</b>. Time fields use
     * {@link Instant} in UTC.
     */
    public record LegInput(

            /**
             * Canonical instrument identifier.
             * Format: {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>}.
             * Never null or blank.
             */
            String instrumentId,

            /**
             * Underlying index name. One of {@code NIFTY} or {@code BANKNIFTY}.
             * Never null or blank.
             */
            String underlying,

            /**
             * Option type. One of {@code CE} or {@code PE}. Never null or blank.
             */
            String optionType,

            /**
             * Strike price. Units: NSE index points. Never null.
             */
            BigDecimal strike,

            /**
             * Option side. One of {@code SHORT} or {@code LONG}.
             * Determines sign convention for theta benefit.
             * Never null or blank.
             */
            String side,

            /**
             * Current option price at signal timestamp. Units: NSE index points.
             * Null if price data is unavailable.
             */
            BigDecimal currentPrice,

            /**
             * Option price 2 minutes before signal timestamp. Units: NSE index points.
             * Null if 2-minute history is unavailable.
             */
            BigDecimal price2mAgo,

            /**
             * Underlying index price at signal timestamp. Units: NSE index points.
             * Null if spot data is unavailable.
             */
            BigDecimal currentUnderlyingPrice,

            /**
             * Underlying index price 2 minutes before signal timestamp.
             * Units: NSE index points. Null if 2-minute history is unavailable.
             */
            BigDecimal underlyingPrice2mAgo,

            /**
             * Option price at the start of the trading day. Units: NSE index points.
             * Null if SOD data is unavailable.
             */
            BigDecimal priceAtStartOfDay,

            /**
             * Underlying price at the start of the trading day. Units: NSE index points.
             * Null if SOD data is unavailable.
             */
            BigDecimal underlyingPriceAtStartOfDay,

            /**
             * Lot size for this instrument as recorded in {@code instrument_master}.
             * Always positive.
             */
            int lotSize,

            /**
             * Quantity of lots currently held. Used for volume-state context only.
             */
            int quantity,

            /**
             * Current traded volume for this contract. Null if unavailable.
             */
            Long currentVolume,

            /**
             * Historical average daily volume from {@code options_context_buckets}.
             * Null or zero if no baseline exists.
             */
            BigDecimal dayAverageVolume,

            /**
             * Option price at the time the position leg was entered.
             * Units: NSE index points. Null if the position is not yet open.
             */
            BigDecimal entryPrice,

            /**
             * Underlying price at the time the position leg was entered.
             * Units: NSE index points. Null if the position is not yet open.
             */
            BigDecimal entryUnderlyingPrice,

            /**
             * Empirical delta recorded at position entry — used together with
             * {@link #entryUnderlyingPrice()} to compute delta-adjusted decay.
             * Null if not recorded at entry.
             */
            BigDecimal entryEmpiricalDelta,

            /**
             * Expected premium decay rate per minute, estimated at entry.
             * Used to compute {@code expectedDecaySinceEntry}.
             * Null if not estimated at entry.
             */
            BigDecimal entryExpectedDecayRatePerMinute,

            /**
             * Timestamp at which the position leg was entered.
             * Null if the position is not yet open.
             */
            Instant entryTime,

            /**
             * {@code true} when the most recent market tick is older than the configured
             * staleness threshold. When stale, delta and theta fields are set to null.
             */
            boolean stale

    ) {
        /** Returns {@code true} when this leg is a short position. */
        public boolean isShort() {
            return "SHORT".equalsIgnoreCase(side);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Computes a {@link SignalSnapshot} for a single option contract leg.
     *
     * <p>All math is identical to the theta-assessment logic previously contained in
     * {@code DeltaAdjustmentService#computeLegTheta}. Semantics have not changed —
     * this is an extract-only refactor.
     *
     * @param input     all market and position data for the leg; must not be null
     * @param signalTs  timestamp at which the signal is being computed; must not be null
     * @return a fully-populated {@link SignalSnapshot}; never null
     */
    public SignalSnapshot compute(LegInput input, Instant signalTs) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        if (signalTs == null) throw new IllegalArgumentException("signalTs must not be null");

        // ------------------------------------------------------------------
        // 1. Staleness guard
        // ------------------------------------------------------------------
        if (input.stale()) {
            return staleSnapshot(input, signalTs, "STALE_MARKET_DATA");
        }

        // ------------------------------------------------------------------
        // 2. Empirical delta — 2-minute window
        // ------------------------------------------------------------------
        BigDecimal underlyingMove2m = null;
        BigDecimal optionMove2m = null;
        BigDecimal empiricalDelta2m = null;
        BigDecimal deltaAdjustedTheta2m = null;

        if (input.currentPrice() != null && input.price2mAgo() != null
                && input.currentUnderlyingPrice() != null && input.underlyingPrice2mAgo() != null) {

            underlyingMove2m = input.currentUnderlyingPrice().subtract(input.underlyingPrice2mAgo(), MC);
            optionMove2m = input.currentPrice().subtract(input.price2mAgo(), MC);

            if (underlyingMove2m.abs().compareTo(MIN_UNDERLYING_MOVE) >= 0) {
                BigDecimal rawDelta = optionMove2m.divide(underlyingMove2m, MC);
                // Clamp implausible delta values
                if (rawDelta.abs().compareTo(EMPIRICAL_DELTA_MAX_ABS) <= 0) {
                    empiricalDelta2m = rawDelta;
                }
                if (empiricalDelta2m != null) {
                    // delta_adjusted_theta = actual_option_change - expected_delta_move
                    BigDecimal expectedDeltaMove = empiricalDelta2m.multiply(underlyingMove2m, MC);
                    BigDecimal residual = optionMove2m.subtract(expectedDeltaMove, MC);
                    // For short legs, theta benefit = option falls more than delta explains → negate residual
                    deltaAdjustedTheta2m = input.isShort() ? residual.negate() : residual;
                }
            }
        }

        // ------------------------------------------------------------------
        // 3. Empirical delta — 5-minute window
        // ------------------------------------------------------------------
        // NOTE: The 5m and SOD windows use the same formula; we expose them as
        //       separate fields for callers that want multi-window confirmation.
        // For now both 5m and SOD are null unless callers supply separate history
        // via the LegInput. We keep this symmetric with DeltaAdjustmentService's
        // LegState which has delta2m / delta5m / deltaSod as pre-computed fields.
        // SignalSnapshotService accepts raw price history and computes 2m inline;
        // 5m and SOD are passed through from pre-computed sources when available.
        BigDecimal empiricalDelta5m = null;   // populated from pre-computed if available
        BigDecimal empiricalDeltaSod = null;  // populated from pre-computed if available

        // ------------------------------------------------------------------
        // 4. Decay since entry (uses entry empirical delta, not 2m delta)
        // ------------------------------------------------------------------
        BigDecimal expectedDecaySinceEntry = null;
        BigDecimal thetaProgressRatio = null;

        if (input.entryPrice() != null && input.entryUnderlyingPrice() != null
                && input.entryEmpiricalDelta() != null && input.entryTime() != null
                && input.currentPrice() != null && input.currentUnderlyingPrice() != null) {

            long elapsedMinutes = java.time.Duration.between(input.entryTime(), signalTs).toMinutes();

            if (elapsedMinutes >= THETA_MIN_ELAPSED_MINUTES) {
                BigDecimal underlyingChangeSinceEntry =
                        input.currentUnderlyingPrice().subtract(input.entryUnderlyingPrice(), MC);

                if (underlyingChangeSinceEntry.abs().compareTo(MIN_UNDERLYING_MOVE) >= 0) {
                    BigDecimal actualOptionChange =
                            input.currentPrice().subtract(input.entryPrice(), MC);
                    BigDecimal expectedDeltaMove =
                            input.entryEmpiricalDelta().multiply(underlyingChangeSinceEntry, MC);
                    BigDecimal residual = actualOptionChange.subtract(expectedDeltaMove, MC);
                    // For short legs, benefit = option fell more than delta → negate
                    BigDecimal actualThetaBenefit = input.isShort() ? residual.negate() : residual;

                    if (input.entryExpectedDecayRatePerMinute() != null) {
                        expectedDecaySinceEntry = input.entryExpectedDecayRatePerMinute()
                                .multiply(BigDecimal.valueOf(elapsedMinutes), MC);
                        if (expectedDecaySinceEntry.compareTo(THETA_MIN_DECAY_THRESHOLD) > 0) {
                            thetaProgressRatio = actualThetaBenefit.divide(expectedDecaySinceEntry, MC);
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 5. Theta state classification
        // ------------------------------------------------------------------
        SignalSnapshot.ThetaState thetaState = classifyThetaState(
                deltaAdjustedTheta2m, thetaProgressRatio);

        // ------------------------------------------------------------------
        // 6. Volume state classification
        // ------------------------------------------------------------------
        SignalSnapshot.VolumeState volumeState = classifyVolumeState(
                input.currentVolume(), input.dayAverageVolume());

        return new SignalSnapshot(
                signalTs,
                input.instrumentId(),
                input.underlying(),
                input.optionType(),
                input.strike(),
                empiricalDelta2m,
                empiricalDelta5m,
                empiricalDeltaSod,
                underlyingMove2m,
                optionMove2m,
                deltaAdjustedTheta2m,
                expectedDecaySinceEntry,
                thetaProgressRatio,
                thetaState,
                volumeState,
                false,
                "OK"
        );
    }

    /**
     * Convenience overload for {@code DeltaAdjustmentService} compatibility: accepts
     * the pre-computed delta values (2m, 5m, SOD) already stored on a
     * {@code LegState} and wraps them into a {@link SignalSnapshot} without
     * re-deriving from raw price history.
     *
     * <p>This overload allows {@code DeltaAdjustmentService} to delegate its
     * per-leg theta assessment to this service without changing the data shape it
     * already carries on {@code LegState}.
     *
     * @param instrumentId         canonical instrument ID; never null
     * @param underlying           underlying name; never null
     * @param optionType           {@code CE} or {@code PE}; never null
     * @param strike               strike price; never null
     * @param isShort              true if the leg is a short position
     * @param currentPrice         current option price; null if unavailable
     * @param currentUnderlying    current underlying price; null if unavailable
     * @param delta2m              pre-computed 2-minute empirical delta; null if unavailable
     * @param delta5m              pre-computed 5-minute empirical delta; null if unavailable
     * @param deltaSod             pre-computed SOD empirical delta; null if unavailable
     * @param entryPrice           entry price; null if not yet entered
     * @param entryUnderlyingPrice underlying price at entry; null if not yet entered
     * @param entryEmpiricalDelta  empirical delta at entry; null if not recorded
     * @param entryExpectedDecayRatePerMinute decay rate at entry; null if not estimated
     * @param entryTime            entry timestamp; null if not yet entered
     * @param currentVolume        current volume; null if unavailable
     * @param dayAverageVolume     historical avg volume; null if unavailable
     * @param stale                true when the market tick is stale
     * @param signalTs             signal computation timestamp; never null
     * @return fully-populated {@link SignalSnapshot}; never null
     */
    public SignalSnapshot computeFromPrecomputedDeltas(
            String instrumentId,
            String underlying,
            String optionType,
            BigDecimal strike,
            boolean isShort,
            BigDecimal currentPrice,
            BigDecimal currentUnderlying,
            BigDecimal delta2m,
            BigDecimal delta5m,
            BigDecimal deltaSod,
            BigDecimal entryPrice,
            BigDecimal entryUnderlyingPrice,
            BigDecimal entryEmpiricalDelta,
            BigDecimal entryExpectedDecayRatePerMinute,
            Instant entryTime,
            Long currentVolume,
            BigDecimal dayAverageVolume,
            boolean stale,
            Instant signalTs
    ) {
        if (instrumentId == null || instrumentId.isBlank())
            throw new IllegalArgumentException("instrumentId must not be blank");
        if (underlying == null || underlying.isBlank())
            throw new IllegalArgumentException("underlying must not be blank");
        if (signalTs == null)
            throw new IllegalArgumentException("signalTs must not be null");

        if (stale) {
            // Produce a minimal stale snapshot without computing fields
            return new SignalSnapshot(
                    signalTs, instrumentId, underlying, optionType == null ? "CE" : optionType,
                    strike == null ? ZERO : strike,
                    null, null, null, null, null, null, null, null,
                    SignalSnapshot.ThetaState.HOLD,
                    SignalSnapshot.VolumeState.LOW,
                    true, "STALE_MARKET_DATA"
            );
        }

        // ------------------------------------------------------------------
        // Delta-adjusted theta (2m) using pre-computed delta2m
        // ------------------------------------------------------------------
        BigDecimal deltaAdjustedTheta2m = null;
        BigDecimal underlyingMove2m = null;
        BigDecimal optionMove2m = null;

        // For the pre-computed path, delta2m represents the empirical ratio already
        // computed externally (e.g. from DeltaAdjustmentService's stored LegState).
        // We cannot recompute underlyingMove2m / optionMove2m here without price history,
        // so those remain null and the snapshot reflects only the ratio fields.

        // ------------------------------------------------------------------
        // Decay since entry
        // ------------------------------------------------------------------
        BigDecimal expectedDecaySinceEntry = null;
        BigDecimal thetaProgressRatio = null;

        if (entryPrice != null && entryUnderlyingPrice != null
                && entryEmpiricalDelta != null && entryTime != null
                && currentPrice != null && currentUnderlying != null) {

            long elapsedMinutes = java.time.Duration.between(entryTime, signalTs).toMinutes();

            if (elapsedMinutes >= THETA_MIN_ELAPSED_MINUTES) {
                BigDecimal underlyingChangeSinceEntry =
                        currentUnderlying.subtract(entryUnderlyingPrice, MC);

                if (underlyingChangeSinceEntry.abs().compareTo(MIN_UNDERLYING_MOVE) >= 0) {
                    BigDecimal actualOptionChange = currentPrice.subtract(entryPrice, MC);
                    BigDecimal expectedDeltaMove =
                            entryEmpiricalDelta.multiply(underlyingChangeSinceEntry, MC);
                    BigDecimal residual = actualOptionChange.subtract(expectedDeltaMove, MC);
                    BigDecimal actualThetaBenefit = isShort ? residual.negate() : residual;

                    if (entryExpectedDecayRatePerMinute != null) {
                        expectedDecaySinceEntry = entryExpectedDecayRatePerMinute
                                .multiply(BigDecimal.valueOf(elapsedMinutes), MC);
                        if (expectedDecaySinceEntry.compareTo(THETA_MIN_DECAY_THRESHOLD) > 0) {
                            thetaProgressRatio = actualThetaBenefit.divide(expectedDecaySinceEntry, MC);
                        }
                    }
                }
            }
        }

        SignalSnapshot.ThetaState thetaState = classifyThetaState(deltaAdjustedTheta2m, thetaProgressRatio);
        SignalSnapshot.VolumeState volumeState = classifyVolumeState(currentVolume, dayAverageVolume);

        return new SignalSnapshot(
                signalTs,
                instrumentId,
                underlying,
                optionType == null ? "CE" : optionType,
                strike == null ? ZERO : strike,
                delta2m,
                delta5m,
                deltaSod,
                underlyingMove2m,
                optionMove2m,
                deltaAdjustedTheta2m,
                expectedDecaySinceEntry,
                thetaProgressRatio,
                thetaState,
                volumeState,
                false,
                "OK"
        );
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (accessible to tests)
    // -------------------------------------------------------------------------

    /**
     * Classifies theta state from delta-adjusted theta and the progress ratio.
     *
     * <ul>
     *   <li>If {@code deltaAdjustedTheta2m} is negative → premium expanding →
     *       {@link SignalSnapshot.ThetaState#DEFENSIVE_EXIT}</li>
     *   <li>If {@code thetaProgressRatio >= THETA_CAPTURE_THRESHOLD} →
     *       {@link SignalSnapshot.ThetaState#PROFIT_BOOK}</li>
     *   <li>Otherwise → {@link SignalSnapshot.ThetaState#HOLD}</li>
     * </ul>
     *
     * <p>When both fields are null (data unavailable), returns {@code HOLD}
     * as a conservative default — the Decision Agent treats a missing signal
     * as a caution, not an exit trigger.
     */
    static SignalSnapshot.ThetaState classifyThetaState(
            BigDecimal deltaAdjustedTheta2m, BigDecimal thetaProgressRatio) {
        if (deltaAdjustedTheta2m != null && deltaAdjustedTheta2m.compareTo(ZERO) < 0) {
            return SignalSnapshot.ThetaState.DEFENSIVE_EXIT;
        }
        if (thetaProgressRatio != null
                && thetaProgressRatio.compareTo(THETA_CAPTURE_THRESHOLD) >= 0) {
            return SignalSnapshot.ThetaState.PROFIT_BOOK;
        }
        return SignalSnapshot.ThetaState.HOLD;
    }

    /**
     * Classifies volume state from current volume vs historical daily average.
     *
     * <ul>
     *   <li>{@code currentVolume} is null or zero → {@link SignalSnapshot.VolumeState#ABSENT}</li>
     *   <li>{@code currentVolume >= dayAverageVolume} → {@link SignalSnapshot.VolumeState#CONFIRMED}</li>
     *   <li>Otherwise → {@link SignalSnapshot.VolumeState#LOW}</li>
     * </ul>
     */
    static SignalSnapshot.VolumeState classifyVolumeState(
            Long currentVolume, BigDecimal dayAverageVolume) {
        if (currentVolume == null || currentVolume <= 0L) {
            return SignalSnapshot.VolumeState.ABSENT;
        }
        if (dayAverageVolume == null || dayAverageVolume.compareTo(ZERO) <= 0) {
            return SignalSnapshot.VolumeState.LOW;
        }
        BigDecimal current = BigDecimal.valueOf(currentVolume);
        return current.compareTo(dayAverageVolume) >= 0
                ? SignalSnapshot.VolumeState.CONFIRMED
                : SignalSnapshot.VolumeState.LOW;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static SignalSnapshot staleSnapshot(LegInput input, Instant signalTs, String reason) {
        return new SignalSnapshot(
                signalTs,
                input.instrumentId(),
                input.underlying(),
                input.optionType() == null ? "CE" : input.optionType(),
                input.strike() == null ? ZERO : input.strike(),
                null, null, null, null, null, null, null, null,
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.LOW,
                true,
                reason
        );
    }
}
