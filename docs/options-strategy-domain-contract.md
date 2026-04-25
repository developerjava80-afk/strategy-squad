# OPTION STRATEGY DOMAIN CONTRACT
### Strategy Squad - Trading-Grade Options Analysis System

---

## Purpose

This document defines non-negotiable domain rules, payoff logic, market structure, and interpretation constraints for building a trading-grade options strategy analysis system for `NIFTY` and `BANKNIFTY`.

This is the primary reference for developer agents and coding assistants. All implementations must comply with this before being considered correct.

## Mandatory pre-code-change rule

Before making any code change that touches:

- strategy-analysis logic
- recommendation logic
- user-facing metrics
- payoff interpretation
- historical matching semantics
- report labeling
- risk/confidence logic
- scanner, signal, decision-agent, profit-booking, risk-guard, or orchestrator behavior
- UI wording for structure outputs

the implementer must review this document and [developer-notes.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/developer-notes.md) for business and domain context.

Required pre-task questions:

1. Is the formula mathematically correct?
2. Is it economically correct for the strategy?
3. Is the unit explicit?
4. Is the number directly comparable to the current setup?
5. Could a trader misread it?
6. Should it be normalized or relabeled?
7. Does it respect payoff invariants?
8. Does it remain aligned with canonical historical truth?

If any answer is `no`, the implementation is incomplete.

---

# 1. PRODUCT IDENTITY

## 1.1 What this system is

- Historical strategy analysis engine
- Structure-based options evaluation system
- Deterministic research workstation
- Canonical-data-driven
- Agentic live-assist intelligence system, after the live decision loop is implemented

## 1.2 What this system is not

- Not a broker UI
- Not a calculator
- Not a black-box optimizer
- Not a sentiment/news-driven system
- Not an automatic broker order-execution system unless a separate approved execution design exists

## 1.3 Core question

For any option structure:

- Is premium rich/cheap vs history?
- What happened in similar cases?
- What is expected payoff behavior?
- Which strategy is preferable in this context?

---

# 2. CANONICAL DATA MODEL RULES

## 2.1 Golden source truth

All pricing and comparison must come from:

- canonical historical options data
- canonical spot data
- derived context tables

## 2.2 Derived signals

Signals like:

- PCR
- VIX
- News/events

are overlays, not pricing truth.

## 2.3 Recommendation layer

- Deterministic ranking engine
- Not a full optimizer
- Must remain explainable

---

# 3. OPTION PAYOFF INVARIANTS (NON-NEGOTIABLE)

## 3.1 Long option

- Max loss = premium paid
- Call -> unlimited upside
- Put -> strong downside

## 3.2 Short option

- Max profit = premium received
- Call -> unlimited loss
- Put -> large downside

## 3.3 Long straddle

- Max loss = total premium paid
- Profit = large move either side

## 3.4 Short straddle

- Max profit = total premium collected
- Max loss = unlimited

Must never show:

> Profit > premium collected

## 3.5 Long strangle

- Max loss = premium paid
- Requires large move

## 3.6 Short strangle

- Max profit = premium collected
- Max loss = large/unbounded

## 3.7 Debit spreads

- Max loss = debit paid
- Max profit = strike difference - debit

## 3.8 Credit spreads

- Max profit = credit received
- Max loss = strike difference - credit

## 3.9 Iron condor

- Max profit = net credit
- Max loss = spread width - credit

## 3.10 Iron butterfly

- Max profit = net credit
- Max loss = defined by wings

## 3.11 Custom structures

If payoff cannot be safely derived:

- do not guess
- label:

> Payoff bounds not safely derivable

---

# 4. STRUCTURE ANALYSIS RULES

- Always compute full structure
- Never mix leg-level metrics into structure output
- Entry = sum of all legs
- Expiry value = combined structure value
- P&L = structure-level only

---

# 5. HISTORICAL MATCHING SEMANTICS

## 5.1 Observation

Observation does not mean trade.

Observation means:

> matched historical structure instance

## 5.2 Matching logic

Based on:

- underlying
- expiry type
- DTE bucket
- moneyness bucket
- valid leg pairing

## 5.3 Important

- Observations are not independent
- Also consider:
  - unique dates
  - regime spread

---

# 6. METRIC DEFINITIONS

## 6.1 Average entry

Mean of structure premiums across matched cohort.

## 6.2 Median entry

Middle value of sorted premiums.

## 6.3 Percentile

Position of current premium vs history.

Interpretation:

- High -> rich (good for selling)
- Low -> cheap (good for buying)

Not:

- win rate
- return %

## 6.4 Avg expiry value

Average structure value at expiry.

## 6.5 Avg P&L

Mean P&L across matched cases.

## 6.6 Median P&L

Typical P&L.

## 6.7 Win rate

Percent of cases where P&L > 0.

## 6.8 Best / worst

Raw sample extremes.

Must not imply:

- current max profit
- current max loss

Must be labeled:

> Raw historical sample extreme

## 6.9 Vs history

If shown, specify whether it means:

- current - historical average
- current - historical median
- absolute points difference
- percentage difference

Prefer both point and percentage difference where useful.

## 6.10 Expectancy support

Do not rely only on:

- percentile
- win rate
- avg P&L

Prefer support from:

- average win
- average loss
- payoff ratio
- expectancy
- tail-loss view

---

# 7. UNIT RULES

Every metric must clearly indicate one of:

- premium points
- rupees
- per lot
- per structure
- normalized %

Never mix units silently.

---

# 8. CURRENT TRADE COMPARABILITY

## 8.1 Safe metrics

- percentile
- normalized P&L
- average behavior

## 8.2 Unsafe without label

- raw best/worst
- historical extreme premiums

## 8.3 Rule

If a metric is mathematically correct but trader-misleading, it must be:

- normalized
- relabeled
- hidden

---

# 9. RECOMMENDATION RULES

## 9.1 Allowed

- Preferred
- Alternative
- Avoid

## 9.2 Not allowed

- Optimal
- Best trade
- Guaranteed

## 9.3 Inputs

- premium richness
- historical P&L
- win rate
- tail risk
- sample size
- event overlay

---

# 10. EVENT / NEWS OVERLAY

## 10.1 Role

Modifier only.

## 10.2 Can influence

- confidence
- risk caution
- strategy ranking

## 10.3 Cannot override

- historical pricing truth

---

# 11. NIFTY / BANKNIFTY CONTRACT & MARKET STRUCTURE (MANDATORY)

## 11.1 Underlyings

- NIFTY (`NIFTY 50` index)
- BANKNIFTY (`Bank Nifty` index)

## 11.2 Expiry cycles

### Weekly expiry

- NIFTY -> Thursday
- BANKNIFTY -> Wednesday

### Monthly expiry

- Last weekly expiry of the month

### Rules

- Weekly and Monthly behavior must not be mixed silently
- UI must clearly show expiry type

## 11.3 DTE

### User-facing

- Calendar-based
- Intuitive

Example:

- Sunday -> Tuesday expiry = `2 DTE`

### Backend

- May use time buckets
- Must map transparently

## 11.4 Lot size

### Rules

- Lot size is defined by exchange
- Lot size can change over time

### System requirements

- Support premium points
- Support rupee conversion
- Never assume fixed historical lot size

### UI requirement

- Show points as primary
- Show `Rs` as secondary when used

## 11.5 Premium vs margin

- Premium is not margin
- Margin must be explicitly labeled
- Do not derive margin implicitly

## 11.6 Futures linkage

- Options are often priced relative to futures
- Carry and basis matter

Rules:

- Use spot for moneyness
- Futures may be optional context
- Never mix spot and futures without clarity

## 11.7 Moneyness

- ATM / ITM / OTM
- Based on a defined reference

Rules:

- ATM straddle must be correctly aligned
- Strangle must be OTM-based

## 11.8 Strike granularity

- NIFTY and BANKNIFTY differ
- Use instrument-aware bucket logic

## 11.9 Expiry-day behavior

- High gamma
- Fast decay
- Must be treated separately where relevant

## 11.10 Volatility (VIX)

- Context signal
- Not pricing truth

---

# 12. UI SAFETY RULES

- Must be trader-readable
- Keep UI compact
- Move complexity to reports

Never show:

- impossible payoff
- unclear units
- misleading comparisons

---

# 13. TESTING RULES

Every metric must pass:

1. math check
2. payoff check
3. unit clarity
4. interpretation safety

Mandatory scenarios:

- short straddle
- long straddle
- strangle
- spreads
- iron condor

---

# 14. IMPLEMENTATION CHECKLIST

Before coding:

- Is it mathematically correct?
- Is it economically correct?
- Is unit clear?
- Comparable to current trade?
- Can trader misread it?
- Violates payoff rules?

If any answer is `yes`, fix first.

---

# 15. SAFE FALLBACK RULES

If unsure:

- hide metric
- label explicitly
- prefer normalized values
- avoid ambiguity

---

# FINAL RULE

> A metric that is mathematically correct but trader-misleading is incorrect.

---

# Copilot Prefix

```text
Apply the Option Strategy Domain Contract.
Any logic or UI that violates payoff invariants, expiry semantics, lot-size interpretation, unit clarity, current-trade comparability, or canonical historical truth must be treated as incorrect.
```
