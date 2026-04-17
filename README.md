# strategy-squad
Agent-driven algorithmic trading platform for Nifty & BankNifty options

## Golden Source Architecture

Single source of truth for all options analytics, strategy, and replay workflows.

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
volume           LONG
open_interest    LONG
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
    moneyness_points     DOUBLE,
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

### What changed from v1 (must-change-now summary)

These changes were applied to the v1 schema before further implementation. They address gaps that would be expensive or impossible to fix after data is loaded.

| Change | Reason |
|--------|--------|
| Two-timestamp model (`exchange_ts` + `ingest_ts`) on live tables | Without defined event-time semantics, cross-stream joins and replay drift. Must be locked before first live ingestion. |
| `spot_historical` + `spot_live` tables added | Moneyness cannot be computed without underlying spot price. Cannot be retrofitted without full re-enrichment. |
| `options_enriched` expanded with moneyness (pct + points), underlying price, denormalized fields | Moneyness and DTE are the primary dimensions for all target use cases. Ad hoc computation in consumers produces divergent results. |
| `options_context_buckets` table added | Per-contract buckets cannot answer "Is this price normal for this moneyness/DTE context?" The context table is the primary strategy lookup structure. |
| `expiry_type` added to `instrument_master` | Weekly vs monthly distinction affects analytics. Adding after data is loaded requires backfill against exchange calendars. |
| Raw immutability policy | If raw data is ever mutated, replay results become non-reproducible. Costs nothing to enforce now. |

---

### Later enhancements

These are valuable but not required for the initial trading-grade pipeline.

| Enhancement | Purpose | When to add |
|-------------|---------|-------------|
| `options_intraday_features` | Rolling volumes (5m/10m/30m), returns, response ratios | After enriched layer is stable |
| `pcr_historical` | Put-call ratio by underlying and date | Can be derived from `options_historical` when needed |
| `options_tick_archive` | Selective tick storage for ATM/near-ATM contracts | After selection policy (which strikes, which sessions) is defined |
| Regime labels (vol regime, session type, day type) | Contextual baselines | Requires empirical validation first |
| Partitioning optimization | Composite partitioning, secondary indexes | When daily row counts exceed ~10M or query latency is a concern |

---

### Implementation sequence

- [x] Create `instrument_master` with `expiry_type`
- [x] Create `spot_historical` + `spot_live`
- [x] Create `options_live` with `exchange_ts` / `ingest_ts` / `underlying`
- [x] Create `options_enriched` with moneyness (pct + points), underlying price, denormalized fields
- [x] Create `options_context_buckets`
- [x] Implement Bhavcopy ingestion (options + spot historical)
- [ ] Implement live feed ingestion with two-timestamp model
- [ ] Implement enrichment pipeline (point-in-time spot join → moneyness computation)
- [ ] Implement contextual aggregation pipeline

### Active next-step driver

- **Current status summary**: The repository is aligned through the historical path. Canonical schema + DDL are present, and Bhavcopy ingestion writes `instrument_master`, `options_historical`, and `spot_historical`. Live raw ingestion, enrichment, and aggregation are not implemented yet.
- **Next required step**: Implement the minimal live ingestion contract and persistence path for both `options_live` and `spot_live`. Lock the canonical payload fields, append-only write behavior, and `exchange_ts` / `ingest_ts` handling before any enriched-layer work.
- **Reason**: `options_enriched` depends on a point-in-time join against immutable `spot_live`, and replay depends on exchange-time-ordered raw events. Until step 7 exists, steps 8 and 9 cannot be implemented correctly.
- **Ownership recommendation**: Feed-service ownership should provide/adapt the incoming tick payload contract. Golden Source / analytic-vault implementation should own QuestDB persistence and contract tests for the live raw tables.
- **Proposed next issue**: `Implement canonical live tick ingestion for options_live and spot_live`
- **Codex review needed**: Not for this status update. Yes for the implementation PR that introduces live ingestion.
