package com.strategysquad.ingestion.bhavcopy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads Bhavcopy CSV files into row maps.
 */
public class BhavcopyCsvReader {

    public ReadResult read(Path csvFile) throws IOException {
        Objects.requireNonNull(csvFile, "csvFile must not be null");
        List<CsvRow> rows = new ArrayList<>();
        List<InvalidRow> invalidRows = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new ReadResult(List.of(), List.of(), 0);
            }
            List<String> headers = splitCsvLine(headerLine).stream()
                    .map(this::normalizeHeader)
                    .toList();
            if (headers.isEmpty()) {
                return new ReadResult(List.of(), List.of(), 0);
            }

            String line;
            long lineNumber = 1;
            int totalDataRows = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                totalDataRows++;
                List<String> values = splitCsvLine(line);
                if (values.size() != headers.size()) {
                    invalidRows.add(new InvalidRow(
                            lineNumber,
                            "column count mismatch: expected " + headers.size() + " but found " + values.size(),
                            line
                    ));
                    continue;
                }

                Map<String, String> columns = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    columns.put(headers.get(i), values.get(i).trim());
                }
                rows.add(new CsvRow(lineNumber, line, columns));
            }
            return new ReadResult(rows, invalidRows, totalDataRows);
        }
    }

    private String normalizeHeader(String header) {
        return header.trim().toUpperCase();
    }

    private List<String> splitCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (c == ',' && !inQuotes) {
                tokens.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        tokens.add(current.toString());
        return tokens;
    }

    public record CsvRow(long lineNumber, String rawLine, Map<String, String> columns) {
        public CsvRow {
            Objects.requireNonNull(rawLine, "rawLine must not be null");
            Objects.requireNonNull(columns, "columns must not be null");
        }

        /**
         * Returns the column value for the provided header name, or an empty string if absent.
         */
        public String column(String name) {
            String normalized = name.trim().toUpperCase();
            return columns.getOrDefault(normalized, "");
        }
    }

    public record ReadResult(
            List<CsvRow> rows,
            List<InvalidRow> invalidRows,
            int totalDataRows
    ) {
        public ReadResult {
            Objects.requireNonNull(rows, "rows must not be null");
            Objects.requireNonNull(invalidRows, "invalidRows must not be null");
        }
    }
}
