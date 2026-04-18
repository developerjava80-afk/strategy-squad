package com.strategysquad.ingestion.bhavcopy;

import java.util.Map;
import java.util.Set;

/**
 * Filters Bhavcopy rows to identify underlying index spot data.
 *
 * <p>Historical spot should come from true index data when available. Futures
 * rows are accepted only as a deterministic fallback proxy for dates where
 * true spot is unavailable.
 */
public class SpotBhavcopyFilter {
    private static final Set<String> ALLOWED_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY");
    private static final Map<String, String> INDEX_NAME_ALIASES = Map.of(
            "NIFTY 50", "NIFTY",
            "NIFTY BANK", "BANKNIFTY",
            "BANKNIFTY", "BANKNIFTY",
            "NIFTY", "NIFTY"
    );

    public boolean isRelevant(BhavcopyCsvReader.CsvRow row) {
        return classify(row) != null;
    }

    public SpotSource classify(BhavcopyCsvReader.CsvRow row) {
        if (resolveUnderlying(row) == null) {
            return null;
        }

        String instrument = normalize(row.column("INSTRUMENT"));
        if ("FUTIDX".equals(instrument)) {
            return SpotSource.DERIVATIVE_PROXY;
        }

        String finInstrmTp = normalize(row.column("FININSTRMTP"));
        if ("IDF".equals(finInstrmTp)) {
            return SpotSource.DERIVATIVE_PROXY;
        }

        if (hasTrueSpotShape(row)) {
            return SpotSource.TRUE_SPOT;
        }
        return null;
    }

    public String resolveUnderlying(BhavcopyCsvReader.CsvRow row) {
        String symbol = normalize(row.column("SYMBOL"));
        if (ALLOWED_UNDERLYINGS.contains(symbol)) {
            return symbol;
        }

        String tckrSymb = normalize(row.column("TCKRSYMB"));
        if (ALLOWED_UNDERLYINGS.contains(tckrSymb)) {
            return tckrSymb;
        }

        String indexName = normalize(row.column("INDEX NAME"));
        if (!indexName.isEmpty()) {
            return INDEX_NAME_ALIASES.get(indexName);
        }
        return null;
    }

    private boolean hasTrueSpotShape(BhavcopyCsvReader.CsvRow row) {
        String instrument = normalize(row.column("INSTRUMENT"));
        String finInstrmTp = normalize(row.column("FININSTRMTP"));
        String optionType = normalize(row.column("OPTION_TYP"));
        String udiffOptionType = normalize(row.column("OPTNTP"));
        if ("OPTIDX".equals(instrument) || "IDO".equals(finInstrmTp)
                || "CE".equals(optionType) || "PE".equals(optionType)
                || "CE".equals(udiffOptionType) || "PE".equals(udiffOptionType)) {
            return false;
        }
        if ("FUTIDX".equals(instrument) || "IDF".equals(finInstrmTp)) {
            return false;
        }
        return hasValue(row, "INDEX DATE")
                || hasValue(row, "OPEN INDEX VALUE")
                || instrument.isEmpty()
                || finInstrmTp.isEmpty();
    }

    private boolean hasValue(BhavcopyCsvReader.CsvRow row, String column) {
        String value = row.column(column);
        return value != null && !value.trim().isEmpty() && !"-".equals(value.trim());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
