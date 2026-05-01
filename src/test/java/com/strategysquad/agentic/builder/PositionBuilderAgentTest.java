package com.strategysquad.agentic.builder;

import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PositionBuilderAgent} — SHORT_STRADDLE selection.
 *
 * <p>All tests are pure in-memory: no database, no HTTP, no file I/O.
 * The {@link PositionBuilderAgent.LotSizeLoader} is stubbed to return fixed
 * values (65 for NIFTY, 30 for BANKNIFTY).
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>Valid straddle — CE and PE at the same strike, both qualified, delta within
 *       threshold → accepted plan with exactly 2 legs, correct structure type,
 *       riskGuardApproved = true.</li>
 *   <li>Delta rejection — empirical delta on the CE leg is large-positive and PE
 *       leg is also positive-biased so their sum exceeds the threshold → rejected
 *       plan with reason {@code NET_DELTA_EXCEEDS_THRESHOLD}.</li>
 *   <li>No candidates — empty candidate list → rejected plan with reason
 *       {@code NO_QUALIFIED_CANDIDATES}.</li>
 *   <li>All candidates disqualified — list contains only disqualified entries →
 *       rejected plan with reason {@code NO_QUALIFIED_CANDIDATES}.</li>
 *   <li>Missing CE — only PE candidates available at every strike → rejected plan
 *       with reason {@code MISSING_CE_CANDIDATE}.</li>
 *   <li>Missing PE — only CE candidates available at every strike → rejected plan
 *       with reason {@code MISSING_PE_CANDIDATE}.</li>
 *   <li>Best strike pair selected — two strikes available; one has higher combined
 *       score → builder selects the higher-scored strike.</li>
 *   <li>Lot size sourced from loader — plan legs carry the lot size returned by
 *       the stub loader (65 for NIFTY).</li>
 * </ol>
 */
class PositionBuilderAgentTest {

    private static final String UNDERLYING = "NIFTY";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 5, 1);
    private static final Instant PLAN_TS = Instant.parse("2026-04-26T09:30:00Z");

    /** Stub lot size loader: NIFTY=65, BANKNIFTY=30. */
    private static final PositionBuilderAgent.LotSizeLoader STUB_LOT_LOADER =
            underlying -> "NIFTY".equals(underlying) ? 65 : 30;

    private PositionBuilderAgent agent;

    @BeforeEach
    void setUp() {
        // Use default thresholds: maxNetDeltaThreshold=0.15, maxLotCap=4
        agent = new PositionBuilderAgent(STUB_LOT_LOADER);
    }

    // =========================================================================
    // Scenario 1 — Valid straddle
    // =========================================================================

    @Test
    void validStraddle_accepted_withTwoLegs() {
        // CE and PE at strike 24800 — delta-neutral pair
        CandidateOpportunity ce = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("150.0"), 0.75, false);
        CandidateOpportunity pe = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("145.0"), 0.72, false);

        // Signal snapshots: CE delta = +0.08, PE delta = -0.07 → net = 0.01 (well within 0.15)
        Map<String, SignalSnapshot> signals = Map.of(
                "INS_NIFTY_20260501_24800_CE", signal("INS_NIFTY_20260501_24800_CE", "CE", "0.08"),
                "INS_NIFTY_20260501_24800_PE", signal("INS_NIFTY_20260501_24800_PE", "PE", "-0.07")
        );

        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(ce, pe), signals, PLAN_TS);

        assertTrue(plan.riskGuardApproved(), "Plan should be accepted");
        assertTrue(plan.rejectionReason().isEmpty(), "No rejection reason for accepted plan");
        assertEquals(PositionBuilderAgent.STRUCTURE_SHORT_STRADDLE, plan.structureType());
        assertEquals(UNDERLYING, plan.underlying());
        assertEquals(2, plan.legs().size(), "Straddle must have exactly 2 legs");

        PositionPlanLeg ceLeg = legByType(plan, "CE");
        PositionPlanLeg peLeg = legByType(plan, "PE");

        assertNotNull(ceLeg, "CE leg must be present");
        assertNotNull(peLeg, "PE leg must be present");
        assertEquals(bd("24800"), ceLeg.strike(), "CE leg strike must match candidate");
        assertEquals(bd("24800"), peLeg.strike(), "PE leg strike must match candidate");
        assertEquals(65, ceLeg.lotSize(), "Lot size must come from loader (NIFTY=65)");
        assertEquals(65, peLeg.lotSize(), "Lot size must come from loader (NIFTY=65)");
        assertEquals(1, ceLeg.lots(), "Phase 3 always starts at 1 lot");
        assertEquals(1, peLeg.lots(), "Phase 3 always starts at 1 lot");
        assertEquals(PositionPlanLeg.Side.SHORT, ceLeg.side(), "All legs are SHORT");
        assertEquals(PositionPlanLeg.Side.SHORT, peLeg.side(), "All legs are SHORT");
        assertEquals(bd("150.0"), ceLeg.entryPrice(), "CE entry price from candidate lastPrice");
        assertEquals(bd("145.0"), peLeg.entryPrice(), "PE entry price from candidate lastPrice");
        assertEquals(1, plan.lotCountPerLeg());

        // Net delta = (0.08 + (-0.07)) * 1 = 0.01
        assertEquals(0.01, plan.estimatedNetDelta(), 1e-9, "Net delta should be ~0.01");

        // Total premium = (150 + 145) * 1 lot * 65 = 295 * 65 = 19175
        assertEquals(19175.0, plan.estimatedTotalPremium(), 1e-6,
                "Total premium should be (CE+PE) * lots * lotSize");
    }

    @Test
    void validStraddle_planId_isNonNull() {
        CandidateOpportunity ce = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("100.0"), 0.6, false);
        CandidateOpportunity pe = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("98.0"), 0.58, false);

        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(ce, pe), Collections.emptyMap(), PLAN_TS);

        assertTrue(plan.riskGuardApproved());
        assertNotNull(plan.planId(), "planId must not be null");
        assertNotNull(plan.plannedTs(), "plannedTs must not be null");
    }

    // =========================================================================
    // Scenario 2 — Delta rejection
    // =========================================================================

    @Test
    void deltaRejection_whenNetDeltaExceedsThreshold() {
        // CE delta = +0.20, PE delta = +0.10 → net = 0.30 > 0.15 threshold
        CandidateOpportunity ce = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("150.0"), 0.75, false);
        CandidateOpportunity pe = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("145.0"), 0.72, false);

        Map<String, SignalSnapshot> signals = Map.of(
                "INS_NIFTY_20260501_24800_CE", signal("INS_NIFTY_20260501_24800_CE", "CE", "0.20"),
                "INS_NIFTY_20260501_24800_PE", signal("INS_NIFTY_20260501_24800_PE", "PE", "0.10")
        );

        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(ce, pe), signals, PLAN_TS);

        assertFalse(plan.riskGuardApproved(), "Plan must be rejected when delta exceeds threshold");
        assertTrue(plan.rejectionReason().isPresent());
        assertEquals("NET_DELTA_EXCEEDS_THRESHOLD", plan.rejectionReason().get());
        assertTrue(plan.legs().isEmpty(), "Rejected plan must have no legs");
    }

    @Test
    void deltaRejection_secondStrikePicked_whenFirstExceedsThreshold() {
        // Strike 24800: CE=+0.20, PE=+0.10 → net=0.30, rejected
        // Strike 24900: CE=+0.06, PE=-0.05 → net=0.01, accepted
        CandidateOpportunity ce1 = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("150.0"), 0.75, false);
        CandidateOpportunity pe1 = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("145.0"), 0.72, false);
        CandidateOpportunity ce2 = candidate("INS_NIFTY_20260501_24900_CE", "CE",
                bd("24900"), bd("80.0"), 0.60, false);
        CandidateOpportunity pe2 = candidate("INS_NIFTY_20260501_24900_PE", "PE",
                bd("24900"), bd("78.0"), 0.58, false);

        Map<String, SignalSnapshot> signals = Map.of(
                "INS_NIFTY_20260501_24800_CE", signal("INS_NIFTY_20260501_24800_CE", "CE", "0.20"),
                "INS_NIFTY_20260501_24800_PE", signal("INS_NIFTY_20260501_24800_PE", "PE", "0.10"),
                "INS_NIFTY_20260501_24900_CE", signal("INS_NIFTY_20260501_24900_CE", "CE", "0.06"),
                "INS_NIFTY_20260501_24900_PE", signal("INS_NIFTY_20260501_24900_PE", "PE", "-0.05")
        );

        // 24800 has higher combined score (0.75+0.72=1.47) vs 24900 (0.60+0.58=1.18)
        // so 24800 is tried first, fails, then 24900 is picked
        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(ce1, pe1, ce2, pe2), signals, PLAN_TS);

        assertTrue(plan.riskGuardApproved(), "Second strike should be accepted");
        assertEquals(2, plan.legs().size());
        // Should pick strike 24900
        assertEquals(bd("24900"), plan.legs().get(0).strike(),
                "Should fall back to 24900 strike");
    }

    // =========================================================================
    // Scenario 3 — No candidates
    // =========================================================================

    @Test
    void noCandidates_rejected_withCorrectReason() {
        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                Collections.emptyList(), Collections.emptyMap(), PLAN_TS);

        assertFalse(plan.riskGuardApproved());
        assertTrue(plan.rejectionReason().isPresent());
        assertEquals("NO_QUALIFIED_CANDIDATES", plan.rejectionReason().get());
        assertTrue(plan.legs().isEmpty());
        assertEquals(UNDERLYING, plan.underlying());
        assertEquals(PositionBuilderAgent.STRUCTURE_SHORT_STRADDLE, plan.structureType());
    }

    @Test
    void allDisqualified_rejected_withNoQualifiedCandidatesReason() {
        // Both candidates are disqualified (ZERO_BID)
        CandidateOpportunity ce = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("0.0"), 0.0, true);
        CandidateOpportunity pe = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("0.0"), 0.0, true);

        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(ce, pe), Collections.emptyMap(), PLAN_TS);

        assertFalse(plan.riskGuardApproved());
        assertEquals("NO_QUALIFIED_CANDIDATES", plan.rejectionReason().orElse(""));
    }

    // =========================================================================
    // Scenario 4 — Missing CE or PE at every strike
    // =========================================================================

    @Test
    void missingCe_rejected_withMissingCeReason() {
        // Only PE candidates — no CE
        CandidateOpportunity pe = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("145.0"), 0.72, false);

        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(pe), Collections.emptyMap(), PLAN_TS);

        assertFalse(plan.riskGuardApproved());
        assertEquals("MISSING_CE_CANDIDATE", plan.rejectionReason().orElse(""));
    }

    @Test
    void missingPe_rejected_withMissingPeReason() {
        // Only CE candidates — no PE
        CandidateOpportunity ce = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("150.0"), 0.75, false);

        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(ce), Collections.emptyMap(), PLAN_TS);

        assertFalse(plan.riskGuardApproved());
        assertEquals("MISSING_PE_CANDIDATE", plan.rejectionReason().orElse(""));
    }

    // =========================================================================
    // Scenario 5 — Best strike pair selected
    // =========================================================================

    @Test
    void bestStrikePairSelected_byHighestCombinedScore() {
        // Strike 24800: CE=0.80, PE=0.78 → combined=1.58 (higher)
        // Strike 24900: CE=0.55, PE=0.50 → combined=1.05 (lower)
        CandidateOpportunity ce1 = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("150.0"), 0.80, false);
        CandidateOpportunity pe1 = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("148.0"), 0.78, false);
        CandidateOpportunity ce2 = candidate("INS_NIFTY_20260501_24900_CE", "CE",
                bd("24900"), bd("90.0"), 0.55, false);
        CandidateOpportunity pe2 = candidate("INS_NIFTY_20260501_24900_PE", "PE",
                bd("24900"), bd("88.0"), 0.50, false);

        // All deltas neutral
        Map<String, SignalSnapshot> signals = Map.of(
                "INS_NIFTY_20260501_24800_CE", signal("INS_NIFTY_20260501_24800_CE", "CE", "0.07"),
                "INS_NIFTY_20260501_24800_PE", signal("INS_NIFTY_20260501_24800_PE", "PE", "-0.06"),
                "INS_NIFTY_20260501_24900_CE", signal("INS_NIFTY_20260501_24900_CE", "CE", "0.05"),
                "INS_NIFTY_20260501_24900_PE", signal("INS_NIFTY_20260501_24900_PE", "PE", "-0.04")
        );

        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(ce1, pe1, ce2, pe2), signals, PLAN_TS);

        assertTrue(plan.riskGuardApproved(), "Plan should be accepted");
        assertEquals(bd("24800"), plan.legs().get(0).strike(),
                "Should select the higher-scored 24800 strike");
    }

    // =========================================================================
    // Scenario 6 — No signal — uses delta = 0.0 fallback
    // =========================================================================

    @Test
    void noSignalSnapshot_usesZeroDeltaFallback_acceptsPlan() {
        CandidateOpportunity ce = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("150.0"), 0.75, false);
        CandidateOpportunity pe = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("145.0"), 0.72, false);

        // No signals — agent should use 0.0 for both legs → net delta = 0.0
        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                List.of(ce, pe), Collections.emptyMap(), PLAN_TS);

        assertTrue(plan.riskGuardApproved(),
                "Plan should be accepted when signals are absent (delta defaults to 0)");
        assertEquals(0.0, plan.estimatedNetDelta(), 1e-9,
                "Net delta should be 0.0 when no signals are available");
    }

    // =========================================================================
    // Scenario 7 — BANKNIFTY lot size
    // =========================================================================

    @Test
    void bankniftyLotSize_isThirty() {
        String bnUnderlying = "BANKNIFTY";
        CandidateOpportunity ce = candidate("INS_BANKNIFTY_20260501_52000_CE", "CE",
                bd("52000"), bd("200.0"), 0.70, false);
        CandidateOpportunity pe = candidate("INS_BANKNIFTY_20260501_52000_PE", "PE",
                bd("52000"), bd("195.0"), 0.68, false);

        PositionPlan plan = agent.buildShortStraddle(bnUnderlying,
                List.of(ce, pe), Collections.emptyMap(), PLAN_TS);

        assertTrue(plan.riskGuardApproved(), "BANKNIFTY plan should be accepted");
        assertEquals(30, legByType(plan, "CE").lotSize(),
                "BANKNIFTY CE lot size must be 30");
        assertEquals(30, legByType(plan, "PE").lotSize(),
                "BANKNIFTY PE lot size must be 30");
    }

    // =========================================================================
    // Scenario 8 — Rejected plan structural invariants
    // =========================================================================

    @Test
    void rejectedPlan_hasEmptyLegs_andZeroMetrics() {
        PositionPlan plan = agent.buildShortStraddle(UNDERLYING,
                Collections.emptyList(), Collections.emptyMap(), PLAN_TS);

        assertFalse(plan.riskGuardApproved());
        assertTrue(plan.legs().isEmpty(), "Rejected plan legs must be empty");
        assertEquals(0.0, plan.estimatedNetDelta(), 1e-9,
                "Rejected plan net delta must be 0.0");
        assertEquals(0.0, plan.estimatedTotalPremium(), 1e-9,
                "Rejected plan total premium must be 0.0");
        assertEquals(0, plan.lotCountPerLeg(),
                "Rejected plan lotCountPerLeg must be 0");
    }

    // =========================================================================
    // SHORT_STRANGLE tests (S3B)
    // =========================================================================

    @Test
    void validStrangle_accepted_withTwoLegsAtDifferentStrikes() {
        // CE at 25000 (OTM call), PE at 24500 (OTM put) — different strikes
        CandidateOpportunity ce = candidate("INS_NIFTY_20260501_25000_CE", "CE",
                bd("25000"), bd("80.0"), 0.70, false);
        CandidateOpportunity pe = candidate("INS_NIFTY_20260501_24500_PE", "PE",
                bd("24500"), bd("75.0"), 0.68, false);

        // Small deltas — net will be near zero
        Map<String, SignalSnapshot> signals = Map.of(
                "INS_NIFTY_20260501_25000_CE", signal("INS_NIFTY_20260501_25000_CE", "CE", "0.06"),
                "INS_NIFTY_20260501_24500_PE", signal("INS_NIFTY_20260501_24500_PE", "PE", "-0.05")
        );

        PositionPlan plan = agent.buildShortStrangle(UNDERLYING, List.of(ce, pe), signals, PLAN_TS);

        assertTrue(plan.riskGuardApproved(), "Strangle plan must be accepted");
        assertEquals(2, plan.legs().size(), "Accepted strangle must have exactly 2 legs");
        assertEquals(PositionBuilderAgent.STRUCTURE_SHORT_STRANGLE, plan.structureType());

        PositionPlanLeg ceLeg = legByType(plan, "CE");
        PositionPlanLeg peLeg = legByType(plan, "PE");
        assertNotNull(ceLeg, "Accepted strangle must have a CE leg");
        assertNotNull(peLeg, "Accepted strangle must have a PE leg");

        // Strangle invariant: CE and PE must be at different strikes
        assertNotEquals(0, ceLeg.strike().compareTo(peLeg.strike()),
                "Strangle CE and PE must be at different strikes");
        assertEquals(bd("25000"), ceLeg.strike(), "CE leg must be at 25000");
        assertEquals(bd("24500"), peLeg.strike(), "PE leg must be at 24500");
        assertTrue(plan.rejectionReason().isEmpty(), "Accepted strangle must have no rejectionReason");
    }

    @Test
    void strangle_allSameStrike_rejected_withNoMatchingStranglePairReason() {
        // Both CE and PE at same strike — no strangle pair possible
        CandidateOpportunity ce = candidate("INS_NIFTY_20260501_24800_CE", "CE",
                bd("24800"), bd("150.0"), 0.75, false);
        CandidateOpportunity pe = candidate("INS_NIFTY_20260501_24800_PE", "PE",
                bd("24800"), bd("145.0"), 0.72, false);

        PositionPlan plan = agent.buildShortStrangle(UNDERLYING, List.of(ce, pe),
                Collections.emptyMap(), PLAN_TS);

        assertFalse(plan.riskGuardApproved(), "Plan must be rejected when all pairs at same strike");
        assertEquals("NO_MATCHING_STRANGLE_PAIR",
                plan.rejectionReason().orElse(""), "Rejection reason must be NO_MATCHING_STRANGLE_PAIR");
    }

    @Test
    void strangle_bestRankedPairSelectedByScore() {
        // Two CE candidates: 25000 (score 0.90) and 25100 (score 0.60)
        // Two PE candidates: 24500 (score 0.85) and 24400 (score 0.50)
        // Expected: builder picks CE 25000 + PE 24500 (highest-scored pair)
        CandidateOpportunity ce1 = candidate("INS_NIFTY_20260501_25000_CE", "CE",
                bd("25000"), bd("80.0"), 0.90, false);
        CandidateOpportunity ce2 = candidate("INS_NIFTY_20260501_25100_CE", "CE",
                bd("25100"), bd("60.0"), 0.60, false);
        CandidateOpportunity pe1 = candidate("INS_NIFTY_20260501_24500_PE", "PE",
                bd("24500"), bd("75.0"), 0.85, false);
        CandidateOpportunity pe2 = candidate("INS_NIFTY_20260501_24400_PE", "PE",
                bd("24400"), bd("55.0"), 0.50, false);

        Map<String, SignalSnapshot> signals = Map.of(
                "INS_NIFTY_20260501_25000_CE", signal("INS_NIFTY_20260501_25000_CE", "CE", "0.06"),
                "INS_NIFTY_20260501_25100_CE", signal("INS_NIFTY_20260501_25100_CE", "CE", "0.04"),
                "INS_NIFTY_20260501_24500_PE", signal("INS_NIFTY_20260501_24500_PE", "PE", "-0.05"),
                "INS_NIFTY_20260501_24400_PE", signal("INS_NIFTY_20260501_24400_PE", "PE", "-0.03")
        );

        PositionPlan plan = agent.buildShortStrangle(UNDERLYING,
                List.of(ce1, ce2, pe1, pe2), signals, PLAN_TS);

        assertTrue(plan.riskGuardApproved(), "Plan must be accepted");
        assertEquals(bd("25000"), legByType(plan, "CE").strike(),
                "Best-ranked CE (25000) must be selected");
        assertEquals(bd("24500"), legByType(plan, "PE").strike(),
                "Best-ranked PE (24500) must be selected");
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    /** Builds a {@link BigDecimal} from a string for readability. */
    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * Creates a minimal {@link CandidateOpportunity} fixture.
     *
     * @param instrumentId  canonical instrument id
     * @param optionType    CE or PE
     * @param strike        strike price
     * @param lastPrice     last traded price (points)
     * @param totalScore    composite score (0–1)
     * @param disqualified  if true, sets disqualifierReason to ZERO_BID
     */
    private static CandidateOpportunity candidate(
            String instrumentId,
            String optionType,
            BigDecimal strike,
            BigDecimal lastPrice,
            double totalScore,
            boolean disqualified) {

        return new CandidateOpportunity(
                "SCAN_" + instrumentId,
                UNDERLYING,
                instrumentId,
                instrumentId.replace("INS_", "").replace("_", ""),
                optionType,
                strike,
                EXPIRY,
                "WEEKLY",
                bd("24750.0"),       // spot
                lastPrice,
                lastPrice,           // bid = last
                lastPrice.add(bd("1.0")),  // ask = last + 1
                BigDecimal.ZERO,     // moneynessPoints
                0,                   // moneynessBucket
                8,                   // timeBucket15m
                lastPrice,           // historicalAvgPrice = lastPrice (richness = 0 for simplicity)
                BigDecimal.ZERO,     // premiumRichnessPoints
                BigDecimal.ZERO,     // premiumRichnessPct
                0.80,                // liquidityScore
                0.65,                // thetaOpportunityScore
                0.70,                // deltaRiskScore
                totalScore,
                disqualified ? Optional.of("ZERO_BID") : Optional.empty()
        );
    }

    /**
     * Creates a minimal {@link SignalSnapshot} fixture with the given empirical delta
     * (2m window only; 5m and SOD are null).
     */
    private static SignalSnapshot signal(
            String instrumentId,
            String optionType,
            String empiricalDelta2m) {

        return new SignalSnapshot(
                PLAN_TS,
                instrumentId,
                UNDERLYING,
                optionType,
                new BigDecimal("24800"),
                new BigDecimal(empiricalDelta2m), // empiricalDelta2m
                null, // empiricalDelta5m
                null, // empiricalDeltaSod
                new BigDecimal("10.0"),  // underlyingMove2m
                new BigDecimal("0.8"),   // optionMove2m
                new BigDecimal("0.5"),   // deltaAdjustedTheta2m
                null, // expectedDecaySinceEntry
                null, // thetaProgressRatio
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.CONFIRMED,
                false,
                "OK"
        );
    }

    /**
     * Returns the leg from the plan with the given option type, or {@code null}.
     */
    private static PositionPlanLeg legByType(PositionPlan plan, String optionType) {
        return plan.legs().stream()
                .filter(leg -> optionType.equals(leg.optionType()))
                .findFirst()
                .orElse(null);
    }
}
