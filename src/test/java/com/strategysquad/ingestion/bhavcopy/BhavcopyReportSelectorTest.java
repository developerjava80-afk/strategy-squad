package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BhavcopyReportSelectorTest {
    private final BhavcopyReportSelector selector = new BhavcopyReportSelector();

    @Test
    void selectsSingleFnoBhavcopyZip() throws Exception {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);
        BhavcopyReport selected = selector.selectFnoBhavcopyZip(
                tradeDate,
                List.of(
                        BhavcopyReport.fromLink(tradeDate, "Open Interest Report", URI.create("https://example.com/oi.zip")),
                        BhavcopyReport.fromLink(tradeDate, "FNO Bhavcopy ZIP", URI.create("https://example.com/fo01JUL2024bhav.csv.zip"))
                )
        );

        assertEquals("fo01JUL2024bhav.csv.zip", selected.fileName());
    }

    @Test
    void failsWhenNoFnoBhavcopyZipIsAvailable() {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> selector.selectFnoBhavcopyZip(
                        tradeDate,
                        List.of(BhavcopyReport.fromLink(tradeDate, "Other report", URI.create("https://example.com/other.csv")))
                )
        );

        assertEquals(BhavcopyArchiveException.Reason.FNO_BHAVCOPY_REPORT_NOT_FOUND, ex.reason());
    }

    @Test
    void failsWhenMultipleFnoBhavcopyZipsMatch() {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);

        BhavcopyArchiveException ex = assertThrows(
                BhavcopyArchiveException.class,
                () -> selector.selectFnoBhavcopyZip(
                        tradeDate,
                        List.of(
                                BhavcopyReport.fromLink(tradeDate, "FNO Bhavcopy ZIP", URI.create("https://example.com/fo01JUL2024bhav.csv.zip")),
                                BhavcopyReport.fromLink(tradeDate, "FNO Bhavcopy ZIP mirror", URI.create("https://example.com/fo01JUL2024bhav-copy.csv.zip"))
                        )
                )
        );

        assertEquals(BhavcopyArchiveException.Reason.AMBIGUOUS_REPORT_MATCH, ex.reason());
    }

    @Test
    void prefersUdiffBhavcopyWhenBothLegacyAndUdiffExist() throws Exception {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);

        BhavcopyReport selected = selector.selectFnoBhavcopyZip(
                tradeDate,
                List.of(
                        BhavcopyReport.fromLink(tradeDate, "F&O-Bhavcopy (zip)", URI.create("https://example.com/fo.zip")),
                        BhavcopyReport.fromLink(
                                tradeDate,
                                "F&O-UDiFF Common Bhavcopy Final (zip)",
                                URI.create("https://example.com/BhavCopy_NSE_FO_0_0_0_20240701_F_0000.csv.zip")
                        )
                )
        );

        assertEquals("BhavCopy_NSE_FO_0_0_0_20240701_F_0000.csv.zip", selected.fileName());
    }
}
