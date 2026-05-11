package com.strategysquad.order;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Heuristic, advisory adjustment engine for short-premium / theta-decay positions.
 *
 * <p>Inputs are the per-strategy aggregates already computed in
 * {@link OptionOrderService#buildStrategyView}. Outputs are a ranked list of
 * {@link OptionOrderService.AdjustmentSuggestion} records — never executed
 * automatically. The trader decides.
 *
 * <p>Rules (priority order, highest first):
 * <ol>
 *   <li><b>CRITICAL delta breach</b> → CLOSE_ALL (priority 1)</li>
 *   <li><b>BREACH</b> with positive net delta → identify the dominant short CE
 *       leg and recommend ADD_LONG_HEDGE (long CE further OTM) and REDUCE_LOTS
 *       on that CE (priority 2-3)</li>
 *   <li><b>BREACH</b> with negative net delta → mirror image on the PE side</li>
 *   <li><b>WATCH</b> — recommend ROLL_UP / ROLL_DOWN of the tested short leg
 *       (priority 4)</li>
 *   <li><b>OK</b> with positive net theta → HOLD (priority 5)</li>
 *   <li><b>OK</b> with negative net theta — premium expanding without delta
 *       breach — recommend WATCH and consider trimming (priority 4)</li>
 * </ol>
 *
 * <p>The engine does not try to size adjustments precisely. Each suggestion
 * carries a coarse {@code expectedDeltaImpact} string for trader intuition.
 * See {@code docs/strategy-tracking-and-adjustment-logic.md} for full
 * explanation.
 */
final class StrategyAdjustmentEngine {

    private StrategyAdjustmentEngine() {}

    static List<OptionOrderService.AdjustmentSuggestion> suggest(
            List<OptionOrderService.StrategyLegView> legs,
            double netDelta,
            Double netDeltaPerShare,
            double netThetaBenefit,
            boolean hasDelta,
            boolean hasTheta,
            boolean shortPremium,
            OptionOrderService.StrategyRiskState risk
    ) {
        List<OptionOrderService.AdjustmentSuggestion> out = new ArrayList<>();
        String state = risk.state();

        if ("UNKNOWN".equals(state)) {
            out.add(new OptionOrderService.AdjustmentSuggestion(
                    "HOLD", "HOLD", 5, null,
                    "Insufficient delta data — wait for next 5 min window with > 0.50 pt spot move.",
                    "—"));
            return out;
        }

        if ("CRITICAL".equals(state)) {
            out.add(new OptionOrderService.AdjustmentSuggestion(
                    "EXIT", "CLOSE_ALL", 1, null,
                    "Delta exposure exceeds 0.50 per share — directional risk now dominates the theta thesis. "
                            + "Close all legs to cap loss.",
                    fmtDelta(netDeltaPerShare)));
            return out;
        }

        boolean bullishDrift = hasDelta && netDelta > 0;   // CE side stressed
        boolean bearishDrift = hasDelta && netDelta < 0;   // PE side stressed

        OptionOrderService.StrategyLegView dominantShortCE = pickDominantLeg(legs, "CE", "SELL");
        OptionOrderService.StrategyLegView dominantShortPE = pickDominantLeg(legs, "PE", "SELL");

        if ("BREACH".equals(state)) {
            if (bullishDrift && dominantShortCE != null) {
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "HEDGE", "ADD_LONG_HEDGE", 2, dominantShortCE.instrumentId(),
                        "Net delta is long-biased and short " + dominantShortCE.tradingSymbol()
                                + " is being tested. Buy a further-OTM CE (1–2 strikes above) to cap upside risk.",
                        "−0.15 to −0.30 per share"));
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "REDUCE", "REDUCE_LOTS", 3, dominantShortCE.instrumentId(),
                        "Reduce lots on " + dominantShortCE.tradingSymbol()
                                + " by ~50% to immediately shrink directional exposure.",
                        "≈ " + fmtDelta(halve(dominantShortCE.netDeltaContribution()))));
            } else if (bearishDrift && dominantShortPE != null) {
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "HEDGE", "ADD_LONG_HEDGE", 2, dominantShortPE.instrumentId(),
                        "Net delta is short-biased and short " + dominantShortPE.tradingSymbol()
                                + " is being tested. Buy a further-OTM PE (1–2 strikes below) to cap downside risk.",
                        "+0.15 to +0.30 per share"));
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "REDUCE", "REDUCE_LOTS", 3, dominantShortPE.instrumentId(),
                        "Reduce lots on " + dominantShortPE.tradingSymbol()
                                + " by ~50% to immediately shrink directional exposure.",
                        "≈ " + fmtDelta(halve(dominantShortPE.netDeltaContribution()))));
            } else {
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "REDUCE", "REDUCE_LOTS", 2, null,
                        "Delta breach but no clear short-leg dominance — trim across all short legs by ~50%.",
                        "≈ " + fmtDelta(netDeltaPerShare == null ? null : -netDeltaPerShare / 2)));
            }
            return out;
        }

        if ("WATCH".equals(state)) {
            if (bullishDrift && dominantShortCE != null) {
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "ROLL", "ROLL_UP", 4, dominantShortCE.instrumentId(),
                        "Roll " + dominantShortCE.tradingSymbol()
                                + " up by 1 strike to re-center delta and recapture decay.",
                        "−0.05 to −0.15 per share"));
            } else if (bearishDrift && dominantShortPE != null) {
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "ROLL", "ROLL_DOWN", 4, dominantShortPE.instrumentId(),
                        "Roll " + dominantShortPE.tradingSymbol()
                                + " down by 1 strike to re-center delta and recapture decay.",
                        "+0.05 to +0.15 per share"));
            } else {
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "HOLD", "HOLD", 5, null,
                        "Mild drift — monitor; no action yet.",
                        fmtDelta(netDeltaPerShare)));
            }
            if (hasTheta && netThetaBenefit < 0) {
                out.add(new OptionOrderService.AdjustmentSuggestion(
                        "REDUCE", "REDUCE_LOTS", 4, null,
                        "Premium is also expanding (net theta against). Consider trimming size by 25–50%.",
                        "—"));
            }
            return out;
        }

        // OK
        if (hasTheta && netThetaBenefit < 0) {
            out.add(new OptionOrderService.AdjustmentSuggestion(
                    "REDUCE", "REDUCE_LOTS", 4, null,
                    "Delta is fine but premium is expanding against the book — IV may be rising. "
                            + "Watch closely; consider trimming if it persists.",
                    "—"));
        } else {
            out.add(new OptionOrderService.AdjustmentSuggestion(
                    "HOLD", "HOLD", 5, null,
                    shortPremium
                            ? "Theta thesis intact — let decay work."
                            : "Position within neutral band.",
                    fmtDelta(netDeltaPerShare)));
        }
        return out;
    }

    /**
     * Picks the leg with the largest absolute net-delta contribution that matches
     * the option type and side. Returns null if no leg matches.
     */
    private static OptionOrderService.StrategyLegView pickDominantLeg(
            List<OptionOrderService.StrategyLegView> legs,
            String optionType,
            String side
    ) {
        return legs.stream()
                .filter(l -> optionType.equalsIgnoreCase(l.optionType()))
                .filter(l -> side.equalsIgnoreCase(l.transactionType()))
                .filter(l -> l.netDeltaContribution() != null)
                .max(Comparator.comparingDouble(l -> Math.abs(l.netDeltaContribution())))
                .orElseGet(() -> legs.stream()
                        .filter(l -> optionType.equalsIgnoreCase(l.optionType()))
                        .filter(l -> side.equalsIgnoreCase(l.transactionType()))
                        .findFirst().orElse(null));
    }

    private static Double halve(Double v) {
        return v == null ? null : v / 2.0;
    }

    private static String fmtDelta(Double v) {
        if (v == null) return "—";
        return String.format(java.util.Locale.ROOT, "%+.3f per share", v);
    }
}
