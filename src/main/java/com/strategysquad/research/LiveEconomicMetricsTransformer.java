package com.strategysquad.research;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Converts live structure state plus canonical historical comparison into
 * trader-readable mark-to-market metrics using scenario quantities.
 */
public final class LiveEconomicMetricsTransformer {
    private static final long LIVE_STALE_SECONDS = 30L;

    private LiveEconomicMetricsTransformer() {
    }

    public static LiveEconomicMetrics transform(
            StrategyStructureDefinition definition,
            LiveMarketService.LiveStructureSnapshot structure,
            EconomicMetrics historicalComparison
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(structure, "structure must not be null");

        boolean seller = EconomicMetricsTransformer.isSeller(definition.orientation());
        double entryPremiumPoints = economicEntryPremium(definition, seller);
        double livePremiumPoints = structure.economicNetPremiumPoints() == null
                ? 0.0d
                : structure.economicNetPremiumPoints().doubleValue();
        double liveVsEntryPoints = livePremiumPoints - entryPremiumPoints;
        double liveVsEntryPct = entryPremiumPoints == 0.0d
                ? 0.0d
                : (liveVsEntryPoints / entryPremiumPoints) * 100.0d;
        double livePnlPoints = seller
                ? entryPremiumPoints - livePremiumPoints
                : livePremiumPoints - entryPremiumPoints;
        int lotSize = Math.max(1, structure.effectiveLotSize());
        double livePnlRupees = livePnlPoints * lotSize;

        LiveEconomicMetrics.PremiumMetrics premium = new LiveEconomicMetrics.PremiumMetrics(
                seller ? "Tracked entry credit" : "Tracked entry debit",
                structure.premiumLabel(),
                entryPremiumPoints,
                livePremiumPoints,
                liveVsEntryPoints,
                liveVsEntryPct
        );

        LiveEconomicMetrics.PnlMetrics pnl = new LiveEconomicMetrics.PnlMetrics(
                seller ? "Seller" : "Buyer",
                livePnlPoints,
                livePnlRupees,
                "Live one-lot mark-to-market",
                markState(livePnlPoints)
        );

        LiveEconomicMetrics.ConfidenceMetrics confidence = confidenceMetrics(
                structure,
                historicalComparison,
                seller,
                livePnlPoints,
                entryPremiumPoints
        );

        return new LiveEconomicMetrics(
                new LiveEconomicMetrics.LotMetrics(lotSize, "Base NSE lot size"),
                premium,
                pnl,
                confidence
        );
    }

    private static double economicEntryPremium(StrategyStructureDefinition definition, boolean seller) {
        double signedNet = 0.0d;
        for (StrategyStructureDefinition.StrategyLeg leg : definition.legs()) {
            double signed = "SHORT".equalsIgnoreCase(leg.side())
                    ? -leg.entryPrice().doubleValue()
                    : leg.entryPrice().doubleValue();
            signedNet += signed * definition.lotCount(leg);
        }
        return EconomicMetricsTransformer.toEconomicPremium(signedNet, seller);
    }

    private static LiveEconomicMetrics.ConfidenceMetrics confidenceMetrics(
            LiveMarketService.LiveStructureSnapshot structure,
            EconomicMetrics historicalComparison,
            boolean seller,
            double livePnlPoints,
            double entryPremiumPoints
    ) {
        if (historicalComparison == null) {
            boolean reliable = isFeedReliable(structure);
            return new LiveEconomicMetrics.ConfidenceMetrics(
                    "Unavailable",
                    reliable ? "Live-only" : "Low-confidence live",
                    reliable,
                    reliable ? "Historical comparison unavailable" : "Feed reliability downgrade",
                    "Live mark is available, but canonical historical comparison is not ready for this structure."
            );
        }

        int score = baseConfidenceScore(historicalComparison.observation().evidenceStrength());
        if (historicalComparison.observation().lowSampleDowngrade()) {
            score = Math.min(score, 1);
        }

        boolean reliable = isFeedReliable(structure);
        String adjustmentLabel = "Live move neutral";
        double pnlPctOfEntry = entryPremiumPoints == 0.0d ? 0.0d : (livePnlPoints / entryPremiumPoints) * 100.0d;

        if (!reliable) {
            score = 0;
            adjustmentLabel = "Feed reliability downgrade";
        } else if (Math.abs(pnlPctOfEntry) >= 15.0d) {
            if (livePnlPoints > 0.0d) {
                score += 1;
                adjustmentLabel = seller
                        ? "Credit contracting in seller favour"
                        : "Premium expanding in buyer favour";
            } else {
                score -= 1;
                adjustmentLabel = seller
                        ? "Credit expanding against seller"
                        : "Premium decaying against buyer";
            }
        }

        score = Math.max(0, Math.min(3, score));
        return new LiveEconomicMetrics.ConfidenceMetrics(
                historicalComparison.observation().evidenceStrength(),
                effectiveConfidenceLabel(score),
                reliable,
                adjustmentLabel,
                confidenceDetail(
                        historicalComparison.observation().evidenceStrength(),
                        historicalComparison.observation().lowSampleWarning(),
                        reliable,
                        adjustmentLabel,
                        livePnlPoints,
                        pnlPctOfEntry
                )
        );
    }

    private static boolean isFeedReliable(LiveMarketService.LiveStructureSnapshot structure) {
        if (structure.partialData()) {
            return false;
        }
        Instant asOf = structure.asOf();
        return asOf != null && ChronoUnit.SECONDS.between(asOf, Instant.now()) < LIVE_STALE_SECONDS;
    }

    private static int baseConfidenceScore(String evidenceStrength) {
        if ("Strong".equalsIgnoreCase(evidenceStrength)) {
            return 3;
        }
        if ("Adequate".equalsIgnoreCase(evidenceStrength)) {
            return 2;
        }
        if ("Mixed".equalsIgnoreCase(evidenceStrength)) {
            return 1;
        }
        return 0;
    }

    private static String effectiveConfidenceLabel(int score) {
        return switch (score) {
            case 3 -> "Strong";
            case 2 -> "Adequate";
            case 1 -> "Mixed";
            default -> "Low-confidence read";
        };
    }

    private static String confidenceDetail(
            String baseEvidenceStrength,
            String lowSampleWarning,
            boolean reliable,
            String adjustmentLabel,
            double livePnlPoints,
            double pnlPctOfEntry
    ) {
        String sampleDetail = lowSampleWarning == null || lowSampleWarning.isBlank()
                ? "Historical evidence starts from %s sample strength.".formatted(baseEvidenceStrength)
                : lowSampleWarning;
        String feedDetail = reliable
                ? "Live feed is complete and current."
                : "Live feed is partial or stale, so confidence is downgraded.";
        return "%s %s %s MTM %+.2f pts (%+.1f%% of entry).".formatted(
                sampleDetail,
                feedDetail,
                adjustmentLabel,
                livePnlPoints,
                pnlPctOfEntry
        );
    }

    private static String markState(double livePnlPoints) {
        if (livePnlPoints > 0.0d) {
            return "Favourable live mark";
        }
        if (livePnlPoints < 0.0d) {
            return "Adverse live mark";
        }
        return "Flat live mark";
    }
}
