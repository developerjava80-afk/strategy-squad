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

    private final SpotBhavcopyFilter filter = new SpotBhavcopyFilter();

    public SpotBhavcopyRecord normalize(BhavcopyCsvReader.CsvRow row) {
        String underlying = required(filter.resolveUnderlying(row), "underlying");
        SpotSource source = filter.classify(row);
        if (source == null) {
            throw new IllegalArgumentException("row is not a supported historical spot record");
        }

        LocalDate tradeDate = parseDate(required(resolveColumn(row, "TIMESTAMP", "TRADDT", "INDEX DATE"), "trade date"), "trade date");
        BigDecimal open = parseDecimal(required(resolveColumn(row, "OPEN", "OPNPRIC", "OPEN INDEX VALUE"), "open"), "open");
        BigDecimal high = parseDecimal(required(resolveColumn(row, "HIGH", "HGHPRIC", "HIGH INDEX VALUE"), "high"), "high");
        BigDecimal low = parseDecimal(required(resolveColumn(row, "LOW", "LWPRIC", "LOW INDEX VALUE"), "low"), "low");
        BigDecimal close = parseDecimal(required(resolveColumn(row, "CLOSE", "CLSPRIC", "CLOSING INDEX VALUE", "CLOSE INDEX VALUE"), "close"), "close");
        LocalDate expiryDate = source == SpotSource.DERIVATIVE_PROXY
                ? parseOptionalDate(row, "EXPIRY_DT", "XPRYDT")
                : null;

        return new SpotBhavcopyRecord(
                row.lineNumber(),
                underlying,
                tradeDate,
                open,
                high,
                low,
                close,
                source,
                expiryDate
        );
    }

    private static String resolveColumn(BhavcopyCsvReader.CsvRow row, String... names) {
        for (String name : names) {
            String value = row.column(name);
            if (value != null && !value.trim().isEmpty() && !MISSING_VALUE_MARKER.equals(value.trim())) {
                return value;
            }
        }
        return null;
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

    private LocalDate parseOptionalDate(BhavcopyCsvReader.CsvRow row, String primary, String fallback) {
        String value = resolveColumn(row, primary, fallback);
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
