package com.strategysquad.ingestion.bhavcopy;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final BigDecimal LAKHS_DIVISOR = new BigDecimal("100000");

    public BhavcopyRecord normalize(BhavcopyCsvReader.CsvRow row) {
        String underlying = required(resolveColumn(row, "SYMBOL", "TCKRSYMB"), "SYMBOL");
        LocalDate expiryDate = parseDate(required(resolveColumn(row, "EXPIRY_DT", "XPRYDT"), "EXPIRY_DT"), "EXPIRY_DT");
        BigDecimal strike = parseDecimal(required(resolveColumn(row, "STRIKE_PR", "STRKPRIC"), "STRIKE_PR"), "STRIKE_PR");
        String optionType = required(resolveColumn(row, "OPTION_TYP", "OPTNTP"), "OPTION_TYP");
        LocalDate tradeDate = parseDate(required(resolveColumn(row, "TIMESTAMP", "TRADDT"), "TIMESTAMP"), "TIMESTAMP");
        BigDecimal open = parseDecimal(required(resolveColumn(row, "OPEN", "OPNPRIC"), "OPEN"), "OPEN");
        BigDecimal high = parseDecimal(required(resolveColumn(row, "HIGH", "HGHPRIC"), "HIGH"), "HIGH");
        BigDecimal low = parseDecimal(required(resolveColumn(row, "LOW", "LWPRIC"), "LOW"), "LOW");
        BigDecimal close = parseDecimal(required(resolveColumn(row, "CLOSE", "CLSPRIC"), "CLOSE"), "CLOSE");
        BigDecimal settlePrice = parseDecimal(required(resolveColumn(row, "SETTLE_PR", "STTLMPRIC"), "SETTLE_PR"), "SETTLE_PR");
        long contracts = parseLong(required(resolveColumn(row, "CONTRACTS", "TTLTRADGVOL"), "CONTRACTS"), "CONTRACTS");
        BigDecimal valueInLakhs = resolveValueInLakhs(row);
        long openInterest = parseLong(required(resolveColumn(row, "OPEN_INT", "OPNINTRST"), "OPEN_INT"), "OPEN_INT");
        long changeInOi = parseLong(required(resolveColumn(row, "CHG_IN_OI", "CHNGINOPNINTRST"), "CHG_IN_OI"), "CHG_IN_OI");

        // UDiFF-specific fields for instrument_master enrichment
        String exchangeToken = optionalColumn(row, "FININSTRMID");
        String tradingSymbol = optionalColumn(row, "FININSTRNM");
        Integer lotSize = optionalInt(row, "NEWBRDLOTQTY");

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
                null,
                exchangeToken,
                tradingSymbol,
                lotSize
        );
    }

    /**
     * Resolves a column value by trying the primary (old) name, then the UDiFF fallback.
     */
    private static String resolveColumn(BhavcopyCsvReader.CsvRow row, String primary, String fallback) {
        String value = row.column(primary);
        if (value != null && !value.trim().isEmpty() && !MISSING_VALUE_MARKER.equals(value.trim())) {
            return value;
        }
        return row.column(fallback);
    }

    /**
     * Old format provides VAL_INLAKH (already in lakhs).
     * UDiFF provides TTLTRFVAL in rupees — divide by 100,000 to convert to lakhs.
     */
    private BigDecimal resolveValueInLakhs(BhavcopyCsvReader.CsvRow row) {
        String valInLakh = row.column("VAL_INLAKH");
        if (valInLakh != null && !valInLakh.trim().isEmpty() && !MISSING_VALUE_MARKER.equals(valInLakh.trim())) {
            return parseDecimal(valInLakh.trim(), "VAL_INLAKH");
        }
        String ttlTrfVal = required(row.column("TTLTRFVAL"), "TTLTRFVAL");
        return parseDecimal(ttlTrfVal, "TTLTRFVAL")
                .divide(LAKHS_DIVISOR, 2, RoundingMode.HALF_UP);
    }

    private String required(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is missing");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || MISSING_VALUE_MARKER.equals(trimmed)) {
            throw new IllegalArgumentException(fieldName + " is missing");
        }
        return trimmed;
    }

    private String optionalColumn(BhavcopyCsvReader.CsvRow row, String name) {
        String value = row.column(name);
        if (value == null || value.isBlank() || MISSING_VALUE_MARKER.equals(value.trim())) {
            return null;
        }
        return value.trim();
    }

    private Integer optionalInt(BhavcopyCsvReader.CsvRow row, String name) {
        String value = optionalColumn(row, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
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
