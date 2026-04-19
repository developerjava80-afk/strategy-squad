package com.strategysquad.research;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Computes cohort price behavior across multiple historical windows.
 */
public final class TimeframeAnalysisSnapshotCalculator {
    private static final ZoneId IST = ZoneId.of("Asia/Calcutta");

    private TimeframeAnalysisSnapshotCalculator() {
    }

    public static TimeframeAnalysisSnapshot calculate(
            CanonicalCohortKey cohort,
            List<Observation> observations,
            double currentOptionPrice,
            String selectedTimeframe,
            LocalDate customFrom,
            LocalDate customTo
    ) {
        Objects.requireNonNull(cohort, "cohort must not be null");
        Objects.requireNonNull(observations, "observations must not be null");

        if (observations.isEmpty()) {
            List<TimeframeAnalysisSnapshot.WindowStats> emptyWindows = List.of(
                    emptyWindow("5Y"),
                    emptyWindow("2Y"),
                    emptyWindow("1Y"),
                    emptyWindow("6M"),
                    emptyWindow("3M"),
                    emptyWindow("1M")
            );
            return new TimeframeAnalysisSnapshot(
                    cohort,
                    "",
                    currentOptionPrice,
                    emptyWindows,
                    emptyWindow(selectedTimeframe == null || selectedTimeframe.isBlank() ? "1Y" : selectedTimeframe)
            );
        }

        LocalDate anchorDate = observations.stream()
                .map(item -> item.exchangeTs().atZone(IST).toLocalDate())
                .max(Comparator.naturalOrder())
                .orElseThrow();

        List<TimeframeAnalysisSnapshot.WindowStats> windows = List.of(
                buildWindow("5Y", observations, currentOptionPrice, anchorDate.minusYears(5).plusDays(1), anchorDate),
                buildWindow("2Y", observations, currentOptionPrice, anchorDate.minusYears(2).plusDays(1), anchorDate),
                buildWindow("1Y", observations, currentOptionPrice, anchorDate.minusYears(1).plusDays(1), anchorDate),
                buildWindow("6M", observations, currentOptionPrice, anchorDate.minusMonths(6).plusDays(1), anchorDate),
                buildWindow("3M", observations, currentOptionPrice, anchorDate.minusMonths(3).plusDays(1), anchorDate),
                buildWindow("1M", observations, currentOptionPrice, anchorDate.minusMonths(1).plusDays(1), anchorDate)
        );

        TimeframeAnalysisSnapshot.WindowStats selectedWindow = selectWindow(
                windows,
                observations,
                currentOptionPrice,
                selectedTimeframe,
                customFrom,
                customTo,
                anchorDate
        );

        return new TimeframeAnalysisSnapshot(
                cohort,
                anchorDate.toString(),
                currentOptionPrice,
                windows,
                selectedWindow
        );
    }

    private static TimeframeAnalysisSnapshot.WindowStats selectWindow(
            List<TimeframeAnalysisSnapshot.WindowStats> windows,
            List<Observation> observations,
            double currentOptionPrice,
            String selectedTimeframe,
            LocalDate customFrom,
            LocalDate customTo,
            LocalDate anchorDate
    ) {
        String normalized = selectedTimeframe == null || selectedTimeframe.isBlank() ? "1Y" : selectedTimeframe;
        if ("CUSTOM".equalsIgnoreCase(normalized)) {
            if (customFrom == null || customTo == null) {
                throw new IllegalArgumentException("Custom timeframe requires both from and to dates");
            }
            if (customTo.isBefore(customFrom)) {
                throw new IllegalArgumentException("Custom timeframe end date must be on or after start date");
            }
            return buildWindow("CUSTOM", observations, currentOptionPrice, customFrom, customTo);
        }
        return windows.stream()
                .filter(item -> item.label().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(buildWindow(normalized, observations, currentOptionPrice, anchorDate.minusYears(1).plusDays(1), anchorDate));
    }

    private static TimeframeAnalysisSnapshot.WindowStats buildWindow(
            String label,
            List<Observation> observations,
            double currentOptionPrice,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        List<Observation> filtered = observations.stream()
                .filter(item -> {
                    LocalDate tradeDate = item.exchangeTs().atZone(IST).toLocalDate();
                    return !tradeDate.isBefore(fromInclusive) && !tradeDate.isAfter(toInclusive);
                })
                .toList();
        if (filtered.isEmpty()) {
            return emptyWindow(label);
        }

        List<Double> prices = filtered.stream()
                .map(Observation::lastPrice)
                .sorted()
                .toList();
        double average = prices.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = FairValueSnapshotCalculator.quantile(prices, 0.50);
        int percentile = FairValueSnapshotCalculator.percentileRank(prices, currentOptionPrice);
        long uniqueContracts = new LinkedHashSet<>(filtered.stream().map(Observation::instrumentId).toList()).size();

        return new TimeframeAnalysisSnapshot.WindowStats(
                label,
                average,
                median,
                filtered.size(),
                uniqueContracts,
                percentile,
                currentOptionPrice - average
        );
    }

    private static TimeframeAnalysisSnapshot.WindowStats emptyWindow(String label) {
        return new TimeframeAnalysisSnapshot.WindowStats(label, 0, 0, 0, 0, 0, 0);
    }

    public record Observation(
            Instant exchangeTs,
            String instrumentId,
            double lastPrice
    ) {
        public Observation {
            Objects.requireNonNull(exchangeTs, "exchangeTs must not be null");
            Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        }
    }
}
