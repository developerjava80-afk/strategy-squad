package com.strategysquad.ingestion.live.session;

import com.strategysquad.enrichment.OptionsEnrichedWriter;

/**
 * Writes live enriched ticks into {@code options_live_enriched}.
 *
 * <p>Isolated from the historical {@code options_enriched} table so live (unverified,
 * pre-settle) data never contaminates the canonical historical baseline.
 *
 * <p>Reuses all logic from {@link OptionsEnrichedWriter} — only the target table differs.
 */
public final class LiveEnrichmentWriter extends OptionsEnrichedWriter {

    private static final String INSERT_SQL =
            "INSERT INTO options_live_enriched"
                    + " (exchange_ts, instrument_id, underlying, option_type, strike,"
                    + "  expiry_date, last_price, underlying_price, minutes_to_expiry,"
                    + "  time_bucket_15m, moneyness_pct, moneyness_points, moneyness_bucket, volume)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public LiveEnrichmentWriter() {
        super(INSERT_SQL);
    }
}
