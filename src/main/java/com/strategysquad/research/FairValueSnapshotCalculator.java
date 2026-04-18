package com.strategysquad.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Computes fair-value distribution statistics from historical cohort prices.
 */
public final class FairValueSnapshotCalculator {
    private FairValueSnapshotCalculator() {
    }

    public static FairValueSnapshot calculate(
            CanonicalCohortKey cohort,
            List<Double> historicalPrices,
            double currentOptionPrice
    ) {
        Objects.requireNonNull(cohort, "cohort must not be null");
        Objects.requireNonNull(historicalPrices, "historicalPrices must not be null");

        List<Double> sortedPrices = historicalPrices.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sortedPrices.isEmpty()) {
            return new FairValueSnapshot(
                    cohort,
                    0,
                    "Sparse",
                    new FairValueSnapshot.DistributionStats(0, 0, 0, 0, 0, 0, 0, 0),
                    new FairValueSnapshot.CurrentPricePosition(
                            currentOptionPrice,
                            0,
                            "Sparse",
                            "No exact historical observations were found for the resolved cohort."
                    )
            );
        }

        double min = sortedPrices.get(0);
        double max = sortedPrices.get(sortedPrices.size() - 1);
        double mean = sortedPrices.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int percentile = percentileRank(sortedPrices, currentOptionPrice);
        String label = valuationLabel(percentile);

        return new FairValueSnapshot(
                cohort,
                sortedPrices.size(),
                cohortStrength(sortedPrices.size()),
                new FairValueSnapshot.DistributionStats(
                        min,
                        quantile(sortedPrices, 0.10),
                        quantile(sortedPrices, 0.25),
                        quantile(sortedPrices, 0.50),
                        mean,
                        quantile(sortedPrices, 0.75),
                        quantile(sortedPrices, 0.90),
                        max
                ),
                new FairValueSnapshot.CurrentPricePosition(
                        currentOptionPrice,
                        percentile,
                        label,
                        valuationInterpretation(label)
                )
        );
    }

    static double quantile(List<Double> sortedPrices, double percentile) {
        if (sortedPrices.isEmpty()) {
            return 0;
        }
        if (sortedPrices.size() == 1) {
            return sortedPrices.get(0);
        }
        double position = percentile * (sortedPrices.size() - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) {
            return sortedPrices.get(lowerIndex);
        }
        double lower = sortedPrices.get(lowerIndex);
        double upper = sortedPrices.get(upperIndex);
        double weight = position - lowerIndex;
        return lower + ((upper - lower) * weight);
    }

    static int percentileRank(List<Double> sortedPrices, double value) {
        if (sortedPrices.isEmpty()) {
            return 0;
        }
        int countLessThanOrEqual = 0;
        for (double price : sortedPrices) {
            if (price <= value) {
                countLessThanOrEqual++;
            }
        }
        return (int) Math.round((countLessThanOrEqual * 100.0) / sortedPrices.size());
    }

    static String valuationLabel(int percentile) {
        if (percentile == 0) {
            return "Sparse";
        }
        if (percentile <= 10 || percentile >= 90) {
            return "Extreme";
        }
        if (percentile < 35) {
            return "Cheap";
        }
        if (percentile > 65) {
            return "Rich";
        }
        return "Fair";
    }

    static String cohortStrength(long sampleSize) {
        if (sampleSize >= 320) {
            return "Strong";
        }
        if (sampleSize < 160) {
            return "Sparse";
        }
        return "Mixed";
    }

    static String valuationInterpretation(String label) {
        return switch (label) {
            case "Cheap" ->
                    "Current price is sitting below the core historical distribution for the resolved cohort.";
            case "Rich" ->
                    "Current price is sitting above the cohort center and looks historically elevated.";
            case "Extreme" ->
                    "Current price is pressing into the edge of the cohort distribution and deserves extra caution.";
            case "Sparse" ->
                    "The cohort is too thin for a trustworthy valuation read.";
            default ->
                    "Current price is sitting near the center of the resolved historical cohort.";
        };
    }
}
