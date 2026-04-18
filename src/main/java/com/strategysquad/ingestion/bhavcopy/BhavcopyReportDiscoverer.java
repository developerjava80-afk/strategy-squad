package com.strategysquad.ingestion.bhavcopy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers the reports exposed by the NSE derivatives reports workflow for a trade date.
 */
public class BhavcopyReportDiscoverer {
    static final URI REPORTS_SOURCE_URI = URI.create("https://www.nseindia.com/all-reports-derivatives");
    static final URI REPORTS_API_URI = URI.create("https://www.nseindia.com/api/daily-reports?key=FO");
    private static final DateTimeFormatter REPORT_DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-uuuu")
            .toFormatter(Locale.ENGLISH);

    private static final Pattern ANCHOR_TAG_PATTERN = Pattern.compile(
            "<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern RAW_LINK_PATTERN = Pattern.compile(
            "(https?://[^\"'\\s>]+\\.(?:zip|csv)(?:\\?[^\"'\\s>]*)?)|((?:/|\\.\\.?/)[^\"'\\s>]+\\.(?:zip|csv)(?:\\?[^\"'\\s>]*)?)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern API_REPORT_PATTERN = Pattern.compile(
            "\\{[^{}]*\"displayName\":\"((?:\\\\.|[^\"\\\\])*)\""
                    + "[^{}]*\"fileActlName\":\"((?:\\\\.|[^\"\\\\])*)\""
                    + "[^{}]*\"filePath\":\"((?:\\\\.|[^\"\\\\])*)\""
                    + "[^{}]*\"tradingDate\":\"((?:\\\\.|[^\"\\\\])*)\""
                    + "[^{}]*}",
            Pattern.CASE_INSENSITIVE
    );

    private final ReportListingClient listingClient;

    public BhavcopyReportDiscoverer() {
        this(new DefaultReportListingClient());
    }

    BhavcopyReportDiscoverer(ReportListingClient listingClient) {
        this.listingClient = Objects.requireNonNull(listingClient, "listingClient must not be null");
    }

    public List<BhavcopyReport> discover(LocalDate tradeDate) throws BhavcopyArchiveException {
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        try {
            String payload = listingClient.fetchListing(tradeDate);
            List<BhavcopyReport> reports = parseReports(tradeDate, payload, REPORTS_SOURCE_URI);
            if (!reports.isEmpty()) {
                return reports;
            }
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.FNO_BHAVCOPY_REPORT_NOT_FOUND,
                    tradeDate,
                    REPORTS_API_URI,
                    "FNO Bhavcopy ZIP report not found for trade date " + tradeDate
            );
        } catch (BhavcopyArchiveException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.REPORTS_DISCOVERY_FAILED,
                    tradeDate,
                    REPORTS_API_URI,
                    "Bhavcopy reports discovery failed for trade date " + tradeDate,
                    ex
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.REPORTS_DISCOVERY_FAILED,
                    tradeDate,
                    REPORTS_API_URI,
                    "Bhavcopy reports discovery interrupted for trade date " + tradeDate,
                    ex
            );
        }
    }

    List<BhavcopyReport> parseReports(LocalDate tradeDate, String payload, URI baseUri) {
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(baseUri, "baseUri must not be null");

        String trimmed = payload.trim();
        if (trimmed.startsWith("{")) {
            return parseApiReports(tradeDate, trimmed);
        }

        Map<String, BhavcopyReport> reportsByUri = new LinkedHashMap<>();

        Matcher anchorMatcher = ANCHOR_TAG_PATTERN.matcher(payload);
        while (anchorMatcher.find()) {
            addReport(
                    reportsByUri,
                    tradeDate,
                    baseUri,
                    anchorMatcher.group(1),
                    normalizeAnchorText(anchorMatcher.group(2))
            );
        }

        Matcher rawLinkMatcher = RAW_LINK_PATTERN.matcher(payload);
        while (rawLinkMatcher.find()) {
            String href = rawLinkMatcher.group(1) != null ? rawLinkMatcher.group(1) : rawLinkMatcher.group(2);
            addReport(reportsByUri, tradeDate, baseUri, href, null);
        }

        return List.copyOf(reportsByUri.values());
    }

    private List<BhavcopyReport> parseApiReports(LocalDate tradeDate, String payload) {
        Map<String, BhavcopyReport> reportsByUri = new LinkedHashMap<>();
        Matcher matcher = API_REPORT_PATTERN.matcher(payload);
        while (matcher.find()) {
            LocalDate rowTradeDate = parseTradeDate(unescapeJson(matcher.group(4)));
            if (!tradeDate.equals(rowTradeDate)) {
                continue;
            }
            String filePath = unescapeJson(matcher.group(3));
            String fileName = unescapeJson(matcher.group(2));
            if (filePath.isBlank() || fileName.isBlank()) {
                continue;
            }
            URI downloadUri = URI.create(filePath + fileName);
            String key = downloadUri.toString();
            String reportName = unescapeJson(matcher.group(1));
            reportsByUri.computeIfAbsent(key, ignored -> BhavcopyReport.fromLink(tradeDate, reportName, downloadUri));
        }
        return List.copyOf(reportsByUri.values());
    }

    private void addReport(
            Map<String, BhavcopyReport> reportsByUri,
            LocalDate tradeDate,
            URI baseUri,
            String href,
            String reportName
    ) {
        if (href == null || href.isBlank()) {
            return;
        }
        String normalizedHref = href.trim();
        if (normalizedHref.startsWith("tel:") || normalizedHref.startsWith("mailto:")
                || normalizedHref.startsWith("javascript:")) {
            return;
        }
        URI downloadUri;
        try {
            downloadUri = baseUri.resolve(normalizedHref);
        } catch (IllegalArgumentException ex) {
            return;
        }
        String key = downloadUri.toString();
        reportsByUri.computeIfAbsent(key, ignored -> BhavcopyReport.fromLink(tradeDate, reportName, downloadUri));
    }

    private String normalizeAnchorText(String rawText) {
        if (rawText == null) {
            return null;
        }
        String withoutTags = TAG_PATTERN.matcher(rawText).replaceAll(" ");
        return withoutTags.replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private LocalDate parseTradeDate(String value) {
        try {
            return LocalDate.parse(value, REPORT_DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            return LocalDate.MIN;
        }
    }

    private String unescapeJson(String value) {
        return value
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    interface ReportListingClient {
        String fetchListing(LocalDate tradeDate) throws IOException, InterruptedException;
    }

    static final class DefaultReportListingClient implements ReportListingClient {
        private final HttpClient httpClient;

        private DefaultReportListingClient() {
            this(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build());
        }

        private DefaultReportListingClient(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public String fetchListing(LocalDate tradeDate) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(REPORTS_API_URI)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", REPORTS_SOURCE_URI.toString())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Reports listing request failed with HTTP status " + response.statusCode());
            }
            return response.body();
        }
    }
}
