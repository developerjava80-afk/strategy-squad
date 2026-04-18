package com.strategysquad.ingestion.bhavcopy;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Selects the single FNO Bhavcopy ZIP report from discovered reports.
 */
public class BhavcopyReportSelector {

    public BhavcopyReport selectFnoBhavcopyZip(LocalDate tradeDate, List<BhavcopyReport> reports)
            throws BhavcopyArchiveException {
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(reports, "reports must not be null");

        List<BhavcopyReport> matches = reports.stream()
                .filter(this::isFnoBhavcopyZip)
                .toList();

        if (matches.isEmpty()) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.FNO_BHAVCOPY_REPORT_NOT_FOUND,
                    tradeDate,
                    BhavcopyReportDiscoverer.REPORTS_SOURCE_URI,
                    "FNO Bhavcopy ZIP report not found for trade date " + tradeDate
            );
        }
        if (matches.size() > 1) {
            throw new BhavcopyArchiveException(
                    BhavcopyArchiveException.Reason.AMBIGUOUS_REPORT_MATCH,
                    tradeDate,
                    BhavcopyReportDiscoverer.REPORTS_SOURCE_URI,
                    "Multiple FNO Bhavcopy ZIP reports matched for trade date " + tradeDate
            );
        }
        return matches.get(0);
    }

    private boolean isFnoBhavcopyZip(BhavcopyReport report) {
        if (report.fileType() != BhavcopyReport.FileType.ZIP) {
            return false;
        }
        String metadata = (report.reportName() + " " + report.fileName() + " " + report.downloadUri())
                .toUpperCase(Locale.ENGLISH);
        boolean looksLikeBhavcopy = metadata.contains("BHAVCOPY") || metadata.contains("BHAV.CSV");
        boolean looksLikeFno = metadata.contains("FNO") || metadata.matches(".*\\bFO\\d{2}.*BHAV\\.CSV\\.ZIP.*");
        return looksLikeBhavcopy && looksLikeFno;
    }
}
