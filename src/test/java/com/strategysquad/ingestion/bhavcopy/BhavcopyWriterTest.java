package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class BhavcopyWriterTest {

    @Test
    void deriveExpiryTypeMonthly() {
        // Last Thursday of March 2024 = 28 Mar 2024
        assertEquals("MONTHLY", BhavcopyWriter.deriveExpiryType(LocalDate.of(2024, 3, 28)));
    }

    @Test
    void deriveExpiryTypeWeekly() {
        // 21 Mar 2024 is a Thursday but not the last Thursday of March
        assertEquals("WEEKLY", BhavcopyWriter.deriveExpiryType(LocalDate.of(2024, 3, 21)));
    }

    @Test
    void deriveExpiryTypeLastThursdayDecember2024() {
        // Last Thursday of December 2024 = 26 Dec 2024
        assertEquals("MONTHLY", BhavcopyWriter.deriveExpiryType(LocalDate.of(2024, 12, 26)));
    }

    @Test
    void deriveExpiryTypeNonThursday() {
        // A non-Thursday is always weekly
        assertEquals("WEEKLY", BhavcopyWriter.deriveExpiryType(LocalDate.of(2024, 3, 25)));
    }
}
