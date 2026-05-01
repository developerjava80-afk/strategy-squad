package com.strategysquad.agentic.scanner;

import com.strategysquad.aggregation.OptionsContextBucket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Stateless scoring engine that converts a raw contract row from {@link ScannerQuery}
 * into a ranked {@link CandidateOpportunity}.
 *
 * <h2>Scoring pipeline</h2>
 * <ol>
 *   <li><b>Disqualification guards</b> (applied in strict priority order):
 *     <ul>
 *       <li>{@code DTE_OUT_OF_WINDOW} — contract is outside the
 *           [{@link #MIN_DTE_DAYS}, {@link #MAX_DTE_DAYS}] window.</li>
 *       <li>{@code ZERO_BID} — no tradeable market for this contract.</li>
 *       <li>{@code ZERO_VOLUME} — illiquid contract.</li>
 *       <li>{@code MISSING_COHORT} — no historical context row for this
 *           (underlying, optionType, moneynessBucket, timeBucket15m) key.
 *           For live mode a fallback aggregate cohort (timeBucket15m=0) is
 *           tried before declaring MISSING_COHORT.</li>
 *     </ul>
 *   </li>
 *   <li><b>Premium richness</b> — {@code (lastPrice − historicalAvg) / historicalAvg × 100}.
 *       Negative or zero richness yields a richness score of 0.0.</li>
 *   <li><b>Liquidity</b> — equal-weighted average of bid-ask spread score and
 *       volume-relative-to-history score.</li>
 *   <li><b>Theta opportunity</b> — weighted combination of richness and time-proximity.</li>
 *   <li><b>Delta risk</b> — penalty for near-ATM contracts, reward for OTM.</li>
 *   <li><b>Total score</b> — weighted sum of all components.</li>
 * </ol>
 */
public final class CandidateScoringEngine {

    // =========================================================================
    // DTE window constants
    // =========================================================================

    /** Minimum days to expiry for a contract to be eligible for scanning. */
    public static final int MIN_DTE_DAYS = 1;

    /**
     * Maximum days to expiry for a contract to be eligible for scanning.
     * Covers both next-weekly (typically DTE 7–9) and near-monthly (DTE up to ~45)
     * contracts so the scanner surfaces theta-decay opportunities across expiry cycles.
     * Intraday peak-decay opportunities (DTE 1–7) will naturally score higher on
     * thetaOpportunityScore due to the time-proximity sub-score.
     */
    public static final int MAX_DTE_DAYS = 45;

    // =========================================================================
    // Richness scoring constants
    // =========================================================================

    /**
     * Richness saturation threshold in percent.
     * A contract trading {@code RICHNESS_SATURATION_PCT}% above historical average
     * receives a richness score of 1.0. Contracts above this threshold are capped.
     */
    public static final double RICHNESS_SATURATION_PCT = 40.0;

    // =========================================================================
    // Liquidity scoring constants
    // =========================================================================

    /** Maximum bid-ask spread ratio above which spreadScore is 0. */
    public static final double MAX_SPREAD_RATIO = 0.05;

    /**
     * Volume multiple at which volumeScore saturates to 1.0.
     * A contract with volume >= {@code VOLUME_SATURATION_MULTIPLE * historicalAvgVolume}
     * receives a volumeScore of 1.0.
     */
    public static final double VOLUME_SATURATION_MULTIPLE = 2.0;

    // =========================================================================
    // Theta opportunity scoring constants
    // =========================================================================

    /**
     * Time-to-expiry bucket at or below which the time-proximity sub-score is 1.0.
     * Value 8 corresponds to 120 minutes to expiry (peak theta decay window).
     */
    public static final int THETA_HIGH_DECAY_TIME_BUCKET = 8;

    /**
     * Time-to-expiry bucket at or above which the time-proximity sub-score is 0.0.
     * Value 26 corresponds to 390 minutes (6.5 hours) to expiry.
     */
    public static final int THETA_LOW_DECAY_TIME_BUCKET = 26;

    /** Weight of the richness sub-score within the theta opportunity score. */
    public static final double THETA_WEIGHT_RICHNESS = 0.60;

    /** Weight of the time-proximity sub-score within the theta opportunity score. */
    public static final double THETA_WEIGHT_TIME_PROXIMITY = 0.40;

    // =========================================================================
    // Delta risk scoring constants
    // =========================================================================

    /**
     * Absolute moneyness threshold (in index points) below which a contract is
     * considered "near-ATM" and receives a delta risk penalty. Value: 100 points.
     */
    public static final double DELTA_RISK_NEAR_ATM_THRESHOLD_POINTS = 100.0;

    /**
     * Absolute moneyness distance (in index points) at which delta risk saturates to 1.0.
     * Contracts at or beyond this distance from ATM are safe for short-selling.
     * Value: 500 points.
     */
    public static final double DELTA_RISK_SAFE_DISTANCE_POINTS = 500.0;

    /**
     * Maximum delta risk score for a contract at the near-ATM boundary
     * ({@link #DELTA_RISK_NEAR_ATM_THRESHOLD_POINTS}). Below this boundary, the score
     * scales linearly from 0 (ATM) to this cap. Value: 0.30.
     */
    public static final double DELTA_RISK_NEAR_ATM_PENALTY_CAP = 0.30;

    // =========================================================================
    // Weight constants — must sum to 1.0
    // =========================================================================

    /** Weight for theta opportunity score in the total score computation. */
    public static final double WEIGHT_THETA = 0.40;

    /** Weight for premium richness score in the total score computation. */
    public static final double WEIGHT_RICHNESS = 0.30;

    /** Weight for liquidity score in the total score computation. */
    public static final double WEIGHT_LIQUIDITY = 0.20;

    /** Weight for delta risk score in the total score computation. */
    public static final double WEIGHT_DELTA_RISK = 0.10;

    // =========================================================================
    // Cohort key — package-private for test access
    // =========================================================================

    /**
     * Immutable key for looking up historical cohort context from
     * {@code options_context_buckets}. Matches the canonical cohort dimensions:
     * underlying x optionType x moneynessBucket x timeBucket15m.
     */
    public record CohortKey(String underlying, String optionType, int moneynessBucket, int timeBucket15m) {

        public CohortKey {
            if (underlying == null || underlying.isBlank()) {
                throw new IllegalArgumentException("underlying must not be blank");
            }
            if (optionType == null || optionType.isBlank()) {
                throw new IllegalArgumentException("optionType must not be blank");
            }
        }

        /** Convenience factory from a raw contract row. */
        static CohortKey from(ScannerQuery.RawContractRow row) {
            return new CohortKey(
                    row.underlying(),
                    row.optionType(),
                    row.moneynessBucket(),
                    row.timeBucket15m()
            );
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Scores a single raw contract row into a {@link CandidateOpportunity}.
     *
     * @param row        raw contract data from {@link ScannerQuery}
     * @param cohortMap  historical context from {@code options_context_buckets}
     * @param scanTs     scan-timestamp string (yyyyMMddHHmm) embedded in the candidate ID
     * @param today      market date at scan time (IST); used to compute DTE
     * @return scored candidate; never null; disqualified candidates have scores of 0.0
     */
    public CandidateOpportunity score(
            ScannerQuery.RawContractRow row,
            Map<CohortKey, OptionsContextBucket> cohortMap,
            String scanTs,
            LocalDate today
    ) {
        String candidateId = "SCAN_" + row.underlying() + "_" + row.instrumentId() + "_" + scanTs;

        // -----------------------------------------------------------------
        // Step 1 — Disqualification guards (applied in priority order)
        // -----------------------------------------------------------------

        // DTE out of window
        int dte = computeDte(row.expiryDate(), today);
        if (dte < MIN_DTE_DAYS || dte > MAX_DTE_DAYS) {
            return disqualified(candidateId, row, "DTE_OUT_OF_WINDOW");
        }

        // Zero bid — no tradeable market
        if (row.bidPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return disqualified(candidateId, row, "ZERO_BID");
        }

        // Zero volume — illiquid
        if (row.volume() == 0L) {
            return disqualified(candidateId, row, "ZERO_VOLUME");
        }

        // Missing historical cohort — cannot compute richness.
        // First try an exact TTE-bucket match (works in simulation mode where data
        // timestamps align). If that misses, fall back to the aggregated moneyness-only
        // cohort (timeBucket15m = 0) which covers live mode: live ticks arrive at
        // intraday time while historical context was built from market-close bhavcopy,
        // so their TTE bucket numbers differ by a few slots.
        CohortKey key = CohortKey.from(row);
        OptionsContextBucket cohort = cohortMap.get(key);
        if (cohort == null) {
            CohortKey fallbackKey = new CohortKey(
                    row.underlying(), row.optionType(), row.moneynessBucket(), 0);
            cohort = cohortMap.get(fallbackKey);
        }
        if (cohort == null) {
            return disqualified(candidateId, row, "MISSING_COHORT");
        }

        // -----------------------------------------------------------------
        // Step 2 — Premium richness computation
        // -----------------------------------------------------------------

        BigDecimal historicalAvgPrice = cohort.avgOptionPrice();
        BigDecimal premiumRichnessPoints = row.lastPrice().subtract(historicalAvgPrice)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal premiumRichnessPct;
        if (historicalAvgPrice.signum() == 0) {
            premiumRichnessPct = BigDecimal.ZERO;
        } else {
            premiumRichnessPct = premiumRichnessPoints
                    .multiply(BigDecimal.valueOf(100))
                    .divide(historicalAvgPrice, 4, RoundingMode.HALF_UP);
        }

        double richnessPctDouble = premiumRichnessPct.doubleValue();
        double richnessScore = 0.0;
        if (richnessPctDouble > 0) {
            richnessScore = Math.min(1.0, richnessPctDouble / RICHNESS_SATURATION_PCT);
        }

        // -----------------------------------------------------------------
        // Step 3 — Liquidity score
        // -----------------------------------------------------------------

        double spreadRatio = 0.0;
        if (row.lastPrice().signum() > 0) {
            spreadRatio = row.askPrice().subtract(row.bidPrice())
                    .divide(row.lastPrice(), 8, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        double spreadScore = Math.max(0.0, 1.0 - spreadRatio / MAX_SPREAD_RATIO);

        double volumeScore = 0.0;
        double avgVolume = cohort.avgVolume().doubleValue();
        if (avgVolume > 0) {
            volumeScore = Math.min(1.0, row.volume() / (VOLUME_SATURATION_MULTIPLE * avgVolume));
        } else if (row.volume() > 0) {
            volumeScore = 1.0;
        }
        double liquidityScore = 0.5 * spreadScore + 0.5 * volumeScore;

        // -----------------------------------------------------------------
        // Step 4 — Theta opportunity score
        // -----------------------------------------------------------------

        double thetaOpportunityScore = computeThetaOpportunityScore(richnessScore, row.timeBucket15m());

        // -----------------------------------------------------------------
        // Step 5 — Delta risk score
        // -----------------------------------------------------------------

        double deltaRiskScore = computeDeltaRiskScore(row.moneynessPoints());

        // -----------------------------------------------------------------
        // Step 6 — Total score
        // -----------------------------------------------------------------

        double totalScore = WEIGHT_THETA * thetaOpportunityScore
                + WEIGHT_RICHNESS * richnessScore
                + WEIGHT_LIQUIDITY * liquidityScore
                + WEIGHT_DELTA_RISK * deltaRiskScore;

        return new CandidateOpportunity(
                candidateId,
                row.underlying(),
                row.instrumentId(),
                row.tradingSymbol(),
                row.optionType(),
                row.strike(),
                row.expiryDate(),
                row.expiryType(),
                row.spot(),
                row.lastPrice(),
                row.bidPrice(),
                row.askPrice(),
                row.moneynessPoints(),
                row.moneynessBucket(),
                row.timeBucket15m(),
                historicalAvgPrice,
                premiumRichnessPoints,
                premiumRichnessPct,
                liquidityScore,
                thetaOpportunityScore,
                deltaRiskScore,
                totalScore,
                Optional.empty()
        );
    }

    // =========================================================================
    // Package-private scoring sub-functions (visible to tests)
    // =========================================================================

    /**
     * Computes the theta opportunity score.
     *
     * @param richnessScore  richness sub-score in [0, 1]
     * @param timeBucket15m  15-minute time-to-expiry bucket
     * @return theta opportunity score in [0, 1]
     */
    static double computeThetaOpportunityScore(double richnessScore, int timeBucket15m) {
        double timeProxScore;
        if (timeBucket15m <= THETA_HIGH_DECAY_TIME_BUCKET) {
            timeProxScore = 1.0;
        } else if (timeBucket15m >= THETA_LOW_DECAY_TIME_BUCKET) {
            timeProxScore = 0.0;
        } else {
            timeProxScore = (double) (THETA_LOW_DECAY_TIME_BUCKET - timeBucket15m)
                    / (THETA_LOW_DECAY_TIME_BUCKET - THETA_HIGH_DECAY_TIME_BUCKET);
        }
        return THETA_WEIGHT_RICHNESS * richnessScore + THETA_WEIGHT_TIME_PROXIMITY * timeProxScore;
    }

    /**
     * Computes the delta risk score.
     *
     * @param moneynessPoints absolute moneyness in index points (may be negative for ITM)
     * @return delta risk score in [0, 1]; 0.0 = maximum delta risk (ATM); 1.0 = safe OTM
     */
    static double computeDeltaRiskScore(BigDecimal moneynessPoints) {
        double absMoneyness = Math.abs(moneynessPoints.doubleValue());
        if (absMoneyness >= DELTA_RISK_SAFE_DISTANCE_POINTS) {
            return 1.0;
        }
        if (absMoneyness <= 0.0) {
            return 0.0;
        }
        if (absMoneyness <= DELTA_RISK_NEAR_ATM_THRESHOLD_POINTS) {
            double t = absMoneyness / DELTA_RISK_NEAR_ATM_THRESHOLD_POINTS;
            return t * DELTA_RISK_NEAR_ATM_PENALTY_CAP;
        }
        double t = (absMoneyness - DELTA_RISK_NEAR_ATM_THRESHOLD_POINTS)
                / (DELTA_RISK_SAFE_DISTANCE_POINTS - DELTA_RISK_NEAR_ATM_THRESHOLD_POINTS);
        return DELTA_RISK_NEAR_ATM_PENALTY_CAP + t * (1.0 - DELTA_RISK_NEAR_ATM_PENALTY_CAP);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static int computeDte(LocalDate expiryDate, LocalDate today) {
        return (int) ChronoUnit.DAYS.between(today, expiryDate);
    }

    private static CandidateOpportunity disqualified(
            String candidateId, ScannerQuery.RawContractRow row, String reason
    ) {
        return new CandidateOpportunity(
                candidateId,
                row.underlying(),
                row.instrumentId(),
                row.tradingSymbol(),
                row.optionType(),
                row.strike(),
                row.expiryDate(),
                row.expiryType(),
                row.spot(),
                row.lastPrice(),
                row.bidPrice(),
                row.askPrice(),
                row.moneynessPoints(),
                row.moneynessBucket(),
                row.timeBucket15m(),
                BigDecimal.ZERO,    // historicalAvgPrice
                BigDecimal.ZERO,    // premiumRichnessPoints
                BigDecimal.ZERO,    // premiumRichnessPct
                0.0,                // liquidityScore
                0.0,                // thetaOpportunityScore
                0.0,                // deltaRiskScore
                0.0,                // totalScore
                Optional.of(reason)
        );
    }
}

