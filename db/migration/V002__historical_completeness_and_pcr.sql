ALTER TABLE options_historical ADD COLUMN settle_price DOUBLE;
ALTER TABLE options_historical ADD COLUMN value_in_lakhs DOUBLE;
ALTER TABLE options_historical ADD COLUMN change_in_oi LONG;
ALTER TABLE options_enriched ADD COLUMN volume LONG;

CREATE TABLE pcr_historical (
    bucket_ts            TIMESTAMP,
    trade_date           DATE,
    underlying           SYMBOL,
    pcr_by_volume        DOUBLE,
    pcr_by_open_interest DOUBLE,
    put_volume           LONG,
    call_volume          LONG,
    put_open_interest    LONG,
    call_open_interest   LONG,
    sample_count         LONG
) timestamp(bucket_ts) PARTITION BY MONTH;
