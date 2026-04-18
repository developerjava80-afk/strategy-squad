package com.strategysquad.ingestion.bhavcopy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
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

    private static final Pattern ANCHOR_TAG_PATTERN = Pattern.compile(
            "<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern RAW_LINK_PATTERN = Pattern.compile(
            "(https?://[^\"'\\s>]+\\.(?:zip|csv)(?:\\?[^\"'\\s>]*)?)|((?:/|\\.\\.?/)[^\"'\\s>]+\\.(?:zip|csv)(?:\\?[^\"'\\s>]*)?)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

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
            return parseReports(tradeDate, payload, REPORTS_SOURCE_URI);
        } catch (IOException ex) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.REPORTS_DISCOVERY_FAILED,
                    tradeDate,
                    REPORTS_SOURCE_URI,
                    "Bhavcopy reports discovery failed for trade date " + tradeDate,
                    ex
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.REPORTS_DISCOVERY_FAILED,
                    tradeDate,
                    REPORTS_SOURCE_URI,
                    "Bhavcopy reports discovery interrupted for trade date " + tradeDate,
                    ex
            );
        }
    }

    List<BhavcopyReport> parseReports(LocalDate tradeDate, String payload, URI baseUri) {
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(baseUri, "baseUri must not be null");

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
        URI downloadUri = baseUri.resolve(href.trim());
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
            HttpRequest request = HttpRequest.newBuilder(REPORTS_SOURCE_URI)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.nseindia.com/")
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
