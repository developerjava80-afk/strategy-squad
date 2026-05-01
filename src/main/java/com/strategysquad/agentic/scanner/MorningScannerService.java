package com.strategysquad.agentic.scanner;

import com.strategysquad.aggregation.OptionsContextBucket;
import com.strategysquad.scope.ResolvedUniverse;
import com.strategysquad.scope.Scope;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Orchestrates the morning (pre-open) scanner pipeline to produce a ranked list
 * of short-option candidates for NIFTY and BANKNIFTY weekly contracts.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Calls {@link ScannerQuery#fetchActiveWeeklyContracts()} to load the raw
 *       universe of active weekly contracts with their latest prices.</li>
 *   <li>Calls {@link CandidateScoringEngine#score} for each contract, passing the
 *       historical cohort context from {@code options_context_buckets}.</li>
 *   <li>Sorts the results by {@link CandidateOpportunity#totalScore()} descending,
 *       with disqualified candidates (score = 0.0 and {@link CandidateOpportunity#disqualifierReason()}
 *       present) placed at the bottom regardless of score.</li>
 *   <li>Logs a summary line: total candidates, qualified count, top candidate
 *       instrument ID and score.</li>
 * </ol>
 *
 * <h2>Simulation mode</h2>
 * <p>When the injected {@link ScannerQuery} was constructed with a live
 * {@code SimulationClock}, all data reads are point-in-time constrained.
 * This service is agnostic to simulation vs live mode — it delegates mode
 * selection entirely to {@link ScannerQuery}.
 *
 * <h2>Empty results</h2>
 * <p>Returns an empty list (never {@code null} or an exception) when no active
 * contracts are found or all contracts are disqualified.
 *
 * <h2>No position mutation</h2>
 * <p>This service is strictly read-only. It never calls
 * {@code PositionSessionActionService} or any other service that mutates state.
 */
public final class MorningScannerService {

    private static final Logger LOG = Logger.getLogger(MorningScannerService.class.getName());

    /** Scan-timestamp formatter: yyyyMMddHHmm — used as part of candidate IDs. */
    private static final DateTimeFormatter SCAN_TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneId.of("Asia/Kolkata"));

    // =========================================================================
    // Contract loader seam — package-private for testability
    // =========================================================================

    /**
     * Functional interface that supplies the raw contract universe.
     * Production code wires this to {@link ScannerQuery#fetchActiveWeeklyContracts()};
     * tests inject a stub that returns pre-built fixture rows without a live DB.
     */
    @FunctionalInterface
    interface ContractLoader {
        /**
         * Returns the list of active weekly contracts to score.
         * Implementations must never return {@code null} — return an empty list when
         * no contracts are available.
         *
         * @throws SQLException if a database error occurs (not a no-results condition)
         */
        List<ScannerQuery.RawContractRow> load() throws SQLException;
    }

    private final ContractLoader contractLoader;
    private final CandidateScoringEngine scoringEngine;

    /**
     * The underlying {@link ScannerQuery} reference, retained for the scoped-scan path.
     * {@code null} when the service was constructed via the test-only
     * {@link #MorningScannerService(ContractLoader, CandidateScoringEngine)} constructor,
     * in which case {@link #scanScoped} is not supported.
     */
    private final ScannerQuery scannerQuery;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates a {@code MorningScannerService} with injected collaborators.
     *
     * <p>Both collaborators must be fully configured before being passed here —
     * simulation-clock setup, connection strings, etc. are the caller's
     * responsibility.
     *
     * @param scannerQuery  configured query component; must not be null
     * @param scoringEngine stateless scoring engine; must not be null
     */
    public MorningScannerService(ScannerQuery scannerQuery, CandidateScoringEngine scoringEngine) {
        if (scannerQuery == null) {
            throw new IllegalArgumentException("scannerQuery must not be null");
        }
        if (scoringEngine == null) {
            throw new IllegalArgumentException("scoringEngine must not be null");
        }
        this.contractLoader = scannerQuery::fetchActiveWeeklyContracts;
        this.scoringEngine  = scoringEngine;
        this.scannerQuery   = scannerQuery;
    }

    /**
     * Creates a {@code MorningScannerService} scoped to a single underlying.
     * Instruments for the other underlying are not loaded, avoiding unnecessary
     * DB round-trips in per-underlying scanner calls.
     *
     * @param scannerQuery  configured query component; must not be null
     * @param scoringEngine stateless scoring engine; must not be null
     * @param underlying    the underlying to scan ({@code NIFTY} or {@code BANKNIFTY}); must not be null
     */
    public MorningScannerService(ScannerQuery scannerQuery, CandidateScoringEngine scoringEngine,
                                  String underlying) {
        if (scannerQuery == null) {
            throw new IllegalArgumentException("scannerQuery must not be null");
        }
        if (scoringEngine == null) {
            throw new IllegalArgumentException("scoringEngine must not be null");
        }
        if (underlying == null) {
            throw new IllegalArgumentException("underlying must not be null");
        }
        this.contractLoader = () -> scannerQuery.fetchActiveWeeklyContracts(underlying);
        this.scoringEngine  = scoringEngine;
        this.scannerQuery   = scannerQuery;
    }

    /**
     * Package-private test constructor. Accepts a {@link ContractLoader} stub so
     * unit and integration tests can inject fixture data without a live database.
     *
     * @param contractLoader stub loader; must not be null
     * @param scoringEngine  scoring engine; must not be null
     */
    MorningScannerService(ContractLoader contractLoader, CandidateScoringEngine scoringEngine) {
        if (contractLoader == null) {
            throw new IllegalArgumentException("contractLoader must not be null");
        }
        if (scoringEngine == null) {
            throw new IllegalArgumentException("scoringEngine must not be null");
        }
        this.contractLoader = contractLoader;
        this.scoringEngine  = scoringEngine;
        this.scannerQuery   = null; // test-only constructor; scanScoped() not supported
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs the full scanner pipeline and returns a ranked list of candidates.
     *
     * <p>The returned list is ordered as follows:
     * <ol>
     *   <li>Qualified candidates (empty {@link CandidateOpportunity#disqualifierReason()}),
     *       sorted by {@link CandidateOpportunity#totalScore()} descending (best first).</li>
     *   <li>Disqualified candidates (non-empty {@link CandidateOpportunity#disqualifierReason()}),
     *       sorted by {@link CandidateOpportunity#instrumentId()} ascending (stable, deterministic).</li>
     * </ol>
     *
     * @param cohortMap   historical context from {@code options_context_buckets}, keyed by
     *                    {@link CandidateScoringEngine.CohortKey}; must not be null but may
     *                    be empty (all contracts will be disqualified as MISSING_COHORT)
     * @param scanInstant the wall-clock or simulation instant at which the scan is being run;
     *                    used to compute the scan-timestamp string embedded in candidate IDs
     *                    and to derive the "today" date for DTE computation
     * @return ranked list of {@link CandidateOpportunity}; never null; may be empty
     * @throws SQLException if a database error occurs fetching the contract universe
     */
    public List<CandidateOpportunity> scan(
            Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap,
            Instant scanInstant
    ) throws SQLException {
        if (cohortMap == null) {
            throw new IllegalArgumentException("cohortMap must not be null");
        }
        if (scanInstant == null) {
            throw new IllegalArgumentException("scanInstant must not be null");
        }

        String scanTs = SCAN_TS_FMT.format(scanInstant);
        LocalDate today = scanInstant.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();

        // Step 1 — fetch raw contract universe
        List<ScannerQuery.RawContractRow> rawRows = contractLoader.load();

        if (rawRows.isEmpty()) {
            LOG.info(() -> "MorningScannerService [" + scanTs + "] — no active weekly contracts found; "
                    + "returning empty candidate list");
            return List.of();
        }

        // Step 2 — score each contract
        List<CandidateOpportunity> scored = new ArrayList<>(rawRows.size());
        for (ScannerQuery.RawContractRow row : rawRows) {
            scored.add(scoringEngine.score(row, cohortMap, scanTs, today));
        }

        // Step 3 — sort: qualified descending by totalScore, then disqualified by instrumentId
        List<CandidateOpportunity> ranked = sortCandidates(scored);

        // Step 4 — log summary
        logSummary(ranked, scanTs);

        return ranked;
    }

    /**
     * Scoped variant of {@link #scan}: runs the scoring pipeline against the
     * resolved universe of a specific {@link Scope} rather than the full
     * {@code instrument_master} universe.
     *
     * <h2>Key differences from {@link #scan}</h2>
     * <ul>
     *   <li>Instrument metadata comes from {@link ResolvedUniverse#instruments()}
     *       (already resolved — no DB round-trip to instrument_master).</li>
     *   <li>Live prices are fetched in one batch {@code IN(...)} query against
     *       {@code options_live_enriched}.</li>
     *   <li>The output is capped at {@link Scope#maxCandidates()} (qualified
     *       candidates only; disqualified candidates still appear at the end
     *       but are not counted against the cap).</li>
     *   <li>If the universe was {@link ResolvedUniverse#truncated()}, a log warning
     *       is emitted so operators can see the narrowing hint.</li>
     * </ul>
     *
     * <p>This method always uses live price data. Simulation mode is not supported
     * for the scoped path.
     *
     * @param scope       the active scope (provides maxCandidates cap and expiry context)
     * @param universe    the resolved instrument universe for this scope
     * @param cohortMap   historical context from {@code options_context_buckets}
     * @param scanInstant wall-clock instant for DTE computation and scan-ID embedding
     * @return ranked list capped at {@code scope.maxCandidates()} qualified candidates,
     *         followed by any disqualified candidates; never null; may be empty
     * @throws SQLException if a database error occurs fetching live prices
     * @throws IllegalStateException if this service was constructed via the test-only
     *         constructor that does not accept a {@link ScannerQuery}
     */
    public List<CandidateOpportunity> scanScoped(
            Scope scope,
            ResolvedUniverse universe,
            Map<CandidateScoringEngine.CohortKey, OptionsContextBucket> cohortMap,
            Instant scanInstant
    ) throws SQLException {
        if (scannerQuery == null) {
            throw new IllegalStateException(
                    "scanScoped() requires a ScannerQuery — use the public constructor");
        }
        if (scope == null)      throw new IllegalArgumentException("scope must not be null");
        if (universe == null)   throw new IllegalArgumentException("universe must not be null");
        if (cohortMap == null)  throw new IllegalArgumentException("cohortMap must not be null");
        if (scanInstant == null) throw new IllegalArgumentException("scanInstant must not be null");

        if (universe.truncated()) {
            LOG.warning(() -> "scanScoped: universe was truncated — " + universe.narrowingHint());
        }

        String scanTs = SCAN_TS_FMT.format(scanInstant);
        LocalDate today = scanInstant.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();

        // Step 1 — fetch scoped raw rows (batch price query, no instrument_master call)
        List<ScannerQuery.RawContractRow> rawRows = scannerQuery.fetchScoped(scope, universe);

        if (rawRows.isEmpty()) {
            LOG.info(() -> "scanScoped [" + scanTs + "] — no live price data for scoped universe; "
                    + "returning empty candidate list");
            return List.of();
        }

        // Step 2 — score each contract (reuses CandidateScoringEngine unchanged)
        List<CandidateOpportunity> scored = new ArrayList<>(rawRows.size());
        for (ScannerQuery.RawContractRow row : rawRows) {
            scored.add(scoringEngine.score(row, cohortMap, scanTs, today));
        }

        // Step 3 — sort: qualified descending by totalScore, disqualified at bottom
        List<CandidateOpportunity> ranked = sortCandidates(scored);

        // Step 4 — cap qualified candidates at scope.maxCandidates()
        //           disqualified candidates are always included (after the cap)
        int cap = scope.maxCandidates();
        List<CandidateOpportunity> qualified   = new ArrayList<>();
        List<CandidateOpportunity> disqualified = new ArrayList<>();
        for (CandidateOpportunity c : ranked) {
            if (c.disqualifierReason().isEmpty()) {
                qualified.add(c);
            } else {
                disqualified.add(c);
            }
        }
        List<CandidateOpportunity> cappedQualified = qualified.size() <= cap
                ? qualified
                : qualified.subList(0, cap);

        List<CandidateOpportunity> result = new ArrayList<>(cappedQualified.size() + disqualified.size());
        result.addAll(cappedQualified);
        result.addAll(disqualified);

        // Step 5 — log summary
        logScopedSummary(scope, result, cappedQualified.size(), disqualified.size(), scanTs);

        return result;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Sorts candidates so that qualified contracts appear first (highest
     * {@code totalScore} first), followed by disqualified contracts in
     * {@code instrumentId} ascending order for deterministic output.
     *
     * <p>Two qualified contracts with equal {@code totalScore} are further
     * ordered by {@code instrumentId} ascending to ensure a stable, reproducible
     * ranking.
     *
     * @param candidates un-ranked list of scored candidates (may mix qualified and disqualified)
     * @return new sorted list; the input list is not modified
     */
    static List<CandidateOpportunity> sortCandidates(List<CandidateOpportunity> candidates) {
        // Qualified first, then disqualified
        Comparator<CandidateOpportunity> comparator = Comparator
                // isDisqualified() true → moves to end (1 > 0)
                .comparingInt((CandidateOpportunity c) -> c.disqualifierReason().isPresent() ? 1 : 0)
                // Within each group: qualified by totalScore desc, disqualified by instrumentId asc
                .thenComparingDouble((CandidateOpportunity c) ->
                        c.disqualifierReason().isPresent() ? 0.0 : -c.totalScore())
                // Tie-breaker: stable ordering by instrumentId
                .thenComparing(CandidateOpportunity::instrumentId);

        List<CandidateOpportunity> sorted = new ArrayList<>(candidates);
        sorted.sort(comparator);
        return sorted;
    }

    /**
     * Emits a single INFO-level summary line after each scan run.
     *
     * <p>Format (example):
     * <pre>
     * MorningScannerService [202604251030] — total=12 qualified=8 disqualified=4
     *   top: INS_NIFTY_20260428_24800_CE score=0.7342
     * </pre>
     *
     * @param ranked sorted candidate list (qualified first)
     * @param scanTs formatted scan timestamp string
     */
    private static void logScopedSummary(Scope scope, List<CandidateOpportunity> result,
                                          int qualifiedCount, int disqualifiedCount, String scanTs) {
        String topInfo;
        if (qualifiedCount > 0) {
            CandidateOpportunity top = result.get(0);
            topInfo = String.format("  top: %s score=%.4f", top.instrumentId(), top.totalScore());
        } else {
            topInfo = "  top: none (all contracts disqualified)";
        }
        LOG.info(() -> String.format(
                "scanScoped [%s] scope=%s/%s — qualified=%d (cap=%d) disqualified=%d%n%s",
                scanTs, scope.underlying(), scope.expiry(),
                qualifiedCount, scope.maxCandidates(), disqualifiedCount, topInfo));
    }

    private static void logSummary(List<CandidateOpportunity> ranked, String scanTs) {
        long qualifiedCount = ranked.stream()
                .filter(c -> c.disqualifierReason().isEmpty())
                .count();
        long disqualifiedCount = ranked.size() - qualifiedCount;

        String topInfo;
        if (qualifiedCount > 0) {
            CandidateOpportunity top = ranked.get(0);
            topInfo = String.format("  top: %s score=%.4f",
                    top.instrumentId(), top.totalScore());
        } else {
            topInfo = "  top: none (all contracts disqualified)";
        }

        LOG.info(() -> String.format(
                "MorningScannerService [%s] — total=%d qualified=%d disqualified=%d%n%s",
                scanTs, ranked.size(), qualifiedCount, disqualifiedCount, topInfo));
    }
}
