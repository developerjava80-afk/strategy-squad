package com.strategysquad.ingestion.bhavcopy;

import java.util.Set;

/**
 * Filters rows to the v1 scope (NIFTY/BANKNIFTY options).
 */
public class BhavcopyFilter {
    private static final Set<String> ALLOWED_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY");

    public boolean isRelevant(BhavcopyCsvReader.CsvRow row) {
        String instrument = normalize(row.column("INSTRUMENT"));
        String symbol = normalize(row.column("SYMBOL"));
        String optionType = normalize(row.column("OPTION_TYP"));
        return "OPTIDX".equals(instrument)
                && ALLOWED_UNDERLYINGS.contains(symbol)
                && ("CE".equals(optionType) || "PE".equals(optionType));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
