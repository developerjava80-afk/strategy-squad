package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BhavcopyArchiveDownloaderTest {

    @Test
    void downloadsZipExtractsCsvAndReturnsStructuredPaths(@TempDir Path tempDir) throws Exception {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);
        BhavcopyArchiveUrlResolver urlResolver = new BhavcopyArchiveUrlResolver();
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                urlResolver,
                (archiveUri, targetFile) -> {
                    writeZip(targetFile, "fo01JUL2024bhav.csv", "A,B\n1,2\n");
                    return 200;
                },
                new BhavcopyZipExtractor()
        );

        BhavcopyArchiveDownloader.DownloadResult result = downloader.download(tradeDate);

        assertEquals(tradeDate, result.tradeDate());
        assertEquals(urlResolver.resolve(tradeDate), result.archiveUri());
        assertEquals(tempDir.resolve("derivatives/2024/07/01"), result.storageDirectory());
        assertEquals(result.storageDirectory().resolve("fo01JUL2024bhav.csv.zip"), result.zipFile());
        assertEquals(result.storageDirectory().resolve("fo01JUL2024bhav.csv"), result.csvFile());
        assertTrue(Files.exists(result.zipFile()));
        assertEquals("A,B\n1,2\n", Files.readString(result.csvFile()));
    }

    @Test
    void rejectsFutureTradeDate(@TempDir Path tempDir) {
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyArchiveUrlResolver(),
                (archiveUri, targetFile) -> 200,
                new BhavcopyZipExtractor()
        );

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> downloader.download(LocalDate.now().plusDays(1))
        );

        assertEquals(BhavcopyArchiveException.Reason.INVALID_DATE, ex.reason());
        assertEquals("tradeDate must not be in the future", ex.getMessage());
    }

    @Test
    void reportsMissingArchiveExplicitly(@TempDir Path tempDir) {
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyArchiveUrlResolver(),
                (archiveUri, targetFile) -> 404,
                new BhavcopyZipExtractor()
        );

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> downloader.download(LocalDate.of(2024, 7, 1))
        );

        assertEquals(BhavcopyArchiveException.Reason.ARCHIVE_NOT_FOUND, ex.reason());
        assertFalse(Files.exists(tempDir.resolve("derivatives/2024/07/01/fo01JUL2024bhav.csv.zip")));
    }

    @Test
    void reportsExtractionFailureExplicitly(@TempDir Path tempDir) {
        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader(
                tempDir,
                new BhavcopyArchiveUrlResolver(),
                (archiveUri, targetFile) -> {
                    Files.writeString(targetFile, "not-a-zip");
                    return 200;
                },
                new BhavcopyZipExtractor()
        );

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> downloader.download(LocalDate.of(2024, 7, 1))
        );

        assertEquals(BhavcopyArchiveException.Reason.EXTRACTION_FAILED, ex.reason());
        assertTrue(Files.exists(tempDir.resolve("derivatives/2024/07/01/fo01JUL2024bhav.csv.zip")));
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
