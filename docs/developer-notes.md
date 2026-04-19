# Developer Notes

These notes are the operational reference for coding work in Strategy Squad.

## Mandatory review before code change

Before making any code change involving:

- strategy-analysis logic
- recommendation logic
- user-facing metrics
- payoff interpretation
- report exports
- confidence or evidence wording
- NIFTY/BANKNIFTY expiry, lot-size, or moneyness semantics

review these documents first:

1. [options-strategy-domain-contract.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/options-strategy-domain-contract.md)
2. [scenario-research-workstation.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/scenario-research-workstation.md)
3. [ui-guidance-algo-testing-console.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/ui-guidance-algo-testing-console.md)

## Required coding behavior

- Treat the domain contract as the primary business reference.
- If a metric is mathematically correct but trader-misleading, treat it as incorrect.
- Preserve canonical historical truth boundaries.
- Prefer suppressing or relabeling unsafe metrics over showing ambiguous values.
- Do not describe the recommendation layer as an optimizer.

## Quick pre-change checklist

1. Is the formula mathematically correct?
2. Is it economically correct?
3. Is the unit explicit?
4. Is it comparable to the current trade?
5. Could a trader misread it?
6. Does it violate any payoff invariant?
7. Does it stay aligned with canonical historical truth?

If any answer fails, stop and fix semantics before implementation.
