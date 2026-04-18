package com.strategysquad.ingestion.bhavcopy;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves the NSE historical derivatives Bhavcopy archive URL for a trade date.
 */
public class BhavcopyArchiveUrlResolver {
    private static final String ARCHIVE_BASE = "https://nsearchives.nseindia.com/content/historical/DERIVATIVES";
    private static final DateTimeFormatter MONTH_DIRECTORY = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
    private static final DateTimeFormatter ZIP_FILE = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);

    public URI resolve(LocalDate tradeDate) {
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        return URI.create(ARCHIVE_BASE
                + "/" + tradeDate.getYear()
                + "/" + tradeDate.format(MONTH_DIRECTORY).toUpperCase(Locale.ENGLISH)
                + "/" + zipFileName(tradeDate));
    }

    public String zipFileName(LocalDate tradeDate) {
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        return "fo" + tradeDate.format(ZIP_FILE).toUpperCase(Locale.ENGLISH) + "bhav.csv.zip";
    }
}
