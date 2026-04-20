# Scenario Research Workstation

This document captures the current structure-testing console implementation and how it stays aligned to the platform's golden-source historical model.

Required pre-task review:

- Before implementing or changing any strategy-analysis logic, recommendation logic, payoff metric, or user-facing interpretation, review [options-strategy-domain-contract.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/options-strategy-domain-contract.md).
- Treat that contract as the mandatory business/domain brief for this workstation.
- Before making code changes, also review [developer-notes.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/developer-notes.md).

## Product posture

- The UI is a flat historical structure-testing console, not a trading or order-entry screen.
- Every analytical conclusion must come from canonical historical cohorts and DB-backed derived outputs.
- Moneyness and time-to-expiry remain the primary comparison dimensions at the leg level.
- The visible screen stays compact and numeric.
- Matched cases and deeper detail belong in downloadable reports, not in on-screen clutter.

## Current structure-testing layer

The current UI under `ui/scenario-research` is centered on strategy structures rather than a simple leg toggle.

Supported strategies:

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

The form now posts a full structure definition:

- `mode`
- `orientation`
- `underlying`
- `expiry_type`
- `dte`
- `spot`
- `timeframe`
- `legCount`
- per-leg `optionType`, `side`, `strike`, and `entryPrice`

## Core components

Canonical and historical foundations:

- `src/main/java/com/strategysquad/research/CanonicalScenarioResolver.java`
- `src/main/java/com/strategysquad/research/CanonicalCohortKey.java`
- `src/main/java/com/strategysquad/research/TimeframeAnalysisService.java`
- `src/main/java/com/strategysquad/research/TimeframeAnalysisSnapshot.java`
- `src/main/java/com/strategysquad/research/TimeframeAnalysisSnapshotCalculator.java`
- `src/main/java/com/strategysquad/research/ForwardOutcomeCohortService.java`
- `src/main/java/com/strategysquad/research/ForwardOutcomeSnapshot.java`
- `src/main/java/com/strategysquad/research/ForwardOutcomeSnapshotCalculator.java`

Structure-testing components:

- `src/main/java/com/strategysquad/research/StrategyStructureDefinition.java`
- `src/main/java/com/strategysquad/research/RawStrategyMetrics.java`
- `src/main/java/com/strategysquad/research/EconomicMetrics.java`
- `src/main/java/com/strategysquad/research/EconomicMetricsTransformer.java`
- `src/main/java/com/strategysquad/research/StrategyAnalysisService.java`
- `src/main/java/com/strategysquad/research/StrategyAnalysisCalculator.java`
- `src/main/java/com/strategysquad/research/ResearchConsoleServer.java`

## Strategy analysis contract

The strategy layer now analyzes the full historical structure, not just individual legs.

Default cohort semantics:

- the primary strategy-analysis path uses contextual historical analogs, not exact strike-pair matching
- the contextual match is built from:
  - `underlying`
  - `expiry_type`
  - `option_type`
  - DTE / `time_bucket_15m`
  - `moneyness_bucket`
  - structure type
- exact strike-pair / exact structure matching is treated as a separate drill-down mode, not the default screen behavior

Transformation boundary:

- `RawStrategyMetrics` is the internal signed model used for historical structure assembly
- `EconomicMetrics` is the canonical output contract for UI, recommendation, CSV, and report consumers
- `EconomicMetricsTransformer` is the only supported place to convert signed internals into trader-readable buyer/seller semantics
- low-sample warnings can downgrade confidence, but they must not flip rich into cheap or invert economic meaning

Domain guardrails from the contract apply here directly:

- payoff invariants must not be violated by labels or metrics
- raw historical best/worst values must not be implied as current-trade payoff bounds
- recommendation output must remain transparent and non-optimizer language
- if a metric is mathematically true but trader-misleading, it should be relabeled, normalized, moved to report, or suppressed

Published compact outputs:

- current premium
- matched structure observations
- average entry premium
- median entry premium
- raw price percentile
- economic percentile
- current structure vs historical average
- average expiry value
- average P&L
- median P&L
- win rate
- best case
- worst case
- premium windows across `5Y`, `2Y`, `1Y`, `6M`, `3M`, `1M`
- average expiry payout
- average seller P&L
- average buyer P&L
- tail-loss view
- recommendation summary with `preferred`, `alternative`, and `avoid`

The exported CSV additionally carries:

- posted leg definitions
- full timeframe rows
- matched historical cases
- recommendation evidence rows

## API surface

Current local APIs:

- `GET /api/fair-value`
- `GET /api/timeframe-analysis`
- `GET /api/forward-outcomes`
- `POST /api/strategy-analysis`
- `GET /api/diagnostics`
- workflow persistence endpoints under `/api/workflow/*`

Live overlay APIs when booted through `KiteLiveConsoleMain`:

- `GET /api/live/status`
- `GET /api/live/spot`
- `POST /api/live/structure`
- `POST /api/live/structure-trend`
- `POST /api/live/overlay`

`POST /api/strategy-analysis` is now the main console endpoint. It accepts a form-encoded multi-leg structure and returns one compact `EconomicMetrics` payload for the UI.

`POST /api/live/overlay` keeps the historical logic centralized: it resolves the current live structure, computes live net premium and current-session trend from isolated live tables/state, then reuses the canonical historical strategy-analysis service for the live-vs-history comparison.

## Recommendation layer

The recommendation block compares a small candidate set built around the same market context and ranks candidates using:

- premium richness vs history
- average realized expiry P&L
- win rate
- downside severity
- sample size / confidence depth

Important modeling note:

- the ranking is deterministic and transparent
- it is not a black-box optimizer
- candidate comparison is currently scoped to a curated nearby strategy set around the same spot/wing context

## Current visible UI

The main screen intentionally shows only:

- strategy selector
- dynamic leg inputs
- snapshot
- premium trend
- realized expiry summary
- recommendation summary
- downloadable CSV report

When live mode is enabled, the top bar also shows:

- live feed connection/staleness state
- latest live spot
- current live structure premium
- last tick age

Live input hydration behavior:

- when live structure quotes are available, the UI hydrates the selected underlying spot into the spot input
- the UI also hydrates per-leg live option prices into the dynamic structure inputs
- the historical comparison still remains canonical and DB-backed; the live values are only current-state overlays used as the current scenario input

Deep diagnostics and matched-case exploration stay off the main screen.

## Reproducibility contract

The structure-testing console remains reproducible because it uses canonical historical data and stores no UI-only pricing truth.

Reproducibility rules:

- historical spot for `NIFTY` and `BANKNIFTY` is canonical
- historical enrichment is rebuilt from canonical `spot_historical`
- PCR remains a derived signal only
- strategy structures are resolved through canonical historical context
- outputs remain replayable from immutable raw plus derived backfill
- pricing logic remains centralized in the DB-backed historical path

## Local run sequence

### Historical data refresh

1. Apply schema migrations:

```sql
-- V001 baseline
-- V002 historical completeness and PCR
-- V003 research workspace tables
```

2. Load raw historical data:

```bash
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.strategysquad.ingestion.bhavcopy.HistoricalLoadMain \
  -Dexec.args="data/bhavcopy/historical jdbc:postgresql://localhost:8812/qdb"
```

3. Rebuild derived historical layers:

```bash
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.strategysquad.ingestion.bhavcopy.HistoricalDerivedBackfillMain \
  -Dexec.args="jdbc:postgresql://localhost:8812/qdb"
```

### Research server

```bash
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.strategysquad.research.ResearchConsoleServer \
  -Dexec.args="8080 jdbc:postgresql://localhost:8812/qdb"
```

Then open:

- `http://localhost:8080`

### Live research server

Apply `V004__live_session_tables.sql`, create `kite.properties`, then start the live console:

```bash
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.strategysquad.ingestion.kite.KiteLiveConsoleMain \
  -Dexec.args="kite.properties"
```

Live runtime notes:

- create local ignored `kite.properties` from `kite.properties.example`
- required keys for the daily login flow:
  - `kite.api.key`
  - `kite.api.secret`
  - `kite.user.id`
- `kite.access.token` is optional; the preferred path is to start the console, open `http://localhost:8080/login.html`, and exchange a fresh `request_token`
- startup now auto-creates the live-session tables used by the overlay path if they are missing
- the startup path refreshes today's NFO instrument universe into `instrument_master`
- current live ATM baseline comes from fresh Kite spot quotes, not stale historical spot
- current weekly/monthly live expiries are derived from the live NFO dump itself
- live ticks persist only into `spot_live`, `options_live`, `options_live_enriched`, `options_live_15m`, and `live_structure_snapshot`
- live overlay data never writes into historical research tables
- the current implementation uses Kite `/quote` polling for transport, but preserves the same live/historical boundary required by the product design

## Validation status

Current local validation for this implementation:

- `mvn test`
- `node --check ui/scenario-research/app.js`

## Non-goals

- No order execution
- No broker workflow
- No pricing logic scattered into downstream UI-only calculations
- No weakening of replayability or determinism
- No return to a cluttered dashboard-style interface for core testing flows
- No black-box strategy recommendation without visible reasons
