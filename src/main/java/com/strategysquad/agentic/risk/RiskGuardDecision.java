package com.strategysquad.agentic.risk;

/**
 * The verdict produced by {@code RiskGuardService} for a given decision cycle.
 *
 * <p>Values are ordered by severity from least restrictive ({@link #ALLOW}) to most
 * restrictive ({@link #HALT_SESSION}). When multiple hard-stop conditions are active
 * simultaneously the highest-severity value wins.
 *
 * <h2>Effect on the Decision Agent</h2>
 * <ul>
 *   <li>{@link #ALLOW} — policy command is applied unchanged.</li>
 *   <li>{@link #WARN} — policy command is applied; a warning annotation is added to
 *       the audit record. Profit booking is still permitted.</li>
 *   <li>{@link #BLOCK_NEW_ENTRY} — any {@code ENTER} or {@code ADD} command is
 *       downgraded to {@code SKIP}. Existing position management continues.</li>
 *   <li>{@link #FORCE_REDUCE} — any command other than a reduce or exit is overridden
 *       to {@code REDUCE}. The Decision Agent sets
 *       {@code overriddenByRiskGuard = true}.</li>
 *   <li>{@link #FORCE_EXIT} — all legs are exited immediately regardless of the
 *       policy command. The session is closed.</li>
 *   <li>{@link #HALT_SESSION} — all legs are exited and the orchestrator transitions
 *       to the {@code HALTED} state. No further commands are processed until an
 *       explicit operator reset.</li>
 * </ul>
 *
 * <h2>Audit requirement</h2>
 * <p>Any value other than {@link #ALLOW} must be accompanied by a non-empty
 * {@code triggered_conditions} list in the accompanying {@link RiskGuardSnapshot}.
 * A Risk Guard decision without condition codes is incomplete.
 */
public enum RiskGuardDecision {

    /**
     * No risk condition is active. The Decision Policy command is applied as-is.
     */
    ALLOW,

    /**
     * A soft risk condition is active (e.g., premium expanding slightly, liquidity
     * thinning). The policy command proceeds but the audit record is annotated.
     * Profit booking is still permitted. New entry is still permitted.
     */
    WARN,

    /**
     * A hard risk condition prevents new entries or additions.
     * Any {@code ENTER} or {@code ADD} command is downgraded to {@code SKIP}.
     * Monitoring, booking, and exits continue normally.
     *
     * <p>Triggered by: data stale beyond threshold, total lots at cap, bid price
     * zero, or missing required signal data.
     */
    BLOCK_NEW_ENTRY,

    /**
     * Net delta or premium expansion has exceeded the critical threshold.
     * The Decision Agent must emit {@code REDUCE} regardless of the policy output.
     * {@code overriddenByRiskGuard} is set to {@code true} in the audit record.
     */
    FORCE_REDUCE,

    /**
     * Maximum loss or drawdown has been breached, or a leg's liquidity has
     * collapsed to zero.
     * The Decision Agent must emit {@code EXIT_ALL}. The session is closed.
     * {@code overriddenByRiskGuard} is set to {@code true} in the audit record.
     */
    FORCE_EXIT,

    /**
     * Adjustment churn has been detected (too many commands in a short window),
     * or the operator has issued a manual halt.
     * The Decision Agent must emit {@code EXIT_ALL} and the orchestrator transitions
     * to the {@code HALTED} state. No further commands are processed until the
     * operator explicitly resets via the live-assist UI or API.
     * {@code overriddenByRiskGuard} is set to {@code true} in the audit record.
     */
    HALT_SESSION
}
