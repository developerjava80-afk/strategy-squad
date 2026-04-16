# strategy-squad
Agent-driven algorithmic trading platform for Nifty & BankNifty options

## Golden Source — Corrected Target Design

This is the corrected Golden Source design for Strategy Squad. It preserves the existing layered approach (raw → enriched → aggregated) and applies the minimum necessary corrections for trading-grade decision support.

Changes are classified as:
- **Must-change now** — required before further implementation
- **Good-to-have later** — valuable but not blocking
- **Avoid for now** — unnecessary complexity at this stage

---

### Design goals

The Golden Source must reliably support:
1. Option pricing comparison by moneyness and time-to-expiry vs historical behavior
2. Volume and activity comparison against historical context
3. Option price response relative to underlying movement
4. Selective tick-level replay and simulation

---

### Layer architecture (preserved)

```
Raw Layer          → immutable market events (append-only, never modified)
Enriched Layer     → derived fields computed once and stored (moneyness, DTE, response)
Aggregated Layer   → statistical lookup tables for strategy consumption
Reference Layer    → instrument master + spot reference
```

---

### Must-change now

These corrections are required before building further on the current schema.

#### 1. Event-time model

**Problem**: Current `ts` / `timestamp` fields have no defined semantics. If they silently become ingestion time, all cross-stream joins (option vs spot) and replay will drift.

**Correction**: Adopt a two-timestamp model for live tables.

| Field | Meaning | Required in |
|-------|---------|-------------|
| `exchange_ts` | Exchange-reported event time (source of truth for ordering) | `options_live`, `spot_live` |
| `ingest_ts` | Time the system received/persisted the record | `options_live`, `spot_live` |

- `exchange_ts` is the designated timestamp for QuestDB partitioning and time-ordered queries.
- `ingest_ts` is recorded for debugging and latency monitoring only.
- Historical tables (`options_historical`, `spot_historical`) use `trade_date` — no change needed.
- Enriched and aggregated tables inherit `exchange_ts` from upstream.

**Why now**: Changing timestamp semantics after data is loaded requires full backfill. This must be locked before first live ingestion.

#### 2. Spot data tables (required for moneyness)

**Problem**: Without underlying spot price, moneyness cannot be computed. The v1 schema has no spot tables.

**Correction**: Add two spot tables.

**spot_historical**
```
trade_ts         TIMESTAMP     -- designated timestamp (normalized to market close for the trade date)
trade_date       DATE          -- Bhavcopy trade date (denormalized for readable filtering)
underlying       SYMBOL        -- NIFTY / BANKNIFTY
open_price       DOUBLE
high_price       DOUBLE
low_price        DOUBLE
close_price      DOUBLE
```

**spot_live**
```
exchange_ts      TIMESTAMP     -- designated timestamp, exchange-reported
ingest_ts        TIMESTAMP     -- system receive time
underlying       SYMBOL        -- NIFTY / BANKNIFTY
last_price       DOUBLE
```

**Why now**: Moneyness is a hard dependency for the primary use case ("Is this option cheap or expensive for its moneyness and DTE?"). Cannot be retrofitted without re-enriching all historical data.

#### 3. Enriched layer must include moneyness and underlying context

**Problem**: Current `options_enriched` has `minutes_to_expiry` and `time_bucket_15m` but no moneyness, no underlying price, no option type, no strike. Downstream consumers would have to re-join instrument_master and spot data, creating divergent results.

**Correction**: Expand `options_enriched` to include the fields needed for contextual lookup.

**options_enriched** (corrected)
```
exchange_ts          TIMESTAMP     -- designated timestamp, inherited from options_live
instrument_id        STRING
underlying           SYMBOL        -- denormalized from instrument_master
option_type          SYMBOL        -- CE / PE, denormalized
strike               DOUBLE        -- denormalized
expiry_date          TIMESTAMP     -- denormalized
last_price           DOUBLE
underlying_price     DOUBLE        -- spot price at enrichment time
minutes_to_expiry    INT
time_bucket_15m      INT           -- floor(minutes_to_expiry / 15)
moneyness_pct        DOUBLE        -- (strike - underlying_price) / underlying_price * 100
moneyness_bucket     INT           -- rounded moneyness band (e.g., nearest 1% or nearest 50 points)
```

**Enrichment rule**: Each `options_live` record is enriched exactly once using the latest available `spot_live` price for the same underlying at or before the option's `exchange_ts`. This join is point-in-time — never forward-looking.

**Why now**: Moneyness and DTE are the two primary dimensions for all four target use cases. Computing them ad hoc in consumers will produce inconsistent results across services.

#### 4. Contextual aggregation table

**Problem**: Current `options_15m_buckets` aggregates by `instrument_id` + `time_bucket_15m`. This answers "What was the average price for *this specific contract* at this DTE?" but cannot answer "What is normal pricing for *any 2% OTM CE at 120 minutes to expiry*?"

**Correction**: Add a context-aware aggregation table that groups by moneyness bucket + DTE bucket + option type.

**options_context_buckets**
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

This table enables the core question: *"Is the current option price normal or abnormal for this moneyness/DTE context?"*

**Why now**: This is the primary lookup structure for strategy decisions. Without it, the system stores data but cannot answer the questions it was built for.

#### 5. Instrument master: preserve expired contracts

**Problem**: Current `is_active` flag exists but no policy is defined. If expired contracts are deleted or filtered out by default, historical comparisons become survivorship-biased.

**Correction**:
- Expired contracts remain in `instrument_master` with `is_active = false`.
- Add `expiry_type` field: `WEEKLY` / `MONTHLY` (affects analytics behavior, must be known at query time).
- No other changes to instrument_master.

**instrument_master** (corrected)
```
instrument_id    STRING
underlying       SYMBOL
symbol           SYMBOL
expiry_date      TIMESTAMP
strike           DOUBLE
option_type      SYMBOL
lot_size         INT
tick_size        DOUBLE
exchange_token   STRING
trading_symbol   STRING
is_active        BOOLEAN
expiry_type      SYMBOL        -- WEEKLY / MONTHLY (new)
created_at       TIMESTAMP
updated_at       TIMESTAMP
```

**Why now**: Deleting expired contracts is irreversible. Adding `expiry_type` after data is loaded requires backfill matching against exchange calendars.

#### 6. Raw tables are append-only and immutable

**Policy**: `options_live`, `spot_live`, `options_historical`, and `spot_historical` must never be updated or deleted after insertion.

- Corrections go into new rows or a separate corrections table.
- Enriched and aggregated tables can be recomputed from raw tables.
- This is the foundation for trustworthy replay.

**Why now**: If raw data is ever mutated, replay results become non-reproducible. This policy costs nothing to enforce now but is impossible to retrofit.

#### 7. Corrected `options_live` schema

Add `exchange_ts` / `ingest_ts` and denormalize `underlying` for efficient partition queries.

**options_live** (corrected)
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

Partition by DAY. Index on `underlying`.

---

### Good-to-have later

These improve the system but are not blocking for the initial trading-grade pipeline.

#### Intraday feature table (`options_intraday_features`)
Rolling volumes (5m, 10m, 30m), option returns, underlying returns, and response ratios. Useful for volume/activity comparison and response analysis. Can be added after the enriched layer is stable and proven.

#### PCR historical table (`pcr_historical`)
Put-call ratio by underlying and date. Useful for sentiment context. Low urgency — can be derived from existing `options_historical` data when needed.

#### Tick archive (`options_tick_archive`)
Selective tick-by-tick storage for ATM and near-ATM contracts only. Useful for high-resolution replay and debugging. Add once the selection policy (which strikes, which sessions) is defined.

#### Regime labels
Vol regime, session type (opening, closing, mid-day), and day type (expiry day, event day, normal). Valuable for contextual baselines but requires domain-specific classification logic that should be validated empirically first.

#### Partitioning optimization
Current partition-by-DAY for live tables and partition-by-MONTH for historical is adequate for initial scale. Revisit when daily live row counts exceed ~10M or query latency becomes a concern.

---

### Avoid for now

These add complexity without proportional benefit at current scale.

| Idea | Why avoid |
|------|-----------|
| Separate `process_ts` in every table | Two timestamps (`exchange_ts` + `ingest_ts`) are sufficient. A third adds overhead with no decision-support value. |
| Forward moneyness (using interest rates) | Spot moneyness is adequate for index options at the DTE ranges we care about. Forward moneyness requires an interest rate model — unnecessary complexity. |
| Schema versioning infrastructure | Feature version tracking can be handled by recompute-and-replace on enriched/aggregated tables. Formal versioning infrastructure is premature. |
| Event sourcing / CQRS patterns | The append-only raw + recomputable derived layer achieves the same replay guarantee without the operational complexity. |
| Multi-exchange / multi-asset generalization | Design for NIFTY and BANKNIFTY options only. Generalize later if needed. |
| Complex survivorship bias tracking | Keeping expired contracts with `is_active = false` is sufficient. Full contract lifecycle audit trails are unnecessary at this scale. |

---

### Corrected full table list

| # | Table | Layer | Status |
|---|-------|-------|--------|
| 1 | `instrument_master` | Reference | **Corrected** — added `expiry_type` |
| 2 | `options_historical` | Raw | Unchanged |
| 3 | `options_live` | Raw | **Corrected** — `exchange_ts` + `ingest_ts`, added `underlying` |
| 4 | `spot_historical` | Raw | **New** |
| 5 | `spot_live` | Raw | **New** |
| 6 | `options_enriched` | Enriched | **Corrected** — added moneyness, underlying price, denormalized fields |
| 7 | `options_15m_buckets` | Aggregated | Unchanged (per-contract aggregation) |
| 8 | `options_context_buckets` | Aggregated | **New** (moneyness × DTE contextual lookup) |

---

### Corrected QuestDB DDL

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
    volume           LONG,
    open_interest    LONG
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
    moneyness_bucket     INT
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

### How this design answers the target questions

| Question | How it is answered |
|----------|--------------------|
| Option pricing vs moneyness + time-to-expiry | Query `options_context_buckets` for the relevant `moneyness_bucket` + `time_bucket_15m`. Compare current live enriched price against historical average. |
| Volume/activity vs historical context | Compare current `options_enriched` volume against `options_15m_buckets` and `options_context_buckets` historical averages for the same context. |
| Option response to underlying movement | Join `options_enriched` records over a time window. `underlying_price` and `last_price` are co-located — compute price change ratios directly. |
| Replay and simulation | Raw tables (`options_live`, `spot_live`) are immutable and timestamped with `exchange_ts`. Replay reads raw data in exchange-time order and re-runs enrichment deterministically. |

---

### Implementation sequence

1. Apply instrument_master correction (`expiry_type`)
2. Create `spot_historical` + `spot_live` tables
3. Apply `options_live` corrections (`exchange_ts` / `ingest_ts` / `underlying`)
4. Apply `options_enriched` corrections (moneyness, underlying price, denormalized fields)
5. Create `options_context_buckets` table
6. Update Bhavcopy ingestion to populate `spot_historical`
7. Update live feed ingestion to populate `spot_live` with two-timestamp model
8. Implement enrichment pipeline (point-in-time spot join → moneyness computation)
9. Implement contextual aggregation pipeline
