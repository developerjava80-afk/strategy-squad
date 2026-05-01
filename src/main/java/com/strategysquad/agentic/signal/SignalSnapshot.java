package com.strategysquad.agentic.signal;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable per-contract signal snapshot produced by the theta/delta signal engine.
 *
 * <p>Each instance captures the empirical delta and theta observations for one option
 * contract at a single point in time. It is the primary output contract consumed by
 * the Decision Agent when evaluating whether to hold, book, or exit a short-option leg.
 *
 * <h2>Sign conventions</h2>
 * <ul>
 *   <li>All price and move fields use <b>NSE index points</b> — never rupees.</li>
 *   <li>Empirical delta is a signed dimensionless ratio
 *       ({@code option_price_change / underlying_price_change}).
 *       For short CE legs it is negative (option rises when underlying rises);
 *       for short PE legs it is positive.</li>
 *   <li>{@link #deltaAdjustedTheta2m()} is the actual option price change minus the
 *       expected delta-induced move. For a short leg, a positive value means the
 *       option is decaying faster than the delta component — beneficial for the
 *       premium seller.</li>
 *   <li>{@link #thetaProgressRatio()} is {@code expected_decay_since_entry /
 *       total_entry_premium}. Range: 0.0–1.0; a value {@code >= 0.75} is the
 *       default profit-booking threshold in {@code DecisionPolicy}.</li>
 * </ul>
 *
 * <h2>Staleness</h2>
 * <p>The {@link #stale()} flag is {@code true} when the most recent tick for this
 * instrument arrived more than the configured {@code stale_data_seconds} threshold
 * before {@link #signalTs()}. Stale snapshots must not be used for decision-making
 * without explicit operator override. The {@link #reason()} field provides a
 * machine-readable explanation when stale (e.g., {@code "NO_TICK_FOR_120S"}).
 *
 * <h2>Immutability</h2>
 * <p>This is a pure value object. Do not add scoring, decision, or database write
 * logic here. The computation that populates this record lives in
 * {@code SignalSnapshotService}.
 */
public record SignalSnapshot(

        /**
         * Timestamp at which this signal was computed.
         * ISO-8601 instant in UTC. Never null.
         */
        Instant signalTs,

        /**
         * Canonical instrument identifier from {@code instrument_master}.
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
         * Option type. One of {@code CE} (call) or {@code PE} (put).
         * Never null or blank.
         */
        String optionType,

        /**
         * Strike price of this contract.
         * Units: NSE index points (e.g., 24800.00).
         * Never null.
         */
        BigDecimal strike,

        /**
         * Empirical delta computed over the most recent 2-minute window.
         * Dimensionless ratio: {@code option_price_change_2m / underlying_price_change_2m}.
         * Null if the 2-minute window has insufficient data or zero underlying move.
         */
        BigDecimal empiricalDelta2m,

        /**
         * Empirical delta computed over the most recent 5-minute window.
         * Dimensionless ratio: {@code option_price_change_5m / underlying_price_change_5m}.
         * Null if the 5-minute window has insufficient data or zero underlying move.
         */
        BigDecimal empiricalDelta5m,

        /**
         * Empirical delta computed from start-of-day (SOD) to signal timestamp.
         * Dimensionless ratio: {@code option_price_change_sod / underlying_price_change_sod}.
         * Null if insufficient SOD data is available or zero underlying move.
         */
        BigDecimal empiricalDeltaSod,

        /**
         * Change in the underlying index price over the 2-minute window ending at
         * {@link #signalTs()}.
         * Units: NSE index points. Positive means the underlying moved up.
         * Null if 2-minute historical data is unavailable.
         */
        BigDecimal underlyingMove2m,

        /**
         * Actual change in this option's price over the 2-minute window ending at
         * {@link #signalTs()}.
         * Units: NSE index points. Positive means the option price increased.
         * Null if 2-minute historical data is unavailable.
         */
        BigDecimal optionMove2m,

        /**
         * Delta-adjusted theta benefit over the 2-minute window.
         * Computed as: {@code option_move_2m − (empirical_delta_2m × underlying_move_2m)}.
         * For a short leg, a positive value means the option is decaying beyond what
         * the delta component explains — favourable for the premium seller.
         * Units: NSE index points.
         * Null if either {@link #empiricalDelta2m()} or {@link #underlyingMove2m()} is null.
         */
        BigDecimal deltaAdjustedTheta2m,

        /**
         * Expected total premium decay since the leg was entered.
         * Computed as: {@code entry_price − current_option_price}, adjusted for
         * delta-induced moves.
         * Units: NSE index points. Positive means the option has decayed relative to
         * entry, which is beneficial for the short leg.
         * Null if entry price is unavailable (position not yet open or session not
         * found).
         */
        BigDecimal expectedDecaySinceEntry,

        /**
         * Theta progress ratio: proportion of the entry premium that has been
         * captured as decay since entry.
         * Computed as: {@code expected_decay_since_entry / entry_price}.
         * Range: 0.0 to 1.0 (may exceed 1.0 if decay exceeds entry premium due to
         * delta moves).
         * The default profit-booking threshold in {@code DecisionPolicy} is {@code 0.75}.
         * Null if {@link #expectedDecaySinceEntry()} is null.
         */
        BigDecimal thetaProgressRatio,

        /**
         * Theta state classification derived from {@link #thetaProgressRatio()} and
         * premium expansion signals.
         * Never null.
         *
         * @see ThetaState
         */
        ThetaState thetaState,

        /**
         * Volume state classification for this contract at signal time.
         * Derived from current volume vs historical average volume.
         * Never null.
         *
         * @see VolumeState
         */
        VolumeState volumeState,

        /**
         * {@code true} when the most recent market tick for this instrument arrived
         * more than the configured {@code stale_data_seconds} threshold before
         * {@link #signalTs()}.
         *
         * <p>A stale snapshot indicates that the underlying price and option price used
         * to compute delta and theta metrics may no longer reflect actual market
         * conditions. The Decision Agent must not emit {@code ENTER} or {@code ADD}
         * commands when any relevant signal is stale, and must route stale signals
         * through the Risk Guard before any other decision. The {@link #reason()} field
         * provides a machine-readable explanation (e.g., {@code "NO_TICK_FOR_120S"},
         * {@code "FEED_GAP_DETECTED"}).
         */
        boolean stale,

        /**
         * Machine-readable reason code that explains the signal state.
         * For stale signals, this contains the staleness cause (e.g.,
         * {@code "NO_TICK_FOR_120S"}, {@code "FEED_GAP_DETECTED"}).
         * For fresh signals in normal state this may be {@code "OK"} or blank.
         * Never null — use {@code "OK"} for normal healthy signals.
         */
        String reason

) {

    // =========================================================================
    // Nested enums
    // =========================================================================

    /**
     * Theta-decay state classification for a short-option leg.
     *
     * <p>Produced by {@code SignalSnapshotService} based on the
     * {@link SignalSnapshot#thetaProgressRatio()} and the behaviour of
     * {@link SignalSnapshot#deltaAdjustedTheta2m()} over recent windows.
     *
     * <h2>Decision Agent use</h2>
     * <ul>
     *   <li>{@link #PROFIT_BOOK} — {@code thetaProgressRatio >= 0.75}: emit
     *       {@code BOOK_PROFIT} if PnL is positive and Risk Guard allows.</li>
     *   <li>{@link #HOLD} — normal decay on track; no action required.</li>
     *   <li>{@link #DEFENSIVE_EXIT} — premium is expanding faster than delta
     *       explains; escalate to Risk Guard for possible {@code REDUCE} or
     *       {@code EXIT_LEG}.</li>
     * </ul>
     */
    public enum ThetaState {

        /**
         * Sufficient theta decay has been captured to trigger profit booking.
         * Corresponds to {@code thetaProgressRatio >= 0.75} (configurable threshold
         * in {@code DecisionPolicy}).
         */
        PROFIT_BOOK,

        /**
         * Theta decay is progressing normally. No booking or defensive action required.
         * The position should be held and monitored.
         */
        HOLD,

        /**
         * Premium is expanding — the option price is increasing beyond what the delta
         * component explains. Indicates adverse market conditions for short sellers.
         * The Decision Agent should escalate to the Risk Guard and consider
         * {@code REDUCE} or {@code EXIT_LEG}.
         */
        DEFENSIVE_EXIT
    }

    /**
     * Volume state classification for a short-option contract at signal time.
     *
     * <p>Produced by {@code SignalSnapshotService} from the ratio of current volume
     * to the historical average volume from {@code options_context_buckets} for the
     * matching cohort.
     *
     * <h2>Decision Agent use</h2>
     * <ul>
     *   <li>{@link #CONFIRMED} — volume is at or above historical average;
     *       entry and adjustment decisions may proceed.</li>
     *   <li>{@link #LOW} — volume below historical average; treat as a caution
     *       signal. Entry should be deprioritised but not blocked.</li>
     *   <li>{@link #ABSENT} — zero or near-zero volume; the contract is illiquid.
     *       {@code CandidateScoringEngine} will have already disqualified this
     *       candidate. If encountered in a live snapshot, the Risk Guard should
     *       consider blocking new entries.</li>
     * </ul>
     */
    public enum VolumeState {

        /**
         * Current volume is at or above the historical cohort average.
         * Liquidity is sufficient for entry and adjustment.
         */
        CONFIRMED,

        /**
         * Current volume is below the historical cohort average but not zero.
         * Entry should be deprioritised; existing positions may be held.
         */
        LOW,

        /**
         * Volume is zero or effectively zero. The contract is illiquid.
         * New entries must not proceed; Risk Guard should be notified.
         */
        ABSENT
    }

    // =========================================================================
    // Compact constructor — non-null invariants
    // =========================================================================

    /**
     * Compact constructor enforcing non-null invariants on all required fields.
     * Nullable fields ({@code empiricalDelta2m}, {@code empiricalDelta5m}, etc.)
     * are permitted to be null as documented on each accessor.
     */
    public SignalSnapshot {
        if (signalTs == null) {
            throw new IllegalArgumentException("signalTs must not be null");
        }
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId must not be blank");
        }
        if (underlying == null || underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        if (optionType == null || (!optionType.equals("CE") && !optionType.equals("PE"))) {
            throw new IllegalArgumentException("optionType must be CE or PE");
        }
        if (strike == null) {
            throw new IllegalArgumentException("strike must not be null");
        }
        if (thetaState == null) {
            throw new IllegalArgumentException("thetaState must not be null");
        }
        if (volumeState == null) {
            throw new IllegalArgumentException("volumeState must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null; use \"OK\" for healthy signals");
        }
    }
}
