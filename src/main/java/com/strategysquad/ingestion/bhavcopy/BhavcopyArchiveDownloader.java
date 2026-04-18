package com.strategysquad.ingestion.bhavcopy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Downloads and extracts a single historical NSE derivatives Bhavcopy archive.
 */
public class BhavcopyArchiveDownloader {
    private static final Path DEFAULT_STORAGE_ROOT = Path.of("data", "bhavcopy", "historical", "derivatives");
    private static final DateTimeFormatter USER_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter ARCHIVE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final URI DIRECT_ARCHIVE_BASE_URI = URI.create("https://nsearchives.nseindia.com/content/fo/");

    private final Path storageRoot;
    private final BhavcopyReportDiscoverer reportDiscoverer;
    private final BhavcopyReportSelector reportSelector;
    private final ArchiveHttpClient httpClient;
    private final BhavcopyZipExtractor zipExtractor;
    private final BhavcopyRelevantCsvFilter csvFilter;

    public BhavcopyArchiveDownloader() {
        this(DEFAULT_STORAGE_ROOT);
    }

    public BhavcopyArchiveDownloader(Path storageRoot) {
        this(
                storageRoot,
                new BhavcopyReportDiscoverer(),
                new BhavcopyReportSelector(),
                new DefaultArchiveHttpClient(),
                new BhavcopyZipExtractor(),
                new BhavcopyRelevantCsvFilter()
        );
    }

    BhavcopyArchiveDownloader(
            Path storageRoot,
            BhavcopyReportDiscoverer reportDiscoverer,
            BhavcopyReportSelector reportSelector,
            ArchiveHttpClient httpClient,
            BhavcopyZipExtractor zipExtractor
    ) {
        this(storageRoot, reportDiscoverer, reportSelector, httpClient, zipExtractor, new BhavcopyRelevantCsvFilter());
    }

    BhavcopyArchiveDownloader(
            Path storageRoot,
            BhavcopyReportDiscoverer reportDiscoverer,
            BhavcopyReportSelector reportSelector,
            ArchiveHttpClient httpClient,
            BhavcopyZipExtractor zipExtractor,
            BhavcopyRelevantCsvFilter csvFilter
    ) {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot must not be null");
        this.reportDiscoverer = Objects.requireNonNull(reportDiscoverer, "reportDiscoverer must not be null");
        this.reportSelector = Objects.requireNonNull(reportSelector, "reportSelector must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.zipExtractor = Objects.requireNonNull(zipExtractor, "zipExtractor must not be null");
        this.csvFilter = Objects.requireNonNull(csvFilter, "csvFilter must not be null");
    }

    public DownloadResult download(LocalDate tradeDate) throws BhavcopyArchiveException {
        validateTradeDate(tradeDate);
        BhavcopyReport selectedReport = resolveReport(tradeDate);
        URI archiveUri = selectedReport.downloadUri();
        Path storageDirectory = storageDirectory();
        Path zipFile = storageDirectory.resolve(selectedReport.fileName());

        try {
            Files.createDirectories(storageDirectory);
            int statusCode = httpClient.download(archiveUri, zipFile);
            if (statusCode < 200 || statusCode >= 300) {
                throw new BhavcopyArchiveException(
                        BhavcopyArchiveException.Reason.DOWNLOAD_FAILED,
                        tradeDate,
                        archiveUri,
                        "Bhavcopy download failed with HTTP status " + statusCode
                );
            }

            Path csvFile;
            try {
                csvFile = zipExtractor.extractCsv(zipFile, storageDirectory);
                csvFilter.filterInPlace(csvFile);
            } catch (IOException ex) {
                throw new BhavcopyArchiveException(
                        BhavcopyArchiveException.Reason.EXTRACTION_FAILED,
                        tradeDate,
                        archiveUri,
                        "Bhavcopy ZIP extraction failed for trade date " + tradeDate,
                        ex
                );
            }

            return new DownloadResult(tradeDate, archiveUri, storageDirectory, zipFile, csvFile, selectedReport);
        } catch (BhavcopyArchiveException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.DOWNLOAD_FAILED,
                    tradeDate,
                    archiveUri,
                    "Bhavcopy download failed for trade date " + tradeDate,
                    ex
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.DOWNLOAD_FAILED,
                    tradeDate,
                    archiveUri,
                    "Bhavcopy download interrupted for trade date " + tradeDate,
                    ex
            );
        }
    }

    private BhavcopyReport resolveReport(LocalDate tradeDate) throws BhavcopyArchiveException {
        try {
            return reportSelector.selectFnoBhavcopyZip(tradeDate, reportDiscoverer.discover(tradeDate));
        } catch (BhavcopyArchiveException ex) {
            if (ex.reason() != BhavcopyArchiveException.Reason.FNO_BHAVCOPY_REPORT_NOT_FOUND) {
                throw ex;
            }
            return directArchiveReport(tradeDate);
        }
    }

    private BhavcopyReport directArchiveReport(LocalDate tradeDate) {
        String fileName = "BhavCopy_NSE_FO_0_0_0_" + tradeDate.format(ARCHIVE_DATE_FORMATTER) + "_F_0000.csv.zip";
        return BhavcopyReport.fromLink(
                tradeDate,
                "F&O-UDiFF Common Bhavcopy Final (zip)",
                DIRECT_ARCHIVE_BASE_URI.resolve(fileName)
        );
    }

    public List<DownloadResult> downloadRange(String startDate, String endDate) throws BhavcopyArchiveException {
        return downloadRange(parseUserDate(startDate), parseUserDate(endDate));
    }

    public List<DownloadResult> downloadRange(LocalDate startDate, LocalDate endDate) throws BhavcopyArchiveException {
        validateDateRange(startDate, endDate);
        List<DownloadResult> results = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (isWeekend(date)) {
                continue;
            }
            try {
                results.add(download(date));
            } catch (BhavcopyArchiveException ex) {
                if (shouldSkipDate(ex)) {
                    continue;
                }
                throw ex;
            }
        }
        return List.copyOf(results);
    }

    private void validateTradeDate(LocalDate tradeDate) throws BhavcopyArchiveException {
        if (tradeDate == null) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.INVALID_DATE,
                    null,
                    null,
                    "tradeDate must not be null"
            );
        }
        if (tradeDate.isAfter(LocalDate.now())) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.INVALID_DATE,
                    tradeDate,
                    null,
                    "tradeDate must not be in the future"
            );
        }
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) throws BhavcopyArchiveException {
        validateTradeDate(startDate);
        validateTradeDate(endDate);
        if (startDate.isAfter(endDate)) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.INVALID_DATE,
                    startDate,
                    null,
                    "startDate must not be after endDate"
            );
        }
    }

    private LocalDate parseUserDate(String value) throws BhavcopyArchiveException {
        if (value == null || value.isBlank()) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.INVALID_DATE,
                    null,
                    null,
                    "date must be in dd/MM/yyyy format"
            );
        }
        try {
            return LocalDate.parse(value.trim(), USER_DATE_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.INVALID_DATE,
                    null,
                    null,
                    "date must be in dd/MM/yyyy format",
                    ex
            );
        }
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }

    private boolean shouldSkipDate(BhavcopyArchiveException ex) {
        return ex.reason() == BhavcopyArchiveException.Reason.FNO_BHAVCOPY_REPORT_NOT_FOUND
                || (ex.reason() == BhavcopyArchiveException.Reason.DOWNLOAD_FAILED
                && ex.getMessage() != null
                && ex.getMessage().contains("HTTP status 404"));
    }

    private Path storageDirectory() {
        return storageRoot;
    }

    public record DownloadResult(
            LocalDate tradeDate,
            URI archiveUri,
            Path storageDirectory,
            Path zipFile,
            Path csvFile,
            BhavcopyReport report
    ) {
        public DownloadResult {
            Objects.requireNonNull(tradeDate, "tradeDate must not be null");
            Objects.requireNonNull(archiveUri, "archiveUri must not be null");
            Objects.requireNonNull(storageDirectory, "storageDirectory must not be null");
            Objects.requireNonNull(zipFile, "zipFile must not be null");
            Objects.requireNonNull(csvFile, "csvFile must not be null");
            Objects.requireNonNull(report, "report must not be null");
        }
    }

    interface ArchiveHttpClient {
        int download(URI archiveUri, Path targetFile) throws IOException, InterruptedException;
    }

    static final class DefaultArchiveHttpClient implements ArchiveHttpClient {
        private final HttpClient httpClient;

        private DefaultArchiveHttpClient() {
            this(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build());
        }

        private DefaultArchiveHttpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public int download(URI archiveUri, Path targetFile) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(archiveUri)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/csv,application/zip,application/octet-stream,*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.nseindia.com/")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Files.copy(body, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return response.statusCode();
            }
        }
    }
}
