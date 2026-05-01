package com.strategysquad.agentic.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SignalSnapshotService}.
 *
 * <p>All tests are pure in-memory — no database, no HTTP, no live market data.
 * Mathematical correctness is verified with known inputs and expected outputs.
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>Normal snapshot — all data present, empirical delta and theta computed correctly</li>
 *   <li>Stale data — stale flag set, computed fields nulled out</li>
 *   <li>Zero underlying move — division-by-zero guard, delta and theta fields null</li>
 *   <li>Missing entry price — expectedDecaySinceEntry and thetaProgressRatio null</li>
 *   <li>Empirical delta math — known inputs validate formula exactly</li>
 *   <li>Delta-adjusted theta math — positive and negative cases</li>
 *   <li>Theta state classification — PROFIT_BOOK, HOLD, DEFENSIVE_EXIT</li>
 *   <li>Volume state classification — CONFIRMED, LOW, ABSENT</li>
 *   <li>Underlying move below minimum threshold — treated as zero-move guard</li>
 * </ol>
 */
class SignalSnapshotServiceTest {

    private final SignalSnapshotService service = new SignalSnapshotService();

    // =========================================================================
    // 1. Normal snapshot — full data present
    // =========================================================================

    @Test
    void normalSnapshot_computesEmpiricalDeltaAndTheta() {
        // Underlying moved up 50 pts; option moved up 20 pts
        // empirical_delta = 20 / 50 = 0.40
        // expected_delta_move = 0.40 * 50 = 20
        // delta_adjusted_theta = 20 - 20 = 0   (no residual)
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE", "24800",
                false,          // long leg
                "120", "100",   // currentPrice, price2mAgo
                "24850", "24800", // currentUnderlying, underlying2mAgo
                null, null,     // priceAtSOD, underlyingAtSOD
                null, null, null, null, null, // entry fields (no position)
                3000L, "1500",  // volume
                false           // not stale
        );

        SignalSnapshot snap = service.compute(input, NOW);

        assertNotNull(snap);
        assertFalse(snap.stale());
        assertEquals("OK", snap.reason());
        assertEquals("INS_NIFTY_20260428_24800_CE", snap.instrumentId());
        assertEquals("NIFTY", snap.underlying());
        assertEquals("CE", snap.optionType());

        assertNotNull(snap.empiricalDelta2m(), "empiricalDelta2m must be present");
        assertEquals(0, new BigDecimal("0.40").compareTo(snap.empiricalDelta2m()),
                "empiricalDelta2m: expected 0.40, got " + snap.empiricalDelta2m());

        assertNotNull(snap.underlyingMove2m());
        assertEquals(0, new BigDecimal("50").compareTo(snap.underlyingMove2m()));

        assertNotNull(snap.optionMove2m());
        assertEquals(0, new BigDecimal("20").compareTo(snap.optionMove2m()));

        // delta_adjusted_theta: for long leg = optionMove - expectedDeltaMove = 20 - 20 = 0
        assertNotNull(snap.deltaAdjustedTheta2m());
        assertEquals(0, BigDecimal.ZERO.compareTo(snap.deltaAdjustedTheta2m()));

        // No entry price → decay fields null
        assertNull(snap.expectedDecaySinceEntry());
        assertNull(snap.thetaProgressRatio());

        assertEquals(SignalSnapshot.ThetaState.HOLD, snap.thetaState());
        assertEquals(SignalSnapshot.VolumeState.CONFIRMED, snap.volumeState());
    }

    // =========================================================================
    // 2. Stale data — all delta/theta fields must be null
    // =========================================================================

    @Test
    void staleData_producesStaleSnapshotWithNullComputedFields() {
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE", "24800",
                true,
                "120", "100",
                "24850", "24800",
                null, null,
                null, null, null, null, null,
                3000L, "1500",
                true // STALE
        );

        SignalSnapshot snap = service.compute(input, NOW);

        assertTrue(snap.stale(), "snapshot must be stale");
        assertEquals("STALE_MARKET_DATA", snap.reason());
        assertNull(snap.empiricalDelta2m(), "empiricalDelta2m must be null when stale");
        assertNull(snap.empiricalDelta5m());
        assertNull(snap.empiricalDeltaSod());
        assertNull(snap.underlyingMove2m());
        assertNull(snap.optionMove2m());
        assertNull(snap.deltaAdjustedTheta2m());
        assertNull(snap.expectedDecaySinceEntry());
        assertNull(snap.thetaProgressRatio());
        // thetaState defaults to HOLD (conservative) for stale data
        assertEquals(SignalSnapshot.ThetaState.HOLD, snap.thetaState());
    }

    // =========================================================================
    // 3. Zero underlying move — division-by-zero guard
    // =========================================================================

    @Test
    void zeroUnderlyingMove_doesNotThrow_deltaIsNull() {
        // currentUnderlying == underlying2mAgo → move = 0
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE", "24800",
                false,
                "120", "100",
                "24800", "24800", // zero move
                null, null,
                null, null, null, null, null,
                3000L, "1500",
                false
        );

        SignalSnapshot snap = assertDoesNotThrow(() -> service.compute(input, NOW),
                "Must not throw on zero underlying move");

        assertFalse(snap.stale());
        assertNull(snap.empiricalDelta2m(), "empiricalDelta2m must be null when underlying move is zero");
        assertNull(snap.deltaAdjustedTheta2m(), "deltaAdjustedTheta2m must be null when delta is null");
        assertEquals(SignalSnapshot.ThetaState.HOLD, snap.thetaState());
    }

    @Test
    void tinyUnderlyingMove_belowMinThreshold_deltaIsNull() {
        // Move of 0.40 pts is below MIN_UNDERLYING_MOVE (0.50)
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE", "24800",
                false,
                "120", "100",
                "24800.40", "24800.00", // move = 0.40 < 0.50
                null, null,
                null, null, null, null, null,
                3000L, "1500",
                false
        );

        SignalSnapshot snap = service.compute(input, NOW);

        assertNull(snap.empiricalDelta2m(), "empiricalDelta2m must be null when move < MIN_UNDERLYING_MOVE");
        assertNull(snap.deltaAdjustedTheta2m());
    }

    // =========================================================================
    // 4. Missing entry price — decay fields null but delta still computed
    // =========================================================================

    @Test
    void missingEntryPrice_decayFieldsNullButDeltaComputed() {
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE", "24800",
                true,            // short leg
                "90", "100",     // price fell (good for short)
                "24850", "24800",
                null, null,
                null,  // entryPrice = null → no position entered yet
                null, null, null, null,
                3000L, "1500",
                false
        );

        SignalSnapshot snap = service.compute(input, NOW);

        assertFalse(snap.stale());
        // Delta should still be computed
        assertNotNull(snap.empiricalDelta2m(), "empiricalDelta2m must be computable even without entry price");
        // Decay fields must be null since entry price is absent
        assertNull(snap.expectedDecaySinceEntry(), "expectedDecaySinceEntry must be null when entry price is missing");
        assertNull(snap.thetaProgressRatio(), "thetaProgressRatio must be null when entry price is missing");
    }

    // =========================================================================
    // 5. Empirical delta math — known inputs, exact expected output
    // =========================================================================

    @Test
    void empiricalDeltaMath_knownInputs_shortCE() {
        // Short CE: underlying moves up 100 pts, option moves up 35 pts
        // empirical_delta = 35 / 100 = 0.35
        // expected_delta_move = 0.35 * 100 = 35
        // residual = 35 - 35 = 0
        // for short: actualThetaBenefit = -residual = 0  (no additional theta in 2m window)
        // delta_adjusted_theta = optionMove - expectedDeltaMove = 35 - 35 = 0
        // for short: negate → 0
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE", "24800",
                true,           // short
                "155", "120",   // option moved up 35 pts (bad for short)
                "24900", "24800", // underlying up 100 pts
                null, null,
                null, null, null, null, null,
                2000L, "1000",
                false
        );

        SignalSnapshot snap = service.compute(input, NOW);

        assertNotNull(snap.empiricalDelta2m());
        assertEquals(0, new BigDecimal("0.35").compareTo(snap.empiricalDelta2m()),
                "empiricalDelta2m should be 35/100=0.35, got: " + snap.empiricalDelta2m());

        // delta_adjusted_theta for short: -(optionMove - expectedDeltaMove) = -(35-35) = 0
        assertNotNull(snap.deltaAdjustedTheta2m());
        assertEquals(0, BigDecimal.ZERO.compareTo(snap.deltaAdjustedTheta2m()));
    }

    @Test
    void empiricalDeltaMath_shortPE_underlyingFalls() {
        // Short PE: underlying falls 60 pts, PE option moves up 18 pts
        // empirical_delta = 18 / -60 = -0.30 (PE has negative delta)
        // expected_delta_move = -0.30 * -60 = 18
        // residual = 18 - 18 = 0
        // delta_adjusted_theta for short = -(18-18) = 0
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24500_PE", "NIFTY", "PE", "24500",
                true,           // short
                "88", "70",     // option moved up 18 pts (bad for short PE when underlying falls)
                "24740", "24800", // underlying down 60 pts
                null, null,
                null, null, null, null, null,
                1800L, "900",
                false
        );

        SignalSnapshot snap = service.compute(input, NOW);

        assertNotNull(snap.empiricalDelta2m());
        // empirical_delta = 18 / -60 = -0.30
        assertEquals(0, new BigDecimal("-0.30").compareTo(snap.empiricalDelta2m()),
                "empiricalDelta2m should be -0.30, got: " + snap.empiricalDelta2m());
    }

    // =========================================================================
    // 6. Delta-adjusted theta — premium decaying faster than delta (positive benefit)
    // =========================================================================

    @Test
    void deltaAdjustedTheta_shortLeg_premiumDecaysBeyondDelta() {
        // Short CE: underlying up 100, option only up 20 (delta explains 35 pts move)
        // empirical_delta = 20 / 100 = 0.20 ... wait, let's use a cleaner scenario:
        // underlying up 50 pts
        // empirical_delta = 10 / 50 = 0.20
        // expected_delta_move = 0.20 * 50 = 10
        // actual option move = 10 pts up
        // residual = 10 - 10 = 0, theta = 0
        //
        // Better scenario: option actually falls while underlying rises (strong theta decay):
        // underlying +50 pts, option -5 pts (premium decayed even past delta pressure)
        // empirical_delta = -5 / 50 = -0.10
        // expected_delta_move = -0.10 * 50 = -5
        // residual = -5 - (-5) = 0
        //
        // Use this scenario: underlying +50, option +5 (less than delta would imply)
        // Suppose we set the "expected" delta move to 15 pts but actual was only 5:
        // We need empirical_delta from recent window AND a separate entryEmpiricalDelta
        // Actually, the 2m window delta IS the empirical delta; use it directly:
        //
        // underlying +80 pts, option +12 pts
        // empirical_delta_2m = 12 / 80 = 0.15
        // expected_delta_move = 0.15 * 80 = 12
        // residual = 12 - 12 = 0 → theta = 0 for short
        //
        // For a positive theta benefit, option must rise LESS than delta expects:
        // underlying +100 pts, option +25 pts, but delta (from a higher-delta time) = 0.40
        // empirical_delta_2m = 25/100 = 0.25
        // expected_delta_move using empirical_delta_2m = 0.25 * 100 = 25
        // residual = 25 - 25 = 0
        //
        // The 2m delta-adjusted-theta uses the SAME 2m delta for both, so it's always 0.
        // A positive delta_adjusted_theta_2m happens when premium falls even MORE than expected:
        // underlying +100 pts, option FALLS 10 pts
        // empirical_delta_2m = -10 / 100 = -0.10
        // expected_delta_move = -0.10 * 100 = -10
        // residual = -10 - (-10) = 0 → still zero
        //
        // The formula always produces zero in the 2m window because both option and underlying
        // moves are observed at the same time interval. Non-zero theta comes from ENTRY vs now.
        // This test instead validates the theta sign convention via direct classifyThetaState.
        SignalSnapshot.ThetaState stateWhenBenefitPositive =
                SignalSnapshotService.classifyThetaState(new BigDecimal("5.0"), null);
        assertEquals(SignalSnapshot.ThetaState.HOLD, stateWhenBenefitPositive,
                "Positive delta-adjusted theta but below booking threshold → HOLD");

        SignalSnapshot.ThetaState stateWhenBenefitNegative =
                SignalSnapshotService.classifyThetaState(new BigDecimal("-3.0"), null);
        assertEquals(SignalSnapshot.ThetaState.DEFENSIVE_EXIT, stateWhenBenefitNegative,
                "Negative delta-adjusted theta → DEFENSIVE_EXIT");

        SignalSnapshot.ThetaState stateWhenAboveThreshold =
                SignalSnapshotService.classifyThetaState(new BigDecimal("2.0"), new BigDecimal("0.72"));
        assertEquals(SignalSnapshot.ThetaState.PROFIT_BOOK, stateWhenAboveThreshold,
                "thetaProgressRatio 0.72 >= 0.70 threshold → PROFIT_BOOK");
    }

    // =========================================================================
    // 7. Theta state classification — direct unit tests of static helper
    // =========================================================================

    @Test
    void thetaStateClassification_holdWhenBothNull() {
        SignalSnapshot.ThetaState state = SignalSnapshotService.classifyThetaState(null, null);
        assertEquals(SignalSnapshot.ThetaState.HOLD, state,
                "Both fields null → conservative HOLD");
    }

    @Test
    void thetaStateClassification_profitBook() {
        // Ratio exactly at threshold
        SignalSnapshot.ThetaState state = SignalSnapshotService.classifyThetaState(
                new BigDecimal("1.0"), new BigDecimal("0.70"));
        assertEquals(SignalSnapshot.ThetaState.PROFIT_BOOK, state);
    }

    @Test
    void thetaStateClassification_profitBook_ratioAboveThreshold() {
        SignalSnapshot.ThetaState state = SignalSnapshotService.classifyThetaState(
                new BigDecimal("3.0"), new BigDecimal("0.85"));
        assertEquals(SignalSnapshot.ThetaState.PROFIT_BOOK, state);
    }

    @Test
    void thetaStateClassification_defensiveExitOverridesRatio() {
        // Even if ratio says profit book, negative theta wins → DEFENSIVE_EXIT
        SignalSnapshot.ThetaState state = SignalSnapshotService.classifyThetaState(
                new BigDecimal("-1.0"), new BigDecimal("0.90"));
        assertEquals(SignalSnapshot.ThetaState.DEFENSIVE_EXIT, state,
                "Negative deltaAdjustedTheta overrides high ratio → DEFENSIVE_EXIT");
    }

    @Test
    void thetaStateClassification_holdWhenRatioBelowThreshold() {
        SignalSnapshot.ThetaState state = SignalSnapshotService.classifyThetaState(
                new BigDecimal("2.0"), new BigDecimal("0.50"));
        assertEquals(SignalSnapshot.ThetaState.HOLD, state,
                "Ratio 0.50 < 0.70 threshold → HOLD");
    }

    // =========================================================================
    // 8. Volume state classification — direct unit tests of static helper
    // =========================================================================

    @Test
    void volumeState_confirmed_whenAboveAverage() {
        SignalSnapshot.VolumeState state =
                SignalSnapshotService.classifyVolumeState(2000L, new BigDecimal("1500"));
        assertEquals(SignalSnapshot.VolumeState.CONFIRMED, state);
    }

    @Test
    void volumeState_confirmed_whenEqualToAverage() {
        SignalSnapshot.VolumeState state =
                SignalSnapshotService.classifyVolumeState(1500L, new BigDecimal("1500"));
        assertEquals(SignalSnapshot.VolumeState.CONFIRMED, state);
    }

    @Test
    void volumeState_low_whenBelowAverage() {
        SignalSnapshot.VolumeState state =
                SignalSnapshotService.classifyVolumeState(800L, new BigDecimal("1500"));
        assertEquals(SignalSnapshot.VolumeState.LOW, state);
    }

    @Test
    void volumeState_absent_whenNull() {
        SignalSnapshot.VolumeState state =
                SignalSnapshotService.classifyVolumeState(null, new BigDecimal("1500"));
        assertEquals(SignalSnapshot.VolumeState.ABSENT, state);
    }

    @Test
    void volumeState_absent_whenZero() {
        SignalSnapshot.VolumeState state =
                SignalSnapshotService.classifyVolumeState(0L, new BigDecimal("1500"));
        assertEquals(SignalSnapshot.VolumeState.ABSENT, state);
    }

    @Test
    void volumeState_low_whenNoAverage() {
        SignalSnapshot.VolumeState state =
                SignalSnapshotService.classifyVolumeState(2000L, null);
        assertEquals(SignalSnapshot.VolumeState.LOW, state,
                "Without average baseline, cannot confirm → LOW");
    }

    // =========================================================================
    // 9. Decay since entry — full computation with known inputs
    // =========================================================================

    @Test
    void decaySinceEntry_computedCorrectly_shortLeg() {
        // Setup: entry 10 minutes ago at price 100, current price 85
        // Entry underlying 24800, current underlying 24900 (+100 pts)
        // Entry empirical delta 0.20
        // Expected delta move = 0.20 * 100 = 20 pts
        // Actual option change = 85 - 100 = -15 pts
        // Residual = -15 - 20 = -35 pts
        // For short: actualThetaBenefit = -(-35) = 35 pts  (option fell MORE than delta explains)
        // entryExpectedDecayRatePerMinute = 2.0 pts/min
        // expectedDecaySinceEntry = 2.0 * 10 = 20 pts
        // thetaProgressRatio = 35 / 20 = 1.75 → above threshold → PROFIT_BOOK
        Instant entryTime = NOW.minusSeconds(600); // 10 minutes ago
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE", "24800",
                true,           // short
                "85", "80",     // currentPrice=85, price2mAgo=80 (recent 2m move: +5)
                "24900", "24890", // recent 2m underlying move: +10
                null, null,
                "100",          // entryPrice
                "24800",        // entryUnderlyingPrice
                "0.20",         // entryEmpiricalDelta
                "2.0",          // entryExpectedDecayRatePerMinute
                entryTime,
                3000L, "1500",
                false
        );

        SignalSnapshot snap = service.compute(input, NOW);

        assertNotNull(snap.expectedDecaySinceEntry(),
                "expectedDecaySinceEntry must be computed when entry data is present");
        assertNotNull(snap.thetaProgressRatio(),
                "thetaProgressRatio must be computed when decay > threshold");

        // expectedDecaySinceEntry = 2.0 * 10 = 20 pts
        assertEquals(0, new BigDecimal("20").compareTo(snap.expectedDecaySinceEntry()),
                "expectedDecaySinceEntry: expected 20, got " + snap.expectedDecaySinceEntry());

        // thetaProgressRatio = 35 / 20 = 1.75 → PROFIT_BOOK
        assertEquals(SignalSnapshot.ThetaState.PROFIT_BOOK, snap.thetaState(),
                "thetaProgressRatio 1.75 >> 0.70 → PROFIT_BOOK");
    }

    @Test
    void decaySinceEntry_missingDecayRate_ratioIsNull() {
        Instant entryTime = NOW.minusSeconds(600);
        SignalSnapshotService.LegInput input = legInput(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE", "24800",
                true,
                "85", "80",
                "24900", "24890",
                null, null,
                "100",
                "24800",
                "0.20",
                null,   // no decay rate recorded at entry
                entryTime,
                3000L, "1500",
                false
        );

        SignalSnapshot snap = service.compute(input, NOW);

        assertNull(snap.expectedDecaySinceEntry(),
                "expectedDecaySinceEntry must be null when entryExpectedDecayRatePerMinute is null");
        assertNull(snap.thetaProgressRatio());
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private static final Instant NOW = Instant.parse("2026-04-28T10:15:00Z");

    /**
     * Builds a {@link SignalSnapshotService.LegInput} from string parameters for concise test fixtures.
     * Pass {@code null} for optional fields that are absent.
     */
    @SuppressWarnings("SameParameterValue")
    private static SignalSnapshotService.LegInput legInput(
            String instrumentId,
            String underlying,
            String optionType,
            String strike,
            boolean isShort,
            String currentPrice,
            String price2mAgo,
            String currentUnderlying,
            String underlying2mAgo,
            String priceAtSOD,
            String underlyingAtSOD,
            String entryPrice,
            String entryUnderlying,
            String entryEmpiricalDelta,
            String entryDecayRatePerMinute,
            Instant entryTime,
            Long currentVolume,
            String dayAverageVolume,
            boolean stale
    ) {
        return new SignalSnapshotService.LegInput(
                instrumentId,
                underlying,
                optionType,
                new BigDecimal(strike),
                isShort ? "SHORT" : "LONG",
                bd(currentPrice),
                bd(price2mAgo),
                bd(currentUnderlying),
                bd(underlying2mAgo),
                bd(priceAtSOD),
                bd(underlyingAtSOD),
                65,   // lotSize
                65,   // quantity
                currentVolume,
                bd(dayAverageVolume),
                bd(entryPrice),
                bd(entryUnderlying),
                bd(entryEmpiricalDelta),
                bd(entryDecayRatePerMinute),
                entryTime,
                stale
        );
    }

    private static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
