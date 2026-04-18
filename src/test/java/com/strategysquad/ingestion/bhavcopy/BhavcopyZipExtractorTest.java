package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BhavcopyZipExtractorTest {

    private final BhavcopyZipExtractor extractor = new BhavcopyZipExtractor();

    @Test
    void extractsCsvFromZip(@TempDir Path tempDir) throws IOException {
        Path zipFile = tempDir.resolve("sample.zip");
        writeZip(zipFile, "fo01JUL2024bhav.csv", "header\nvalue\n");

        Path csvFile = extractor.extractCsv(zipFile, tempDir.resolve("out"));

        assertEquals("header\nvalue\n", Files.readString(csvFile));
        assertEquals("fo01JUL2024bhav.csv", csvFile.getFileName().toString());
    }

    @Test
    void rejectsZipWithoutCsv(@TempDir Path tempDir) throws IOException {
        Path zipFile = tempDir.resolve("sample.zip");
        writeZip(zipFile, "notes.txt", "missing csv");

        IOException ex = assertThrows(IOException.class, () -> extractor.extractCsv(zipFile, tempDir.resolve("out")));

        assertEquals("ZIP archive does not contain a CSV file", ex.getMessage());
    }

    private static void writeZip(Path zipFile, String entryName, String content) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(zipFile);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(entryName));
            zipOutputStream.write(content.getBytes());
            zipOutputStream.closeEntry();
        }
    }
}
