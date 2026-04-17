-- Golden Source schema: all 8 tables for the corrected v2 design.
-- Extracted from the frozen architecture in README.md.
-- Target: QuestDB

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
