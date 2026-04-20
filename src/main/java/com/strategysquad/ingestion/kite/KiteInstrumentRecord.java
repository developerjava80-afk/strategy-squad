package com.strategysquad.ingestion.kite;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Single row from the Kite NFO instruments CSV dump.
 *
 * <p>CSV columns (in order):
 * instrument_token, exchange_token, tradingsymbol, name, last_price,
 * expiry, strike, tick_size, lot_size, instrument_type, segment, exchange
 */
public record KiteInstrumentRecord(
        long instrumentToken,
        String exchangeToken,
        String tradingSymbol,
        String name,
        LocalDate expiry,
        BigDecimal strike,
        double tickSize,
        int lotSize,
        String instrumentType,
        String segment,
        String exchange
) {

    /**
     * Parses one CSV line from the Kite instruments dump.
     * Returns null if the line is blank, a header, or cannot be parsed.
     */
    public static KiteInstrumentRecord parseOrNull(String line) {
        if (line == null || line.isBlank() || line.startsWith("instrument_token")) {
            return null;
        }
        String[] cols = line.split(",", -1);
        if (cols.length < 12) {
            return null;
        }
        try {
            long instrumentToken = Long.parseLong(cols[0].trim());
            String exchangeToken = normalizeText(cols[1]);
            String tradingSymbol = normalizeText(cols[2]);
            String name = normalizeText(cols[3]);
            // cols[4] = last_price (ignored)
            String expiryStr = normalizeText(cols[5]);
            LocalDate expiry = expiryStr.isEmpty() ? null : LocalDate.parse(expiryStr);
            BigDecimal strike = new BigDecimal(normalizeText(cols[6]));
            double tickSize = Double.parseDouble(normalizeText(cols[7]));
            int lotSize = Integer.parseInt(normalizeText(cols[8]));
            String instrumentType = normalizeText(cols[9]).toUpperCase();
            String segment = normalizeText(cols[10]);
            String exchange = normalizeText(cols[11]);
            return new KiteInstrumentRecord(
                    instrumentToken, exchangeToken, tradingSymbol, name,
                    expiry, strike, tickSize, lotSize,
                    instrumentType, segment, exchange
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeText(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.trim();
    }

    /** True if this is a NIFTY or BANKNIFTY CE/PE option on NFO. */
    public boolean isNiftyOrBankNiftyOption() {
        return ("NIFTY".equals(name) || "BANKNIFTY".equals(name))
                && ("CE".equals(instrumentType) || "PE".equals(instrumentType))
                && "NFO-OPT".equals(segment)
                && expiry != null;
    }
}
