package com.strategysquad.ingestion.kite;

import com.strategysquad.scope.ExpiryType;
import com.strategysquad.scope.InstrumentRef;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link KiteInstrumentRecord} instances (Kite CSV row objects) into
 * {@link InstrumentRef} instances (the scope-domain instrument representation).
 *
 * <p>This bridge is needed because:
 * <ul>
 *   <li>{@code KiteInstrumentRecord} lives in the {@code ingestion.kite} package and
 *       carries raw Kite CSV fields (instrumentToken, tradingSymbol, lotSize, …).</li>
 *   <li>{@code InstrumentRef} lives in the {@code scope} package and carries the
 *       canonical fields needed for subscription management and universe resolution
 *       (instrumentId, kiteToken, optionType, …).</li>
 * </ul>
 *
 * <p>The instrument ID is derived using the same formula as
 * {@link com.strategysquad.ingestion.bhavcopy.InstrumentResolver}:
 * {@code INS_<UNDERLYING>_<YYYYMMDD>_<STRIKETOKEN>_<CE|PE>}.
 */
public final class KiteInstrumentConverter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private KiteInstrumentConverter() {}

    /**
     * Converts a list of Kite instrument records into {@link InstrumentRef} objects.
     *
     * <p>Records that lack a required field (null expiry, zero strike, unknown option type)
     * are silently skipped. This matches the filtering behaviour in
     * {@link KiteInstrumentsDumpJob}.
     *
     * @param records       Kite NFO CSV rows (already filtered to NIFTY/BANKNIFTY CE/PE)
     * @param tokenToId     map from Kite instrumentToken → canonical instrumentId
     *                      (built by {@link KiteInstrumentsDumpJob#buildTokenMap})
     * @return list of {@link InstrumentRef} in the same order as {@code records}
     *         (minus skipped rows)
     */
    public static List<InstrumentRef> toInstrumentRefs(
            List<KiteInstrumentRecord> records,
            Map<Long, String> tokenToId) {

        List<InstrumentRef> result = new ArrayList<>(records.size());
        for (KiteInstrumentRecord rec : records) {
            if (rec.expiry() == null) continue;
            if (rec.strike() == null || rec.strike().signum() <= 0) continue;
            String optionType = rec.instrumentType(); // "CE" or "PE"
            if (!"CE".equals(optionType) && !"PE".equals(optionType)) continue;

            String instrumentId = tokenToId.get(rec.instrumentToken());
            if (instrumentId == null) {
                // Fall back to computing the ID directly (same formula)
                instrumentId = computeInstrumentId(rec);
            }

            ExpiryType expiryType = deriveExpiryType(rec.expiry(), rec.name());

            result.add(new InstrumentRef(
                    instrumentId,
                    rec.instrumentToken(),
                    rec.tradingSymbol(),
                    optionType,
                    rec.strike(),
                    rec.expiry(),
                    expiryType,
                    rec.lotSize()
            ));
        }
        return result;
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    /**
     * Computes the canonical instrument ID.
     * Mirrors {@link com.strategysquad.ingestion.bhavcopy.InstrumentResolver#resolveInstrumentId}.
     */
    private static String computeInstrumentId(KiteInstrumentRecord rec) {
        return "INS_"
                + rec.name()
                + "_"
                + rec.expiry().format(DATE_FMT)
                + "_"
                + strikeToken(rec.strike())
                + "_"
                + rec.instrumentType();
    }

    private static String strikeToken(BigDecimal strike) {
        return strike.stripTrailingZeros()
                     .toPlainString()
                     .replace('-', 'M')
                     .replace('.', 'P');
    }

    /**
     * Derives the expiry type using the same logic as
     * {@link KiteInstrumentsDumpJob}: BankNifty is always MONTHLY; Nifty is
     * MONTHLY when the expiry is the last Thursday of the month, WEEKLY otherwise.
     */
    private static ExpiryType deriveExpiryType(LocalDate expiryDate, String underlying) {
        if ("BANKNIFTY".equals(underlying)) return ExpiryType.MONTHLY;
        LocalDate lastDay = expiryDate.withDayOfMonth(expiryDate.lengthOfMonth());
        while (lastDay.getDayOfWeek() != DayOfWeek.THURSDAY) {
            lastDay = lastDay.minusDays(1);
        }
        return expiryDate.equals(lastDay) ? ExpiryType.MONTHLY : ExpiryType.WEEKLY;
    }
}
