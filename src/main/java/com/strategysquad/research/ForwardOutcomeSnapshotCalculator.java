package com.strategysquad.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Computes forward outcome summaries from matched historical cohort observations.
 */
public final class ForwardOutcomeSnapshotCalculator {
    private ForwardOutcomeSnapshotCalculator() {
    }

    public static ForwardOutcomeSnapshot calculate(
            CanonicalCohortKey cohort,
            List<Double> nextDayReturnsPct,
            List<Double> expiryReturnsPct
    ) {
        Objects.requireNonNull(cohort, "cohort must not be null");
        Objects.requireNonNull(nextDayReturnsPct, "nextDayReturnsPct must not be null");
        Objects.requireNonNull(expiryReturnsPct, "expiryReturnsPct must not be null");

        ForwardOutcomeSnapshot.OutcomeHorizon nextDay = summarize(nextDayReturnsPct);
        ForwardOutcomeSnapshot.OutcomeHorizon expiry = summarize(expiryReturnsPct);
        String label = opportunityLabel(nextDay, expiry, nextDayReturnsPct.size(), expiryReturnsPct.size());

        return new ForwardOutcomeSnapshot(
                cohort,
                Math.max(nextDayReturnsPct.size(), expiryReturnsPct.size()),
                nextDayReturnsPct.size(),
                expiryReturnsPct.size(),
                nextDay,
                expiry,
                label,
                opportunityInterpretation(label, nextDay, expiry)
        );
    }

    private static ForwardOutcomeSnapshot.OutcomeHorizon summarize(List<Double> returnsPct) {
        if (returnsPct.isEmpty()) {
            return new ForwardOutcomeSnapshot.OutcomeHorizon(0, 0, 0, 0, 0);
        }

        List<Double> sorted = new ArrayList<>(returnsPct);
        sorted.sort(Comparator.naturalOrder());
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = FairValueSnapshotCalculator.quantile(sorted, 0.50);

        long expandCount = sorted.stream().filter(value -> value > 0.25d).count();
        long decayCount = sorted.stream().filter(value -> value < -0.25d).count();
        long flatCount = sorted.size() - expandCount - decayCount;
        double total = sorted.size();

        return new ForwardOutcomeSnapshot.OutcomeHorizon(
                median,
                mean,
                (expandCount * 100.0d) / total,
                (flatCount * 100.0d) / total,
                (decayCount * 100.0d) / total
        );
    }

    static String opportunityLabel(
            ForwardOutcomeSnapshot.OutcomeHorizon nextDay,
            ForwardOutcomeSnapshot.OutcomeHorizon expiry,
            int nextDayCount,
            int expiryCount
    ) {
        if (nextDayCount < 40 || expiryCount < 40) {
            return "No clear edge";
        }

        double longScore = (nextDay.expandProbabilityPct() - nextDay.decayProbabilityPct())
                + ((expiry.expandProbabilityPct() - expiry.decayProbabilityPct()) * 0.6d)
                + (nextDay.medianReturnPct() * 0.8d)
                + (expiry.medianReturnPct() * 0.4d);
        double shortScore = (nextDay.decayProbabilityPct() - nextDay.expandProbabilityPct())
                + ((expiry.decayProbabilityPct() - expiry.expandProbabilityPct()) * 0.9d)
                + ((-nextDay.medianReturnPct()) * 0.5d)
                + ((-expiry.medianReturnPct()) * 0.8d);

        if (longScore >= shortScore + 8.0d) {
            return "Long premium favored";
        }
        if (shortScore >= longScore + 8.0d) {
            return "Short premium favored";
        }
        return "No clear edge";
    }

    static String opportunityInterpretation(
            String label,
            ForwardOutcomeSnapshot.OutcomeHorizon nextDay,
            ForwardOutcomeSnapshot.OutcomeHorizon expiry
    ) {
        return switch (label) {
            case "Long premium favored" ->
                    "Historical matches expanded more often than they decayed, and the forward path was supportive for premium ownership.";
            case "Short premium favored" ->
                    "Historical matches decayed more often than they expanded, especially into expiry, so premium selling was the more natural posture.";
            default ->
                    "Historical matches did not separate cleanly enough between expansion and decay to support a strong premium posture.";
        };
    }
}
