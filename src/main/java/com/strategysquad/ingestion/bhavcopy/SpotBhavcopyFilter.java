package com.strategysquad.ingestion.bhavcopy;

import java.util.Set;

/**
 * Filters Bhavcopy rows to identify underlying index (spot) data.
 *
 * <p>NSE Bhavcopy includes futures rows with instrument type {@code FUTIDX}.
 * For daily spot data, we use the nearest-expiry {@code FUTIDX} row which
 * closely tracks the index value. True spot data can also come from the
 * index-level Bhavcopy — this filter accepts both {@code FUTIDX} rows
 * (for F&amp;O Bhavcopy files) and rows where INSTRUMENT is empty or absent
 * and SYMBOL matches NIFTY/BANKNIFTY (for index Bhavcopy files).
 */
public class SpotBhavcopyFilter {
    private static final Set<String> ALLOWED_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY");

    /**
     * Returns {@code true} if the row represents an underlying index data point
     * suitable for {@code spot_historical} ingestion.
     */
    public boolean isRelevant(BhavcopyCsvReader.CsvRow row) {
        // Old bhavcopy format
        String symbol = normalize(row.column("SYMBOL"));
        if (ALLOWED_UNDERLYINGS.contains(symbol)) {
            String instrument = normalize(row.column("INSTRUMENT"));
            if ("FUTIDX".equals(instrument)) {
                return true;
            }
        }
        // UDiFF format
        String tckrSymb = normalize(row.column("TCKRSYMB"));
        if (ALLOWED_UNDERLYINGS.contains(tckrSymb)) {
            String finInstrmTp = normalize(row.column("FININSTRMTP"));
            if ("IDF".equals(finInstrmTp)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
