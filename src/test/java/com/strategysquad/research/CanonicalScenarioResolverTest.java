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
        assertEquals(100, cohort.timeBucket15m());
        assertEquals(1500, cohort.estimatedMinutesToExpiry());
    }

    @Test
    void resolvesBankNiftyScenarioIntoHundredPointBuckets() {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                "BANKNIFTY",
                "PE",
                BigDecimal.valueOf(48220),
                BigDecimal.valueOf(48070),
                2
        );

        assertEquals(-200, cohort.moneynessBucket());
        assertEquals(50, cohort.timeBucket15m());
    }
}
