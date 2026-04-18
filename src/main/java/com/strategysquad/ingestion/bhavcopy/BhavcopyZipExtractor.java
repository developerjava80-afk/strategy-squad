package com.strategysquad.ingestion.bhavcopy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts CSV content from a downloaded Bhavcopy ZIP archive.
 */
public class BhavcopyZipExtractor {

    public Path extractCsv(Path zipFile, Path outputDirectory) throws IOException {
        Objects.requireNonNull(zipFile, "zipFile must not be null");
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Files.createDirectories(outputDirectory);

        Path extractedCsv = null;
        try (InputStream fileInputStream = Files.newInputStream(zipFile);
             ZipInputStream zipInputStream = new ZipInputStream(fileInputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }
                String entryName = Path.of(entry.getName()).getFileName().toString();
                if (!entryName.toLowerCase(Locale.ENGLISH).endsWith(".csv")) {
                    zipInputStream.closeEntry();
                    continue;
                }
                if (extractedCsv != null) {
                    throw new IOException("ZIP archive contains multiple CSV files");
                }
                extractedCsv = outputDirectory.resolve(entryName).normalize();
                if (!extractedCsv.startsWith(outputDirectory.normalize())) {
                    throw new IOException("ZIP entry resolves outside output directory");
                }
                Files.copy(zipInputStream, extractedCsv, StandardCopyOption.REPLACE_EXISTING);
                zipInputStream.closeEntry();
            }
        }

        if (extractedCsv == null) {
            throw new IOException("ZIP archive does not contain a CSV file");
        }
        return extractedCsv;
    }
}
