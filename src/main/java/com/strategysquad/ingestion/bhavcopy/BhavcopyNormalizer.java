package com.strategysquad.ingestion.bhavcopy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes filtered Bhavcopy rows into internal records.
 */
public class BhavcopyNormalizer {
    private static final String MISSING_VALUE_MARKER = "-";
    private static final DateTimeFormatter BHAVCOPY_DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-uuuu")
            .toFormatter(Locale.ENGLISH);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            BHAVCOPY_DATE_FORMAT,
            DateTimeFormatter.ISO_LOCAL_DATE
    );

    public BhavcopyRecord normalize(BhavcopyCsvReader.CsvRow row) {
        String underlying = required(row, "SYMBOL");
        LocalDate expiryDate = parseDate(required(row, "EXPIRY_DT"), "EXPIRY_DT");
        BigDecimal strike = parseDecimal(required(row, "STRIKE_PR"), "STRIKE_PR");
        String optionType = required(row, "OPTION_TYP");
        LocalDate tradeDate = parseDate(required(row, "TIMESTAMP"), "TIMESTAMP");
        BigDecimal open = parseDecimal(required(row, "OPEN"), "OPEN");
        BigDecimal high = parseDecimal(required(row, "HIGH"), "HIGH");
        BigDecimal low = parseDecimal(required(row, "LOW"), "LOW");
        BigDecimal close = parseDecimal(required(row, "CLOSE"), "CLOSE");
        BigDecimal settlePrice = parseDecimal(required(row, "SETTLE_PR"), "SETTLE_PR");
        long contracts = parseLong(required(row, "CONTRACTS"), "CONTRACTS");
        BigDecimal valueInLakhs = parseDecimal(required(row, "VAL_INLAKH"), "VAL_INLAKH");
        long openInterest = parseLong(required(row, "OPEN_INT"), "OPEN_INT");
        long changeInOi = parseLong(required(row, "CHG_IN_OI"), "CHG_IN_OI");

        return new BhavcopyRecord(
                row.lineNumber(),
                underlying,
                expiryDate,
                strike,
                optionType,
                tradeDate,
                open,
                high,
                low,
                close,
                settlePrice,
                contracts,
                valueInLakhs,
                openInterest,
                changeInOi,
                null
        );
    }

    private String required(BhavcopyCsvReader.CsvRow row, String column) {
        String value = row.column(column);
        if (value == null) {
            throw new IllegalArgumentException(column + " is missing");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || MISSING_VALUE_MARKER.equals(trimmed)) {
            throw new IllegalArgumentException(column + " is missing");
        }
        return trimmed;
    }

    private BigDecimal parseDecimal(String value, String fieldName) {
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + " is not a valid decimal: " + value, ex);
        }
    }

    private long parseLong(String value, String fieldName) {
        try {
            return Long.parseLong(value.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + " is not a valid long: " + value, ex);
        }
    }

    private LocalDate parseDate(String value, String fieldName) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        throw new IllegalArgumentException(fieldName + " is not a valid date: " + value);
    }
}
