package com.strategysquad.research;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Computes signed raw strategy metrics from matched historical scenarios.
 */
public final class StrategyAnalysisCalculator {
    private static final List<String> WINDOW_LABELS = List.of("5Y", "2Y", "1Y", "6M", "3M", "1M");

    private StrategyAnalysisCalculator() {
    }

    public static RawStrategyMetrics calculate(
            String mode,
            String orientation,
            String timeframe,
            double currentNetPremium,
            List<StrategyScenario> scenarios,
            TheoreticalBoundsInput boundsInput
    ) {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(orientation, "orientation must not be null");
        Objects.requireNonNull(timeframe, "timeframe must not be null");
        Objects.requireNonNull(scenarios, "scenarios must not be null");

        String premiumType = currentNetPremium >= 0 ? "NET_DEBIT" : "NET_CREDIT";
        RawStrategyMetrics.TheoreticalBounds bounds = computeBounds(boundsInput, currentNetPremium);

        if (scenarios.isEmpty()) {
            return new RawStrategyMetrics(
                    mode,
                    orientation,
                    timeframe,
                    "",
                    0,
                    currentNetPremium,
                    premiumType,
                    new RawStrategyMetrics.Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    emptyWindows(currentNetPremium),
                    new RawStrategyMetrics.ExpiryOutcome(0, 0, 0, 0, 0, 0, "No history", 0),
                    bounds,
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

        List<RawStrategyMetrics.PremiumWindow> windows = WINDOW_LABELS.stream()
                .map(label -> toWindow(label, currentNetPremium, filterRange(sorted, resolveRange(label, anchorDate)), orientation))
                .toList();

        List<RawStrategyMetrics.HistoricalCase> cases = sorted.stream()
                .sorted(Comparator.comparingDouble(item -> Math.abs(item.netEntryPremium() - currentNetPremium)))
                .limit(20)
                .map(item -> new RawStrategyMetrics.HistoricalCase(
                        item.tradeDate().toString(),
                        item.expiryDate().toString(),
                        item.netEntryPremium(),
                        item.netExpiryValue(),
                        item.selectedPnl(),
                        item.buyerPnl(),
                        item.sellerPnl()
                ))
                .toList();

        return new RawStrategyMetrics(
                mode,
                orientation,
                timeframe,
                anchorDate.toString(),
                selectedWindow.size(),
                currentNetPremium,
                premiumType,
                buildSummary(selectedWindow, currentNetPremium, orientation),
                windows,
                buildExpiryOutcome(selectedWindow, orientation),
                bounds,
                cases
        );
    }

    private static List<RawStrategyMetrics.PremiumWindow> emptyWindows(double currentNetPremium) {
        List<RawStrategyMetrics.PremiumWindow> windows = new ArrayList<>();
        for (String label : WINDOW_LABELS) {
            windows.add(new RawStrategyMetrics.PremiumWindow(label, 0, 0, 0, currentNetPremium, 0));
        }
        return windows;
    }

    private static RawStrategyMetrics.PremiumWindow toWindow(
            String label,
            double currentNetPremium,
            List<StrategyScenario> scenarios,
            String orientation
    ) {
        if (scenarios.isEmpty()) {
            return new RawStrategyMetrics.PremiumWindow(label, 0, 0, 0, currentNetPremium, 0);
        }
        double average = scenarios.stream().mapToDouble(StrategyScenario::netEntryPremium).average().orElse(0);
        double median = median(scenarios.stream().map(StrategyScenario::netEntryPremium).toList());
        int percentile = percentileRank(
                scenarios.stream().map(item -> comparablePremium(item.netEntryPremium(), orientation)).toList(),
                comparablePremium(currentNetPremium, orientation)
        );
        return new RawStrategyMetrics.PremiumWindow(
                label,
                average,
                median,
                percentile,
                currentNetPremium - average,
                scenarios.size()
        );
    }

    private static RawStrategyMetrics.Summary buildSummary(
            List<StrategyScenario> scenarios,
            double currentNetPremium,
            String orientation
    ) {
        double averageNet = scenarios.stream().mapToDouble(StrategyScenario::netEntryPremium).average().orElse(0);
        double medianNet = median(scenarios.stream().map(StrategyScenario::netEntryPremium).toList());
        double averageNetExpiry = scenarios.stream().mapToDouble(StrategyScenario::netExpiryValue).average().orElse(0);
        double averagePnl = scenarios.stream().mapToDouble(StrategyScenario::selectedPnl).average().orElse(0);
        double medianPnl = median(scenarios.stream().map(StrategyScenario::selectedPnl).toList());

        List<Double> wins = scenarios.stream().map(StrategyScenario::selectedPnl).filter(p -> p > 0).toList();
        List<Double> losses = scenarios.stream().map(StrategyScenario::selectedPnl).filter(p -> p <= 0).toList();
        double winRate = wins.size() * 100.0d / scenarios.size();
        double avgWin = wins.isEmpty() ? 0 : wins.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double avgLoss = losses.isEmpty() ? 0 : losses.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double payoffRatio = avgLoss == 0 ? 0 : Math.abs(avgWin / avgLoss);
        double expectancy = (winRate / 100.0d) * avgWin + ((100.0d - winRate) / 100.0d) * avgLoss;
        int percentile = percentileRank(
                scenarios.stream().map(item -> comparablePremium(item.netEntryPremium(), orientation)).toList(),
                comparablePremium(currentNetPremium, orientation)
        );

        return new RawStrategyMetrics.Summary(
                averageNet,
                medianNet,
                percentile,
                currentNetPremium - averageNet,
                averageNetExpiry,
                averagePnl,
                medianPnl,
                winRate,
                avgWin,
                avgLoss,
                payoffRatio,
                expectancy
        );
    }

    private static RawStrategyMetrics.ExpiryOutcome buildExpiryOutcome(
            List<StrategyScenario> scenarios,
            String orientation
    ) {
        double avgPayout = scenarios.stream().mapToDouble(StrategyScenario::netExpiryValue).average().orElse(0);
        double avgBuyerPnl = scenarios.stream().mapToDouble(StrategyScenario::buyerPnl).average().orElse(0);
        double avgSellerPnl = scenarios.stream().mapToDouble(StrategyScenario::sellerPnl).average().orElse(0);
        double sellerWinRate = scenarios.stream().filter(item -> item.sellerPnl() > 0).count() * 100.0d / scenarios.size();
        double buyerWinRate = scenarios.stream().filter(item -> item.buyerPnl() > 0).count() * 100.0d / scenarios.size();
        double tailLoss = percentileValue(scenarios.stream().map(StrategyScenario::selectedPnl).toList(), 10);
        double expectancy;
        if ("SELLER".equalsIgnoreCase(orientation)) {
            List<Double> wins = scenarios.stream().map(StrategyScenario::sellerPnl).filter(p -> p > 0).toList();
            List<Double> losses = scenarios.stream().map(StrategyScenario::sellerPnl).filter(p -> p <= 0).toList();
            double avgWin = wins.isEmpty() ? 0 : wins.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double avgLoss = losses.isEmpty() ? 0 : losses.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            expectancy = (sellerWinRate / 100.0d) * avgWin + ((100.0d - sellerWinRate) / 100.0d) * avgLoss;
        } else {
            List<Double> wins = scenarios.stream().map(StrategyScenario::buyerPnl).filter(p -> p > 0).toList();
            List<Double> losses = scenarios.stream().map(StrategyScenario::buyerPnl).filter(p -> p <= 0).toList();
            double avgWin = wins.isEmpty() ? 0 : wins.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double avgLoss = losses.isEmpty() ? 0 : losses.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            expectancy = (buyerWinRate / 100.0d) * avgWin + ((100.0d - buyerWinRate) / 100.0d) * avgLoss;
        }
        String downsideProfile = "%s-side tail %.2f pts".formatted(
                orientation.toLowerCase(Locale.ROOT),
                tailLoss
        );
        return new RawStrategyMetrics.ExpiryOutcome(
                avgPayout,
                avgSellerPnl,
                avgBuyerPnl,
                sellerWinRate,
                buyerWinRate,
                tailLoss,
                downsideProfile,
                expectancy
        );
    }

    static RawStrategyMetrics.TheoreticalBounds computeBounds(
            TheoreticalBoundsInput input,
            double currentNetPremium
    ) {
        if (input == null) {
            return new RawStrategyMetrics.TheoreticalBounds(null, null, "Not derivable", 0, 0);
        }
        double historicalBest = input.historicalBestPnl();
        double historicalWorst = input.historicalWorstPnl();
        return switch (input.mode()) {
            case "SINGLE_OPTION" -> {
                double absPremium = Math.abs(currentNetPremium);
                if ("LONG".equalsIgnoreCase(input.firstLegSide())) {
                    yield new RawStrategyMetrics.TheoreticalBounds(null, -absPremium, "Unlimited upside", historicalBest, historicalWorst);
                }
                yield new RawStrategyMetrics.TheoreticalBounds(absPremium, null, "Unlimited risk", historicalBest, historicalWorst);
            }
            case "LONG_STRADDLE", "LONG_STRANGLE" -> {
                double totalDebit = Math.abs(currentNetPremium);
                yield new RawStrategyMetrics.TheoreticalBounds(null, -totalDebit, "Unlimited upside", historicalBest, historicalWorst);
            }
            case "SHORT_STRADDLE", "SHORT_STRANGLE" -> {
                double totalCredit = Math.abs(currentNetPremium);
                yield new RawStrategyMetrics.TheoreticalBounds(totalCredit, null, "Unlimited risk", historicalBest, historicalWorst);
            }
            case "BULL_CALL_SPREAD", "BEAR_PUT_SPREAD" -> {
                double spreadWidth = input.spreadWidth();
                double netDebit = Math.abs(currentNetPremium);
                yield new RawStrategyMetrics.TheoreticalBounds(spreadWidth - netDebit, -netDebit, "Defined risk", historicalBest, historicalWorst);
            }
            case "IRON_CONDOR", "IRON_BUTTERFLY" -> {
                double netCredit = Math.abs(currentNetPremium);
                double maxSpreadWidth = input.spreadWidth();
                yield new RawStrategyMetrics.TheoreticalBounds(netCredit, -(maxSpreadWidth - netCredit), "Defined risk", historicalBest, historicalWorst);
            }
            default -> new RawStrategyMetrics.TheoreticalBounds(null, null, "Not derivable", historicalBest, historicalWorst);
        };
    }

    private static List<StrategyScenario> filterRange(List<StrategyScenario> scenarios, DateRange range) {
        return scenarios.stream()
                .filter(item -> !item.tradeDate().isBefore(range.from()) && !item.tradeDate().isAfter(range.to()))
                .toList();
    }

    static DateRange resolveRange(String timeframe, LocalDate anchorDate) {
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

    static int percentileRank(List<Double> values, double value) {
        if (values.isEmpty()) {
            return 0;
        }
        long lessOrEqual = values.stream().filter(item -> item <= value).count();
        return (int) Math.round(lessOrEqual * 100.0d / values.size());
    }

    static double percentileValue(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.floor((percentile / 100.0d) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    static double median(List<Double> values) {
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

    private static double comparablePremium(double netPremium, String orientation) {
        return "SELLER".equalsIgnoreCase(orientation) ? -netPremium : netPremium;
    }

    public record StrategyScenario(
            LocalDate tradeDate,
            LocalDate expiryDate,
            double netEntryPremium,
            double netExpiryValue,
            double selectedPnl,
            double buyerPnl,
            double sellerPnl
    ) {
    }

    public record TheoreticalBoundsInput(
            String mode,
            String firstLegSide,
            double spreadWidth,
            double historicalBestPnl,
            double historicalWorstPnl
    ) {
    }

    record DateRange(LocalDate from, LocalDate to) {
    }
}
