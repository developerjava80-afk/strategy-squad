package com.strategysquad.ingestion.kite;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Filters the full Kite NFO instruments dump to the set Strategy Squad subscribes to.
 *
 * <p>Expiry selection is derived from the live dump itself rather than hard-coded weekday
 * assumptions, so the live option universe stays valid even when exchange expiry conventions
 * change.
 */
public final class KiteInstrumentFilter {

    private KiteInstrumentFilter() {
    }

    /**
     * @deprecated No longer called. Phase 6 removed the two-phase ATM login; the scope-first
     * design uses {@link #filter} (full window) which is written to {@code instrument_master},
     * and instruments are subscribed only when the user activates a scope via
     * {@code POST /api/scope}. This method is retained only to avoid breaking any
     * test that may still reference it directly.
     *
     * @param niftyStrikeStep    typical strike increment for NIFTY (e.g. 50 or 100)
     * @param bankNiftyStrikeStep typical strike increment for BANKNIFTY (e.g. 100)
     */
    public static List<KiteInstrumentRecord> atmOnly(
            List<KiteInstrumentRecord> all,
            LocalDate referenceDate,
            double niftyAtmEstimate,
            double bankNiftyAtmEstimate,
            double niftyStrikeStep,
            double bankNiftyStrikeStep
    ) {
        // ±3 strikes each side = 7 strikes per expiry per type (CE+PE) = ~28 instruments total
        double niftyWindow = niftyStrikeStep * 3;
        double bankNiftyWindow = bankNiftyStrikeStep * 3;
        return filter(all, referenceDate,
                niftyAtmEstimate, bankNiftyAtmEstimate,
                niftyWindow, bankNiftyWindow,
                false, false); // current weekly only, no next weekly/monthly
    }

    public static List<KiteInstrumentRecord> filter(
            List<KiteInstrumentRecord> all,
            LocalDate referenceDate,
            double niftyAtmEstimate,
            double bankNiftyAtmEstimate,
            double niftyStrikeWindow,
            double bankNiftyStrikeWindow,
            boolean includeNextWeekly,
            boolean includeNextMonthly
    ) {
        ExpirySelection niftySelection = expirySelection(all, "NIFTY", referenceDate);
        ExpirySelection bankNiftySelection = expirySelection(all, "BANKNIFTY", referenceDate);

        List<KiteInstrumentRecord> result = new ArrayList<>();
        for (KiteInstrumentRecord rec : all) {
            if (!rec.isNiftyOrBankNiftyOption()) {
                continue;
            }

            LocalDate expiry = rec.expiry();
            String name = rec.name();
            ExpirySelection selection = "NIFTY".equals(name) ? niftySelection : bankNiftySelection;

            boolean expiryIncluded = expiry.equals(selection.currentWeekly())
                    || (includeNextWeekly && expiry.equals(selection.nextWeekly()))
                    || expiry.equals(selection.currentMonthly())
                    || (includeNextMonthly && expiry.equals(selection.nextMonthly()));
            if (!expiryIncluded) {
                continue;
            }

            double atm = "NIFTY".equals(name) ? niftyAtmEstimate : bankNiftyAtmEstimate;
            double window = "NIFTY".equals(name) ? niftyStrikeWindow : bankNiftyStrikeWindow;
            double strike = rec.strike().doubleValue();
            if (strike < atm - window || strike > atm + window) {
                continue;
            }

            result.add(rec);
        }
        return result;
    }

    static ExpirySelection expirySelection(List<KiteInstrumentRecord> all, String underlying, LocalDate referenceDate) {
        NavigableSet<LocalDate> expiries = new TreeSet<>();
        for (KiteInstrumentRecord record : all) {
            if (underlying.equals(record.name())
                    && record.expiry() != null
                    && !record.expiry().isBefore(referenceDate)) {
                expiries.add(record.expiry());
            }
        }
        if (expiries.isEmpty()) {
            throw new IllegalStateException("No live expiries available for " + underlying + " on or after " + referenceDate);
        }

        List<LocalDate> ordered = expiries.stream().sorted().toList();
        LocalDate currentWeekly = ordered.get(0);
        LocalDate nextWeekly = ordered.size() > 1 ? ordered.get(1) : currentWeekly;
        LocalDate currentMonthly = monthlyExpiryForOrAfter(ordered, referenceDate);
        LocalDate nextMonthly = monthlyExpiryAfter(ordered, currentMonthly);
        return new ExpirySelection(currentWeekly, nextWeekly, currentMonthly, nextMonthly);
    }

    private static LocalDate monthlyExpiryForOrAfter(List<LocalDate> expiries, LocalDate referenceDate) {
        LocalDate first = expiries.stream()
                .filter(expiry -> !expiry.isBefore(referenceDate))
                .findFirst()
                .orElseThrow();
        int month = first.getMonthValue();
        int year = first.getYear();
        return expiries.stream()
                .filter(expiry -> expiry.getYear() == year && expiry.getMonthValue() == month)
                .max(Comparator.naturalOrder())
                .orElse(first);
    }

    private static LocalDate monthlyExpiryAfter(List<LocalDate> expiries, LocalDate currentMonthly) {
        LocalDate later = expiries.stream()
                .filter(expiry -> expiry.isAfter(currentMonthly))
                .findFirst()
                .orElse(currentMonthly);
        if (later.equals(currentMonthly)) {
            return currentMonthly;
        }
        int month = later.getMonthValue();
        int year = later.getYear();
        return expiries.stream()
                .filter(expiry -> expiry.getYear() == year && expiry.getMonthValue() == month)
                .max(Comparator.naturalOrder())
                .orElse(later);
    }

    record ExpirySelection(
            LocalDate currentWeekly,
            LocalDate nextWeekly,
            LocalDate currentMonthly,
            LocalDate nextMonthly
    ) {
    }
}
