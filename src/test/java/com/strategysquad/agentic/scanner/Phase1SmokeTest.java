package com.strategysquad.agentic.scanner;

import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.agentic.signal.SignalSnapshotService;
import com.strategysquad.aggregation.OptionsContextBucket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 integration smoke test.
 *
 * <p>Exercises the full Phase 1 path end-to-end using pre-built historical fixtures —
 * no live database, no network calls:
 * <pre>
 *   MorningScannerService (fixture ContractLoader)
 *       → CandidateScoringEngine
 *       → SignalSnapshotService
 * </pre>
 *
 * <p>This class lives in {@code com.strategysquad.agentic.scanner} to access the
 * package-private {@link CandidateScoringEngine.CohortKey} type needed to build
 * cohort map fixtures.
 *
 * <h2>Acceptance criteria (Task 1.8)</h2>
 * <ol>
 *   <li>At least one candidate is returned from the scanner.</li>
 *   <li>The top candidate has an empty {@link CandidateOpportunity#disqualifierReason()}.</li>
 *   <li>A {@link SignalSnapshot} is computable for the top candidate.</li>
 *   <li>The top candidate's snapshot is not stale and has a valid reason code.</li>
 *   <li>{@code mvn test} passes — all existing tests remain green.</li>
 * </ol>
 */
class Phase1SmokeTest {

    private static final Instant SCAN_TS   = Instant.parse("2026-04-28T04:00:00Z"); // 09:30 IST
    private static final LocalDate EXPIRY  = LocalDate.of(2026, 5, 1);  // 3 DTE weekly
    private static final BigDecimal SPOT   = new BigDecimal("24600");

    // Cohort dimensions used across fixtures
    private static final int MONEYNESS_BUCKET = 200;   // 200 pts OTM
    private static final int TIME_BUCKET      = 24;    // ~6 h to expiry → bucket 24

    // Historical avg price is 70; lastPrice 95 → 36% rich → high theta score
    private static final BigDecimal HIST_AVG_PRICE  = new BigDecimal("70.0");
    private static final BigDecimal HIST_AVG_VOLUME = new BigDecimal("1500.0");

    // =========================================================================
    // Test 1 — primary acceptance criteria
    // =========================================================================

    @Test
    void phase1_smokeTest_scannerProducesRankedCandidateAndSnapshotIsComputable()
            throws Exception {

        // --- Step 1: cohort map -----------------------------------------------
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap = buildCohortMap();

        // --- Step 2: scanner --------------------------------------------------
        CandidateScoringEngine engine  = new CandidateScoringEngine();
        MorningScannerService  scanner = new MorningScannerService(buildContractLoader(), engine);
        List<CandidateOpportunity> candidates = scanner.scan(cohortMap, SCAN_TS);

        // AC1 — at least one candidate returned
        assertFalse(candidates.isEmpty(), "Phase 1 scanner must return at least one candidate");

        // AC2 — top candidate is qualified
        CandidateOpportunity top = candidates.get(0);
        assertTrue(top.disqualifierReason().isEmpty(),
                "Top candidate must be qualified; disqualifier=" + top.disqualifierReason().orElse("n/a"));
        assertTrue(top.totalScore() > 0.0,
                "Top candidate must have positive total score; got=" + top.totalScore());

        // --- Step 3: signal snapshot ------------------------------------------
        SignalSnapshotService signalService = new SignalSnapshotService();
        SignalSnapshot snap = signalService.compute(buildLegInput(top), SCAN_TS);

        // AC3 — snapshot is computable
        assertNotNull(snap, "SignalSnapshot must not be null for the top candidate");

        // AC4 — snapshot is fresh with valid reason code
        assertFalse(snap.stale(), "Snapshot for top candidate must not be stale");
        assertNotNull(snap.reason());
        assertFalse(snap.reason().isBlank());

        // Metadata consistency
        assertEquals(top.instrumentId(), snap.instrumentId());
        assertEquals(top.underlying(),   snap.underlying());
        assertEquals(top.optionType(),    snap.optionType());

        // Invariant fields — never null per SignalSnapshot compact constructor
        assertNotNull(snap.thetaState());
        assertNotNull(snap.volumeState());
    }

    // =========================================================================
    // Test 2 — disqualified candidates at bottom
    // =========================================================================

    @Test
    void phase1_disqualifiedCandidatesAtBottom() throws Exception {
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap = buildCohortMap();
        MorningScannerService scanner = new MorningScannerService(
                buildContractLoaderWithDisqualified(), new CandidateScoringEngine());
        List<CandidateOpportunity> candidates = scanner.scan(cohortMap, SCAN_TS);

        boolean seenDisqualified = false;
        for (CandidateOpportunity c : candidates) {
            if (c.disqualifierReason().isPresent()) {
                seenDisqualified = true;
            } else {
                assertFalse(seenDisqualified,
                        "Qualified candidate appeared after a disqualified one — sort order violated");
            }
        }
    }

    // =========================================================================
    // Test 3 — zero underlying move guard (smoke-level, uses SignalSnapshotService directly)
    // =========================================================================

    @Test
    void phase1_signalSnapshotService_zeroUnderlyingMove_doesNotThrow() {
        SignalSnapshotService service = new SignalSnapshotService();
        SignalSnapshotService.LegInput input = new SignalSnapshotService.LegInput(
                "INS_NIFTY_20260501_24800_CE",
                "NIFTY", "CE",
                new BigDecimal("24800"),
                "SHORT",
                new BigDecimal("95"),  new BigDecimal("90"),   // currentPrice, price2mAgo
                new BigDecimal("24600"), new BigDecimal("24600"), // zero underlying move
                null, null,                    // SOD prices
                65, 1,                         // lotSize, quantity
                2000L, new BigDecimal("1000"), // currentVolume, dayAverageVolume
                null, null, null, null, null,  // entry fields — pre-entry snapshot
                false
        );

        SignalSnapshot snap = assertDoesNotThrow(
                () -> service.compute(input, SCAN_TS),
                "Must not throw on zero underlying move");

        assertFalse(snap.stale());
        assertNull(snap.empiricalDelta2m(),
                "empiricalDelta2m must be null when underlying did not move (division-by-zero guard)");
    }

    // =========================================================================
    // Fixture builders
    // =========================================================================

    /**
     * Two cohort buckets: one for CE and one for PE at moneynessBucket=200, timeBucket=24.
     *
     * <p>{@code OptionsContextBucket} constructor:
     * {@code (bucketTs, underlying, optionType, timeBucket15m, moneynessBucket,
     *   avgOptionPrice, avgPriceToSpotRatio, avgVolume, sampleCount)}
     */
    private static Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> buildCohortMap() {
        Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> map = new HashMap<>();

        map.put(
                new CandidateScoringEngine.CohortKey("NIFTY", "CE", MONEYNESS_BUCKET, TIME_BUCKET),
                new OptionsContextBucket(
                        SCAN_TS, "NIFTY", "CE",
                        TIME_BUCKET, MONEYNESS_BUCKET,
                        HIST_AVG_PRICE,
                        new BigDecimal("0.00285"),   // avgPriceToSpotRatio (70/24600)
                        HIST_AVG_VOLUME,
                        50L
                )
        );

        map.put(
                new CandidateScoringEngine.CohortKey("NIFTY", "PE", MONEYNESS_BUCKET, TIME_BUCKET),
                new OptionsContextBucket(
                        SCAN_TS, "NIFTY", "PE",
                        TIME_BUCKET, MONEYNESS_BUCKET,
                        new BigDecimal("65.0"),
                        new BigDecimal("0.00264"),
                        new BigDecimal("1400.0"),
                        45L
                )
        );

        return map;
    }

    /**
     * Two well-formed, qualified contracts.
     *
     * <p>{@code RawContractRow} constructor:
     * {@code (instrumentId, underlying, tradingSymbol, optionType, strike, expiryDate,
     *   expiryType, lotSize, spot, lastPrice, bidPrice, askPrice, moneynessPoints,
     *   moneynessBucket, timeBucket15m, volume)}
     */
    private static MorningScannerService.ContractLoader buildContractLoader() {
        // CE: 24800 strike, spot 24600 → 200 pts OTM; lastPrice 95 → 36% above avg 70
        ScannerQuery.RawContractRow ce = new ScannerQuery.RawContractRow(
                "INS_NIFTY_20260501_24800_CE", "NIFTY", "NIFTY26MAY24800CE", "CE",
                new BigDecimal("24800"), EXPIRY, "WEEKLY", 65, SPOT,
                new BigDecimal("95.0"),
                new BigDecimal("94.5"), new BigDecimal("95.5"),
                new BigDecimal("200"), MONEYNESS_BUCKET, TIME_BUCKET,
                3000L   // volume > avg 1500 → CONFIRMED
        );

        // PE: 24400 strike, spot 24600 → 200 pts OTM; lastPrice 88 → 35% above avg 65
        ScannerQuery.RawContractRow pe = new ScannerQuery.RawContractRow(
                "INS_NIFTY_20260501_24400_PE", "NIFTY", "NIFTY26MAY24400PE", "PE",
                new BigDecimal("24400"), EXPIRY, "WEEKLY", 65, SPOT,
                new BigDecimal("88.0"),
                new BigDecimal("87.5"), new BigDecimal("88.5"),
                new BigDecimal("200"), MONEYNESS_BUCKET, TIME_BUCKET,
                2800L
        );

        return () -> List.of(ce, pe);
    }

    /** One qualified contract and one zero-bid (disqualified) contract. */
    private static MorningScannerService.ContractLoader buildContractLoaderWithDisqualified() {
        ScannerQuery.RawContractRow qualified = new ScannerQuery.RawContractRow(
                "INS_NIFTY_20260501_24800_CE", "NIFTY", "NIFTY26MAY24800CE", "CE",
                new BigDecimal("24800"), EXPIRY, "WEEKLY", 65, SPOT,
                new BigDecimal("95.0"),
                new BigDecimal("94.5"), new BigDecimal("95.5"),
                new BigDecimal("200"), MONEYNESS_BUCKET, TIME_BUCKET,
                3000L
        );

        ScannerQuery.RawContractRow zeroBid = new ScannerQuery.RawContractRow(
                "INS_NIFTY_20260501_24900_CE", "NIFTY", "NIFTY26MAY24900CE", "CE",
                new BigDecimal("24900"), EXPIRY, "WEEKLY", 65, SPOT,
                new BigDecimal("30.0"),
                BigDecimal.ZERO,              // bid=0 → ZERO_BID disqualifier
                new BigDecimal("30.5"),
                new BigDecimal("300"), MONEYNESS_BUCKET, TIME_BUCKET,
                1000L
        );

        return () -> List.of(qualified, zeroBid);
    }

    /**
     * Builds a {@link SignalSnapshotService.LegInput} from a candidate.
     * Simulates: underlying +80 pts, option +18 pts over the 2-minute window.
     */
    private static SignalSnapshotService.LegInput buildLegInput(CandidateOpportunity top) {
        BigDecimal currentPrice   = top.lastPrice();
        BigDecimal price2mAgo     = currentPrice.subtract(new BigDecimal("18"));
        BigDecimal currentUnderly = top.spot();
        BigDecimal underly2mAgo   = currentUnderly.subtract(new BigDecimal("80"));

        return new SignalSnapshotService.LegInput(
                top.instrumentId(),
                top.underlying(),
                top.optionType(),
                top.strike(),
                "SHORT",
                currentPrice, price2mAgo,
                currentUnderly, underly2mAgo,
                null, null,                    // SOD prices — not needed for smoke
                65, 1,                         // lotSize, quantity
                3000L, new BigDecimal("1500"), // currentVolume, dayAverageVolume
                null, null, null, null, null,  // entry fields — pre-entry snapshot
                false
        );
    }
}
