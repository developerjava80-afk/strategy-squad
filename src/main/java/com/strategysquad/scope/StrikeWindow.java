package com.strategysquad.scope;

import java.util.List;
import java.util.Objects;

/**
 * Defines how the set of strikes in a {@link Scope} is bounded.
 *
 * <p>This is a sealed hierarchy: exactly four concrete forms are supported.
 * {@link com.strategysquad.scope.UniverseResolver} (Phase 2) translates each
 * form into a SQL {@code BETWEEN} or {@code IN} clause against
 * {@code instrument_master}.
 *
 * <p>Hard cap: any window that would yield more than 100 strikes is rejected
 * at the controller boundary with {@code STRIKE_WINDOW_TOO_WIDE} before the
 * resolver is ever called.
 *
 * <p>Serialisation: stored as JSON in the {@code strike_window} column of
 * {@code scope_state}. The canonical JSON schema per variant:
 * <ul>
 *   <li>{@code {"kind":"ATM_PCT","pct":4.0}}
 *   <li>{@code {"kind":"ATM_POINTS","points":500.0}}
 *   <li>{@code {"kind":"EXPLICIT_RANGE","low":24000.0,"high":25000.0}}
 *   <li>{@code {"kind":"LEGS_ONLY","instrumentIds":["INS_NIFTY_20260430_24800_CE",...]}}
 * </ul>
 */
public sealed interface StrikeWindow
        permits StrikeWindow.AtmPct, StrikeWindow.AtmPoints,
                StrikeWindow.ExplicitRange, StrikeWindow.LegsOnly {

    /**
     * Returns the discriminator string written into the {@code kind} JSON field.
     * Used by serialisation/deserialisation helpers in {@link ScopeStore}.
     */
    String kind();

    // =========================================================================
    // Concrete variants
    // =========================================================================

    /**
     * ±{@code pct}% of the current spot price.
     *
     * <p>Example: {@code pct=4.0} with NIFTY spot at 24 800 yields strikes
     * between 23 808 and 25 792, rounded to the nearest strike step.
     *
     * <p>Conservative default: 4%. The controller rejects requests where the
     * resulting strike count exceeds the hard cap of 100.
     */
    record AtmPct(double pct) implements StrikeWindow {

        public AtmPct {
            if (pct <= 0 || pct > 50) {
                throw new IllegalArgumentException(
                        "AtmPct.pct must be in (0, 50], got: " + pct);
            }
        }

        @Override
        public String kind() {
            return "ATM_PCT";
        }
    }

    /**
     * ±{@code points} of the current spot price in index points.
     *
     * <p>Example: {@code points=500} with NIFTY at 24 800 yields strikes
     * between 24 300 and 25 300, rounded to the nearest strike step.
     */
    record AtmPoints(double points) implements StrikeWindow {

        public AtmPoints {
            if (points <= 0) {
                throw new IllegalArgumentException(
                        "AtmPoints.points must be > 0, got: " + points);
            }
        }

        @Override
        public String kind() {
            return "ATM_POINTS";
        }
    }

    /**
     * Explicit strike range — instruments with {@code strike BETWEEN low AND high}.
     *
     * <p>Used when the trader knows exactly which strikes matter. The resolver
     * does not adjust for spot; {@code low} and {@code high} are absolute strike
     * values.
     */
    record ExplicitRange(double low, double high) implements StrikeWindow {

        public ExplicitRange {
            if (low >= high) {
                throw new IllegalArgumentException(
                        "ExplicitRange.low must be < high, got: low=" + low + " high=" + high);
            }
            if (low <= 0) {
                throw new IllegalArgumentException(
                        "ExplicitRange.low must be > 0, got: " + low);
            }
        }

        @Override
        public String kind() {
            return "EXPLICIT_RANGE";
        }
    }

    /**
     * Explicit list of instrument IDs — the universe is exactly these instruments.
     *
     * <p>Used when the trader has already chosen specific legs (e.g. after the
     * scanner has identified candidates and the trader selected two legs). The
     * resolver validates that every ID exists in {@code instrument_master} and
     * that CE/PE pairing is complete for strategies that require it.
     *
     * <p>Instrument IDs follow the canonical format:
     * {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>}.
     */
    record LegsOnly(List<String> instrumentIds) implements StrikeWindow {

        public LegsOnly {
            Objects.requireNonNull(instrumentIds, "instrumentIds must not be null");
            if (instrumentIds.isEmpty()) {
                throw new IllegalArgumentException("LegsOnly.instrumentIds must not be empty");
            }
            // Defensive copy — the record must be immutable.
            instrumentIds = List.copyOf(instrumentIds);
        }

        @Override
        public String kind() {
            return "LEGS_ONLY";
        }
    }
}
