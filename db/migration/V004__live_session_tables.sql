-- Live session tables: isolated from historical golden source.
-- options_enriched remains canonical historical truth.
-- These tables are current-session only and can be truncated at market close.

-- Current-session enriched ticks. Same schema as options_enriched but for live (unverified) data.
-- Keeps real-time ticks out of the historical baseline used by the research layer.
CREATE TABLE options_live_enriched (
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

-- Rolling 15-minute session aggregates for the current trading day.
-- Historical 15m aggregation continues to use options_15m_buckets.
CREATE TABLE options_live_15m (
    bucket_ts        TIMESTAMP,
    session_date     TIMESTAMP,
    instrument_id    STRING,
    time_bucket_15m  INT,
    avg_price        DOUBLE,
    min_price        DOUBLE,
    max_price        DOUBLE,
    volume_sum       LONG,
    sample_count     LONG,
    last_updated_ts  TIMESTAMP
) timestamp(bucket_ts) PARTITION BY DAY;

-- Live structure premium snapshot: net debit/credit per named strategy structure.
-- Refreshed on each tick batch flush; queried by the UI for live overlay.
CREATE TABLE live_structure_snapshot (
    snapshot_ts      TIMESTAMP,
    session_date     TIMESTAMP,
    structure_key    STRING,
    underlying       SYMBOL,
    expiry_type      SYMBOL,
    net_premium      DOUBLE,
    leg_count        INT,
    leg_detail_json  STRING
) timestamp(snapshot_ts) PARTITION BY DAY;
