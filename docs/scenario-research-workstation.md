# Scenario Research Workstation

This document captures the current research-console implementation and how it stays aligned to the platform's golden-source historical model.

## Product posture

- The UI is a historical research workstation, not a trading or order-entry screen.
- Every analytical conclusion must come from canonical historical cohorts and DB-backed derived outputs.
- Moneyness and time-to-expiry are the primary comparison dimensions.
- Saved research artifacts must remain reproducible through historical reload, backfill, and replay.

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

### 3. Forward Outcome Engine

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

### 4. Diagnostics & Transparency

The trust layer shows whether the historical conclusion is actually supported by enough evidence.

Key components:

- `src/main/java/com/strategysquad/research/DiagnosticsCohortService.java`
- `src/main/java/com/strategysquad/research/DiagnosticsSnapshot.java`
- `src/main/java/com/strategysquad/research/DiagnosticsSnapshotCalculator.java`
- `src/main/java/com/strategysquad/research/MatchedHistoricalObservation.java`
- API: `GET /api/diagnostics`

The diagnostics layer exposes:

- cohort size
- unique instrument count
- unique trade-date count
- next-day and expiry coverage
- concentration level
- sparsity / instability warnings
- representative comparable cases
- comparability reasons

Representative historical cases are drawn from actual cohort matches so users can audit why the system considers them comparable.

### 5. Research Workflow Persistence

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

## Validation status

Current local validation completed for the implementation:

- `mvn test`
- `node --check ui/scenario-research/app.js`

## Non-goals

- No order execution
- No broker workflow
- No pricing logic scattered into downstream UI-only calculations
- No weakening of replayability or determinism
