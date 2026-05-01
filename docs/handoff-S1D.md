# Session S1D Handoff Note

**Date:** 2026-04-25  
**Session:** S1D — Phase 1, Tasks 1.5 and 1.6  
**Status:** Code written and reviewed. **Compile + test must be run by operator on the Windows host (`mvn -DskipTests compile && mvn test`) before marking fully green.** The sandbox only had Java 11; project requires Java 17.

---

## What was done

### Task 1.5 — GET /api/agentic/scanner/candidates

**Files changed:**
- `src/main/java/com/strategysquad/research/ResearchConsoleServer.java`
- `src/main/java/com/strategysquad/agentic/scanner/ScannerQuery.java`

**Changes to `ResearchConsoleServer`:**
- Added imports: `CandidateOpportunity`, `MorningScannerService`, `ScannerQuery`, `CandidateScoringEngine`
- Registered `new AgenticScannerHandler(jdbcUrl)` at `/api/agentic/scanner/candidates` — unconditionally wired (no null guard needed; jdbcUrl is always available)
- Added `AgenticScannerHandler` inner class: GET only, OPTIONS CORS preflight, 400 for missing/invalid underlying, 500 for SQLException, never touches position state
- Added `toCandidatesJson(List<CandidateOpportunity>)` — returns `[]` for empty list
- Added `toCandidateJson(CandidateOpportunity)` — full 23-field JSON object using existing `decimalOrNull` and `escapeJson` helpers

**Changes to `ScannerQuery`:**
- Added `loadCohortMap(String underlying)` public method — queries `options_context_buckets` for all rows matching the underlying, returns `Map<CandidateScoringEngine.CohortKey, OptionsContextBucket>`. Empty map if table has no rows for that underlying (causes all contracts to be disqualified as `MISSING_COHORT` by the scoring engine — correct behaviour).
- Added SQL constant `SELECT_CONTEXT_BUCKETS_SQL`
- Added imports: `OptionsContextBucket`, `HashMap`, `Map`

**Simulation support:** When `mode=simulation` and `simulationTs` is a valid ISO-8601 timestamp, a `SimulationClock` is constructed and fixed to that instant. The `ScannerQuery` and `scanInstant` both use that timestamp so the scan is point-in-time consistent.

**Read-only guarantee:** `AgenticScannerHandler` constructs a `MorningScannerService` and calls `scan()` — no call to `PositionSessionActionService` or any mutation path anywhere.

**Cross-package type note:** `CandidateScoringEngine.CohortKey` is package-private. The handler uses `var cohortMap = scannerQuery.loadCohortMap(underlying)` to avoid naming the type explicitly. The inferred type is structurally compatible with `MorningScannerService.scan()`'s parameter — this is valid Java 17. No cast required.

---

### Task 1.6 — SignalSnapshot record

**File created:** `src/main/java/com/strategysquad/agentic/signal/SignalSnapshot.java`

All 17 blueprint fields are present with full Javadoc including explicit unit documentation:

| Field | Type | Nullable |
|---|---|---|
| `signalTs` | `Instant` | No |
| `instrumentId` | `String` | No |
| `underlying` | `String` | No |
| `optionType` | `String` | No |
| `strike` | `BigDecimal` | No |
| `empiricalDelta2m` | `BigDecimal` | Yes |
| `empiricalDelta5m` | `BigDecimal` | Yes |
| `empiricalDeltaSod` | `BigDecimal` | Yes |
| `underlyingMove2m` | `BigDecimal` | Yes |
| `optionMove2m` | `BigDecimal` | Yes |
| `deltaAdjustedTheta2m` | `BigDecimal` | Yes |
| `expectedDecaySinceEntry` | `BigDecimal` | Yes |
| `thetaProgressRatio` | `BigDecimal` | Yes |
| `thetaState` | `ThetaState` | No |
| `volumeState` | `VolumeState` | No |
| `stale` | `boolean` | — |
| `reason` | `String` | No |

Enums nested inside the record (Java 16+ allows enums in records):
- `ThetaState`: `PROFIT_BOOK`, `HOLD`, `DEFENSIVE_EXIT` — with Decision Agent usage guidance in Javadoc
- `VolumeState`: `CONFIRMED`, `LOW`, `ABSENT` — with Decision Agent usage guidance in Javadoc

The compact constructor enforces non-null invariants on the 7 required non-nullable fields. Nullable fields (delta windows, decay metrics) are documented as null when data is unavailable (zero underlying move, missing entry price, insufficient history).

---

## What the next session (S1E) must do

1. Read `docs/agentic-loop-implementation-plan.md` Task 1.7 in full before writing any code.
2. Read the **full source** of `src/main/java/com/strategysquad/research/DeltaAdjustmentService.java` — the extraction must preserve all existing math exactly.
3. Create `SignalSnapshotService` by extracting the empirical delta and delta-adjusted theta computation from `DeltaAdjustmentService`.
4. Refactor `DeltaAdjustmentService` to call `SignalSnapshotService` — no semantic change.
5. Verify `DeltaAdjustmentServiceTest` passes without modification to the test class.
6. Create `SignalSnapshotServiceTest` covering normal snapshot, stale data, zero-move division-by-zero guard, missing entry price.
7. Create `Phase1SmokeTest` using historical fixtures.
8. **Run `mvn -DskipTests compile` then `mvn test` — ALL tests including existing ones must pass.**

---

## Build verification required

Maven (3.x) and Java 17 are required. Run on the Windows host:

```
mvn -DskipTests compile
mvn test
```

Expected: compile clean, all existing tests pass (ScannerQueryTest, CandidateScoringEngineTest, MorningScannerServiceTest, and all pre-existing tests). No new tests were required for tasks 1.5 and 1.6 per the implementation plan — Task 1.5 is wiring only (the underlying scanner service already has tests), and Task 1.6 is a pure data contract record.
