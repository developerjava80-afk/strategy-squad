package com.strategysquad.research;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Core service for the <em>Theta + Delta Sense Check</em> feature.
 *
 * <h2>What this service does</h2>
 * <ol>
 *   <li>Validates inputs.</li>
 *   <li>Calculates observed delta from user-supplied entry/current prices.</li>
 *   <li>Fetches historical average delta and theta benefit per minute from the
 *       historical backend via {@link HistoricalThetaDeltaAdapter}.</li>
 *   <li>Computes deviations, classifies statuses, generates an opportunity signal,
 *       and assigns a reliability score.</li>
 *   <li>Logs every run for audit.</li>
 * </ol>
 *
 * <h2>What this service does NOT do</h2>
 * <ul>
 *   <li>No Black-Scholes. No IV. No Gamma. No Vega.</li>
 *   <li>No live KiteConnect dependency.</li>
 *   <li>No order placement.</li>
 *   <li>No multi-leg optimisation.</li>
 * </ul>
 *
 * <p>Empirical Theta Benefit is treated as delta-adjusted premium decay,
 * not textbook Greek theta.
 */
public final class ThetaDeltaSenseCheckService {

    private static final Logger LOG = Logger.getLogger(ThetaDeltaSenseCheckService.class.getName());

    // Status constants
    public static final String STATUS_NORMAL       = "NORMAL";
    public static final String STATUS_HIGH         = "HIGH";
    public static final String STATUS_VERY_HIGH    = "VERY_HIGH";
    public static final String STATUS_LOW          = "LOW";
    public static final String STATUS_VERY_LOW     = "VERY_LOW";
    public static final String STATUS_NOT_RELIABLE = "NOT_RELIABLE";

    // Signal constants
    public static final String SIGNAL_ATTRACTIVE        = "ATTRACTIVE";
    public static final String SIGNAL_MILDLY_ATTRACTIVE = "MILDLY_ATTRACTIVE";
    public static final String SIGNAL_NO_EDGE           = "NO_EDGE";
    public static final String SIGNAL_WEAK              = "WEAK";
    public static final String SIGNAL_UNSTABLE          = "UNSTABLE";
    public static final String SIGNAL_NOT_RELIABLE      = "NOT_RELIABLE";

    private final ThetaDeltaSenseCheckConfig config;
    private final HistoricalThetaDeltaAdapter adapter;

    /** Production constructor — uses default config and a live DB adapter. */
    public ThetaDeltaSenseCheckService(String jdbcUrl) {
        this(ThetaDeltaSenseCheckConfig.defaults(), new HistoricalThetaDeltaAdapter(jdbcUrl));
    }

    /** Testable constructor — inject config and adapter. */
    public ThetaDeltaSenseCheckService(
            ThetaDeltaSenseCheckConfig config,
            HistoricalThetaDeltaAdapter adapter
    ) {
        this.config  = Objects.requireNonNull(config,  "config must not be null");
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Runs the full sense-check pipeline using real wall-clock time for DTE computation.
     *
     * @param request validated user inputs
     * @return fully populated response (never null)
     * @throws IllegalArgumentException if input validation fails
     * @throws SQLException             if the historical backend is unreachable
     */
    public ThetaDeltaSenseCheckResponse runSenseCheck(ThetaDeltaSenseCheckRequest request)
            throws SQLException {
        return runSenseCheck(request, LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
    }

    /**
     * Runs the full sense-check pipeline with an explicit reference date for DTE computation.
     *
     * <p>Use this overload in <strong>simulation mode</strong>: pass the replay date so that
     * the time-to-expiry bucket and historical cohort match the simulated trading day,
     * not today's calendar date.
     *
     * @param request validated user inputs
     * @param asOf    reference date for DTE/time-bucket calculation (use replay date in sim mode)
     * @return fully populated response (never null)
     * @throws IllegalArgumentException if input validation fails
     * @throws SQLException             if the historical backend is unreachable
     */
    public ThetaDeltaSenseCheckResponse runSenseCheck(ThetaDeltaSenseCheckRequest request, LocalDate asOf)
            throws SQLException {

        Objects.requireNonNull(asOf, "asOf must not be null");
        validateInput(request);

        String underlying  = request.underlying().trim().toUpperCase(Locale.ROOT);
        String optionType  = request.optionType().trim().toUpperCase(Locale.ROOT);
        String side        = request.side().trim().toUpperCase(Locale.ROOT);
        LocalDate expiry   = LocalDate.parse(request.expiry().trim());
        LocalDate todayIst = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean isSimulation = !asOf.equals(todayIst);
        String mode = isSimulation ? "simulation" : "live";

        // --- 6.1 Basic moves ---
        double underlyingMove = calculateUnderlyingMove(request);
        double premiumMove    = calculatePremiumMove(request);

        // --- 6.2 Observed delta ---
        double observedDelta        = calculateObservedDelta(premiumMove, underlyingMove);
        double observedDeltaClamped = clampDelta(observedDelta);
        boolean underlyingMoveTooSmall = Math.abs(underlyingMove) < config.minUnderlyingMovePoints();

        // --- Resolve canonical cohort key for historical lookups ---
        // Use the caller-supplied asOf date so simulation mode resolves the correct DTE bucket.
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                underlying, optionType,
                java.math.BigDecimal.valueOf(request.currentUnderlying()),
                java.math.BigDecimal.valueOf(request.strike()),
                dteDaysFromExpiry(expiry, asOf)
        );

        // --- 6.3 Fetch historical average delta ---
        HistoricalThetaDeltaAdapter.DeltaLookupResult deltaLookup =
                adapter.fetchAverageDelta(
                        underlying, optionType,
                        request.strike(), expiry,
                        cohort.timeBucket15m(), cohort.moneynessBucket()
                );

        Double averageHistoricalDelta   = deltaLookup != null ? deltaLookup.averageDelta() : null;
        Integer deltaSampleSize         = deltaLookup != null ? deltaLookup.sampleSize()   : null;
        ThetaDeltaSenseCheckResponse.HistoricalBucket deltaBucket =
                deltaLookup != null ? deltaLookup.bucket() : null;

        // --- 6.4–6.5 Delta deviation and status ---
        Double deltaDeviationPct = calculateDeltaDeviation(observedDelta, averageHistoricalDelta);
        String deltaStatus = classifyDeltaStatus(
                deltaDeviationPct, underlyingMoveTooSmall,
                averageHistoricalDelta, deltaSampleSize
        );

        // --- 6.6 Expected delta move and residual ---
        double avgDeltaForResidual = averageHistoricalDelta != null ? averageHistoricalDelta : 0.0;
        double expectedDeltaMove   = avgDeltaForResidual * underlyingMove;
        double residualPremiumMove = premiumMove - expectedDeltaMove;

        // --- 6.7 Theta benefit ---
        double thetaBenefit = calculateThetaBenefit(side, residualPremiumMove);

        // --- 6.8 Theta benefit per minute ---
        double thetaBenefitPerMin = request.elapsedMinutes() > 0
                ? thetaBenefit / request.elapsedMinutes()
                : 0.0;
        boolean elapsedTooShort = request.elapsedMinutes() < config.minElapsedMinutes();

        // --- 6.9 Fetch historical average theta ---
        HistoricalThetaDeltaAdapter.ThetaLookupResult thetaLookup =
                adapter.fetchAverageThetaBenefitPerMin(
                        underlying, optionType,
                        cohort.timeBucket15m(), cohort.moneynessBucket()
                );

        Double averageThetaBenefitPerMin = thetaLookup != null ? thetaLookup.averageThetaBenefitPerMin() : null;
        Integer thetaSampleSize          = thetaLookup != null ? thetaLookup.sampleSize()                : null;
        ThetaDeltaSenseCheckResponse.HistoricalBucket thetaBucket =
                thetaLookup != null ? thetaLookup.bucket() : null;

        // --- 6.10–6.11 Theta deviation and status ---
        Double thetaDeviationPct = calculateThetaDeviation(thetaBenefitPerMin, averageThetaBenefitPerMin);
        String thetaStatus = classifyThetaStatus(
                thetaDeviationPct, elapsedTooShort,
                averageThetaBenefitPerMin, thetaSampleSize,
                deltaStatus
        );

        // --- 7. Opportunity signal ---
        ThetaDeltaSenseCheckResponse.Signal signal =
                generateOpportunitySignal(deltaStatus, thetaStatus, thetaBenefit);

        // --- 8. Reliability score ---
        ThetaDeltaSenseCheckResponse.Reliability reliability = calculateReliabilityScore(
                deltaStatus, thetaStatus,
                request.elapsedMinutes(), Math.abs(underlyingMove),
                deltaSampleSize, thetaSampleSize
        );

        // --- Logging ---
        logRun(request, underlyingMove, premiumMove, observedDelta, averageHistoricalDelta,
                deltaStatus, thetaBenefit, thetaBenefitPerMin, averageThetaBenefitPerMin,
                thetaStatus, signal.opportunitySignal(), reliability.reliabilityScore());

        // --- Build response ---
        return buildResponse(
                request, underlying, optionType, side, mode, asOf,
                underlyingMove, premiumMove, observedDelta, observedDeltaClamped,
                averageHistoricalDelta, deltaDeviationPct, deltaStatus,
                expectedDeltaMove, residualPremiumMove, thetaBenefit, thetaBenefitPerMin,
                averageThetaBenefitPerMin, thetaDeviationPct, thetaStatus,
                deltaBucket, thetaBucket, deltaSampleSize, thetaSampleSize,
                signal, reliability
        );
    }

    // =========================================================================
    // Validation
    // =========================================================================

    public void validateInput(ThetaDeltaSenseCheckRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        requireNonBlank(request.underlying(), "underlying");
        String u = request.underlying().trim().toUpperCase(Locale.ROOT);
        if (!u.equals("NIFTY") && !u.equals("BANKNIFTY")) {
            throw new IllegalArgumentException("underlying must be NIFTY or BANKNIFTY, got: " + request.underlying());
        }

        requireNonBlank(request.expiry(), "expiry");
        try {
            LocalDate.parse(request.expiry().trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiry must be ISO-8601 date (yyyy-MM-dd): " + request.expiry());
        }

        if (request.strike() <= 0) throw new IllegalArgumentException("strike must be > 0");

        requireNonBlank(request.optionType(), "optionType");
        String ot = request.optionType().trim().toUpperCase(Locale.ROOT);
        if (!ot.equals("CE") && !ot.equals("PE")) {
            throw new IllegalArgumentException("optionType must be CE or PE, got: " + request.optionType());
        }

        requireNonBlank(request.side(), "side");
        String s = request.side().trim().toUpperCase(Locale.ROOT);
        if (!s.equals("LONG") && !s.equals("SHORT")) {
            throw new IllegalArgumentException("side must be LONG or SHORT, got: " + request.side());
        }

        if (request.entryPremium()    <= 0) throw new IllegalArgumentException("entryPremium must be > 0");
        if (request.currentPremium()  <= 0) throw new IllegalArgumentException("currentPremium must be > 0");
        if (request.entryUnderlying() <= 0) throw new IllegalArgumentException("entryUnderlying must be > 0");
        if (request.currentUnderlying() <= 0) throw new IllegalArgumentException("currentUnderlying must be > 0");
        if (request.elapsedMinutes()  <= 0) throw new IllegalArgumentException("elapsedMinutes must be > 0");
    }

    // =========================================================================
    // Calculation methods (package-visible for unit testing)
    // =========================================================================

    double calculateUnderlyingMove(ThetaDeltaSenseCheckRequest request) {
        return request.currentUnderlying() - request.entryUnderlying();
    }

    double calculatePremiumMove(ThetaDeltaSenseCheckRequest request) {
        return request.currentPremium() - request.entryPremium();
    }

    /**
     * Observed delta = premium_move / underlying_move.
     * Sign is preserved as-is — CE typically positive, PE typically negative.
     * Returns 0.0 when underlying move is zero (caller must flag NOT_RELIABLE).
     */
    double calculateObservedDelta(double premiumMove, double underlyingMove) {
        if (underlyingMove == 0.0) return 0.0;
        return premiumMove / underlyingMove;
    }

    double clampDelta(double delta) {
        return Math.max(config.observedDeltaClampMin(), Math.min(config.observedDeltaClampMax(), delta));
    }

    /**
     * Delta deviation percentage from historical average.
     * Returns null when averageHistoricalDelta is null or near zero.
     */
    Double calculateDeltaDeviation(double observedDelta, Double averageHistoricalDelta) {
        if (averageHistoricalDelta == null || Math.abs(averageHistoricalDelta) < 0.001) return null;
        return ((observedDelta - averageHistoricalDelta) / Math.abs(averageHistoricalDelta)) * 100.0;
    }

    String classifyDeltaStatus(
            Double deltaDeviationPct,
            boolean underlyingMoveTooSmall,
            Double averageHistoricalDelta,
            Integer sampleSize
    ) {
        if (underlyingMoveTooSmall) return STATUS_NOT_RELIABLE;
        if (deltaDeviationPct == null) return STATUS_NOT_RELIABLE;
        if (averageHistoricalDelta == null) return STATUS_NOT_RELIABLE;
        if (sampleSize != null && sampleSize < config.minimumSampleSize()) return STATUS_NOT_RELIABLE;

        double abs = Math.abs(deltaDeviationPct);
        if (abs <= config.deltaNormalPct()) return STATUS_NORMAL;

        if (deltaDeviationPct > 0) {
            return abs <= config.deltaHighPct() ? STATUS_HIGH : STATUS_VERY_HIGH;
        } else {
            return abs <= config.deltaHighPct() ? STATUS_LOW : STATUS_VERY_LOW;
        }
    }

    /**
     * For SHORT: positive residual decay (premium fell more than delta explains) = positive benefit.
     * For LONG:  favorable premium residual (premium rose more than delta explains) = positive benefit.
     */
    double calculateThetaBenefit(String side, double residualPremiumMove) {
        return "SHORT".equals(side) ? -residualPremiumMove : residualPremiumMove;
    }

    /**
     * Theta deviation percentage from historical average.
     * Returns null when averageThetaBenefitPerMin is null or near zero.
     */
    Double calculateThetaDeviation(double thetaBenefitPerMin, Double averageThetaBenefitPerMin) {
        if (averageThetaBenefitPerMin == null || Math.abs(averageThetaBenefitPerMin) < 0.0001) return null;
        return ((thetaBenefitPerMin - averageThetaBenefitPerMin) / Math.abs(averageThetaBenefitPerMin)) * 100.0;
    }

    String classifyThetaStatus(
            Double thetaDeviationPct,
            boolean elapsedTooShort,
            Double averageThetaBenefitPerMin,
            Integer sampleSize,
            String deltaStatus
    ) {
        if (elapsedTooShort) return STATUS_NOT_RELIABLE;
        if (thetaDeviationPct == null) return STATUS_NOT_RELIABLE;
        if (averageThetaBenefitPerMin == null) return STATUS_NOT_RELIABLE;
        if (STATUS_NOT_RELIABLE.equals(deltaStatus)) return STATUS_NOT_RELIABLE;
        if (sampleSize != null && sampleSize < config.minimumSampleSize()) return STATUS_NOT_RELIABLE;

        double abs = Math.abs(thetaDeviationPct);
        if (abs <= config.thetaNormalPct()) return STATUS_NORMAL;

        if (thetaDeviationPct > 0) {
            return abs <= config.thetaHighPct() ? STATUS_HIGH : STATUS_VERY_HIGH;
        } else {
            return abs <= config.thetaHighPct() ? STATUS_LOW : STATUS_VERY_LOW;
        }
    }

    // =========================================================================
    // Signal logic (section 7 of spec)
    // =========================================================================

    ThetaDeltaSenseCheckResponse.Signal generateOpportunitySignal(
            String deltaStatus, String thetaStatus, double thetaBenefit
    ) {
        // Priority 1 — NOT_RELIABLE
        if (STATUS_NOT_RELIABLE.equals(deltaStatus) || STATUS_NOT_RELIABLE.equals(thetaStatus)) {
            return new ThetaDeltaSenseCheckResponse.Signal(
                    SIGNAL_NOT_RELIABLE, "Not enough reliable data", "GREY",
                    "Underlying move or elapsed time is too small, or historical average is missing. "
                    + "The signal should not be used."
            );
        }

        // Priority 2 — UNSTABLE (delta very far from historical average)
        if (STATUS_VERY_HIGH.equals(deltaStatus) || STATUS_VERY_LOW.equals(deltaStatus)) {
            return new ThetaDeltaSenseCheckResponse.Signal(
                    SIGNAL_UNSTABLE,
                    "Delta unstable — do not trust theta signal",
                    "RED",
                    "Theta Benefit looks high, but Delta is far away from historical average. "
                    + "This may be directional noise rather than clean premium decay."
            );
        }

        boolean deltaAcceptable = STATUS_NORMAL.equals(deltaStatus)
                || STATUS_HIGH.equals(deltaStatus)
                || STATUS_LOW.equals(deltaStatus);

        // Priority 3 — ATTRACTIVE
        if (STATUS_VERY_HIGH.equals(thetaStatus) && deltaAcceptable && thetaBenefit > 0) {
            return new ThetaDeltaSenseCheckResponse.Signal(
                    SIGNAL_ATTRACTIVE, "Opportunity looks attractive", "GREEN",
                    "Theta Benefit is much higher than historical average while Delta is within "
                    + "acceptable range. This suggests premium decay is favorable after adjusting "
                    + "for expected underlying movement."
            );
        }

        // Priority 4 — MILDLY_ATTRACTIVE
        if (STATUS_HIGH.equals(thetaStatus) && deltaAcceptable && thetaBenefit > 0) {
            return new ThetaDeltaSenseCheckResponse.Signal(
                    SIGNAL_MILDLY_ATTRACTIVE, "Opportunity mildly attractive", "GREEN",
                    "Theta Benefit is above historical average and Delta is within acceptable range. "
                    + "Decay appears favorable but not exceptional."
            );
        }

        // Priority 5 — WEAK
        if (STATUS_LOW.equals(thetaStatus) || STATUS_VERY_LOW.equals(thetaStatus) || thetaBenefit <= 0) {
            return new ThetaDeltaSenseCheckResponse.Signal(
                    SIGNAL_WEAK, "Theta edge is weak", "RED",
                    "Theta Benefit is below historical average. There is no strong decay edge in this setup."
            );
        }

        // Priority 6 — NO_EDGE (theta is NORMAL)
        return new ThetaDeltaSenseCheckResponse.Signal(
                SIGNAL_NO_EDGE, "No special edge", "YELLOW",
                "Theta Benefit is within the normal historical range. "
                + "The setup is unremarkable — neither attractive nor weak."
        );
    }

    // =========================================================================
    // Reliability score (section 8 of spec)
    // =========================================================================

    ThetaDeltaSenseCheckResponse.Reliability calculateReliabilityScore(
            String deltaStatus, String thetaStatus,
            int elapsedMinutes, double absUnderlyingMove,
            Integer deltaSampleSize, Integer thetaSampleSize
    ) {
        int score = 100;
        List<String> warnings = new ArrayList<>();

        if (STATUS_NOT_RELIABLE.equals(deltaStatus)) {
            score -= 40;
            warnings.add("Observed delta is not reliable (underlying move too small or historical data missing)");
        } else if (STATUS_VERY_HIGH.equals(deltaStatus) || STATUS_VERY_LOW.equals(deltaStatus)) {
            score -= 30;
            warnings.add("Observed delta is outside expected historical range");
            warnings.add("Theta benefit should not be trusted when delta is unstable");
        }

        if (STATUS_NOT_RELIABLE.equals(thetaStatus)) {
            score -= 35;
            warnings.add("Theta status is not reliable (elapsed time too short or historical data missing)");
        }

        if (elapsedMinutes < 10) {
            score -= 20;
            warnings.add("Elapsed time is short (< 10 minutes) — theta estimate may be noisy");
        }

        if (absUnderlyingMove < 10.0) {
            score -= 20;
            warnings.add("Underlying move is small (< 10 points) — delta estimate is less precise");
        }

        if ((deltaSampleSize != null && deltaSampleSize < config.minimumSampleSize())
                || (thetaSampleSize != null && thetaSampleSize < config.minimumSampleSize())) {
            score -= 15;
            warnings.add("Historical sample size is below minimum threshold of " + config.minimumSampleSize());
        }

        score = Math.max(0, score);

        String label;
        boolean isReliable;
        if (score >= 80) {
            label = "Reliable";
            isReliable = true;
        } else if (score >= 60) {
            label = "Usable";
            isReliable = true;
        } else if (score >= 40) {
            label = "Weak";
            isReliable = false;
        } else {
            label = "Not Reliable";
            isReliable = false;
        }

        return new ThetaDeltaSenseCheckResponse.Reliability(isReliable, score, label, warnings);
    }

    // =========================================================================
    // Logging
    // =========================================================================

    private void logRun(
            ThetaDeltaSenseCheckRequest request,
            double underlyingMove, double premiumMove,
            double observedDelta, Double averageHistoricalDelta,
            String deltaStatus,
            double thetaBenefit, double thetaBenefitPerMin,
            Double averageThetaBenefitPerMin, String thetaStatus,
            String opportunitySignal, int reliabilityScore
    ) {
        LOG.info(() -> String.format(
                "{\"event\":\"theta_delta_sense_check_run\","
                + "\"underlying\":\"%s\",\"expiry\":\"%s\",\"strike\":%.1f,"
                + "\"option_type\":\"%s\",\"side\":\"%s\","
                + "\"underlying_move\":%.4f,\"premium_move\":%.4f,"
                + "\"observed_delta\":%.4f,\"average_historical_delta\":%s,"
                + "\"delta_status\":\"%s\","
                + "\"theta_benefit\":%.4f,\"theta_benefit_per_min\":%.4f,"
                + "\"average_theta_benefit_per_min\":%s,\"theta_status\":\"%s\","
                + "\"opportunity_signal\":\"%s\",\"reliability_score\":%d}",
                request.underlying(), request.expiry(), request.strike(),
                request.optionType(), request.side(),
                underlyingMove, premiumMove,
                observedDelta,
                averageHistoricalDelta != null ? String.format("%.4f", averageHistoricalDelta) : "null",
                deltaStatus,
                thetaBenefit, thetaBenefitPerMin,
                averageThetaBenefitPerMin != null
                        ? String.format("%.4f", averageThetaBenefitPerMin) : "null",
                thetaStatus,
                opportunitySignal, reliabilityScore
        ));
    }

    // =========================================================================
    // Response builder
    // =========================================================================

    private ThetaDeltaSenseCheckResponse buildResponse(
            ThetaDeltaSenseCheckRequest request,
            String underlying, String optionType, String side, String mode, LocalDate asOf,
            double underlyingMove, double premiumMove,
            double observedDelta, double observedDeltaClamped,
            Double averageHistoricalDelta, Double deltaDeviationPct, String deltaStatus,
            double expectedDeltaMove, double residualPremiumMove,
            double thetaBenefit, double thetaBenefitPerMin,
            Double averageThetaBenefitPerMin, Double thetaDeviationPct, String thetaStatus,
            ThetaDeltaSenseCheckResponse.HistoricalBucket deltaBucket,
            ThetaDeltaSenseCheckResponse.HistoricalBucket thetaBucket,
            Integer deltaSampleSize, Integer thetaSampleSize,
            ThetaDeltaSenseCheckResponse.Signal signal,
            ThetaDeltaSenseCheckResponse.Reliability reliability
    ) {
        ThetaDeltaSenseCheckResponse.InputEcho inputEcho = new ThetaDeltaSenseCheckResponse.InputEcho(
                underlying, request.expiry(), request.strike(), optionType, side,
                request.entryPremium(), request.currentPremium(),
                request.entryUnderlying(), request.currentUnderlying(),
                request.elapsedMinutes(), mode, asOf.toString()
        );

        ThetaDeltaSenseCheckResponse.Calculation calculation =
                new ThetaDeltaSenseCheckResponse.Calculation(
                        round4(underlyingMove), round4(premiumMove),
                        round4(observedDelta), round4(observedDeltaClamped),
                        averageHistoricalDelta != null ? round4(averageHistoricalDelta) : null,
                        deltaDeviationPct != null ? round2(deltaDeviationPct) : null,
                        deltaStatus,
                        round4(expectedDeltaMove), round4(residualPremiumMove),
                        round4(thetaBenefit), round4(thetaBenefitPerMin),
                        averageThetaBenefitPerMin != null ? round4(averageThetaBenefitPerMin) : null,
                        thetaDeviationPct != null ? round2(thetaDeviationPct) : null,
                        thetaStatus,
                        deltaBucket, thetaBucket,
                        deltaSampleSize, thetaSampleSize
                );

        return new ThetaDeltaSenseCheckResponse(inputEcho, calculation, signal, reliability);
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static int dteDaysFromExpiry(LocalDate expiry, LocalDate asOf) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(asOf, expiry);
        return (int) Math.max(0, days);
    }
}
