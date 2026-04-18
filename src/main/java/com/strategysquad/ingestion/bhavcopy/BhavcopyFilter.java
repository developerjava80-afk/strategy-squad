package com.strategysquad.ingestion.bhavcopy;

import java.util.Set;

/**
 * Filters rows to the v1 scope (NIFTY/BANKNIFTY options).
 */
public class BhavcopyFilter {
    private static final Set<String> ALLOWED_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY");

    public boolean isRelevant(BhavcopyCsvReader.CsvRow row) {
        // Old bhavcopy format
        String instrument = normalize(row.column("INSTRUMENT"));
        String symbol = normalize(row.column("SYMBOL"));
        String optionType = normalize(row.column("OPTION_TYP"));
        if ("OPTIDX".equals(instrument)
                && ALLOWED_UNDERLYINGS.contains(symbol)
                && ("CE".equals(optionType) || "PE".equals(optionType))) {
            return true;
        }
        // UDiFF format
        String finInstrmTp = normalize(row.column("FININSTRMTP"));
        String tckrSymb = normalize(row.column("TCKRSYMB"));
        String optnTp = normalize(row.column("OPTNTP"));
        return "IDO".equals(finInstrmTp)
                && ALLOWED_UNDERLYINGS.contains(tckrSymb)
                && ("CE".equals(optnTp) || "PE".equals(optnTp));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
