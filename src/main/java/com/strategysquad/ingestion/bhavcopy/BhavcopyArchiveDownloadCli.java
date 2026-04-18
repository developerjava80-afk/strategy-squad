package com.strategysquad.ingestion.bhavcopy;

import java.util.List;

/**
 * CLI entrypoint for downloading bhavcopy archives over a date range.
 */
public final class BhavcopyArchiveDownloadCli {
    private BhavcopyArchiveDownloadCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: BhavcopyArchiveDownloadCli <startDate dd/MM/yyyy> <endDate dd/MM/yyyy>");
            System.exit(1);
        }

        BhavcopyArchiveDownloader downloader = new BhavcopyArchiveDownloader();
        try {
            List<BhavcopyArchiveDownloader.DownloadResult> results = downloader.downloadRange(args[0], args[1]);
            for (BhavcopyArchiveDownloader.DownloadResult result : results) {
                System.out.println(result.tradeDate() + " -> " + result.csvFile().toAbsolutePath());
            }
            System.out.println("downloaded=" + results.size());
        } catch (BhavcopyArchiveException ex) {
            System.err.println("reason=" + ex.reason());
            System.err.println("message=" + ex.getMessage());
            if (ex.archiveUri() != null) {
                System.err.println("archiveUri=" + ex.archiveUri());
            }
            System.exit(1);
        }
    }
}
