# Developer Notes

These notes are the operational reference for coding work in Strategy Squad.

## Mandatory review before code change

Before making any code change involving:

- strategy-analysis logic
- recommendation logic
- user-facing metrics
- payoff interpretation
- report exports
- live delta-adjustment behavior
- simulation replay behavior
- position-session persistence or audit logging
- scanner, signal engine, decision-agent, profit-booking, risk-guard, or orchestrator behavior
- confidence or evidence wording
- NIFTY/BANKNIFTY expiry, lot-size, or moneyness semantics

review these documents first:

1. [options-strategy-domain-contract.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/options-strategy-domain-contract.md)
2. [scenario-research-workstation.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/scenario-research-workstation.md)
3. [ui-guidance-algo-testing-console.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/ui-guidance-algo-testing-console.md)
4. [live-kite-overlay.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/live-kite-overlay.md)
5. [agentic-live-trading-decision-loop.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/agentic-live-trading-decision-loop.md)

## Required coding behavior

- Treat the domain contract as the primary business reference.
- If a metric is mathematically correct but trader-misleading, treat it as incorrect.
- Preserve canonical historical truth boundaries.
- Prefer suppressing or relabeling unsafe metrics over showing ambiguous values.
- Do not describe the recommendation layer as an optimizer.
- Keep report generation separate from adjustment decision behavior.
- Treat simulation as a consumer of the live-market services, not a separate analytics fork.
- Preserve booked PnL, manual exit behavior, and persisted audit semantics when changing live adjustment flows.
- Treat the agentic roadmap as live-assist and simulation-first. Do not add broker order execution without a separate approved design.
- Route every scanner, decision, adjustment, profit-booking, or risk-guard action through explicit reason codes and reconstructable audit state.

## Quick pre-change checklist

1. Is the formula mathematically correct?
2. Is it economically correct?
3. Is the unit explicit?
4. Is it comparable to the current trade?
5. Could a trader misread it?
6. Does it violate any payoff invariant?
7. Does it stay aligned with canonical historical truth?

If any answer fails, stop and fix semantics before implementation.

---

## Agentic loop — required reading and rules

Before touching any file under `src/main/java/com/strategysquad/agentic/` or any related test, read:

1. `docs/agentic-live-trading-decision-loop.md` — the full blueprint: agent contracts, state machine, implementation phases.
2. `docs/options-strategy-domain-contract.md` — Section 16 covers unit and sign rules for all five new agentic output types.
3. `docs/agentic-loop-implementation-plan.md` — the task-based plan with per-task acceptance criteria.

### Audit trail requirement

Every scanner candidate, decision command, adjustment, profit booking, risk override, and skip must produce a written audit record **before** the action is applied — not after. The audit must include:

- `timestamp`, `mode`, `command_type`, `reason_code` (machine-readable), `explanation` (trader-readable)
- `risk_guard_decision` and `overridden_by_risk_guard` flag
- `net_delta_before`, `net_delta_after`, `theta_state`, `theta_progress_ratio`
- `live_pnl`, `booked_pnl`, `liquidity_score`

An audit record without `reason_code` or `explanation` is incomplete.

### Simulation-first rule

Every new agent service must work in simulation mode (via `SimulationClock` injection) before live mode is activated. Do not wire live market data into a new agent service until:

1. The service has passing unit tests with historical fixtures.
2. At least one replay test has run successfully end to end.
3. The decision quality has been reviewed in paper mode.

### Risk Guard gate rule

`RiskGuardService.evaluate()` must be called before **every** `DecisionCommand` is applied — including `HOLD`, `ADD`, and `BOOK_PROFIT`, not just `ENTER`. If the Risk Guard escalates the decision, the final command type must reflect the override, and `overridden_by_risk_guard` must be `true` in the audit record.

### No broker order placement

No code in the `agentic/` package or anywhere else in this repo may call Kite or any other broker order API. This constraint may only be removed by a separately approved written design document. The live-assist gate (Phase 6) is the final boundary: it recommends, the operator confirms, the system does not execute.

### One structure at a time

Do not add multi-structure orchestration until the single-structure loop is stable across at least five simulation replays with reviewed reports. Premature complexity in the builder or orchestrator creates untestable audit state.
