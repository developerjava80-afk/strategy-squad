# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

Strategy Squad is a **historical options analysis and live-assist intelligence platform** for Nifty and BankNifty options on NSE. The current workstation is still a compact research console, not a broker order-entry screen, and the recommendation layer is not a black-box optimizer. The roadmap now adds an agentic theta-decay decision loop for scanner, signal, decision, adjustment, profit-booking, and risk-guard behavior in simulation and live-assist mode first.

Primary roadmap reference:

- `docs/agentic-live-trading-decision-loop.md`

## Build & Test Commands

```bash
# Compile (skip tests)
mvn -DskipTests compile

# Run all tests
mvn test

# Run a single test class
mvn -Dtest=OptionsEnricherTest test

# Load raw historical data from Bhavcopy CSVs
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.strategysquad.ingestion.bhavcopy.HistoricalLoadMain \
    -Dexec.args="data/bhavcopy/historical jdbc:postgresql://localhost:8812/qdb"

# Rebuild enriched + aggregated tables from raw
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.strategysquad.ingestion.bhavcopy.HistoricalDerivedBackfillMain \
    -Dexec.args="jdbc:postgresql://localhost:8812/qdb"

# Download NSE Bhavcopy archives for a date range
java -cp target/classes com.strategysquad.ingestion.bhavcopy.BhavcopyArchiveDownloadCli 01/04/2026 18/04/2026

# Start the research console UI (Windows, default port 8080)
scripts/start-research-console.bat [port]
```

**Database:** QuestDB accessed via PostgreSQL wire protocol. Default: `jdbc:postgresql://localhost:8812/qdb`

## Architecture

### Layer Pipeline (data flows strictly downward)

```
NSE Bhavcopy CSVs
      ↓
[Ingestion]        ingestion/bhavcopy/  — parse, filter (NIFTY/BANKNIFTY only), normalize, load
      ↓
[Raw Tables]       options_historical, spot_historical, instrument_master  ← append-only, never mutated
      ↓
[Enrichment]       enrichment/  — point-in-time join with spot, computes moneyness (% + points), DTE, time buckets
      ↓
[options_enriched] (derived, rebuildable from raw)
      ↓
[Aggregation]      aggregation/  — 15-min TTE buckets per contract; contextual buckets by moneyness+DTE+type
      ↓
[options_15m_buckets, options_context_buckets]
      ↓
[Derived]          derived/  — PCR, historical signal rows
      ↓
[Research Layer]   research/  — cohort matching, economic metrics, recommendation ranking
      ↓
[ResearchConsoleServer]  → REST API (port 8080) → ui/scenario-research/ (HTML/JS/CSS)
```

### Key Packages

| Package | Role |
|---|---|
| `ingestion/bhavcopy` | NSE UDiFF/Bhavcopy CSV pipeline; 3 entry-point mains |
| `enrichment` | Options enrichment with spot context, InstrumentMasterLookup |
| `aggregation` | 15m bucket and contextual bucket aggregation jobs |
| `derived` | PCR computation, signal derivation |
| `research` | Strategy analysis, cohort matching, economic metrics, REST server |

### Entry Points

- `HistoricalLoadMain` — bulk load raw tables from CSVs
- `HistoricalDerivedBackfillMain` — rebuild all derived tables from raw
- `BhavcopyArchiveDownloadCli` — download NSE archives
- `ResearchConsoleServer` — HTTP server for research console UI

### Research Layer Internals

The research layer has a strict transformation boundary:

- `RawStrategyMetrics` — signed internal representation (negative P&L for short positions, raw percentiles)
- `EconomicMetrics` (via `EconomicMetricsTransformer`) — trader-readable presentation. **This is a one-way transform; never mix or reverse.**
- `CanonicalScenarioResolver` + `FairValueCohortService` — contextual cohort matching by `underlying + expiry_type + DTE_bucket + moneyness_bucket + option_type`
- `StrategyAnalysisService` — full-structure analysis only (never mix legs across strategies)
- Recommendations: only `Preferred / Alternative / Avoid` — never "optimal"

## Domain Contracts (Read Before Changing Anything)

Three documents govern all correctness decisions. Read them before making any non-trivial change:

- **`docs/options-strategy-domain-contract.md`** — Non-negotiable rules on payoff invariants, metric definitions, comparability, units, and UI safety. The final rule: *"A metric that is mathematically correct but trader-misleading is incorrect."*
- **`docs/developer-notes.md`** — Pre-change checklist: mathematical correctness, economic correctness, unit explicitness, trader misread potential, payoff invariant violations, canonical truth preservation.
- **`docs/scenario-research-workstation.md`** — Product posture, supported strategies, API surface, cohort semantics, and transformation boundaries.

## Core Invariants

- **Raw tables are append-only.** Corrections are new rows, never updates.
- **All analytics derive from canonical historical tables.** Nothing is recomputed downstream from downstream results.
- **Point-in-time joins only.** Enrichment uses spot at-or-before the option timestamp — never forward-looking.
- **Instrument IDs are deterministic and human-readable:** `INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<CE|PE>`
- **Weekly and monthly expiries are never mixed** in the same cohort or metric.
- **Units must always be explicit:** points vs. rupees vs. per-lot — never ambiguous.

## Database Schema

Three migration files in `db/migration/` (applied manually, no Flyway runtime):

- `V001` — Core golden source tables (8 tables)
- `V002` — Completeness additions: `settle_price`, `value_in_lakhs`, `change_in_oi`, `pcr_historical`
- `V003` — Research workspace tables

## Tech Stack

- **Java 17**, Maven
- **QuestDB** (PostgreSQL wire, port 8812)
- **JUnit 5** for tests (40+ test classes)
- **Vanilla HTML/CSS/JS** frontend (no framework)
- No Spring — `ResearchConsoleServer` is a lightweight custom HTTP server
