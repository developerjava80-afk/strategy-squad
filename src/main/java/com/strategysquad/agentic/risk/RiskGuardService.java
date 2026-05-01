package com.strategysquad.agentic.risk;

import com.strategysquad.agentic.signal.SignalSnapshot;
import com.strategysquad.research.PositionSessionSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the current risk posture and returns a {@link RiskGuardSnapshot}.
 *
 * <p>Implements all Phase 4 hard stops. When multiple conditions are active at the
 * same time, the most severe {@link RiskGuardDecision} wins while
 * {@code triggeredConditions} carries every active condition code.
 *
 * <h2>No I/O</h2>
 * <p>All inputs arrive through {@link RiskGuardInput}. This service does not read the
 * database, filesystem, or network directly.
 */
public class RiskGuardService {

    /**
     * Maximum absolute net-delta ratio before {@link RiskGuardDecision#FORCE_REDUCE}
     * is triggered.
     */
    public static final double MAX_NET_DELTA_THRESHOLD = 0.30;

    /**
     * Number of {@link SignalSnapshot.ThetaState#DEFENSIVE_EXIT} legs required to
     * escalate premium expansion from {@link RiskGuardDecision#WARN} to
     * {@link RiskGuardDecision#FORCE_REDUCE}.
     */
    public static final int PREMIUM_EXPANSION_FORCE_REDUCE_LEG_COUNT = 2;

    /**
     * Maximum number of recent commands allowed inside the current churn window
     * before {@link RiskGuardDecision#HALT_SESSION} is triggered.
     */
    public static final int MAX_COMMANDS_PER_CHURN_WINDOW = 3;

    /**
     * Evaluates the supplied risk input against all hard stops.
     *
     * @param input assembled risk input for the current cycle; must not be null
     * @return non-null risk snapshot capturing the winning decision and all active conditions
     */
    public RiskGuardSnapshot evaluate(RiskGuardInput input) {
        if (input == null) {
            throw new IllegalArgumentException("RiskGuardInput must not be null");
        }

        List<Violation> violations = new ArrayList<>();
        boolean sessionActive = input.activeSession().isPresent();
        Map<String, SignalSnapshot> signals = input.signalSnapshots();

        // Stop 1 — Net delta beyond threshold → FORCE_REDUCE
        if (Math.abs(input.netDelta()) > MAX_NET_DELTA_THRESHOLD) {
            violations.add(new Violation(
                    RiskGuardDecision.FORCE_REDUCE,
                    "NET_DELTA_BREACH",
                    String.format("Net delta %.3f exceeds threshold %.2f — forced reduction required",
                            input.netDelta(), MAX_NET_DELTA_THRESHOLD),
                    false, false, false, false, false, false
            ));
        }

        // Stop 2 — Live PnL beyond max loss → FORCE_EXIT
        if (input.livePnl() < -input.maxLossPoints()) {
            violations.add(new Violation(
                    RiskGuardDecision.FORCE_EXIT,
                    "MAX_LOSS_BREACH",
                    String.format("Live PnL %.1f pts breaches max loss limit %.1f pts — forced exit required",
                            input.livePnl(), input.maxLossPoints()),
                    true, false, false, false, false, false
            ));
        }

        // Stop 3 — Premium expansion proxy from theta state
        long defensiveExitCount = signals.values().stream()
                .filter(snapshot -> snapshot.thetaState() == SignalSnapshot.ThetaState.DEFENSIVE_EXIT)
                .count();
        if (defensiveExitCount >= PREMIUM_EXPANSION_FORCE_REDUCE_LEG_COUNT) {
            violations.add(new Violation(
                    RiskGuardDecision.FORCE_REDUCE,
                    "PREMIUM_EXPANDING",
                    String.format("%d legs with expanding premium (DEFENSIVE_EXIT) — forced reduction required",
                            defensiveExitCount),
                    false, true, false, false, false, false
            ));
        } else if (defensiveExitCount == 1) {
            violations.add(new Violation(
                    RiskGuardDecision.WARN,
                    "PREMIUM_EXPANDING",
                    "One leg with expanding premium (DEFENSIVE_EXIT) — monitor closely",
                    false, true, false, false, false, false
            ));
        }

        // Stop 4 — Liquidity absent
        boolean liquidityAbsent = signals.values().stream()
                .anyMatch(snapshot -> snapshot.volumeState() == SignalSnapshot.VolumeState.ABSENT);
        if (liquidityAbsent) {
            violations.add(new Violation(
                    sessionActive ? RiskGuardDecision.FORCE_EXIT : RiskGuardDecision.BLOCK_NEW_ENTRY,
                    "ZERO_BID",
                    sessionActive
                            ? "Liquidity absent (zero volume) on one or more active legs — forced exit required"
                            : "Liquidity absent (zero volume) — new entry blocked",
                    false, false, true, false, false, false
            ));
        }

        // Stop 5 — Data stale
        if (input.lastTickAgeSeconds() > input.staleDataSeconds()) {
            violations.add(new Violation(
                    RiskGuardDecision.BLOCK_NEW_ENTRY,
                    "STALE_DATA",
                    String.format("Market data stale: last tick %ds ago, threshold %ds — new entry blocked",
                            input.lastTickAgeSeconds(), input.staleDataSeconds()),
                    false, false, false, true, false, false
            ));
        }

        // Stop 6 — Total drawdown beyond max loss → FORCE_EXIT
        double totalPnl = input.bookedPnl() + input.livePnl();
        if (totalPnl < -input.maxLossPoints()) {
            violations.add(new Violation(
                    RiskGuardDecision.FORCE_EXIT,
                    "MAX_DRAWDOWN_BREACH",
                    String.format("Total PnL %.1f pts breaches drawdown limit %.1f pts — forced exit required",
                            totalPnl, input.maxLossPoints()),
                    true, false, false, false, false, false
            ));
        }

        // Stop 7 — Adjustment churn detected → HALT_SESSION
        if (input.recentCommandCount() > MAX_COMMANDS_PER_CHURN_WINDOW) {
            violations.add(new Violation(
                    RiskGuardDecision.HALT_SESSION,
                    "CHURN_DETECTED",
                    String.format("Recent command count %d exceeds churn threshold %d within %d minutes — halting session",
                            input.recentCommandCount(), MAX_COMMANDS_PER_CHURN_WINDOW, input.churnWindowMinutes()),
                    false, false, false, false, true, false
            ));
        }

        // Stop 8 — Total lots exceed cap → BLOCK_NEW_ENTRY
        if (input.lotCount() > input.maxLotCap()) {
            violations.add(new Violation(
                    RiskGuardDecision.BLOCK_NEW_ENTRY,
                    "LOT_CAP_BREACH",
                    String.format("Lot count %d exceeds max cap %d — blocking new entry",
                            input.lotCount(), input.maxLotCap()),
                    false, false, false, false, false, true
            ));
        }

        // Stop 9 — Missing required signal data → BLOCK_NEW_ENTRY
        if (missingRequiredSignalData(input)) {
            violations.add(new Violation(
                    RiskGuardDecision.BLOCK_NEW_ENTRY,
                    "MISSING_REQUIRED_SIGNAL_DATA",
                    "Required signal data is missing for one or more open legs — blocking new entry",
                    false, false, false, false, false, false
            ));
        }

        if (violations.isEmpty()) {
            return new RiskGuardSnapshot(
                    input.evaluationTs(),
                    RiskGuardDecision.ALLOW,
                    List.of(),
                    "",
                    input.netDelta(),
                    input.livePnl(),
                    false,
                    false,
                    false,
                    false,
                    false,
                    false
            );
        }

        Violation winning = violations.stream()
                .max(Comparator.comparingInt(v -> v.decision().ordinal()))
                .orElseThrow();
        List<String> triggeredConditions = violations.stream()
                .map(Violation::conditionCode)
                .distinct()
                .toList();

        boolean maxLossBreached = violations.stream().anyMatch(Violation::maxLossBreached);
        boolean premiumExpansionAlert = violations.stream().anyMatch(Violation::premiumExpansionAlert);
        boolean liquidityAlert = violations.stream().anyMatch(Violation::liquidityAlert);
        boolean dataStale = violations.stream().anyMatch(Violation::dataStale);
        boolean churnDetected = violations.stream().anyMatch(Violation::churnDetected);
        boolean lotCapBreached = violations.stream().anyMatch(Violation::lotCapBreached);

        String explanation = winning.explanation();
        if (triggeredConditions.size() > 1) {
            explanation = explanation + " Active conditions: " + triggeredConditions;
        }

        return new RiskGuardSnapshot(
                input.evaluationTs(),
                winning.decision(),
                triggeredConditions,
                explanation,
                input.netDelta(),
                input.livePnl(),
                maxLossBreached,
                premiumExpansionAlert,
                liquidityAlert,
                dataStale,
                churnDetected,
                lotCapBreached
        );
    }

    private static boolean missingRequiredSignalData(RiskGuardInput input) {
        if (input.activeSession().isEmpty()) {
            return false;
        }
        PositionSessionSnapshot session = input.activeSession().get();
        return session.legs().stream()
                .filter(leg -> leg.openQuantity() > 0)
                .anyMatch(leg -> {
                    String instrumentId = leg.instrumentId();
                    if (instrumentId == null || instrumentId.isBlank()) {
                        return true;
                    }
                    SignalSnapshot snapshot = input.signalSnapshots().get(instrumentId);
                    return snapshot == null
                            || snapshot.thetaState() == null
                            || snapshot.volumeState() == null;
                });
    }

    private record Violation(
            RiskGuardDecision decision,
            String conditionCode,
            String explanation,
            boolean maxLossBreached,
            boolean premiumExpansionAlert,
            boolean liquidityAlert,
            boolean dataStale,
            boolean churnDetected,
            boolean lotCapBreached
    ) {
    }
}
