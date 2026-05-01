package com.strategysquad.agentic.decision;

import com.strategysquad.agentic.risk.RiskGuardDecision;
import com.strategysquad.agentic.risk.RiskGuardSnapshot;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.signal.SignalSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DecisionPolicy} — Rules 1–10 (all rules).
 *
 * <p>All tests are pure in-memory: no database, no HTTP, no file I/O.
 * Every input is constructed directly as a Java object.
 *
 * <h2>Rules covered</h2>
 * <ol>
 *   <li>HALT_SESSION  → EXIT_ALL with overriddenByRiskGuard = true</li>
 *   <li>FORCE_EXIT    → EXIT_ALL with overriddenByRiskGuard = true</li>
 *   <li>FORCE_REDUCE  → REDUCE   with overriddenByRiskGuard = true</li>
 *   <li>BLOCK_NEW_ENTRY + no active session → SKIP with overriddenByRiskGuard = true</li>
 *   <li>No active session + no qualified candidates → SKIP, overriddenByRiskGuard = false</li>
 *   <li>No session + qualified candidates + ALLOW → ENTER (never when BLOCK_NEW_ENTRY active)</li>
 *   <li>Active session + ratio &gt;= threshold + positive PnL + ALLOW/WARN → BOOK_PROFIT
 *       (never when PnL negative)</li>
 *   <li>Active session + delta-adjusted theta negative → REDUCE</li>
 *   <li>Active session + all signals HOLD + ALLOW → HOLD</li>
 *   <li>Default → HOLD</li>
 * </ol>
 */
class DecisionPolicyTest {

    private DecisionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DecisionPolicy();
    }

    // =========================================================================
    // Rules 1–5 (from S2B — preserved unchanged)
    // =========================================================================

    // -------------------------------------------------------------------------
    // Rule 1 — HALT_SESSION → EXIT_ALL override
    // -------------------------------------------------------------------------

    @Test
    void rule1_haltSession_emitsExitAllWithOverride() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.HALT_SESSION,
                List.of("CHURN_DETECTED"),
                Optional.empty(),
                List.of()
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.EXIT_ALL, cmd.commandType(),
                "HALT_SESSION must produce EXIT_ALL");
        assertTrue(cmd.overriddenByRiskGuard(),
                "overriddenByRiskGuard must be true for HALT_SESSION");
        assertEquals(RiskGuardDecision.HALT_SESSION, cmd.riskGuardDecision());
        assertFalse(cmd.reasonCode().isBlank(), "reasonCode must not be blank");
        assertFalse(cmd.explanation().isBlank(), "explanation must not be blank");
    }

    @Test
    void rule1_haltSession_overridesEvenWithActiveSessionAndCandidates() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.HALT_SESSION,
                List.of("CHURN_DETECTED"),
                Optional.of("SESSION-001"),
                List.of(qualifiedCandidate("INS_NIFTY_001"))
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.EXIT_ALL, cmd.commandType());
        assertTrue(cmd.overriddenByRiskGuard());
    }

    // -------------------------------------------------------------------------
    // Rule 2 — FORCE_EXIT → EXIT_ALL override
    // -------------------------------------------------------------------------

    @Test
    void rule2_forceExit_emitsExitAllWithOverride() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.FORCE_EXIT,
                List.of("MAX_LOSS_EXCEEDED"),
                Optional.of("SESSION-002"),
                List.of()
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.EXIT_ALL, cmd.commandType(),
                "FORCE_EXIT must produce EXIT_ALL");
        assertTrue(cmd.overriddenByRiskGuard(),
                "overriddenByRiskGuard must be true for FORCE_EXIT");
        assertEquals(RiskGuardDecision.FORCE_EXIT, cmd.riskGuardDecision());
        assertFalse(cmd.reasonCode().isBlank());
        assertFalse(cmd.explanation().isBlank());
    }

    @Test
    void rule2_forceExit_noSession_stillEmitsExitAll() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.FORCE_EXIT,
                List.of("MAX_LOSS_EXCEEDED"),
                Optional.empty(),
                List.of()
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.EXIT_ALL, cmd.commandType());
        assertTrue(cmd.overriddenByRiskGuard());
    }

    // -------------------------------------------------------------------------
    // Rule 3 — FORCE_REDUCE → REDUCE override
    // -------------------------------------------------------------------------

    @Test
    void rule3_forceReduce_emitsReduceWithOverride() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.FORCE_REDUCE,
                List.of("NET_DELTA_BREACH"),
                Optional.of("SESSION-003"),
                List.of()
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.REDUCE, cmd.commandType(),
                "FORCE_REDUCE must produce REDUCE");
        assertTrue(cmd.overriddenByRiskGuard(),
                "overriddenByRiskGuard must be true for FORCE_REDUCE");
        assertEquals(RiskGuardDecision.FORCE_REDUCE, cmd.riskGuardDecision());
        assertFalse(cmd.reasonCode().isBlank());
        assertFalse(cmd.explanation().isBlank());
    }

    // -------------------------------------------------------------------------
    // Rule 4 — BLOCK_NEW_ENTRY + no active session → SKIP
    // -------------------------------------------------------------------------

    @Test
    void rule4_blockNewEntry_noSession_emitsSkipWithOverride() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.BLOCK_NEW_ENTRY,
                List.of("DATA_STALE"),
                Optional.empty(),
                List.of(qualifiedCandidate("INS_NIFTY_002"))
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.SKIP, cmd.commandType(),
                "BLOCK_NEW_ENTRY with no session must produce SKIP");
        assertTrue(cmd.overriddenByRiskGuard(),
                "overriddenByRiskGuard must be true for BLOCK_NEW_ENTRY override");
        assertEquals(RiskGuardDecision.BLOCK_NEW_ENTRY, cmd.riskGuardDecision());
        assertFalse(cmd.reasonCode().isBlank());
        assertFalse(cmd.explanation().isBlank());
    }

    @Test
    void rule4_blockNewEntry_withActiveSession_doesNotSkip() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.BLOCK_NEW_ENTRY,
                List.of("DATA_STALE"),
                Optional.of("SESSION-004"),
                List.of()
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertNotEquals(DecisionCommand.CommandType.SKIP, cmd.commandType(),
                "Rule 4 SKIP must not fire when an active session exists");
    }

    // -------------------------------------------------------------------------
    // Rule 5 — No active session + no qualified candidates → SKIP
    // -------------------------------------------------------------------------

    @Test
    void rule5_noSession_noQualifiedCandidates_emitsSkipNoOverride() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.empty(),
                List.of()
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.SKIP, cmd.commandType(),
                "No session and no qualified candidates must produce SKIP");
        assertFalse(cmd.overriddenByRiskGuard(),
                "Rule 5 SKIP is a policy skip, not a risk guard override");
        assertEquals(RiskGuardDecision.ALLOW, cmd.riskGuardDecision());
        assertFalse(cmd.reasonCode().isBlank());
        assertFalse(cmd.explanation().isBlank());
    }

    @Test
    void rule5_noSession_onlyDisqualifiedCandidates_emitsSkip() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.empty(),
                List.of(disqualifiedCandidate("INS_NIFTY_003", "ZERO_BID"))
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.SKIP, cmd.commandType(),
                "Disqualified candidates do not satisfy Rule 5 — must still emit SKIP");
        assertFalse(cmd.overriddenByRiskGuard());
    }

    @Test
    void bookingThresholdConstant_isCorrectValue() {
        assertEquals(0.75, DecisionPolicy.BOOKING_THRESHOLD, 1e-9,
                "BOOKING_THRESHOLD must be 0.75");
    }

    @Test
    void allRulePaths_rules1to5_returnCompleteCommand() {
        List<DecisionContext> contexts = List.of(
                contextWith(RiskGuardDecision.HALT_SESSION,    List.of("C1"), Optional.empty(), List.of()),
                contextWith(RiskGuardDecision.FORCE_EXIT,      List.of("C2"), Optional.empty(), List.of()),
                contextWith(RiskGuardDecision.FORCE_REDUCE,    List.of("C3"), Optional.of("S1"), List.of()),
                contextWith(RiskGuardDecision.BLOCK_NEW_ENTRY, List.of("C4"), Optional.empty(), List.of()),
                contextWith(RiskGuardDecision.ALLOW,           List.of(),     Optional.empty(), List.of())
        );

        for (DecisionContext ctx : contexts) {
            DecisionCommand cmd = policy.evaluate(ctx);
            assertNotNull(cmd.commandId(),   "commandId must not be null");
            assertNotNull(cmd.issuedTs(),    "issuedTs must not be null");
            assertFalse(cmd.reasonCode().isBlank(),  "reasonCode must not be blank");
            assertFalse(cmd.explanation().isBlank(), "explanation must not be blank");
        }
    }

    // =========================================================================
    // Rules 6–10 (S2C additions)
    // =========================================================================

    // -------------------------------------------------------------------------
    // Rule 6 — No session + qualified candidates + ALLOW → ENTER
    // -------------------------------------------------------------------------

    /**
     * When there is no active session, at least one qualified candidate exists,
     * and the Risk Guard is ALLOW, the policy must emit ENTER for the top candidate.
     */
    @Test
    void rule6_noSession_qualifiedCandidates_allow_emitsEnter() {
        CandidateOpportunity top = qualifiedCandidate("INS_NIFTY_RULE6_TOP");
        DecisionContext ctx = contextWith(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.empty(),
                List.of(top)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.ENTER, cmd.commandType(),
                "No session + qualified candidate + ALLOW must produce ENTER");
        assertFalse(cmd.overriddenByRiskGuard(),
                "Rule 6 ENTER is a normal policy decision — not a guard override");
        assertEquals(RiskGuardDecision.ALLOW, cmd.riskGuardDecision());
        assertFalse(cmd.reasonCode().isBlank(), "reasonCode must not be blank");
        assertFalse(cmd.explanation().isBlank(), "explanation must not be blank");
        assertEquals(1, cmd.selectedCandidateIds().size(),
                "ENTER must reference exactly one selected candidate");
        assertEquals(top.candidateId(), cmd.selectedCandidateIds().get(0),
                "ENTER must select the top-ranked qualified candidate");
    }

    /**
     * ENTER must never be emitted when BLOCK_NEW_ENTRY is active, even if
     * qualified candidates exist and there is no session.
     * Rule 4 fires first and emits SKIP.
     */
    @Test
    void rule6_blockNewEntry_noSession_qualifiedCandidates_neverEmitsEnter() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.BLOCK_NEW_ENTRY,
                List.of("DATA_STALE"),
                Optional.empty(),
                List.of(qualifiedCandidate("INS_NIFTY_BLOCKED"))
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertNotEquals(DecisionCommand.CommandType.ENTER, cmd.commandType(),
                "ENTER must never be emitted when BLOCK_NEW_ENTRY is active");
        assertEquals(DecisionCommand.CommandType.SKIP, cmd.commandType(),
                "BLOCK_NEW_ENTRY + no session must produce SKIP (Rule 4), not ENTER");
        assertTrue(cmd.overriddenByRiskGuard());
    }

    /**
     * ENTER must not be emitted when HALT_SESSION is active.
     * Rule 1 fires first → EXIT_ALL.
     */
    @Test
    void rule6_haltSession_qualifiedCandidates_neverEmitsEnter() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.HALT_SESSION,
                List.of("CHURN_DETECTED"),
                Optional.empty(),
                List.of(qualifiedCandidate("INS_NIFTY_HALTED"))
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertNotEquals(DecisionCommand.CommandType.ENTER, cmd.commandType(),
                "ENTER must never be emitted when HALT_SESSION is active");
    }

    /**
     * ENTER must not be emitted when Risk Guard is WARN — Rule 6 requires ALLOW.
     * Falls through to Rule 10 default HOLD.
     */
    @Test
    void rule6_warnGuard_noSession_qualifiedCandidates_doesNotEnter() {
        DecisionContext ctx = contextWith(
                RiskGuardDecision.WARN,
                List.of("PREMIUM_EXPANSION"),
                Optional.empty(),
                List.of(qualifiedCandidate("INS_NIFTY_WARN"))
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertNotEquals(DecisionCommand.CommandType.ENTER, cmd.commandType(),
                "ENTER must not be emitted when Risk Guard is WARN");
    }

    // -------------------------------------------------------------------------
    // Rule 7 — Active session + ratio >= threshold + positive PnL + ALLOW/WARN
    //          → BOOK_PROFIT; never when PnL negative
    // -------------------------------------------------------------------------

    /**
     * When active session, theta_progress_ratio >= BOOKING_THRESHOLD, PnL positive,
     * and Risk Guard ALLOW → BOOK_PROFIT.
     */
    @Test
    void rule7_activeSession_ratioAboveThreshold_positivePnl_allow_emitsBookProfit() {
        SignalSnapshot signal = signalWithRatio("INS_NIFTY_BOOK", new BigDecimal("0.80"));
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.of("SESSION-007"),
                50.0,
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.BOOK_PROFIT, cmd.commandType(),
                "ratio 0.80 >= 0.75 + positive PnL + ALLOW must produce BOOK_PROFIT");
        assertFalse(cmd.overriddenByRiskGuard());
        assertFalse(cmd.reasonCode().isBlank());
        assertFalse(cmd.explanation().isBlank());
    }

    /**
     * BOOK_PROFIT must also fire when Risk Guard is WARN (not just ALLOW).
     */
    @Test
    void rule7_warnGuard_ratioAboveThreshold_positivePnl_emitsBookProfit() {
        SignalSnapshot signal = signalWithRatio("INS_NIFTY_BOOK_WARN", new BigDecimal("0.82"));
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.WARN,
                List.of("PREMIUM_EXPANSION"),
                Optional.of("SESSION-007B"),
                30.0,
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.BOOK_PROFIT, cmd.commandType(),
                "WARN guard must still allow BOOK_PROFIT when PnL positive and ratio met");
    }

    /**
     * BOOK_PROFIT must never be emitted when live PnL is negative,
     * even if theta_progress_ratio is above the threshold.
     */
    @Test
    void rule7_negativePnl_ratioAboveThreshold_neverEmitsBookProfit() {
        SignalSnapshot signal = signalWithRatio("INS_NIFTY_NEG_PNL", new BigDecimal("0.80"));
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.of("SESSION-007C"),
                -15.0,
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertNotEquals(DecisionCommand.CommandType.BOOK_PROFIT, cmd.commandType(),
                "BOOK_PROFIT must never be emitted when PnL is negative");
    }

    /**
     * BOOK_PROFIT must not be emitted when ratio is below BOOKING_THRESHOLD.
     */
    @Test
    void rule7_ratioBelowThreshold_doesNotBookProfit() {
        SignalSnapshot signal = signalWithRatio("INS_NIFTY_LOW_RATIO", new BigDecimal("0.60"));
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.of("SESSION-007D"),
                40.0,
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertNotEquals(DecisionCommand.CommandType.BOOK_PROFIT, cmd.commandType(),
                "ratio 0.60 < 0.75 must not trigger BOOK_PROFIT");
    }

    // -------------------------------------------------------------------------
    // Rule 8 — Active session + delta-adjusted theta negative → REDUCE
    // -------------------------------------------------------------------------

    /**
     * When active session and at least one signal has negative delta-adjusted theta
     * (premium expanding), the policy must emit REDUCE.
     */
    @Test
    void rule8_activeSession_negativeAdjustedTheta_emitsReduce() {
        SignalSnapshot signal = signalWithNegativeDeltaAdjustedTheta("INS_NIFTY_EXP");
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.of("SESSION-008"),
                -5.0,
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.REDUCE, cmd.commandType(),
                "Negative delta-adjusted theta must produce REDUCE");
        assertFalse(cmd.overriddenByRiskGuard(),
                "Rule 8 REDUCE is a policy decision — not a guard override");
        assertFalse(cmd.reasonCode().isBlank());
        assertFalse(cmd.explanation().isBlank());
    }

    /**
     * Rule 8 must not fire when delta-adjusted theta is null (insufficient data).
     * Only definite negative values trigger REDUCE.
     */
    @Test
    void rule8_nullAdjustedTheta_doesNotReduce() {
        SignalSnapshot signal = signalWithNullDeltaAdjustedTheta("INS_NIFTY_NULL_THETA");
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.of("SESSION-008B"),
                0.0,
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        // null deltaAdjustedTheta + HOLD thetaState + ALLOW → Rule 9 fires → HOLD
        assertNotEquals(DecisionCommand.CommandType.REDUCE, cmd.commandType(),
                "Null delta-adjusted theta must not trigger Rule 8 REDUCE");
    }

    // -------------------------------------------------------------------------
    // Rule 9 — Active session + all signals HOLD + ALLOW → HOLD
    // -------------------------------------------------------------------------

    /**
     * When active session, all signals ThetaState.HOLD, and Risk Guard ALLOW,
     * the policy must emit HOLD with reason ALL_SIGNALS_HOLD.
     */
    @Test
    void rule9_activeSession_allSignalsHold_allow_emitsHold() {
        SignalSnapshot signal = signalInHoldState("INS_NIFTY_HOLD");
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.of("SESSION-009"),
                10.0,
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.HOLD, cmd.commandType(),
                "Active session + all signals HOLD + ALLOW must produce HOLD");
        assertFalse(cmd.overriddenByRiskGuard());
        assertEquals("ALL_SIGNALS_HOLD", cmd.reasonCode());
        assertFalse(cmd.explanation().isBlank());
    }

    /**
     * When a signal has PROFIT_BOOK state, allSignalsHold() returns false,
     * so Rule 9 must not fire. Falls to Rule 10 default HOLD.
     */
    @Test
    void rule9_profitBookSignal_doesNotFireHoldRule() {
        SignalSnapshot signal = signalWithThetaState(
                "INS_NIFTY_PROFIT_BOOK_STATE",
                SignalSnapshot.ThetaState.PROFIT_BOOK,
                new BigDecimal("0.60"),  // below booking threshold
                null                     // no negative adjusted theta
        );
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.ALLOW,
                List.of(),
                Optional.of("SESSION-009B"),
                0.0,                     // PnL zero → Rule 7 won't fire
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        // allSignalsHold() is false (PROFIT_BOOK ≠ HOLD) → Rule 10 DEFAULT_HOLD
        assertNotEquals("ALL_SIGNALS_HOLD", cmd.reasonCode(),
                "Rule 9 must not fire when a signal has PROFIT_BOOK state");
        assertEquals(DecisionCommand.CommandType.HOLD, cmd.commandType());
        assertEquals("DEFAULT_HOLD", cmd.reasonCode());
    }

    // -------------------------------------------------------------------------
    // Rule 10 — Default → HOLD
    // -------------------------------------------------------------------------

    /**
     * When no rule 1–9 condition matches (active session, WARN guard, ratio below
     * threshold, HOLD signals — Rule 9 needs ALLOW, not WARN), must fall to Rule 10.
     */
    @Test
    void rule10_default_emitsHold() {
        SignalSnapshot signal = signalInHoldState("INS_NIFTY_DEFAULT");
        DecisionContext ctx = contextWithSignals(
                RiskGuardDecision.WARN,     // Rule 9 needs ALLOW — this fails it
                List.of("SOME_WARN"),
                Optional.of("SESSION-010"),
                5.0,
                Map.of(signal.instrumentId(), signal)
        );

        DecisionCommand cmd = policy.evaluate(ctx);

        assertEquals(DecisionCommand.CommandType.HOLD, cmd.commandType(),
                "Fallthrough with no specific match must produce HOLD (Rule 10)");
        assertEquals("DEFAULT_HOLD", cmd.reasonCode());
        assertFalse(cmd.overriddenByRiskGuard());
        assertFalse(cmd.explanation().isBlank());
    }

    // -------------------------------------------------------------------------
    // Cross-rule invariants (S2C)
    // -------------------------------------------------------------------------

    /**
     * ENTER must never appear for any hard-override guard state,
     * even with qualified candidates and no active session.
     */
    @Test
    void enterNeverEmitted_whenBlockingGuardActive() {
        List<RiskGuardDecision> blockingGuards = List.of(
                RiskGuardDecision.HALT_SESSION,
                RiskGuardDecision.FORCE_EXIT,
                RiskGuardDecision.FORCE_REDUCE,
                RiskGuardDecision.BLOCK_NEW_ENTRY
        );
        CandidateOpportunity candidate = qualifiedCandidate("INS_NIFTY_GUARD_CHECK");

        for (RiskGuardDecision guard : blockingGuards) {
            DecisionContext ctx = contextWith(
                    guard, List.of("SOME_CONDITION"), Optional.empty(), List.of(candidate));
            DecisionCommand cmd = policy.evaluate(ctx);
            assertNotEquals(DecisionCommand.CommandType.ENTER, cmd.commandType(),
                    "ENTER must never be emitted when guard is " + guard);
        }
    }

    /**
     * BOOK_PROFIT must never appear when PnL is zero or negative,
     * across ALLOW and WARN guard states with ratio above threshold.
     */
    @Test
    void bookProfitNeverEmitted_whenPnlNotPositive() {
        SignalSnapshot signal = signalWithRatio("INS_NIFTY_ZERO_PNL", new BigDecimal("0.90"));

        for (double pnl : new double[]{0.0, -0.01, -100.0}) {
            DecisionContext ctx = contextWithSignals(
                    RiskGuardDecision.ALLOW, List.of(),
                    Optional.of("SESSION-PNLCHECK"), pnl,
                    Map.of(signal.instrumentId(), signal)
            );
            DecisionCommand cmd = policy.evaluate(ctx);
            assertNotEquals(DecisionCommand.CommandType.BOOK_PROFIT, cmd.commandType(),
                    "BOOK_PROFIT must never be emitted when livePnl=" + pnl);
        }
    }

    /**
     * Every rule path (1–10) must return a command with non-null commandId,
     * non-null issuedTs, non-blank reasonCode, and non-blank explanation.
     */
    @Test
    void allRulePaths_rules6to10_returnCompleteCommand() {
        SignalSnapshot holdSignal    = signalInHoldState("INS_NIFTY_COMPLETE_6");
        SignalSnapshot bookSignal    = signalWithRatio("INS_NIFTY_COMPLETE_7", new BigDecimal("0.80"));
        SignalSnapshot negThetaSignal = signalWithNegativeDeltaAdjustedTheta("INS_NIFTY_COMPLETE_8");

        List<DecisionContext> contexts = List.of(
                // Rule 6: ENTER
                contextWith(RiskGuardDecision.ALLOW, List.of(), Optional.empty(),
                        List.of(qualifiedCandidate("INS_NIFTY_R6"))),
                // Rule 7: BOOK_PROFIT
                contextWithSignals(RiskGuardDecision.ALLOW, List.of(), Optional.of("S7"),
                        40.0, Map.of(bookSignal.instrumentId(), bookSignal)),
                // Rule 8: REDUCE (negative theta)
                contextWithSignals(RiskGuardDecision.ALLOW, List.of(), Optional.of("S8"),
                        -5.0, Map.of(negThetaSignal.instrumentId(), negThetaSignal)),
                // Rule 9: HOLD (all signals HOLD + ALLOW)
                contextWithSignals(RiskGuardDecision.ALLOW, List.of(), Optional.of("S9"),
                        10.0, Map.of(holdSignal.instrumentId(), holdSignal)),
                // Rule 10: default HOLD (WARN guard, HOLD signal — Rule 9 needs ALLOW)
                contextWithSignals(RiskGuardDecision.WARN, List.of("W"), Optional.of("S10"),
                        5.0, Map.of(holdSignal.instrumentId(), holdSignal))
        );

        for (DecisionContext ctx : contexts) {
            DecisionCommand cmd = policy.evaluate(ctx);
            assertNotNull(cmd.commandId(),   "commandId must not be null");
            assertNotNull(cmd.issuedTs(),    "issuedTs must not be null");
            assertFalse(cmd.reasonCode().isBlank(),  "reasonCode must not be blank");
            assertFalse(cmd.explanation().isBlank(), "explanation must not be blank");
        }
    }

    // =========================================================================
    // Fixture helpers — original (Rules 1–5)
    // =========================================================================

    /** Builds a minimal {@link DecisionContext} with no signal snapshots. */
    private DecisionContext contextWith(
            RiskGuardDecision guardDecision,
            List<String> conditions,
            Optional<String> sessionId,
            List<CandidateOpportunity> candidates) {

        RiskGuardSnapshot riskGuard = new RiskGuardSnapshot(
                Instant.now(), guardDecision, conditions,
                conditions.isEmpty() ? "" : "Test condition: " + conditions,
                0.0, 0.0, false, false, false, false, false, false
        );

        Optional<com.strategysquad.research.PositionSessionSnapshot> activeSession =
                sessionId.map(this::minimalSession);

        return new DecisionContext(
                Instant.now(), DecisionCommand.Mode.PAPER,
                candidates, Map.of(), activeSession,
                0.0, 0.0, riskGuard, 4, false, false
        );
    }

    /** Qualified candidate: disqualifierReason is empty. */
    private CandidateOpportunity qualifiedCandidate(String instrumentId) {
        return new CandidateOpportunity(
                "SCAN_NIFTY_" + instrumentId + "_202604251030",
                "NIFTY", instrumentId, "NIFTY26APR24800CE", "CE",
                new BigDecimal("24800"), LocalDate.of(2026, 4, 30), "WEEKLY",
                new BigDecimal("24750"), new BigDecimal("55.0"),
                new BigDecimal("54.5"),  new BigDecimal("55.5"),
                new BigDecimal("-50"), -100, 8,
                new BigDecimal("45.0"), new BigDecimal("10.0"),
                new BigDecimal("22.2"), 0.85, 0.72, 0.80, 0.76,
                Optional.empty()
        );
    }

    /** Disqualified candidate: disqualifierReason is set. */
    private CandidateOpportunity disqualifiedCandidate(String instrumentId, String reason) {
        return new CandidateOpportunity(
                "SCAN_NIFTY_" + instrumentId + "_202604251030",
                "NIFTY", instrumentId, "NIFTY26APR24800CE", "CE",
                new BigDecimal("24800"), LocalDate.of(2026, 4, 30), "WEEKLY",
                new BigDecimal("24750"), new BigDecimal("0"),
                new BigDecimal("0"),     new BigDecimal("0.5"),
                new BigDecimal("-50"), -100, 8,
                new BigDecimal("45.0"), new BigDecimal("0"),
                new BigDecimal("0"), 0.0, 0.0, 0.0, 0.0,
                Optional.of(reason)
        );
    }

    /** Minimal PositionSessionSnapshot — only sessionId is meaningful for most rules. */
    private com.strategysquad.research.PositionSessionSnapshot minimalSession(String sessionId) {
        return new com.strategysquad.research.PositionSessionSnapshot(
                sessionId, "PAPER", "SHORT_STRADDLE", "SHORT", "NIFTY",
                "WEEKLY", "INTRADAY", 3,
                new BigDecimal("24750"), 1,
                Instant.now(), Instant.now(), null,
                "OPEN", List.of(), List.of()
        );
    }

    // =========================================================================
    // Fixture helpers — S2C additions (Rules 7–10 signal-based contexts)
    // =========================================================================

    /**
     * Builds a {@link DecisionContext} with an explicit signal map and livePnl,
     * used for Rules 7–10 which depend on signal state and PnL.
     */
    private DecisionContext contextWithSignals(
            RiskGuardDecision guardDecision,
            List<String> conditions,
            Optional<String> sessionId,
            double livePnl,
            Map<String, SignalSnapshot> signals) {

        RiskGuardSnapshot riskGuard = new RiskGuardSnapshot(
                Instant.now(), guardDecision, conditions,
                conditions.isEmpty() ? "" : "Test condition: " + conditions,
                0.0, livePnl, false, false, false, false, false, false
        );

        Optional<com.strategysquad.research.PositionSessionSnapshot> activeSession =
                sessionId.map(this::minimalSession);

        return new DecisionContext(
                Instant.now(), DecisionCommand.Mode.PAPER,
                List.of(), signals, activeSession,
                livePnl, 0.0, riskGuard, 4, false, false
        );
    }

    /**
     * Signal with given thetaProgressRatio and PROFIT_BOOK state; positive
     * delta-adjusted theta (not expanding) and fresh data.
     */
    private SignalSnapshot signalWithRatio(String instrumentId, BigDecimal ratio) {
        return new SignalSnapshot(
                Instant.now(), instrumentId, "NIFTY", "CE",
                new BigDecimal("24800"),
                new BigDecimal("0.12"), new BigDecimal("0.11"), new BigDecimal("0.13"),
                new BigDecimal("20"),   new BigDecimal("3"),
                new BigDecimal("0.6"),  // positive deltaAdjustedTheta2m
                new BigDecimal("30"),   ratio,
                SignalSnapshot.ThetaState.PROFIT_BOOK,
                SignalSnapshot.VolumeState.CONFIRMED,
                false, "OK"
        );
    }

    /**
     * Signal with negative deltaAdjustedTheta2m (premium expanding) and
     * DEFENSIVE_EXIT state. Ratio well below booking threshold so Rule 7 does not fire.
     */
    private SignalSnapshot signalWithNegativeDeltaAdjustedTheta(String instrumentId) {
        return new SignalSnapshot(
                Instant.now(), instrumentId, "NIFTY", "PE",
                new BigDecimal("24600"),
                new BigDecimal("-0.10"), new BigDecimal("-0.09"), new BigDecimal("-0.11"),
                new BigDecimal("-30"),   new BigDecimal("5"),
                new BigDecimal("-2.0"),  // negative — triggers Rule 8
                new BigDecimal("10"),    new BigDecimal("0.30"),
                SignalSnapshot.ThetaState.DEFENSIVE_EXIT,
                SignalSnapshot.VolumeState.CONFIRMED,
                false, "PREMIUM_EXPANDING"
        );
    }

    /**
     * Signal with null deltaAdjustedTheta2m (insufficient data) and HOLD state.
     * Rule 8 must not fire for this snapshot.
     */
    private SignalSnapshot signalWithNullDeltaAdjustedTheta(String instrumentId) {
        return new SignalSnapshot(
                Instant.now(), instrumentId, "NIFTY", "CE",
                new BigDecimal("24800"),
                null, null, null, null, null,
                null,   // deltaAdjustedTheta2m null — Rule 8 must not fire
                null,   // expectedDecaySinceEntry
                null,   // thetaProgressRatio null — Rule 7 must not fire
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.LOW,
                false, "OK"
        );
    }


    /** Signal: HOLD state, positive adjusted theta, ratio below booking threshold. Triggers Rule 9. */
    private SignalSnapshot signalInHoldState(String instrumentId) {
        return new SignalSnapshot(
                Instant.now(), instrumentId, "NIFTY", "CE",
                new BigDecimal("24800"),
                new BigDecimal("0.10"), new BigDecimal("0.09"), new BigDecimal("0.11"),
                new BigDecimal("15"),   new BigDecimal("2"),
                new BigDecimal("0.5"),  new BigDecimal("20"), new BigDecimal("0.45"),
                SignalSnapshot.ThetaState.HOLD,
                SignalSnapshot.VolumeState.CONFIRMED, false, "OK"
        );
    }

    /** Signal: caller-specified thetaState, ratio, and deltaAdjustedTheta. */
    private SignalSnapshot signalWithThetaState(
            String instrumentId, SignalSnapshot.ThetaState thetaState,
            BigDecimal ratio, BigDecimal deltaAdjustedTheta) {
        return new SignalSnapshot(
                Instant.now(), instrumentId, "NIFTY", "CE",
                new BigDecimal("24800"),
                new BigDecimal("0.10"), new BigDecimal("0.09"), new BigDecimal("0.11"),
                new BigDecimal("15"),   new BigDecimal("2"),
                deltaAdjustedTheta,     new BigDecimal("20"), ratio,
                thetaState, SignalSnapshot.VolumeState.CONFIRMED, false, "OK"
        );
    }
}
