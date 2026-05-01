package com.strategysquad.agentic.scanner;

import com.strategysquad.aggregation.OptionsContextBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CandidateScoringEngine}.
 *
 * <p>All tests are pure in-memory — no database, no HTTP. Every input value is
 * constructed directly as a Java object and passed to the scoring engine.
 *
 * <h2>Test scenarios covered</h2>
 * <ol>
 *   <li>Normal scoring — qualified contract receives positive richness and
 *       liquidity scores, total score is positive, disqualifierReason is empty.</li>
 *   <li>Zero bid disqualification — bid price is zero; contract disqualified with
 *       reason {@code ZERO_BID}, all scores are 0.0.</li>
 *   <li>Missing cohort disqualification — no historical context entry for this
 *       contract's (underlying, optionType, moneynessBucket, timeBucket15m) key;
 *       contract disqualified with reason {@code MISSING_COHORT}.</li>
 *   <li>Zero volume disqualification — volume is zero; contract disqualified with
 *       reason {@code ZERO_VOLUME}.</li>
 *   <li>DTE out-of-window disqualification — expiry is today (DTE = 0); disqualified
 *       with reason {@code DTE_OUT_OF_WINDOW}.</li>
 *   <li>Premium richness math — known inputs produce expected points and percent values
 *       within tolerance.</li>
 *   <li>Liquidity score — tight spread + high volume yields near-1.0 score; wide
 *       spread + zero volume yields 0.0.</li>
 *   <li>Weight constants sanity — all four weight constants sum to 1.0.</li>
 * </ol>
 */
class CandidateScoringEngineTest {

    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 25);
    private static final LocalDate EXPIRY_3D = TODAY.plusDays(3);   // DTE = 3 (within window)
    private static final LocalDate EXPIRY_TODAY = TODAY;             // DTE = 0 (out of window)
    private static final String SCAN_TS = "202604251030";

    /** Historical average option price for the cohort. Units: NSE index points. */
    private static final BigDecimal HIST_AVG_PRICE = new BigDecimal("100.00");
    /** Historical average volume for the cohort. Units: contracts. */
    private static final BigDecimal HIST_AVG_VOLUME = new BigDecimal("2000");

    private CandidateScoringEngine engine;

    @BeforeEach
    void setUp() {
        engine = new CandidateScoringEngine();
    }

    // =========================================================================
    // Helper factories
    // =========================================================================

    /**
     * Builds a {@link ScannerQuery.RawContractRow} fixture with sensible defaults.
     * Override individual fields as needed in each test.
     */
    private static ScannerQuery.RawContractRow row(
            String instrumentId,
            String optionType,
            BigDecimal lastPrice,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            long volume,
            LocalDate expiryDate,
            int moneynessBucket,
            int timeBucket15m
    ) {
        return new ScannerQuery.RawContractRow(
                instrumentId,
                "NIFTY",
                "NIFTY26APR24800" + optionType,
                optionType,
                new BigDecimal("24800"),
                expiryDate,
                "WEEKLY",
                75,
                new BigDecimal("24700"),           // spot
                lastPrice,
                bidPrice,
                askPrice,
                new BigDecimal("100"),             // moneynessPoints (100 pts OTM)
                moneynessBucket,
                timeBucket15m,
                volume
        );
    }

    /**
     * Builds the cohort map for the standard NIFTY CE bucket (moneynessBucket=1,
     * timeBucket15m=8) using the shared historical avg price and volume fixtures.
     */
    private static Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> standardCohortMap(
            int moneynessBucket, int timeBucket15m
    ) {
        CandidateScoringEngine.CohortKey key = new CandidateScoringEngine.CohortKey(
                "NIFTY", "CE", moneynessBucket, timeBucket15m);
        OptionsContextBucket cohort = new OptionsContextBucket(
                Instant.parse("2026-04-25T00:00:00Z"),
                "NIFTY",
                "CE",
                timeBucket15m,
                moneynessBucket,
                HIST_AVG_PRICE,
                new BigDecimal("0.0040"),   // avgPriceToSpotRatio (dimensionless)
                HIST_AVG_VOLUME,
                120L                        // sampleCount
        );
        return Map.of(key, cohort);
    }

    // =========================================================================
    // Test 1 — Normal scoring (qualified contract)
    // =========================================================================

    /**
     * A contract that is 20% richer than historical average, with a tight 1%
     * bid-ask spread and volume 1× the historical average, should:
     * <ul>
     *   <li>Have disqualifierReason = Optional.empty()</li>
     *   <li>Have premium richness points = lastPrice − historicalAvg = 20 pts</li>
     *   <li>Have premium richness pct ≈ 20%</li>
     *   <li>Have liquidity score > 0 (positive contribution from both sub-components)</li>
     *   <li>Have total score > 0</li>
     * </ul>
     */
    @Test
    void normalScoring_qualifiedContract_populatesAllScores() {
        // lastPrice = 120 → 20% above historicalAvg of 100
        BigDecimal lastPrice = new BigDecimal("120.00");
        BigDecimal bidPrice  = new BigDecimal("118.80");  // spread = 2.40, ~2% of 120
        BigDecimal askPrice  = new BigDecimal("121.20");
        long volume = 2000L;  // exactly 1× historicalAvgVolume

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        // --- No disqualification ---
        assertTrue(result.disqualifierReason().isEmpty(),
                "qualified contract must have empty disqualifierReason");

        // --- Candidate ID format ---
        assertTrue(result.candidateId().startsWith("SCAN_NIFTY_"),
                "candidateId must start with SCAN_NIFTY_");

        // --- Premium richness points: lastPrice (120) − historicalAvg (100) = 20 pts ---
        assertEquals(0,
                new BigDecimal("20.00").compareTo(result.premiumRichnessPoints()),
                "premiumRichnessPoints must be 20.00 pts");

        // --- Premium richness pct: 20 / 100 * 100 = 20.0% ---
        double richnessPct = result.premiumRichnessPct().doubleValue();
        assertEquals(20.0, richnessPct, 0.01,
                "premiumRichnessPct must be ~20.0%");

        // --- Historical avg price preserved ---
        assertEquals(0, HIST_AVG_PRICE.compareTo(result.historicalAvgPrice()),
                "historicalAvgPrice must match the cohort avg");

        // --- Liquidity score: positive (tight spread + decent volume) ---
        assertTrue(result.liquidityScore() > 0.0,
                "liquidityScore must be positive for a liquid contract");
        assertTrue(result.liquidityScore() <= 1.0,
                "liquidityScore must not exceed 1.0");

        // --- Total score: positive ---
        assertTrue(result.totalScore() > 0.0,
                "totalScore must be positive for a qualified contract");
        assertTrue(result.totalScore() <= 1.0,
                "totalScore must not exceed 1.0");

        // --- Theta opportunity and delta risk scores are now non-zero (S1C) ---
        assertTrue(result.thetaOpportunityScore() > 0.0,
                "thetaOpportunityScore must be positive for a rich contract at timeBucket=8");
        assertTrue(result.thetaOpportunityScore() <= 1.0,
                "thetaOpportunityScore must not exceed 1.0");
        // moneynessPoints=100 is exactly at the near-ATM threshold; score should be at penalty cap
        assertEquals(CandidateScoringEngine.DELTA_RISK_NEAR_ATM_PENALTY_CAP,
                result.deltaRiskScore(), 1e-9,
                "deltaRiskScore for moneynessPoints=100 (AT near-ATM boundary) must equal the penalty cap");
    }

    // =========================================================================
    // Test 2 — Zero bid disqualification
    // =========================================================================

    /**
     * A contract whose bid price is zero has no tradeable market and must be
     * disqualified with reason {@code ZERO_BID}. All scores must be 0.0.
     */
    @Test
    void disqualification_zeroBid_reasonZeroBid() {
        BigDecimal lastPrice = new BigDecimal("100.00");
        BigDecimal bidPrice  = BigDecimal.ZERO;       // ← triggers ZERO_BID
        BigDecimal askPrice  = new BigDecimal("102.00");
        long volume = 1500L;

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        assertTrue(result.disqualifierReason().isPresent(),
                "disqualified contract must have a disqualifierReason");
        assertEquals("ZERO_BID", result.disqualifierReason().get(),
                "disqualifierReason must be ZERO_BID");

        assertAllScoresZero(result);
    }

    // =========================================================================
    // Test 3 — Missing cohort disqualification
    // =========================================================================

    /**
     * A contract with no matching historical cohort row cannot have its premium
     * richness computed and must be disqualified with reason {@code MISSING_COHORT}.
     */
    @Test
    void disqualification_missingCohort_reasonMissingCohort() {
        BigDecimal lastPrice = new BigDecimal("120.00");
        BigDecimal bidPrice  = new BigDecimal("119.00");
        BigDecimal askPrice  = new BigDecimal("121.00");
        long volume = 2000L;

        // Contract uses moneynessBucket=99, timeBucket15m=99 — no cohort entry for this
        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 99, 99);

        // Cohort map has entry for (bucket=1, time=8), not (bucket=99, time=99)
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);  // wrong key — no match for this contract

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        assertTrue(result.disqualifierReason().isPresent(),
                "disqualified contract must have a disqualifierReason");
        assertEquals("MISSING_COHORT", result.disqualifierReason().get(),
                "disqualifierReason must be MISSING_COHORT");

        assertAllScoresZero(result);
    }

    // =========================================================================
    // Test 4 — Zero volume disqualification
    // =========================================================================

    /**
     * A contract with zero volume is illiquid and must be disqualified with
     * reason {@code ZERO_VOLUME}. All scores must be 0.0.
     */
    @Test
    void disqualification_zeroVolume_reasonZeroVolume() {
        BigDecimal lastPrice = new BigDecimal("120.00");
        BigDecimal bidPrice  = new BigDecimal("119.00");
        BigDecimal askPrice  = new BigDecimal("121.00");
        long volume = 0L;  // ← triggers ZERO_VOLUME

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        assertTrue(result.disqualifierReason().isPresent(),
                "disqualified contract must have a disqualifierReason");
        assertEquals("ZERO_VOLUME", result.disqualifierReason().get(),
                "disqualifierReason must be ZERO_VOLUME");

        assertAllScoresZero(result);
    }

    // =========================================================================
    // Test 5 — DTE out of window disqualification
    // =========================================================================

    /**
     * A contract expiring today has DTE = 0, which is below {@link CandidateScoringEngine#MIN_DTE_DAYS}.
     * It must be disqualified with reason {@code DTE_OUT_OF_WINDOW}.
     */
    @Test
    void disqualification_dteZero_reasonDteOutOfWindow() {
        BigDecimal lastPrice = new BigDecimal("120.00");
        BigDecimal bidPrice  = new BigDecimal("119.00");
        BigDecimal askPrice  = new BigDecimal("121.00");
        long volume = 2000L;

        // Expiry is today → DTE = 0 → below MIN_DTE_DAYS
        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260425_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_TODAY, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        assertTrue(result.disqualifierReason().isPresent(),
                "disqualified contract must have a disqualifierReason");
        assertEquals("DTE_OUT_OF_WINDOW", result.disqualifierReason().get(),
                "disqualifierReason must be DTE_OUT_OF_WINDOW");

        assertAllScoresZero(result);
    }

    // =========================================================================
    // Test 6 — Premium richness math with known inputs
    // =========================================================================

    /**
     * Verifies the richness formula precisely with known inputs:
     * <pre>
     *   lastPrice = 130, historicalAvg = 100
     *   richnessPts = 130 − 100 = 30 pts
     *   richnessPct = 30 / 100 × 100 = 30.0%
     *   richnessScore = min(1.0, 30.0 / 40.0) = 0.75
     * </pre>
     */
    @Test
    void premiumRichnessMath_knownInputs_exactResults() {
        BigDecimal lastPrice = new BigDecimal("130.00"); // 30% above historicalAvg
        BigDecimal bidPrice  = new BigDecimal("129.00");
        BigDecimal askPrice  = new BigDecimal("131.00");
        long volume = 4000L;  // 2× historicalAvg → volumeScore saturates at 1.0

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        // Points: 130 − 100 = 30.00
        assertEquals(0,
                new BigDecimal("30.00").compareTo(result.premiumRichnessPoints()),
                "premiumRichnessPoints must be exactly 30.00 pts");

        // Percent: 30 / 100 × 100 = 30.0%
        assertEquals(30.0, result.premiumRichnessPct().doubleValue(), 0.01,
                "premiumRichnessPct must be ~30.0%");

        // richnessScore = 30.0 / 40.0 = 0.75
        // totalScore = WEIGHT_THETA * theta + WEIGHT_RICHNESS * 0.75 + WEIGHT_LIQUIDITY * liquidity + WEIGHT_DELTA_RISK * delta
        // Verify total score includes at minimum the richness contribution
        double expectedMinRichnessContrib = CandidateScoringEngine.WEIGHT_RICHNESS * 0.75;
        assertTrue(result.totalScore() >= expectedMinRichnessContrib - 1e-9,
                "totalScore must include the richness contribution of >= "
                        + expectedMinRichnessContrib);
    }

    /**
     * Verifies that a contract trading BELOW historical average (richnessPct negative)
     * gets a richness score of 0.0 — we do not reward cheap contracts.
     */
    @Test
    void premiumRichnessMath_belowHistoricalAvg_richnessScoreIsZero() {
        BigDecimal lastPrice = new BigDecimal("80.00");  // 20% BELOW historicalAvg of 100
        BigDecimal bidPrice  = new BigDecimal("79.00");
        BigDecimal askPrice  = new BigDecimal("81.00");
        long volume = 2000L;

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        // richnessPct < 0 → richnessScore = 0.0 → no richness contribution to totalScore
        assertTrue(result.premiumRichnessPct().doubleValue() < 0,
                "premiumRichnessPct must be negative when lastPrice < historicalAvg");
        // The richness score is zero; total score comes from liquidity, theta, and delta risk only.
        // Theta richness sub-component is also 0 since richnessScore=0.
        // Max possible: WEIGHT_THETA * thetaTimeProximity + WEIGHT_LIQUIDITY + WEIGHT_DELTA_RISK
        double maxPossibleWithoutRichness =
                CandidateScoringEngine.WEIGHT_THETA * CandidateScoringEngine.THETA_WEIGHT_TIME_PROXIMITY
              + CandidateScoringEngine.WEIGHT_LIQUIDITY
              + CandidateScoringEngine.WEIGHT_DELTA_RISK;
        assertTrue(result.totalScore() <= maxPossibleWithoutRichness + 1e-9,
                "totalScore must not include richness when contract is below historical avg; got "
                        + result.totalScore());
    }

    /**
     * Verifies that richness score saturates at 1.0 when richnessPct exceeds
     * {@link CandidateScoringEngine#RICHNESS_SATURATION_PCT}.
     */
    @Test
    void premiumRichnessMath_aboveSaturation_scoreCapsPat1() {
        // 50% above historicalAvg — exceeds RICHNESS_SATURATION_PCT (40%)
        BigDecimal lastPrice = new BigDecimal("150.00");
        BigDecimal bidPrice  = new BigDecimal("149.00");
        BigDecimal askPrice  = new BigDecimal("151.00");
        long volume = 4000L;

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        // richnessScore should be capped at 1.0
        // totalScore must be >= WEIGHT_RICHNESS * 1.0 (since richness is maxed)
        double minExpectedTotal = CandidateScoringEngine.WEIGHT_RICHNESS * 1.0;
        assertTrue(result.totalScore() >= minExpectedTotal - 1e-9,
                "totalScore must be >= WEIGHT_RICHNESS when richness is saturated");
        assertTrue(result.totalScore() <= 1.0,
                "totalScore must never exceed 1.0");
    }

    // =========================================================================
    // Test 7 — Liquidity score edge cases
    // =========================================================================

    /**
     * A contract with a very tight spread (≤ 0%) and very high volume should
     * receive a near-maximum liquidity score.
     */
    @Test
    void liquidityScore_tightSpreadHighVolume_nearMaxScore() {
        BigDecimal lastPrice = new BigDecimal("100.00");
        // bid == ask == last → spread is 0% → spreadScore = 1.0
        BigDecimal bidPrice  = new BigDecimal("100.00");
        BigDecimal askPrice  = new BigDecimal("100.00");
        // volume = 4× historicalAvg → volumeScore saturates at 1.0
        long volume = 8000L;

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        // liquidityScore should be very high (both sub-scores near 1.0)
        assertEquals(1.0, result.liquidityScore(), 0.01,
                "liquidityScore must be ~1.0 for zero-spread and saturated-volume contract");
    }

    /**
     * A contract with a wide spread (≥ MAX_SPREAD_RATIO × lastPrice) and zero
     * historical volume context should receive a low liquidity score.
     * Note: volume itself must be > 0 to avoid ZERO_VOLUME disqualification.
     */
    @Test
    void liquidityScore_wideSpreadLowVolume_lowScore() {
        BigDecimal lastPrice = new BigDecimal("100.00");
        // Spread = 10% of lastPrice → exceeds MAX_SPREAD_RATIO (5%) → spreadScore = 0.0
        BigDecimal bidPrice  = new BigDecimal("95.00");
        BigDecimal askPrice  = new BigDecimal("105.00");
        // Volume = 1 (present so not disqualified, but tiny vs historicalAvg of 2000)
        long volume = 1L;

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        assertTrue(result.disqualifierReason().isEmpty(),
                "contract with volume=1 should not be disqualified (volume > 0)");
        assertTrue(result.liquidityScore() < 0.10,
                "liquidityScore must be very low for wide spread and minimal volume; got "
                        + result.liquidityScore());
    }

    // =========================================================================
    // Test 8 — Weight constants sanity
    // =========================================================================

    /**
     * All four weight constants must sum to exactly 1.0 (within floating-point
     * tolerance). This ensures the total score is always in [0, 1] when all
     * component scores are in [0, 1].
     */
    @Test
    void weightConstants_sumToOne() {
        double sum = CandidateScoringEngine.WEIGHT_THETA
                + CandidateScoringEngine.WEIGHT_RICHNESS
                + CandidateScoringEngine.WEIGHT_LIQUIDITY
                + CandidateScoringEngine.WEIGHT_DELTA_RISK;
        assertEquals(1.0, sum, 1e-9,
                "All weight constants must sum to 1.0; got " + sum);
    }

    // =========================================================================
    // Test 9 — Disqualified contract passthrough fields
    // =========================================================================

    /**
     * Disqualified contracts must still carry their raw contract data (underlying,
     * instrumentId, optionType, etc.) in the output, so the caller can log or
     * display them without losing context.
     */
    @Test
    void disqualified_contractFields_preservedInOutput() {
        BigDecimal lastPrice = new BigDecimal("100.00");
        BigDecimal bidPrice  = BigDecimal.ZERO;  // triggers ZERO_BID
        BigDecimal askPrice  = new BigDecimal("102.00");
        long volume = 1500L;

        ScannerQuery.RawContractRow contract = row(
                "INS_NIFTY_20260428_24800_CE", "CE",
                lastPrice, bidPrice, askPrice, volume, EXPIRY_3D, 1, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                standardCohortMap(1, 8);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        // Verify raw contract fields are preserved
        assertEquals("INS_NIFTY_20260428_24800_CE", result.instrumentId());
        assertEquals("NIFTY", result.underlying());
        assertEquals("CE", result.optionType());
        assertEquals(0, new BigDecimal("24800").compareTo(result.strike()));
        assertEquals(EXPIRY_3D, result.expiryDate());
        assertEquals("WEEKLY", result.expiryType());
        assertEquals(0, lastPrice.compareTo(result.lastPrice()));
        assertEquals(0, bidPrice.compareTo(result.bidPrice()));
        assertEquals(Optional.of("ZERO_BID"), result.disqualifierReason());
    }

    // =========================================================================
    // Test 10 — Theta opportunity score (S1C)
    // =========================================================================

    /**
     * A contract at timeBucket=4 (≤ THETA_HIGH_DECAY_TIME_BUCKET) receives the maximum
     * time-proximity bonus (1.0). Combined with a rich premium, the theta score should
     * be near its maximum.
     */
    @Test
    void thetaScore_nearExpiry_highScore() {
        // richnessScore = 30/40 = 0.75 (30% above avg)
        // timeBucket=4 <= THETA_HIGH_DECAY_TIME_BUCKET(8) → timeProxScore = 1.0
        // thetaScore = 0.60*0.75 + 0.40*1.0 = 0.45 + 0.40 = 0.85
        double result = CandidateScoringEngine.computeThetaOpportunityScore(0.75, 4);
        assertEquals(0.85, result, 1e-9,
                "thetaScore for richnessScore=0.75, timeBucket=4 must be 0.85");
    }

    /**
     * A contract at timeBucket=26 (= THETA_LOW_DECAY_TIME_BUCKET) has zero time-proximity
     * bonus. The theta score is driven only by the richness sub-component.
     */
    @Test
    void thetaScore_farFromExpiry_onlyRichnessContributes() {
        // timeBucket=26 = THETA_LOW_DECAY_TIME_BUCKET → timeProxScore = 0.0
        // thetaScore = 0.60*richnessScore + 0.40*0.0 = 0.60*richnessScore
        double richnessScore = 0.5;
        double result = CandidateScoringEngine.computeThetaOpportunityScore(richnessScore,
                CandidateScoringEngine.THETA_LOW_DECAY_TIME_BUCKET);
        double expected = CandidateScoringEngine.THETA_WEIGHT_RICHNESS * richnessScore;
        assertEquals(expected, result, 1e-9,
                "thetaScore at THETA_LOW_DECAY_TIME_BUCKET must equal THETA_WEIGHT_RICHNESS * richnessScore");
    }

    /**
     * A flat (not rich) contract at maximum proximity has only the time proximity component.
     */
    @Test
    void thetaScore_zeroRichness_onlyTimeProximityContributes() {
        // richnessScore=0, timeBucket=4 (below high decay threshold) → timeProxScore=1.0
        // thetaScore = 0.60*0 + 0.40*1.0 = 0.40
        double result = CandidateScoringEngine.computeThetaOpportunityScore(0.0, 4);
        assertEquals(CandidateScoringEngine.THETA_WEIGHT_TIME_PROXIMITY, result, 1e-9,
                "thetaScore with zero richness must equal THETA_WEIGHT_TIME_PROXIMITY");
    }

    /**
     * Time bucket mid-range (between HIGH and LOW decay thresholds) yields a
     * linearly interpolated time-proximity score.
     */
    @Test
    void thetaScore_midRangeTimeBucket_linearInterpolation() {
        // HIGH=8, LOW=26 → range=18
        // timeBucket=17 → (26-17)/18 = 9/18 = 0.5 time-proximity score
        // richnessScore=1.0 → thetaScore = 0.60*1.0 + 0.40*0.5 = 0.60 + 0.20 = 0.80
        double result = CandidateScoringEngine.computeThetaOpportunityScore(1.0, 17);
        assertEquals(0.80, result, 1e-9,
                "thetaScore at timeBucket=17 with max richness must be 0.80");
    }

    // =========================================================================
    // Test 11 — Delta risk score (S1C)
    // =========================================================================

    /**
     * A contract with zero moneyness (exactly ATM) gets the minimum possible delta
     * risk score — delta is highest and most uncertain at ATM.
     */
    @Test
    void deltaRiskScore_exactlyAtm_minimumScore() {
        // absMoneyness=0 → ratio=0 → score = 0 * NEAR_ATM_PENALTY_CAP = 0.0
        double result = CandidateScoringEngine.computeDeltaRiskScore(BigDecimal.ZERO);
        assertEquals(0.0, result, 1e-9,
                "deltaRiskScore for ATM (moneyness=0) must be 0.0");
    }

    /**
     * A contract at exactly the near-ATM threshold boundary receives the penalty cap.
     * Moneyness = DELTA_RISK_NEAR_ATM_THRESHOLD_POINTS.
     */
    @Test
    void deltaRiskScore_atNearAtmBoundary_penaltyCap() {
        // absMoneyness = NEAR_ATM_THRESHOLD → ratio=1.0 → score = 1.0 * NEAR_ATM_PENALTY_CAP
        BigDecimal moneyness = BigDecimal.valueOf(
                CandidateScoringEngine.DELTA_RISK_NEAR_ATM_THRESHOLD_POINTS);
        double result = CandidateScoringEngine.computeDeltaRiskScore(moneyness);
        assertEquals(CandidateScoringEngine.DELTA_RISK_NEAR_ATM_PENALTY_CAP, result, 1e-9,
                "deltaRiskScore at near-ATM boundary must equal DELTA_RISK_NEAR_ATM_PENALTY_CAP");
    }

    /**
     * A contract at or beyond the safe distance saturates to 1.0.
     */
    @Test
    void deltaRiskScore_atSafeDistance_saturatesTo1() {
        // absMoneyness = SAFE_DISTANCE → t=1.0 → score = cap + 1.0*(1.0 - cap) = 1.0
        BigDecimal moneyness = BigDecimal.valueOf(
                CandidateScoringEngine.DELTA_RISK_SAFE_DISTANCE_POINTS);
        double result = CandidateScoringEngine.computeDeltaRiskScore(moneyness);
        assertEquals(1.0, result, 1e-9,
                "deltaRiskScore at DELTA_RISK_SAFE_DISTANCE_POINTS must be 1.0");
    }

    /**
     * A contract beyond safe distance still saturates at 1.0 (does not exceed it).
     */
    @Test
    void deltaRiskScore_beyondSafeDistance_cappedAt1() {
        BigDecimal moneyness = BigDecimal.valueOf(2000.0); // well beyond 500-point safe distance
        double result = CandidateScoringEngine.computeDeltaRiskScore(moneyness);
        assertEquals(1.0, result, 1e-9,
                "deltaRiskScore must not exceed 1.0 for deep OTM contracts");
    }

    /**
     * Midpoint between threshold (100) and safe distance (500) = 300 points.
     * Expected: t = (300-100)/(500-100) = 200/400 = 0.5
     * score = 0.30 + 0.5*(1.0-0.30) = 0.30 + 0.35 = 0.65
     */
    @Test
    void deltaRiskScore_midpointOtm_linearInterpolation() {
        BigDecimal moneyness = BigDecimal.valueOf(300.0);
        double result = CandidateScoringEngine.computeDeltaRiskScore(moneyness);
        double expected = CandidateScoringEngine.DELTA_RISK_NEAR_ATM_PENALTY_CAP
                + 0.5 * (1.0 - CandidateScoringEngine.DELTA_RISK_NEAR_ATM_PENALTY_CAP);
        assertEquals(expected, result, 1e-9,
                "deltaRiskScore at 300 points must be linearly interpolated to " + expected);
    }

    /**
     * Negative moneyness (ITM contract) is treated the same as the same absolute value.
     * The delta risk score uses |moneynessPoints|, so sign does not matter.
     */
    @Test
    void deltaRiskScore_negativeMoneynessSymmetric() {
        BigDecimal positive = BigDecimal.valueOf(300.0);
        BigDecimal negative = BigDecimal.valueOf(-300.0);
        double scorePositive = CandidateScoringEngine.computeDeltaRiskScore(positive);
        double scoreNegative = CandidateScoringEngine.computeDeltaRiskScore(negative);
        assertEquals(scorePositive, scoreNegative, 1e-9,
                "deltaRiskScore must be symmetric — positive and negative moneyness yield same score");
    }

    /**
     * End-to-end: a qualified contract at timeBucket=4 (near expiry), 30% rich,
     * and 300 points OTM should have a meaningful total score that reflects all
     * four components properly.
     */
    @Test
    void fullScore_nearExpiry_richPremium_otmContract_meaningfulTotal() {
        // 30% above avg → richnessScore = 0.75
        BigDecimal lastPrice = new BigDecimal("130.00");
        BigDecimal bidPrice  = new BigDecimal("129.00");
        BigDecimal askPrice  = new BigDecimal("131.00");
        long volume = 4000L;
        // moneynessPoints = 300 → at midpoint between near-ATM and safe distance
        // Build row with custom moneynessPoints: use moneynessBucket=3, timeBucket=4
        ScannerQuery.RawContractRow contract = new ScannerQuery.RawContractRow(
                "INS_NIFTY_20260428_24800_CE",
                "NIFTY",
                "NIFTY26APR24800CE",
                "CE",
                new BigDecimal("24800"),
                EXPIRY_3D,
                "WEEKLY",
                75,
                new BigDecimal("24500"),            // spot — 300 pts below strike → moneynessPoints=300
                lastPrice,
                bidPrice,
                askPrice,
                new BigDecimal("300"),              // moneynessPoints (300 pts OTM)
                3,                                  // moneynessBucket
                4,                                  // timeBucket15m — near expiry
                volume
        );

        // Build cohort for moneynessBucket=3, timeBucket=4
        CandidateScoringEngine.CohortKey key = new CandidateScoringEngine.CohortKey(
                "NIFTY", "CE", 3, 4);
        OptionsContextBucket cohort = new OptionsContextBucket(
                Instant.parse("2026-04-25T00:00:00Z"),
                "NIFTY", "CE", 4, 3,
                HIST_AVG_PRICE,
                new BigDecimal("0.0052"),
                HIST_AVG_VOLUME,
                100L
        );
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                Map.of(key, cohort);

        CandidateOpportunity result = engine.score(contract, cohortMap, SCAN_TS, TODAY);

        assertTrue(result.disqualifierReason().isEmpty(),
                "contract must be qualified");

        // Theta: richnessScore=0.75, timeBucket=4 → thetaScore=0.85
        assertEquals(0.85, result.thetaOpportunityScore(), 1e-9,
                "thetaOpportunityScore must be 0.85 (richnessScore=0.75, timeBucket=4)");

        // Delta: moneyness=300 → midpoint → score = 0.30 + 0.5*(0.70) = 0.65
        double expectedDelta = CandidateScoringEngine.DELTA_RISK_NEAR_ATM_PENALTY_CAP
                + 0.5 * (1.0 - CandidateScoringEngine.DELTA_RISK_NEAR_ATM_PENALTY_CAP);
        assertEquals(expectedDelta, result.deltaRiskScore(), 1e-9,
                "deltaRiskScore must be " + expectedDelta + " for moneyness=300");

        // Total score must be meaningfully high — all four components contribute
        assertTrue(result.totalScore() > 0.60,
                "totalScore should be > 0.60 for a rich, near-expiry, OTM contract; got "
                        + result.totalScore());
        assertTrue(result.totalScore() <= 1.0,
                "totalScore must not exceed 1.0");
    }

    // =========================================================================
    // Private assertion helpers
    // =========================================================================

    /**
     * Asserts that all score fields on a disqualified {@link CandidateOpportunity}
     * are exactly 0.0.
     */
    private static void assertAllScoresZero(CandidateOpportunity result) {
        assertEquals(0.0, result.liquidityScore(), 1e-9,
                "liquidityScore must be 0.0 for a disqualified contract");
        assertEquals(0.0, result.thetaOpportunityScore(), 1e-9,
                "thetaOpportunityScore must be 0.0 for a disqualified contract");
        assertEquals(0.0, result.deltaRiskScore(), 1e-9,
                "deltaRiskScore must be 0.0 for a disqualified contract");
        assertEquals(0.0, result.totalScore(), 1e-9,
                "totalScore must be 0.0 for a disqualified contract");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.premiumRichnessPoints()),
                "premiumRichnessPoints must be 0 for a disqualified contract");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.premiumRichnessPct()),
                "premiumRichnessPct must be 0 for a disqualified contract");
    }
}
