package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalScenarioResolverTest {
    @Test
    void resolvesNiftyScenarioIntoCanonicalKey() {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                "NIFTY",
                "CE",
                BigDecimal.valueOf(22480),
                BigDecimal.valueOf(22600),
                4
        );

        assertEquals("NIFTY", cohort.underlying());
        assertEquals("CE", cohort.optionType());
        assertEquals(100, cohort.moneynessBucket());
        // (4-1)*1440+510 = 4830 minutes, 4830/15 = 322 buckets
        assertEquals(322, cohort.timeBucket15m());
        assertEquals(4830, cohort.estimatedMinutesToExpiry());
    }

    @Test
    void resolvesBankNiftyScenarioIntoFiftyPointBuckets() {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                "BANKNIFTY",
                "PE",
                BigDecimal.valueOf(48220),
                BigDecimal.valueOf(48070),
                2
        );

        // -150 / 50 = -3 → -3 * 50 = -150
        assertEquals(-150, cohort.moneynessBucket());
        // (2-1)*1440+510 = 1950 minutes, 1950/15 = 130 buckets
        assertEquals(130, cohort.timeBucket15m());
    }

    @Test
    void zeroDteProducesZeroMinutes() {
        assertEquals(0, CanonicalScenarioResolver.estimatedMinutesToExpiry(0));
        assertEquals(0, CanonicalScenarioResolver.estimatedMinutesToExpiry(-1));
    }

    @Test
    void oneDteMatchesObservedBucket34() {
        // DTE=1 → (1-1)*1440+510 = 510 minutes → bucket 34
        assertEquals(510, CanonicalScenarioResolver.estimatedMinutesToExpiry(1));
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                "NIFTY", "CE", BigDecimal.valueOf(22000), BigDecimal.valueOf(22000), 1
        );
        assertEquals(34, cohort.timeBucket15m());
    }
}
