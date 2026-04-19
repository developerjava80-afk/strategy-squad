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
- `src/main/java/com/strategysquad/research/StrategyAnalysisService.java`
- `src/main/java/com/strategysquad/research/StrategyAnalysisSnapshot.java`
- `src/main/java/com/strategysquad/research/StrategyAnalysisCalculator.java`
- `src/main/java/com/strategysquad/research/ResearchConsoleServer.java`

## Strategy analysis contract

The strategy layer now analyzes the full historical structure, not just individual legs.

Domain guardrails from the contract apply here directly:

- payoff invariants must not be violated by labels or metrics
- raw historical best/worst values must not be implied as current-trade payoff bounds
- recommendation output must remain transparent and non-optimizer language
- if a metric is mathematically true but trader-misleading, it should be relabeled, normalized, moved to report, or suppressed

Published compact outputs:

- current total premium
- matched structure observations
- average entry premium
- median entry premium
- current premium percentile
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

`POST /api/strategy-analysis` is now the main console endpoint. It accepts a form-encoded multi-leg structure and returns one compact structure snapshot for the UI.

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
