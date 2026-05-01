package com.strategysquad.agentic.scanner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Immutable output record for one scanner candidate contract.
 *
 * <p>Produced by {@code CandidateScoringEngine} from a raw contract row returned by
 * {@code ScannerQuery}. Every field carries explicit unit documentation. Disqualified
 * contracts still appear in the output with all scores set to zero and
 * {@link #disqualifierReason()} populated — they are never silently dropped.
 *
 * <p>Sign convention: all prices and premium values are in <b>points</b> (NSE index
 * points), not rupees. Moneyness is always expressed as {@code strike − spot} for CE
 * and {@code spot − strike} for PE so that positive moneyness means in-the-money.
 *
 * <p>Do not add DB write logic or scoring logic to this record. It is a pure value
 * object. See {@code CandidateScoringEngine} for scoring and {@code ScannerQuery} for
 * data fetching.
 */
public record CandidateOpportunity(

        /**
         * Stable identifier for this candidate in a single scan run.
         * Format: {@code SCAN_<underlying>_<instrumentId>_<yyyyMMddHHmm>}.
         * Unique within a scan, not across sessions.
         */
        String candidateId,

        /**
         * Underlying index name. One of {@code NIFTY} or {@code BANKNIFTY}.
         */
        String underlying,

        /**
         * Canonical instrument identifier from {@code instrument_master}.
         * Format: {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>}.
         */
        String instrumentId,

        /**
         * NSE trading symbol as it appears in market data feeds
         * (e.g., {@code NIFTY26APR24800CE}).
         */
        String tradingSymbol,

        /**
         * Option type. One of {@code CE} (call) or {@code PE} (put).
         */
        String optionType,

        /**
         * Strike price. Units: NSE index points (e.g., 24800.00 for NIFTY 24800 CE).
         */
        BigDecimal strike,

        /**
         * Expiry date of this contract (NSE calendar date, no time component).
         */
        LocalDate expiryDate,

        /**
         * Expiry classification. One of {@code WEEKLY} or {@code MONTHLY}.
         * Weekly and monthly contracts are never mixed in the same cohort.
         */
        String expiryType,

        /**
         * Spot price of the underlying index at scan time.
         * Units: NSE index points.
         */
        BigDecimal spot,

        /**
         * Last traded price of this option contract at scan time.
         * Units: NSE index points.
         */
        BigDecimal lastPrice,

        /**
         * Best bid price in the order book at scan time.
         * Units: NSE index points. Zero if no bid is available.
         */
        BigDecimal bidPrice,

        /**
         * Best ask price in the order book at scan time.
         * Units: NSE index points. Zero if no ask is available.
         */
        BigDecimal askPrice,

        /**
         * Moneyness expressed as the absolute distance between strike and spot.
         * Positive means in-the-money (CE: strike &lt; spot; PE: strike &gt; spot).
         * Units: NSE index points.
         */
        BigDecimal moneynessPoints,

        /**
         * Moneyness bucket — discretised moneyness for cohort matching against
         * {@code options_context_buckets}. Bucket boundaries defined in
         * {@code OptionsContextBucketAggregator}. Units: index points (bucket midpoint).
         */
        int moneynessBucket,

        /**
         * 15-minute time-to-expiry bucket for cohort matching.
         * Represents the number of 15-minute slots remaining to expiry at scan time.
         * A bucket of 4 means 45–60 minutes to expiry.
         */
        int timeBucket15m,

        /**
         * Historical average price for this contract's cohort
         * ({@code underlying + option_type + moneyness_bucket + time_bucket_15m})
         * sourced from {@code options_context_buckets}.
         * Units: NSE index points. Zero if no historical cohort exists.
         */
        BigDecimal historicalAvgPrice,

        /**
         * Absolute difference between {@link #lastPrice()} and
         * {@link #historicalAvgPrice()}: {@code lastPrice − historicalAvgPrice}.
         * Positive means the contract is trading richer than historical average (good for
         * short-selling). Negative means cheaper than historical average.
         * Units: NSE index points.
         */
        BigDecimal premiumRichnessPoints,

        /**
         * Premium richness expressed as a percentage of historical average:
         * {@code (lastPrice − historicalAvgPrice) / historicalAvgPrice × 100}.
         * Units: percent (e.g., 15.0 means 15% above historical average).
         */
        BigDecimal premiumRichnessPct,

        /**
         * Liquidity score for this contract at scan time. Range: 0.0–1.0.
         * A score of 1.0 means tightest bid-ask spread and highest relative volume
         * vs historical average. A score of 0.0 means illiquid (wide spread or zero
         * volume). Dimensionless ratio.
         */
        double liquidityScore,

        /**
         * Theta opportunity score. Range: 0.0–1.0.
         * Higher score indicates a more attractive theta-decay opportunity — typically
         * a contract with meaningful premium richness at a short time-to-expiry bucket.
         * Dimensionless composite score.
         */
        double thetaOpportunityScore,

        /**
         * Delta risk score. Range: 0.0–1.0.
         * Higher score means lower delta risk. Near-ATM contracts with uncertain
         * empirical delta receive a penalty. Dimensionless composite score.
         */
        double deltaRiskScore,

        /**
         * Weighted composite score combining all scoring components.
         * Weights: theta_opportunity (0.40) + premium_richness (0.30) +
         * liquidity (0.20) + delta_risk (0.10). Range: 0.0–1.0.
         * Candidates are ranked descending by this field.
         * Disqualified candidates have this field set to 0.0.
         * Dimensionless composite score.
         */
        double totalScore,

        /**
         * If present, the reason this candidate was disqualified and excluded from
         * scoring. Machine-readable code (e.g., {@code ZERO_BID}, {@code MISSING_COHORT},
         * {@code ZERO_VOLUME}, {@code DTE_OUT_OF_WINDOW}).
         * {@link Optional#empty()} for qualified candidates.
         * Disqualified candidates still appear in scanner output at the bottom of the
         * ranked list with all scores set to 0.0.
         */
        Optional<String> disqualifierReason

) {
    /**
     * Compact constructor — enforces non-null invariants on all required fields and
     * ensures {@link #disqualifierReason()} is never null (use
     * {@link Optional#empty()} for qualified candidates).
     */
    public CandidateOpportunity {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        if (underlying == null || underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId must not be blank");
        }
        if (tradingSymbol == null || tradingSymbol.isBlank()) {
            throw new IllegalArgumentException("tradingSymbol must not be blank");
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
        if (expiryType == null || expiryType.isBlank()) {
            throw new IllegalArgumentException("expiryType must not be blank");
        }
        if (spot == null) {
            throw new IllegalArgumentException("spot must not be null");
        }
        if (lastPrice == null) {
            throw new IllegalArgumentException("lastPrice must not be null");
        }
        if (bidPrice == null) {
            throw new IllegalArgumentException("bidPrice must not be null");
        }
        if (askPrice == null) {
            throw new IllegalArgumentException("askPrice must not be null");
        }
        if (moneynessPoints == null) {
            throw new IllegalArgumentException("moneynessPoints must not be null");
        }
        if (historicalAvgPrice == null) {
            throw new IllegalArgumentException("historicalAvgPrice must not be null");
        }
        if (premiumRichnessPoints == null) {
            throw new IllegalArgumentException("premiumRichnessPoints must not be null");
        }
        if (premiumRichnessPct == null) {
            throw new IllegalArgumentException("premiumRichnessPct must not be null");
        }
        // disqualifierReason must not itself be null — use Optional.empty() for qualified candidates
        if (disqualifierReason == null) {
            throw new IllegalArgumentException(
                    "disqualifierReason must not be null; use Optional.empty() for qualified candidates");
        }
    }
}
