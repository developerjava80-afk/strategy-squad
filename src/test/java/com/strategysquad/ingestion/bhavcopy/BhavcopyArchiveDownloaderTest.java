package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BhavcopyArchiveDownloaderTest {

    @Test
    void downloadsZipExtractsCsvAndReturnsStructuredPaths(@TempDir Path tempDir) throws Exception {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);
        BhavcopyReport report = BhavcopyReport.fromLink(
                tradeDate,
                "FNO Bhavcopy ZIP",
                URI.create("https://downloads.example.com/fo01JUL2024bhav.csv.zip")
        );
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyReportDiscoverer(ignored -> """
                        <a href="https://downloads.example.com/fo01JUL2024bhav.csv.zip">FNO Bhavcopy ZIP</a>
                        """),
                new BhavcopyReportSelector(),
                (archiveUri, targetFile) -> {
                    writeZip(targetFile, "fo01JUL2024bhav.csv", "A,B\n1,2\n");
                    return 200;
                },
                new BhavcopyZipExtractor(),
                passthroughFilter()
        );

        BhavcopyArchiveDownloader.DownloadResult result = downloader.download(tradeDate);

        assertEquals(tradeDate, result.tradeDate());
        assertEquals(report.downloadUri(), result.archiveUri());
        assertEquals(tempDir, result.storageDirectory());
        assertEquals(result.storageDirectory().resolve("fo01JUL2024bhav.csv.zip"), result.zipFile());
        assertEquals(result.storageDirectory().resolve("fo01JUL2024bhav.csv"), result.csvFile());
        assertEquals(report, result.report());
        assertTrue(Files.exists(result.zipFile()));
        assertEquals("A,B\n1,2\n", Files.readString(result.csvFile()));
    }

    @Test
    void rejectsFutureTradeDate(@TempDir Path tempDir) {
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyReportDiscoverer(ignored -> ""),
                new BhavcopyReportSelector(),
                (archiveUri, targetFile) -> 200,
                new BhavcopyZipExtractor(),
                passthroughFilter()
        );

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> downloader.download(LocalDate.now().plusDays(1))
        );

        assertEquals(BhavcopyArchiveException.Reason.INVALID_DATE, ex.reason());
        assertEquals("tradeDate must not be in the future", ex.getMessage());
    }

    @Test
    void reportsMissingFnoBhavcopyExplicitly(@TempDir Path tempDir) {
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyReportDiscoverer(ignored -> "<a href=\"/reports/other.csv\">Other report</a>"),
                new BhavcopyReportSelector(),
                (archiveUri, targetFile) -> 200,
                new BhavcopyZipExtractor(),
                passthroughFilter()
        );

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> downloader.download(LocalDate.of(2024, 7, 1))
        );

        assertEquals(BhavcopyArchiveException.Reason.FNO_BHAVCOPY_REPORT_NOT_FOUND, ex.reason());
    }

    @Test
    void reportsExtractionFailureExplicitly(@TempDir Path tempDir) {
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyReportDiscoverer(ignored -> """
                        <a href="https://downloads.example.com/fo01JUL2024bhav.csv.zip">FNO Bhavcopy ZIP</a>
                        """),
                new BhavcopyReportSelector(),
                (archiveUri, targetFile) -> {
                    Files.writeString(targetFile, "not-a-zip");
                    return 200;
                },
                new BhavcopyZipExtractor(),
                passthroughFilter()
        );

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> downloader.download(LocalDate.of(2024, 7, 1))
        );

        assertEquals(BhavcopyArchiveException.Reason.EXTRACTION_FAILED, ex.reason());
        assertTrue(Files.exists(tempDir.resolve("fo01JUL2024bhav.csv.zip")));
    }

    @Test
    void reportsAmbiguousMatchesExplicitly(@TempDir Path tempDir) {
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyReportDiscoverer(ignored -> """
                        <a href="https://downloads.example.com/fo01JUL2024bhav.csv.zip">FNO Bhavcopy ZIP</a>
                        <a href="https://downloads.example.com/fo01JUL2024bhav-copy.csv.zip">FNO Bhavcopy ZIP mirror</a>
                        """),
                new BhavcopyReportSelector(),
                (archiveUri, targetFile) -> 200,
                new BhavcopyZipExtractor(),
                passthroughFilter()
        );

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> downloader.download(LocalDate.of(2024, 7, 1))
        );

        assertEquals(BhavcopyArchiveException.Reason.AMBIGUOUS_REPORT_MATCH, ex.reason());
    }

    @Test
    void downloadsWeekdaysOnlyAcrossDateRange(@TempDir Path tempDir) throws Exception {
        class TrackingDownloader extends BhavcopyArchiveDownloader {
            private final List<LocalDate> downloadedDates = new java.util.ArrayList<>();

            private TrackingDownloader() {
                super(
                        tempDir,
                        new BhavcopyReportDiscoverer(ignored -> """
                                <a href="https://downloads.example.com/fo01JUL2024bhav.csv.zip">FNO Bhavcopy ZIP</a>
                                """),
                        new BhavcopyReportSelector(),
                        (archiveUri, targetFile) -> {
                            writeZip(targetFile, "sample.csv", "INSTRUMENT,SYMBOL,OPTION_TYP\nOPTIDX,NIFTY,CE\n");
                            return 200;
                        },
                        new BhavcopyZipExtractor(),
                        passthroughFilter()
                );
            }

            @Override
            public DownloadResult download(LocalDate tradeDate) throws BhavcopyArchiveException {
                downloadedDates.add(tradeDate);
                return super.download(tradeDate);
            }
        }

        TrackingDownloader downloader = new TrackingDownloader();
        List<BhavcopyArchiveDownloader.DownloadResult> results = downloader.downloadRange("16/04/2026", "20/04/2026");

        assertEquals(List.of(
                LocalDate.of(2026, 4, 16),
                LocalDate.of(2026, 4, 17),
                LocalDate.of(2026, 4, 20)
        ), results.stream().map(BhavcopyArchiveDownloader.DownloadResult::tradeDate).toList());
    }

    @Test
    void rejectsStartDateAfterEndDate(@TempDir Path tempDir) {
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyReportDiscoverer(ignored -> ""),
                new BhavcopyReportSelector(),
                (archiveUri, targetFile) -> 200,
                new BhavcopyZipExtractor(),
                passthroughFilter()
        );

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> downloader.downloadRange("20/04/2026", "16/04/2026")
        );

        assertEquals(BhavcopyArchiveException.Reason.INVALID_DATE, ex.reason());
        assertEquals("startDate must not be after endDate", ex.getMessage());
    }

    private static BhavcopyRelevantCsvFilter passthroughFilter() {
        return new BhavcopyRelevantCsvFilter() {
            @Override
            public FilterResult filterInPlace(Path csvFile) {
                return new FilterResult(0, 0);
            }
        };
    }

    private static void writeZip(Path zipFile, String entryName, String content) throws IOException {
        Files.createDirectories(zipFile.getParent());
        try (OutputStream outputStream = Files.newOutputStream(zipFile);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(entryName));
            zipOutputStream.write(content.getBytes());
            zipOutputStream.closeEntry();
        }
    }
}
