# Session S2A Handoff Note

**Date:** 2026-04-26  
**Session:** S2A — Phase 2, Tasks 2.1, 2.2, and 2.3  
**Status:** All four contract files written and compiled clean. **`mvn -DskipTests compile` confirmed via `.class` file presence in `target/classes` — all six expected class files present (DecisionCommand + 2 nested enum classes, DecisionContext, RiskGuardDecision, RiskGuardSnapshot).** No tests were required for this session (pure data contracts, no logic).

---

## What was done

### Task 2.1 — `DecisionCommand` record

**File created:** `src/main/java/com/strategysquad/agentic/decision/DecisionCommand.java`

Immutable Java record with 10 fields and two embedded enums:

| Field | Type | Notes |
|---|---|---|
| `commandId` | `UUID` | Primary key in audit table and live-assist confirmation token |
| `issuedTs` | `Instant` | SimulationClock time in simulation mode |
| `mode` | `Mode` (enum) | SIMULATION / PAPER / LIVE_ASSIST |
| `commandType` | `CommandType` (enum) | 9 values — see below |
| `selectedCandidateIds` | `List<String>` | Defensive `List.copyOf`; empty for non-entry commands |
| `positionSessionId` | `Optional<String>` | Empty for ENTER/SKIP when no session exists |
| `reasonCode` | `String` | Machine-readable; must not be blank in a complete command |
| `explanation` | `String` | Trader-readable; shown in live-assist UI and reports |
| `riskGuardDecision` | `RiskGuardDecision` | Guard verdict at time of issue |
| `overriddenByRiskGuard` | `boolean` | True when guard overrode the policy's preferred command |

**`Mode` enum values:** `SIMULATION`, `PAPER`, `LIVE_ASSIST`

**`CommandType` enum values (9):** `ENTER`, `ADD`, `REDUCE`, `SHIFT_STRIKE`, `HOLD`, `BOOK_PROFIT`, `EXIT_LEG`, `EXIT_ALL`, `SKIP`

The compact constructor enforces non-null on all 10 fields and performs a defensive `List.copyOf` on `selectedCandidateIds` to prevent external mutation.

---

### Task 2.3 (part 1) — `RiskGuardDecision` enum

**File created:** `src/main/java/com/strategysquad/agentic/risk/RiskGuardDecision.java`

**6 values ordered by severity (least → most restrictive):**

| Value | Effect |
|---|---|
| `ALLOW` | Policy command applied unchanged |
| `WARN` | Policy command applied; audit annotated; booking still permitted |
| `BLOCK_NEW_ENTRY` | ENTER/ADD downgraded to SKIP; existing position management continues |
| `FORCE_REDUCE` | Any non-exit command overridden to REDUCE; `overriddenByRiskGuard = true` |
| `FORCE_EXIT` | EXIT_ALL emitted regardless of policy; session closed |
| `HALT_SESSION` | EXIT_ALL emitted; orchestrator transitions to HALTED; operator reset required |

Written as a top-level enum (not nested) so it can be imported independently by `DecisionCommand`, `DecisionContext`, and `RiskGuardSnapshot`.

---

### Task 2.3 (part 2) — `RiskGuardSnapshot` record

**File created:** `src/main/java/com/strategysquad/agentic/risk/RiskGuardSnapshot.java`

Immutable Java record with 12 fields:

| Field | Type | Notes |
|---|---|---|
| `snapshotTs` | `Instant` | SimulationClock time in simulation mode |
| `decision` | `RiskGuardDecision` | The verdict; drives Decision Agent override logic |
| `triggeredConditions` | `List<String>` | **Never null** — `List.copyOf` in compact constructor; empty only when ALLOW |
| `explanation` | `String` | Trader-readable; shown in live-assist UI |
| `netDelta` | `double` | Signed dimensionless ratio; zero when no session |
| `livePnl` | `double` | NSE index points; positive = profitable short structure |
| `maxLossBreached` | `boolean` | Triggers minimum FORCE_EXIT |
| `premiumExpansionAlert` | `boolean` | Triggers minimum WARN, escalates to FORCE_REDUCE |
| `liquidityAlert` | `boolean` | Triggers BLOCK_NEW_ENTRY or FORCE_EXIT |
| `dataStale` | `boolean` | Triggers BLOCK_NEW_ENTRY |
| `churnDetected` | `boolean` | Triggers HALT_SESSION |
| `lotCapBreached` | `boolean` | Triggers BLOCK_NEW_ENTRY |

The `triggered_conditions` never-null invariant is enforced in the compact constructor via `List.copyOf`. An `IllegalArgumentException` is thrown if `null` is passed — this surfaces the bug at the call site rather than silently producing incomplete audit records.

---

### Task 2.2 — `DecisionContext` record

**File created:** `src/main/java/com/strategysquad/agentic/decision/DecisionContext.java`

Immutable Java record with 11 fields — the single input bundle the Decision Agent passes to `DecisionPolicy`:

| Field | Type | Notes |
|---|---|---|
| `contextTs` | `Instant` | SimulationClock time in simulation mode |
| `mode` | `DecisionCommand.Mode` | Propagated from agent configuration |
| `rankedCandidates` | `List<CandidateOpportunity>` | Defensive `List.copyOf`; never null |
| `signalSnapshots` | `Map<String, SignalSnapshot>` | Keyed by instrumentId; defensive `Map.copyOf`; never null |
| `activeSession` | `Optional<PositionSessionSnapshot>` | `Optional.empty()` when no session open |
| `livePnl` | `double` | NSE index points; positive = profitable |
| `bookedPnl` | `double` | NSE index points; cumulative realised PnL for the day |
| `riskGuardSnapshot` | `RiskGuardSnapshot` | Full guard snapshot; never null |
| `maxLotCap` | `int` | Total lot cap across all active legs combined |
| `cooldownActive` | `boolean` | True when a post-adjustment cooldown is still running |
| `churnGuardActive` | `boolean` | Mirrors `RiskGuardSnapshot.churnDetected()` for policy convenience |

The compact constructor enforces non-null on all fields and performs defensive copies: `List.copyOf` on `rankedCandidates`, `Map.copyOf` on `signalSnapshots`. Callers must pass `Optional.empty()` (not `null`) for `activeSession`.

Cross-package imports resolved correctly: `CandidateOpportunity` from `agentic.scanner`, `SignalSnapshot` from `agentic.signal`, `PositionSessionSnapshot` from `research`.

---

## Compile verification

`mvn -DskipTests compile` was confirmed clean via `.class` file presence in `target/classes/com/strategysquad/agentic/`:

```
decision/DecisionCommand.class
decision/DecisionCommand$Mode.class
decision/DecisionCommand$CommandType.class
decision/DecisionContext.class
risk/RiskGuardDecision.class
risk/RiskGuardSnapshot.class
```

(Plus all 16 Phase 1 class files which remain unchanged.)

---

## What the next session (S2B) must do

1. Read `docs/agentic-loop-implementation-plan.md` Task 2.4 in full before writing any code.
2. Read the four new contract files from this session before writing `DecisionPolicy` — the field names and enum constants are the source of truth.
3. Create `src/main/java/com/strategysquad/agentic/decision/DecisionPolicy.java` implementing rules 1–5 in priority order:
   - Rule 1: `HALT_SESSION` → `EXIT_ALL` with `overriddenByRiskGuard = true`
   - Rule 2: `FORCE_EXIT` → `EXIT_ALL` with override
   - Rule 3: `FORCE_REDUCE` → `REDUCE` with override
   - Rule 4: `BLOCK_NEW_ENTRY` + no active session → `SKIP`
   - Rule 5: no active session + no qualified candidates → `SKIP`
4. Define `BOOKING_THRESHOLD = 0.75` as a named constant (not a magic number inline).
5. Create `src/test/java/com/strategysquad/agentic/decision/DecisionPolicyTest.java` with one test method per rule for rules 1–5. All tests must be pure in-memory — no DB, no HTTP.
6. **Run `mvn -DskipTests compile` then `mvn test` — all tests must pass before stopping.**
7. Update `docs/dev-tasks.json`: set S2B to `completed`, fill `filesChanged`. Write handoff note `docs/handoff-S2B.md`.

---

## Build verification required

Maven (3.x) and Java 17 are required. Run on the Windows host:

```
mvn -DskipTests compile
mvn test
```

Expected: compile clean, all existing tests pass. No new tests were written in this session (Tasks 2.1–2.3 are pure data contracts — no executable logic to test).
