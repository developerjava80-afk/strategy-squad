# Agentic Live Trading Decision Loop

This document converts the Strategy Squad roadmap into the working blueprint for the next product phase.

The target is an agentic theta-decay trading intelligence loop for NIFTY and BANKNIFTY weekly options. The first implementation target is deterministic simulation and live-assist decisions with full auditability. Broker order execution is out of scope until explicitly approved as a separate phase.

## Product stance

Strategy Squad remains grounded in canonical historical truth:

- historical pricing context comes from the golden source tables
- live data stays isolated from historical data
- every decision must be reproducible from stored inputs
- every decision must produce an audit trail
- every output must be trader-readable and unit-safe

The agentic layer should decide what should be done. It should not silently place broker orders in the initial roadmap phases.

## Where We Stand

Current platform capabilities:

| Area | Current state |
| --- | --- |
| Golden source data | Historical NIFTY and BANKNIFTY options, spot, enrichment, 15-minute buckets, contextual buckets, and PCR derived signals exist. |
| Historical fair-value context | Current structures can be compared against moneyness and time-to-expiry cohorts from canonical historical data. |
| Live market overlay | Kite live spot and option polling writes isolated live tables and hydrates the research console. |
| Empirical delta | `EmpiricalDeltaResponseService` computes live option response versus underlying movement over 2-minute, 5-minute, and start-of-day windows. |
| Theta detection | Delta-adjusted theta residual logic exists inside `DeltaAdjustmentService`, including `PROFIT_BOOK`, `HOLD`, and `DEFENSIVE_EXIT` states. |
| Position sessions | Position sessions persist legs, quantities, booked PnL, manual exits, and adjustment audit entries. |
| Adjustment engine | Current live path supports one-lot `ADD` and `REDUCE` decisions with cooldown, churn guard, volume confirmation, delta scoring, and risk hierarchy. |
| Simulation replay | Historical live ticks can be replayed through the same live services using `HistoricalReplayService`. |
| Reports | Completed live and simulation runs can write markdown execution reports under `docs/reports/`. |

Current gaps against the roadmap:

| Roadmap capability | Gap |
| --- | --- |
| Morning Scanner Agent | No service scans all active weekly contracts and ranks short-option opportunities. |
| First-class signal engine | Empirical delta exists, but theta and delta signals are not yet persisted as reusable per-contract/per-leg snapshots. |
| Decision Agent | No unified decision contract for `ENTER`, `ADD`, `REDUCE`, `SHIFT_STRIKE`, `HOLD`, `BOOK_PROFIT`, or `EXIT`. |
| Position Builder Agent | No automated builder creates a max-theta, low-delta short-option structure from scanner candidates. |
| Adjustment Agent | Existing logic handles `ADD` and `REDUCE`; it does not yet shift strikes, exit one leg, or exit the full structure automatically. |
| Profit Booking Agent | Theta capture is detected but not yet translated into a dedicated partial/full booking flow and scanner restart. |
| Risk Guard Agent | Risk checks exist in pieces; there is no global override layer that gates every decision. |
| Full orchestrator | No market-day state machine ties scanner, decision, builder, adjustment, profit booking, risk guard, simulation, UI, and reports together. |

## Target Market-Day Loop

The final live-assist loop should run like this:

1. Pre-open scanner loads the active weekly NIFTY and BANKNIFTY option universe.
2. Scanner ranks candidates by premium richness, theta opportunity, liquidity, moneyness, time-to-expiry, and delta risk.
3. Market opens and the signal engine continuously updates empirical delta and delta-adjusted theta.
4. Decision Agent selects `ENTER`, `HOLD`, or `SKIP`.
5. Position Builder creates a near-delta-neutral short-option structure under max lot and liquidity constraints.
6. Risk Guard validates the proposed structure before it can become active.
7. Live monitoring updates theta, delta, PnL, liquidity, stale-data status, and premium expansion.
8. Adjustment Agent can `ADD`, `REDUCE`, `SHIFT_STRIKE`, `EXIT_LEG`, or `EXIT_ALL`.
9. Profit Booking Agent books partial or full profit when theta capture is strong and risk is not critical.
10. After booking, scanner restarts and searches for the next opportunity.
11. Risk Guard can override normal logic at any point.
12. End-of-day flow closes or marks the session, freezes audit state, and writes a run report.

## Agent Contracts

### 1. Morning Scanner Agent

Purpose:

- scan NIFTY and BANKNIFTY weekly option contracts before and during market hours
- identify short-option candidates where live premium is rich versus historical context

Inputs:

- active `instrument_master` rows for NIFTY and BANKNIFTY
- latest live spot
- latest live option price, bid, ask, volume, OI where available
- canonical historical context from `options_context_buckets`
- moneyness and time-to-expiry
- empirical delta when available

Output:

- ranked `CandidateOpportunity` rows

Suggested fields:

- `candidate_id`
- `underlying`
- `instrument_id`
- `trading_symbol`
- `option_type`
- `strike`
- `expiry_date`
- `expiry_type`
- `spot`
- `last_price`
- `bid_price`
- `ask_price`
- `moneyness_points`
- `moneyness_bucket`
- `time_bucket_15m`
- `historical_avg_price`
- `premium_richness_points`
- `premium_richness_pct`
- `liquidity_score`
- `theta_opportunity_score`
- `delta_risk_score`
- `total_score`
- `disqualifier_reason`

First implementation:

- read-only service and API
- no position mutation
- simulation-compatible

### 2. Theta / Delta Signal Engine

Purpose:

- make empirical delta and delta-adjusted theta reusable outside `DeltaAdjustmentService`

Empirical delta:

```text
empirical_delta = option_price_change / underlying_price_change
```

Delta-adjusted theta:

```text
expected_delta_move = empirical_delta * underlying_change
delta_adjusted_theta = actual_option_price_change - expected_delta_move
```

For short legs, theta benefit is positive when the option price falls more than expected after adjusting for underlying movement.

Suggested output:

- `SignalSnapshot`

Suggested fields:

- `signal_ts`
- `instrument_id`
- `underlying`
- `option_type`
- `strike`
- `empirical_delta_2m`
- `empirical_delta_5m`
- `empirical_delta_sod`
- `underlying_move_2m`
- `option_move_2m`
- `delta_adjusted_theta_2m`
- `expected_decay_since_entry`
- `theta_progress_ratio`
- `theta_state`
- `volume_state`
- `stale`
- `reason`

First implementation:

- extract current theta residual logic into a reusable service
- keep `DeltaAdjustmentService` as a consumer
- persist signal snapshots only after the in-memory contract is stable

### 3. Decision Agent

Purpose:

- be the main policy engine
- convert scanner, signal, position, PnL, and risk context into one explicit command

Inputs:

- ranked scanner candidates
- live signal snapshots
- current position session
- booked PnL
- live PnL
- risk guard snapshot
- max lot and churn/cooldown state
- historical fair-value context

Output:

- `DecisionCommand`

Allowed command types:

- `ENTER`
- `ADD`
- `REDUCE`
- `SHIFT_STRIKE`
- `HOLD`
- `BOOK_PROFIT`
- `EXIT_LEG`
- `EXIT_ALL`
- `SKIP`

Core rules:

- enter only when premium is rich versus history
- prefer low-delta, high-theta opportunities
- avoid illiquid or unstable contracts
- do not increase risk only to collect premium
- if theta capture is fast and PnL is positive, prefer booking
- if theta fails and premium expands, prefer defensive reduction or exit
- Risk Guard can override every normal command

First implementation:

- produce decisions in paper mode and write audit records
- do not mutate position state until decision quality is validated in replay

### 4. Position Builder Agent

Purpose:

- turn an `ENTER` command into a structure proposal

Inputs:

- scanner candidates
- live spot
- signal snapshots
- lot sizing rules
- risk limits

Output:

- `PositionPlan`

Rules:

- NIFTY lot size = 65
- BANKNIFTY lot size = 30
- total lots capped
- net delta should be near zero
- short options are preferred only when premium richness is confirmed
- choose strikes with the strongest expected theta decay
- do not add risk just to collect premium

First implementation:

- produce one conservative structure family at a time
- start with short straddle or short strangle candidates
- add defined-risk variants after the decision loop is stable

### 5. Adjustment Agent

Purpose:

- manage an active structure after entry

Allowed actions:

- `ADD`
- `REDUCE`
- `SHIFT_STRIKE`
- `EXIT_LEG`
- `EXIT_ALL`
- `HOLD`

Decision basis:

- net delta
- empirical theta benefit
- live PnL
- booked PnL
- underlying momentum
- liquidity
- churn guard
- cooldown

Guiding rule:

```text
Delta controls risk. Theta controls opportunity.
```

First implementation:

- extend current `ADD` and `REDUCE` engine
- add `SHIFT_STRIKE` only after replay confirms candidate quality
- never adjust both sides in one command until multi-action audit support is explicit

### 6. Profit Booking Agent

Purpose:

- book fast theta capture instead of waiting after the main premium decay has happened

Trigger pattern:

```text
theta_progress_ratio >= threshold
AND live PnL positive
AND risk not critical
```

Allowed actions:

- partial reduce
- full exit
- restart scanner

First implementation:

- use existing booked PnL and position-session action services
- write a clear audit reason such as `theta_capture_profit_book`
- only run in simulation/paper mode until replay evidence is good

### 7. Risk Guard Agent

Purpose:

- provide the always-active override layer

Hard stops:

- net delta beyond critical threshold
- live PnL deterioration
- one leg premium expanding too fast
- liquidity disappears
- data stale
- max loss or drawdown breach
- adjustment loop churn detected
- total lots exceed cap
- missing required signal data

Output:

- `RiskGuardSnapshot`

Allowed guard decisions:

- `ALLOW`
- `WARN`
- `BLOCK_NEW_ENTRY`
- `FORCE_REDUCE`
- `FORCE_EXIT`
- `HALT_SESSION`

First implementation:

- centralize currently scattered risk checks
- require Risk Guard evaluation before every `DecisionCommand` is applied

## Proposed State Machine

| State | Meaning | Next states |
| --- | --- | --- |
| `PRE_OPEN_SCAN` | Build candidate list before market open. | `WAIT_MARKET_OPEN`, `HALTED` |
| `WAIT_MARKET_OPEN` | Hold until live data is fresh and market is active. | `EVALUATE_ENTRY`, `HALTED` |
| `EVALUATE_ENTRY` | Decision Agent reviews candidates and risk. | `POSITION_OPEN`, `RESTART_SCAN`, `HALTED` |
| `POSITION_OPEN` | Structure has been accepted into session state. | `MONITOR`, `EXITED`, `HALTED` |
| `MONITOR` | Continuous signal, PnL, liquidity, and risk evaluation. | `ADJUST`, `BOOK_PROFIT`, `EXITED`, `RESTART_SCAN`, `HALTED` |
| `ADJUST` | Apply one audited adjustment command. | `MONITOR`, `EXITED`, `HALTED` |
| `BOOK_PROFIT` | Reduce or exit after theta capture. | `RESTART_SCAN`, `MONITOR`, `EXITED` |
| `RESTART_SCAN` | Look for the next opportunity after booking or skip. | `EVALUATE_ENTRY`, `END_OF_DAY`, `HALTED` |
| `EXITED` | No active structure remains. | `RESTART_SCAN`, `END_OF_DAY` |
| `END_OF_DAY` | Freeze session and write report. | Terminal |
| `HALTED` | Risk or system issue stopped the loop. | Terminal until manual reset |

## Implementation Phases

### Phase 0: Documentation and alignment

Deliverables:

- this blueprint
- README links and current-state notes
- developer notes updated for agentic-loop work
- existing workstation and live-overlay docs aligned with the roadmap

Done when:

- future contributors know where the roadmap lives
- current-vs-target gaps are explicit
- no document implies that the current code already has full agentic decision orchestration

### Phase 1: Scanner and signal snapshots

Deliverables:

- scanner service for active weekly contracts
- candidate scoring model
- reusable signal snapshot service
- tests for premium richness, liquidity scoring, empirical delta, and theta residual math
- optional read-only API endpoint for scanner candidates

Suggested files:

- `src/main/java/com/strategysquad/agentic/scanner/*`
- `src/main/java/com/strategysquad/agentic/signal/*`
- `src/test/java/com/strategysquad/agentic/scanner/*`
- `src/test/java/com/strategysquad/agentic/signal/*`

Done when:

- simulation and live modes can produce ranked candidates without changing positions
- every candidate has an explanation and disqualifier reason when rejected

### Phase 2: Decision Agent in paper mode

Deliverables:

- `DecisionCommand` contract
- deterministic decision policy
- risk guard snapshot contract
- paper-mode audit log
- replay tests across multiple market days

Suggested files:

- `src/main/java/com/strategysquad/agentic/decision/*`
- `src/main/java/com/strategysquad/agentic/risk/*`
- `src/test/java/com/strategysquad/agentic/decision/*`
- `src/test/java/com/strategysquad/agentic/risk/*`

Done when:

- the system can explain why it would enter, hold, skip, book, reduce, or exit
- no decision mutates a position without an audit event

### Phase 3: Position Builder and profit booking

Deliverables:

- `PositionPlan` builder
- near-delta-neutral short structure construction
- profit-booking policy
- integration with position-session actions
- report fields for scanner, decision, builder, and booking context

Done when:

- replay can open a simulated structure, monitor it, book profit, and restart scanning
- booked PnL remains correct after partial and full exits

### Phase 4: Adjustment expansion and global Risk Guard

Deliverables:

- `SHIFT_STRIKE`
- `EXIT_LEG`
- `EXIT_ALL`
- global risk guard enforcement before every command
- drawdown and premium-expansion checks
- churn and cooldown enforcement outside one-off adjustment logic

Done when:

- no accepted action worsens risk without a hard-risk justification
- every forced action identifies the guard condition that triggered it

### Phase 5: Orchestrator, API, UI, and reports

Deliverables:

- market-day orchestrator
- state machine persistence
- live/simulation APIs for agent status
- compact UI panel for agent state and last decision
- execution report sections for scanner ranking, signal snapshots, decision commands, risk guard overrides, and profit booking

Done when:

- a full simulation run can be replayed end to end from pre-open scan to report
- the UI can show the active agent state without becoming an order-entry screen

### Phase 6: Live-assist gate

Deliverables:

- manual confirmation gate for any live-affecting action
- operator-visible reason and risk summary
- explicit session halt/reset controls
- production-readiness checklist

Done when:

- live mode can recommend actions with full context
- the system still does not place broker orders automatically
- any future broker execution phase has a separate approved design

## Required Audit Trail

Every scanner candidate, decision, adjustment, profit booking, risk override, and skip should be reconstructable.

Minimum audit fields:

- timestamp
- mode: `simulation`, `paper`, or `live-assist`
- state machine state
- command type
- selected candidate ids
- current position session id
- live spot
- net delta before and after
- theta state
- theta progress ratio
- live PnL
- booked PnL
- liquidity score
- risk guard decision
- reason code
- human-readable explanation

## Non-Goals For The Next Phase

- no broker order placement
- no black-box optimizer
- no weakening of canonical historical truth
- no live data merged into historical tables
- no UI-only pricing logic
- no unlabeled or trader-misleading metrics

## Immediate Next Step

Implement Phase 1:

1. Define `CandidateOpportunity`.
2. Build the scanner query over active weekly NIFTY and BANKNIFTY contracts.
3. Compare live price to historical context buckets.
4. Score premium richness, theta opportunity, liquidity, and delta risk.
5. Extract signal snapshot logic so theta and delta are reusable.
6. Add tests before wiring any position mutation.
