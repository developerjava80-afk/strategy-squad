# strategy-squad
Agent-driven algorithmic trading platform for Nifty & BankNifty options

## Golden Source Architecture

Single source of truth for all options analytics, strategy, and replay workflows.

Historical canonicality for index underlyings:
- `NIFTY` and `BANKNIFTY` historical pricing context must be anchored to true historical spot.
- Archival derivatives rows are supplemental raw inputs used only to improve completeness where true spot or derivative completeness is missing.
- PCR is a reproducible derived signal, not part of fair-price truth.

Current verified state:
- Historical raw load now supports the downloaded NSE UDiFF CSV layout.
- Non-live refresh rebuilds `options_enriched`, `options_15m_buckets`, `options_context_buckets`, and `pcr_historical` from the raw tables.
- Instrument IDs are human-readable and deterministic so they can be queried directly in analytics and joins.

---

### Design goals

1. Compare option pricing by moneyness and time-to-expiry against historical behavior
2. Compare current volume and activity against historical context
3. Measure option price response relative to underlying movement
4. Support deterministic replay and simulation from immutable raw data

---

### Layer architecture

```
Raw Layer          → immutable market events (append-only, never modified)
Enriched Layer     → derived fields computed once and stored (moneyness, DTE, response)
Aggregated Layer   → statistical lookup tables for strategy consumption
Reference Layer    → instrument master + spot reference
```

---

### Tables

#### 1. instrument_master (Reference)

Stable identity for every option contract. Expired contracts are never deleted — they remain with `is_active = false` to prevent survivorship bias in historical analysis.

Instrument ID policy:
- `instrument_id` is deterministic and human-readable (not a hash).
- Format: `INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<OPTION_TYPE>`
- Example: `INS_NIFTY_20260428_24800_CE`, `INS_BANKNIFTY_20260428_61900P5_PE`
- `STRIKE_TOKEN` uses `P` as decimal separator for readability (`61900.5` -> `61900P5`).
- `instrument_master` remains the canonical lookup for `trading_symbol`, `exchange_token`, `expiry_date`, and contract metadata.

```
instrument_id    STRING
underlying       SYMBOL        -- NIFTY / BANKNIFTY
symbol           SYMBOL
expiry_date      TIMESTAMP
strike           DOUBLE
option_type      SYMBOL        -- CE / PE
lot_size         INT
tick_size        DOUBLE
exchange_token   STRING
trading_symbol   STRING
is_active        BOOLEAN       -- false for expired contracts
expiry_type      SYMBOL        -- WEEKLY / MONTHLY
created_at       TIMESTAMP
updated_at       TIMESTAMP
```

Uniqueness: `underlying` + `expiry_date` + `strike` + `option_type`.

#### 2. options_historical (Raw)

Daily historical option records from Bhavcopy. Append-only, never updated.

```
trade_ts         TIMESTAMP     -- designated timestamp (normalized to market close)
trade_date       DATE          -- Bhavcopy trade date
instrument_id    STRING
open_price       DOUBLE
high_price       DOUBLE
low_price        DOUBLE
close_price      DOUBLE
settle_price     DOUBLE        -- Bhavcopy settlement price
volume           LONG
value_in_lakhs   DOUBLE        -- Bhavcopy notional value in lakhs
open_interest    LONG
change_in_oi     LONG          -- daily change in open interest
```

Partition by MONTH.

#### 3. options_live (Raw)

Live market ticks. Append-only, never updated.

Uses a two-timestamp model:
- `exchange_ts` — exchange-reported event time, source of truth for ordering and partitioning
- `ingest_ts` — system receive time, used for latency monitoring only

```
exchange_ts      TIMESTAMP     -- designated timestamp, exchange-reported
ingest_ts        TIMESTAMP     -- system receive time
instrument_id    STRING
underlying       SYMBOL        -- denormalized for partition/filter efficiency
last_price       DOUBLE
bid_price        DOUBLE
ask_price        DOUBLE
volume           LONG
open_interest    LONG
```

Partition by DAY.

#### 4. spot_historical (Raw)

Daily underlying index data from Bhavcopy. Required for historical moneyness computation. Append-only, never updated.

Historical source policy:
- True historical index spot is the canonical source for `NIFTY` / `BANKNIFTY`.
- Derivative proxy rows are admitted only as deterministic fallback when true spot is unavailable for a date.
- Downstream historical enrichment and context tables are always rebuilt from the canonical `spot_historical` dataset.
- Historical backfill prefers true spot/index files first, then deterministic derivative proxy rows only when no true spot row exists.

```
trade_ts         TIMESTAMP     -- designated timestamp (normalized to market close)
trade_date       DATE          -- Bhavcopy trade date
underlying       SYMBOL        -- NIFTY / BANKNIFTY
open_price       DOUBLE
high_price       DOUBLE
low_price        DOUBLE
close_price      DOUBLE
```

Partition by MONTH.

#### 5. spot_live (Raw)

Live underlying index ticks. Same two-timestamp model as `options_live`. Append-only, never updated.

```
exchange_ts      TIMESTAMP     -- designated timestamp, exchange-reported
ingest_ts        TIMESTAMP     -- system receive time
underlying       SYMBOL        -- NIFTY / BANKNIFTY
last_price       DOUBLE
```

Partition by DAY.

#### 6. options_enriched (Enriched)

Each `options_live` record enriched exactly once with moneyness and DTE context. The spot price used is the latest `spot_live` value at or before the option's `exchange_ts` (point-in-time join, never forward-looking).

Moneyness is expressed in two forms:
- **Percent** (`moneyness_pct`): `(strike - underlying_price) / underlying_price × 100` — normalized, comparable across underlyings
- **Points** (`moneyness_points`): `strike - underlying_price` — absolute distance, directly meaningful for NIFTY/BANKNIFTY strike structures

```
exchange_ts          TIMESTAMP     -- designated timestamp, inherited from options_live
instrument_id        STRING
underlying           SYMBOL
option_type          SYMBOL        -- CE / PE
strike               DOUBLE
expiry_date          TIMESTAMP
last_price           DOUBLE
underlying_price     DOUBLE        -- spot price at enrichment time
minutes_to_expiry    INT
time_bucket_15m      INT           -- floor(minutes_to_expiry / 15)
moneyness_pct        DOUBLE        -- percent distance from spot
moneyness_points     DOUBLE        -- absolute point distance from spot
moneyness_bucket     INT           -- rounded band (nearest 1% or 50 points, per underlying)
volume               LONG          -- carried from options_live for aggregation
```

Partition by DAY.

#### 7. options_15m_buckets (Aggregated)

Per-contract 15-minute time-to-expiry bucket aggregates. Answers: "What was the average price for *this specific contract* at this DTE?"

```
bucket_ts        TIMESTAMP     -- designated timestamp
trade_date       DATE
instrument_id    STRING
time_bucket_15m  INT
avg_price        DOUBLE
min_price        DOUBLE
max_price        DOUBLE
volume_sum       LONG
sample_count     LONG
```

Partition by MONTH.

#### 8. options_context_buckets (Aggregated)

Contextual aggregation across contracts. Groups by moneyness bucket + DTE bucket + option type. Answers: "Is this option price normal or abnormal for a 2% OTM CE at 120 minutes to expiry?"

```
bucket_ts                TIMESTAMP     -- designated timestamp
underlying               SYMBOL
option_type              SYMBOL        -- CE / PE
time_bucket_15m          INT
moneyness_bucket         INT
avg_option_price         DOUBLE
avg_price_to_spot_ratio  DOUBLE        -- option price / underlying price
avg_volume               DOUBLE
sample_count             LONG
```

Partition by MONTH.

---

### Policies

**Raw immutability**: `options_live`, `spot_live`, `options_historical`, and `spot_historical` are never updated or deleted after insertion. Corrections go into new rows. Enriched and aggregated tables can always be recomputed from raw. This is the foundation for trustworthy replay.

**Event-time model**: Live tables use `exchange_ts` (exchange-reported) as the designated timestamp for QuestDB partitioning and ordering. `ingest_ts` is recorded alongside for debugging. Historical tables use `trade_ts` (normalized to market close) + `trade_date`.

**Enrichment contract**: Each `options_live` record is enriched exactly once using the latest `spot_live` price at or before the option's `exchange_ts`. This join is point-in-time and never forward-looking.

**Historical enrichment contract**: Historical option enrichment is rebuilt from `options_historical` joined to canonical `spot_historical` by `trade_date` + `underlying`. If both true spot and derivative fallback raw rows exist for a date, only the canonical spot record participates in enrichment.

**Derived-signal separation**: Signals such as `pcr_historical` are derived from canonical raw tables during backfill/replay. They may inform analytics or strategy features, but they are not part of fair-price truth.

**Non-live refresh contract**: The supported reset path is truncate non-live tables, reload raw historical CSVs, then backfill derived tables. Live tables are never part of the historical reload.

**Expired contract retention**: Expired contracts remain in `instrument_master` with `is_active = false`. They are never deleted.

---

### How this design answers the target questions

| Question | Approach |
|----------|----------|
| Option pricing vs moneyness + time-to-expiry | Query `options_context_buckets` for the relevant `moneyness_bucket` + `time_bucket_15m`. Compare current live enriched price against historical average. |
| Volume/activity vs historical context | Compare current `options_enriched` volume against `options_15m_buckets` and `options_context_buckets` historical averages for the same context. |
| Option response to underlying movement | `options_enriched` co-locates `underlying_price` and `last_price`. Compute price-change ratios over any time window directly. |
| Replay and simulation | Raw tables are immutable and ordered by `exchange_ts`. Replay reads raw data in exchange-time order and re-runs enrichment deterministically. |

---

### QuestDB DDL

```sql
CREATE TABLE instrument_master (
    instrument_id    STRING,
    underlying       SYMBOL,
    symbol           SYMBOL,
    expiry_date      TIMESTAMP,
    strike           DOUBLE,
    option_type      SYMBOL,
    lot_size         INT,
    tick_size        DOUBLE,
    exchange_token   STRING,
    trading_symbol   STRING,
    is_active        BOOLEAN,
    expiry_type      SYMBOL,
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP
);

CREATE TABLE options_historical (
    trade_ts         TIMESTAMP,
    trade_date       DATE,
    instrument_id    STRING,
    open_price       DOUBLE,
    high_price       DOUBLE,
    low_price        DOUBLE,
    close_price      DOUBLE,
    settle_price     DOUBLE,
    volume           LONG,
    value_in_lakhs   DOUBLE,
    open_interest    LONG,
    change_in_oi     LONG
) timestamp(trade_ts) PARTITION BY MONTH;

CREATE TABLE options_live (
    exchange_ts      TIMESTAMP,
    ingest_ts        TIMESTAMP,
    instrument_id    STRING,
    underlying       SYMBOL,
    last_price       DOUBLE,
    bid_price        DOUBLE,
    ask_price        DOUBLE,
    volume           LONG,
    open_interest    LONG
) timestamp(exchange_ts) PARTITION BY DAY;

CREATE TABLE spot_historical (
    trade_ts         TIMESTAMP,
    trade_date       DATE,
    underlying       SYMBOL,
    open_price       DOUBLE,
    high_price       DOUBLE,
    low_price        DOUBLE,
    close_price      DOUBLE
) timestamp(trade_ts) PARTITION BY MONTH;

CREATE TABLE spot_live (
    exchange_ts      TIMESTAMP,
    ingest_ts        TIMESTAMP,
    underlying       SYMBOL,
    last_price       DOUBLE
) timestamp(exchange_ts) PARTITION BY DAY;

CREATE TABLE options_enriched (
    exchange_ts          TIMESTAMP,
    instrument_id        STRING,
    underlying           SYMBOL,
    option_type          SYMBOL,
    strike               DOUBLE,
    expiry_date          TIMESTAMP,
    last_price           DOUBLE,
    underlying_price     DOUBLE,
    minutes_to_expiry    INT,
    time_bucket_15m      INT,
    moneyness_pct        DOUBLE,
    moneyness_points     DOUBLE,
    moneyness_bucket     INT,
    volume               LONG
) timestamp(exchange_ts) PARTITION BY DAY;

CREATE TABLE options_15m_buckets (
    bucket_ts        TIMESTAMP,
    trade_date       DATE,
    instrument_id    STRING,
    time_bucket_15m  INT,
    avg_price        DOUBLE,
    min_price        DOUBLE,
    max_price        DOUBLE,
    volume_sum       LONG,
    sample_count     LONG
) timestamp(bucket_ts) PARTITION BY MONTH;

CREATE TABLE options_context_buckets (
    bucket_ts                TIMESTAMP,
    underlying               SYMBOL,
    option_type              SYMBOL,
    time_bucket_15m          INT,
    moneyness_bucket         INT,
    avg_option_price         DOUBLE,
    avg_price_to_spot_ratio  DOUBLE,
    avg_volume               DOUBLE,
    sample_count             LONG
) timestamp(bucket_ts) PARTITION BY MONTH;
```

---

### What changed from v1 (must-change-now summary)

These changes were applied to the v1 schema before further implementation. They address gaps that would be expensive or impossible to fix after data is loaded.

| Change | Reason |
|--------|--------|
| Two-timestamp model (`exchange_ts` + `ingest_ts`) on live tables | Without defined event-time semantics, cross-stream joins and replay drift. Must be locked before first live ingestion. |
| `spot_historical` + `spot_live` tables added | Moneyness cannot be computed without underlying spot price. Cannot be retrofitted without full re-enrichment. |
| `options_enriched` expanded with moneyness (pct + points), underlying price, denormalized fields | Moneyness and DTE are the primary dimensions for all target use cases. Ad hoc computation in consumers produces divergent results. |
| `options_context_buckets` table added | Per-contract buckets cannot answer "Is this price normal for this moneyness/DTE context?" The context table is the primary strategy lookup structure. |
| `expiry_type` added to `instrument_master` | Weekly vs monthly distinction affects analytics. Adding after data is loaded requires backfill against exchange calendars. |
| `volume` added to `options_enriched` | Aggregation tables (`options_15m_buckets.volume_sum`, `options_context_buckets.avg_volume`) require volume. Carrying it from `options_live` into the enriched layer makes aggregation self-contained without cross-table joins. |
| Raw immutability policy | If raw data is ever mutated, replay results become non-reproducible. Costs nothing to enforce now. |

---

### Later enhancements

These are valuable but not required for the initial trading-grade pipeline.

| Enhancement | Purpose | When to add |
|-------------|---------|-------------|
| `options_intraday_features` | Rolling volumes (5m/10m/30m), returns, response ratios | After enriched layer is stable |
| `pcr_historical` | Put-call ratio by underlying and date | Derived deterministically from `options_historical` during historical backfill |
| `options_tick_archive` | Selective tick storage for ATM/near-ATM contracts | After selection policy (which strikes, which sessions) is defined |
| Regime labels (vol regime, session type, day type) | Contextual baselines | Requires empirical validation first |
| Partitioning optimization | Composite partitioning, secondary indexes | When daily row counts exceed ~10M or query latency is a concern |

---

### Historical download workflow

Historical derivatives archives can now be downloaded directly from NSE for a single date or a date range.

- Scope is limited to `NIFTY` and `BANKNIFTY`
- Stored files are flattened under `data/bhavcopy/historical/derivatives`
- Date range input uses `dd/MM/yyyy`
- Saturdays and Sundays are skipped automatically
- Missing exchange archive dates are skipped without aborting the whole batch
- Downloaded UDiFF CSVs are filtered to keep only:
  - `NIFTY` / `BANKNIFTY` option rows
  - `NIFTY` / `BANKNIFTY` futures rows used only as a historical fallback proxy when true spot is missing

CLI:

```bash
java -cp target/classes com.strategysquad.ingestion.bhavcopy.BhavcopyArchiveDownloadCli 01/04/2026 18/04/2026
```

Example files:

```text
data/bhavcopy/historical/derivatives/BhavCopy_NSE_FO_0_0_0_20260401_F_0000.csv
data/bhavcopy/historical/derivatives/BhavCopy_NSE_FO_0_0_0_20260417_F_0000.csv
```

### Historical DB load status

The historical pipeline now supports both legacy Bhavcopy CSVs and NSE UDiFF CSVs.

Validated local baseline as of April 2026:
- Historical raw coverage is complete for 1,050 trading days from 2021-04-19 through 2025-07-17.
- `options_historical` contains 3,456,896 rows and `spot_historical` contains 2,100 rows.
- Historical derived coverage is complete for the same 1,050 trading days.
- `options_enriched`, `options_15m_buckets`, and `options_context_buckets` each contain 3,456,896 rows.
- `pcr_historical` contains 2,100 rows, which is 2 underlyings per trading day.
- This non-live historical corpus is the current analytics-ready golden source baseline.

Notes for the current design:
- Place historical files under `data/bhavcopy/historical`, not only `data/bhavcopy/historical/derivatives`, when you are loading both derivative and true spot/index sources.
- File ordering prefers true spot/index CSVs ahead of derivative proxy CSVs for the same trade date so canonical `spot_historical` rows win deterministically during load.
- Historical backfill is the only supported rebuild path for non-live context tables and `pcr_historical`. Strategy consumers should continue reading the canonical database outputs rather than recomputing pricing context downstream.
- Historical date derivation must use timestamp columns such as `trade_ts` converted to IST, not QuestDB `DATE` columns read through JDBC `getDate(...).toLocalDate()`. Using `DATE` here can shift the JVM-side trading calendar and skip real trade days.

Current runnable entrypoints:
- `HistoricalLoadMain` reloads `instrument_master`, `options_historical`, and `spot_historical`.
- `HistoricalDerivedBackfillMain` rebuilds `options_enriched`, `options_15m_buckets`, `options_context_buckets`, and `pcr_historical`.

### Algo Testing Console

The repo now includes a standalone flat structure-testing console under `ui/scenario-research`.

Mandatory domain-review requirement:
- Before any task that changes strategy-analysis logic, recommendation logic, payoff interpretation, or user-facing metrics, review [docs/options-strategy-domain-contract.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/options-strategy-domain-contract.md).
- That contract is the business and domain brief for this project and should be treated as required context, not optional reference material.
- Also review [docs/developer-notes.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/developer-notes.md) before making code changes in those areas.

Product posture:
- fast historical structure-testing engine, not broker execution
- canonical historical context remains the pricing and comparison truth
- moneyness and DTE remain first-class comparison dimensions at the leg level
- compact numeric outputs stay in the UI; deeper detail moves to downloadable reports

Current UI flow:

1. Strategy selector
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
2. Dynamic structure inputs
   - underlying
   - expiry type
   - DTE
   - spot
   - timeframe
   - per-leg option type, side, strike, distance, and entry price
3. Compact snapshot
   - observations
   - average entry premium
   - median entry premium
   - current premium percentile
   - current structure vs historical average
   - average expiry value
   - average P&L
   - median P&L
   - win rate
   - best / worst case
4. Premium trend
   - structure-level premium windows across `5Y`, `2Y`, `1Y`, `6M`, `3M`, `1M`
   - average total premium
   - median premium
   - current premium overlay
5. Realized expiry summary
   - average expiry payout
   - average seller P&L
   - average buyer P&L
   - win rate
   - tail-loss view
6. Recommendation summary
   - `preferred strategy`
   - `alternative strategy`
   - `avoid strategy`
   - clear reasons backed by historical richness, realized P&L, win rate, downside severity, and sample size
7. Report export
   - downloadable CSV for matched cases and deeper detail outside the compact UI

Current implementation note:
- the console is now centered on full strategy structure testing rather than a small strategy toggle
- the UI posts a full multi-leg structure to the backend and receives one compact structure snapshot
- recommendation ranking is deterministic and transparent; it compares a small candidate set around the same market context rather than acting like a black-box optimizer
- the default historical matching path is contextual, not exact strike-pair matching
- contextual historical analogs are resolved by `underlying`, `expiry_type`, `option_type`, `time_bucket_15m`, `moneyness_bucket`, and structure type
- exact strike-pair / exact structure matching is reserved for drill-down behavior, not the primary screen
- raw signed strategy-analysis values are internal only
- all UI, recommendation, and CSV/report output now consume canonical `EconomicMetrics`
- descriptive price percentile and side-aware economic percentile are both published
- low sample can downgrade confidence, but it must not invert economic meaning
- the domain contract governs payoff safety, unit clarity, current-trade comparability, and recommendation honesty

Canonical server-backed components:
- `CanonicalScenarioResolver` resolves each leg into the same cohort vocabulary used by the data platform
- `TimeframeAnalysisService` remains available for regime comparison utilities
- `ForwardOutcomeCohortService` remains available for direct cohort outcome work
- `StrategyStructureDefinition` models posted multi-leg strategy structures
- `RawStrategyMetrics` is the internal signed analysis contract
- `EconomicMetrics` is the canonical trader-readable output contract
- `EconomicMetricsTransformer` is the single raw-to-economic transformation layer
- `StrategyAnalysisService` backs structure-level historical testing and contextual recommendation ranking
- `StrategyAnalysisCalculator` computes compact raw structure-level outputs
- `ResearchConsoleServer` serves the UI and exposes the local research APIs

Current research APIs:
- `GET /api/fair-value`
- `GET /api/timeframe-analysis`
- `GET /api/forward-outcomes`
- `POST /api/strategy-analysis`
- `GET /api/diagnostics`
- workflow persistence endpoints remain available for saved studies and collections

### Live Kite Overlay

The repo now includes a live-session overlay path for Zerodha Kite that preserves the canonical historical boundary.

Live design boundary:
- live ticks persist into `spot_live`, `options_live`, and `options_live_enriched`
- live 15-minute session aggregates persist into `options_live_15m`
- current structure snapshots persist into `live_structure_snapshot`
- historical fair-value and strategy-context truth still comes only from the canonical historical tables and services
- live mode is an overlay on top of historical research, not a replacement for it

Runtime components:
- `KiteInstrumentsDumpJob` refreshes the current NFO option universe into `instrument_master`
- `KiteTickerSession` polls Kite `/quote` and writes live raw + enriched data into isolated live tables
- `Live15mAggregator` maintains current-session rolling 15-minute buckets
- `LiveMarketService` resolves user-defined structures into current live contracts, computes live structure premiums, materializes snapshots, and compares live structures against canonical history
- `KiteLiveConsoleMain` boots the console with live endpoints enabled
- `LiveSchemaBootstrapper` ensures the live-session tables exist before live ingestion starts

Live migration:
- apply `db/migration/V004__live_session_tables.sql`

Live entrypoint:

```bash
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.strategysquad.ingestion.kite.KiteLiveConsoleMain \
    -Dexec.args="kite.properties"
```

Expected `kite.properties` keys:
- `kite.api.key`
- `kite.api.secret`
- `kite.user.id`
- optional: `kite.access.token`
- optional: `kite.jdbc.url`
- optional: `kite.console.port`
- optional strike-window and expiry-subscription settings from `KiteLiveConfig`

Recommended setup:
- copy `kite.properties.example` to local ignored `kite.properties`
- fill `kite.api.key`, `kite.api.secret`, and `kite.user.id`
- leave `kite.access.token` blank unless you intentionally want a bootstrap token path

Daily login flow:
- start the console with `scripts\start-research-console.bat` or `KiteLiveConsoleMain`
- open `http://localhost:8080/login.html`
- click `Open Zerodha Kite Login`
- complete the Kite Connect login flow for the same app as `kite.api.key`
- paste the returned `request_token`, or let `login.html` auto-capture it if your Kite app redirect URL points back to that page
- the backend exchanges the `request_token` using `kite.api.secret`, saves the day-scoped `access_token` into local `kite.local.properties`, and reuses it for the rest of the day

Daily login behavior:
- when no valid token exists for the current trading day, the UI opens a login screen
- the user logs in to Zerodha Kite, obtains a `request_token`, and the backend exchanges it using `api_key + api_secret`
- the first successful login of the day saves the resulting `access_token` into local ignored file `kite.local.properties`
- the live console reuses that token for the rest of the day
- if the token expires, the login screen is shown again

Live APIs added when booted through `KiteLiveConsoleMain`:
- `GET /api/live/status`
- `GET /api/live/spot`
- `POST /api/live/structure`
- `POST /api/live/structure-trend`
- `POST /api/live/overlay`

Live feed correctness notes:
- raw live DB writes use native `DOUBLE` bindings for QuestDB compatibility; `BigDecimal -> DOUBLE` writes on the PG wire are not relied on
- current live option-universe selection uses fresh Kite spot quotes for ATM baseline rather than stale historical spot
- current weekly/monthly live expiries are derived from the live NFO dump itself rather than hard-coded weekday assumptions
- the current Kite CSV parser normalizes quoted fields such as `"NIFTY"` so index option rows are actually admitted into the live universe
- the UI live overlay hydrates the selected underlying spot and per-leg live option prices back into the dynamic input form when quotes are available

Current transport note:
- the current repo implementation uses Kite `/quote` polling every 2 seconds rather than the Kite WebSocket SDK
- the architectural boundary remains the same: live transport feeds isolated live raw tables and then overlays canonical historical comparison on top

Completed in code:

- UDiFF-aware filters for options (`IDO`) and derivative fallback rows (`IDF`)
- True historical spot preference for `NIFTY` / `BANKNIFTY` with deterministic fallback to derivative proxy rows only when true spot is unavailable
- Dual-column normalizers (old Bhavcopy + UDiFF names)
- Index-style historical spot row support (`INDEX NAME` / `INDEX DATE` / `OPEN|HIGH|LOW|CLOSING INDEX VALUE`)
- UDiFF instrument metadata mapping into `instrument_master`:
    - `FinInstrmId` -> `exchange_token`
    - `FinInstrmNm` -> `trading_symbol`
    - `NewBrdLotQty` -> `lot_size`
- Full raw historical option completeness in `options_historical`:
    - `settle_price`
    - `value_in_lakhs`
    - `change_in_oi`
- Readable deterministic instrument IDs in the `INS_<UNDERLYING>_<YYYYMMDD>_<STRIKE_TOKEN>_<OPTION_TYPE>` format
- Runnable local historical loaders:
    - `HistoricalLoadMain` for raw historical tables
    - `HistoricalDerivedBackfillMain` for non-live derived tables
        - `options_enriched`
        - `options_15m_buckets`
        - `options_context_buckets`
        - `pcr_historical`

---

### Local non-live refresh (from downloaded CSV files)

The sequence below refreshes all non-live tables and keeps live tables untouched.

1. Compile:

```bash
mvn -DskipTests compile
```

2. Load historical raw tables from downloaded CSV files:

```bash
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.strategysquad.ingestion.bhavcopy.HistoricalLoadMain \
    -Dexec.args="data/bhavcopy/historical jdbc:postgresql://localhost:8812/qdb"
```

3. Backfill non-live derived tables from historical raw tables:

```bash
mvn -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.strategysquad.ingestion.bhavcopy.HistoricalDerivedBackfillMain \
    -Dexec.args="jdbc:postgresql://localhost:8812/qdb"
```

Tables refreshed by this flow:

- `instrument_master`
- `options_historical`
- `spot_historical`
- `options_enriched`
- `options_15m_buckets`
- `options_context_buckets`
- `pcr_historical`

Live tables intentionally excluded:

- `options_live`
- `spot_live`

Recommended analytics assumption:

- Yes, for the non-live historical stack you can now treat the dataset as clean enough to proceed with analytics work.
- Keep the assumption scoped to historical/raw-derived tables rebuilt by this flow. Live ingestion and any future schema changes still need their own validation cycle.

Current repo baseline:
- the repo contains an expanded historical derivatives archive under `data/bhavcopy/historical/derivatives`
- historical pricing context and derived tables are aligned to canonical spot-based underlying history
- `ui/scenario-research` is the current end-to-end flat structure-testing console backed by canonical historical APIs

---

### QuestDB compatibility note

The currently deployed local QuestDB schema may be narrower than the frozen DDL shown above.

Use `db/migration/V002__historical_completeness_and_pcr.sql` when upgrading an existing local QuestDB instance from the older layout.

If `pcr_historical` already exists, run only the missing column upgrades from V002:

```sql
ALTER TABLE options_historical ADD COLUMN settle_price DOUBLE;
ALTER TABLE options_historical ADD COLUMN value_in_lakhs DOUBLE;
ALTER TABLE options_historical ADD COLUMN change_in_oi LONG;
ALTER TABLE options_enriched ADD COLUMN volume LONG;
```
