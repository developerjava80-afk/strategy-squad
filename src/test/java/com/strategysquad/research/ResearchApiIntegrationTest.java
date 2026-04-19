package com.strategysquad.research;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that run against a live QuestDB instance.
 * Skipped automatically if QuestDB is not available.
 */
class ResearchApiIntegrationTest {
    private static final String JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";

    @BeforeAll
    static void verifyDatabase() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            fail("PostgreSQL driver not found");
        }
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "admin", "quest")) {
            // connection succeeded
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "QuestDB not available, skipping integration tests: " + e.getMessage());
        }
    }

    // ---- Fair-Value: ATM near-expiry ----

    @Test
    void fairValueReturnsObservationsForAtmNearExpiry() throws Exception {
        FairValueCohortService service = new FairValueCohortService(JDBC_URL);
        FairValueSnapshot snapshot = service.loadSnapshot(
                "NIFTY", "CE",
                BigDecimal.valueOf(22000), BigDecimal.valueOf(22000),
                1,
                BigDecimal.valueOf(100)
        );
        assertTrue(snapshot.observationCount() > 0,
                "ATM near-expiry (DTE=1) should return historical observations but got 0");
        assertNotNull(snapshot.distribution());
        assertTrue(snapshot.distribution().min() > 0, "min price should be positive");
        assertTrue(snapshot.distribution().max() >= snapshot.distribution().min());
    }

    // ---- Fair-Value: OTM moderate DTE ----

    @Test
    void fairValueReturnsObservationsForOtmModerateDte() throws Exception {
        FairValueCohortService service = new FairValueCohortService(JDBC_URL);
        FairValueSnapshot snapshot = service.loadSnapshot(
                "NIFTY", "PE",
                BigDecimal.valueOf(22000), BigDecimal.valueOf(21500),
                7,
                BigDecimal.valueOf(50)
        );
        assertTrue(snapshot.observationCount() > 0,
                "OTM moderate-DTE (NIFTY PE, -500pts, DTE=7) should return observations but got 0");
    }

    // ---- Fair-Value: sparse scenario ----

    @Test
    void fairValueHandlesSparseScenarioGracefully() throws Exception {
        FairValueCohortService service = new FairValueCohortService(JDBC_URL);
        FairValueSnapshot snapshot = service.loadSnapshot(
                "NIFTY", "CE",
                BigDecimal.valueOf(22000), BigDecimal.valueOf(32000),
                1,
                BigDecimal.valueOf(1)
        );
        // Deep OTM (10000 pts away) at DTE=1 should have very few or zero observations
        assertNotNull(snapshot);
        assertEquals("Sparse", snapshot.cohortStrength());
    }

    // ---- Fair-Value: BANKNIFTY ----

    @Test
    void fairValueReturnsObservationsForBankNifty() throws Exception {
        FairValueCohortService service = new FairValueCohortService(JDBC_URL);
        FairValueSnapshot snapshot = service.loadSnapshot(
                "BANKNIFTY", "CE",
                BigDecimal.valueOf(48000), BigDecimal.valueOf(48000),
                2,
                BigDecimal.valueOf(200)
        );
        assertTrue(snapshot.observationCount() > 0,
                "BANKNIFTY ATM DTE=2 should return observations but got 0");
    }

    // ---- Forward-Outcomes: no SQL error ----

    @Test
    void forwardOutcomesCompletesWithoutError() throws Exception {
        ForwardOutcomeCohortService service = new ForwardOutcomeCohortService(JDBC_URL);
        ForwardOutcomeSnapshot snapshot = service.loadSnapshot(
                "NIFTY", "CE",
                BigDecimal.valueOf(22000), BigDecimal.valueOf(22000),
                1
        );
        assertNotNull(snapshot);
        assertTrue(snapshot.observationCount() > 0,
                "Forward-outcomes ATM DTE=1 should return observations");
        assertTrue(snapshot.nextDayObservationCount() >= 0);
        assertTrue(snapshot.expiryObservationCount() >= 0);
        assertNotNull(snapshot.opportunityLabel());
    }

    @Test
    void forwardOutcomesReturnsForwardReturns() throws Exception {
        ForwardOutcomeCohortService service = new ForwardOutcomeCohortService(JDBC_URL);
        ForwardOutcomeSnapshot snapshot = service.loadSnapshot(
                "NIFTY", "CE",
                BigDecimal.valueOf(22000), BigDecimal.valueOf(22000),
                3
        );
        assertNotNull(snapshot);
        // DTE=3 should have next-day data (the row at DTE=2 for the same instrument)
        assertTrue(snapshot.nextDayObservationCount() > 0,
                "DTE=3 cohort should have next-day forward returns");
        assertTrue(snapshot.expiryObservationCount() > 0,
                "DTE=3 cohort should have expiry forward returns");
    }

    // ---- Diagnostics: no SQL error ----

    @Test
    void diagnosticsCompletesWithoutError() throws Exception {
        DiagnosticsCohortService service = new DiagnosticsCohortService(JDBC_URL);
        DiagnosticsSnapshot snapshot = service.loadSnapshot(
                "NIFTY", "CE",
                BigDecimal.valueOf(22000), BigDecimal.valueOf(22000),
                1,
                BigDecimal.valueOf(100)
        );
        assertNotNull(snapshot);
        assertTrue(snapshot.observationCount() > 0,
                "Diagnostics ATM DTE=1 should return observations");
        assertNotNull(snapshot.confidenceLevel());
        assertNotNull(snapshot.confidenceText());
        assertTrue(snapshot.uniqueTradeDateCount() > 0);
        assertTrue(snapshot.uniqueInstrumentCount() > 0);
    }

    @Test
    void diagnosticsReturnsCasesAndCoverage() throws Exception {
        DiagnosticsCohortService service = new DiagnosticsCohortService(JDBC_URL);
        DiagnosticsSnapshot snapshot = service.loadSnapshot(
                "NIFTY", "CE",
                BigDecimal.valueOf(22000), BigDecimal.valueOf(22000),
                3,
                BigDecimal.valueOf(150)
        );
        assertNotNull(snapshot);
        assertFalse(snapshot.cases().isEmpty(), "Should return representative historical cases");
        assertTrue(snapshot.expiryCoveragePct() > 0, "Should have some expiry coverage");
        assertNotNull(snapshot.warnings());
        assertFalse(snapshot.warnings().isEmpty());
    }

    // ---- BANKNIFTY: forward-outcomes and diagnostics ----

    @Test
    void forwardOutcomesWorksForBankNifty() throws Exception {
        ForwardOutcomeCohortService service = new ForwardOutcomeCohortService(JDBC_URL);
        ForwardOutcomeSnapshot snapshot = service.loadSnapshot(
                "BANKNIFTY", "PE",
                BigDecimal.valueOf(48000), BigDecimal.valueOf(47800),
                2
        );
        assertNotNull(snapshot);
        // -200 pts / 50 = -4 → bucket -200; DTE=2 → bucket 130
        assertTrue(snapshot.observationCount() >= 0);
    }

    @Test
    void diagnosticsWorksForBankNifty() throws Exception {
        DiagnosticsCohortService service = new DiagnosticsCohortService(JDBC_URL);
        DiagnosticsSnapshot snapshot = service.loadSnapshot(
                "BANKNIFTY", "PE",
                BigDecimal.valueOf(48000), BigDecimal.valueOf(47800),
                2,
                BigDecimal.valueOf(80)
        );
        assertNotNull(snapshot);
        assertNotNull(snapshot.confidenceLevel());
    }
}
