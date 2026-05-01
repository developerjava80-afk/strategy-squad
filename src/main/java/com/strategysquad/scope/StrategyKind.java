package com.strategysquad.scope;

/**
 * Supported strategy kinds for a trading scope.
 *
 * <p>The strategy kind drives which legs the scanner considers and which
 * strike window is meaningful. {@code ANALYSIS_ONLY} is a non-trading mode
 * that loads the universe for research without implying any leg structure.
 *
 * <p>Matches the {@code strategy} SYMBOL column in {@code scope_state}.
 *
 * <p><strong>Note:</strong> this enum declares the set of kinds the scope
 * layer understands. The agentic decision loop may support a subset; the
 * research console supports all including {@code ANALYSIS_ONLY}.
 */
public enum StrategyKind {

    /** Sell one CE and one PE at equidistant strikes from ATM. */
    SHORT_STRANGLE,

    /** Four-leg position: short strangle with long wings for defined risk. */
    IRON_CONDOR,

    /** Sell a put spread — short higher-strike PE, long lower-strike PE. */
    BULL_PUT_SPREAD,

    /** Sell a call spread — short lower-strike CE, long higher-strike CE. */
    BEAR_CALL_SPREAD,

    /** Buy one ATM CE and one ATM PE. */
    LONG_STRADDLE,

    /** Buy one CE and one PE at equidistant strikes from ATM. */
    LONG_STRANGLE,

    /**
     * No trade intent — load the bounded universe for research and analysis
     * without committing to a specific leg structure. The scanner still runs
     * but candidates are scored for informational purposes only.
     */
    ANALYSIS_ONLY
}
