# Agentic Loop Implementation Plan

This document is the task-based execution plan for building the agentic theta-decay decision loop
described in `docs/agentic-live-trading-decision-loop.md`.

Every task maps to a specific deliverable. Tasks within a phase are ordered by dependency.
No phase should begin until the previous phase's acceptance criteria are fully met.

---

## Reading this document

Each task entry contains:

- **Goal** — what must exist when the task is done
- **Inputs** — existing code or data the task consumes
- **Outputs** — new files, types, or behaviours produced
- **Acceptance criteria** — the observable conditions that mark the task complete
- **Do not** — explicit anti-patterns or guard rails for that task

---

## Phase 0 — Documentation and alignment

**Objective:** Ensure every contributor understands the gap between current state and roadmap target
before any agentic code is written.

**Done when:** No document implies the current code already has full agentic decision orchestration.
Future contributors know where the roadmap lives and what is missing.

---

### Task 0.1 — Verify current-state notes in README and roadmap doc

**Goal:** README and `agentic-live-trading-decision-loop.md` both accurately reflect what the
current codebase does and does not do. No optimistic over-statement of completed capability.

**Inputs:**
- `README.md`
- `docs/agentic-live-trading-decision-loop.md`
- Current Java source tree

**Outputs:**
- Corrected or confirmed README roadmap status section
- Confirmed gap table in the blueprint doc

**Acceptance criteria:**
- "Where We Stand" gap table in `agentic-live-trading-decision-loop.md` matches the actual Java
  source tree (no scanner package, no decision package, no orchestrator)
- README roadmap status bullet accurately says the missing layer has not been built yet

**Do not:** Add any placeholder Java files or stub classes in this task.

---

### Task 0.2 — Update developer-notes.md for agentic-loop work

**Goal:** `docs/developer-notes.md` has a section telling contributors what to read and check
before touching any file in the new `agentic/` package tree.

**Inputs:**
- `docs/developer-notes.md`
- `docs/agentic-live-trading-decision-loop.md`

**Outputs:**
- Updated `docs/developer-notes.md` with an "Agentic loop" section covering:
  - required reading (blueprint doc, domain contract)
  - the audit trail requirement (every command must have a reason code and explanation)
  - the simulation-first rule (no position mutation until replay validation)
  - the Risk Guard gate rule (guard evaluated before every command application)

**Acceptance criteria:**
- Section is present, accurate, and not padded with aspirational claims

---

### Task 0.3 — Confirm domain contract coverage for agentic outputs

**Goal:** Verify that `docs/options-strategy-domain-contract.md` covers the new output types
(`CandidateOpportunity`, `SignalSnapshot`, `DecisionCommand`, `PositionPlan`, `RiskGuardSnapshot`).
Add a note for each if it is absent.

**Inputs:**
- `docs/options-strategy-domain-contract.md`
- `docs/agentic-live-trading-decision-loop.md` agent contracts section

**Outputs:**
- Additions to `docs/options-strategy-domain-contract.md` covering unit and sign conventions for
  the five new output types

**Acceptance criteria:**
- Each of the five types has at least one explicit rule covering units, sign convention, and
  trader-readability requirement

---

## Phase 1 — Scanner and signal snapshots

**Objective:** Produce ranked short-option candidates from live and simulation context. Extract
empirical delta and theta into a reusable, persistable `SignalSnapshot`. No position mutation.

**Done when:** Simulation and live modes can produce ranked candidates without touching positions.
Every candidate has a score breakdown and a disqualifier reason when rejected.

---

### Task 1.1 — Define `CandidateOpportunity` record

**Goal:** A clean, immutable Java record that carries all scanner output fields defined in the
blueprint.

**Output file:**
`src/main/java/com/strategysquad/agentic/scanner/CandidateOpportunity.java`

**Required fields (all from blueprint):**
`candidate_id`, `underlying`, `instrument_id`, `trading_symbol`, `option_type`, `strike`,
`expiry_date`, `expiry_type`, `spot`, `last_price`, `bid_price`, `ask_price`,
`moneyness_points`, `moneyness_bucket`, `time_bucket_15m`, `historical_avg_price`,
`premium_richness_points`, `premium_richness_pct`, `liquidity_score`, `theta_opportunity_score`,
`delta_risk_score`, `total_score`, `disqualifier_reason`.

**Acceptance criteria:**
- All fields present with explicit Java types and inline Javadoc units
- `disqualifier_reason` is `Optional<String>` (null-safe)
- No mutable setters

**Do not:** Add any database write logic here.

---

### Task 1.2 — Build `ScannerQuery` — active weekly contract fetch

**Goal:** A single-responsibility class that queries `instrument_master` and `options_live` (or
`options_historical` in simulation mode) to return the universe of active weekly NIFTY and
BANKNIFTY option contracts eligible for scanning.

**Output file:**
`src/main/java/com/strategysquad/agentic/scanner/ScannerQuery.java`

**Inputs consumed:**
- `instrument_master` (filter: `is_active = true`, `expiry_type = WEEKLY`, underlying in
  `{NIFTY, BANKNIFTY}`)
- Latest live spot from `spot_live` (or historical spot in simulation mode via `SimulationClock`)
- Latest option price from `options_live` or `options_historical` as appropriate

**Acceptance criteria:**
- Returns only weekly contracts
- Handles simulation clock: when `SimulationClock` is active, reads historical data at-or-before
  the simulation timestamp
- Returns an empty list (not an exception) when no active contracts are found
- Has a unit test with an in-memory fixture covering at least: found contracts, no contracts,
  simulation mode

**Do not:** Apply any scoring in this class. Scoring is `CandidateScoringEngine`'s job.

---

### Task 1.3 — Build `CandidateScoringEngine`

**Goal:** Score each raw contract row from `ScannerQuery` into a `CandidateOpportunity`.

**Output file:**
`src/main/java/com/strategysquad/agentic/scanner/CandidateScoringEngine.java`

**Scoring components (all must be computed and stored separately):**

1. **Premium richness** — compare `last_price` against `historical_avg_price` from
   `options_context_buckets` for the matching `(underlying, option_type, moneyness_bucket,
   time_bucket_15m)` cohort. Express as both points and percent.

2. **Liquidity score** — derived from bid-ask spread width and volume relative to historical
   average volume from `options_context_buckets`. Score 0–1, where 1 is tightest spread and
   highest relative volume.

3. **Theta opportunity score** — based on premium richness percent and time bucket. Higher score
   for contracts with meaningful premium at shorter time-to-expiry buckets.

4. **Delta risk score** — based on moneyness points and empirical delta when available. Higher
   score = lower delta risk. Penalise near-ATM contracts when empirical delta is uncertain.

5. **Total score** — weighted sum. Default weights:
   `theta_opportunity(0.40) + premium_richness(0.30) + liquidity(0.20) + delta_risk(0.10)`.
   Weights must be constants, not magic numbers inline.

6. **Disqualification** — a contract is disqualified (not scored) if any of:
   - No historical cohort match (cannot compute richness)
   - Bid price is zero or bid-ask spread exceeds configured maximum
   - Volume is zero
   - DTE is outside configured minimum/maximum window
   Disqualified contracts still appear in output with `disqualifier_reason` set and all scores
   zero.

**Acceptance criteria:**
- Unit test covers: normal scoring, disqualification for zero bid, disqualification for missing
  cohort, disqualification for zero volume
- Premium richness math is tested with known inputs and expected outputs
- No database access in scoring logic (data is passed in, not fetched)

**Do not:** Apply any position-mutation logic. Do not call `PositionSessionActionService`.

---

### Task 1.4 — Build `MorningScannerService`

**Goal:** Orchestrate `ScannerQuery` + `CandidateScoringEngine` into a single callable that
returns a ranked list of `CandidateOpportunity` rows.

**Output file:**
`src/main/java/com/strategysquad/agentic/scanner/MorningScannerService.java`

**Behaviour:**
- Calls `ScannerQuery` to load the universe
- Calls `CandidateScoringEngine` to score each contract
- Returns results sorted descending by `total_score`
- Disqualified candidates appear at the bottom of the list, not filtered out
- Logs a summary line: total candidates, qualified count, top candidate instrument_id and score

**Acceptance criteria:**
- Integration test that passes a multi-contract fixture through the full scanner pipeline and
  asserts correct rank order
- Simulation and live modes both work (controlled by `SimulationClock` injection)
- Returns an empty ranked list (not an exception) when no contracts qualify

---

### Task 1.5 — Add read-only scanner REST endpoint

**Goal:** Expose scanner results via a GET endpoint so the research console can display
candidates.

**Endpoint:** `GET /api/agentic/scanner/candidates`

**Query parameters:**
- `underlying` (required): `NIFTY` or `BANKNIFTY`
- `mode` (optional, default `live`): `live` or `simulation`
- `simulationTs` (optional, ISO timestamp): simulation clock override

**Response:** JSON array of `CandidateOpportunity` rows, sorted by `total_score` descending.

**Integration point:** Wire into `ResearchConsoleServer` alongside existing endpoints.

**Acceptance criteria:**
- Endpoint returns 200 with a valid JSON array when data is available
- Endpoint returns 200 with an empty array when no candidates qualify (not 404 or 500)
- Invalid `underlying` returns 400

**Do not:** Allow this endpoint to mutate position state.

---

### Task 1.6 — Define `SignalSnapshot` record

**Goal:** An immutable Java record for the per-contract signal output from the theta/delta signal
engine.

**Output file:**
`src/main/java/com/strategysquad/agentic/signal/SignalSnapshot.java`

**Required fields (from blueprint):**
`signal_ts`, `instrument_id`, `underlying`, `option_type`, `strike`,
`empirical_delta_2m`, `empirical_delta_5m`, `empirical_delta_sod`,
`underlying_move_2m`, `option_move_2m`, `delta_adjusted_theta_2m`,
`expected_decay_since_entry`, `theta_progress_ratio`, `theta_state`, `volume_state`,
`stale`, `reason`.

**Acceptance criteria:**
- All fields present with explicit Java types and inline Javadoc
- `theta_state` is an enum: `PROFIT_BOOK`, `HOLD`, `DEFENSIVE_EXIT`
- `volume_state` is an enum: `CONFIRMED`, `LOW`, `ABSENT`
- `stale` is a boolean with a Javadoc explaining the staleness condition

**Do not:** Add any scoring or decision logic to this record.

---

### Task 1.7 — Extract `SignalSnapshotService` from `DeltaAdjustmentService`

**Goal:** Move empirical delta and delta-adjusted theta computation out of
`DeltaAdjustmentService` into a standalone, reusable `SignalSnapshotService`. Keep
`DeltaAdjustmentService` as a consumer of `SignalSnapshotService`.

**Output file:**
`src/main/java/com/strategysquad/agentic/signal/SignalSnapshotService.java`

**Computation rules (must not change behaviour):**
```
empirical_delta = option_price_change / underlying_price_change
expected_delta_move = empirical_delta * underlying_change
delta_adjusted_theta = actual_option_price_change - expected_delta_move
```
For short legs, theta benefit is positive when option price falls more than expected.

**Inputs consumed:**
- `options_enriched` (or `options_live` + live enrichment) for the 2m, 5m, and start-of-day
  windows
- Entry price from the position session leg (for `expected_decay_since_entry` and
  `theta_progress_ratio`)

**Acceptance criteria:**
- `DeltaAdjustmentServiceTest` still passes without modification to the test class
- New `SignalSnapshotServiceTest` covers: normal snapshot construction, stale-data handling,
  zero underlying move (guard against division by zero), missing entry price fallback
- Empirical delta math tested with known inputs

**Do not:** Delete or weaken any existing `DeltaAdjustmentService` logic. Only extract — do not
change semantics.

---

### Task 1.8 — Write Phase 1 integration smoke test

**Goal:** A single test class that exercises the full Phase 1 path end to end using historical
fixtures: `ScannerQuery` → `CandidateScoringEngine` → `MorningScannerService` →
`SignalSnapshotService`.

**Output file:**
`src/test/java/com/strategysquad/agentic/Phase1SmokeTest.java`

**Acceptance criteria:**
- Uses a small set of pre-loaded historical rows (no live DB required)
- Asserts: at least one candidate is returned, the top candidate has `disqualifier_reason` empty,
  a `SignalSnapshot` is computable for the top candidate
- Test passes with `mvn test`

---

## Phase 2 — Decision Agent in paper mode

**Objective:** A deterministic policy engine that converts scanner candidates, signal snapshots,
position context, and risk state into one explicit `DecisionCommand`. Runs in paper mode only:
produces and audits decisions without mutating position state.

**Done when:** The system can explain why it would enter, hold, skip, book, reduce, or exit for
any given market snapshot. Every decision has an audit record. No position is mutated.

---

### Task 2.1 — Define `DecisionCommand` record

**Goal:** The canonical output contract of the Decision Agent.

**Output file:**
`src/main/java/com/strategysquad/agentic/decision/DecisionCommand.java`

**Required fields:**
- `command_id` (UUID)
- `issued_ts` (Instant)
- `mode` (enum: `SIMULATION`, `PAPER`, `LIVE_ASSIST`)
- `command_type` (enum: `ENTER`, `ADD`, `REDUCE`, `SHIFT_STRIKE`, `HOLD`, `BOOK_PROFIT`,
  `EXIT_LEG`, `EXIT_ALL`, `SKIP`)
- `selected_candidate_ids` (List<String>, empty for non-entry commands)
- `position_session_id` (Optional<String>)
- `reason_code` (String — machine-readable, e.g. `PREMIUM_RICH_LOW_DELTA`)
- `explanation` (String — trader-readable, e.g. "CE 24800 premium 18% above historical average,
  empirical delta 0.12, entering short straddle")
- `risk_guard_decision` (RiskGuardDecision enum — see Task 2.3)
- `overridden_by_risk_guard` (boolean)

**Acceptance criteria:**
- All fields present with Javadoc
- `command_type` and `mode` are enums, not strings
- Immutable record — no setters

---

### Task 2.2 — Define `DecisionContext` record

**Goal:** A single snapshot of all inputs the Decision Agent requires. Avoids passing many
arguments into the decision policy.

**Output file:**
`src/main/java/com/strategysquad/agentic/decision/DecisionContext.java`

**Fields:**
- `context_ts` (Instant)
- `mode` (Mode enum)
- `ranked_candidates` (List<CandidateOpportunity>)
- `signal_snapshots` (Map<String, SignalSnapshot> keyed by instrument_id)
- `active_session` (Optional<PositionSessionSnapshot>)
- `live_pnl` (double, points)
- `booked_pnl` (double, points)
- `risk_guard_snapshot` (RiskGuardSnapshot — see Task 2.3)
- `max_lot_cap` (int)
- `cooldown_active` (boolean)
- `churn_guard_active` (boolean)

**Acceptance criteria:**
- Immutable record
- All Optional fields are null-safe

---

### Task 2.3 — Define `RiskGuardSnapshot` and `RiskGuardDecision` enum

**Goal:** The output contract of the Risk Guard (built fully in Phase 4, but the contract must
exist in Phase 2 so the Decision Agent can consume it).

**Output files:**
- `src/main/java/com/strategysquad/agentic/risk/RiskGuardSnapshot.java`
- `src/main/java/com/strategysquad/agentic/risk/RiskGuardDecision.java`

**`RiskGuardDecision` values:** `ALLOW`, `WARN`, `BLOCK_NEW_ENTRY`, `FORCE_REDUCE`,
`FORCE_EXIT`, `HALT_SESSION`

**`RiskGuardSnapshot` fields:**
- `snapshot_ts` (Instant)
- `decision` (RiskGuardDecision)
- `triggered_conditions` (List<String> — machine-readable condition codes)
- `explanation` (String — trader-readable)
- `net_delta` (double)
- `live_pnl` (double)
- `max_loss_breached` (boolean)
- `premium_expansion_alert` (boolean)
- `liquidity_alert` (boolean)
- `data_stale` (boolean)
- `churn_detected` (boolean)
- `lot_cap_breached` (boolean)

**Acceptance criteria:**
- Both files are immutable records/enums with Javadoc
- `triggered_conditions` is never null (use empty list)

---

### Task 2.4 — Build `DecisionPolicy` — core rule engine

**Goal:** A pure function (no I/O, no DB access) that maps `DecisionContext` → `DecisionCommand`
according to the rules in the blueprint.

**Output file:**
`src/main/java/com/strategysquad/agentic/decision/DecisionPolicy.java`

**Rules to implement (in priority order):**

1. If `risk_guard_snapshot.decision` is `HALT_SESSION` → emit `EXIT_ALL` with
   `overridden_by_risk_guard = true`
2. If `risk_guard_snapshot.decision` is `FORCE_EXIT` → emit `EXIT_ALL` with override
3. If `risk_guard_snapshot.decision` is `FORCE_REDUCE` → emit `REDUCE` with override
4. If `risk_guard_snapshot.decision` is `BLOCK_NEW_ENTRY` and no active session → emit `SKIP`
5. If no active session and no qualified candidates → emit `SKIP`
6. If no active session and candidates are available and risk allows → emit `ENTER` for the
   top-ranked non-disqualified candidate
7. If active session and `theta_progress_ratio >= booking_threshold` and PnL positive and risk is
   `ALLOW` or `WARN` → emit `BOOK_PROFIT`
8. If active session and delta-adjusted theta is negative (premium expanding) → emit `REDUCE`
9. If active session and all signals are `HOLD` and risk allows → emit `HOLD`
10. Default → emit `HOLD`

**Thresholds:** `booking_threshold = 0.75` (configurable, not magic number).

**Acceptance criteria:**
- Unit test covers every rule branch
- Tests are pure in-memory (no DB, no HTTP)
- `ENTER` is never emitted when `BLOCK_NEW_ENTRY` or worse is active
- `BOOK_PROFIT` is never emitted when PnL is negative

**Do not:** Allow any I/O inside `DecisionPolicy`. All inputs arrive via `DecisionContext`.

---

### Task 2.5 — Build `DecisionAgent`

**Goal:** The orchestrating service that assembles `DecisionContext`, calls `DecisionPolicy`,
and writes an audit record. In Phase 2, it does not mutate position state.

**Output file:**
`src/main/java/com/strategysquad/agentic/decision/DecisionAgent.java`

**Responsibilities:**
- Calls `MorningScannerService` to get ranked candidates
- Calls `SignalSnapshotService` for the relevant instruments
- Calls `ResearchPositionSessionService` for the active session snapshot
- Calls `RiskGuardService` (stub in Phase 2, see Task 2.6) for the risk snapshot
- Builds `DecisionContext`
- Calls `DecisionPolicy` to produce `DecisionCommand`
- Writes the audit record (see Task 2.7)
- Returns the `DecisionCommand` — does not apply it

**Acceptance criteria:**
- In paper mode, no call to `PositionSessionActionService` is made
- Audit record is written even if the policy returns `SKIP`

---

### Task 2.6 — Build stub `RiskGuardService` for Phase 2

**Goal:** A minimal `RiskGuardService` that always returns `ALLOW` in Phase 2, satisfying the
`DecisionAgent`'s dependency. The full implementation is Phase 4.

**Output file:**
`src/main/java/com/strategysquad/agentic/risk/RiskGuardService.java`

**Interface:**
```java
RiskGuardSnapshot evaluate(RiskGuardInput input);
```

**Phase 2 implementation:** Always returns `RiskGuardSnapshot` with `decision = ALLOW`.

**Acceptance criteria:**
- `DecisionAgent` compiles and runs using this stub
- Javadoc on the class explicitly states: "Stub implementation. Full enforcement in Phase 4."

---

### Task 2.7 — Build `DecisionAuditWriter`

**Goal:** Persist every `DecisionCommand` to a structured audit log.

**Output file:**
`src/main/java/com/strategysquad/agentic/decision/DecisionAuditWriter.java`

**Audit record fields (all from the blueprint "Required Audit Trail" section):**
`timestamp`, `mode`, `state_machine_state`, `command_type`, `selected_candidate_ids`,
`position_session_id`, `live_spot`, `net_delta_before`, `net_delta_after`, `theta_state`,
`theta_progress_ratio`, `live_pnl`, `booked_pnl`, `liquidity_score`, `risk_guard_decision`,
`reason_code`, `explanation`.

**Storage:** Write to a new QuestDB table `agentic_decision_audit`. Include DDL in a new migration
file `db/migration/V005__agentic_audit.sql`.

**Acceptance criteria:**
- DDL migration file is present and correct
- Writer test verifies that a `DecisionCommand` round-trips correctly through write + read
- Writer does not throw on null optional fields

---

### Task 2.8 — Write Phase 2 replay test

**Goal:** A test that replays several historical market days through `DecisionAgent` in paper mode
and asserts that decisions are reasonable and the audit log is populated.

**Output file:**
`src/test/java/com/strategysquad/agentic/Phase2ReplayTest.java`

**Acceptance criteria:**
- At least 3 distinct market-day scenarios tested (entering, holding, booking)
- Each scenario asserts: correct `command_type`, non-empty `reason_code`, non-empty `explanation`,
  audit record written
- No position session is mutated in any scenario

---

## Phase 3 — Position Builder and profit booking

**Objective:** Turn an `ENTER` command into a concrete position plan and execute it. Add a
dedicated profit-booking flow. Run simulation end-to-end: open, monitor, book, restart scan.

**Done when:** A simulation run can open a structure, monitor it, book profit, and restart
scanning. Booked PnL remains correct after partial and full exits.

---

### Task 3.1 — Define `PositionPlan` record

**Goal:** The output contract of the Position Builder Agent.

**Output file:**
`src/main/java/com/strategysquad/agentic/builder/PositionPlan.java`

**Fields:**
- `plan_id` (UUID)
- `planned_ts` (Instant)
- `underlying` (String)
- `legs` (List<PositionPlanLeg>)
- `estimated_net_delta` (double)
- `estimated_total_premium` (double, points)
- `structure_type` (String: `SHORT_STRADDLE`, `SHORT_STRANGLE`)
- `lot_count_per_leg` (int)
- `risk_guard_approved` (boolean)
- `rejection_reason` (Optional<String>)

**`PositionPlanLeg` fields:**
- `instrument_id`
- `option_type`
- `strike`
- `expiry_date`
- `lot_size`
- `lots`
- `entry_price` (points)
- `side` (enum: `SHORT`)

**Acceptance criteria:**
- Immutable records
- `legs` is never null (use empty list for rejected plans)

---

### Task 3.2 — Build `PositionBuilderAgent`

**Goal:** Convert a ranked candidate list into a `PositionPlan` for a short straddle or short
strangle.

**Output file:**
`src/main/java/com/strategysquad/agentic/builder/PositionBuilderAgent.java`

**Rules:**
- NIFTY lot size = 65, BANKNIFTY lot size = 30 (from `instrument_master`, not hard-coded)
- Start with 1 lot per leg
- Total lots (across all legs) must not exceed configured `max_lot_cap`
- Net delta of the proposed structure must be within configured `max_net_delta_threshold`
- Prefer the pair of CE + PE strikes that maximises combined premium richness score while keeping
  net delta within threshold
- Short straddle: same strike for CE and PE (closest to ATM with premium richness confirmed)
- Short strangle: different strikes (CE OTM, PE OTM) chosen by scanner rank
- If no valid plan can be constructed, return a rejected `PositionPlan` with `rejection_reason`
  set

**Acceptance criteria:**
- Unit test covers: valid straddle plan, valid strangle plan, rejection when max delta breached,
  rejection when no qualified candidates
- Net delta computation uses empirical delta from `SignalSnapshot`, not theoretical Black-Scholes

**Do not:** Add market-making or multi-structure logic. One structure at a time.

---

### Task 3.3 — Wire `PositionBuilderAgent` into `DecisionAgent` for `ENTER` commands

**Goal:** When `DecisionPolicy` returns `ENTER`, `DecisionAgent` calls `PositionBuilderAgent` and
uses the resulting `PositionPlan` to open the position session via
`PositionSessionActionService`.

**Changes to:** `DecisionAgent.java`

**Acceptance criteria:**
- Position session is created with leg quantities and entry prices from the `PositionPlan`
- If the plan is rejected, `DecisionAgent` emits `SKIP` instead of `ENTER` and writes the
  rejection reason to the audit record
- In paper mode, position session creation still happens (paper positions are simulated state)
- Test asserts that a simulated `ENTER` correctly creates a session with two legs

---

### Task 3.4 — Build `ProfitBookingAgent`

**Goal:** A dedicated service that evaluates whether to book partial or full profit, and executes
via existing `PositionSessionActionService`.

**Output file:**
`src/main/java/com/strategysquad/agentic/booking/ProfitBookingAgent.java`

**Trigger conditions (all must be true):**
- `theta_progress_ratio >= booking_threshold` (default 0.75, configurable)
- `live_pnl > 0`
- `risk_guard_snapshot.decision` is `ALLOW` or `WARN`

**Booking actions:**
- Partial booking: reduce each leg by 1 lot, write audit reason `theta_capture_profit_book_partial`
- Full booking: exit all legs, write audit reason `theta_capture_profit_book_full`
- Full booking triggers scanner restart (return a `RESTART_SCAN` signal to the caller)

**Partial vs full decision:** Full booking if `theta_progress_ratio >= 0.90`, else partial.

**Acceptance criteria:**
- Unit test covers: partial trigger, full trigger, no trigger (ratio below threshold), no trigger
  (negative PnL), no trigger (risk blocks)
- Booked PnL from `PositionSessionSnapshot` is correct after partial and full booking
- Audit record written for every booking action

**Do not:** Book profit if risk guard is `BLOCK_NEW_ENTRY`, `FORCE_REDUCE`, `FORCE_EXIT`, or
`HALT_SESSION`.

---

### Task 3.5 — Extend `StrategyRunReportService` with Phase 3 fields

**Goal:** Execution reports include scanner ranking summary, decision command history, and
profit-booking context.

**Changes to:** `StrategyRunReportService.java` (and its report template)

**New report sections:**
- Scanner ranking: top 5 candidates at entry time with scores
- Decision history: table of `command_type`, `reason_code`, `explanation` for all commands in run
- Profit booking: for each booking action, `theta_progress_ratio`, `live_pnl`, action taken

**Acceptance criteria:**
- `StrategyRunReportServiceTest` passes
- A sample simulation run generates a report that includes all three new sections

---

### Task 3.6 — Write Phase 3 end-to-end simulation test

**Goal:** A test that runs a complete simulation: pre-open scan → entry → monitoring → profit
booking → scanner restart.

**Output file:**
`src/test/java/com/strategysquad/agentic/Phase3SimulationTest.java`

**Acceptance criteria:**
- Full loop completes without exceptions
- Position session is opened and closed correctly
- Booked PnL is positive (not zero) after the booking step
- Report is generated and contains scanner, decision, and booking sections

---

## Phase 4 — Adjustment expansion and global Risk Guard

**Objective:** Implement `SHIFT_STRIKE`, `EXIT_LEG`, `EXIT_ALL` in the Adjustment Agent.
Replace the Phase 2 Risk Guard stub with full enforcement. Every decision is gated.

**Done when:** No accepted command worsens risk without a hard-risk justification. Every forced
action identifies the guard condition that triggered it.

---

### Task 4.1 — Implement full `RiskGuardService`

**Goal:** Replace the Phase 2 stub with a real implementation of all hard stops listed in the
blueprint.

**Changes to:** `src/main/java/com/strategysquad/agentic/risk/RiskGuardService.java`

**Hard stops to implement:**

| Condition | Minimum triggered decision |
|---|---|
| Net delta beyond critical threshold (configurable, e.g. ±0.30) | `FORCE_REDUCE` |
| Live PnL deterioration beyond max loss (configurable) | `FORCE_EXIT` |
| One leg premium expanding > configured percent above entry | `WARN` or `FORCE_REDUCE` |
| Bid price zero or liquidity absent | `BLOCK_NEW_ENTRY` or `FORCE_EXIT` |
| Data stale beyond configured seconds | `BLOCK_NEW_ENTRY` |
| Max loss or drawdown breached | `FORCE_EXIT` |
| Adjustment churn detected (> N commands in M minutes) | `HALT_SESSION` |
| Total lots exceed cap | `BLOCK_NEW_ENTRY` |
| Missing required signal data | `BLOCK_NEW_ENTRY` |

**Acceptance criteria:**
- Unit test for each hard stop condition
- `triggered_conditions` list is populated with the matching condition code(s) for every non-ALLOW
  decision
- All thresholds are configurable constants, not magic numbers
- `DecisionAgent` Phase 2 replay tests still pass after this change (no regression)

---

### Task 4.2 — Define `RiskGuardInput` record

**Goal:** A clean input contract for `RiskGuardService.evaluate()`.

**Output file:**
`src/main/java/com/strategysquad/agentic/risk/RiskGuardInput.java`

**Fields:**
- `evaluation_ts` (Instant)
- `active_session` (Optional<PositionSessionSnapshot>)
- `signal_snapshots` (Map<String, SignalSnapshot>)
- `live_pnl` (double)
- `booked_pnl` (double)
- `net_delta` (double)
- `lot_count` (int)
- `max_lot_cap` (int)
- `recent_command_count` (int — for churn detection)
- `churn_window_minutes` (int)
- `max_loss_points` (double)
- `stale_data_seconds` (int)
- `last_tick_age_seconds` (int)

**Acceptance criteria:**
- Immutable record with Javadoc on every field

---

### Task 4.3 — Add `SHIFT_STRIKE` to `AdjustmentAgent`

**Goal:** Extend the existing adjustment logic (currently `ADD`/`REDUCE` only) to support strike
shifting: exit the current leg and re-enter at a more favourable strike.

**Output file:**
`src/main/java/com/strategysquad/agentic/adjustment/AdjustmentAgent.java`
(new class; consolidates existing `DeltaAdjustmentService` adjustment actions)

**`SHIFT_STRIKE` rules:**
- Only valid when `SHIFT_STRIKE` is in the command from `DecisionPolicy`
- Must pass Risk Guard before executing
- Exit the leg at current market price (via `PositionSessionActionService`)
- Re-enter at the strike recommended by the scanner for the same option type and expiry
- Both exit and re-entry must write audit records
- Cooldown applies after a shift: no further adjustment on the same leg for a configured period

**Acceptance criteria:**
- Unit test: successful shift, cooldown prevents second shift, risk block prevents shift
- Replay test confirms audit records for both exit and re-entry legs are written

**Do not:** Shift both CE and PE in the same command. One leg per command.

---

### Task 4.4 — Add `EXIT_LEG` and `EXIT_ALL` to `AdjustmentAgent`

**Goal:** Implement leg-level and full-structure exits.

**`EXIT_LEG` rules:**
- Exit only the leg identified by the command (by `instrument_id`)
- Session remains open with remaining legs

**`EXIT_ALL` rules:**
- Exit all active legs at current market prices
- Freeze the session (mark as `CLOSED`)
- Write a session-close audit record with total booked PnL

**Acceptance criteria:**
- Unit test for each action
- After `EXIT_ALL`, the session cannot receive further commands (service must reject them)
- Booked PnL is the sum of all individual leg bookings

---

### Task 4.5 — Gate every command through Risk Guard

**Goal:** Ensure `RiskGuardService.evaluate()` is called before every `DecisionCommand` is
applied, not just before entry.

**Changes to:** `DecisionAgent.java`

**Rule:** If the Risk Guard upgrades to `FORCE_*` or `HALT_SESSION` after the policy has already
selected a non-exit command, the command type is overridden before execution and the audit record
reflects `overridden_by_risk_guard = true`.

**Acceptance criteria:**
- Test: policy selects `HOLD`, Risk Guard returns `FORCE_EXIT` → final command is `EXIT_ALL`
  with override flag set
- Test: policy selects `ENTER`, Risk Guard returns `BLOCK_NEW_ENTRY` → final command is `SKIP`
  with override flag set
- No command reaches `AdjustmentAgent` or `ProfitBookingAgent` without passing through Risk Guard

---

### Task 4.6 — Write Phase 4 risk integration test

**Goal:** A test that simulates a deteriorating position that triggers progressive Risk Guard
escalation.

**Output file:**
`src/test/java/com/strategysquad/agentic/Phase4RiskIntegrationTest.java`

**Scenarios:**
1. Delta breach → `FORCE_REDUCE` overrides `HOLD`
2. Max loss breach → `FORCE_EXIT` closes position
3. Churn detection → `HALT_SESSION` after repeated adjustments
4. Data stale → `BLOCK_NEW_ENTRY` prevents re-entry after booking

**Acceptance criteria:**
- All four scenarios pass
- Audit log entries reflect the override reason for each forced action

---

## Phase 5 — Orchestrator, API, UI, and reports

**Objective:** A market-day state machine that ties all agents together. API endpoints for
agent status. A compact UI panel. End-to-end simulation replay from pre-open scan to report.

**Done when:** A full simulation run replays from pre-open scan to `END_OF_DAY` report. The UI
can show active agent state without becoming an order-entry screen.

---

### Task 5.1 — Implement `MarketDayOrchestrator` state machine

**Goal:** The central service that transitions through the states defined in the blueprint and
calls the correct agents at each state.

**Output file:**
`src/main/java/com/strategysquad/agentic/orchestrator/MarketDayOrchestrator.java`

**State machine:**

| State | Entry action | Exit condition |
|---|---|---|
| `PRE_OPEN_SCAN` | Call `MorningScannerService` | Candidates ready or no candidates |
| `WAIT_MARKET_OPEN` | Poll live readiness via `LiveMarketReadinessService` | Market open and data fresh |
| `EVALUATE_ENTRY` | Call `DecisionAgent` | Command = `ENTER` or `SKIP` |
| `POSITION_OPEN` | Call `PositionBuilderAgent`, open session | Session created |
| `MONITOR` | Call `DecisionAgent` on every tick cycle | Any non-HOLD command |
| `ADJUST` | Call `AdjustmentAgent` | Adjustment complete |
| `BOOK_PROFIT` | Call `ProfitBookingAgent` | Booking complete |
| `RESTART_SCAN` | Call `MorningScannerService` | Next candidates ready or EOD |
| `EXITED` | Log exit | Trigger restart or EOD |
| `END_OF_DAY` | Write report via `StrategyRunReportService` | Terminal |
| `HALTED` | Log halt reason | Terminal until manual reset |

**State persistence:** Persist current state, last command, and last transition timestamp to a new
QuestDB table `agentic_session_state`. DDL in `db/migration/V006__agentic_session_state.sql`.

**Acceptance criteria:**
- State transitions are logged at INFO level with state-in, state-out, and elapsed time
- `HALTED` state can only be exited by an explicit operator reset call (not automatic)
- In simulation mode, time advances via `SimulationClock`, not wall clock

---

### Task 5.2 — Add orchestrator REST APIs

**Goal:** Expose agent and orchestrator status so the UI can display the current loop state.

**New endpoints (wire into `ResearchConsoleServer`):**

| Endpoint | Purpose |
|---|---|
| `GET /api/agentic/status` | Current state machine state, last command, last transition ts |
| `GET /api/agentic/last-decision` | Most recent `DecisionCommand` with explanation |
| `GET /api/agentic/risk-guard` | Most recent `RiskGuardSnapshot` |
| `POST /api/agentic/reset-halt` | Reset `HALTED` state — manual operator action only |
| `POST /api/agentic/simulation/start` | Start a simulation run with a given date range |
| `POST /api/agentic/simulation/stop` | Stop a running simulation |

**Acceptance criteria:**
- All endpoints return valid JSON
- `reset-halt` requires a confirmation token to prevent accidental resets (body: `{"confirm": true}`)
- Simulation start validates that the date range has historical data before accepting

---

### Task 5.3 — Add compact agent-status panel to research console UI

**Goal:** A non-intrusive read-only panel in `ui/scenario-research/` that shows the current agent
state and last decision. Not an order-entry screen.

**Implementation:** HTML + vanilla JS, consistent with the existing UI style (no framework).

**Panel contents:**
- Current state machine state (colour-coded badge)
- Last command type and timestamp
- Last decision explanation (trader-readable text)
- Risk guard status badge
- Top scanner candidate (underlying, strike, score) when in `PRE_OPEN_SCAN` or `EVALUATE_ENTRY`

**Acceptance criteria:**
- Panel polls `/api/agentic/status` and `/api/agentic/last-decision` every 5 seconds
- Panel is read-only: no buttons except a clearly labelled "Emergency Halt" button that calls
  `reset-halt`
- Panel does not display raw Java field names (all labels are trader-readable)
- Works in both live and simulation mode (simulation shows a "SIMULATION" badge)

---

### Task 5.4 — Write full end-to-end simulation replay test

**Goal:** A test that drives `MarketDayOrchestrator` through a complete simulation from
`PRE_OPEN_SCAN` to `END_OF_DAY` using historical data.

**Output file:**
`src/test/java/com/strategysquad/agentic/Phase5EndToEndTest.java`

**Scenario:** One market day where a position is entered, monitored, and profit-booked.

**Acceptance criteria:**
- Orchestrator reaches `END_OF_DAY` without exception
- Session state table records at least: `PRE_OPEN_SCAN`, `EVALUATE_ENTRY`, `POSITION_OPEN`,
  `MONITOR`, `BOOK_PROFIT`, `RESTART_SCAN`, `END_OF_DAY`
- Report is generated and readable
- Audit log has at least one record per state transition

---

## Phase 6 — Live-assist gate

**Objective:** Production-ready live-assist mode. The system recommends actions with full context.
The operator confirms. No broker order is placed automatically.

**Done when:** Live mode can recommend actions with full context. The system does not place orders
automatically. Any future broker execution phase has a separate approved design.

---

### Task 6.1 — Build `LiveAssistConfirmationGate`

**Goal:** Every command that would affect a live position requires explicit operator confirmation
before being applied.

**Output file:**
`src/main/java/com/strategysquad/agentic/liveassist/LiveAssistConfirmationGate.java`

**Behaviour:**
- In `SIMULATION` or `PAPER` mode: gate is bypassed (auto-approved)
- In `LIVE_ASSIST` mode:
  - Write a pending command to a new table `agentic_pending_commands`
  - Return a `PENDING` status to the orchestrator
  - The orchestrator transitions to a `WAIT_OPERATOR_CONFIRM` state (add this state to the machine)
  - Operator calls `POST /api/agentic/confirm-command` with the `command_id`
  - On confirmation, the command is applied and the orchestrator resumes

**Acceptance criteria:**
- In live-assist mode, no command is applied without a confirmation record in the database
- Pending command expires after a configurable timeout (default 60 seconds); on expiry, the
  command is cancelled and the orchestrator transitions to `MONITOR` or `HALTED`
- Confirmation API rejects commands with `command_id` that do not exist or have already expired

---

### Task 6.2 — Build operator session halt and reset controls

**Goal:** The operator can halt the session at any time via UI and API, and can reset `HALTED`
state with an explicit acknowledgement.

**New UI controls (in the agent-status panel):**
- "Halt Session" button — calls `POST /api/agentic/halt` with operator confirmation dialog
- "Reset Halt" button — visible only when state is `HALTED`; calls `POST /api/agentic/reset-halt`

**New API endpoints:**
- `POST /api/agentic/halt` — immediately transitions to `HALTED`, writes halt audit record
- `POST /api/agentic/cancel-pending` — cancels a pending command (body: `{"command_id": "..."}`)

**Acceptance criteria:**
- Halt is immediate and cannot be reversed by a non-operator call
- Halt writes an audit record with `command_type = HALT_SESSION` and `reason_code = OPERATOR_HALT`
- After halt, the orchestrator does not process further commands until explicitly reset

---

### Task 6.3 — Write production-readiness checklist

**Goal:** A documented checklist that must be reviewed before live-assist mode is used with real
money.

**Output file:** `docs/live-assist-production-checklist.md`

**Required checklist items:**
- Historical data coverage is >= 6 months for both NIFTY and BANKNIFTY
- All Phase 1–4 tests pass on `mvn test`
- Risk Guard thresholds have been reviewed and set for actual lot sizes and account size
- At least 5 simulation runs have been replayed and reports reviewed
- Kite API credentials are scoped to read/quote only (no order placement permissions)
- Confirmation gate is active and tested manually at least once
- Emergency halt button has been tested in live-assist mode
- Audit log retention policy is defined (minimum 90 days of decision records)
- No live data has been merged into historical golden source tables

**Acceptance criteria:**
- Every item has a "checked by" and "date" field
- Checklist is version-controlled and reviewed before each new trading day in live-assist mode

---

### Task 6.4 — Final audit trail verification test

**Goal:** A test that verifies the complete audit trail for a live-assist simulation run is
reproducible.

**Output file:**
`src/test/java/com/strategysquad/agentic/Phase6AuditTrailTest.java`

**Acceptance criteria:**
- Given a fixed set of historical inputs, the replay produces the exact same sequence of
  `DecisionCommand` records on every run (deterministic)
- Every command in the audit log has non-empty `reason_code` and `explanation`
- Every forced action has `overridden_by_risk_guard = true` and a non-empty
  `triggered_conditions` list

---

## Cross-cutting constraints (apply to every phase)

These rules apply unconditionally across all tasks.

1. **Raw tables are never touched.** No task may write to or mutate `options_historical`,
   `spot_historical`, `options_live`, or `spot_live`.

2. **Live data never enters historical tables.** Live enrichment, signal, and decision state live
   in their own tables.

3. **Point-in-time joins only.** Any enrichment or signal computation uses the latest value
   at-or-before the relevant timestamp. Never forward-looking.

4. **Every output is trader-readable.** No raw field name appears in a UI label or report. No
   metric is displayed without its unit (points, percent, lots).

5. **Simulation-first.** Every agent must work in simulation mode before live mode is activated.
   The `SimulationClock` must be injectable into any time-dependent service.

6. **Audit before action.** The audit record is written before the action is applied, not after.
   This ensures the intent is captured even if execution fails.

7. **One structure at a time.** No task should add multi-structure support until the single-
   structure loop is stable across 5+ simulation runs.

8. **No broker order placement.** No code in this codebase may call Kite or any broker order
   API. This constraint may only be removed by a separately approved design document.

---

## File tree summary

```
src/main/java/com/strategysquad/agentic/
├── scanner/
│   ├── CandidateOpportunity.java        (Task 1.1)
│   ├── ScannerQuery.java                (Task 1.2)
│   ├── CandidateScoringEngine.java      (Task 1.3)
│   └── MorningScannerService.java       (Task 1.4)
├── signal/
│   ├── SignalSnapshot.java              (Task 1.6)
│   └── SignalSnapshotService.java       (Task 1.7)
├── decision/
│   ├── DecisionCommand.java             (Task 2.1)
│   ├── DecisionContext.java             (Task 2.2)
│   ├── DecisionPolicy.java              (Task 2.4)
│   ├── DecisionAgent.java               (Task 2.5)
│   └── DecisionAuditWriter.java         (Task 2.7)
├── risk/
│   ├── RiskGuardSnapshot.java           (Task 2.3)
│   ├── RiskGuardDecision.java           (Task 2.3)
│   ├── RiskGuardInput.java              (Task 4.2)
│   └── RiskGuardService.java            (Task 2.6 stub → Task 4.1 full)
├── builder/
│   ├── PositionPlan.java                (Task 3.1)
│   ├── PositionPlanLeg.java             (Task 3.1)
│   └── PositionBuilderAgent.java        (Task 3.2)
├── booking/
│   └── ProfitBookingAgent.java          (Task 3.4)
├── adjustment/
│   └── AdjustmentAgent.java             (Task 4.3 / 4.4)
├── orchestrator/
│   └── MarketDayOrchestrator.java       (Task 5.1)
└── liveassist/
    └── LiveAssistConfirmationGate.java  (Task 6.1)

db/migration/
├── V005__agentic_audit.sql              (Task 2.7)
└── V006__agentic_session_state.sql      (Task 5.1)

docs/
└── live-assist-production-checklist.md (Task 6.3)
```

---

## Sequencing summary

```
Phase 0 (docs)
    └── Phase 1 (scanner + signals)
            └── Phase 2 (decision agent, paper mode)
                    └── Phase 3 (position builder + profit booking)
                            └── Phase 4 (adjustment expansion + full risk guard)
                                    └── Phase 5 (orchestrator + UI + reports)
                                                └── Phase 6 (live-assist gate)
```

Each phase is a hard prerequisite for the next. Do not begin Phase N+1 until all acceptance
criteria in Phase N are green.
