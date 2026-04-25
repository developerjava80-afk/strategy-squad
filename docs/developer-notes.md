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
