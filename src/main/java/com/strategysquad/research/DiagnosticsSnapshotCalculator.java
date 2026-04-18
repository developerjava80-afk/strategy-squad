package com.strategysquad.research;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Calculates diagnostics and representative case selections from matched cohort rows.
 */
public final class DiagnosticsSnapshotCalculator {
    private DiagnosticsSnapshotCalculator() {
    }

    public static DiagnosticsSnapshot calculate(
            CanonicalCohortKey cohort,
            double currentOptionPrice,
            List<MatchedHistoricalObservation> observations
    ) {
        Objects.requireNonNull(cohort, "cohort must not be null");
        Objects.requireNonNull(observations, "observations must not be null");

        int observationCount = observations.size();
        int uniqueInstrumentCount = (int) observations.stream().map(MatchedHistoricalObservation::instrumentId).distinct().count();
        int uniqueTradeDateCount = (int) observations.stream().map(MatchedHistoricalObservation::tradeDate).distinct().count();
        long nextCovered = observations.stream().filter(item -> item.nextDayReturnPct() != null).count();
        long expiryCovered = observations.stream().filter(item -> item.expiryReturnPct() != null).count();
        double nextCoveragePct = observationCount == 0 ? 0 : (nextCovered * 100.0d) / observationCount;
        double expiryCoveragePct = observationCount == 0 ? 0 : (expiryCovered * 100.0d) / observationCount;
        double concentrationPct = concentrationPct(observations);

        String confidenceLevel = confidenceLevel(observationCount, concentrationPct, expiryCoveragePct);
        String confidenceText = confidenceText(confidenceLevel);
        String concentrationLabel = concentrationLabel(concentrationPct);
        String concentrationText = concentrationText(concentrationLabel);
        String sparsityLabel = sparsityLabel(observationCount, expiryCoveragePct);
        String sparsityText = sparsityText(sparsityLabel);

        List<String> warnings = warnings(observationCount, concentrationPct, nextCoveragePct, expiryCoveragePct);
        List<String> reasons = comparabilityReasons(cohort);
        List<DiagnosticsSnapshot.HistoricalCaseMatch> cases = representativeCases(cohort, currentOptionPrice, observations);

        return new DiagnosticsSnapshot(
                cohort,
                observationCount,
                uniqueInstrumentCount,
                uniqueTradeDateCount,
                concentrationPct,
                nextCoveragePct,
                expiryCoveragePct,
                confidenceLevel,
                confidenceText,
                concentrationLabel,
                concentrationText,
                sparsityLabel,
                sparsityText,
                warnings,
                reasons,
                cases
        );
    }

    private static double concentrationPct(List<MatchedHistoricalObservation> observations) {
        if (observations.isEmpty()) {
            return 0;
        }
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (MatchedHistoricalObservation observation : observations) {
            counts.merge(observation.tradeDate(), 1, Integer::sum);
        }
        int maxDayCount = counts.values().stream().max(Integer::compareTo).orElse(0);
        return (maxDayCount * 100.0d) / observations.size();
    }

    private static String confidenceLevel(int observationCount, double concentrationPct, double expiryCoveragePct) {
        if (observationCount >= 250 && concentrationPct <= 12 && expiryCoveragePct >= 70) {
            return "Strong";
        }
        if (observationCount >= 80 && concentrationPct <= 22 && expiryCoveragePct >= 45) {
            return "Moderate";
        }
        return "Weak";
    }

    private static String confidenceText(String confidenceLevel) {
        return switch (confidenceLevel) {
            case "Strong" ->
                    "The conclusion is supported by broad historical evidence with acceptable concentration and horizon coverage.";
            case "Moderate" ->
                    "The conclusion is directionally supported, but sample concentration or horizon coverage still limits certainty.";
            default ->
                    "The conclusion is fragile because the matched evidence is thin, concentrated, or incomplete across horizons.";
        };
    }

    private static String concentrationLabel(double concentrationPct) {
        if (concentrationPct >= 25) {
            return "High";
        }
        if (concentrationPct >= 12) {
            return "Moderate";
        }
        return "Low";
    }

    private static String concentrationText(String label) {
        return switch (label) {
            case "High" ->
                    "A large share of matches cluster into a narrow slice of history, so one regime may dominate the read.";
            case "Moderate" ->
                    "Matches are somewhat clustered, which is usable but still deserves regime awareness.";
            default ->
                    "Matches are spread across historical dates and contracts, which supports a steadier research conclusion.";
        };
    }

    private static String sparsityLabel(int observationCount, double expiryCoveragePct) {
        if (observationCount < 50 || expiryCoveragePct < 35) {
            return "Elevated";
        }
        if (observationCount < 140 || expiryCoveragePct < 60) {
            return "Manageable";
        }
        return "Contained";
    }

    private static String sparsityText(String label) {
        return switch (label) {
            case "Elevated" ->
                    "Sparse matched history means any edge should be treated as exploratory rather than robust.";
            case "Manageable" ->
                    "There is enough evidence to study the setup, but not enough to ignore sample fragility.";
            default ->
                    "Matched history is broad enough that sparsity is not the primary concern.";
        };
    }

    private static List<String> warnings(int observationCount, double concentrationPct, double nextCoveragePct, double expiryCoveragePct) {
        List<String> warnings = new ArrayList<>();
        if (observationCount < 50) {
            warnings.add("Sparse cohort warning");
        } else if (observationCount < 140) {
            warnings.add("Moderate depth warning");
        } else {
            warnings.add("No major sparsity warning");
        }

        if (expiryCoveragePct < 35) {
            warnings.add("Expiry coverage thin");
        } else if (expiryCoveragePct < 60 || nextCoveragePct < 60) {
            warnings.add("Horizon coverage mixed");
        } else {
            warnings.add("Horizon coverage adequate");
        }

        if (concentrationPct >= 25) {
            warnings.add("Concentration risk elevated");
        } else if (concentrationPct >= 12) {
            warnings.add("Outcome read still regime-sensitive");
        } else {
            warnings.add("Regime concentration controlled");
        }
        return warnings;
    }

    private static List<String> comparabilityReasons(CanonicalCohortKey cohort) {
        return List.of(
                "Same underlying and option orientation",
                "Same canonical time bucket: time_bucket_15m=" + cohort.timeBucket15m(),
                "Same canonical moneyness bucket: " + cohort.moneynessBucket(),
                "Matched on the same historical context model used by the platform"
        );
    }

    private static List<DiagnosticsSnapshot.HistoricalCaseMatch> representativeCases(
            CanonicalCohortKey cohort,
            double currentOptionPrice,
            List<MatchedHistoricalObservation> observations
    ) {
        return observations.stream()
                .sorted(Comparator.comparingDouble(item -> Math.abs(item.entryPrice() - currentOptionPrice)))
                .limit(3)
                .map(item -> new DiagnosticsSnapshot.HistoricalCaseMatch(
                        item.tradeDate().toString(),
                        matchScore(item.entryPrice(), currentOptionPrice),
                        cohort.underlying() + " " + cohort.optionType() + ", TB" + cohort.timeBucket15m()
                                + ", " + (cohort.moneynessBucket() >= 0 ? "+" : "") + cohort.moneynessBucket() + " bucket",
                        item.entryPrice(),
                        item.nextDayReturnPct(),
                        item.expiryReturnPct(),
                        "Same canonical cohort with entry premium closest to the live scenario."
                ))
                .collect(Collectors.toList());
    }

    private static double matchScore(double entryPrice, double currentOptionPrice) {
        double denominator = Math.max(1.0d, currentOptionPrice);
        double distance = Math.abs(entryPrice - currentOptionPrice) / denominator;
        return Math.max(0.50d, Math.min(0.99d, 0.99d - distance));
    }
}
