# UI Guidance: Algo Testing Console

This document explains every output published by the flat structure-testing console and the functional meaning of each one.

## Screen intent

The screen is a compact historical structure-testing tool.

It is designed to answer:

- what structure am I querying?
- how rich or cheap is the current structure versus history?
- how has comparable structure premium changed across regimes?
- what did comparable structures actually do at expiry?
- which nearby strategy shape looks preferred, acceptable, or worth avoiding?

It is not meant to be:

- a broker screen
- a large research dashboard
- a narrative-heavy analytics page

## Inputs

### Strategy

Published as:

- `Single Option`
- `Long Straddle`
- `Short Straddle`
- `Long Strangle`
- `Short Strangle`
- `Bull Call Spread`
- `Bear Put Spread`
- `Iron Condor`
- `Iron Butterfly`
- `Custom Multi-Leg`

Functional meaning:

- selects the structure template
- determines whether the tested side is buyer-oriented or seller-oriented
- controls the dynamic leg set shown in the form

### Underlying

Published as:

- `NIFTY`
- `BANKNIFTY`

Functional meaning:

- selects the canonical historical underlying partition
- determines strike bucket sizing

### Expiry type

Published as:

- `Weekly`
- `Monthly`

Functional meaning:

- keeps the business input aligned with the option family being tested

### DTE

Published as:

- numeric days-to-expiry

Functional meaning:

- each leg is normalized into canonical time-to-expiry context

### Spot

Published as:

- current underlying spot used for the query

Functional meaning:

- used with each strike to derive moneyness

### Timeframe

Published as:

- `1M`, `3M`, `6M`, `1Y`, `2Y`, `5Y`, `Custom`

Functional meaning:

- selects the active historical regime for the snapshot block
- fixed windows are trailing windows from the latest matched historical date
- `Custom` uses explicit date bounds

### Dynamic leg inputs

Published per leg as:

- option type
- side
- strike
- distance from spot
- entry price

Functional meaning:

- each leg is independently normalized into canonical historical context
- all legs together define the tested structure
- entry prices sum into the current total premium

## Header chips

### Strategy

Functional meaning:

- quick identifier of the structure being tested

### Orientation

Published as:

- `Buyer`
- `Seller`

Functional meaning:

- identifies which side the main P&L metrics refer to

### Timeframe

Functional meaning:

- shows which regime is driving the active snapshot

### Current premium

Functional meaning:

- sum of posted leg entry prices
- reference used in structure percentile and premium-richness comparisons

## Snapshot block

This is the active structure summary for the selected timeframe.

### Observations

Functional meaning:

- matched historical structure count behind the summary

### Avg entry

Functional meaning:

- average total entry premium of comparable historical structures

### Median entry

Functional meaning:

- central structure premium without mean distortion

### Current percentile

Functional meaning:

- percentile rank of the current structure premium inside the selected timeframe

### Vs history

Functional meaning:

- current total premium minus historical average entry premium
- positive means richer than history
- negative means cheaper than history

### Avg expiry value

Functional meaning:

- average realized terminal value of the full structure at expiry

### Avg P&L

Functional meaning:

- average realized P&L for the selected orientation

### Median P&L

Functional meaning:

- central realized P&L for the selected orientation

### Win rate

Functional meaning:

- percentage of comparable historical structures with positive realized P&L

### Best / worst

Functional meaning:

- realized upper and lower P&L bounds seen in matched history

## Premium trend block

This shows how comparable structure premium changed from long-term history into recent history.

### Avg premium by timeframe

Functional meaning:

- average total premium for `5Y`, `2Y`, `1Y`, `6M`, `3M`, `1M`

### Median premium by timeframe

Functional meaning:

- central premium level for each regime

### Vs current by timeframe

Functional meaning:

- current total premium minus the average premium of each regime

### Line chart

Functional meaning:

- visual trend of structure richness from long-term to short-term windows

### Current premium overlay

Functional meaning:

- shows where the live structure sits versus the historical regime path

## Realized expiry block

This keeps the outcome view compact and structure-focused.

### Avg expiry payout

Functional meaning:

- average realized full-structure payout at expiry

### Avg seller P&L

Functional meaning:

- average realized outcome for the seller side

### Avg buyer P&L

Functional meaning:

- average realized outcome for the buyer side

### Win rate

Functional meaning:

- historical positive-outcome rate for the selected side

### Tail loss

Functional meaning:

- tenth-percentile realized P&L for the selected side

### Downside profile

Functional meaning:

- one-line description of the tail-loss shape for the selected side

## Recommendation block

This compares a small candidate set of nearby strategy shapes in the same market context.

### Preferred

Functional meaning:

- highest-ranked candidate based on premium richness, realized P&L, win rate, downside severity, and sample size

### Alternative

Functional meaning:

- viable second-choice candidate

### Avoid

Functional meaning:

- weakest-ranked candidate in the same comparison set

### Reason text

Functional meaning:

- human-readable explanation of why the candidate was ranked there

## Download report

Published as:

- `Download Report CSV`

Functional meaning:

- exports the deeper detail that should not clutter the main screen

The export includes:

- scenario and structure inputs
- snapshot metrics
- timeframe premium windows
- recommendation rows
- matched historical cases

## UX rules

The screen should continue to obey these rules:

- compact first
- numeric first
- canonical data only
- no decorative dashboard panels
- no trading workflow language
- no pricing logic recomputed ad hoc in the browser
- no on-screen matched-case clutter for the main testing flow

## Functional contract summary

Every output on the screen must remain:

- derived from the platform's golden-source historical model
- reproducible from historical reload and backfill
- explainable in compact numeric form
- suitable for fast structure testing without UI clutter
