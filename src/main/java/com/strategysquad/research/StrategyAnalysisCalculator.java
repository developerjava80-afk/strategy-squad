package com.strategysquad.research;

import java.util.List;
import java.util.Objects;

/**
 * Computes compact short-premium strategy metrics from historical scenarios.
 */
public final class StrategyAnalysisCalculator {
    private StrategyAnalysisCalculator() {
    }

    public static StrategyAnalysisSnapshot calculate(
            String mode,
            String timeframe,
            List<StrategyScenario> scenarios
    ) {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(timeframe, "timeframe must not be null");
        Objects.requireNonNull(scenarios, "scenarios must not be null");

        if (scenarios.isEmpty()) {
            return new StrategyAnalysisSnapshot(mode, timeframe, 0, 0, 0, 0, 0, 0, 0);
        }

        double avgPremium = scenarios.stream().mapToDouble(StrategyScenario::entryPremiumCollected).average().orElse(0);
        double avgExpiryValue = scenarios.stream().mapToDouble(StrategyScenario::expiryValue).average().orElse(0);
        double avgPnl = scenarios.stream().mapToDouble(StrategyScenario::expiryPnl).average().orElse(0);
        double winRate = scenarios.stream().filter(item -> item.expiryPnl() > 0).count() * 100.0d / scenarios.size();
        double maxGain = scenarios.stream().mapToDouble(StrategyScenario::expiryPnl).max().orElse(0);
        double maxLoss = scenarios.stream().mapToDouble(StrategyScenario::expiryPnl).min().orElse(0);

        return new StrategyAnalysisSnapshot(
                mode,
                timeframe,
                scenarios.size(),
                avgPremium,
                avgExpiryValue,
                avgPnl,
                winRate,
                maxGain,
                maxLoss
        );
    }

    public record StrategyScenario(
            double entryPremiumCollected,
            double expiryValue,
            double expiryPnl
    ) {
    }
}
