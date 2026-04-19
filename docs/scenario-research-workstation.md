# Scenario Research Workstation

This document captures the current algo-testing console implementation and how it stays aligned to the platform's golden-source historical model.

## Product posture

- The UI is a flat historical algo-testing console, not a trading or order-entry screen.
- Every analytical conclusion must come from canonical historical cohorts and DB-backed derived outputs.
- Moneyness and time-to-expiry are the primary comparison dimensions.
- The visible screen stays compact and numeric.
- Deeper detail belongs in downloadable reports, not in dashboard clutter.

## Implemented execution order

### 1. Scenario -> Cohort Resolution

User inputs from `ui/scenario-research` are normalized into the canonical context model:

- `underlying`
- `option_type`
- `time_bucket_15m`
- `moneyness_bucket`

Key components:

- `src/main/java/com/strategysquad/research/CanonicalScenarioResolver.java`
- `src/main/java/com/strategysquad/research/CanonicalCohortKey.java`

The UI explicitly shows:

- normalized moneyness interpretation
- normalized DTE / time bucket interpretation
- canonical cohort key
- cohort observation count
- cohort strength classification

### 2. Fair Value Engine

The valuation panel is backed by real historical observations from the canonical historical dataset.

Key components:

- `src/main/java/com/strategysquad/research/FairValueCohortService.java`
- `src/main/java/com/strategysquad/research/FairValueSnapshot.java`
- `src/main/java/com/strategysquad/research/FairValueSnapshotCalculator.java`
- API: `GET /api/fair-value`

The fair-value layer computes:

- historical price distribution
- mean and median
- percentile bands
- current-price percentile placement
- cheap / fair / rich / extreme interpretation

Pricing truth remains anchored to canonical enriched history. PCR is not used as fair-value truth.

### 3. Timeframe Analysis Layer

The current UI uses a timeframe-first regime comparison layer so users can see whether pricing is getting richer or cheaper over time.

Key components:

- `src/main/java/com/strategysquad/research/TimeframeAnalysisService.java`
- `src/main/java/com/strategysquad/research/TimeframeAnalysisSnapshot.java`
- `src/main/java/com/strategysquad/research/TimeframeAnalysisSnapshotCalculator.java`
- API: `GET /api/timeframe-analysis`

The layer computes:

- average price across `5Y`, `2Y`, `1Y`, `6M`, `3M`, `1M`
- median price for the selected timeframe
- observation count for each timeframe
- unique contract count for the selected timeframe
- percentile of current price vs the selected timeframe
- difference between current price and timeframe average

This is the primary compact regime-comparison layer in the current UI.

### 4. Forward Outcome Engine

The opportunity layer measures what actually happened after comparable historical setups.

Key components:

- `src/main/java/com/strategysquad/research/ForwardOutcomeCohortService.java`
- `src/main/java/com/strategysquad/research/ForwardOutcomeSnapshot.java`
- `src/main/java/com/strategysquad/research/ForwardOutcomeSnapshotCalculator.java`
- API: `GET /api/forward-outcomes`

The engine computes:

- next-day premium decay vs expansion probabilities
- expiry-horizon premium decay vs expansion probabilities
- median and mean forward returns
- opportunity framing:
  - `Long premium favored`
  - `Short premium favored`
  - `No clear edge`

This layer uses historical observations only. It does not use static posture rules as truth.

### 5. Strategy Testing Mode

The console now supports compact strategy testing without turning the UI into a large research dashboard.

Key components:

- `src/main/java/com/strategysquad/research/StrategyAnalysisService.java`
- `src/main/java/com/strategysquad/research/StrategyAnalysisSnapshot.java`
- `src/main/java/com/strategysquad/research/StrategyAnalysisCalculator.java`
- API: `GET /api/strategy-analysis`

Supported modes:

- `Single Option`
- `Straddle`
- `Strangle`

Published compact metrics:

- average premium collected
- expiry average value
- expiry average P&L
- win rate
- max gain
- max loss

Current modeling note:

- these metrics are evaluated as short-premium outcomes because the published fields are intended for premium-selling style strategy testing

### 6. Diagnostics & Transparency

The trust layer shows whether the historical conclusion is actually supported by enough evidence.

Key components:

- `src/main/java/com/strategysquad/research/DiagnosticsCohortService.java`
- `src/main/java/com/strategysquad/research/DiagnosticsSnapshot.java`
- `src/main/java/com/strategysquad/research/DiagnosticsSnapshotCalculator.java`
- `src/main/java/com/strategysquad/research/MatchedHistoricalObservation.java`
- API: `GET /api/diagnostics`

The diagnostics layer still exists server-side and remains available for export/report use. It exposes:

- cohort size
- unique instrument count
- unique trade-date count
- next-day and expiry coverage
- concentration level
- sparsity / instability warnings
- representative comparable cases
- comparability reasons

Representative historical cases are drawn from actual cohort matches so users can audit why the system considers them comparable.

### 7. Research Workflow Persistence

The workstation now persists workflow state in DB-backed tables instead of browser-only storage.

Key components:

- `src/main/java/com/strategysquad/research/ResearchWorkspaceService.java`
- `src/main/java/com/strategysquad/research/ResearchScenarioSnapshot.java`
- `src/main/java/com/strategysquad/research/ResearchCollection.java`
- `src/main/java/com/strategysquad/research/ResearchWorkspaceSnapshot.java`
- `src/main/java/com/strategysquad/research/ResearchConsoleServer.java`

Database migration:

- `db/migration/V003__research_workspace_tables.sql`

API surface:

- `POST /api/workflow/collections`
- `GET /api/workflow/studies`
- `POST /api/workflow/studies`
- `GET /api/workflow/studies/{scenarioId}`

Persisted workflow behavior:

- save scenario studies with attached canonical analysis outputs
- clone scenarios for side-by-side research
- organize studies into named collections
- reload saved studies into the builder
- compare live and saved studies using persisted historical outputs
- keep recommendation buckets tied to stored historical evidence

## Current visible UI

The current UI under `ui/scenario-research` intentionally publishes only compact outputs:

- timeframe trend
- snapshot summary
- simple outcome metrics
- strategy test metrics
- downloadable CSV report

Large exploratory sections such as case explorers, deep diagnostics, and extended narrative panels are no longer part of the main visible screen.

## Reproducibility contract

The research console remains reproducible because it stores and displays outputs derived from canonical historical context, not ad hoc downstream calculations.

Reproducibility rules:

- historical spot for `NIFTY` and `BANKNIFTY` is canonical
- historical enrichment is rebuilt from canonical `spot_historical`
- `options_context_buckets` remains the canonical cohort vocabulary
- PCR is a derived signal only
- saved workflow artifacts keep canonical cohort-linked outputs attached
- strategy logic still consumes one canonical DB-backed pricing path

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

Run the local research server:

```bash
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.strategysquad.research.ResearchConsoleServer \
  -Dexec.args="8080 jdbc:postgresql://localhost:8812/qdb"
```

Then open:

- `http://localhost:8080`

Available current APIs:

- `GET /api/fair-value`
- `GET /api/timeframe-analysis`
- `GET /api/forward-outcomes`
- `GET /api/strategy-analysis`
- `GET /api/diagnostics`
- workflow persistence endpoints under `/api/workflow/*`

## Validation status

Current local validation completed for the implementation:

- `mvn test`
- `node --check ui/scenario-research/app.js`

## Non-goals

- No order execution
- No broker workflow
- No pricing logic scattered into downstream UI-only calculations
- No weakening of replayability or determinism
- No return to a cluttered dashboard-style interface for core testing flows
