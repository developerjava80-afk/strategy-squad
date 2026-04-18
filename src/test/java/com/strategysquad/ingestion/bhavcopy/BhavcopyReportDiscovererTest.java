package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BhavcopyReportDiscovererTest {

    @Test
    void discoversAvailableReportsForTradeDateFromHtmlListing() throws Exception {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);
        String html = """
                <html>
                  <body>
                    <a href="/reports/FNO_Bhavcopy_2024-07-01.zip">FNO Bhavcopy ZIP</a>
                    <a href="/reports/other-report.csv">Other report</a>
                  </body>
                </html>
                """;
        BhavcopyReportDiscoverer discoverer = new BhavcopyReportDiscoverer(ignored -> html);

        List<BhavcopyReport> reports = discoverer.discover(tradeDate);

        assertEquals(2, reports.size());
        assertEquals("FNO Bhavcopy ZIP", reports.get(0).reportName());
        assertEquals(URI.create("https://www.nseindia.com/reports/FNO_Bhavcopy_2024-07-01.zip"), reports.get(0).downloadUri());
        assertEquals(BhavcopyReport.FileType.ZIP, reports.get(0).fileType());
        assertEquals(tradeDate, reports.get(0).tradeDate());
        assertEquals("Other report", reports.get(1).reportName());
        assertEquals(BhavcopyReport.FileType.CSV, reports.get(1).fileType());
    }

    @Test
    void wrapsListingFailuresAsDiscoveryFailures() {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);
        BhavcopyReportDiscoverer discoverer = new BhavcopyReportDiscoverer(ignored -> {
            throw new java.io.IOException("boom");
        });

        BhavcopyArchiveException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BhavcopyArchiveException.class,
                () -> discoverer.discover(tradeDate)
        );

        assertEquals(BhavcopyArchiveException.Reason.REPORTS_DISCOVERY_FAILED, ex.reason());
    }
}
