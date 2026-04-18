package com.strategysquad.research;

import java.time.Instant;

/**
 * Persisted scenario analysis artifact stored in the research workspace.
 */
public record ResearchScenarioSnapshot(
        String snapshotId,
        String scenarioId,
        String parentScenarioId,
        String collectionId,
        String collectionName,
        String title,
        String mode,
        Instant savedAt,
        ScenarioInputs inputs,
        ScenarioAnalysis analysis
) {
    public record ScenarioInputs(
            String underlying,
            String optionType,
            String expiryType,
            int dte,
            double spot,
            double strike,
            double distancePoints,
            double optionPrice,
            String activityBias,
            String researchNote
    ) {
    }

    public record ScenarioAnalysis(
            String currentScenarioName,
            String fairnessLabel,
            int fairnessPercentile,
            String decisionPosture,
            String decisionClass,
            String trustLevel,
            String trustClass,
            long sampleSize,
            String cohortKey,
            String dteBand,
            String recommendationBucket,
            String recommendationText,
            String cohortStrength,
            String fairValueJson,
            String forwardOutcomeJson,
            String diagnosticsJson
    ) {
    }
}
