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
