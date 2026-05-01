package com.strategysquad.research;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ThetaDeltaSenseCheckService}.
 *
 * All tests use a stub {@link HistoricalThetaDeltaAdapter} to verify calculation
 * correctness without a live database connection.
 *
 * Tests cover all 22 backend cases specified in the feature spec:
 * no Black-Scholes, no Gamma, no Vega, no IV anywhere in the response.
 */
class ThetaDeltaSenseCheckServiceTest {

    // ── Stub adapter ──────────────────────────────────────────────────────────

    private static class StubAdapter extends HistoricalThetaDeltaAdapter {
        private Double avgDelta;
        private Integer deltaSampleSize;
        private Double avgThetaPerMin;
        private Integer thetaSampleSize;

        StubAdapter(Double avgDelta, Integer deltaSampleSize,
                    Double avgThetaPerMin, Integer thetaSampleSize) {
            super("jdbc:stub://unused");
            this.avgDelta       = avgDelta;
            this.deltaSampleSize = deltaSampleSize;
            this.avgThetaPerMin  = avgThetaPerMin;
            this.thetaSampleSize = thetaSampleSize;
        }

        @Override
        public DeltaLookupResult fetchAverageDelta(String underlying, String optionType,
                double strike, LocalDate expiry, int timeBucket15m, int moneynessBucket) {
            if (avgDelta == null) return null;
            ThetaDeltaSenseCheckResponse.HistoricalBucket bucket =
                    new ThetaDeltaSenseCheckResponse.HistoricalBucket(
                            underlying, optionType, "weekly_0_3", "ATM", null, "dte+moneyness");
            return new DeltaLookupResult(avgDelta, deltaSampleSize != null ? deltaSampleSize : 50,
                    "dte+moneyness", bucket);
        }

        @Override
        public ThetaLookupResult fetchAverageThetaBenefitPerMin(String underlying, String optionType,
                int timeBucket15m, int moneynessBucket) {
            if (avgThetaPerMin == null) return null;
            ThetaDeltaSenseCheckResponse.HistoricalBucket bucket =
                    new ThetaDeltaSenseCheckResponse.HistoricalBucket(
                            underlying, optionType, "weekly_0_3", "ATM", null, "dte+moneyness");
            return new ThetaLookupResult(avgThetaPerMin, thetaSampleSize != null ? thetaSampleSize : 50,
                    "dte+moneyness", bucket);
        }
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private static ThetaDeltaSenseCheckRequest req(
            double entryUnderlying, double currentUnderlying,
            double entryPremium, double currentPremium,
            int elapsedMinutes, String side
    ) {
        return ThetaDeltaSenseCheckRequest.of(
                "NIFTY", "2026-04-30", 24500, "CE", side,
                entryPremium, currentPremium,
                entryUnderlying, currentUnderlying, elapsedMinutes
        );
    }

    private ThetaDeltaSenseCheckService svc(Double avgDelta, Double avgThetaPerMin) {
        return svc(avgDelta, 50, avgThetaPerMin, 50);
    }

    private ThetaDeltaSenseCheckService svc(Double avgDelta, Integer deltaSamples,
                                             Double avgThetaPerMin, Integer thetaSamples) {
        return new ThetaDeltaSenseCheckService(
                ThetaDeltaSenseCheckConfig.defaults(),
                new StubAdapter(avgDelta, deltaSamples, avgThetaPerMin, thetaSamples)
        );
    }

    // =========================================================================
    // Test 1 — Underlying move calculation
    // =========================================================================
    @Test
    void test01_underlyingMoveCalculatedCorrectly() throws SQLException {
        var s = svc(0.4, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        assertEquals(45.0, r.calculation().underlyingMove(), 0.001,
                "underlying_move = current - entry");
    }

    // =========================================================================
    // Test 2 — Premium move calculation
    // =========================================================================
    @Test
    void test02_premiumMoveCalculatedCorrectly() throws SQLException {
        var s = svc(0.4, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        assertEquals(-16.0, r.calculation().premiumMove(), 0.001,
                "premium_move = current_premium - entry_premium");
    }

    // =========================================================================
    // Test 3 — Observed delta sign is preserved
    // =========================================================================
    @Test
    void test03_observedDeltaSignPreserved() throws SQLException {
        var s = svc(0.4, 0.45);
        // underlying rose +45, premium fell -16 → negative slope
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        assertTrue(r.calculation().observedDelta() < 0,
                "observed delta must be negative when premium falls and underlying rises");
    }

    // =========================================================================
    // Test 4 — Delta marked NOT_RELIABLE when underlying move < minUnderlyingMovePoints
    // =========================================================================
    @Test
    void test04_deltaNotReliableWhenUnderlyingMoveTooSmall() throws SQLException {
        var s = svc(0.4, 0.45);
        // underlying only moved 2 points (< 5 default threshold)
        var r = s.runSenseCheck(req(24520, 24522, 120, 119, 45, "SHORT"));
        assertEquals(ThetaDeltaSenseCheckService.STATUS_NOT_RELIABLE, r.calculation().deltaStatus(),
                "delta should be NOT_RELIABLE when underlying move < 5 pts");
    }

    // =========================================================================
    // Test 5 — Fetches average historical delta from adapter
    // =========================================================================
    @Test
    void test05_fetchesHistoricalDeltaFromAdapter() throws SQLException {
        var s = svc(0.42, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        assertNotNull(r.calculation().averageHistoricalDelta());
        assertEquals(0.42, r.calculation().averageHistoricalDelta(), 0.001);
    }

    // =========================================================================
    // Test 6 — Delta deviation calculated correctly
    // =========================================================================
    @Test
    void test06_deltaDeviationCalculatedCorrectly() throws SQLException {
        // observed = -16/45 = -0.3556, avg = 0.42
        // deviation = ((-0.3556 - 0.42) / 0.42) * 100 = -185.6%
        var s = svc(0.42, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        assertNotNull(r.calculation().deltaDeviationPct());
        assertTrue(Math.abs(r.calculation().deltaDeviationPct()) > 100,
                "deviation should be large when observed delta is opposite sign to avg delta");
    }

    // =========================================================================
    // Test 7 — Delta status classification
    // =========================================================================
    @Test
    void test07_deltaStatusClassification() throws SQLException {
        // NORMAL: deviation ~0% (observed ≈ avg)
        var s = svc(0.4, 0.45);
        // Make underlying move 50, premium move 20 → observed 0.4 = avg 0.4 → NORMAL
        var r = s.runSenseCheck(req(24500, 24550, 100, 120, 30, "LONG"));
        assertEquals(ThetaDeltaSenseCheckService.STATUS_NORMAL, r.calculation().deltaStatus(),
                "delta should be NORMAL when observed ≈ historical average");

        // VERY_HIGH: deviation > +50%
        var s2 = svc(0.4, 0.45);
        // observed = 30/50 = 0.6, avg = 0.4, deviation = (0.6-0.4)/0.4*100 = +50 → HIGH boundary
        // Let's use bigger difference: observed = 40/50 = 0.8, avg = 0.4, deviation = +100% → VERY_HIGH
        var r2 = s2.runSenseCheck(req(24500, 24550, 100, 140, 30, "LONG"));
        assertEquals(ThetaDeltaSenseCheckService.STATUS_VERY_HIGH, r2.calculation().deltaStatus());
    }

    // =========================================================================
    // Test 8 — Expected delta move uses historical average, not observed delta
    // =========================================================================
    @Test
    void test08_expectedDeltaMoveUsesHistoricalAverage() throws SQLException {
        var s = svc(0.42, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        // expected_delta_move = 0.42 * 45 = 18.9
        assertEquals(18.9, r.calculation().expectedDeltaMove(), 0.01);
    }

    // =========================================================================
    // Test 9 — Residual premium move calculated correctly
    // =========================================================================
    @Test
    void test09_residualPremiumMoveCorrect() throws SQLException {
        var s = svc(0.42, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        // premium_move = -16, expected_delta_move = 18.9
        // residual = -16 - 18.9 = -34.9
        assertEquals(-34.9, r.calculation().residualPremiumMove(), 0.1);
    }

    // =========================================================================
    // Test 10 — For SHORT: positive residual decay → positive theta benefit
    // =========================================================================
    @Test
    void test10_shortPositiveResidualDecayGivesPositiveThetaBenefit() throws SQLException {
        var s = svc(0.42, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        // residual = -34.9, theta_benefit for SHORT = -(-34.9) = +34.9
        assertTrue(r.calculation().thetaBenefit() > 0,
                "SHORT should have positive theta benefit when premium decays more than expected delta");
    }

    // =========================================================================
    // Test 11 — For LONG: favorable premium residual → positive theta benefit
    // =========================================================================
    @Test
    void test11_longFavorableResidualGivesPositiveThetaBenefit() throws SQLException {
        // LONG: underlying up 50, entry 100, current 145 → premium_move = +45
        // avg delta 0.4, expected = 0.4 * 50 = +20
        // residual = 45 - 20 = +25 (favorable for LONG = positive theta benefit)
        var s = svc(0.4, 0.45);
        var r = s.runSenseCheck(req(24500, 24550, 100, 145, 30, "LONG"));
        assertTrue(r.calculation().thetaBenefit() > 0,
                "LONG should have positive theta benefit when premium rises more than expected delta");
    }

    // =========================================================================
    // Test 12 — Theta benefit per minute
    // =========================================================================
    @Test
    void test12_thetaBenefitPerMinute() throws SQLException {
        var s = svc(0.42, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        double expected = r.calculation().thetaBenefit() / 45.0;
        assertEquals(expected, r.calculation().thetaBenefitPerMin(), 0.0001);
    }

    // =========================================================================
    // Test 13 — Fetches average theta from adapter
    // =========================================================================
    @Test
    void test13_fetchesHistoricalThetaFromAdapter() throws SQLException {
        var s = svc(0.42, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        assertNotNull(r.calculation().averageThetaBenefitPerMin());
        assertEquals(0.45, r.calculation().averageThetaBenefitPerMin(), 0.001);
    }

    // =========================================================================
    // Test 14 — Theta deviation calculated correctly
    // =========================================================================
    @Test
    void test14_thetaDeviationCalculatedCorrectly() throws SQLException {
        var s = svc(0.42, 0.45);
        var r = s.runSenseCheck(req(24520, 24565, 120, 104, 45, "SHORT"));
        assertNotNull(r.calculation().thetaDeviationPct());
        // thetaPerMin = thetaBenefit / 45; avg = 0.45
        // deviation = (perMin - 0.45) / 0.45 * 100
        double expectedDev = ((r.calculation().thetaBenefitPerMin() - 0.45) / 0.45) * 100.0;
        assertEquals(expectedDev, r.calculation().thetaDeviationPct(), 1.0);
    }

    // =========================================================================
    // Test 15 — Theta status classification
    // =========================================================================
    @Test
    void test15_thetaStatusClassification() throws SQLException {
        // VERY_HIGH: deviation > +50%
        // theta/min >> avg: e.g. theta 100, elapsed 10 = 10/min, avg 0.45
        // Need: thetaPerMin much higher than avg
        // Let's craft: underlying +50, premium -60, short → theta_benefit = -(residual)
        // avg_delta=0.4, exp_delta_move=20, residual=-60-20=-80, theta=+80, per_min=80/10=8, avg=0.45
        var s = svc(0.4, 0.45);
        var r = s.runSenseCheck(req(24500, 24550, 100, 40, 10, "SHORT"));
        assertEquals(ThetaDeltaSenseCheckService.STATUS_VERY_HIGH, r.calculation().thetaStatus());

        // NORMAL: theta/min ≈ avg (within 20%)
        // Need thetaPerMin ≈ 0.45. e.g. theta=13.5, elapsed=30 → 0.45/min
        // residual = theta for SHORT, so residual = -13.5
        // residual = premiumMove - expectedDeltaMove
        // underlying +50, expectedDelta = 0.4*50=20
        // premiumMove = residual + expected = -13.5 + 20 = 6.5 → current = 100 + 6.5 = 106.5
        var s2 = svc(0.4, 0.45);
        var r2 = s2.runSenseCheck(req(24500, 24550, 100, 106.5, 30, "SHORT"));
        assertEquals(ThetaDeltaSenseCheckService.STATUS_NORMAL, r2.calculation().thetaStatus());
    }

    // =========================================================================
    // Test 16 — ATTRACTIVE when theta very high and delta acceptable
    // =========================================================================
    @Test
    void test16_attractiveSignalWhenThetaVeryHighDeltaAcceptable() throws SQLException {
        // delta NORMAL (observed ≈ avg 0.4): underlying +50, premium +20 → obs_delta=0.4
        // theta VERY_HIGH: premium fell much more than expected after delta adj
        // SHORT: underlying +50, premium fell -60
        // expected_delta = 0.4*50=20, residual = -60-20=-80, theta=+80, per_min=80/10=8
        // avg=0.45, dev=(8-0.45)/0.45*100=+1678% → VERY_HIGH
        var s = svc(0.4, 0.45);
        var r = s.runSenseCheck(req(24500, 24550, 100, 40, 10, "SHORT"));
        // delta obs = -60/50 = -1.2, avg=0.4, dev=(−1.2−0.4)/0.4*100=−400% → VERY_LOW → UNSTABLE
        // Need delta to be NORMAL: let's keep underlying the same but adjust delta manually
        // Better: use small delta mismatch + very high theta
        // underlying +50, premium -5 for delta (obs_delta=-0.1 vs avg 0.4 → -125% → VERY_LOW)
        // To get delta NORMAL, obs must ≈ avg (0.4). underlying+50, premium must move +20
        // But for SHORT theta benefit we need premium to fall.
        // Solution: obs_delta +0.4 (CE ATM) and theta benefit still high
        // underlying +50, CE obs needs +20 for delta NORMAL
        // premium_move = +20, but we want theta benefit > 0 for SHORT
        // theta_benefit(SHORT) = -(premium_move - exp_delta) = -(20 - 20) = 0 → NOT attractive
        // We need premium move LESS than expected delta: e.g. premium +15, expected +20
        // theta = -(15-20) = +5, per_min = 5/10 = 0.5, avg=0.45, dev=+11% → NORMAL not VERY_HIGH
        // Let's use: underlying +50, exp_delta=20, premium +0 → residual=-20, theta=+20, per_min=20/10=2
        // dev=(2-0.45)/0.45*100=+344% → VERY_HIGH; delta_obs=0/50=0 vs avg=0.4, dev=-100% → VERY_LOW → UNSTABLE
        // The issue: to have delta NORMAL, obs ≈ avg.  For SHORT theta benefit, premium must drop relative to expected.
        // Solution with LONG: underlying +50, premium +25 (obs=0.5 vs avg=0.4 → +25% HIGH/NORMAL boundary)
        // theta(LONG) = residual = 25 - 20 = +5, per_min=5/5=1, dev=(1-0.45)/0.45*100=+122% → VERY_HIGH
        var sLong = svc(0.4, 0.45);
        var rLong = sLong.runSenseCheck(req(24500, 24550, 100, 125, 5, "LONG"));
        assertEquals(ThetaDeltaSenseCheckService.SIGNAL_ATTRACTIVE, rLong.signal().opportunitySignal());
    }

    // =========================================================================
    // Test 17 — UNSTABLE when delta is VERY_HIGH or VERY_LOW
    // =========================================================================
    @Test
    void test17_unstableWhenDeltaVeryHighOrVeryLow() throws SQLException {
        var s = svc(0.4, 0.45);
        // obs_delta = -60/50 = -1.2 vs avg 0.4 → very far → VERY_LOW → UNSTABLE
        var r = s.runSenseCheck(req(24500, 24550, 100, 40, 10, "SHORT"));
        assertEquals(ThetaDeltaSenseCheckService.SIGNAL_UNSTABLE, r.signal().opportunitySignal());
        assertEquals("RED", r.signal().color());
    }

    // =========================================================================
    // Test 18 — WEAK when theta benefit is poor
    // =========================================================================
    @Test
    void test18_weakWhenThetaBenefitPoor() throws SQLException {
        // delta NORMAL (obs ≈ avg 0.4): underlying +50, CE long
        // theta LOW: premium gained but less than historical avg theta suggests
        // LONG: underlying +50, premium +20 (obs=0.4=avg), residual=20-20=0, theta=0 → WEAK (≤0)
        var s = svc(0.4, 0.45);
        var r = s.runSenseCheck(req(24500, 24550, 100, 120, 10, "LONG"));
        assertEquals(ThetaDeltaSenseCheckService.SIGNAL_WEAK, r.signal().opportunitySignal());
    }

    // =========================================================================
    // Test 19 — NOT_RELIABLE when historical average is missing
    // =========================================================================
    @Test
    void test19_notReliableWhenHistoricalAverageMissing() throws SQLException {
        // No historical data for either delta or theta
        var s = svc(null, null);
        var r = s.runSenseCheck(req(24500, 24550, 100, 120, 10, "SHORT"));
        assertEquals(ThetaDeltaSenseCheckService.SIGNAL_NOT_RELIABLE, r.signal().opportunitySignal());
        assertEquals("GREY", r.signal().color());
    }

    // =========================================================================
    // Test 20 — Reliability score decreases for poor sample size
    // =========================================================================
    @Test
    void test20_reliabilityScoreDecreasesForSmallSampleSize() throws SQLException {
        var sGood = svc(0.4, 50, 0.45, 50);
        var rGood = sGood.runSenseCheck(req(24500, 24550, 100, 120, 30, "LONG"));

        var sBad = svc(0.4, 5, 0.45, 5);
        var rBad = sBad.runSenseCheck(req(24500, 24550, 100, 120, 30, "LONG"));

        assertTrue(rGood.reliability().reliabilityScore() > rBad.reliability().reliabilityScore(),
                "reliability score should be lower when sample size is below minimum");
    }

    // =========================================================================
    // Test 21 — No Black-Scholes dependency (no IV, Gamma, Vega in response)
    // =========================================================================
    @Test
    void test21_noBlackScholesFieldsInResponse() throws SQLException {
        var s = svc(0.4, 0.45);
        var r = s.runSenseCheck(req(24500, 24550, 100, 120, 30, "SHORT"));
        // Verify via JSON serialization (check toString representations)
        String calcStr = r.calculation().toString();
        assertFalse(calcStr.toLowerCase().contains("iv"),
                "No IV field should appear in calculation");
        assertFalse(calcStr.toLowerCase().contains("gamma"),
                "No Gamma field should appear in calculation");
        assertFalse(calcStr.toLowerCase().contains("vega"),
                "No Vega field should appear in calculation");
        assertFalse(calcStr.toLowerCase().contains("blackscholes"),
                "No Black-Scholes reference should appear in calculation");
    }

    // =========================================================================
    // Test 22 — No Gamma/Vega/IV fields in ThetaDeltaSenseCheckResponse class
    // =========================================================================
    @Test
    void test22_noBlackScholesInResponseClass() {
        // Verify at the class level — no field names containing gamma, vega, iv, blackscholes
        for (java.lang.reflect.RecordComponent rc : ThetaDeltaSenseCheckResponse.Calculation.class.getRecordComponents()) {
            String name = rc.getName().toLowerCase();
            assertFalse(name.contains("gamma"),   "Gamma must not appear in Calculation: " + rc.getName());
            assertFalse(name.contains("vega"),    "Vega must not appear in Calculation: " + rc.getName());
            assertFalse(name.contains("volatility"), "Volatility must not appear in Calculation: " + rc.getName());
        }
    }

    // =========================================================================
    // Test — Validation: missing/invalid inputs throw IllegalArgumentException
    // =========================================================================
    @Test
    void testValidation_invalidUnderlying() {
        var s = svc(0.4, 0.45);
        assertThrows(IllegalArgumentException.class, () ->
                s.runSenseCheck(new ThetaDeltaSenseCheckRequest(
                        "SENSEX", "2026-04-30", 24500, "CE", "SHORT",
                        120, 104, 24520, 24565, 45, null, null, null)));
    }

    @Test
    void testValidation_entryPremiumZero() {
        var s = svc(0.4, 0.45);
        assertThrows(IllegalArgumentException.class, () ->
                s.runSenseCheck(new ThetaDeltaSenseCheckRequest(
                        "NIFTY", "2026-04-30", 24500, "CE", "SHORT",
                        0, 104, 24520, 24565, 45, null, null, null)));
    }

    @Test
    void testValidation_elapsedMinutesZero() {
        var s = svc(0.4, 0.45);
        assertThrows(IllegalArgumentException.class, () ->
                s.runSenseCheck(new ThetaDeltaSenseCheckRequest(
                        "NIFTY", "2026-04-30", 24500, "CE", "SHORT",
                        120, 104, 24520, 24565, 0, null, null, null)));
    }

    // =========================================================================
    // Test — Config defaults are sane
    // =========================================================================
    @Test
    void testConfigDefaults() {
        ThetaDeltaSenseCheckConfig cfg = ThetaDeltaSenseCheckConfig.defaults();
        assertEquals(5.0, cfg.minUnderlyingMovePoints());
        assertEquals(5, cfg.minElapsedMinutes());
        assertEquals(30, cfg.minimumSampleSize());
        assertEquals(20.0, cfg.deltaNormalPct());
        assertEquals(50.0, cfg.deltaHighPct());
        assertEquals(-1.5, cfg.observedDeltaClampMin());
        assertEquals(1.5, cfg.observedDeltaClampMax());
    }

    // =========================================================================
    // Test — Reliability label mapping
    // =========================================================================
    @Test
    void testReliabilityLabels() throws SQLException {
        // Score >= 80 → Reliable: good data, adequate underlying move
        var s = svc(0.4, 50, 0.45, 50);
        // underlying +50, elapsed 30, delta NORMAL, theta NORMAL
        var r = s.runSenseCheck(req(24500, 24550, 100, 120, 30, "LONG"));
        assertNotNull(r.reliability().reliabilityLabel());
        assertTrue(List.of("Reliable", "Usable", "Weak", "Not Reliable")
                .contains(r.reliability().reliabilityLabel()));
    }


    // =========================================================================
    // Test 23 — Theta residual uses historical average delta, not observed delta
    // =========================================================================
    @Test
    void test23_thetaResidualUsesHistoricalAvgDeltaNotObservedDelta() throws SQLException {
        // underlying moves +50, premium moves -30
        // observed delta = -30/50 = -0.60 (far from historical avg 0.40)
        // expectedDeltaMove must use historical 0.40, NOT observed -0.60
        //   => expectedDeltaMove = 0.40 * 50 = 20.0
        //   => residualPremiumMove = -30 - 20.0 = -50.0
        //   => thetaBenefit (SHORT) = -(-50.0) = +50.0
        var s = svc(0.40, 0.45);
        var r = s.runSenseCheck(
                ThetaDeltaSenseCheckRequest.of(
                        "NIFTY", "2026-04-30", 24500, "CE", "SHORT",
                        120, 90,      // premiumMove = -30
                        24500, 24550, // underlyingMove = +50
                        30
                )
        );
        assertEquals(50.0, r.calculation().underlyingMove(), 0.01,
                "underlyingMove must be +50");
        assertEquals(-30.0, r.calculation().premiumMove(), 0.01,
                "premiumMove must be -30");
        assertEquals(20.0, r.calculation().expectedDeltaMove(), 0.01,
                "expectedDeltaMove must use historical avg delta 0.40, not observed delta -0.60");
        assertEquals(-50.0, r.calculation().residualPremiumMove(), 0.01,
                "residualPremiumMove = -30 - 20.0 = -50.0");
        assertEquals(50.0, r.calculation().thetaBenefit(), 0.01,
                "thetaBenefit (SHORT) = -residualPremiumMove = +50.0");
    }

    // =========================================================================
    // Test 24 — Delta instability overrides attractive theta signal
    // =========================================================================
    @Test
    void test24_deltaInstabilityOverridesAttractiveTheta() throws SQLException {
        // underlying +50, premium +60 => observedDelta = +60/50 = +1.20
        // historicalAvgDelta = 0.40 => deviation = (1.20-0.40)/0.40*100 = +200% => VERY_HIGH
        // Even though theta numbers may look large, signal must be UNSTABLE not ATTRACTIVE
        var s = svc(0.40, 0.45);
        var r = s.runSenseCheck(
                ThetaDeltaSenseCheckRequest.of(
                        "NIFTY", "2026-04-30", 24500, "CE", "SHORT",
                        120, 180,     // premiumMove = +60
                        24500, 24550, // underlyingMove = +50
                        30
                )
        );
        assertEquals(ThetaDeltaSenseCheckService.STATUS_VERY_HIGH,
                r.calculation().deltaStatus(),
                "deltaStatus must be VERY_HIGH when observedDelta deviates +200% from historical");
        assertEquals(ThetaDeltaSenseCheckService.SIGNAL_UNSTABLE,
                r.signal().opportunitySignal(),
                "UNSTABLE must override any theta signal when delta is VERY_HIGH");
        assertEquals("RED", r.signal().color(),
                "color must be RED for UNSTABLE signal");
    }
}
