package com.strategysquad.agentic.builder;

import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Converts a ranked candidate list into a {@link PositionPlan} for a short
 * straddle structure.
 *
 * <h2>Phase 3 scope — SHORT_STRADDLE only</h2>
 * <p>This session (S3A) implements the {@code SHORT_STRADDLE} structure: one CE and
 * one PE at the same strike, chosen as the ATM strike with the highest combined
 * premium richness score. {@code SHORT_STRANGLE} support (different CE/PE strikes)
 * is added in Session S3B.
 *
 * <h2>Construction rules</h2>
 * <ol>
 *   <li>Lot sizes are read from {@code instrument_master} via the injected
 *       {@link LotSizeLoader} — they are never hard-coded.</li>
 *   <li>Total lots across all legs must not exceed {@link #maxLotCap}.</li>
 *   <li>The proposed structure starts at {@code 1 lot per leg}.</li>
 *   <li>Net delta of the proposed structure must satisfy
 *       {@code |estimatedNetDelta| <= maxNetDeltaThreshold}.</li>
 *   <li>Net delta is computed from empirical deltas in {@code SignalSnapshot},
 *       not from Black-Scholes theory.</li>
 *   <li>For a short straddle, the selected CE and PE must share the same strike.
 *       The pair maximising the combined {@code totalScore} is preferred, subject
 *       to the delta threshold.</li>
 *   <li>If no valid plan can be constructed, a rejected {@link PositionPlan} is
 *       returned with a machine-readable {@code rejectionReason}.</li>
 * </ol>
 *
 * <h2>Delta convention</h2>
 * <p>For a short straddle the net delta contribution from each leg is:
 * <pre>
 *   CE leg:  +empiricalDelta_CE * lots   (CE price rises when underlying rises →
 *                                          positive empirical delta for long owner,
 *                                          so short position has positive delta response)
 *   PE leg:  +empiricalDelta_PE * lots   (PE price falls when underlying rises →
 *                                          negative empirical delta for long owner,
 *                                          so short PE response is negative)
 *   net = empiricalDelta_CE + empiricalDelta_PE (both lots = 1, signs already encoded)
 * </pre>
 * <p>If empirical delta is unavailable for a leg ({@code null} in the snapshot),
 * the builder uses {@code 0.0} as a conservative fallback and logs a warning.
 * The plan is still accepted if the resulting delta is within threshold.
 *
 * <h2>Simulation-first</h2>
 * <p>This agent does not call any broker order API. It produces a plan for the
 * Decision Agent to act on — it never applies any position mutation itself.
 *
 * <h2>One structure at a time</h2>
 * <p>Multi-structure orchestration is explicitly out of scope until the single-
 * structure loop is validated across five simulation replays (see
 * {@code developer-notes.md}).
 */
public class PositionBuilderAgent {

    private static final Logger LOG = Logger.getLogger(PositionBuilderAgent.class.getName());

    // =========================================================================
    // Configuration constants
    // =========================================================================

    /** Structure type label for a short straddle (same CE + PE strike). */
    public static final String STRUCTURE_SHORT_STRADDLE = "SHORT_STRADDLE";

    /** Structure type label for a short strangle (different CE + PE strikes, each chosen by scanner rank). */
    public static final String STRUCTURE_SHORT_STRANGLE = "SHORT_STRANGLE";

    /**
     * Default maximum |net delta| allowed for an accepted plan.
     * Plans whose estimated net delta exceeds this threshold are rejected.
     * Configurable at construction time.
     */
    public static final double DEFAULT_MAX_NET_DELTA_THRESHOLD = 0.15;

    /**
     * Default maximum total lot count across all legs.
     * A structure with 1 lot per leg and 2 legs requires at most 2 total lots.
     * Configurable at construction time.
     */
    public static final int DEFAULT_MAX_LOT_CAP = 4;

    /**
     * Starting lot count per leg. Phase 3 always starts at 1 lot (minimum entry).
     * Scaling up is handled by the Adjustment Agent after entry.
     */
    private static final int INITIAL_LOTS_PER_LEG = 1;

    // =========================================================================
    // Functional interface seams — allow test injection
    // =========================================================================

    /**
     * Loads the lot size for a given underlying from {@code instrument_master}.
     *
     * <p>The standard production implementation executes:
     * <pre>
     *   SELECT lot_size FROM instrument_master
     *   WHERE underlying = ? AND is_active = true
     *   LIMIT 1
     * </pre>
     * Tests inject a stub (e.g., {@code underlying -> underlying.equals("NIFTY") ? 65 : 30})
     * so no live DB connection is required.
     */
    @FunctionalInterface
    public interface LotSizeLoader {
        /**
         * Returns the lot size for the given underlying index.
         *
         * @param underlying the underlying name (e.g., {@code "NIFTY"})
         * @return lot size (e.g., 65 for NIFTY, 30 for BANKNIFTY)
         * @throws Exception if the lookup fails
         */
        int load(String underlying) throws Exception;
    }

    // =========================================================================
    // State
    // =========================================================================

    private final LotSizeLoader lotSizeLoader;
    private final double maxNetDeltaThreshold;
    private final int maxLotCap;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a {@code PositionBuilderAgent} with all configuration injected.
     *
     * @param lotSizeLoader        loads lot size per underlying from instrument_master
     * @param maxNetDeltaThreshold maximum allowed |net delta| for an accepted plan
     *                             (e.g., 0.15); must be > 0
     * @param maxLotCap            maximum total lots across all legs; must be >= 2
     *                             (at least one lot per side of a two-legged structure)
     */
    public PositionBuilderAgent(
            LotSizeLoader lotSizeLoader,
            double maxNetDeltaThreshold,
            int maxLotCap) {

        this.lotSizeLoader = Objects.requireNonNull(lotSizeLoader, "lotSizeLoader must not be null");
        if (maxNetDeltaThreshold <= 0.0) {
            throw new IllegalArgumentException(
                    "maxNetDeltaThreshold must be > 0, got: " + maxNetDeltaThreshold);
        }
        if (maxLotCap < 2) {
            throw new IllegalArgumentException(
                    "maxLotCap must be >= 2 for a two-legged structure, got: " + maxLotCap);
        }
        this.maxNetDeltaThreshold = maxNetDeltaThreshold;
        this.maxLotCap = maxLotCap;
    }

    /**
     * Convenience constructor using default thresholds.
     *
     * @param lotSizeLoader loads lot size per underlying from instrument_master
     */
    public PositionBuilderAgent(LotSizeLoader lotSizeLoader) {
        this(lotSizeLoader, DEFAULT_MAX_NET_DELTA_THRESHOLD, DEFAULT_MAX_LOT_CAP);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Attempts to build a {@link PositionPlan} for a {@code SHORT_STRADDLE} from
     * the provided ranked candidate list.
     *
     * <p>The method is deterministic: given the same candidates and signal snapshots,
     * it will always produce the same plan. It does not call any broker API or mutate
     * any position state.
     *
     * @param underlying      the underlying index to build for ({@code NIFTY} or
     *                        {@code BANKNIFTY}); must not be null
     * @param rankedCandidates ranked list of scanner candidates (may include
     *                        disqualified entries at the bottom); must not be null
     * @param signalSnapshots  map of {@code instrument_id → SignalSnapshot} for
     *                        empirical delta lookup; empty map is safe (delta defaults to 0)
     * @param planTs           UTC instant at which the plan is being built
     * @return an accepted plan with two legs (CE + PE) or a rejected plan with a
     *         {@code rejectionReason}; never null
     */
    public PositionPlan buildShortStraddle(
            String underlying,
            List<CandidateOpportunity> rankedCandidates,
            Map<String, SignalSnapshot> signalSnapshots,
            Instant planTs) {

        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(rankedCandidates, "rankedCandidates must not be null");
        Objects.requireNonNull(signalSnapshots, "signalSnapshots must not be null");
        Objects.requireNonNull(planTs, "planTs must not be null");

        // ------------------------------------------------------------------
        // Step 1 — Check that there are any qualified candidates at all
        // ------------------------------------------------------------------
        List<CandidateOpportunity> qualified = rankedCandidates.stream()
                .filter(c -> c.disqualifierReason().isEmpty())
                .toList();

        if (qualified.isEmpty()) {
            LOG.warning("[PositionBuilderAgent] No qualified candidates for " + underlying +
                        " — returning rejected plan");
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRADDLE,
                    "NO_QUALIFIED_CANDIDATES");
        }

        // ------------------------------------------------------------------
        // Step 2 — Check total lot cap (2 legs × INITIAL_LOTS_PER_LEG)
        // ------------------------------------------------------------------
        int totalLotsNeeded = 2 * INITIAL_LOTS_PER_LEG;
        if (totalLotsNeeded > maxLotCap) {
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRADDLE,
                    "LOT_CAP_EXCEEDED");
        }

        // ------------------------------------------------------------------
        // Step 3 — Load lot size from instrument_master (via injected loader)
        // ------------------------------------------------------------------
        int lotSize;
        try {
            lotSize = lotSizeLoader.load(underlying);
        } catch (Exception e) {
            LOG.warning("[PositionBuilderAgent] LotSizeLoader failed for " + underlying +
                        ": " + e.getMessage() + " — returning rejected plan");
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRADDLE,
                    "LOT_SIZE_LOOKUP_FAILED");
        }
        if (lotSize < 1) {
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRADDLE,
                    "INVALID_LOT_SIZE");
        }

        // ------------------------------------------------------------------
        // Step 4 — Find the best strike pair (same strike, CE + PE)
        //          Strategy: group qualified candidates by strike, then for each
        //          strike that has both a CE and PE, compute the combined totalScore.
        //          Choose the strike with the highest combined score.
        // ------------------------------------------------------------------

        // Collect all strikes that have at least one qualified CE and one qualified PE
        List<BigDecimal> straddleStrikes = qualified.stream()
                .map(CandidateOpportunity::strike)
                .distinct()
                .filter(strike -> hasQualifiedLeg(qualified, strike, "CE")
                               && hasQualifiedLeg(qualified, strike, "PE"))
                .toList();

        if (straddleStrikes.isEmpty()) {
            // Could be that we have only CEs or only PEs at each strike
            boolean hasCe = qualified.stream().anyMatch(c -> "CE".equals(c.optionType()));
            boolean hasPe = qualified.stream().anyMatch(c -> "PE".equals(c.optionType()));

            if (!hasCe) {
                return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRADDLE,
                        "MISSING_CE_CANDIDATE");
            }
            if (!hasPe) {
                return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRADDLE,
                        "MISSING_PE_CANDIDATE");
            }
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRADDLE,
                    "NO_MATCHING_STRIKE_PAIR");
        }

        // ------------------------------------------------------------------
        // Step 5 — Score each available straddle strike and pick the best one
        //          that satisfies the delta threshold
        // ------------------------------------------------------------------

        // Sort strikes by combined score (descending) so the best is tried first
        List<BigDecimal> rankedStrikes = straddleStrikes.stream()
                .sorted(Comparator.comparingDouble(
                        (BigDecimal strike) -> combinedScore(qualified, strike)).reversed())
                .toList();

        for (BigDecimal candidateStrike : rankedStrikes) {
            CandidateOpportunity ceLeg = bestLegForStrike(qualified, candidateStrike, "CE");
            CandidateOpportunity peLeg = bestLegForStrike(qualified, candidateStrike, "PE");

            if (ceLeg == null || peLeg == null) {
                continue; // should not happen given the filter above, but defensive
            }

            // ----------------------------------------------------------
            // Step 5a — Compute estimated net delta using empirical delta
            // ----------------------------------------------------------
            double ceDeltaEmpirical = empiricalDelta(ceLeg.instrumentId(), signalSnapshots);
            double peDeltaEmpirical = empiricalDelta(peLeg.instrumentId(), signalSnapshots);

            // For a short straddle both legs are SHORT.
            // Empirical delta for a long option owner:
            //   CE: positive (option rises when underlying rises)
            //   PE: negative (option falls when underlying rises)
            // As a SHORT position, the PnL moves opposite — but for net delta
            // of the STRUCTURE, we track the underlying exposure:
            //   netDelta = (ceDeltaEmpirical + peDeltaEmpirical) * lots
            // A near-zero net delta means the CE and PE deltas roughly cancel.
            double netDelta = (ceDeltaEmpirical + peDeltaEmpirical) * INITIAL_LOTS_PER_LEG;

            if (Math.abs(netDelta) > maxNetDeltaThreshold) {
                LOG.info("[PositionBuilderAgent] Strike " + candidateStrike +
                         " rejected: |netDelta|=" + Math.abs(netDelta) +
                         " exceeds threshold " + maxNetDeltaThreshold);
                continue; // try next strike
            }

            // ----------------------------------------------------------
            // Step 5b — Build the accepted plan
            // ----------------------------------------------------------
            double totalPremium = (ceLeg.lastPrice().doubleValue() +
                                   peLeg.lastPrice().doubleValue())
                                  * INITIAL_LOTS_PER_LEG * lotSize;

            PositionPlanLeg cePlanLeg = new PositionPlanLeg(
                    ceLeg.instrumentId(),
                    "CE",
                    ceLeg.strike(),
                    ceLeg.expiryDate(),
                    lotSize,
                    INITIAL_LOTS_PER_LEG,
                    ceLeg.lastPrice(),
                    PositionPlanLeg.Side.SHORT
            );

            PositionPlanLeg pePlanLeg = new PositionPlanLeg(
                    peLeg.instrumentId(),
                    "PE",
                    peLeg.strike(),
                    peLeg.expiryDate(),
                    lotSize,
                    INITIAL_LOTS_PER_LEG,
                    peLeg.lastPrice(),
                    PositionPlanLeg.Side.SHORT
            );

            LOG.info("[PositionBuilderAgent] Accepted SHORT_STRADDLE plan for " + underlying +
                     " at strike " + candidateStrike +
                     " | netDelta=" + netDelta +
                     " | totalPremium=" + totalPremium + " pts");

            return new PositionPlan(
                    UUID.randomUUID(),
                    planTs,
                    underlying,
                    List.of(cePlanLeg, pePlanLeg),
                    netDelta,
                    totalPremium,
                    STRUCTURE_SHORT_STRADDLE,
                    INITIAL_LOTS_PER_LEG,
                    true,
                    Optional.empty()
            );
        }

        // All candidate strikes were rejected due to delta threshold
        return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRADDLE,
                "NET_DELTA_EXCEEDS_THRESHOLD");
    }

    /**
     * Attempts to build a {@link PositionPlan} for a {@code SHORT_STRANGLE} from
     * the provided ranked candidate list.
     *
     * <p>Unlike a straddle, a strangle picks the best-ranked CE and the best-ranked
     * PE <em>independently</em> — they must be at <em>different</em> strikes. The
     * pair is chosen by scanner {@code totalScore}: highest CE score + highest PE
     * score with a different strike, subject to the net delta threshold.
     *
     * @param underlying      the underlying index ({@code NIFTY} or {@code BANKNIFTY})
     * @param rankedCandidates ranked scanner candidates (may include disqualified entries)
     * @param signalSnapshots  map of {@code instrument_id → SignalSnapshot} for delta lookup
     * @param planTs           UTC instant at which the plan is being built
     * @return an accepted plan with two legs at different strikes, or a rejected plan
     */
    public PositionPlan buildShortStrangle(
            String underlying,
            List<CandidateOpportunity> rankedCandidates,
            Map<String, SignalSnapshot> signalSnapshots,
            Instant planTs) {

        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(rankedCandidates, "rankedCandidates must not be null");
        Objects.requireNonNull(signalSnapshots, "signalSnapshots must not be null");
        Objects.requireNonNull(planTs, "planTs must not be null");

        // ------------------------------------------------------------------
        // Step 1 — Check that there are any qualified candidates at all
        // ------------------------------------------------------------------
        List<CandidateOpportunity> qualified = rankedCandidates.stream()
                .filter(c -> c.disqualifierReason().isEmpty())
                .toList();

        if (qualified.isEmpty()) {
            LOG.warning("[PositionBuilderAgent] No qualified candidates for " + underlying +
                        " — returning rejected strangle plan");
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRANGLE,
                    "NO_QUALIFIED_CANDIDATES");
        }

        // ------------------------------------------------------------------
        // Step 2 — Check total lot cap (2 legs × INITIAL_LOTS_PER_LEG)
        // ------------------------------------------------------------------
        int totalLotsNeeded = 2 * INITIAL_LOTS_PER_LEG;
        if (totalLotsNeeded > maxLotCap) {
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRANGLE,
                    "LOT_CAP_EXCEEDED");
        }

        // ------------------------------------------------------------------
        // Step 3 — Load lot size from instrument_master (via injected loader)
        // ------------------------------------------------------------------
        int lotSize;
        try {
            lotSize = lotSizeLoader.load(underlying);
        } catch (Exception e) {
            LOG.warning("[PositionBuilderAgent] LotSizeLoader failed for " + underlying +
                        ": " + e.getMessage() + " — returning rejected strangle plan");
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRANGLE,
                    "LOT_SIZE_LOOKUP_FAILED");
        }
        if (lotSize < 1) {
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRANGLE,
                    "INVALID_LOT_SIZE");
        }

        // ------------------------------------------------------------------
        // Step 4 — Separate CE and PE candidates, each ranked by totalScore
        // ------------------------------------------------------------------
        List<CandidateOpportunity> qualifiedCEs = qualified.stream()
                .filter(c -> "CE".equals(c.optionType()))
                .sorted(Comparator.comparingDouble(CandidateOpportunity::totalScore).reversed())
                .toList();

        List<CandidateOpportunity> qualifiedPEs = qualified.stream()
                .filter(c -> "PE".equals(c.optionType()))
                .sorted(Comparator.comparingDouble(CandidateOpportunity::totalScore).reversed())
                .toList();

        if (qualifiedCEs.isEmpty()) {
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRANGLE,
                    "MISSING_CE_CANDIDATE");
        }
        if (qualifiedPEs.isEmpty()) {
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRANGLE,
                    "MISSING_PE_CANDIDATE");
        }

        // ------------------------------------------------------------------
        // Step 5 — Try CE × PE combinations in scanner-rank order.
        //          CE and PE must be at different strikes (strangle invariant).
        //          Pick the first pair that satisfies the delta threshold.
        // ------------------------------------------------------------------
        boolean anyDifferentStrikePair = false;
        for (CandidateOpportunity ceLeg : qualifiedCEs) {
            for (CandidateOpportunity peLeg : qualifiedPEs) {
                if (ceLeg.strike().compareTo(peLeg.strike()) == 0) {
                    continue; // same strike = straddle, not strangle — skip
                }
                anyDifferentStrikePair = true;

                double ceDeltaEmpirical = empiricalDelta(ceLeg.instrumentId(), signalSnapshots);
                double peDeltaEmpirical = empiricalDelta(peLeg.instrumentId(), signalSnapshots);
                double netDelta = (ceDeltaEmpirical + peDeltaEmpirical) * INITIAL_LOTS_PER_LEG;

                if (Math.abs(netDelta) > maxNetDeltaThreshold) {
                    LOG.info("[PositionBuilderAgent] Strangle CE " + ceLeg.strike() +
                             " + PE " + peLeg.strike() +
                             " rejected: |netDelta|=" + Math.abs(netDelta) +
                             " exceeds threshold " + maxNetDeltaThreshold);
                    continue;
                }

                double totalPremium = (ceLeg.lastPrice().doubleValue() +
                                       peLeg.lastPrice().doubleValue())
                                      * INITIAL_LOTS_PER_LEG * lotSize;

                PositionPlanLeg cePlanLeg = new PositionPlanLeg(
                        ceLeg.instrumentId(),
                        "CE",
                        ceLeg.strike(),
                        ceLeg.expiryDate(),
                        lotSize,
                        INITIAL_LOTS_PER_LEG,
                        ceLeg.lastPrice(),
                        PositionPlanLeg.Side.SHORT
                );

                PositionPlanLeg pePlanLeg = new PositionPlanLeg(
                        peLeg.instrumentId(),
                        "PE",
                        peLeg.strike(),
                        peLeg.expiryDate(),
                        lotSize,
                        INITIAL_LOTS_PER_LEG,
                        peLeg.lastPrice(),
                        PositionPlanLeg.Side.SHORT
                );

                LOG.info("[PositionBuilderAgent] Accepted SHORT_STRANGLE plan for " + underlying +
                         " CE=" + ceLeg.strike() + " / PE=" + peLeg.strike() +
                         " | netDelta=" + netDelta +
                         " | totalPremium=" + totalPremium + " pts");

                return new PositionPlan(
                        UUID.randomUUID(),
                        planTs,
                        underlying,
                        List.of(cePlanLeg, pePlanLeg),
                        netDelta,
                        totalPremium,
                        STRUCTURE_SHORT_STRANGLE,
                        INITIAL_LOTS_PER_LEG,
                        true,
                        Optional.empty()
                );
            }
        }

        if (!anyDifferentStrikePair) {
            // Every CE and PE happened to be at the same strike — use straddle instead
            return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRANGLE,
                    "NO_MATCHING_STRANGLE_PAIR");
        }
        return PositionPlan.rejected(underlying, STRUCTURE_SHORT_STRANGLE,
                "NET_DELTA_EXCEEDS_THRESHOLD");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns {@code true} if there is at least one qualified candidate with the
     * given strike and option type.
     */
    private static boolean hasQualifiedLeg(
            List<CandidateOpportunity> qualified,
            BigDecimal strike,
            String optionType) {

        return qualified.stream()
                .anyMatch(c -> c.strike().compareTo(strike) == 0
                            && optionType.equals(c.optionType()));
    }

    /**
     * Computes the combined total score for CE + PE at a given strike.
     * Used to rank candidate straddle strikes; higher is better.
     */
    private static double combinedScore(
            List<CandidateOpportunity> qualified,
            BigDecimal strike) {

        double ceScore = qualified.stream()
                .filter(c -> c.strike().compareTo(strike) == 0 && "CE".equals(c.optionType()))
                .mapToDouble(CandidateOpportunity::totalScore)
                .max()
                .orElse(0.0);

        double peScore = qualified.stream()
                .filter(c -> c.strike().compareTo(strike) == 0 && "PE".equals(c.optionType()))
                .mapToDouble(CandidateOpportunity::totalScore)
                .max()
                .orElse(0.0);

        return ceScore + peScore;
    }

    /**
     * Returns the best qualified candidate for a given strike and option type,
     * ranked by {@code totalScore} descending. Returns {@code null} if none found.
     */
    private static CandidateOpportunity bestLegForStrike(
            List<CandidateOpportunity> qualified,
            BigDecimal strike,
            String optionType) {

        return qualified.stream()
                .filter(c -> c.strike().compareTo(strike) == 0
                          && optionType.equals(c.optionType()))
                .max(Comparator.comparingDouble(CandidateOpportunity::totalScore))
                .orElse(null);
    }

    /**
     * Returns the empirical delta for the given instrument from the signal map.
     *
     * <p>Preference order: {@code empiricalDelta2m} → {@code empiricalDelta5m} →
     * {@code empiricalDeltaSod} → {@code 0.0} (conservative fallback when no data).
     * If the signal is stale, logs a warning but still uses the value — the Risk
     * Guard (Phase 4) handles stale-data hard stops.
     *
     * @param instrumentId the canonical instrument identifier
     * @param signals      the map of available signal snapshots
     * @return empirical delta as a double; 0.0 if no signal is available
     */
    private double empiricalDelta(String instrumentId, Map<String, SignalSnapshot> signals) {
        SignalSnapshot snapshot = signals.get(instrumentId);
        if (snapshot == null) {
            LOG.fine("[PositionBuilderAgent] No signal snapshot for " + instrumentId +
                     " — using empirical delta = 0.0");
            return 0.0;
        }
        if (snapshot.stale()) {
            LOG.warning("[PositionBuilderAgent] Stale signal for " + instrumentId +
                        ": " + snapshot.reason() + " — using delta anyway");
        }
        // Prefer 2m, fallback to 5m, then SOD, then 0.0
        if (snapshot.empiricalDelta2m() != null) {
            return snapshot.empiricalDelta2m().doubleValue();
        }
        if (snapshot.empiricalDelta5m() != null) {
            return snapshot.empiricalDelta5m().doubleValue();
        }
        if (snapshot.empiricalDeltaSod() != null) {
            return snapshot.empiricalDeltaSod().doubleValue();
        }
        LOG.fine("[PositionBuilderAgent] All delta windows null for " + instrumentId +
                 " — using empirical delta = 0.0");
        return 0.0;
    }
}
