package com.strategysquad.research;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Computes compact multi-leg strategy metrics from matched historical scenarios.
 */
public final class StrategyAnalysisCalculator {
    private static final List<String> WINDOW_LABELS = List.of("5Y", "2Y", "1Y", "6M", "3M", "1M");

    private StrategyAnalysisCalculator() {
    }

    public static StrategyAnalysisSnapshot calculate(
            String mode,
            String orientation,
            String timeframe,
            double currentTotalPremium,
            List<StrategyScenario> scenarios,
            List<StrategyAnalysisSnapshot.StrategyRecommendation> rankedRecommendations
    ) {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(orientation, "orientation must not be null");
        Objects.requireNonNull(timeframe, "timeframe must not be null");
        Objects.requireNonNull(scenarios, "scenarios must not be null");
        Objects.requireNonNull(rankedRecommendations, "rankedRecommendations must not be null");

        if (scenarios.isEmpty()) {
            StrategyAnalysisSnapshot.Summary emptySummary = new StrategyAnalysisSnapshot.Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            StrategyAnalysisSnapshot.ExpiryOutcome emptyOutcome = new StrategyAnalysisSnapshot.ExpiryOutcome(0, 0, 0, 0, 0, "No history");
            StrategyAnalysisSnapshot.RecommendationSummary emptyRecommendation = new StrategyAnalysisSnapshot.RecommendationSummary(
                    emptyRecommendation(mode, orientation, "No candidate strategy has usable history."),
                    emptyRecommendation("N/A", "N/A", "No alternative strategy has usable history."),
                    emptyRecommendation("N/A", "N/A", "No avoid signal because no candidates matched.")
            );
            return new StrategyAnalysisSnapshot(
                    mode,
                    orientation,
                    timeframe,
                    0,
                    currentTotalPremium,
                    emptySummary,
                    emptyWindows(currentTotalPremium),
                    emptyOutcome,
                    emptyRecommendation,
                    List.of()
            );
        }

        List<StrategyScenario> sorted = scenarios.stream()
                .sorted(Comparator.comparing(StrategyScenario::tradeDate))
                .toList();
        LocalDate anchorDate = sorted.get(sorted.size() - 1).tradeDate();
        DateRange selectedRange = resolveRange(timeframe, anchorDate);
        List<StrategyScenario> selectedWindow = filterRange(sorted, selectedRange);
        if (selectedWindow.isEmpty()) {
            selectedWindow = sorted;
        }

        List<StrategyAnalysisSnapshot.PremiumWindow> windows = WINDOW_LABELS.stream()
                .map(label -> toWindow(label, currentTotalPremium, filterRange(sorted, resolveRange(label, anchorDate))))
                .toList();

        StrategyAnalysisSnapshot.Summary summary = buildSummary(selectedWindow, currentTotalPremium);
        StrategyAnalysisSnapshot.ExpiryOutcome expiryOutcome = buildExpiryOutcome(selectedWindow, orientation);
        StrategyAnalysisSnapshot.RecommendationSummary recommendation = buildRecommendationSummary(rankedRecommendations);
        List<StrategyAnalysisSnapshot.MatchedCase> cases = sorted.stream()
                .sorted(Comparator.comparingDouble(item -> Math.abs(item.totalEntryPremium() - currentTotalPremium)))
                .limit(20)
                .map(item -> new StrategyAnalysisSnapshot.MatchedCase(
                        item.tradeDate().toString(),
                        item.expiryDate().toString(),
                        item.totalEntryPremium(),
                        item.expiryValue(),
                        item.selectedPnl(),
                        item.buyerPnl(),
                        item.sellerPnl()
                ))
                .toList();

        return new StrategyAnalysisSnapshot(
                mode,
                orientation,
                timeframe,
                selectedWindow.size(),
                currentTotalPremium,
                summary,
                windows,
                expiryOutcome,
                recommendation,
                cases
        );
    }

    private static StrategyAnalysisSnapshot.StrategyRecommendation emptyRecommendation(String mode, String orientation, String reason) {
        return new StrategyAnalysisSnapshot.StrategyRecommendation(mode, orientation, 0, 0, 0, 0, 0, 0, reason);
    }

    private static List<StrategyAnalysisSnapshot.PremiumWindow> emptyWindows(double currentTotalPremium) {
        List<StrategyAnalysisSnapshot.PremiumWindow> windows = new ArrayList<>();
        for (String label : WINDOW_LABELS) {
            windows.add(new StrategyAnalysisSnapshot.PremiumWindow(label, 0, 0, currentTotalPremium, 0));
        }
        return windows;
    }

    private static StrategyAnalysisSnapshot.RecommendationSummary buildRecommendationSummary(
            List<StrategyAnalysisSnapshot.StrategyRecommendation> rankedRecommendations
    ) {
        if (rankedRecommendations.isEmpty()) {
            return new StrategyAnalysisSnapshot.RecommendationSummary(
                    emptyRecommendation("N/A", "N/A", "No candidate strategy has usable history."),
                    emptyRecommendation("N/A", "N/A", "No alternative strategy has usable history."),
                    emptyRecommendation("N/A", "N/A", "No avoid signal because no candidates matched.")
            );
        }
        StrategyAnalysisSnapshot.StrategyRecommendation preferred = rankedRecommendations.get(0);
        StrategyAnalysisSnapshot.StrategyRecommendation alternative = rankedRecommendations.size() > 1
                ? rankedRecommendations.get(1)
                : emptyRecommendation(preferred.mode(), preferred.orientation(), "No second strategy exceeded the history threshold.");
        StrategyAnalysisSnapshot.StrategyRecommendation avoid = rankedRecommendations.size() > 2
                ? rankedRecommendations.get(rankedRecommendations.size() - 1)
                : emptyRecommendation(preferred.mode(), preferred.orientation(), "No avoid signal because only one strategy matched.");
        return new StrategyAnalysisSnapshot.RecommendationSummary(preferred, alternative, avoid);
    }

    private static StrategyAnalysisSnapshot.PremiumWindow toWindow(
            String label,
            double currentTotalPremium,
            List<StrategyScenario> scenarios
    ) {
        if (scenarios.isEmpty()) {
            return new StrategyAnalysisSnapshot.PremiumWindow(label, 0, 0, currentTotalPremium, 0);
        }
        double average = scenarios.stream().mapToDouble(StrategyScenario::totalEntryPremium).average().orElse(0);
        double median = median(scenarios.stream().map(StrategyScenario::totalEntryPremium).toList());
        return new StrategyAnalysisSnapshot.PremiumWindow(
                label,
                average,
                median,
                currentTotalPremium - average,
                scenarios.size()
        );
    }

    private static StrategyAnalysisSnapshot.Summary buildSummary(
            List<StrategyScenario> scenarios,
            double currentTotalPremium
    ) {
        double averageEntry = scenarios.stream().mapToDouble(StrategyScenario::totalEntryPremium).average().orElse(0);
        double medianEntry = median(scenarios.stream().map(StrategyScenario::totalEntryPremium).toList());
        double averageExpiryValue = scenarios.stream().mapToDouble(StrategyScenario::expiryValue).average().orElse(0);
        double averagePnl = scenarios.stream().mapToDouble(StrategyScenario::selectedPnl).average().orElse(0);
        double medianPnl = median(scenarios.stream().map(StrategyScenario::selectedPnl).toList());
        double winRate = scenarios.stream().filter(item -> item.selectedPnl() > 0).count() * 100.0d / scenarios.size();
        double bestCase = scenarios.stream().mapToDouble(StrategyScenario::selectedPnl).max().orElse(0);
        double worstCase = scenarios.stream().mapToDouble(StrategyScenario::selectedPnl).min().orElse(0);
        int percentile = percentileRank(scenarios.stream().map(StrategyScenario::totalEntryPremium).toList(), currentTotalPremium);

        return new StrategyAnalysisSnapshot.Summary(
                averageEntry,
                medianEntry,
                percentile,
                currentTotalPremium - averageEntry,
                averageExpiryValue,
                averagePnl,
                medianPnl,
                winRate,
                bestCase,
                worstCase
        );
    }

    private static StrategyAnalysisSnapshot.ExpiryOutcome buildExpiryOutcome(
            List<StrategyScenario> scenarios,
            String orientation
    ) {
        double avgPayout = scenarios.stream().mapToDouble(StrategyScenario::expiryValue).average().orElse(0);
        double avgBuyerPnl = scenarios.stream().mapToDouble(StrategyScenario::buyerPnl).average().orElse(0);
        double avgSellerPnl = scenarios.stream().mapToDouble(StrategyScenario::sellerPnl).average().orElse(0);
        double selectedWinRate = scenarios.stream().filter(item -> item.selectedPnl() > 0).count() * 100.0d / scenarios.size();
        double tailLoss = percentileValue(scenarios.stream().map(StrategyScenario::selectedPnl).toList(), 10);
        String downsideProfile = "%s-side tail %.2f".formatted(
                orientation.toLowerCase(Locale.ROOT),
                tailLoss
        );
        return new StrategyAnalysisSnapshot.ExpiryOutcome(
                avgPayout,
                avgSellerPnl,
                avgBuyerPnl,
                selectedWinRate,
                tailLoss,
                downsideProfile
        );
    }

    private static List<StrategyScenario> filterRange(List<StrategyScenario> scenarios, DateRange range) {
        return scenarios.stream()
                .filter(item -> !item.tradeDate().isBefore(range.from()) && !item.tradeDate().isAfter(range.to()))
                .toList();
    }

    private static DateRange resolveRange(String timeframe, LocalDate anchorDate) {
        String normalized = timeframe == null || timeframe.isBlank() ? "1Y" : timeframe.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "5Y" -> new DateRange(anchorDate.minusYears(5).plusDays(1), anchorDate);
            case "2Y" -> new DateRange(anchorDate.minusYears(2).plusDays(1), anchorDate);
            case "6M" -> new DateRange(anchorDate.minusMonths(6).plusDays(1), anchorDate);
            case "3M" -> new DateRange(anchorDate.minusMonths(3).plusDays(1), anchorDate);
            case "1M" -> new DateRange(anchorDate.minusMonths(1).plusDays(1), anchorDate);
            default -> new DateRange(anchorDate.minusYears(1).plusDays(1), anchorDate);
        };
    }

    private static int percentileRank(List<Double> values, double value) {
        if (values.isEmpty()) {
            return 0;
        }
        long lessOrEqual = values.stream().filter(item -> item <= value).count();
        return (int) Math.round(lessOrEqual * 100.0d / values.size());
    }

    private static double percentileValue(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.floor((percentile / 100.0d) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if ((sorted.size() % 2) == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0d;
        }
        return sorted.get(mid);
    }

    public record StrategyScenario(
            LocalDate tradeDate,
            LocalDate expiryDate,
            double totalEntryPremium,
            double expiryValue,
            double selectedPnl,
            double buyerPnl,
            double sellerPnl
    ) {
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
