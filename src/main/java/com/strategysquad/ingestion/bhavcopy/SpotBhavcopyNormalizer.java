package com.strategysquad.ingestion.bhavcopy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes filtered Bhavcopy rows into spot (index) records.
 */
public class SpotBhavcopyNormalizer {
    private static final String MISSING_VALUE_MARKER = "-";
    private static final DateTimeFormatter BHAVCOPY_DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-uuuu")
            .toFormatter(Locale.ENGLISH);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            BHAVCOPY_DATE_FORMAT,
            DateTimeFormatter.ISO_LOCAL_DATE
    );

    public SpotBhavcopyRecord normalize(BhavcopyCsvReader.CsvRow row) {
        String underlying = required(row, "SYMBOL");
        LocalDate tradeDate = parseDate(required(row, "TIMESTAMP"), "TIMESTAMP");
        BigDecimal open = parseDecimal(required(row, "OPEN"), "OPEN");
        BigDecimal high = parseDecimal(required(row, "HIGH"), "HIGH");
        BigDecimal low = parseDecimal(required(row, "LOW"), "LOW");
        BigDecimal close = parseDecimal(required(row, "CLOSE"), "CLOSE");
        LocalDate expiryDate = parseOptionalDate(row, "EXPIRY_DT");

        return new SpotBhavcopyRecord(
                row.lineNumber(),
                underlying,
                tradeDate,
                open,
                high,
                low,
                close,
                expiryDate
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

    private LocalDate parseOptionalDate(BhavcopyCsvReader.CsvRow row, String column) {
        String value = row.column(column);
        if (value == null || value.isBlank() || MISSING_VALUE_MARKER.equals(value.trim())) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value.trim(), format);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }
}
