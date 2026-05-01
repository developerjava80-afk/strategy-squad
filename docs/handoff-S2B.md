# Session S2B Handoff Note

**Date:** 2026-04-26  
**Session:** S2B — Phase 2, Task 2.4a (DecisionPolicy rules 1–5)  
**Status:** Two files written and reviewed. `mvn -DskipTests compile` and `mvn test` must be run on the Windows host (Java 17 + Maven required; Linux sandbox has only Java 11). Run `build-and-test-s2b.bat` to verify.

---

## What was done

### Task 2.4a — `DecisionPolicy` (Rules 1–5)

**File created:** `src/main/java/com/strategysquad/agentic/decision/DecisionPolicy.java`

A pure stateless class — no I/O, no DB access, no mutable fields. The single public method `evaluate(DecisionContext)` maps a context to a `DecisionCommand` by testing rules in strict priority order.

**`BOOKING_THRESHOLD` constant:** `public static final double BOOKING_THRESHOLD = 0.75` — defined at class level, referenced by this class and by `ProfitBookingAgent` (Task 3.4).

**Rules implemented (1–5):**

| Rule | Condition | Output | `overriddenByRiskGuard` |
|---|---|---|---|
| 1 | `RiskGuardDecision.HALT_SESSION` | `EXIT_ALL` | `true` |
| 2 | `RiskGuardDecision.FORCE_EXIT` | `EXIT_ALL` | `true` |
| 3 | `RiskGuardDecision.FORCE_REDUCE` | `REDUCE` | `true` |
| 4 | `BLOCK_NEW_ENTRY` + `activeSession.isEmpty()` | `SKIP` | `true` |
| 5 | `activeSession.isEmpty()` + no qualified candidates | `SKIP` | `false` |
| default | (none of the above matched) | `HOLD` | `false` |

The default `HOLD` is a clean placeholder for Rules 6–10, which are implemented in S2C.

**Key implementation details:**
- `qualifiedCandidates()` private helper: filters `rankedCandidates` by `disqualifierReason().isEmpty()` — disqualified candidates do not count toward Rule 5.
- `command()` private helper: extracts `positionSessionId` from `activeSession` via `Optional.map(s -> s.sessionId())`. Generates a fresh `UUID.randomUUID()` and `Instant.now()` per command.
- Every emitted command has a non-blank `reasonCode` (machine-readable) and `explanation` (trader-readable). Triggered conditions from the risk guard snapshot are embedded in the explanation text for Rules 1–4.
- Rule 4 only fires when `BLOCK_NEW_ENTRY` **and** no active session. If a session is open, the policy falls through — the block applies to new entries only, not to existing position management.

---

### `DecisionPolicyTest` — 11 test methods

**File created:** `src/test/java/com/strategysquad/agentic/decision/DecisionPolicyTest.java`

All tests are pure in-memory — no DB, no HTTP, no file I/O. Every input is constructed directly as a Java object.

| Test method | Rule | What it asserts |
|---|---|---|
| `rule1_haltSession_emitsExitAllWithOverride` | 1 | EXIT_ALL + override=true + non-blank codes |
| `rule1_haltSession_overridesEvenWithActiveSessionAndCandidates` | 1 | Rule 1 is highest priority — fires even with session + candidates |
| `rule2_forceExit_emitsExitAllWithOverride` | 2 | EXIT_ALL + override=true |
| `rule2_forceExit_noSession_stillEmitsExitAll` | 2 | FORCE_EXIT fires regardless of session presence |
| `rule3_forceReduce_emitsReduceWithOverride` | 3 | REDUCE + override=true |
| `rule4_blockNewEntry_noSession_emitsSkipWithOverride` | 4 | SKIP + override=true; candidates present but blocked |
| `rule4_blockNewEntry_withActiveSession_doesNotSkip` | 4 | Rule 4 must NOT fire when session is open |
| `rule5_noSession_noQualifiedCandidates_emitsSkipNoOverride` | 5 | SKIP + override=false (policy skip, not guard override) |
| `rule5_noSession_onlyDisqualifiedCandidates_emitsSkip` | 5 | Disqualified candidates do not satisfy Rule 5 |
| `bookingThresholdConstant_isCorrectValue` | — | `BOOKING_THRESHOLD == 0.75` exactly |
| `allRulePaths_returnCompleteCommand` | all | All 5 rule paths return non-null commandId, issuedTs, non-blank reasonCode + explanation |

**Fixture helpers:**
- `contextWith(guard, conditions, sessionId, candidates)` — builds a minimal `DecisionContext` with the given risk guard state, optional session, and candidate list.
- `qualifiedCandidate(instrumentId)` — 23-field `CandidateOpportunity` with `disqualifierReason = Optional.empty()`.
- `disqualifiedCandidate(instrumentId, reason)` — 23-field `CandidateOpportunity` with `disqualifierReason = Optional.of(reason)`.
- `minimalSession(sessionId)` — minimal `PositionSessionSnapshot` with all required fields, `lastDeltaAdjustmentTs = null` (nullable per record definition).

---

## Build artifact

**File created:** `build-and-test-s2b.bat`

Runs on Windows:
1. `mvn -DskipTests compile` — must exit 0
2. `mvn -Dtest=DecisionPolicyTest test` — targeted run of the new test class
3. `mvn test` — full suite regression check

---

## Pre-existing test fix applied in this session

`src/test/java/com/strategysquad/agentic/scanner/Phase1SmokeTest.java` had two `LegInput` constructor callsites written against an older 19-field signature. The `SignalSnapshotService.LegInput` record gained `lotSize (int)`, `quantity (int)`, `entryEmpiricalDelta (BigDecimal)`, and `entryExpectedDecayRatePerMinute (BigDecimal)` in S1E, bringing the field count to 21. Both callsites were missing `65, 1` for `lotSize/quantity` (placed between SOD prices and `currentVolume`) and were missing two of the five entry-field nulls. Fixed by inserting `65, 1,` and redistributing the null block to match the current 21-field layout. `SignalSnapshotServiceTest` already used the correct 21-field form and required no changes.

---

## What S2C must do

1. Read `docs/agentic-loop-implementation-plan.md` Task 2.4 **and** Task 2.6 in full before writing any code.
2. Read `DecisionPolicy.java` (this file) before adding to it — the rule numbering, constant name, and helper methods are the anchor points.
3. **Add Rules 6–10 to `DecisionPolicy.evaluate()`** in place of the current default HOLD:
   - Rule 6: `activeSession.isEmpty()` + qualified candidates available + risk `ALLOW` → `ENTER` for top-ranked candidate
   - Rule 7: `activeSession` present + `thetaProgressRatio >= BOOKING_THRESHOLD` + `livePnl > 0` + risk `ALLOW` or `WARN` → `BOOK_PROFIT`
   - Rule 8: `activeSession` present + delta-adjusted theta negative (premium expanding) → `REDUCE`
   - Rule 9: `activeSession` present + all signal snapshots are `HOLD` + risk allows → `HOLD`
   - Rule 10: default → `HOLD`
4. Add test methods for Rules 6–10 to `DecisionPolicyTest`. Verify: `ENTER` is **never** emitted when `BLOCK_NEW_ENTRY` or worse is active; `BOOK_PROFIT` is **never** emitted when `livePnl <= 0`.
5. Create `src/main/java/com/strategysquad/agentic/risk/RiskGuardService.java` — stub that always returns `RiskGuardSnapshot` with `decision = ALLOW`. Javadoc must say: *"Stub implementation. Full enforcement in Phase 4."*
6. Run `mvn -DskipTests compile` then `mvn test`. All tests — including Rules 1–5 from this session — must remain green.
7. Update `docs/dev-tasks.json`: set S2C to `completed`. Write `docs/handoff-S2C.md`.

---

## Key gotchas for S2C

- **Rule 6 `ENTER` guard:** `BLOCK_NEW_ENTRY` check must come before Rule 6. The current implementation already handles this because Rule 4 catches `BLOCK_NEW_ENTRY + noSession` before falling through to Rule 6. Verify the test explicitly asserts no `ENTER` when guard is `BLOCK_NEW_ENTRY`.
- **Rule 7 requires `SignalSnapshot.thetaProgressRatio()`** — check the exact field name in `SignalSnapshot.java` before writing Rule 7. The field in the plan is `theta_progress_ratio`; the Java record accessor follows camelCase conventions.
- **`RiskGuardService` input type:** Task 2.6 references a `RiskGuardInput` record, but that record is defined in Task 4.2 (Phase 4). For the Phase 2 stub, accept `Object` or define a minimal placeholder — do not block on Phase 4 types. Alternatively, scope the stub method to accept `DecisionContext` directly since that is what `DecisionAgent` already has.
- **`PositionSessionSnapshot` fields used in Rules 7–9** — read the full record before referencing fields. The record has `sessionId`, `mode`, `status`, `legs`, `auditLog`, `livePnl` etc — verify each before use.
