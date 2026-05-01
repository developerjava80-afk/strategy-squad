package com.strategysquad.agentic.decision;

import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure function that maps a {@link DecisionContext} to a {@link DecisionCommand}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>No I/O — no DB access, no HTTP, no filesystem reads.</li>
 *   <li>No mutable state — every call is fully determined by the supplied context.</li>
 *   <li>Every returned command has a non-blank {@code reasonCode} and
 *       {@code explanation}.</li>
 *   <li>Rules are evaluated in strict priority order (Rule 1 is highest).</li>
 * </ul>
 *
 * <h2>Rule priority order</h2>
 * <ol>
 *   <li>No active session and no qualified candidates → emit {@code SKIP}.</li>
 *   <li>No active session + qualified candidates available
 *       → emit {@code ENTER} for top-ranked qualified candidate.</li>
 *   <li>Active session + {@code thetaProgressRatio >= BOOKING_THRESHOLD} + positive
 *       PnL → emit {@code BOOK_PROFIT}.</li>
 *   <li>Active session + delta-adjusted theta negative (premium expanding) →
 *       emit {@code REDUCE}.</li>
 *   <li>Active session + all available signals are {@code HOLD}
 *       → emit {@code HOLD}.</li>
 *   <li>Default → emit {@code HOLD} (conservative fallback).</li>
 * </ol>
 */
public class DecisionPolicy {

    // -------------------------------------------------------------------------
    // Threshold constants — never inline as magic numbers
    // -------------------------------------------------------------------------

    /**
     * Minimum {@code theta_progress_ratio} required to trigger a profit-booking
     * command (Rule 7, implemented in S2C).
     * A ratio of 0.75 means 75 % of the expected theta decay since entry has been
     * realised, making profit-booking appropriate.
     */
    public static final double BOOKING_THRESHOLD = 0.75;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Evaluate the supplied context and return exactly one {@link DecisionCommand}.
     *
     * <p>Rules are tested in the declared priority order. The first rule that
     * matches produces the return value; no subsequent rules are tested.
     *
     * @param ctx the assembled decision context for this cycle — must not be null
     * @return a fully-populated, non-null {@link DecisionCommand}
     */
    public DecisionCommand evaluate(DecisionContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("DecisionContext must not be null");
        // -----------------------------------------------------------------
        // Rule 1: No active session and no qualified candidates → SKIP
        // -----------------------------------------------------------------
        if (ctx.activeSession().isEmpty() && qualifiedCandidates(ctx).isEmpty()) {
            return command(ctx,
                    DecisionCommand.CommandType.SKIP,
                    "NO_QUALIFIED_CANDIDATES",
                    "No active session and no qualified scanner candidates available "
                            + "this cycle — skipping.",
                    false);
        }

        // -----------------------------------------------------------------
        // Rule 2: No active session + qualified candidates available
        //         → ENTER for top-ranked qualified candidate.
        // -----------------------------------------------------------------
        List<CandidateOpportunity> qualified = qualifiedCandidates(ctx);
        if (ctx.activeSession().isEmpty() && !qualified.isEmpty()) {
            CandidateOpportunity top = qualified.get(0);
            return new DecisionCommand(
                    UUID.randomUUID(),
                    Instant.now(),
                    ctx.mode(),
                    DecisionCommand.CommandType.ENTER,
                    List.of(top.candidateId()),
                    Optional.empty(),   // no session open yet
                    "PREMIUM_RICH_LOW_DELTA_ENTER",
                    String.format(
                            "No active session. Top candidate %s (%s %s, score %.2f) "
                                    + "is qualified — entering.",
                            top.instrumentId(),
                            top.underlying(),
                            top.optionType(),
                            top.totalScore()),
                    ctx.riskGuardSnapshot().decision(),
                    false
            );
        }

        // -----------------------------------------------------------------
        // Rule 3: Active session + theta_progress_ratio >= BOOKING_THRESHOLD
        //         + positive PnL → BOOK_PROFIT.
        // Guard: BOOK_PROFIT must never be emitted when PnL is negative.
        // -----------------------------------------------------------------
        if (ctx.activeSession().isPresent() && ctx.livePnl() > 0.0) {

            double maxRatio = maxThetaProgressRatio(ctx);
            if (maxRatio >= BOOKING_THRESHOLD) {
                return command(ctx,
                        DecisionCommand.CommandType.BOOK_PROFIT,
                        "THETA_PROGRESS_BOOKING_THRESHOLD_MET",
                        String.format(
                                "Active session. Theta progress ratio %.3f >= booking "
                                        + "threshold %.2f and live PnL %.2f pts is positive "
                                        + "— booking profit.",
                                maxRatio,
                                BOOKING_THRESHOLD,
                                ctx.livePnl()),
                        false);
            }
        }

        // -----------------------------------------------------------------
        // Rule 4: Active session + delta-adjusted theta negative for any leg
        //         (premium expanding beyond delta) → REDUCE.
        // -----------------------------------------------------------------
        if (ctx.activeSession().isPresent() && isDeltaAdjustedThetaNegative(ctx)) {
            return command(ctx,
                    DecisionCommand.CommandType.REDUCE,
                    "DELTA_ADJUSTED_THETA_NEGATIVE",
                    "Active session. One or more legs have negative delta-adjusted theta "
                            + "(premium expanding faster than delta explains) — reducing position.",
                    false);
        }

        // -----------------------------------------------------------------
        // Rule 5: Active session + all available signals are HOLD state → HOLD.
        // -----------------------------------------------------------------
        if (ctx.activeSession().isPresent() && allSignalsHold(ctx)) {
            return command(ctx,
                    DecisionCommand.CommandType.HOLD,
                    "ALL_SIGNALS_HOLD",
                    "Active session. All available signal snapshots indicate HOLD — "
                            + "maintaining current position.",
                    false);
        }

        // -----------------------------------------------------------------
        // Rule 6: Default → HOLD.
        // Conservative fallback for all remaining combinations.
        // -----------------------------------------------------------------
        return command(ctx,
                DecisionCommand.CommandType.HOLD,
                "DEFAULT_HOLD",
                "No specific condition matched — defaulting to HOLD. "
                        + "Session active: " + ctx.activeSession().isPresent() + ".",
                false);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the subset of {@code rankedCandidates} in {@code ctx} that are
     * <em>qualified</em> — i.e., their {@code disqualifierReason} is empty.
     */
    private List<CandidateOpportunity> qualifiedCandidates(DecisionContext ctx) {
        return ctx.rankedCandidates().stream()
                .filter(c -> c.disqualifierReason().isEmpty())
                .toList();
    }

    /**
     * Returns the maximum {@code thetaProgressRatio} across all signal snapshots
     * present in the context.
     *
     * <p>Returns {@code 0.0} when the signal map is empty or all ratios are null,
     * so the caller does not need a null-guard.
     */
    private double maxThetaProgressRatio(DecisionContext ctx) {
        return ctx.signalSnapshots().values().stream()
                .map(SignalSnapshot::thetaProgressRatio)
                .filter(r -> r != null)
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0.0);
    }

    /**
     * Returns {@code true} when at least one signal snapshot in the context has a
     * negative {@code deltaAdjustedTheta2m} value, indicating that a leg's option
     * premium is expanding beyond what the delta component explains — adverse for
     * a short-option structure.
     *
     * <p>Snapshots with a {@code null} {@code deltaAdjustedTheta2m} (insufficient
     * data) are ignored; only snapshots with a definite negative value trigger Rule 8.
     */
    private boolean isDeltaAdjustedThetaNegative(DecisionContext ctx) {
        return ctx.signalSnapshots().values().stream()
                .map(SignalSnapshot::deltaAdjustedTheta2m)
                .filter(d -> d != null)
                .anyMatch(d -> d.compareTo(BigDecimal.ZERO) < 0);
    }

    /**
     * Returns {@code true} when every signal snapshot in the context has
     * {@link SignalSnapshot.ThetaState#HOLD} theta state.
     *
     * <p>Returns {@code true} when the signal map is empty (no signals → no
     * signal contradicts HOLD, so the policy conservatively assumes HOLD is safe
     * and lets Rule 10 handle it; in practice Rule 9 requires an active session
     * which provides context for the caller to verify intent).
     */
    private boolean allSignalsHold(DecisionContext ctx) {
        return ctx.signalSnapshots().values().stream()
                .allMatch(s -> s.thetaState() == SignalSnapshot.ThetaState.HOLD);
    }

    /**
     * Builds a {@link DecisionCommand} from the supplied context and fields,
     * setting {@code positionSessionId} from the active session when present.
     *
     * @param ctx                  the decision context
     * @param type                 the resolved command type
     * @param reasonCode           machine-readable reason code (never blank)
     * @param explanation          trader-readable explanation (never blank)
        * @param overriddenByRiskGuard compatibility audit flag; currently always false
     * @return a fully-populated, immutable {@link DecisionCommand}
     */
    private DecisionCommand command(
            DecisionContext ctx,
            DecisionCommand.CommandType type,
            String reasonCode,
            String explanation,
            boolean overriddenByRiskGuard) {

        Optional<String> sessionId = ctx.activeSession()
                .map(s -> s.sessionId());

        return new DecisionCommand(
                UUID.randomUUID(),
                Instant.now(),
                ctx.mode(),
                type,
                List.of(),
                sessionId,
                reasonCode,
                explanation,
                ctx.riskGuardSnapshot().decision(),
                overriddenByRiskGuard
        );
    }
}
