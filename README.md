# strategy-squad
Agent-driven algorithmic trading platform for Nifty & BankNifty options

## Golden Source Architecture Review (Single Source of Truth)

### 1) System goal (as interpreted for trading decisions)

The Golden Source must support:
- option pricing context by **moneyness** and **time-to-expiry**
- current volume/activity comparison against **historical regime-aware baselines**
- option response to **underlying movement** (delta-like behavior + realized reaction)
- **replay/simulation** with selective high-resolution reconstruction

For real trading usage, this requires a timestamp-accurate, versioned, auditable market data model with deterministic feature generation.

### 2) What is currently working well

- Clear intent to centralize data into one Golden Source.
- Scope is focused on index options where contract lifecycle and expiry logic can be standardized.
- Early-stage repository means major design corrections are still cheap.

### 3) Critical gaps and hidden risks

Given the current repository state, the architecture is not yet implemented enough to reliably answer the target questions. Key risks to address before further ingestion work:

1. **No explicit canonical schema**
   - Without normalized dimensions for instrument, contract, expiry, strike, option type, and event time, moneyness/time comparisons will be inconsistent.
   - Late schema changes here are expensive and backfill-heavy.

2. **No event-time policy**
   - If ingestion-time is used as truth, replay quality and cross-stream joins (underlying vs option) will drift.
   - Need exchange timestamp, receive timestamp, and processing timestamp captured separately.

3. **No survivorship-safe historical model**
   - Historical comparisons become biased unless delisted/expired contracts and symbol remaps are preserved.
   - Missing contract master/version history will break longitudinal studies.

4. **No feature layer contract**
   - Questions on moneyness/tenor response require deterministic derived fields (forward moneyness bucket, DTE bucket, realized vol window, liquidity state).
   - If derived features are generated ad hoc in downstream consumers, results will diverge.

5. **Replay/simulation not design-locked**
   - Without immutable raw event storage + deterministic reprocessing versions, replay cannot be trusted for decision support.
   - “Selective high-resolution” needs a policy (which symbols/windows/events are retained at tick depth).

6. **Scaling bottlenecks likely if not partitioned now**
   - Options ticks are high-cardinality; naive partitioning by date alone will create hotspot and slow symbol-strike queries.
   - Missing retention/tiering policy will cause cost/performance issues quickly.

### 4) Can the current design answer the required questions?

Short answer: **not reliably yet**.

- **Pricing vs moneyness + time-to-expiry**: Not reliable without canonical strike/expiry dimensions + derived moneyness/DTE features.
- **Volume/activity vs historical context**: Not reliable without regime labels, intraday seasonality baselines, and survivorship-safe history.
- **Option response to underlying movement**: Not reliable without synchronized event-time joins and lag-aware feature generation.
- **Replay/simulation**: Not reliable without immutable raw events, schema versioning, and deterministic backfill/recompute.

### 5) What should change before further implementation

1. **Define and freeze core data contracts now**
   - Raw market events (immutable)
   - Canonical instrument/contract master (versioned)
   - Derived feature table(s) with feature version + computation timestamp

2. **Adopt a strict time model**
   - Required columns: `exchange_ts`, `ingest_ts`, `process_ts`, `event_id`, `source_seq`
   - Event-time watermark/reordering policy for live joins

3. **Add required analytical dimensions**
   - Moneyness (spot and forward variants)
   - DTE exact + bucketed
   - Liquidity/market quality metrics (spread, depth proxy, trade count)
   - Regime context (session bucket, day type, vol regime)

4. **Implement replay-grade storage strategy**
   - Immutable append-only raw zone
   - Curated query zone
   - Deterministic feature recompute with version pinning

5. **Plan partitioning/indexing for option analytics**
   - Partition by date + underlying + expiry (not date only)
   - Secondary indexing strategy for strike/moneyness window scans

### 6) Recommended direction

Proceed in this order:
1. Canonical schema + event-time rules
2. Instrument master + contract lifecycle/versioning
3. Raw + curated ingestion pipeline with idempotency checks
4. Feature layer for moneyness/DTE/response analytics
5. Replay/simulation framework tied to immutable raw data and feature versions

This sequencing minimizes future rework and gives a credible foundation for trading-grade analytics.
