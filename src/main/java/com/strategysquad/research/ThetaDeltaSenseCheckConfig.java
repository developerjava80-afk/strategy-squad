package com.strategysquad.research;

/**
 * Configuration thresholds for the Theta + Delta Sense Check feature.
 *
 * <p>All values have safe defaults matching the product specification. Override via
 * system properties or by supplying a custom instance in tests.
 *
 * <p>This config is deliberately separate from any Black-Scholes or theoretical-Greek
 * configuration. The only inputs it governs are empirical/historical thresholds.
 */
public record ThetaDeltaSenseCheckConfig(
        /** Minimum underlying move (in index points) required to trust observed delta. */
        double minUnderlyingMovePoints,

        /** Minimum elapsed minutes required to trust theta benefit per minute. */
        int minElapsedMinutes,

        /** Minimum historical sample size to treat averages as reliable. */
        int minimumSampleSize,

        /** Absolute delta deviation % within which status = NORMAL. */
        double deltaNormalPct,

        /** Absolute delta deviation % above which status = VERY_HIGH / VERY_LOW. */
        double deltaHighPct,

        /** Absolute theta deviation % within which status = NORMAL. */
        double thetaNormalPct,

        /** Absolute theta deviation % above which status = VERY_HIGH / VERY_LOW. */
        double thetaHighPct,

        /** Lower clamp on observed delta for display (raw value is always preserved). */
        double observedDeltaClampMin,

        /** Upper clamp on observed delta for display (raw value is always preserved). */
        double observedDeltaClampMax
) {

    /** Default instance matching the product specification. */
    public static ThetaDeltaSenseCheckConfig defaults() {
        return new ThetaDeltaSenseCheckConfig(
                5.0,   // minUnderlyingMovePoints
                5,     // minElapsedMinutes
                30,    // minimumSampleSize
                20.0,  // deltaNormalPct
                50.0,  // deltaHighPct
                20.0,  // thetaNormalPct
                50.0,  // thetaHighPct
                -1.5,  // observedDeltaClampMin
                1.5    // observedDeltaClampMax
        );
    }

    public ThetaDeltaSenseCheckConfig {
        if (minUnderlyingMovePoints <= 0) {
            throw new IllegalArgumentException("minUnderlyingMovePoints must be positive");
        }
        if (minElapsedMinutes <= 0) {
            throw new IllegalArgumentException("minElapsedMinutes must be positive");
        }
        if (minimumSampleSize <= 0) {
            throw new IllegalArgumentException("minimumSampleSize must be positive");
        }
        if (deltaNormalPct <= 0 || deltaHighPct <= 0 || deltaNormalPct >= deltaHighPct) {
            throw new IllegalArgumentException("delta deviation thresholds must be positive and normal < high");
        }
        if (thetaNormalPct <= 0 || thetaHighPct <= 0 || thetaNormalPct >= thetaHighPct) {
            throw new IllegalArgumentException("theta deviation thresholds must be positive and normal < high");
        }
        if (observedDeltaClampMin >= observedDeltaClampMax) {
            throw new IllegalArgumentException("observedDeltaClampMin must be less than observedDeltaClampMax");
        }
    }
}
