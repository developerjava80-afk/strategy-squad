# Strategy Squad — Development Plan to Reach the Agentic Theta-Decay Loop

This plan converts the roadmap in `strategy-squad-roadmap.txt` and the blueprint in `agentic-live-trading-decision-loop.md` into an executable, phase-by-phase build plan, anchored to the current code under `src/main/java/com/strategysquad/`.

It is the operating doc the team should follow to go from "research console + live overlay + lot-based ADD/REDUCE engine" to "agentic theta-decay live-assist orchestrator with full audit trail."

---

## 1. Final Objective (One Paragraph)

Strategy Squad becomes an agentic theta-decay trading intelligence system for NIFTY and BANKNIFTY weekly options that, on every market day, scans the option chain for historically rich premium, builds near-delta-neutral short-option structures, tracks real-time empirical theta and delta, dynamically rebalances positions, books profit when theta decay is captured, restarts scanning, and lets a global Risk Guard override anything — all with full reproducibility, simulation/paper parity, and a strict no-broker-execution boundary until a separate phase is approved.

The deliverable is the seven-agent loop (Scanner → Signal → Decision → Builder → Adjustment → Profit Booking → Risk Guard) wired through a market-day Orchestrator that drives the documented state machine, persists an audit trail for every decision, and exposes compact agent state in the existing UI plus richer detail in markdown reports.

---

## 2. Where We Are Today (Current State Read From the Code)

The platform already owns the foundations the agentic loop needs. Concretely:

Historical engine. Canonical raw, enriched, 15-minute and contextual buckets, plus PCR. `CanonicalScenarioResolver`, `FairValueCohortService`, `TimeframeAnalysisService`, and `StrategyAnalysisService` cover historical fair-value comparison, with the `RawStrategyMetrics` → `EconomicMetrics` transform boundary already enforced. Three migrations (`V001`–`V003`) are applied.

Live overlay. `KiteLiveConsoleMain`, `KiteInstrumentsDumpJob`, `KiteTickerSession`, `KiteSpotQuoteService`, `LiveSchemaBootstrapper`, `LiveStructureSnapshotService`, `LiveSessionState`, and `Live15mAggregator` write into isolated live-session tables (`spot_live`, `options_live`, `options_live_enriched`, `options_live_15m`, `live_structure_snapshot`) under `V004`. `LiveMarketService` resolves UI legs into live contracts, computes net premium, and reuses `StrategyAnalysisService` for live-vs-history.

Empirical signals (partial). `EmpiricalDeltaResponseService` already computes 2m / 5m / start-of-day empirical delta. Delta-adjusted theta residual logic exists, but only inside `DeltaAdjustmentService` — it is not yet a reusable, persistable signal contract.

Position sessions and audit. `ResearchPositionSessionService`, `PositionSessionActionService`, `PositionSessionSnapshot`, and `PositionSessionActionRequest` persist legs, blended entry, booked PnL, and audit entries for `ADD`, `REDUCE`, `MANUAL_EXIT`, `EXIT_ALL`.

Adjustment engine (partial). `DeltaAdjustmentService` supports `ADD` and `REDUCE` for one lot, with the `HARD` / `NORMAL` / `DELAYED` / `SKIPPED` trigger hierarchy, cooldown, churn guard, volume confirmation, scoring (delta-improvement, theta proxy, liquidity, churn penalty, risk penalty), max 20 lots, and post-action net-delta-worse rejection.

Simulation. `HistoricalReplayService` + `SimulationClock` replay `spot_live` / `options_live` rows in exchange-time order from 09:15 IST through the same live services.

Reports. `StrategyRunReportService` writes `docs/reports/strategy-run-YYYYMMDD-HHMMSS-{live|simulation}.md` with run metadata, initial structure, adjustment timeline, signal snapshot per adjustment, PnL summary, final structure, decision summary, observations.

Known correctness debt (from `architecture-audit-part1.md`) that is *not* part of the agentic loop but still bounds trust in everything downstream: P0-1 unnormalized recommendation score, P0-2 gross-sum premium for mixed-side strategies, P0-3 best/worst violating seller payoff invariants, plus P1 unit-label gaps. These should be treated as a parallel pre-flight track (see Section 5) because the agentic Decision Agent will consume the same `EconomicMetrics`.

What is missing for the roadmap: the Morning Scanner Agent, a first-class persistable Signal Engine output, a unified `DecisionCommand` contract, a Position Builder, expansion of Adjustment to `SHIFT_STRIKE` / `EXIT_LEG` / `EXIT_ALL` (auto), a standalone Profit Booking Agent, a global Risk Guard layer, and the market-day Orchestrator with state-machine persistence. The `src/main/java/com/strategysquad/agentic/` package does not yet exist.

---

## 3. Guiding Principles (Apply at Every Phase)

Reproducibility before automation. Every agent decision must be reconstructable from stored inputs (timestamp, mode, state, command, candidate ids, position id, spot, net delta before/after, theta state, theta progress, live PnL, booked PnL, liquidity, risk decision, reason code, human explanation).

Simulation- and paper-mode first. No agent mutates a live position before its decisions have been validated in `HistoricalReplayService`. The Decision Agent runs paper-only until replay confirms quality.

Canonical truth boundary stays intact. Live data never merges into historical tables. `RawStrategyMetrics` → `EconomicMetrics` remains the only sign-aware transform path. No UI-side pricing logic.

Live-assist, not auto-execute. Phases 1–5 produce decisions and recommendations. Phase 6 introduces a manual confirmation gate. Broker order placement is explicitly out of scope until a separate approved design exists.

Domain contract beats elegance. If a metric is mathematically correct but trader-misleading, it is incorrect. Units are explicit at every boundary (points / rupees / per-lot / per-structure / normalized %). Risk Guard can override every command.

Tests precede wiring. Premium-richness, liquidity, empirical delta, theta residual, decision policy, builder geometry, profit-booking trigger, and risk-guard hard stops each get unit tests before any orchestrator integration.

---

## 4. Target Package Layout

All new code lives under a new `agentic/` root, parallel to `research/`, with clean dependencies pointing into existing services rather than fanning out from them.

```
src/main/java/com/strategysquad/agentic/
├── scanner/         CandidateOpportunity, CandidateScoringPolicy, MorningScannerService
├── signal/          SignalSnapshot, SignalSnapshotService, ThetaResidualCalculator
├── decision/        DecisionCommand, DecisionPolicy, DecisionAgent, AuditRecord
├── builder/         PositionPlan, PositionBuilderAgent, StructureFamily
├── adjustment/      AdjustmentCommand, AdjustmentAgent (extends current engine)
├── booking/         ProfitBookingAgent, ThetaCaptureTrigger
├── risk/            RiskGuardSnapshot, RiskGuardAgent, GuardRule (composable)
├── orchestrator/    MarketDayState, MarketDayStateMachine, OrchestratorService
└── audit/           AgenticAuditWriter, AgenticAuditEntry
```

Mirrored test tree under `src/test/java/com/strategysquad/agentic/`.

New schema migration (single file, applied once): `db/migration/V005__agentic_session_tables.sql`, holding `scanner_candidates`, `signal_snapshots`, `agentic_decisions`, `agentic_audit`, and `market_day_state` (one row per session, updated as state transitions happen). All append-only except `market_day_state`.

---

## 5. Pre-Flight (Parallel Track, Don't Block Phase 1)

Before the Decision Agent ships, the `EconomicMetrics` it consumes must not lie about premium economics or recommendations. These are tracked in `architecture-audit-part1.md` as P0/P1 and should be closed in parallel by another contributor while Phase 1 starts:

- P0-1 Recommendation score: normalize each input term to a common 0–100 scale (or switch to rank-based scoring) before applying weights, so the score reflects the stated weights instead of being dominated by win-rate scale.
- P0-2 Gross-sum premium: introduce signed `netPremium = Σ(signedLegPremium)` for mixed-side strategies (LONG = entry, SHORT = −entry). Use net for display, percentile, and "vs history."
- P0-3 Best/worst label: rename to "Raw historical sample extreme" and stop presenting it as current-trade max profit/loss.
- P1-1 Unit labels: attach unit metadata at the JSON/UI boundary for every metric.

These are pre-conditions for the Decision Agent's `premium_richness_*` inputs to be trustworthy.

---

## 6. Phase Plan

The blueprint's six phases are kept and refined here with concrete file targets, acceptance criteria, and dependencies. Each phase ends with a hard gate; the next phase does not start until the gate passes.

### Phase 0 — Documentation alignment (1–2 days)

Goal. Make sure every contributor sees the same target.

Work. Add this development plan to `docs/`. Cross-link from `README.md`, `developer-notes.md`, `live-kite-overlay.md`, and `scenario-research-workstation.md`. Verify the existing blueprint's "Where We Stand" table still matches the code (it does as of this snapshot; re-check at start of each phase).

Gate. A new contributor can read `docs/agentic-live-trading-decision-loop.md` plus this plan and explain the next concrete deliverable without code spelunking.

### Phase 1 — Scanner + Signal snapshots (2–3 weeks)

Goal. Produce ranked short-option candidates and reusable per-contract theta/delta snapshots, both read-only.

Deliverables.

- `agentic/scanner/CandidateOpportunity.java` with the 17 fields listed in the blueprint (`candidate_id` through `disqualifier_reason`, including `total_score`).
- `agentic/scanner/CandidateScoringPolicy.java` — pure function from inputs to four sub-scores (`premium_richness_score`, `theta_opportunity_score`, `liquidity_score`, `delta_risk_score`) plus weighted total. Weights normalized to a common scale to avoid the P0-1 scale-bias trap.
- `agentic/scanner/MorningScannerService.java` — reads active weekly `instrument_master` rows for NIFTY and BANKNIFTY, joins latest `spot_live` and `options_live`/`options_live_enriched`, compares against `options_context_buckets` for richness, and returns a ranked list. Honors moneyness and DTE filters; emits a `disqualifier_reason` for skipped contracts.
- `agentic/signal/ThetaResidualCalculator.java` — extracts the delta-adjusted-theta math currently embedded in `DeltaAdjustmentService`. `expected_delta_move = empirical_delta * underlying_change`; `delta_adjusted_theta = actual_option_price_change − expected_delta_move`. Sign convention: positive = theta benefit for short legs.
- `agentic/signal/SignalSnapshot.java` (record) and `SignalSnapshotService.java` — produces in-memory snapshots first, persists into `signal_snapshots` only after the in-memory contract is stable. `EmpiricalDeltaResponseService` becomes a dependency, not a duplicate.
- Refactor `DeltaAdjustmentService` to consume `ThetaResidualCalculator` and (optionally) `SignalSnapshot` so both paths share one math source. No behavior change in this phase.
- Read-only API: `GET /api/agentic/scanner/candidates?underlying=NIFTY|BANKNIFTY` returning the ranked candidate list. No position mutation.
- Tests: premium-richness math, liquidity score, theta residual on a synthetic time-series, scanner ordering with fixtures, disqualifier-reason coverage, parity test that `DeltaAdjustmentService` post-refactor matches pre-refactor outputs.

Gate.

- Simulation and live both produce a non-empty ranked list when given a normal market day.
- Every accepted candidate has a numeric explanation for each sub-score.
- Every rejected candidate has a non-empty `disqualifier_reason`.
- `DeltaAdjustmentService` parity tests pass.
- No position state mutates anywhere in this phase.

### Phase 2 — Decision Agent in paper mode + Risk Guard skeleton (2–3 weeks)

Goal. Convert ranked candidates + signals + position + PnL + risk into one explicit `DecisionCommand`, audited but never executing.

Deliverables.

- `agentic/decision/DecisionCommand.java` (sealed type / enum-tagged record) with the nine command types from the blueprint: `ENTER`, `ADD`, `REDUCE`, `SHIFT_STRIKE`, `HOLD`, `BOOK_PROFIT`, `EXIT_LEG`, `EXIT_ALL`, `SKIP`.
- `agentic/decision/DecisionPolicy.java` — deterministic rules:
  - enter only when `premium_richness_pct ≥ threshold` and structure is low-delta-eligible
  - hold when no candidate beats the current structure on theta-per-delta
  - skip when liquidity or stale-data thresholds fail
  - prefer book over hold when `theta_progress_ratio ≥ threshold` and live PnL > 0
  - prefer reduce/exit when premium is expanding against a short leg
- `agentic/decision/DecisionAgent.java` — orchestrates inputs, applies policy, returns a single command + audit record.
- `agentic/risk/RiskGuardSnapshot.java` and `RiskGuardAgent.java` (skeleton) — implements `ALLOW`, `WARN`, `BLOCK_NEW_ENTRY`. Hard stops in this phase: stale data, missing required signals, total lots cap. Other guards land in Phase 4.
- `agentic/audit/AgenticAuditWriter.java` — persists to `agentic_audit` with the full minimum field set (timestamp, mode, state, command, selected ids, session id, spot, net delta before/after, theta state, theta progress, live/booked PnL, liquidity, risk decision, reason code, human-readable explanation).
- API: `POST /api/agentic/decision/preview` returning the next command (paper mode only — no mutation).
- Replay tests: drive `HistoricalReplayService` across at least 5 distinct trading days from the live-session table fixtures and assert that `DecisionCommand` outputs are deterministic and auditable.

Gate.

- For each replay day, the system explains why it would enter, hold, skip, book, reduce, or exit.
- No path mutates a position.
- Every emitted command produces exactly one `agentic_audit` row with all required fields populated.
- Risk Guard's three skeleton states are exercised by tests.

### Phase 3 — Position Builder + Profit Booking (2–3 weeks)

Goal. Turn `ENTER` and `BOOK_PROFIT` commands into real (simulated) structure mutations with correct booked-PnL semantics.

Deliverables.

- `agentic/builder/PositionPlan.java` — proposed legs, lot allocations, expected net delta, expected total credit, structure family.
- `agentic/builder/PositionBuilderAgent.java` — first family is short straddle/strangle. Rules: NIFTY lot 65, BANKNIFTY lot 30, total lots capped (start at the existing 20), net delta near zero, premium richness confirmed, strikes selected for strongest expected theta decay, no risk-only-for-premium increases. Defined-risk variants (iron condor / butterfly) come after Phase 5.
- `agentic/booking/ProfitBookingAgent.java` — trigger: `theta_progress_ratio ≥ threshold AND live_pnl > 0 AND risk_status != CRITICAL`. Actions: partial reduce (default ½ lots) or full exit, then signal `RESTART_SCAN`. Audit reason code: `theta_capture_profit_book`.
- Wiring through existing `PositionSessionActionService` so booked PnL stays correct on partial and full exits, blended entry remains correct on `ADD`.
- Replay test: open a simulated short straddle, monitor it, hit a theta-capture event, book profit, restart scanning, complete a second entry, EOD report — all within one synthetic day.
- Report fields added: scanner ranking summary, decision command stream, builder rationale, booking trigger evidence.

Gate.

- A full simulated day can run pre-open-scan → enter → monitor → book → re-scan → end-of-day with one auditable trail.
- Booked PnL after partial then full exit equals booked PnL of an equivalent single full exit, within rounding.
- Reports include builder and booking sections without breaking the live path.

### Phase 4 — Adjustment expansion + global Risk Guard (2–3 weeks)

Goal. Add `SHIFT_STRIKE`, `EXIT_LEG`, `EXIT_ALL` to the live-mutation surface, and put Risk Guard in front of every command.

Deliverables.

- Extend `DeltaAdjustmentService` (or add `agentic/adjustment/AdjustmentAgent.java` as a thin wrapper) for:
  - `SHIFT_STRIKE`: close one leg, open a better-balanced strike, in a single audited atomic command.
  - `EXIT_LEG`: close one leg.
  - `EXIT_ALL`: close the structure.
- Constraint: never adjust both sides in one command until multi-action audit support is explicit.
- `agentic/risk/GuardRule.java` — composable rules. Add: net delta beyond critical threshold, live PnL deterioration, one-leg premium expansion velocity, liquidity disappearance, drawdown breach, churn loop detected, total lots exceed cap, missing signal data.
- `RiskGuardAgent` decisions expand to full set: `ALLOW`, `WARN`, `BLOCK_NEW_ENTRY`, `FORCE_REDUCE`, `FORCE_EXIT`, `HALT_SESSION`.
- Wiring rule: every `DecisionCommand` (and every command produced by Adjustment, Booking, or Builder) goes through `RiskGuardAgent.evaluate()` before it is applied. Forced actions name the guard condition that triggered them.
- Replay tests covering each guard condition.

Gate.

- No accepted action ever worsens absolute net delta without an explicit hard-risk justification.
- Every forced action's audit row identifies the triggering guard rule.
- Replay across at least 10 days produces zero churn-loop violations.

### Phase 5 — Orchestrator, API, UI, Reports (3–4 weeks)

Goal. Close the loop end-to-end and surface state without turning the UI into an order-entry screen.

Deliverables.

- `agentic/orchestrator/MarketDayState.java` — enum matching the blueprint's 11 states (`PRE_OPEN_SCAN` … `HALTED`).
- `agentic/orchestrator/MarketDayStateMachine.java` — pure state transition table.
- `agentic/orchestrator/OrchestratorService.java` — drives the loop on a tick (live) or simulated tick (replay), using Scanner → Signal → Decision → Builder/Adjustment/Booking → Risk Guard, persisting state in `market_day_state`.
- APIs:
  - `GET /api/agentic/state` — current state, last decision, last risk-guard verdict, current position session id.
  - `GET /api/agentic/decisions?since=...` — recent audit stream for the day.
  - `POST /api/agentic/halt` — operator override to `HALTED`.
  - `POST /api/agentic/reset` — operator clear after `HALTED`.
- UI: small status strip in `ui/scenario-research/` (or a new compact panel) showing state, last decision, risk guard verdict, last audit reason. No order-entry-style controls. The deeper detail stays in reports.
- Reports: extend `StrategyRunReportService` sections — Scanner Ranking, Signal Snapshots Stream, Decision Commands Stream, Risk Guard Overrides, Profit Booking Events.

Gate.

- A full simulation run can be replayed end-to-end from pre-open scan to end-of-day report with no manual steps.
- The UI shows agent state changes live during a replay without becoming a trading screen.
- Halt + reset round-trip works in both live and simulation modes.

### Phase 6 — Live-assist gate (2 weeks)

Goal. Allow a real operator to act on the agentic loop's recommendations during live hours, with a manual confirmation gate. No broker orders.

Deliverables.

- A confirmation modal/CLI step before any "live-affecting action" (i.e. anything that changes the persisted live position session in live mode). Simulation skips the gate.
- Operator-visible reason and risk summary on every confirmation prompt.
- Explicit session halt/reset controls hardened with a 2-step confirmation.
- Production-readiness checklist: token expiry handling, stale-tick fallbacks, EOD freeze behavior, report write-failure isolation, audit-write atomicity, no-data-leak between live and historical tables.

Gate.

- Live mode can recommend each action with full context.
- The system still does not place any broker order.
- Future broker execution (if ever pursued) is documented as a separate approved design with its own threat model.

---

## 7. Cross-Phase Dependencies and Sequencing

Phase 1 unblocks Phase 2 (Decision Agent needs Candidates and Signals).
Phase 2 unblocks Phase 3 (Builder/Booking consume `DecisionCommand`).
Phase 3 unblocks Phase 4 only insofar as `EXIT_ALL`/`SHIFT_STRIKE` need a stable position-mutation path — but Phase 4's Risk Guard expansion can start in parallel.
Phase 5 requires all of 1–4 stable.
Phase 6 requires Phase 5 stable.

The parallel pre-flight in Section 5 (P0-1, P0-2, P0-3, P1-1) should ideally close before Phase 2 ships, because `DecisionPolicy` reads `EconomicMetrics`.

---

## 8. Acceptance Tests Per Phase (Replayable)

Every phase ends with a deterministic replay-based test suite, not just unit tests:

- Phase 1: scan a fixed replay day, assert top-N candidates and disqualifier reasons match a golden file.
- Phase 2: replay 5 days; assert command stream and audit fields match golden.
- Phase 3: replay 1 day with a forced theta-capture; assert booked PnL trail matches golden.
- Phase 4: replay 10 days; assert no state transition worsens net delta without a guard reason; force each guard and assert the corresponding action.
- Phase 5: replay 1 full day end-to-end; assert state-machine transition log and report sections match golden.
- Phase 6: live-mode dry-run with paper confirmation gate; assert no DB row is written before confirm.

Golden files live under `src/test/resources/agentic/golden/`.

---

## 9. Risks and Mitigations

Empirical theta noise. The 2-minute residual is sensitive to price jumps. Mitigation: keep both 2m and 5m residuals in `SignalSnapshot`; require concordance for `BOOK_PROFIT`; let `RiskGuard` warn on residual disagreement.

Replay/live divergence. The simulation is meant to use the same services, but timing semantics differ. Mitigation: keep `SimulationClock` as the single time source consumed by every agent, and unit-test that clock injection.

Audit trail gaps. The minimum field list in the blueprint is non-trivial. Mitigation: enforce via a single `AgenticAuditWriter.write(...)` entry point with required-field validation; reject commands that lack audit context.

P0 correctness debt leaks into Decision policy. Mitigation: make Section 5's pre-flight a hard prerequisite for Phase 2; if it slips, gate `DecisionPolicy` on a `metricsTrustworthy()` flag and emit `SKIP` until it's true.

Scope creep into auto-execution. Mitigation: keep Section 3's "live-assist, not auto-execute" stance reflected in PR templates; any PR that opens broker order paths is rejected outside the explicit Phase 7 (not yet defined) design.

UI clutter regression. Mitigation: every UI addition stays compact and routes deep detail into the markdown report. The screen-intent rule from `ui-guidance-algo-testing-console.md` applies.

---

## 10. Definition of Done (For the Whole Roadmap)

The agentic theta-decay loop is "done" when, on a normal market day:

1. The Morning Scanner ranks NIFTY and BANKNIFTY weekly candidates with reasons.
2. The Signal Engine produces per-contract theta/delta snapshots that the Decision Agent and `DeltaAdjustmentService` both consume from one math source.
3. The Decision Agent emits one of nine commands per tick, audited.
4. The Position Builder turns `ENTER` into a near-delta-neutral short structure within lot caps.
5. The Adjustment Agent supports `ADD`, `REDUCE`, `SHIFT_STRIKE`, `EXIT_LEG`, `EXIT_ALL`.
6. The Profit Booking Agent books fast theta capture and restarts the scan.
7. The Risk Guard has reviewed every command and can force-reduce, force-exit, or halt the session.
8. The Orchestrator drives the documented state machine end-to-end in both live-assist and simulation modes.
9. A markdown report is produced for every run with scanner, signal, decision, adjustment, booking, and guard sections.
10. No broker order is ever placed automatically; every live-affecting action passes through the manual confirmation gate.
11. The full day is reproducible from stored audit + live-session tables.

Once all eleven hold across 20 distinct simulated trading days and 5 supervised live-assist days, the roadmap is delivered.

---

## 11. Immediate Next Step

Start Phase 1 work in this order, exactly as `agentic-live-trading-decision-loop.md` § Immediate Next Step prescribes:

1. Define `CandidateOpportunity`.
2. Build the scanner query over active weekly NIFTY and BANKNIFTY contracts.
3. Compare live price to historical context buckets.
4. Score premium richness, theta opportunity, liquidity, delta risk.
5. Extract signal snapshot logic so theta and delta are reusable.
6. Add tests before wiring any position mutation.

Do not touch position state in Phase 1.

---

*This plan must be re-read at the start of every phase. Update the "Where We Stand" snapshot in Section 2 whenever a phase gate passes.*
