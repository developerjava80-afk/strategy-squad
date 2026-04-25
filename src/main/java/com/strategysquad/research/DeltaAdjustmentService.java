package com.strategysquad.research;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Evaluates live delta adjustments for an open structure.
 *
 * <p>The engine remains portfolio-first:
 * <ul>
 *   <li>portfolio net delta is the primary risk signal</li>
 *   <li>side-aware delta contribution is used for both CE and PE legs</li>
 *   <li>every candidate must improve {@code |net_delta|}</li>
 *   <li>live add/rebalance competes directly with reduce</li>
 *   <li>cooldown, delay, and hard-risk hierarchy remain intact</li>
 * </ul>
 */
public final class DeltaAdjustmentService {

    static final Duration COOLDOWN = Duration.ofSeconds(60);
    static final Duration FAVORABLE_DELAY_WINDOW = Duration.ofMinutes(2);
    static final Duration WARNING_DELAY_WINDOW = Duration.ofSeconds(60);
    static final Duration CHURN_GUARD_WINDOW = Duration.ofMinutes(3);

    static final int MAX_TOTAL_LOTS = 20;

    static final BigDecimal ZERO = BigDecimal.ZERO;
    static final BigDecimal WARNING_NET_DELTA = new BigDecimal("0.50");
    static final BigDecimal CRITICAL_NET_DELTA = new BigDecimal("0.75");
    static final BigDecimal SIGNIFICANT_IMPROVEMENT_RATIO = new BigDecimal("0.35");
    static final BigDecimal VOLUME_SPIKE_MULTIPLIER = new BigDecimal("1.30");
    static final BigDecimal PNL_DETERIORATION_2M_POINTS = new BigDecimal("8.00");
    static final BigDecimal PNL_DETERIORATION_5M_POINTS = new BigDecimal("15.00");
    static final BigDecimal PNL_IMPROVEMENT_2M_POINTS = new BigDecimal("4.00");
    static final BigDecimal PNL_IMPROVEMENT_5M_POINTS = new BigDecimal("8.00");
    static final BigDecimal LEG_ASYMMETRY_RATIO = new BigDecimal("2.00");

    static final BigDecimal HARD_DELTA_WEIGHT = new BigDecimal("1.00");
    static final BigDecimal HARD_THETA_WEIGHT = new BigDecimal("0.10");
    static final BigDecimal HARD_LIQUIDITY_WEIGHT = new BigDecimal("0.10");
    static final BigDecimal NORMAL_DELTA_WEIGHT = new BigDecimal("1.00");
    static final BigDecimal NORMAL_THETA_WEIGHT = new BigDecimal("0.45");
    static final BigDecimal NORMAL_LIQUIDITY_WEIGHT = new BigDecimal("0.20");
    static final BigDecimal LONG_REDUCE_RISK_PENALTY = new BigDecimal("0.50");
    static final BigDecimal HARD_LONG_REDUCE_RISK_PENALTY = new BigDecimal("0.15");
    static final BigDecimal PROFITABLE_SHORT_REDUCE_RISK_PENALTY = new BigDecimal("0.20");
    static final BigDecimal HARD_PROFITABLE_SHORT_REDUCE_RISK_PENALTY = new BigDecimal("0.05");
    static final BigDecimal LONG_ADD_RISK_PENALTY = new BigDecimal("0.15");
    static final BigDecimal HARD_LONG_ADD_RISK_PENALTY = new BigDecimal("0.05");
    static final BigDecimal THETA_DELTA_FLOOR = new BigDecimal("0.05");
    static final BigDecimal THETA_SCORE_NORMALIZER = new BigDecimal("400.00");
    static final BigDecimal MIN_UNDERLYING_MOVE_FOR_THETA = new BigDecimal("0.50");
    static final BigDecimal EMPIRICAL_DELTA_MAX_ABS = new BigDecimal("1.50");
    static final BigDecimal THETA_CAPTURE_THRESHOLD = new BigDecimal("0.70");
    static final BigDecimal THETA_HOLD_THRESHOLD = new BigDecimal("0.30");
    static final BigDecimal THETA_MIN_DECAY_THRESHOLD = new BigDecimal("0.10");
    static final long THETA_MIN_ELAPSED_MINUTES = 5L;
    static final BigDecimal THETA_PROFIT_BOOK_BONUS = new BigDecimal("0.15");
    static final BigDecimal THETA_DEFENSIVE_PENALTY = new BigDecimal("0.10");

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final Logger LOG = Logger.getLogger(DeltaAdjustmentService.class.getName());

    public enum UnderlyingDirection { BULLISH, BEARISH, NEUTRAL }
    public enum ProfitAlignment { FAVORABLE, UNFAVORABLE, NEUTRAL }
    public enum TriggerType { HARD, NORMAL, DELAYED, SKIPPED }
    public enum ActionType { ADD, REDUCE, SKIP }
    public enum ThetaState { PROFIT_BOOK, HOLD, DEFENSIVE_EXIT, THETA_UNAVAILABLE }

    public AdjustmentOutcome evaluate(AdjustmentContext ctx) {
        Objects.requireNonNull(ctx, "context must not be null");
        Instant evaluationTs = Objects.requireNonNull(ctx.evaluationTs(), "evaluationTs must not be null");

        List<LegState> openLegs = ctx.legs().stream().filter(LegState::isOpen).toList();
        if (openLegs.isEmpty()) {
            return null;
        }

        if (openLegs.stream().anyMatch(leg -> leg.delta2m() == null)) {
            LegState reference = openLegs.get(0);
            return skipped(
                    "adjustment_skipped_missing_delta_history",
                    "missing_delta2m_history",
                    TriggerType.SKIPPED,
                    evaluationTs,
                    ctx.lastAdjustmentTs(),
                    null,
                    reference,
                    lotState(openLegs, ctx.maxTotalLots()),
                    new DecisionSnapshot(null, null, null, null, ZERO, ZERO, false, false, false, false, false),
                    ctx,
                    UnderlyingDirection.NEUTRAL,
                    ProfitAlignment.NEUTRAL,
                    null,
                    false,
                    "Required delta2m is missing on one or more open legs"
            );
        }

        BigDecimal netDelta2m = portfolioNetDelta(openLegs, LegState::delta2m);
        BigDecimal netDelta5m = openLegs.stream().allMatch(leg -> leg.delta5m() != null)
                ? portfolioNetDelta(openLegs, LegState::delta5m)
                : null;
        BigDecimal netDeltaSod = openLegs.stream().allMatch(leg -> leg.deltaSod() != null)
                ? portfolioNetDelta(openLegs, LegState::deltaSod)
                : null;

        boolean worsening = netDelta5m != null
                && netDeltaSod != null
                && netDelta2m.abs().compareTo(netDelta5m.abs()) > 0
                && netDelta5m.abs().compareTo(netDeltaSod.abs()) > 0;

        BigDecimal normalizedNetDelta = normalizedNetDelta(openLegs, netDelta2m);
        boolean warning = normalizedNetDelta != null && normalizedNetDelta.compareTo(WARNING_NET_DELTA) >= 0;
        boolean critical = normalizedNetDelta != null && normalizedNetDelta.compareTo(CRITICAL_NET_DELTA) >= 0;
        boolean riskActive = critical || warning || worsening;
        if (!riskActive) {
            return null;
        }

        LotState lotState = lotState(openLegs, ctx.maxTotalLots());

        UnderlyingDirection underlyingDirection = Objects.requireNonNullElse(
                ctx.underlyingDirection(), UnderlyingDirection.NEUTRAL);
        boolean pnlDeterioratingQuick = isPnlDeterioratingQuick(ctx);
        boolean pnlImproving = isPnlImproving(ctx);
        boolean legAsymmetryStress = isLegAsymmetryStress(openLegs);
        ProfitAlignment profitAlignment = deriveProfitAlignment(
                ctx,
                underlyingDirection,
                netDelta2m,
                warning,
                worsening,
                pnlDeterioratingQuick,
                pnlImproving,
                legAsymmetryStress
        );

        String hardReasonCode = null;
        if (critical) {
            hardReasonCode = "hard_critical_net_delta";
        } else if ((warning || worsening) && pnlDeterioratingQuick) {
            hardReasonCode = "hard_live_pnl_deterioration";
        } else if ((warning || worsening) && legAsymmetryStress) {
            hardReasonCode = "hard_leg_asymmetry_stress";
        }
        boolean hardTrigger = hardReasonCode != null;

        CandidateSet candidateSet = buildCandidates(ctx, openLegs, lotState, netDelta2m, hardTrigger);
        Candidate bestImprovementCandidate = candidateSet.bestImprovementCandidate();
        if (bestImprovementCandidate == null) {
            String code = candidateSet.addBlockedByCap() && !candidateSet.hasReduceImprovement()
                    ? "adjustment_skipped_max_lots_cap"
                    : candidateSet.churnBlockedOnly()
                    ? "adjustment_skipped_churn_guard"
                    : "adjustment_skipped_no_positive_candidate";
            String reasonCode = candidateSet.addBlockedByCap() && !candidateSet.hasReduceImprovement()
                    ? "max_lot_cap_reached"
                    : candidateSet.churnBlockedOnly()
                    ? "churn_guard_active"
                    : "no_candidate_improves_neutrality";
            String message = candidateSet.addBlockedByCap() && !candidateSet.hasReduceImprovement()
                    ? "No add candidate can be applied because the max lot cap is already reached"
                    : candidateSet.churnBlockedOnly()
                    ? "Churn guard blocked immediate flip-flop on the most recent adjustment strike"
                    : "No add or reduce candidate improves portfolio neutrality";
            LegState reference = openLegs.get(0);
            return skipped(
                    code,
                    reasonCode,
                    TriggerType.SKIPPED,
                    evaluationTs,
                    ctx.lastAdjustmentTs(),
                    null,
                    reference,
                    lotState,
                    new DecisionSnapshot(
                            netDelta2m, netDelta5m, netDeltaSod, normalizedNetDelta,
                            ZERO, ZERO, worsening, warning, critical, false, false
                    ),
                    ctx,
                    underlyingDirection,
                    profitAlignment,
                    null,
                    legAsymmetryStress,
                    message
            );
        }

        BigDecimal improvementRatio = improvementRatio(
                bestImprovementCandidate.improvementAbsDelta(),
                netDelta2m.abs()
        );
        boolean significantImprovement = improvementRatio != null
                && improvementRatio.compareTo(SIGNIFICANT_IMPROVEMENT_RATIO) >= 0;

        TriggerType triggerType;
        String triggerReasonCode;
        if (hardTrigger) {
            triggerType = TriggerType.HARD;
            triggerReasonCode = hardReasonCode;
        } else if (profitAlignment == ProfitAlignment.FAVORABLE && !significantImprovement) {
            triggerType = TriggerType.DELAYED;
            triggerReasonCode = "delayed_favorable_alignment";
        } else if (significantImprovement) {
            triggerType = TriggerType.NORMAL;
            triggerReasonCode = "normal_significant_neutrality_improvement";
        } else if (profitAlignment == ProfitAlignment.UNFAVORABLE) {
            triggerType = TriggerType.NORMAL;
            triggerReasonCode = "normal_unfavorable_alignment";
        } else {
            triggerType = TriggerType.NORMAL;
            triggerReasonCode = "normal_neutral_alignment";
        }

        DecisionSnapshot snapshot = new DecisionSnapshot(
                netDelta2m,
                netDelta5m,
                netDeltaSod,
                normalizedNetDelta,
                bestImprovementCandidate.improvementAbsDelta(),
                improvementRatio,
                worsening,
                warning,
                critical,
                bestImprovementCandidate.volumeConfirmed(),
                hardTrigger && !bestImprovementCandidate.volumeConfirmed()
        );

        if (ctx.lastAdjustmentTs() != null
                && Duration.between(ctx.lastAdjustmentTs(), evaluationTs).compareTo(COOLDOWN) < 0) {
            return skipped(
                    "adjustment_skipped_cooldown",
                    "cooldown_active",
                    TriggerType.SKIPPED,
                    evaluationTs,
                    ctx.lastAdjustmentTs(),
                    null,
                    bestImprovementCandidate.leg(),
                    lotState,
                    snapshot,
                    ctx,
                    underlyingDirection,
                    profitAlignment,
                    bestImprovementCandidate.postAdjustmentNetDelta(),
                    legAsymmetryStress,
                    "Cooldown active from last adjustment at " + ctx.lastAdjustmentTs()
            );
        }

        if (triggerType == TriggerType.DELAYED) {
            Duration delay = warning ? WARNING_DELAY_WINDOW : FAVORABLE_DELAY_WINDOW;
            if (ctx.pendingAdjustmentSinceTs() == null) {
                return skipped(
                        "adjustment_pending_favorable_delay",
                        "delay_started_favorable_alignment",
                        TriggerType.DELAYED,
                        evaluationTs,
                        ctx.lastAdjustmentTs(),
                        evaluationTs,
                        bestImprovementCandidate.leg(),
                        lotState,
                        snapshot,
                        ctx,
                        underlyingDirection,
                        profitAlignment,
                        bestImprovementCandidate.postAdjustmentNetDelta(),
                        legAsymmetryStress,
                        "Favorable move with stable risk: started delay window of " + delay
                );
            }
            Duration elapsed = Duration.between(ctx.pendingAdjustmentSinceTs(), evaluationTs);
            if (elapsed.compareTo(delay) < 0) {
                return skipped(
                        "adjustment_pending_favorable_delay",
                        "delay_waiting_favorable_alignment",
                        TriggerType.DELAYED,
                        evaluationTs,
                        ctx.lastAdjustmentTs(),
                        ctx.pendingAdjustmentSinceTs(),
                        bestImprovementCandidate.leg(),
                        lotState,
                        snapshot,
                        ctx,
                        underlyingDirection,
                        profitAlignment,
                        bestImprovementCandidate.postAdjustmentNetDelta(),
                        legAsymmetryStress,
                        "Favorable move still inside delay window (" + elapsed + " / " + delay + ")"
                );
            }
            triggerReasonCode = "delayed_favorable_alignment_elapsed";
        }

        List<LegThetaAssessment> thetaAssessments = openLegs.stream()
                .map(leg -> computeLegTheta(leg, ctx.currentUnderlyingPrice(), evaluationTs))
                .toList();
        ThetaState portfolioTheta = portfolioThetaState(thetaAssessments);
        for (LegThetaAssessment assessment : thetaAssessments) {
            if (assessment.state() != ThetaState.THETA_UNAVAILABLE) {
                LegState thetaLeg = openLegs.stream()
                        .filter(l -> l.label().equals(assessment.legLabel()))
                        .findFirst().orElse(null);
                if (thetaLeg != null) {
                    LOG.info("[theta_assessment] leg=" + assessment.legLabel()
                            + " thetaState=" + assessment.state()
                            + " entryPrice=" + format(thetaLeg.entryPrice())
                            + " currentPrice=" + format(thetaLeg.currentPrice())
                            + " entryUnderlying=" + format(thetaLeg.entryUnderlyingPrice())
                            + " currentUnderlying=" + format(ctx.currentUnderlyingPrice())
                            + " empiricalDelta=" + format(thetaLeg.entryEmpiricalDelta())
                            + " actualThetaBenefit=" + format(assessment.actualThetaBenefit())
                            + " expectedDecay=" + format(assessment.expectedDecaySinceEntry())
                            + " thetaRatio=" + format(assessment.thetaProgressRatio()));
                }
            }
        }

        List<ScoredCandidate> scored = candidateSet.candidates().stream()
                .filter(candidate -> candidate.improvementAbsDelta().compareTo(ZERO) > 0)
                .filter(candidate -> !candidate.churnBlocked())
                .filter(candidate -> candidate.isEligibleFor(triggerType, hardTrigger))
                .filter(candidate -> portfolioTheta != ThetaState.DEFENSIVE_EXIT
                        || candidate.actionType() != ActionType.ADD)
                .map(candidate -> scoreCandidate(candidate, triggerType,
                        findThetaAssessment(thetaAssessments, candidate.leg().label())))
                .filter(candidate -> candidate.score().compareTo(ZERO) > 0)
                .toList();

        if (scored.isEmpty()) {
            Candidate reference = bestImprovementCandidate;
            String code;
            String reasonCode;
            String message;
            if (reference.actionType() == ActionType.ADD && reference.volumeBaselineMissing()) {
                code = "adjustment_skipped_missing_volume_baseline";
                reasonCode = "missing_volume_baseline";
                message = "Volume baseline unavailable for add candidate " + reference.leg().label();
            } else if (!hardTrigger && !reference.volumeConfirmed()) {
                code = "adjustment_skipped_volume_not_confirmed";
                reasonCode = "volume_not_confirmed";
                message = "Volume confirmation not met for candidate " + reference.leg().label();
            } else if (reference.churnBlocked()) {
                code = "adjustment_skipped_churn_guard";
                reasonCode = "churn_guard_active";
                message = "Churn guard blocked immediate flip-flop on " + reference.leg().label();
            } else if (lotState.availableLots() <= 0 && reference.actionType() == ActionType.ADD) {
                code = "adjustment_skipped_max_lots_cap";
                reasonCode = "max_lot_cap_reached";
                message = "No add candidate can be applied because the max lot cap is already reached";
            } else {
                code = "adjustment_skipped_no_positive_candidate";
                reasonCode = "no_positive_candidate";
                message = "No add or reduce candidate has a positive risk-adjusted score";
            }
            return skipped(
                    code,
                    reasonCode,
                    TriggerType.SKIPPED,
                    evaluationTs,
                    ctx.lastAdjustmentTs(),
                    null,
                    reference.leg(),
                    lotState,
                    snapshot,
                    ctx,
                    underlyingDirection,
                    profitAlignment,
                    reference.postAdjustmentNetDelta(),
                    legAsymmetryStress,
                    message
            );
        }

        ScoredCandidate selected = selectBestScored(scored, triggerType);
        Candidate candidate = selected.candidate();
        boolean volumeBypassed = hardTrigger && !candidate.volumeConfirmed();

        AdjustmentOutcome outcome = new AdjustmentOutcome(
                "delta_adjustment_applied",
                true,
                evaluationTs,
                evaluationTs,
                null,
                candidate.actionType().name(),
                triggerType.name(),
                triggerReasonCode,
                candidate.leg().label(),
                candidate.leg().optionType(),
                candidate.leg().side(),
                candidate.leg().strike(),
                candidate.leg().instrumentId(),
                candidate.leg().symbol(),
                candidate.leg().expiryDate(),
                candidate.leg().currentPrice(),
                candidate.oldQuantity(),
                candidate.newQuantity(),
                lotState.currentTotalLots(),
                lotState.maxTotalLots(),
                lotState.availableLots(),
                candidate.leg().delta2m(),
                candidate.leg().delta5m(),
                candidate.leg().deltaSod(),
                candidate.leg().currentVolume(),
                candidate.leg().dayAverageVolume(),
                candidate.volumeConfirmed(),
                volumeBypassed,
                underlyingDirection.name(),
                profitAlignment.name(),
                ctx.livePnlPoints(),
                ctx.livePnlChange2mPoints(),
                ctx.livePnlChange5mPoints(),
                netDelta2m,
                netDelta5m,
                netDeltaSod,
                normalizedNetDelta,
                candidate.improvementAbsDelta(),
                candidate.improvementRatio(),
                candidate.postAdjustmentNetDelta(),
                selected.thetaScore(),
                selected.liquidityScore(),
                selected.score(),
                worsening,
                legAsymmetryStress,
                triggerReasonCode,
                actionMessage(candidate, triggerType, netDelta2m),
                portfolioTheta.name(),
                totalThetaBenefitQty(thetaAssessments),
                avgThetaProgressRatio(thetaAssessments)
        );

        LOG.info("[delta_adjustment_applied] " + auditLine(outcome));
        return outcome;
    }

    private static CandidateSet buildCandidates(
            AdjustmentContext ctx,
            List<LegState> openLegs,
            LotState lotState,
            BigDecimal netDelta2m,
            boolean hardTrigger
    ) {
        List<Candidate> candidates = new java.util.ArrayList<>();
        boolean anyAddCandidate = false;
        boolean addBlockedByCap = false;
        boolean anyReduceImprovement = false;
        boolean churnBlockedOnly = true;

        for (LegState leg : openLegs) {
            if (leg.quantity() <= leg.lotSize()) {
                continue;
            }
            Candidate candidate = reduceCandidate(ctx, leg, netDelta2m);
            if (candidate != null) {
                candidates.add(candidate);
                if (candidate.improvementAbsDelta().compareTo(ZERO) > 0) {
                    anyReduceImprovement = true;
                }
                if (!candidate.churnBlocked()) {
                    churnBlockedOnly = false;
                }
            }
        }

        if (!openLegs.isEmpty() || !ctx.addCandidates().isEmpty()) {
            anyAddCandidate = true;
            if (lotState.availableLots() <= 0) {
                addBlockedByCap = true;
            } else {
                for (LegState candidateLeg : openLegs) {
                    Candidate candidate = addCandidate(ctx, openLegs, candidateLeg, lotState, netDelta2m, hardTrigger);
                    if (candidate != null) {
                        candidates.add(candidate);
                        if (!candidate.churnBlocked()) {
                            churnBlockedOnly = false;
                        }
                    }
                }
                for (LegState candidateLeg : ctx.addCandidates()) {
                    Candidate candidate = addCandidate(ctx, openLegs, candidateLeg, lotState, netDelta2m, hardTrigger);
                    if (candidate != null) {
                        candidates.add(candidate);
                        if (!candidate.churnBlocked()) {
                            churnBlockedOnly = false;
                        }
                    }
                }
            }
        }

        Candidate bestImprovementCandidate = candidates.stream()
                .filter(candidate -> candidate.improvementAbsDelta().compareTo(ZERO) > 0)
                .max(Comparator
                        .comparing(Candidate::improvementAbsDelta)
                        .thenComparing(candidate -> candidate.actionType() == ActionType.REDUCE ? BigDecimal.ONE : ZERO))
                .orElse(null);

        return new CandidateSet(
                List.copyOf(candidates),
                bestImprovementCandidate,
                anyAddCandidate,
                addBlockedByCap,
                anyReduceImprovement,
                !candidates.isEmpty() && churnBlockedOnly
        );
    }

    private static Candidate reduceCandidate(AdjustmentContext ctx, LegState leg, BigDecimal currentNetDelta) {
        if (leg.delta2m() == null || leg.currentPrice() == null || leg.stale()) {
            return null;
        }
        BigDecimal signedDeltaPerUnit = signedDelta(leg, LegState::delta2m);
        BigDecimal deltaChange = signedDeltaPerUnit.multiply(BigDecimal.valueOf(leg.lotSize()), MC).negate();
        BigDecimal postNet = currentNetDelta.add(deltaChange, MC);
        BigDecimal improvement = currentNetDelta.abs().subtract(postNet.abs(), MC);
        boolean volumeBaselineMissing = leg.dayAverageVolume() == null || leg.dayAverageVolume().compareTo(ZERO) <= 0;
        boolean volumeConfirmed = !volumeBaselineMissing
                && leg.currentVolume() != null
                && BigDecimal.valueOf(leg.currentVolume()).compareTo(
                        leg.dayAverageVolume().multiply(VOLUME_SPIKE_MULTIPLIER, MC)
                ) > 0;
        boolean churnBlocked = isChurnBlocked(ctx, ActionType.REDUCE, leg);
        return new Candidate(
                ActionType.REDUCE,
                leg,
                leg.quantity(),
                Math.max(0, leg.quantity() - leg.lotSize()),
                postNet,
                improvement.max(ZERO),
                improvementRatio(improvement.max(ZERO), currentNetDelta.abs()),
                volumeConfirmed,
                volumeBaselineMissing,
                churnBlocked
        );
    }

    private static Candidate addCandidate(
            AdjustmentContext ctx,
            List<LegState> openLegs,
            LegState addLeg,
            LotState lotState,
            BigDecimal currentNetDelta,
            boolean hardTrigger
    ) {
        if (lotState.availableLots() <= 0) {
            return null;
        }
        if (addLeg == null
                || addLeg.delta2m() == null
                || addLeg.currentPrice() == null
                || addLeg.stale()
                || addLeg.currentVolume() == null
                || addLeg.dayAverageVolume() == null
                || addLeg.dayAverageVolume().compareTo(ZERO) <= 0) {
            return null;
        }
        BigDecimal signedDeltaPerUnit = signedDelta(addLeg, LegState::delta2m);
        BigDecimal deltaChange = signedDeltaPerUnit.multiply(BigDecimal.valueOf(addLeg.lotSize()), MC);
        BigDecimal postNet = currentNetDelta.add(deltaChange, MC);
        BigDecimal improvement = currentNetDelta.abs().subtract(postNet.abs(), MC);
        int oldQuantity = matchingOpenQuantity(openLegs, addLeg);
        int newQuantity = oldQuantity + addLeg.lotSize();
        boolean volumeConfirmed = BigDecimal.valueOf(addLeg.currentVolume()).compareTo(
                addLeg.dayAverageVolume().multiply(VOLUME_SPIKE_MULTIPLIER, MC)
        ) > 0;
        boolean churnBlocked = isChurnBlocked(ctx, ActionType.ADD, addLeg);
        if (!hardTrigger && !volumeConfirmed && improvement.compareTo(ZERO) <= 0) {
            return null;
        }
        return new Candidate(
                ActionType.ADD,
                addLeg,
                oldQuantity,
                newQuantity,
                postNet,
                improvement.max(ZERO),
                improvementRatio(improvement.max(ZERO), currentNetDelta.abs()),
                volumeConfirmed,
                false,
                churnBlocked
        );
    }

    private static int matchingOpenQuantity(List<LegState> openLegs, LegState candidate) {
        return openLegs.stream()
                .filter(leg -> identityMatches(leg, candidate))
                .mapToInt(LegState::quantity)
                .findFirst()
                .orElse(0);
    }

    private static ScoredCandidate scoreCandidate(
            Candidate candidate, TriggerType triggerType, LegThetaAssessment thetaAssessment) {
        BigDecimal deltaWeight = triggerType == TriggerType.HARD ? HARD_DELTA_WEIGHT : NORMAL_DELTA_WEIGHT;
        BigDecimal thetaWeight = triggerType == TriggerType.HARD ? HARD_THETA_WEIGHT : NORMAL_THETA_WEIGHT;
        BigDecimal liquidityWeight = triggerType == TriggerType.HARD ? HARD_LIQUIDITY_WEIGHT : NORMAL_LIQUIDITY_WEIGHT;
        BigDecimal thetaScore = thetaScore(candidate, triggerType, thetaAssessment);
        BigDecimal liquidityScore = liquidityScore(candidate);
        BigDecimal riskPenalty = riskPenalty(candidate, triggerType);
        BigDecimal score = deltaWeight.multiply(candidate.improvementRatio(), MC)
                .add(thetaWeight.multiply(thetaScore, MC), MC)
                .add(liquidityWeight.multiply(liquidityScore, MC), MC)
                .subtract(riskPenalty, MC);
        return new ScoredCandidate(candidate, thetaScore, liquidityScore, riskPenalty, score);
    }

    private static ScoredCandidate selectBestScored(List<ScoredCandidate> scored, TriggerType triggerType) {
        Comparator<ScoredCandidate> comparator;
        if (triggerType == TriggerType.HARD) {
            comparator = Comparator
                    .comparing((ScoredCandidate candidate) -> candidate.candidate().improvementAbsDelta())
                    .thenComparing(ScoredCandidate::score)
                    .thenComparing(candidate -> candidate.candidate().actionType() == ActionType.REDUCE ? BigDecimal.ONE : ZERO);
        } else {
            comparator = Comparator
                    .comparing(ScoredCandidate::score)
                    .thenComparing(candidate -> candidate.candidate().improvementAbsDelta())
                    .thenComparing(candidate -> candidate.candidate().actionType() == ActionType.ADD ? BigDecimal.ONE : ZERO);
        }
        return scored.stream().max(comparator).orElseThrow();
    }

    private static BigDecimal thetaScore(
            Candidate candidate, TriggerType triggerType, LegThetaAssessment thetaAssessment) {
        LegState leg = candidate.leg();
        if (leg.currentPrice() == null || leg.delta2m() == null) {
            return ZERO;
        }
        BigDecimal deltaAbs = leg.delta2m().abs().max(THETA_DELTA_FLOOR);
        BigDecimal rawCarry = leg.currentPrice().divide(deltaAbs, MC);
        BigDecimal normalizedCarry = clamp01(rawCarry.divide(THETA_SCORE_NORMALIZER, MC));

        BigDecimal base;
        if (candidate.actionType() == ActionType.ADD) {
            base = leg.isShort() ? normalizedCarry
                    : normalizedCarry.negate().multiply(new BigDecimal("0.25"), MC);
        } else if (leg.isShort()) {
            base = normalizedCarry.negate();
        } else {
            base = normalizedCarry.multiply(
                    triggerType == TriggerType.HARD ? new BigDecimal("0.10") : new BigDecimal("0.25"), MC);
        }

        if (thetaAssessment != null) {
            if (thetaAssessment.state() == ThetaState.PROFIT_BOOK
                    && candidate.actionType() == ActionType.REDUCE && leg.isShort()) {
                base = base.add(THETA_PROFIT_BOOK_BONUS, MC);
            } else if (thetaAssessment.state() == ThetaState.DEFENSIVE_EXIT) {
                base = base.subtract(THETA_DEFENSIVE_PENALTY, MC);
            }
        }
        return base;
    }

    private static BigDecimal liquidityScore(Candidate candidate) {
        LegState leg = candidate.leg();
        if (leg.currentVolume() == null || leg.dayAverageVolume() == null || leg.dayAverageVolume().compareTo(ZERO) <= 0) {
            return ZERO;
        }
        BigDecimal ratio = BigDecimal.valueOf(leg.currentVolume()).divide(leg.dayAverageVolume(), MC);
        return clamp01(ratio.divide(VOLUME_SPIKE_MULTIPLIER, MC));
    }

    private static BigDecimal riskPenalty(Candidate candidate, TriggerType triggerType) {
        LegState leg = candidate.leg();
        BigDecimal penalty = ZERO;
        if (candidate.actionType() == ActionType.REDUCE && leg.isLong()) {
            penalty = penalty.add(triggerType == TriggerType.HARD
                    ? HARD_LONG_REDUCE_RISK_PENALTY
                    : LONG_REDUCE_RISK_PENALTY);
        }
        if (candidate.actionType() == ActionType.REDUCE
                && leg.isShort()
                && leg.livePnlPoints() != null
                && leg.livePnlPoints().compareTo(ZERO) > 0) {
            penalty = penalty.add(triggerType == TriggerType.HARD
                    ? HARD_PROFITABLE_SHORT_REDUCE_RISK_PENALTY
                    : PROFITABLE_SHORT_REDUCE_RISK_PENALTY);
        }
        if (candidate.actionType() == ActionType.ADD && leg.isLong()) {
            penalty = penalty.add(triggerType == TriggerType.HARD
                    ? HARD_LONG_ADD_RISK_PENALTY
                    : LONG_ADD_RISK_PENALTY);
        }
        return penalty;
    }

    private static boolean isChurnBlocked(AdjustmentContext ctx, ActionType nextAction, LegState leg) {
        if (ctx.lastAdjustment() == null || ctx.lastAdjustmentTs() == null) {
            return false;
        }
        if (Duration.between(ctx.lastAdjustmentTs(), ctx.evaluationTs()).compareTo(CHURN_GUARD_WINDOW) >= 0) {
            return false;
        }
        LastAdjustment last = ctx.lastAdjustment();
        if (last.actionType() == null || last.actionType() == nextAction) {
            return false;
        }
        if (!equalsIgnoreCase(last.optionType(), leg.optionType())) {
            return false;
        }
        if (!equalsIgnoreCase(last.side(), leg.side())) {
            return false;
        }
        return last.strike() != null
                && leg.strike() != null
                && last.strike().compareTo(leg.strike()) == 0;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static BigDecimal portfolioNetDelta(List<LegState> legs, Function<LegState, BigDecimal> selector) {
        return legs.stream()
                .map(leg -> signedDelta(leg, selector).multiply(BigDecimal.valueOf(leg.quantity()), MC))
                .reduce(ZERO, BigDecimal::add);
    }

    private static BigDecimal signedDelta(LegState leg, Function<LegState, BigDecimal> selector) {
        BigDecimal rawDelta = selector.apply(leg);
        if (rawDelta == null) {
            return ZERO;
        }
        BigDecimal sideMultiplier = leg.isShort() ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        return rawDelta.multiply(sideMultiplier, MC);
    }

    private static BigDecimal normalizedNetDelta(List<LegState> legs, BigDecimal netDelta2m) {
        BigDecimal totalAbsDelta = legs.stream()
                .map(leg -> signedDelta(leg, LegState::delta2m).multiply(BigDecimal.valueOf(leg.quantity()), MC).abs())
                .reduce(ZERO, BigDecimal::add);
        if (totalAbsDelta.compareTo(ZERO) <= 0) {
            return ZERO;
        }
        return netDelta2m.abs().divide(totalAbsDelta, MC);
    }

    private static LotState lotState(List<LegState> openLegs, int requestedMaxLots) {
        int maxLots = requestedMaxLots > 0 ? requestedMaxLots : MAX_TOTAL_LOTS;
        int currentLots = openLegs.stream()
                .mapToInt(leg -> leg.lotSize() <= 0 ? 0 : Math.max(0, leg.quantity()) / leg.lotSize())
                .sum();
        return new LotState(currentLots, maxLots, Math.max(0, maxLots - currentLots));
    }

    private static BigDecimal improvementRatio(BigDecimal improvement, BigDecimal currentAbsNet) {
        if (improvement == null || currentAbsNet == null || currentAbsNet.compareTo(ZERO) <= 0) {
            return ZERO;
        }
        return improvement.divide(currentAbsNet, MC);
    }

    private static boolean isPnlDeterioratingQuick(AdjustmentContext ctx) {
        return isAtOrBelow(ctx.livePnlChange2mPoints(), PNL_DETERIORATION_2M_POINTS.negate())
                || isAtOrBelow(ctx.livePnlChange5mPoints(), PNL_DETERIORATION_5M_POINTS.negate());
    }

    private static boolean isPnlImproving(AdjustmentContext ctx) {
        return isAtOrAbove(ctx.livePnlChange2mPoints(), PNL_IMPROVEMENT_2M_POINTS)
                || isAtOrAbove(ctx.livePnlChange5mPoints(), PNL_IMPROVEMENT_5M_POINTS);
    }

    private static boolean isAtOrBelow(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) <= 0;
    }

    private static boolean isAtOrAbove(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }

    private static boolean isLegAsymmetryStress(List<LegState> legs) {
        BigDecimal maxLoss = ZERO;
        BigDecimal maxGain = ZERO;
        for (LegState leg : legs) {
            if (leg.livePnlPoints() == null) {
                continue;
            }
            if (leg.livePnlPoints().compareTo(ZERO) < 0) {
                maxLoss = maxLoss.max(leg.livePnlPoints().abs());
            } else if (leg.livePnlPoints().compareTo(ZERO) > 0) {
                maxGain = maxGain.max(leg.livePnlPoints());
            }
        }
        return maxLoss.compareTo(ZERO) > 0
                && maxGain.compareTo(ZERO) > 0
                && maxLoss.compareTo(maxGain.multiply(LEG_ASYMMETRY_RATIO, MC)) >= 0;
    }

    private static ProfitAlignment deriveProfitAlignment(
            AdjustmentContext ctx,
            UnderlyingDirection direction,
            BigDecimal netDelta2m,
            boolean warning,
            boolean worsening,
            boolean pnlDeterioratingQuick,
            boolean pnlImproving,
            boolean legAsymmetryStress
    ) {
        boolean liveInLoss = ctx.livePnlPoints() != null && ctx.livePnlPoints().compareTo(ZERO) < 0;
        ProfitAlignment directionalPressure = directionalPressure(direction, netDelta2m);

        if (legAsymmetryStress || pnlDeterioratingQuick) {
            return ProfitAlignment.UNFAVORABLE;
        }
        if (directionalPressure == ProfitAlignment.UNFAVORABLE) {
            return ProfitAlignment.UNFAVORABLE;
        }
        if (liveInLoss && (warning || worsening) && directionalPressure != ProfitAlignment.FAVORABLE) {
            return ProfitAlignment.UNFAVORABLE;
        }
        if (directionalPressure == ProfitAlignment.FAVORABLE && !worsening && !liveInLoss) {
            return ProfitAlignment.FAVORABLE;
        }
        if (pnlImproving && directionalPressure != ProfitAlignment.UNFAVORABLE && !worsening) {
            return ProfitAlignment.FAVORABLE;
        }
        return ProfitAlignment.NEUTRAL;
    }

    private static ProfitAlignment directionalPressure(UnderlyingDirection direction, BigDecimal netDelta2m) {
        if (direction == UnderlyingDirection.NEUTRAL || netDelta2m == null || netDelta2m.compareTo(ZERO) == 0) {
            return ProfitAlignment.NEUTRAL;
        }
        boolean favorable = (direction == UnderlyingDirection.BULLISH && netDelta2m.compareTo(ZERO) > 0)
                || (direction == UnderlyingDirection.BEARISH && netDelta2m.compareTo(ZERO) < 0);
        return favorable ? ProfitAlignment.FAVORABLE : ProfitAlignment.UNFAVORABLE;
    }

    private static String actionMessage(Candidate candidate, TriggerType triggerType, BigDecimal currentNetDelta) {
        String verb = candidate.actionType() == ActionType.ADD ? "Added" : "Reduced";
        return verb + " " + candidate.leg().label()
                + " by one lot because post-adjustment net delta improves from "
                + format(currentNetDelta) + " to " + format(candidate.postAdjustmentNetDelta())
                + " (trigger=" + triggerType.name()
                + ", improvement=" + format(candidate.improvementAbsDelta()) + ")";
    }

    private static AdjustmentOutcome skipped(
            String code,
            String reasonCode,
            TriggerType triggerType,
            Instant timestamp,
            Instant lastAdjustmentTs,
            Instant pendingAdjustmentTs,
            LegState leg,
            LotState lotState,
            DecisionSnapshot snapshot,
            AdjustmentContext ctx,
            UnderlyingDirection underlyingDirection,
            ProfitAlignment profitAlignment,
            BigDecimal postAdjustmentNetDelta,
            boolean legAsymmetryStress,
            String message
    ) {
        AdjustmentOutcome outcome = new AdjustmentOutcome(
                code,
                false,
                timestamp,
                lastAdjustmentTs,
                pendingAdjustmentTs,
                ActionType.SKIP.name(),
                triggerType.name(),
                reasonCode,
                leg == null ? null : leg.label(),
                leg == null ? null : leg.optionType(),
                leg == null ? null : leg.side(),
                leg == null ? null : leg.strike(),
                leg == null ? null : leg.instrumentId(),
                leg == null ? null : leg.symbol(),
                leg == null ? null : leg.expiryDate(),
                leg == null ? null : leg.currentPrice(),
                leg == null ? 0 : leg.quantity(),
                leg == null ? 0 : leg.quantity(),
                lotState == null ? 0 : lotState.currentTotalLots(),
                lotState == null ? MAX_TOTAL_LOTS : lotState.maxTotalLots(),
                lotState == null ? MAX_TOTAL_LOTS : lotState.availableLots(),
                leg == null ? null : leg.delta2m(),
                leg == null ? null : leg.delta5m(),
                leg == null ? null : leg.deltaSod(),
                leg == null ? null : leg.currentVolume(),
                leg == null ? null : leg.dayAverageVolume(),
                snapshot.volumeConfirmed(),
                snapshot.volumeBypassed(),
                underlyingDirection.name(),
                profitAlignment.name(),
                ctx == null ? null : ctx.livePnlPoints(),
                ctx == null ? null : ctx.livePnlChange2mPoints(),
                ctx == null ? null : ctx.livePnlChange5mPoints(),
                snapshot.netDelta2m(),
                snapshot.netDelta5m(),
                snapshot.netDeltaSod(),
                snapshot.normalizedNetDelta(),
                snapshot.improvementAbsDelta(),
                snapshot.improvementRatio(),
                postAdjustmentNetDelta,
                null,
                null,
                null,
                snapshot.worsening(),
                legAsymmetryStress,
                reasonCode,
                message,
                null,
                null,
                null
        );
        LOG.info("[" + code + "] " + auditLine(outcome));
        return outcome;
    }

    private static LegThetaAssessment computeLegTheta(
            LegState leg, BigDecimal currentUnderlyingPrice, Instant evaluationTs) {
        if (leg.entryUnderlyingPrice() == null || leg.entryEmpiricalDelta() == null
                || leg.entryTime() == null || leg.entryPrice() == null
                || leg.currentPrice() == null || currentUnderlyingPrice == null) {
            return new LegThetaAssessment(leg.label(), ThetaState.THETA_UNAVAILABLE,
                    null, null, null, null);
        }
        long elapsedMinutes = Duration.between(leg.entryTime(), evaluationTs).toMinutes();
        if (elapsedMinutes < THETA_MIN_ELAPSED_MINUTES) {
            return new LegThetaAssessment(leg.label(), ThetaState.THETA_UNAVAILABLE,
                    null, null, null, null);
        }
        BigDecimal underlyingChange = currentUnderlyingPrice.subtract(leg.entryUnderlyingPrice());
        if (underlyingChange.abs().compareTo(MIN_UNDERLYING_MOVE_FOR_THETA) < 0) {
            return new LegThetaAssessment(leg.label(), ThetaState.THETA_UNAVAILABLE,
                    null, null, null, null);
        }
        BigDecimal actualOptionChange = leg.currentPrice().subtract(leg.entryPrice());
        BigDecimal expectedDeltaMove = leg.entryEmpiricalDelta().multiply(underlyingChange, MC);
        BigDecimal residual = actualOptionChange.subtract(expectedDeltaMove, MC);
        BigDecimal actualThetaBenefit = leg.isShort() ? residual.negate() : residual;
        BigDecimal actualThetaBenefitQty = actualThetaBenefit.multiply(BigDecimal.valueOf(leg.quantity()), MC);

        BigDecimal expectedDecaySinceEntry = null;
        BigDecimal thetaProgressRatio = null;
        if (leg.entryExpectedDecayRatePerMinute() != null) {
            expectedDecaySinceEntry = leg.entryExpectedDecayRatePerMinute()
                    .multiply(BigDecimal.valueOf(elapsedMinutes), MC);
            if (expectedDecaySinceEntry.compareTo(THETA_MIN_DECAY_THRESHOLD) > 0) {
                thetaProgressRatio = actualThetaBenefit.divide(expectedDecaySinceEntry, MC);
            }
        }

        ThetaState state;
        if (actualThetaBenefit.compareTo(ZERO) < 0) {
            state = ThetaState.DEFENSIVE_EXIT;
        } else if (thetaProgressRatio != null && thetaProgressRatio.compareTo(THETA_CAPTURE_THRESHOLD) >= 0) {
            state = ThetaState.PROFIT_BOOK;
        } else {
            state = ThetaState.HOLD;
        }
        return new LegThetaAssessment(leg.label(), state, actualThetaBenefit,
                actualThetaBenefitQty, expectedDecaySinceEntry, thetaProgressRatio);
    }

    private static ThetaState portfolioThetaState(List<LegThetaAssessment> assessments) {
        boolean anyDefensive = false;
        boolean anyProfitBook = false;
        boolean allUnavailable = true;
        for (LegThetaAssessment a : assessments) {
            if (a.state() != ThetaState.THETA_UNAVAILABLE) {
                allUnavailable = false;
            }
            if (a.state() == ThetaState.DEFENSIVE_EXIT) {
                anyDefensive = true;
            }
            if (a.state() == ThetaState.PROFIT_BOOK) {
                anyProfitBook = true;
            }
        }
        if (anyDefensive) return ThetaState.DEFENSIVE_EXIT;
        if (allUnavailable) return ThetaState.THETA_UNAVAILABLE;
        if (anyProfitBook) return ThetaState.PROFIT_BOOK;
        return ThetaState.HOLD;
    }

    private static LegThetaAssessment findThetaAssessment(
            List<LegThetaAssessment> assessments, String legLabel) {
        for (LegThetaAssessment a : assessments) {
            if (a.legLabel().equals(legLabel)) {
                return a;
            }
        }
        return null;
    }

    private static BigDecimal totalThetaBenefitQty(List<LegThetaAssessment> assessments) {
        BigDecimal total = ZERO;
        for (LegThetaAssessment a : assessments) {
            if (a.state() != ThetaState.THETA_UNAVAILABLE && a.actualThetaBenefitQty() != null) {
                total = total.add(a.actualThetaBenefitQty(), MC);
            }
        }
        return total;
    }

    private static BigDecimal avgThetaProgressRatio(List<LegThetaAssessment> assessments) {
        BigDecimal sum = ZERO;
        int count = 0;
        for (LegThetaAssessment a : assessments) {
            if (a.thetaProgressRatio() != null) {
                sum = sum.add(a.thetaProgressRatio(), MC);
                count++;
            }
        }
        if (count == 0) return null;
        return sum.divide(BigDecimal.valueOf(count), MC);
    }

    private static String auditLine(AdjustmentOutcome outcome) {
        return "action=" + outcome.actionType()
                + " trigger=" + outcome.triggerType()
                + " reasonCode=" + outcome.reasonCode()
                + " leg=" + outcome.leg()
                + " strike=" + format(outcome.strike())
                + " oldQty=" + outcome.oldQuantity()
                + " newQty=" + outcome.newQuantity()
                + " lots=" + outcome.currentTotalLots() + "/" + outcome.maxTotalLots()
                + " availableLots=" + outcome.availableLots()
                + " net2m=" + format(outcome.netDelta2m())
                + " net5m=" + format(outcome.netDelta5m())
                + " netSod=" + format(outcome.netDeltaSod())
                + " postNet=" + format(outcome.postAdjNetDelta())
                + " improvement=" + format(outcome.improvementAbsDelta())
                + " theta=" + format(outcome.thetaScore())
                + " score=" + format(outcome.score())
                + " pnl2m=" + format(outcome.livePnlChange2mPoints())
                + " pnl5m=" + format(outcome.livePnlChange5mPoints())
                + " dir=" + outcome.underlyingDirection()
                + " alignment=" + outcome.profitAlignment()
                + " volumeConfirmed=" + outcome.volumeConfirmed()
                + " volumeBypassed=" + outcome.volumeBypassed()
                + " portfolioTheta=" + outcome.portfolioThetaState()
                + " thetaBenefitQty=" + format(outcome.portfolioThetaBenefitQty())
                + " thetaRatio=" + format(outcome.portfolioThetaProgressRatio());
    }

    private static String format(BigDecimal value) {
        return value == null ? "n/a" : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal clamp01(BigDecimal value) {
        if (value == null || value.compareTo(ZERO) <= 0) {
            return ZERO;
        }
        return value.compareTo(BigDecimal.ONE) >= 0 ? BigDecimal.ONE : value;
    }

    private static boolean identityMatches(LegState left, LegState right) {
        return equalsIgnoreCase(left.optionType(), right.optionType())
                && equalsIgnoreCase(left.side(), right.side())
                && left.strike() != null
                && right.strike() != null
                && left.strike().compareTo(right.strike()) == 0
                && Objects.equals(nullToBlank(left.expiryDate()), nullToBlank(right.expiryDate()));
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private record CandidateSet(
            List<Candidate> candidates,
            Candidate bestImprovementCandidate,
            boolean anyAddCandidate,
            boolean addBlockedByCap,
            boolean hasReduceImprovement,
            boolean churnBlockedOnly
    ) {
    }

    private record Candidate(
            ActionType actionType,
            LegState leg,
            int oldQuantity,
            int newQuantity,
            BigDecimal postAdjustmentNetDelta,
            BigDecimal improvementAbsDelta,
            BigDecimal improvementRatio,
            boolean volumeConfirmed,
            boolean volumeBaselineMissing,
            boolean churnBlocked
    ) {
        boolean isEligibleFor(TriggerType triggerType, boolean hardTrigger) {
            if (leg.currentPrice() == null || leg.delta2m() == null || leg.stale()) {
                return false;
            }
            if (actionType == ActionType.ADD && volumeBaselineMissing) {
                return false;
            }
            return hardTrigger || volumeConfirmed;
        }
    }

    private record ScoredCandidate(
            Candidate candidate,
            BigDecimal thetaScore,
            BigDecimal liquidityScore,
            BigDecimal riskPenalty,
            BigDecimal score
    ) {
    }

    private record LotState(
            int currentTotalLots,
            int maxTotalLots,
            int availableLots
    ) {
    }

    private record DecisionSnapshot(
            BigDecimal netDelta2m,
            BigDecimal netDelta5m,
            BigDecimal netDeltaSod,
            BigDecimal normalizedNetDelta,
            BigDecimal improvementAbsDelta,
            BigDecimal improvementRatio,
            boolean worsening,
            boolean warning,
            boolean critical,
            boolean volumeConfirmed,
            boolean volumeBypassed
    ) {
    }

    private record LegThetaAssessment(
            String legLabel,
            ThetaState state,
            BigDecimal actualThetaBenefit,
            BigDecimal actualThetaBenefitQty,
            BigDecimal expectedDecaySinceEntry,
            BigDecimal thetaProgressRatio
    ) {
    }

    public record AdjustmentContext(
            Instant evaluationTs,
            Instant lastAdjustmentTs,
            Instant pendingAdjustmentSinceTs,
            List<LegState> legs,
            List<LegState> addCandidates,
            UnderlyingDirection underlyingDirection,
            BigDecimal livePnlPoints,
            BigDecimal livePnlChange2mPoints,
            BigDecimal livePnlChange5mPoints,
            int maxTotalLots,
            LastAdjustment lastAdjustment,
            BigDecimal currentUnderlyingPrice
    ) {
        public AdjustmentContext {
            legs = legs == null ? List.of() : List.copyOf(legs);
            addCandidates = addCandidates == null ? List.of() : List.copyOf(addCandidates);
        }

        public static AdjustmentContext of(Instant evaluationTs, Instant lastAdjustmentTs, List<LegState> legs) {
            return new AdjustmentContext(
                    evaluationTs,
                    lastAdjustmentTs,
                    null,
                    legs,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    MAX_TOTAL_LOTS,
                    null,
                    null
            );
        }
    }

    public record LastAdjustment(
            ActionType actionType,
            String optionType,
            String side,
            BigDecimal strike,
            Instant timestamp
    ) {
    }

    public record LegState(
            String label,
            String optionType,
            String side,
            BigDecimal strike,
            int quantity,
            int lotSize,
            BigDecimal entryPrice,
            BigDecimal currentPrice,
            BigDecimal delta2m,
            BigDecimal delta5m,
            BigDecimal deltaSod,
            Long currentVolume,
            BigDecimal dayAverageVolume,
            BigDecimal livePnlPoints,
            String instrumentId,
            String symbol,
            String expiryDate,
            boolean stale,
            BigDecimal entryUnderlyingPrice,
            BigDecimal entryEmpiricalDelta,
            BigDecimal entryExpectedDecayRatePerMinute,
            Instant entryTime
    ) {
        boolean isShort() {
            return "SHORT".equalsIgnoreCase(side);
        }

        boolean isLong() {
            return "LONG".equalsIgnoreCase(side);
        }

        boolean isOpen() {
            return quantity > 0;
        }
    }

    public record AdjustmentOutcome(
            String code,
            boolean applied,
            Instant timestamp,
            Instant updatedLastAdjustmentTs,
            Instant updatedPendingAdjustmentSinceTs,
            String actionType,
            String triggerType,
            String reasonCode,
            String leg,
            String optionType,
            String side,
            BigDecimal strike,
            String instrumentId,
            String symbol,
            String expiryDate,
            BigDecimal marketPrice,
            int oldQuantity,
            int newQuantity,
            int currentTotalLots,
            int maxTotalLots,
            int availableLots,
            BigDecimal delta2m,
            BigDecimal delta5m,
            BigDecimal deltaSod,
            Long currentVolume,
            BigDecimal dayAverageVolume,
            boolean volumeConfirmed,
            boolean volumeBypassed,
            String underlyingDirection,
            String profitAlignment,
            BigDecimal livePnlPoints,
            BigDecimal livePnlChange2mPoints,
            BigDecimal livePnlChange5mPoints,
            BigDecimal netDelta2m,
            BigDecimal netDelta5m,
            BigDecimal netDeltaSod,
            BigDecimal normalizedNetDelta,
            BigDecimal improvementAbsDelta,
            BigDecimal improvementRatio,
            BigDecimal postAdjNetDelta,
            BigDecimal thetaScore,
            BigDecimal liquidityScore,
            BigDecimal score,
            boolean worsening,
            boolean legAsymmetryStress,
            String reason,
            String message,
            String portfolioThetaState,
            BigDecimal portfolioThetaBenefitQty,
            BigDecimal portfolioThetaProgressRatio
    ) {
    }
}
