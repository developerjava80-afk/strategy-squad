package com.strategysquad.agentic.scanner;

import com.strategysquad.aggregation.OptionsContextBucket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MorningScannerService}.
 *
 * <p>All tests are pure in-memory — no database, no HTTP. The {@link ScannerQuery}
 * instance is constructed via the package-private {@code ConnectionSupplier} seam
 * so that SQL queries are answered by pre-built stub {@link ResultSet} objects.
 *
 * <h2>Test scenarios</h2>
 * <ol>
 *   <li><b>Rank order</b> — given four contracts with different scoring profiles,
 *       asserts that the qualified candidates are sorted by {@code totalScore}
 *       descending and disqualified candidates appear at the bottom.</li>
 *   <li><b>Empty universe</b> — when no active weekly contracts are found, returns
 *       an empty list without throwing.</li>
 *   <li><b>All disqualified</b> — when every contract fails a disqualification
 *       check, all appear at the bottom (with score 0.0) and no exception is thrown.</li>
 *   <li><b>Single qualified</b> — a single qualified contract is returned as the sole
 *       top candidate.</li>
 *   <li><b>Disqualified at bottom</b> — qualified candidates always appear before
 *       disqualified ones regardless of scan order.</li>
 * </ol>
 *
 * <h2>Fixture design</h2>
 * <p>Each test builds a {@link MorningScannerService} backed by a
 * {@link StubScannerQuery} that returns a fixed list of {@link ScannerQuery.RawContractRow}
 * objects. The {@link CandidateScoringEngine} is a real (unmodified) instance so that
 * scoring math is genuinely exercised.
 */
class MorningScannerServiceTest {

    // =========================================================================
    // Shared date fixtures
    // =========================================================================

    private static final Instant SCAN_INSTANT = Instant.parse("2026-04-25T05:00:00Z"); // 10:30 IST
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 25);
    private static final LocalDate EXPIRY_3D = TODAY.plusDays(3);   // DTE=3, within window
    private static final LocalDate EXPIRY_TODAY = TODAY;             // DTE=0, disqualified

    // Historical context constants
    private static final BigDecimal HIST_AVG_PRICE  = new BigDecimal("100.00");
    private static final BigDecimal HIST_AVG_VOLUME = new BigDecimal("2000");

    // =========================================================================
    // Test 1 — Rank order with four contracts
    // =========================================================================

    /**
     * Four contracts are scored through the full pipeline:
     * <ul>
     *   <li>Contract A (CE, 30% rich, tight spread, 300 pts OTM, timeBucket=4) —
     *       highest total score → must be rank 1.</li>
     *   <li>Contract B (PE, 20% rich, tight spread, 200 pts OTM, timeBucket=8) —
     *       moderate score → must be rank 2.</li>
     *   <li>Contract C (CE, 5% rich, wide spread, 50 pts OTM, timeBucket=18) —
     *       lowest qualified score → must be rank 3.</li>
     *   <li>Contract D (CE, zero bid) — disqualified ZERO_BID → must be last.</li>
     * </ul>
     */
    @Test
    void scan_multiContractFixture_correctRankOrder() throws Exception {
        ScannerQuery.RawContractRow contractA = row(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE",
                new BigDecimal("130.00"),   // 30% rich
                new BigDecimal("129.50"),   // tight spread
                new BigDecimal("130.50"),
                4000L,                      // 2× avg volume
                EXPIRY_3D,
                new BigDecimal("300"),      // 300 pts OTM
                3, 4                        // timeBucket=4 (near expiry)
        );

        ScannerQuery.RawContractRow contractB = row(
                "INS_NIFTY_20260428_24700_PE", "NIFTY", "PE",
                new BigDecimal("120.00"),   // 20% rich
                new BigDecimal("119.50"),
                new BigDecimal("120.50"),
                3000L,
                EXPIRY_3D,
                new BigDecimal("200"),      // 200 pts OTM
                2, 8                        // timeBucket=8 (at high-decay boundary)
        );

        ScannerQuery.RawContractRow contractC = row(
                "INS_NIFTY_20260428_24900_CE", "NIFTY", "CE",
                new BigDecimal("105.00"),   // 5% rich
                new BigDecimal("102.00"),   // wider spread
                new BigDecimal("108.00"),
                500L,
                EXPIRY_3D,
                new BigDecimal("50"),       // only 50 pts OTM — near-ATM penalty
                1, 18                       // timeBucket=18 (mid-range, less theta decay)
        );

        // Disqualified: bid=0
        ScannerQuery.RawContractRow contractD = row(
                "INS_NIFTY_20260428_25000_CE", "NIFTY", "CE",
                new BigDecimal("110.00"),
                BigDecimal.ZERO,            // ZERO_BID → disqualified
                new BigDecimal("112.00"),
                1000L,
                EXPIRY_3D,
                new BigDecimal("400"),
                4, 4
        );

        // Build cohort map covering all qualified contracts' (underlying, optionType, bucket, timeBucket)
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap = new HashMap<>();
        cohortMap.put(key("NIFTY", "CE", 3, 4),  cohort("NIFTY", "CE", 3, 4));
        cohortMap.put(key("NIFTY", "PE", 2, 8),  cohort("NIFTY", "PE", 2, 8));
        cohortMap.put(key("NIFTY", "CE", 1, 18), cohort("NIFTY", "CE", 1, 18));
        // No entry for contractD — but it will be disqualified by ZERO_BID before cohort lookup

        MorningScannerService service = new MorningScannerService(
                () -> List.of(contractA, contractB, contractC, contractD),
                new CandidateScoringEngine());

        List<CandidateOpportunity> result = service.scan(cohortMap, SCAN_INSTANT);

        assertEquals(4, result.size(), "must return all 4 contracts");

        // Qualified contracts (first 3) must have empty disqualifierReason
        for (int i = 0; i < 3; i++) {
            assertTrue(result.get(i).disqualifierReason().isEmpty(),
                    "position " + i + " must be qualified; got reason: "
                            + result.get(i).disqualifierReason());
        }

        // Disqualified contract must be last
        assertEquals(Optional.of("ZERO_BID"),
                result.get(3).disqualifierReason(),
                "last position must be the ZERO_BID disqualified contract");
        assertEquals("INS_NIFTY_20260428_25000_CE", result.get(3).instrumentId(),
                "last position must be contractD");

        // Rank 1 must be Contract A (highest theta from near-expiry + high richness + deep OTM)
        assertEquals("INS_NIFTY_20260428_24800_CE", result.get(0).instrumentId(),
                "rank 1 must be contract A (30% rich, timeBucket=4, 300 pts OTM)");

        // Rank 2 must be Contract B
        assertEquals("INS_NIFTY_20260428_24700_PE", result.get(1).instrumentId(),
                "rank 2 must be contract B (20% rich, timeBucket=8, 200 pts OTM)");

        // Rank 3 must be Contract C (lowest qualified score)
        assertEquals("INS_NIFTY_20260428_24900_CE", result.get(2).instrumentId(),
                "rank 3 must be contract C (5% rich, timeBucket=18, near-ATM penalty)");

        // Score ordering: rank1 >= rank2 >= rank3
        assertTrue(result.get(0).totalScore() >= result.get(1).totalScore(),
                "rank 1 score must be >= rank 2 score");
        assertTrue(result.get(1).totalScore() >= result.get(2).totalScore(),
                "rank 2 score must be >= rank 3 score");
        assertTrue(result.get(2).totalScore() > 0.0,
                "rank 3 (last qualified) must still have a positive total score");
        assertEquals(0.0, result.get(3).totalScore(), 1e-9,
                "disqualified contract must have totalScore = 0.0");
    }

    // =========================================================================
    // Test 2 — Empty contract universe
    // =========================================================================

    /**
     * When {@link ScannerQuery#fetchActiveWeeklyContracts()} returns an empty list,
     * {@link MorningScannerService#scan} must return an empty list without throwing.
     */
    @Test
    void scan_emptyUniverse_returnsEmptyList() throws Exception {
        MorningScannerService service = new MorningScannerService(
                List::of,
                new CandidateScoringEngine());

        List<CandidateOpportunity> result = service.scan(Map.of(), SCAN_INSTANT);

        assertNotNull(result, "result must not be null");
        assertTrue(result.isEmpty(), "result must be empty when no contracts are found");
    }

    // =========================================================================
    // Test 3 — All contracts disqualified
    // =========================================================================

    /**
     * When every contract fails a disqualification check, all are returned at the
     * bottom (score = 0.0, disqualifierReason present), and no exception is thrown.
     */
    @Test
    void scan_allDisqualified_allAtBottom() throws Exception {
        // Two contracts: one zero-bid, one DTE=0 (expires today)
        ScannerQuery.RawContractRow zeroBid = row(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE",
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("102.00"),
                1000L, EXPIRY_3D, new BigDecimal("100"), 1, 8);

        ScannerQuery.RawContractRow expiredToday = row(
                "INS_NIFTY_20260425_24900_CE", "NIFTY", "CE",
                new BigDecimal("5.00"), new BigDecimal("4.90"), new BigDecimal("5.10"),
                500L, EXPIRY_TODAY, new BigDecimal("100"), 1, 8);

        MorningScannerService service = new MorningScannerService(
                () -> List.of(zeroBid, expiredToday),
                new CandidateScoringEngine());

        List<CandidateOpportunity> result = service.scan(Map.of(), SCAN_INSTANT);

        assertEquals(2, result.size(), "both contracts must be present");
        for (CandidateOpportunity c : result) {
            assertTrue(c.disqualifierReason().isPresent(),
                    "all contracts must be disqualified; got: " + c.disqualifierReason());
            assertEquals(0.0, c.totalScore(), 1e-9,
                    "disqualified contract must have totalScore = 0.0");
        }
    }

    // =========================================================================
    // Test 4 — Single qualified contract is top candidate
    // =========================================================================

    /**
     * A single qualified contract must be the sole top candidate at position 0
     * of the returned list.
     */
    @Test
    void scan_singleQualifiedContract_isTopCandidate() throws Exception {
        ScannerQuery.RawContractRow single = row(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE",
                new BigDecimal("120.00"), new BigDecimal("119.50"), new BigDecimal("120.50"),
                3000L, EXPIRY_3D, new BigDecimal("200"), 2, 8);

        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                Map.of(key("NIFTY", "CE", 2, 8), cohort("NIFTY", "CE", 2, 8));

        MorningScannerService service = new MorningScannerService(
                () -> List.of(single),
                new CandidateScoringEngine());

        List<CandidateOpportunity> result = service.scan(cohortMap, SCAN_INSTANT);

        assertEquals(1, result.size(), "must return exactly one candidate");
        assertTrue(result.get(0).disqualifierReason().isEmpty(),
                "the single contract must be qualified");
        assertEquals("INS_NIFTY_20260428_24800_CE", result.get(0).instrumentId(),
                "the single contract must be top candidate");
        assertTrue(result.get(0).totalScore() > 0.0,
                "qualified single candidate must have positive totalScore");
    }

    // =========================================================================
    // Test 5 — Qualified candidates always before disqualified
    // =========================================================================

    /**
     * Even when the scanner returns disqualified contracts first (as the query
     * would naturally return them), qualified ones must always appear before
     * disqualified ones in the output.
     */
    @Test
    void scan_disqualifiedFirst_qualifiedPromotedToTop() throws Exception {
        // Build list with disqualified contract first (ZERO_BID) then qualified
        ScannerQuery.RawContractRow disqualified = row(
                "INS_NIFTY_20260428_24700_CE", "NIFTY", "CE",
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("102.00"),
                1000L, EXPIRY_3D, new BigDecimal("200"), 2, 8);

        ScannerQuery.RawContractRow qualified = row(
                "INS_NIFTY_20260428_24800_CE", "NIFTY", "CE",
                new BigDecimal("120.00"), new BigDecimal("119.50"), new BigDecimal("120.50"),
                3000L, EXPIRY_3D, new BigDecimal("300"), 3, 8);

        // Disqualified is passed first in the list (as if DB returned it first)
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap =
                Map.of(key("NIFTY", "CE", 3, 8), cohort("NIFTY", "CE", 3, 8));

        MorningScannerService service = new MorningScannerService(
                () -> List.of(disqualified, qualified),
                new CandidateScoringEngine());

        List<CandidateOpportunity> result = service.scan(cohortMap, SCAN_INSTANT);

        assertEquals(2, result.size(), "must return both contracts");
        assertTrue(result.get(0).disqualifierReason().isEmpty(),
                "qualified contract must be at position 0");
        assertEquals("INS_NIFTY_20260428_24800_CE", result.get(0).instrumentId(),
                "qualified contract must be ranked first");
        assertTrue(result.get(1).disqualifierReason().isPresent(),
                "disqualified contract must be at position 1");
    }

    // =========================================================================
    // Test 6 — sortCandidates unit test
    // =========================================================================

    /**
     * Direct test of {@link MorningScannerService#sortCandidates(List)} with a
     * mixed list — verifies the sorting logic in isolation.
     */
    @Test
    void sortCandidates_mixedList_qualifiedFirstByScoreDescThenDisqualified() {
        CandidateOpportunity high = makeCandidate("INS_A", 0.80, false);
        CandidateOpportunity mid  = makeCandidate("INS_B", 0.50, false);
        CandidateOpportunity low  = makeCandidate("INS_C", 0.20, false);
        CandidateOpportunity disq = makeCandidate("INS_D", 0.00, true);

        // Pass in reverse order to confirm sorting works
        List<CandidateOpportunity> input = List.of(disq, low, high, mid);
        List<CandidateOpportunity> sorted = MorningScannerService.sortCandidates(input);

        assertEquals(4, sorted.size());
        assertEquals("INS_A", sorted.get(0).instrumentId(), "rank 1 must be INS_A (score 0.80)");
        assertEquals("INS_B", sorted.get(1).instrumentId(), "rank 2 must be INS_B (score 0.50)");
        assertEquals("INS_C", sorted.get(2).instrumentId(), "rank 3 must be INS_C (score 0.20)");
        assertEquals("INS_D", sorted.get(3).instrumentId(), "rank 4 must be the disqualified INS_D");
    }

    // =========================================================================
    // Private fixture helpers
    // =========================================================================

    /** Builds a minimal {@link ScannerQuery.RawContractRow} for tests. */
    private static ScannerQuery.RawContractRow row(
            String instrumentId,
            String underlying,
            String optionType,
            BigDecimal lastPrice,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            long volume,
            LocalDate expiryDate,
            BigDecimal moneynessPoints,
            int moneynessBucket,
            int timeBucket15m
    ) {
        return new ScannerQuery.RawContractRow(
                instrumentId,
                underlying,
                underlying + "26APR" + instrumentId.replaceAll(".*_(\\d+)_.*", "$1") + optionType,
                optionType,
                new BigDecimal("24800"),
                expiryDate,
                "WEEKLY",
                75,
                new BigDecimal("24500"),      // spot (arbitrary — not scored)
                lastPrice,
                bidPrice,
                askPrice,
                moneynessPoints,
                moneynessBucket,
                timeBucket15m,
                volume
        );
    }

    /** Builds a {@link CandidateScoringEngine.CohortKey} for the given dimensions. */
    private static CandidateScoringEngine.CohortKey key(
            String underlying, String optionType, int moneynessBucket, int timeBucket15m
    ) {
        return new CandidateScoringEngine.CohortKey(underlying, optionType, moneynessBucket, timeBucket15m);
    }

    /** Builds an {@link OptionsContextBucket} with the shared historical avg price/volume. */
    private static OptionsContextBucket cohort(
            String underlying, String optionType, int moneynessBucket, int timeBucket15m
    ) {
        return new OptionsContextBucket(
                Instant.parse("2026-04-25T00:00:00Z"),
                underlying,
                optionType,
                timeBucket15m,
                moneynessBucket,
                HIST_AVG_PRICE,
                new BigDecimal("0.0041"),
                HIST_AVG_VOLUME,
                100L
        );
    }

    /**
     * Builds a minimal {@link CandidateOpportunity} for use in sorting tests.
     * All price fields are set to non-null plausible values.
     */
    private static CandidateOpportunity makeCandidate(
            String instrumentId, double totalScore, boolean disqualified
    ) {
        return new CandidateOpportunity(
                "SCAN_NIFTY_" + instrumentId + "_202604251030",
                "NIFTY",
                instrumentId,
                "NIFTY26APR24800CE",
                "CE",
                new BigDecimal("24800"),
                LocalDate.of(2026, 4, 28),
                "WEEKLY",
                new BigDecimal("24500"),
                new BigDecimal("100.00"),
                new BigDecimal("99.50"),
                new BigDecimal("100.50"),
                new BigDecimal("200"),
                2,
                8,
                new BigDecimal("100.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                totalScore,   // liquidityScore
                totalScore,   // thetaOpportunityScore
                totalScore,   // deltaRiskScore
                totalScore,   // totalScore
                disqualified ? Optional.of("ZERO_BID") : Optional.empty()
        );
    }

}
