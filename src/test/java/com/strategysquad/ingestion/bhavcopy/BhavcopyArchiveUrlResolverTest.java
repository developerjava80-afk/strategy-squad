package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BhavcopyArchiveUrlResolverTest {

    private final BhavcopyArchiveUrlResolver resolver = new BhavcopyArchiveUrlResolver();

    @Test
    void resolvesHistoricalArchiveUrl() {
        LocalDate tradeDate = LocalDate.of(2024, 7, 1);

        assertEquals(
                URI.create("https://nsearchives.nseindia.com/content/historical/DERIVATIVES/2024/JUL/fo01JUL2024bhav.csv.zip"),
                resolver.resolve(tradeDate)
        );
        assertEquals("fo01JUL2024bhav.csv.zip", resolver.zipFileName(tradeDate));
    }
}
