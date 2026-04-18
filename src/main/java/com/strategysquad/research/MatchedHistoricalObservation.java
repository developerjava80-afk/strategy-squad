package com.strategysquad.research;

import java.time.LocalDate;

/**
 * Matched historical cohort row plus forward outcomes used by diagnostics and case exploration.
 */
public record MatchedHistoricalObservation(
        String instrumentId,
        LocalDate tradeDate,
        double entryPrice,
        Double nextDayReturnPct,
        Double expiryReturnPct
) {
}
