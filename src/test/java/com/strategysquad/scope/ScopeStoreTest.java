package com.strategysquad.scope;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScopeStore} covering:
 * <ul>
 *   <li>JSON round-trip for all four {@link StrikeWindow} variants.</li>
 *   <li>{@link Scope} construction and invariant enforcement.</li>
 *   <li>{@link ScopeStore.StrikeWindowJson} serialisation / deserialisation.</li>
 *   <li>{@link Scope#toScopeId(LocalDate)} key generation.</li>
 *   <li>{@link ScopeValidationException#toJsonBody()} error contract.</li>
 * </ul>
 *
 * <p>Database round-trip tests (JDBC) are intentionally excluded here — the
 * store requires a live QuestDB instance and belong in an integration test.
 * This test class is purely in-memory and runs as part of {@code mvn test}.
 */
class ScopeStoreTest {

    // =========================================================================
    // StrikeWindow JSON round-trip
    // =========================================================================

    @Test
    void atmPct_roundTrip() {
        StrikeWindow original = new StrikeWindow.AtmPct(4.0);
        String json = ScopeStore.StrikeWindowJson.toJson(original);
        StrikeWindow restored = ScopeStore.StrikeWindowJson.fromJson(json);

        assertInstanceOf(StrikeWindow.AtmPct.class, restored);
        assertEquals(4.0, ((StrikeWindow.AtmPct) restored).pct(), 1e-9);
        assertTrue(json.contains("\"kind\":\"ATM_PCT\""));
    }

    @Test
    void atmPoints_roundTrip() {
        StrikeWindow original = new StrikeWindow.AtmPoints(500.0);
        String json = ScopeStore.StrikeWindowJson.toJson(original);
        StrikeWindow restored = ScopeStore.StrikeWindowJson.fromJson(json);

        assertInstanceOf(StrikeWindow.AtmPoints.class, restored);
        assertEquals(500.0, ((StrikeWindow.AtmPoints) restored).points(), 1e-9);
        assertTrue(json.contains("\"kind\":\"ATM_POINTS\""));
    }

    @Test
    void explicitRange_roundTrip() {
        StrikeWindow original = new StrikeWindow.ExplicitRange(24000.0, 25000.0);
        String json = ScopeStore.StrikeWindowJson.toJson(original);
        StrikeWindow restored = ScopeStore.StrikeWindowJson.fromJson(json);

        assertInstanceOf(StrikeWindow.ExplicitRange.class, restored);
        StrikeWindow.ExplicitRange range = (StrikeWindow.ExplicitRange) restored;
        assertEquals(24000.0, range.low(),  1e-9);
        assertEquals(25000.0, range.high(), 1e-9);
        assertTrue(json.contains("\"kind\":\"EXPLICIT_RANGE\""));
    }

    @Test
    void legsOnly_roundTrip() {
        StrikeWindow original = new StrikeWindow.LegsOnly(
                java.util.List.of("INS_NIFTY_20260430_24800_CE", "INS_NIFTY_20260430_24800_PE"));
        String json = ScopeStore.StrikeWindowJson.toJson(original);
        StrikeWindow restored = ScopeStore.StrikeWindowJson.fromJson(json);

        assertInstanceOf(StrikeWindow.LegsOnly.class, restored);
        StrikeWindow.LegsOnly legs = (StrikeWindow.LegsOnly) restored;
        assertEquals(2, legs.instrumentIds().size());
        assertEquals("INS_NIFTY_20260430_24800_CE", legs.instrumentIds().get(0));
        assertEquals("INS_NIFTY_20260430_24800_PE", legs.instrumentIds().get(1));
        assertTrue(json.contains("\"kind\":\"LEGS_ONLY\""));
    }

    @Test
    void unknownKind_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ScopeStore.StrikeWindowJson.fromJson("{\"kind\":\"MADE_UP\",\"x\":1}"));
    }

    @Test
    void blankJson_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ScopeStore.StrikeWindowJson.fromJson("  "));
    }

    // =========================================================================
    // Scope construction and invariant enforcement
    // =========================================================================

    @Test
    void scope_validNifty_constructsSuccessfully() {
        Scope scope = new Scope(
                "NIFTY",
                LocalDate.of(2026, 4, 30),
                ExpiryType.WEEKLY,
                StrategyKind.SHORT_STRANGLE,
                new StrikeWindow.AtmPct(4.0),
                30
        );
        assertEquals("NIFTY", scope.underlying());
        assertEquals(ExpiryType.WEEKLY, scope.expiryType());
        assertEquals(StrategyKind.SHORT_STRANGLE, scope.strategy());
        assertEquals(30, scope.maxCandidates());
    }

    @Test
    void scope_invalidUnderlying_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Scope("SENSEX", LocalDate.of(2026, 4, 30),
                        ExpiryType.WEEKLY, StrategyKind.SHORT_STRANGLE,
                        new StrikeWindow.AtmPct(4.0), 30));
    }

    @Test
    void scope_maxCandidatesExceedsHardCap_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Scope("NIFTY", LocalDate.of(2026, 4, 30),
                        ExpiryType.WEEKLY, StrategyKind.SHORT_STRANGLE,
                        new StrikeWindow.AtmPct(4.0), 101));
    }

    @Test
    void scope_maxCandidatesZero_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Scope("NIFTY", LocalDate.of(2026, 4, 30),
                        ExpiryType.WEEKLY, StrategyKind.SHORT_STRANGLE,
                        new StrikeWindow.AtmPct(4.0), 0));
    }

    @Test
    void scope_of_usesDefaultCandidateCount() {
        Scope scope = Scope.of("BANKNIFTY", LocalDate.of(2026, 4, 30),
                ExpiryType.MONTHLY, StrategyKind.IRON_CONDOR,
                new StrikeWindow.AtmPoints(1000.0));
        assertEquals(Scope.DEFAULT_MAX_CANDIDATES, scope.maxCandidates());
    }

    // =========================================================================
    // Scope ID generation
    // =========================================================================

    @Test
    void toScopeId_weekly_nifty() {
        Scope scope = Scope.of("NIFTY", LocalDate.of(2026, 4, 30),
                ExpiryType.WEEKLY, StrategyKind.SHORT_STRANGLE,
                new StrikeWindow.AtmPct(4.0));
        String id = scope.toScopeId(LocalDate.of(2026, 4, 28));
        assertEquals("S_20260428_NIFTY_20260430_W_001", id);
    }

    @Test
    void toScopeId_monthly_banknifty() {
        Scope scope = Scope.of("BANKNIFTY", LocalDate.of(2026, 4, 30),
                ExpiryType.MONTHLY, StrategyKind.IRON_CONDOR,
                new StrikeWindow.AtmPct(4.0));
        String id = scope.toScopeId(LocalDate.of(2026, 4, 28));
        assertEquals("S_20260428_BANKNIFTY_20260430_M_001", id);
    }

    // =========================================================================
    // StrikeWindow construction invariants
    // =========================================================================

    @Test
    void atmPct_zeroOrNegative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new StrikeWindow.AtmPct(0.0));
        assertThrows(IllegalArgumentException.class, () -> new StrikeWindow.AtmPct(-1.0));
    }

    @Test
    void atmPct_above50_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new StrikeWindow.AtmPct(51.0));
    }

    @Test
    void atmPoints_zeroOrNegative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new StrikeWindow.AtmPoints(0.0));
    }

    @Test
    void explicitRange_lowGeHigh_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new StrikeWindow.ExplicitRange(25000.0, 24000.0));
        assertThrows(IllegalArgumentException.class, () ->
                new StrikeWindow.ExplicitRange(24000.0, 24000.0));
    }

    @Test
    void legsOnly_emptyList_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new StrikeWindow.LegsOnly(java.util.List.of()));
    }

    @Test
    void legsOnly_listIsImmutable() {
        var mutable = new java.util.ArrayList<String>();
        mutable.add("INS_NIFTY_20260430_24800_CE");
        StrikeWindow.LegsOnly legs = new StrikeWindow.LegsOnly(mutable);
        mutable.add("EXTRA");
        // The record's list must not reflect the external mutation.
        assertEquals(1, legs.instrumentIds().size());
    }

    // =========================================================================
    // ScopeValidationException JSON body
    // =========================================================================

    @Test
    void validationException_toJsonBody_withHint() {
        ScopeValidationException ex = new ScopeValidationException(
                "STRIKE_WINDOW_TOO_WIDE",
                "ATM_PCT=15.0 yields 312 strikes; max=100.",
                "Try strikeWindow={\"kind\":\"ATM_PCT\",\"pct\":4.0}");
        String json = ex.toJsonBody();
        assertTrue(json.contains("\"error\":\"STRIKE_WINDOW_TOO_WIDE\""));
        assertTrue(json.contains("\"details\":"));
        assertTrue(json.contains("\"hint\":"));
    }

    @Test
    void validationException_toJsonBody_withoutHint() {
        ScopeValidationException ex = new ScopeValidationException(
                "INVALID_UNDERLYING", "underlying must be NIFTY or BANKNIFTY");
        String json = ex.toJsonBody();
        assertTrue(json.contains("\"error\":\"INVALID_UNDERLYING\""));
        assertFalse(json.contains("\"hint\""));
    }

    @Test
    void validationException_messageIncludesCodeAndDetails() {
        ScopeValidationException ex = new ScopeValidationException(
                "EXPIRY_NOT_IN_MASTER", "Expiry 2026-05-30 not found");
        assertTrue(ex.getMessage().contains("EXPIRY_NOT_IN_MASTER"));
        assertTrue(ex.getMessage().contains("Expiry 2026-05-30 not found"));
    }
}
