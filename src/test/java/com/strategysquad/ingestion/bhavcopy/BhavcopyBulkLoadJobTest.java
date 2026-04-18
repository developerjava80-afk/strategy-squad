package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BhavcopyBulkLoadJobTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsMultipleFilesInNameOrder() throws Exception {
        // Create two minimal CSV files (alphabetically: a.csv before b.csv)
        Files.writeString(tempDir.resolve("b_20240418.csv"), "INSTRUMENT,SYMBOL\nOPTIDX,NIFTY");
        Files.writeString(tempDir.resolve("a_20240417.csv"), "INSTRUMENT,SYMBOL\nOPTIDX,NIFTY");

        RecordingIngestionJob recorder = new RecordingIngestionJob();
        BhavcopyBulkLoadJob bulkJob = new BhavcopyBulkLoadJob(recorder);

        BhavcopyBulkLoadJob.BulkLoadResult result = bulkJob.loadDirectory(
                tempDir, new BhavcopyJdbcTestSupport.ConnectionRecorder(true).proxy());

        assertEquals(2, result.totalFiles());
        assertEquals(2, result.successfulFiles());
        assertTrue(result.fileErrors().isEmpty());
        // Verify files processed in name order
        assertEquals("a_20240417.csv", recorder.processedFiles.get(0).getFileName().toString());
        assertEquals("b_20240418.csv", recorder.processedFiles.get(1).getFileName().toString());
    }

    @Test
    void aggregatesResultsAcrossFiles() throws Exception {
        Files.writeString(tempDir.resolve("day1.csv"), "H\nR");
        Files.writeString(tempDir.resolve("day2.csv"), "H\nR");

        RecordingIngestionJob recorder = new RecordingIngestionJob(
                new BhavcopyIngestionJob.IngestionResult(10, 8, 7, List.of(), 3, 6, 2, 1),
                new BhavcopyIngestionJob.IngestionResult(20, 15, 14, List.of(), 5, 12, 4, 3)
        );
        BhavcopyBulkLoadJob bulkJob = new BhavcopyBulkLoadJob(recorder);

        BhavcopyBulkLoadJob.BulkLoadResult result = bulkJob.loadDirectory(
                tempDir, new BhavcopyJdbcTestSupport.ConnectionRecorder(true).proxy());

        assertEquals(2, result.totalFiles());
        assertEquals(2, result.successfulFiles());
        assertEquals(30, result.totalRowsRead());
        assertEquals(18, result.totalOptionsInserted());
        assertEquals(8, result.totalInstrumentsInserted());
        assertEquals(4, result.totalSpotInserted());
    }

    @Test
    void recordsFailedFilesWithoutAborting() throws Exception {
        Files.writeString(tempDir.resolve("good.csv"), "H\nR");
        Files.writeString(tempDir.resolve("bad.csv"), "H\nR");

        RecordingIngestionJob recorder = new RecordingIngestionJob() {
            @Override
            public BhavcopyIngestionJob.IngestionResult ingestFile(Path csvFile, Connection conn) throws IOException {
                if (csvFile.getFileName().toString().equals("bad.csv")) {
                    throw new IOException("corrupted file");
                }
                return super.ingestFile(csvFile, conn);
            }
        };
        BhavcopyBulkLoadJob bulkJob = new BhavcopyBulkLoadJob(recorder);

        BhavcopyBulkLoadJob.BulkLoadResult result = bulkJob.loadDirectory(
                tempDir, new BhavcopyJdbcTestSupport.ConnectionRecorder(true).proxy());

        assertEquals(2, result.totalFiles());
        assertEquals(1, result.successfulFiles());
        assertEquals(1, result.fileErrors().size());
        assertEquals("bad.csv", result.fileErrors().get(0).file().getFileName().toString());
    }

    @Test
    void skipsNonCsvFiles() throws Exception {
        Files.writeString(tempDir.resolve("data.csv"), "H\nR");
        Files.writeString(tempDir.resolve("readme.txt"), "not a csv");

        RecordingIngestionJob recorder = new RecordingIngestionJob();
        BhavcopyBulkLoadJob bulkJob = new BhavcopyBulkLoadJob(recorder);

        BhavcopyBulkLoadJob.BulkLoadResult result = bulkJob.loadDirectory(
                tempDir, new BhavcopyJdbcTestSupport.ConnectionRecorder(true).proxy());

        assertEquals(1, result.totalFiles());
        assertEquals(1, recorder.processedFiles.size());
    }

    @Test
    void rejectsNonDirectory() {
        Path file = tempDir.resolve("notadir.csv");
        assertThrows(IOException.class, () ->
                new BhavcopyBulkLoadJob().loadDirectory(file,
                        new BhavcopyJdbcTestSupport.ConnectionRecorder(true).proxy()));
    }

    /**
     * Stub that records which files were processed and returns configurable results.
     */
    private static class RecordingIngestionJob extends BhavcopyIngestionJob {
        final List<Path> processedFiles = new ArrayList<>();
        private final List<IngestionResult> results;
        private int callIndex;

        RecordingIngestionJob(IngestionResult... results) {
            this.results = List.of(results);
        }

        @Override
        public IngestionResult ingest(Path csvFile, Connection connection) throws IOException {
            return ingestFile(csvFile, connection);
        }

        IngestionResult ingestFile(Path csvFile, Connection connection) throws IOException {
            processedFiles.add(csvFile);
            if (callIndex < results.size()) {
                return results.get(callIndex++);
            }
            return new IngestionResult(0, 0, 0, List.of(), 0, 0, 0, 0);
        }
    }
}
