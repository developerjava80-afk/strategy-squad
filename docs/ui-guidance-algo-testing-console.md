# UI Guidance: Algo Testing Console

This document explains every output published by the flat algo-testing console and the functional meaning of each one.

## Screen intent

The screen is a compact historical strategy-testing tool.

It is designed to answer:

- what scenario am I querying?
- how does the same canonical cohort behave across different historical regimes?
- how does the current price compare with those regimes?
- what does a compact strategy test look like for this setup?

It is not meant to be:

- a broker screen
- a large research dashboard
- a narrative-heavy analytics page

## Inputs

### Underlying

Published as:

- `NIFTY`
- `BANKNIFTY`

Functional meaning:

- selects the canonical historical underlying partition
- determines strike-point bucket sizing and cohort lookup scope

### Option type

Published as:

- `Call`
- `Put`

Functional meaning:

- selects the option orientation used for canonical cohort resolution
- drives the single-option leg when strategy mode is `Single Option`

### Strategy mode

Published as:

- `Single Option`
- `Straddle`
- `Strangle`

Functional meaning:

- controls how compact strategy metrics are derived from historical comparable setups

Current implementation meaning:

- `Single Option`: one option leg matching the selected orientation
- `Straddle`: paired CE and PE at the same strike/day/expiry
- `Strangle`: paired CE and PE wings using symmetric distance buckets around spot

### Expiry type

Published as:

- `Weekly`
- `Monthly`

Functional meaning:

- selects the option family used in the scenario
- remains part of the business input posture even though canonical cohort resolution is still anchored primarily by moneyness and time bucket

### DTE

Published as:

- numeric days-to-expiry

Functional meaning:

- converted into `time_bucket_15m`
- anchors the scenario to a comparable historical time-to-expiry state

### Spot

Published as:

- current underlying spot used for the query

Functional meaning:

- combined with strike to derive the current moneyness state

### Strike

Published as:

- selected strike price

Functional meaning:

- used with spot to derive distance from spot and canonical moneyness bucket

### Distance from spot

Published as:

- strike minus spot, in points

Functional meaning:

- direct business expression of moneyness
- normalized into canonical `moneyness_bucket`

### Option price

Published as:

- current option premium

Functional meaning:

- reference point for percentile, difference-vs-current, and timeframe comparisons

### Timeframe

Published as:

- `1M`, `3M`, `6M`, `1Y`, `2Y`, `5Y`, `Custom`

Functional meaning:

- selects the historical regime window used for the selected summary metrics
- all fixed windows are trailing windows measured backward from the latest available matched historical date
- `Custom` uses explicit date bounds

## Header chips

### Scenario

Published as:

- `UNDERLYING / OPTION_TYPE / EXPIRY_TYPE / DTE`

Functional meaning:

- quick human-readable identifier of the query being tested

### Cohort

Published as:

- `UNDERLYING / OPTION_TYPE / TBx / +/-bucket`

Functional meaning:

- canonical historical cohort key used by the platform
- this is the compact expression of how the input scenario is normalized for history lookup

### Timeframe

Published as:

- selected window such as `1Y` or a custom date range

Functional meaning:

- shows which regime is driving the selected summary and percentile

### Strategy

Published as:

- current strategy mode label

Functional meaning:

- makes it explicit whether the compact strategy metrics are being evaluated as a single-leg, straddle, or strangle test

## Timeframe analysis block

This block shows how comparable pricing behaves from long-term history into shorter-term history.

### Average price by timeframe

Published as:

- average price for `5Y`, `2Y`, `1Y`, `6M`, `3M`, `1M`

Functional meaning:

- shows whether the matched cohort has become richer or cheaper over time
- helps identify regime drift rather than treating all history as one blended baseline

### Line chart

Published as:

- long-term to short-term connected line of average prices

Functional meaning:

- visual trend of regime pricing
- rising line suggests the comparable cohort is getting richer into recent history
- falling line suggests the comparable cohort is getting cheaper into recent history

### Current price overlay

Published as:

- horizontal reference line

Functional meaning:

- shows where today’s premium sits versus the average regime path
- quickly highlights whether current pricing is above or below most historical windows

## Snapshot summary block

This block uses the selected timeframe as the active comparison regime.

### Total observations

Published as:

- matched row count in the selected timeframe

Functional meaning:

- sample depth for the selected historical regime

### Unique contracts

Published as:

- number of distinct instruments in the selected timeframe

Functional meaning:

- breadth of contract coverage behind the summary
- helps distinguish repeated observations from broader contract diversity

### Avg price

Published as:

- average matched premium in the selected timeframe

Functional meaning:

- main regime-average reference for current-price comparison

### Median

Published as:

- median matched premium in the selected timeframe

Functional meaning:

- center of the selected regime without mean distortion from tail values

### Percentile

Published as:

- percentile rank of the current price inside the selected timeframe

Functional meaning:

- tells how rich or cheap the current price is relative to the active regime window

Interpretation pattern:

- lower percentile: historically cheaper
- middle percentile: historically near regime center
- higher percentile: historically richer

### Vs current

Published as:

- current price minus selected timeframe average

Functional meaning:

- compact richness/cheapness gap in absolute premium terms
- positive means current is richer than the selected regime average
- negative means current is cheaper than the selected regime average

## Simple outcome metrics block

This block keeps the forward read compact.

### Decay %

Published as:

- probability of premium decay into expiry from matched history

Functional meaning:

- quick read on whether the comparable setup historically lost premium more often than not into the final path

### Next-day avg move

Published as:

- average next-day premium return

Functional meaning:

- short-horizon directional tendency of the premium after similar setups

### Expiry avg P&L

Published as:

- average expiry-horizon premium return

Functional meaning:

- compact long-path outcome read from matched history

Current implementation note:

- these simple outcome metrics come from the current forward-outcome endpoint and are cohort-based rather than fully timeframe-filtered

## Strategy test block

This block turns the scenario into a compact practical strategy read.

### Avg premium collected

Published as:

- average entry premium across matched historical strategy setups

Functional meaning:

- average credit collected by the tested structure

### Expiry avg value

Published as:

- average final structure value at expiry

Functional meaning:

- average cost to settle or close the tested structure at expiry

### Expiry avg P&L

Published as:

- average profit and loss of the tested structure

Functional meaning:

- compact average edge measure for the selected strategy mode

### Win rate

Published as:

- percentage of matched scenarios with positive expiry P&L

Functional meaning:

- hit rate of the compact strategy test

### Max gain / max loss

Published as:

- best historical observed P&L
- worst historical observed P&L

Functional meaning:

- compact realized range of outcomes in matched history
- meant as a quick risk/reward boundary reference, not as a formal risk model

Current implementation note:

- these strategy metrics are currently interpreted as short-premium outcomes

## Download report

Published as:

- `Download Report CSV`

Functional meaning:

- moves deeper detail outside the compact UI
- provides exportable scenario inputs, timeframe metrics, outcome metrics, and strategy metrics

What belongs in the report rather than on the screen:

- larger detail tables
- full timeframe rows
- deeper diagnostics
- historical cases
- extended distribution detail

## UX rules

The screen should continue to obey these rules:

- compact first
- numeric first
- canonical data only
- no decorative dashboard panels
- no trading workflow language
- no pricing logic recomputed ad hoc in the browser

## Functional contract summary

Every output on the screen must remain:

- derived from the platform’s golden-source historical model
- reproducible from historical reload and backfill
- explainable in compact numeric form
- suitable for fast algo testing without UI clutter
