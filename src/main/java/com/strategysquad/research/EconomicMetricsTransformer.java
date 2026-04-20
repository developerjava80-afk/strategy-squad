package com.strategysquad.research;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonical raw-to-economic transformation layer. All UI, recommendation, export, and report
 * surfaces must consume this model rather than raw signed strategy metrics.
 */
public final class EconomicMetricsTransformer {
    private EconomicMetricsTransformer() {
    }

    public static EconomicMetrics transform(
            RawStrategyMetrics raw,
            EconomicMetrics.RecommendationContext recommendation
    ) {
        Objects.requireNonNull(raw, "raw must not be null");
        Objects.requireNonNull(recommendation, "recommendation must not be null");

        boolean seller = isSeller(raw.orientation());
        SampleStatus sampleStatus = sampleStatus(raw.observationCount());
        int rawPricePercentile = toRawPricePercentile(raw.snapshot().currentNetPremiumPercentile());
        int economicPercentile = toEconomicPercentile(rawPricePercentile, seller);

        double currentPremium = toEconomicPremium(raw.currentNetPremium(), seller);
        double averageEntry = toEconomicPremium(raw.snapshot().averageNetPremium(), seller);
        double medianEntry = toEconomicPremium(raw.snapshot().medianNetPremium(), seller);
        double currentVsAverage = currentPremium - averageEntry;
        double currentVsAveragePct = averageEntry == 0 ? 0 : (currentVsAverage / averageEntry) * 100.0d;

        EconomicMetrics.ObservationMetrics observation = new EconomicMetrics.ObservationMetrics(
                raw.anchorDate(),
                raw.observationCount(),
                sampleStatus.evidenceStrength(),
                sampleStatus.warning(),
                sampleStatus.lowSampleDowngrade()
        );

        EconomicMetrics.PremiumMetrics premium = new EconomicMetrics.PremiumMetrics(
                seller ? "Current net credit" : "Current net debit",
                seller ? "Average net credit" : "Average net debit",
                seller ? "Median net credit" : "Median net debit",
                currentPremium,
                averageEntry,
                medianEntry,
                rawPricePercentile,
                economicPercentile,
                !sampleStatus.lowSampleDowngrade(),
                currentVsAverage,
                currentVsAveragePct,
                priceConditionLabel(rawPricePercentile),
                attractivenessLabel(economicPercentile, seller)
        );

        double selectedSideAveragePnl = seller ? raw.expiryOutcome().averageSellerPnl() : raw.expiryOutcome().averageBuyerPnl();
        double oppositeSideAveragePnl = seller ? raw.expiryOutcome().averageBuyerPnl() : raw.expiryOutcome().averageSellerPnl();
        double selectedSideWinRate = seller ? raw.expiryOutcome().sellerWinRatePct() : raw.expiryOutcome().buyerWinRatePct();
        double oppositeSideWinRate = seller ? raw.expiryOutcome().buyerWinRatePct() : raw.expiryOutcome().sellerWinRatePct();

        EconomicMetrics.ExpiryMetrics expiry = new EconomicMetrics.ExpiryMetrics(
                seller ? "Average expiry closeout" : "Average expiry value",
                toEconomicPremium(raw.snapshot().averageNetExpiryValue(), seller),
                toEconomicPremium(raw.expiryOutcome().averageNetExpiryPayout(), seller),
                selectedSideAveragePnl,
                oppositeSideAveragePnl,
                selectedSideWinRate,
                oppositeSideWinRate,
                seller ? "Seller" : "Buyer"
        );

        EconomicMetrics.PnlMetrics pnl = new EconomicMetrics.PnlMetrics(
                raw.snapshot().averagePnl(),
                raw.snapshot().medianPnl(),
                raw.snapshot().avgWinPnl(),
                raw.snapshot().avgLossPnl(),
                raw.snapshot().payoffRatio(),
                raw.snapshot().expectancy(),
                expectancyLabel(raw.snapshot().expectancy()),
                seller ? "Seller" : "Buyer"
        );

        EconomicMetrics.RiskMetrics risk = new EconomicMetrics.RiskMetrics(
                raw.bounds().theoreticalMaxProfit(),
                raw.bounds().theoreticalMaxLoss() == null ? null : Math.abs(raw.bounds().theoreticalMaxLoss()),
                raw.bounds().boundsLabel(),
                raw.expiryOutcome().tailLossP10(),
                raw.expiryOutcome().downsideProfile(),
                sampleStatus.warning(),
                raw.bounds().historicalBestPnl(),
                raw.bounds().historicalWorstPnl(),
                "Raw historical sample extreme"
        );

        EconomicMetrics.InsightMetrics insight = buildInsight(
                raw.orientation(),
                premium,
                pnl,
                risk,
                sampleStatus
        );

        EconomicMetrics.TimeframeTrendMetrics timeframeTrend = new EconomicMetrics.TimeframeTrendMetrics(
                currentPremium,
                raw.premiumWindows().stream()
                        .map(item -> {
                            int windowRawPercentile = toRawPricePercentile(item.currentNetPremiumPercentile());
                            return new EconomicMetrics.TimeframeWindow(
                                    item.label(),
                                    toEconomicPremium(item.averageNetPremium(), seller),
                                    toEconomicPremium(item.medianNetPremium(), seller),
                                    windowRawPercentile,
                                    toEconomicPercentile(windowRawPercentile, seller),
                                    item.observationCount() >= 25,
                                    currentPremium - toEconomicPremium(item.averageNetPremium(), seller),
                                    item.observationCount()
                            );
                        })
                        .toList()
        );

        List<EconomicMetrics.HistoricalCase> historicalCases = raw.historicalCases().stream()
                .map(item -> new EconomicMetrics.HistoricalCase(
                        item.tradeDate(),
                        item.expiryDate(),
                        toEconomicPremium(item.netEntryPremium(), seller),
                        toEconomicPremium(item.netExpiryValue(), seller),
                        item.selectedPnl(),
                        item.buyerPnl(),
                        item.sellerPnl(),
                        "Raw historical sample extreme"
                ))
                .toList();

        return new EconomicMetrics(
                raw.mode(),
                raw.orientation(),
                raw.timeframe(),
                observation,
                premium,
                expiry,
                pnl,
                risk,
                insight,
                timeframeTrend,
                recommendation,
                historicalCases
        );
    }

    public static EconomicMetrics.RecommendationCandidate toRecommendationCandidate(
            String mode,
            String orientation,
            RawStrategyMetrics raw
    ) {
        EconomicMetrics transformed = transform(raw, emptyRecommendationContext());
        SampleStatus sampleStatus = sampleStatus(raw.observationCount());
        double downside = Math.abs(transformed.risk().tailLossP10Points());
        String verdict = recommendationVerdict(
                transformed.premium().economicPercentile(),
                transformed.pnl().averagePnlPoints(),
                transformed.pnl().expectancyPoints(),
                downside,
                sampleStatus.lowSampleDowngrade()
        );
        return new EconomicMetrics.RecommendationCandidate(
                mode,
                orientation,
                titleFor(mode, orientation),
                0,
                transformed.observation().observationCount(),
                transformed.observation().lowSampleWarning(),
                transformed.premium().rawPricePercentile(),
                transformed.premium().economicPercentile(),
                transformed.premium().currentVsAveragePoints(),
                transformed.pnl().averagePnlPoints(),
                        selectedSideWinRate(transformed),
                        downside,
                        verdict,
                        "%s, %s, avg pnl %.2f pts, win rate %.1f%%.".formatted(
                                transformed.premium().attractivenessLabel(),
                                transformed.observation().evidenceStrength(),
                                transformed.pnl().averagePnlPoints(),
                                selectedSideWinRate(transformed)
                        )
        );
    }

    public static EconomicMetrics.RecommendationContext emptyRecommendationContext() {
        EconomicMetrics.RecommendationCandidate empty = new EconomicMetrics.RecommendationCandidate(
                "N/A", "N/A", "No candidate", 0, 0, "No matched history", 0, 0, 0, 0, 0, 0, "No verdict", "No matched history."
        );
        return new EconomicMetrics.RecommendationContext(empty, empty, empty, "No candidate strategy has usable history.");
    }

    static boolean isSeller(String orientation) {
        return "SELLER".equalsIgnoreCase(orientation);
    }

    static double toEconomicPremium(double signedNetPremium, boolean seller) {
        return seller ? -signedNetPremium : signedNetPremium;
    }

    static int toRawPricePercentile(int currentNetPremiumPercentile) {
        if (currentNetPremiumPercentile <= 0) {
            return 0;
        }
        return clampPercentile(currentNetPremiumPercentile);
    }

    static int toEconomicPercentile(int rawPricePercentile, boolean seller) {
        if (rawPricePercentile <= 0) {
            return 0;
        }
        return seller ? clampPercentile(rawPricePercentile) : clampPercentile(101 - rawPricePercentile);
    }

    private static int clampPercentile(int percentile) {
        return Math.max(1, Math.min(100, percentile));
    }

    private static String priceConditionLabel(int rawPricePercentile) {
        if (rawPricePercentile >= 85) {
            return "High premium regime";
        }
        if (rawPricePercentile >= 60) {
            return "Above median premium";
        }
        if (rawPricePercentile <= 15) {
            return "Low premium regime";
        }
        if (rawPricePercentile <= 40) {
            return "Below median premium";
        }
        return "Balanced premium regime";
    }

    private static String attractivenessLabel(int economicPercentile, boolean seller) {
        if (economicPercentile >= 85) {
            return seller ? "Rich credit for seller" : "Cheap debit for buyer";
        }
        if (economicPercentile >= 60) {
            return seller ? "Supportive seller setup" : "Reasonable buyer setup";
        }
        if (economicPercentile <= 15) {
            return seller ? "Thin seller setup" : "Expensive buyer setup";
        }
        if (economicPercentile <= 40) {
            return seller ? "Below average seller setup" : "Above average buyer cost";
        }
        return "Neutral attractiveness";
    }

    private static String expectancyLabel(double expectancy) {
        if (expectancy >= 15) {
            return "Positive expectancy";
        }
        if (expectancy <= -15) {
            return "Negative expectancy";
        }
        return "Flat expectancy";
    }

    private static EconomicMetrics.InsightMetrics buildInsight(
            String orientation,
            EconomicMetrics.PremiumMetrics premium,
            EconomicMetrics.PnlMetrics pnl,
            EconomicMetrics.RiskMetrics risk,
            SampleStatus sampleStatus
    ) {
        boolean seller = isSeller(orientation);
        String premiumVerdict = premium.attractivenessLabel();
        String edgeVerdict = pnl.expectancyPoints() >= 10 && pnl.averagePnlPoints() > 0
                ? "Constructive historical edge"
                : pnl.expectancyPoints() <= -10
                ? "Weak historical edge"
                : "Mixed historical edge";
        String riskVerdict = risk.currentTheoreticalMaxLossPoints() == null
                ? "Open-ended risk"
                : "Defined-risk structure";
        String overallVerdict;
        if (sampleStatus.lowSampleDowngrade()) {
            overallVerdict = "Low-confidence read";
        } else if (pnl.expectancyPoints() > 10 && premium.economicPercentile() >= 60) {
            overallVerdict = seller ? "Historically supportive seller setup" : "Historically supportive buyer setup";
        } else if (pnl.expectancyPoints() < -10 || premium.economicPercentile() <= 30) {
            overallVerdict = "Historically unattractive setup";
        } else {
            overallVerdict = "Mixed historical setup";
        }
        return new EconomicMetrics.InsightMetrics(
                premiumVerdict,
                "Raw price percentile %dth, economic percentile %dth.".formatted(
                        premium.rawPricePercentile(),
                        premium.economicPercentile()
                ),
                edgeVerdict,
                "Avg pnl %.2f pts, expectancy %.2f pts, win profile %s.".formatted(
                        pnl.averagePnlPoints(),
                        pnl.expectancyPoints(),
                        seller ? "seller-side" : "buyer-side"
                ),
                riskVerdict,
                "%s. Historical extremes remain sample values only.".formatted(risk.boundsLabel()),
                overallVerdict,
                sampleStatus.lowSampleDowngrade()
                        ? sampleStatus.warning()
                        : "Current-trade bounds are theoretical; raw historical extremes are explicitly sample-only."
        );
    }

    private static String recommendationVerdict(
            int economicPercentile,
            double averagePnl,
            double expectancy,
            double downside,
            boolean lowSampleDowngrade
    ) {
        if (lowSampleDowngrade) {
            return "Low-confidence candidate";
        }
        if (economicPercentile >= 70 && averagePnl > 0 && expectancy > 0 && downside < 80) {
            return "Preferred candidate";
        }
        if (economicPercentile <= 30 || expectancy < 0) {
            return "Avoid candidate";
        }
        return "Alternative candidate";
    }

    private static double selectedSideWinRate(EconomicMetrics transformed) {
        return transformed.expiry().selectedSideWinRatePct();
    }

    private static String titleFor(String mode, String orientation) {
        return "%s / %s".formatted(modeLabel(mode), titleCase(orientation));
    }

    private static String modeLabel(String mode) {
        return switch (mode) {
            case "LONG_STRADDLE" -> "Long Straddle";
            case "SHORT_STRADDLE" -> "Short Straddle";
            case "LONG_STRANGLE" -> "Long Strangle";
            case "SHORT_STRANGLE" -> "Short Strangle";
            case "BULL_CALL_SPREAD" -> "Bull Call Spread";
            case "BEAR_PUT_SPREAD" -> "Bear Put Spread";
            case "IRON_CONDOR" -> "Iron Condor";
            case "IRON_BUTTERFLY" -> "Iron Butterfly";
            case "CUSTOM_MULTI_LEG" -> "Custom Multi-Leg";
            default -> "Single Option";
        };
    }

    private static String titleCase(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? "" : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static SampleStatus sampleStatus(long observationCount) {
        if (observationCount < 25) {
            return new SampleStatus("Sparse", "Low sample warning: fewer than 25 matched structures.", true);
        }
        if (observationCount < 60) {
            return new SampleStatus("Mixed", "Limited sample: treat conclusions cautiously.", true);
        }
        if (observationCount < 120) {
            return new SampleStatus("Adequate", "Moderate sample depth.", false);
        }
        return new SampleStatus("Strong", "", false);
    }

    private record SampleStatus(String evidenceStrength, String warning, boolean lowSampleDowngrade) {
    }
}
