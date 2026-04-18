package com.strategysquad.ingestion.bhavcopy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Filters extracted bhavcopy CSV files down to NIFTY/BANKNIFTY-relevant rows.
 *
 * <p>The filtered file keeps option rows used by historical ingestion and the
 * matching FUTIDX rows used as the spot proxy for those same underlyings.
 */
public class BhavcopyRelevantCsvFilter {
    private final BhavcopyFilter optionFilter;
    private final SpotBhavcopyFilter spotFilter;

    public BhavcopyRelevantCsvFilter() {
        this(new BhavcopyFilter(), new SpotBhavcopyFilter());
    }

    BhavcopyRelevantCsvFilter(BhavcopyFilter optionFilter, SpotBhavcopyFilter spotFilter) {
        this.optionFilter = Objects.requireNonNull(optionFilter, "optionFilter must not be null");
        this.spotFilter = Objects.requireNonNull(spotFilter, "spotFilter must not be null");
    }

    public FilterResult filterInPlace(Path csvFile) throws IOException {
        Objects.requireNonNull(csvFile, "csvFile must not be null");

        Path tempFile = csvFile.resolveSibling(csvFile.getFileName() + ".tmp");
        int totalRows = 0;
        int keptRows = 0;

        try (BufferedReader reader = Files.newBufferedReader(csvFile);
             BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                Files.move(tempFile, csvFile, StandardCopyOption.REPLACE_EXISTING);
                return new FilterResult(0, 0);
            }

            writer.write(headerLine);
            writer.newLine();
            List<String> headers = splitCsvLine(headerLine).stream()
                    .map(this::normalizeHeader)
                    .toList();

            String line;
            long lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                totalRows++;
                BhavcopyCsvReader.CsvRow row = toRow(lineNumber, line, headers);
                if (row != null && isRelevant(row)) {
                    writer.write(line);
                    writer.newLine();
                    keptRows++;
                }
            }
        } catch (IOException ex) {
            Files.deleteIfExists(tempFile);
            throw ex;
        }

        Files.move(tempFile, csvFile, StandardCopyOption.REPLACE_EXISTING);
        return new FilterResult(totalRows, keptRows);
    }

    private BhavcopyCsvReader.CsvRow toRow(long lineNumber, String line, List<String> headers) {
        List<String> values = splitCsvLine(line);
        if (values.size() != headers.size()) {
            return null;
        }
        Map<String, String> columns = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            columns.put(headers.get(i), values.get(i).trim());
        }
        return new BhavcopyCsvReader.CsvRow(lineNumber, line, columns);
    }

    private boolean isRelevant(BhavcopyCsvReader.CsvRow row) {
        if (optionFilter.isRelevant(row) || spotFilter.isRelevant(row)) {
            return true;
        }
        return isRelevantUdiffRow(row);
    }

    private boolean isRelevantUdiffRow(BhavcopyCsvReader.CsvRow row) {
        String symbol = normalize(row.column("TCKRSYMB"));
        if (!"NIFTY".equals(symbol) && !"BANKNIFTY".equals(symbol)) {
            return false;
        }

        String instrumentType = normalize(row.column("FININSTRMTP"));
        String optionType = normalize(row.column("OPTNTP"));
        boolean isOption = "IDO".equals(instrumentType) && ("CE".equals(optionType) || "PE".equals(optionType));
        boolean isFuture = "IDF".equals(instrumentType);
        return isOption || isFuture;
    }

    private String normalizeHeader(String header) {
        return header.trim().toUpperCase();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
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

    public record FilterResult(int totalRows, int keptRows) {
    }
}
