package com.strategysquad.research;

import com.strategysquad.support.QuestDbConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Queries the historical backend to fetch average empirical delta and average
 * empirical theta benefit per minute for a given option context bucket.
 *
 * <p><strong>No Black-Scholes. No theoretical Greeks. No IV.</strong>
 * All values originate from observed historical price movements in
 * {@code options_enriched}.
 *
 * <h2>Delta average</h2>
 * Computed as an OLS slope of (option price ~ underlying price) over all historical
 * rows matching the cohort bucket. This mirrors the approach used by
 * {@link EmpiricalDeltaResponseService} for live data but operates on the full
 * historical table.
 *
 * <h2>Theta benefit per minute</h2>
 * Computed as the average per-contract premium decay rate after subtracting the
 * expected delta move, normalised to per-minute. We approximate this from the
 * contextual bucket's average option price relative to its time-to-expiry bucket:
 * the difference in avg_option_price between consecutive 15m buckets at the same
 * moneyness is the empirical 15-minute decay, divided by 15 to get per-minute rate.
 *
 * <h2>Matching hierarchy</h2>
 * <ol>
 *   <li>Exact underlying + expiry_date + strike + option_type (per-contract)</li>
 *   <li>underlying + DTE bucket + moneyness bucket + option_type (cross-contract cohort)</li>
 *   <li>underlying + option_type + nearest moneyness bucket (broadest fallback)</li>
 * </ol>
 */
public class HistoricalThetaDeltaAdapter {

    private static final int BUCKET_WINDOW = 4;   // ± 4 time-buckets (~1 h) either side

    private final String jdbcUrl;

    public HistoricalThetaDeltaAdapter(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result from a historical lookup. Carries the raw numbers and the bucket
     * context that was actually used (for the debug section of the response).
     */
    public record DeltaLookupResult(
            double averageDelta,
            int sampleSize,
            String matchLevel,
            ThetaDeltaSenseCheckResponse.HistoricalBucket bucket
    ) {}

    public record ThetaLookupResult(
            double averageThetaBenefitPerMin,
            int sampleSize,
            String matchLevel,
            ThetaDeltaSenseCheckResponse.HistoricalBucket bucket
    ) {}

    /**
     * Returns the average empirical delta for the given contract context.
     * Returns {@code null} when the historical backend has no matching data.
     */
    public DeltaLookupResult fetchAverageDelta(
            String underlying,
            String optionType,
            double strike,
            LocalDate expiry,
            int timeBucket15m,
            int moneynessBucket
    ) throws SQLException {
        // Level 1 — per-contract OLS slope
        DeltaLookupResult exact = fetchDeltaByContract(underlying, optionType, strike, expiry, timeBucket15m);
        if (exact != null) return exact;

        // Level 2 — cohort (DTE bucket + moneyness bucket)
        DeltaLookupResult cohort = fetchDeltaByCohort(underlying, optionType, timeBucket15m, moneynessBucket);
        if (cohort != null) return cohort;

        // Level 3 — broadest fallback: underlying + option_type + moneyness bucket only
        return fetchDeltaByMoneyness(underlying, optionType, moneynessBucket);
    }

    /**
     * Returns the average empirical theta benefit per minute.
     * Returns {@code null} when the historical backend has no matching data.
     */
    public ThetaLookupResult fetchAverageThetaBenefitPerMin(
            String underlying,
            String optionType,
            int timeBucket15m,
            int moneynessBucket
    ) throws SQLException {
        // We estimate empirical decay from consecutive time-bucket price steps in
        // options_context_buckets, which represents the historical average premium
        // at a given time-to-expiry and moneyness.  The drop in avg_option_price
        // between adjacent buckets is the empirical 15-min decay.
        return fetchThetaFromContextBuckets(underlying, optionType, timeBucket15m, moneynessBucket);
    }

    // -------------------------------------------------------------------------
    // Delta lookups
    // -------------------------------------------------------------------------

    /**
     * OLS slope of (option price ~ underlying price) for rows within ±BUCKET_WINDOW
     * 15m buckets of the target, restricted to the specific contract.
     */
    private DeltaLookupResult fetchDeltaByContract(
            String underlying, String optionType, double strike,
            LocalDate expiry, int timeBucket15m
    ) throws SQLException {
        String sql = """
                SELECT last_price, underlying_price
                FROM options_enriched
                WHERE underlying = ?
                  AND option_type = ?
                  AND strike = ?
                  AND expiry_date >= ?
                  AND expiry_date < ?
                  AND time_bucket_15m BETWEEN ? AND ?
                  AND last_price > 0
                  AND underlying_price > 0
                """;
        int lo = Math.max(0, timeBucket15m - BUCKET_WINDOW);
        int hi = timeBucket15m + BUCKET_WINDOW;

        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, underlying.toUpperCase());
            ps.setString(2, optionType.toUpperCase());
            ps.setDouble(3, strike);
            ps.setTimestamp(4, java.sql.Timestamp.valueOf(expiry.atStartOfDay()));
            ps.setTimestamp(5, java.sql.Timestamp.valueOf(expiry.plusDays(1).atStartOfDay()));
            ps.setInt(6, lo);
            ps.setInt(7, hi);

            OlsAccumulator acc = new OlsAccumulator();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    acc.add(rs.getDouble("underlying_price"), rs.getDouble("last_price"));
                }
            }
            if (!acc.hasEnoughData()) return null;
            double slope = acc.slope();
            String dteBucket = dteBucketLabel(timeBucket15m);
            ThetaDeltaSenseCheckResponse.HistoricalBucket bucket = new ThetaDeltaSenseCheckResponse.HistoricalBucket(
                    underlying, optionType, dteBucket, moneynessBucketLabel(0), null, "exact");
            return new DeltaLookupResult(slope, acc.n(), "exact", bucket);
        }
    }

    /** Cross-contract cohort: same DTE bucket + moneyness bucket + option_type. */
    private DeltaLookupResult fetchDeltaByCohort(
            String underlying, String optionType, int timeBucket15m, int moneynessBucket
    ) throws SQLException {
        String sql = """
                SELECT last_price, underlying_price
                FROM options_enriched
                WHERE underlying = ?
                  AND option_type = ?
                  AND time_bucket_15m BETWEEN ? AND ?
                  AND moneyness_bucket = ?
                  AND last_price > 0
                  AND underlying_price > 0
                """;
        int lo = Math.max(0, timeBucket15m - BUCKET_WINDOW);
        int hi = timeBucket15m + BUCKET_WINDOW;

        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, underlying.toUpperCase());
            ps.setString(2, optionType.toUpperCase());
            ps.setInt(3, lo);
            ps.setInt(4, hi);
            ps.setInt(5, moneynessBucket);

            OlsAccumulator acc = new OlsAccumulator();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    acc.add(rs.getDouble("underlying_price"), rs.getDouble("last_price"));
                }
            }
            if (!acc.hasEnoughData()) return null;
            double slope = acc.slope();
            String dteBucket = dteBucketLabel(timeBucket15m);
            String mbLabel = moneynessBucketLabel(moneynessBucket);
            ThetaDeltaSenseCheckResponse.HistoricalBucket bucket = new ThetaDeltaSenseCheckResponse.HistoricalBucket(
                    underlying, optionType, dteBucket, mbLabel, null, "dte+moneyness");
            return new DeltaLookupResult(slope, acc.n(), "dte+moneyness", bucket);
        }
    }

    /** Broadest fallback: underlying + option_type + moneyness bucket across all DTE. */
    private DeltaLookupResult fetchDeltaByMoneyness(
            String underlying, String optionType, int moneynessBucket
    ) throws SQLException {
        String sql = """
                SELECT last_price, underlying_price
                FROM options_enriched
                WHERE underlying = ?
                  AND option_type = ?
                  AND moneyness_bucket = ?
                  AND last_price > 0
                  AND underlying_price > 0
                """;
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, underlying.toUpperCase());
            ps.setString(2, optionType.toUpperCase());
            ps.setInt(3, moneynessBucket);

            OlsAccumulator acc = new OlsAccumulator();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    acc.add(rs.getDouble("underlying_price"), rs.getDouble("last_price"));
                }
            }
            if (!acc.hasEnoughData()) return null;
            double slope = acc.slope();
            String mbLabel = moneynessBucketLabel(moneynessBucket);
            ThetaDeltaSenseCheckResponse.HistoricalBucket bucket = new ThetaDeltaSenseCheckResponse.HistoricalBucket(
                    underlying, optionType, null, mbLabel, null, "moneyness_only");
            return new DeltaLookupResult(slope, acc.n(), "moneyness_only", bucket);
        }
    }

    // -------------------------------------------------------------------------
    // Theta lookups
    // -------------------------------------------------------------------------

    /**
     * Fetches empirical theta benefit per minute from options_context_buckets.
     *
     * <p>We look at the average option price in the matched bucket and the bucket
     * immediately above it (more time to expiry).  The difference divided by 15
     * gives the historical per-minute decay rate at that moneyness and DTE.
     *
     * <p>For a SHORT seller positive decay (price falls over time) is beneficial.
     * The caller (service) applies the LONG/SHORT sign convention.
     */
    private ThetaLookupResult fetchThetaFromContextBuckets(
            String underlying, String optionType, int timeBucket15m, int moneynessBucket
    ) throws SQLException {
        // Fetch a window of consecutive context-bucket prices to estimate per-15m decay
        String sql = """
                SELECT time_bucket_15m, avg_option_price, sample_count
                FROM options_context_buckets
                WHERE underlying = ?
                  AND option_type = ?
                  AND moneyness_bucket = ?
                  AND time_bucket_15m BETWEEN ? AND ?
                ORDER BY time_bucket_15m ASC
                """;
        int lo = Math.max(0, timeBucket15m - BUCKET_WINDOW * 2);
        int hi = timeBucket15m + BUCKET_WINDOW * 2;

        record BucketRow(int bucket, double avgPrice, long sampleCount) {}

        java.util.List<BucketRow> rows = new java.util.ArrayList<>();

        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, underlying.toUpperCase());
            ps.setString(2, optionType.toUpperCase());
            ps.setInt(3, moneynessBucket);
            ps.setInt(4, lo);
            ps.setInt(5, hi);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new BucketRow(
                            rs.getInt("time_bucket_15m"),
                            rs.getDouble("avg_option_price"),
                            rs.getLong("sample_count")
                    ));
                }
            }
        }

        if (rows.size() < 2) return null;

        // Compute average per-15m decay as price change across adjacent buckets,
        // then divide by 15 for per-minute rate.  A lower time_bucket_15m means
        // less time to expiry, so we expect avg_option_price to fall as bucket
        // index decreases (moving towards expiry).  We measure decay as:
        //   decay_per_15m = avg_price(t) - avg_price(t-1)   [should be negative for theta decay]
        // Theta benefit per minute = -decay_per_15m / 15     [positive when premium falls]
        double sumDecayPer15m = 0.0;
        int pairs = 0;
        long totalSamples = 0;
        for (int i = 1; i < rows.size(); i++) {
            BucketRow prev = rows.get(i - 1);
            BucketRow curr = rows.get(i);
            if (curr.bucket() - prev.bucket() == 1) {
                // adjacent buckets — curr has MORE time to expiry (higher bucket = more TTE)
                // as time passes bucket decreases, so price at prev is lower = decay
                double decayPer15m = prev.avgPrice() - curr.avgPrice();  // positive when decaying towards expiry
                sumDecayPer15m += decayPer15m;
                pairs++;
                totalSamples += curr.sampleCount();
            }
        }
        if (pairs == 0) return null;

        double avgDecayPer15m = sumDecayPer15m / pairs;
        double thetaPerMin = avgDecayPer15m / 15.0;

        String dteBucket = dteBucketLabel(timeBucket15m);
        String mbLabel = moneynessBucketLabel(moneynessBucket);
        ThetaDeltaSenseCheckResponse.HistoricalBucket bucket = new ThetaDeltaSenseCheckResponse.HistoricalBucket(
                underlying, optionType, dteBucket, mbLabel, null, "dte+moneyness");
        return new ThetaLookupResult(thetaPerMin, (int) Math.min(totalSamples / pairs, Integer.MAX_VALUE),
                "dte+moneyness", bucket);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Minimal OLS accumulator for slope calculation (option price ~ underlying price). */
    static final class OlsAccumulator {
        private double sumX, sumY, sumXX, sumXY;
        private int n;
        private static final int MIN_OBSERVATIONS = 10;
        private static final double MIN_VARIANCE = 0.01;

        void add(double x, double y) {
            sumX += x; sumY += y; sumXX += x * x; sumXY += x * y; n++;
        }

        boolean hasEnoughData() {
            if (n < MIN_OBSERVATIONS) return false;
            double variance = sumXX - (sumX * sumX) / n;
            return Math.abs(variance) >= MIN_VARIANCE;
        }

        double slope() {
            double variance = sumXX - (sumX * sumX) / n;
            double covariance = sumXY - (sumX * sumY) / n;
            return covariance / variance;
        }

        int n() { return n; }
    }

    private static String dteBucketLabel(int timeBucket15m) {
        int totalMinutes = timeBucket15m * 15;
        int days = totalMinutes / 1440;
        if (days == 0) return "weekly_0_3";
        if (days <= 3) return "weekly_0_3";
        if (days <= 7) return "weekly_3_7";
        if (days <= 30) return "monthly_7_30";
        return "monthly_30+";
    }

    private static String moneynessBucketLabel(int moneynessBucket) {
        if (moneynessBucket == 0) return "ATM";
        if (moneynessBucket > 0) return "OTM+" + moneynessBucket;
        return "ITM" + moneynessBucket;
    }
}
