package com.strategysquad.agentic.orchestrator;

import com.strategysquad.agentic.adjustment.AdjustmentAgent;
import com.strategysquad.agentic.booking.ProfitBookingAgent;
import com.strategysquad.agentic.decision.DecisionAgent;
import com.strategysquad.agentic.decision.DecisionCommand;
import com.strategysquad.agentic.liveassist.LiveAssistConfirmationGate;
import com.strategysquad.research.MarketSessionStateResolver;
import com.strategysquad.research.SimulationClock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * State machine that drives a single market-day agentic loop.
 *
 * <p>Transitions through 11 states from pre-open scanning to end-of-day report.
 * Each transition is logged at INFO and persisted to {@code agentic_session_state}
 * via an injected {@link StatePersister}.
 *
 * <p>The {@code HALTED} state is terminal until an explicit {@link #reset()} call.
 * In simulation mode a {@link SimulationClock} is injected as the {@link Clock} seam
 * so time advances predictably without relying on wall-clock time.
 *
 * <h3>State machine summary</h3>
 * <pre>
 *  PRE_OPEN_SCAN  →  WAIT_MARKET_OPEN  →  EVALUATE_ENTRY  →  POSITION_OPEN
 *       ↑                                       |                    ↓
 *  RESTART_SCAN  ←─────────────────────────── MONITOR ──→  ADJUST / BOOK_PROFIT
 *       ↓                  ↑                                        ↓
 *  EVALUATE_ENTRY    WAIT_OPERATOR_CONFIRM ←── (LIVE_ASSIST gate) ─-┤
 *       ↓                                                           ↓
 *  END_OF_DAY  (terminal)                     EXITED → RESTART_SCAN
 *  HALTED      (terminal until reset())
 * </pre>
 */
public final class MarketDayOrchestrator {

    private static final Logger LOG = Logger.getLogger(MarketDayOrchestrator.class.getName());

    // =========================================================================
    // State enum
    // =========================================================================

    /**
     * All states the orchestrator may occupy. The string name is persisted directly
     * to {@code agentic_session_state.state}; do not rename without a migration.
     */
    public enum OrchestratorState {

        /** Build candidate list before market open via {@link com.strategysquad.agentic.scanner.MorningScannerService}. */
        PRE_OPEN_SCAN,

        /** Hold until live data is fresh and the market session is active. */
        WAIT_MARKET_OPEN,

        /** {@link DecisionAgent} reviews candidates; decides ENTER or SKIP. */
        EVALUATE_ENTRY,

        /** Structure accepted; session created by {@link com.strategysquad.agentic.builder.PositionBuilderAgent}. */
        POSITION_OPEN,

        /** Continuous signal, PnL, liquidity, and risk evaluation by {@link DecisionAgent}. */
        MONITOR,

        /** Apply one audited adjustment via {@link AdjustmentAgent}. */
        ADJUST,

        /** Reduce or exit after theta capture via {@link ProfitBookingAgent}. */
        BOOK_PROFIT,

        /** Look for the next entry opportunity via the scanner. */
        RESTART_SCAN,

        /** No active structure remains — triggers restart or end-of-day. */
        EXITED,

        /** Freeze session and write report via {@link com.strategysquad.research.StrategyRunReportService}. Terminal. */
        END_OF_DAY,

        /**
         * Risk or system issue stopped the loop. Terminal until manual {@link MarketDayOrchestrator#reset()}.
         */
        HALTED,

        /**
         * A {@link DecisionCommand} has been submitted to the
         * {@link LiveAssistConfirmationGate} and is awaiting operator confirmation.
         * Only reached in {@code LIVE_ASSIST} mode.
         *
         * <p>The orchestrator stays in this state until the operator confirms,
         * cancels, or the pending command expires. On confirmation the original
         * command is applied; on cancellation or expiry the orchestrator returns
         * to the appropriate scanning or monitoring state.
         */
        WAIT_OPERATOR_CONFIRM
    }

    // =========================================================================
    // Functional interface seams
    // =========================================================================

    /**
     * Time source. In production, delegates to system clock. In simulation mode,
     * a {@link SimulationClock} instance satisfies this interface via a lambda:
     * {@code simulationClock::instant}.
     */
    @FunctionalInterface
    public interface Clock {
        Instant now();
    }

    /**
     * Persists one row to {@code agentic_session_state} after a transition.
     *
     * <p>The seam allows tests to capture persistence calls without a live QuestDB.
     * The production implementation uses a JDBC {@link Connection}.
     */
    @FunctionalInterface
    public interface StatePersister {
        /**
         * Record that the orchestrator transitioned into {@code newState}.
         *
         * @param newState         state just entered
         * @param priorState       state just left; null for the first transition
         * @param elapsedMs        milliseconds spent in {@code priorState}; null for the first row
         * @param lastCommandType  command that triggered this transition, or null
         * @param lastCommandId    UUID of the triggering command, or null
         * @param haltReason       reason code for HALTED transitions, or null
         * @param notes            trader-readable explanation of the transition
         * @throws Exception if the persistence call fails
         */
        void persist(
                OrchestratorState newState,
                OrchestratorState priorState,
                Long elapsedMs,
                String lastCommandType,
                String lastCommandId,
                String haltReason,
                String notes) throws Exception;
    }

    // =========================================================================
    // INSERT SQL (QuestDB, WAL table)
    // =========================================================================

    private static final String INSERT_STATE_SQL =
            "INSERT INTO agentic_session_state " +
            "(transition_ts, run_id, mode, underlying, state, prior_state, elapsed_ms, " +
            " last_command_type, last_command_id, halt_reason, notes) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    // =========================================================================
    // Fields
    // =========================================================================

    private final String runId;
    private final String mode;
    private final String underlying;
    private final DecisionAgent decisionAgent;
    private final ProfitBookingAgent profitBookingAgent;
    private final AdjustmentAgent adjustmentAgent;
    private final Clock clock;
    private final StatePersister statePersister;

    // Mutable state — the orchestrator is single-threaded by design
    private OrchestratorState currentState;
    private Instant stateEnteredAt;

    // Last decision context — exposed for REST status queries
    private DecisionCommand lastCommand;
    private String haltReason;

    // Pending command awaiting operator confirmation (LIVE_ASSIST mode only)
    private String pendingCommandId;
    private DecisionCommand pendingCommand;
    // The state to return to when the pending command comes from the MONITOR path
    // vs the EVALUATE_ENTRY path (different next-state logic)
    private boolean pendingFromMonitor;

    // Optional live-assist confirmation gate — null in SIMULATION and PAPER modes
    private final LiveAssistConfirmationGate gate;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Full constructor.
     *
     * @param mode             operating mode string (SIMULATION, PAPER, or LIVE_ASSIST)
     * @param underlying       the index this orchestrator manages (NIFTY or BANKNIFTY)
     * @param decisionAgent    wired and ready decision agent
     * @param profitBookingAgent wired and ready profit booking agent
     * @param adjustmentAgent  wired and ready adjustment agent
     * @param clock            time source — inject {@code simulationClock::instant} in simulation mode
     * @param statePersister   persistence seam for agentic_session_state rows
     */
    public MarketDayOrchestrator(
            String mode,
            String underlying,
            DecisionAgent decisionAgent,
            ProfitBookingAgent profitBookingAgent,
            AdjustmentAgent adjustmentAgent,
            Clock clock,
            StatePersister statePersister) {

        this(mode, underlying, decisionAgent, profitBookingAgent, adjustmentAgent,
             clock, statePersister, null);
    }

    /**
     * Full constructor with optional {@link LiveAssistConfirmationGate}.
     *
     * @param mode             operating mode string (SIMULATION, PAPER, or LIVE_ASSIST)
     * @param underlying       the index this orchestrator manages (NIFTY or BANKNIFTY)
     * @param decisionAgent    wired and ready decision agent
     * @param profitBookingAgent wired and ready profit booking agent
     * @param adjustmentAgent  wired and ready adjustment agent
     * @param clock            time source — inject {@code simulationClock::instant} in simulation mode
     * @param statePersister   persistence seam for agentic_session_state rows
     * @param gate             live-assist confirmation gate; {@code null} for SIMULATION/PAPER
     *                         (gate is bypassed and commands are auto-approved)
     */
    public MarketDayOrchestrator(
            String mode,
            String underlying,
            DecisionAgent decisionAgent,
            ProfitBookingAgent profitBookingAgent,
            AdjustmentAgent adjustmentAgent,
            Clock clock,
            StatePersister statePersister,
            LiveAssistConfirmationGate gate) {

        this.runId = UUID.randomUUID().toString();
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.underlying = Objects.requireNonNull(underlying, "underlying must not be null");
        this.decisionAgent = Objects.requireNonNull(decisionAgent, "decisionAgent must not be null");
        this.profitBookingAgent = Objects.requireNonNull(profitBookingAgent, "profitBookingAgent must not be null");
        this.adjustmentAgent = Objects.requireNonNull(adjustmentAgent, "adjustmentAgent must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.statePersister = Objects.requireNonNull(statePersister, "statePersister must not be null");
        this.gate = gate; // nullable — null means auto-approve all commands

        // Initial state — the orchestrator does not auto-advance until tick() is called
        this.currentState = OrchestratorState.PRE_OPEN_SCAN;
        this.stateEnteredAt = clock.now();
    }

    /**
     * Convenience constructor for simulation mode. Wires the {@link SimulationClock}
     * as the time source automatically.
     *
     * @param mode             operating mode string
     * @param underlying       the index this orchestrator manages
     * @param decisionAgent    wired and ready decision agent
     * @param profitBookingAgent wired and ready profit booking agent
     * @param adjustmentAgent  wired and ready adjustment agent
     * @param simulationClock  the shared simulation clock
     * @param statePersister   persistence seam for agentic_session_state rows
     */
    public MarketDayOrchestrator(
            String mode,
            String underlying,
            DecisionAgent decisionAgent,
            ProfitBookingAgent profitBookingAgent,
            AdjustmentAgent adjustmentAgent,
            SimulationClock simulationClock,
            StatePersister statePersister) {

        this(mode, underlying, decisionAgent, profitBookingAgent, adjustmentAgent,
             simulationClock::instant, statePersister, null);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the current orchestrator state. Thread-safe for read.
     */
    public OrchestratorState currentState() {
        return currentState;
    }

    /**
     * Returns the run identifier — a UUID that groups all transition rows for this
     * market-day loop instance.
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns the most recent {@link DecisionCommand} produced by the decision agent,
     * or {@code null} if no decision has been made yet.
     */
    public DecisionCommand lastCommand() {
        return lastCommand;
    }

    /**
     * Returns the halt reason code when in {@link OrchestratorState#HALTED} state,
     * or {@code null} otherwise.
     */
    public String haltReason() {
        return haltReason;
    }

    /**
     * Returns the operating mode string (SIMULATION, PAPER, or LIVE_ASSIST).
     */
    public String mode() {
        return mode;
    }

    /**
     * Returns the underlying index this orchestrator manages (NIFTY or BANKNIFTY).
     */
    public String underlying() {
        return underlying;
    }

    /**
     * Returns the command ID string of the command currently awaiting operator
     * confirmation, or {@code null} if there is no pending command.
     */
    public String pendingCommandId() {
        return pendingCommandId;
    }

    /**
     * Confirms the pending command with the given {@code commandId} via the wired
     * {@link LiveAssistConfirmationGate}.
     *
     * <p>The orchestrator does not immediately apply the command — it waits for the
     * next {@link #tick()} which will observe the CONFIRMED status from
     * {@link LiveAssistConfirmationGate#checkStatus(String)} and advance accordingly.
     *
     * @param commandId the UUID string of the pending command to confirm
     * @return the gate result (CONFIRMED, EXPIRED, or NOT_FOUND)
     * @throws IllegalStateException if no gate is wired
     */
    public LiveAssistConfirmationGate.GateResult confirmPending(String commandId) {
        if (gate == null) {
            throw new IllegalStateException(
                    "No LiveAssistConfirmationGate is wired — confirm is not available in " + mode + " mode");
        }
        return gate.confirm(commandId);
    }

    /**
     * Cancels the pending command with the given {@code commandId} via the wired
     * {@link LiveAssistConfirmationGate}.
     *
     * @param commandId the UUID string of the pending command to cancel
     * @return the gate result (CANCELLED, NOT_FOUND, or CONFIRMED if already confirmed)
     * @throws IllegalStateException if no gate is wired
     */
    public LiveAssistConfirmationGate.GateResult cancelPending(String commandId) {
        if (gate == null) {
            throw new IllegalStateException(
                    "No LiveAssistConfirmationGate is wired — cancel is not available in " + mode + " mode");
        }
        return gate.cancel(commandId);
    }

    /**
     * Advances the state machine by exactly one tick (one state entry + potential transition).
     *
     * <p>If the orchestrator is in a terminal state ({@link OrchestratorState#END_OF_DAY}
     * or {@link OrchestratorState#HALTED}), this method returns immediately without
     * transitioning. HALTED can only be exited via {@link #reset()}.
     *
     * <p>Transition failures cause the orchestrator to transition to {@code HALTED} with
     * the reason code {@code INTERNAL_ERROR} so the loop never silently stalls.
     *
     * @return the state the orchestrator is in AFTER this tick
     */
    public OrchestratorState tick() {
        if (currentState == OrchestratorState.END_OF_DAY) {
            return currentState;
        }
        if (currentState == OrchestratorState.HALTED) {
            LOG.warning("[MarketDayOrchestrator] tick() ignored — orchestrator is HALTED. Call reset() first.");
            return currentState;
        }

        try {
            OrchestratorState next = executeCurrentState();
            if (next != currentState) {
                transitionTo(next, null, null, null);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[MarketDayOrchestrator] Unhandled exception in state " + currentState
                    + " — halting orchestrator", e);
            haltOrchestrator("INTERNAL_ERROR", "Unhandled exception in state " + currentState
                    + ": " + e.getMessage());
        }
        return currentState;
    }

    /**
     * Forces the orchestrator into {@link OrchestratorState#HALTED} immediately,
     * regardless of the current state.
     *
     * <p>This method is intended exclusively for the operator emergency-halt UI action.
     * It must never be called from automated agent code paths — halt via agent code must
     * go through the normal {@code tick()} risk-guard path so that the decision audit log
     * is preserved.
     *
     * <p>Idempotent: calling {@code forceHalt} when already in {@code HALTED} updates the
     * halt reason but does not log a second transition.
     *
     * @param reason machine-readable reason code for the halt (e.g., {@code "OPERATOR_HALT"})
     */
    public void forceHalt(String reason) {
        if (currentState == OrchestratorState.HALTED) {
            // Already halted — update the reason in case it changed, but don't re-transition
            this.haltReason = reason != null ? reason : "OPERATOR_HALT";
            return;
        }
        haltOrchestrator(reason != null ? reason : "OPERATOR_HALT",
                "Operator-initiated emergency halt via REST API");
    }

    /**
     * Resets the orchestrator from {@link OrchestratorState#HALTED} back to
     * {@link OrchestratorState#PRE_OPEN_SCAN}.
     *
     * <p>This is the only way to exit the HALTED state. No other code path
     * transitions away from HALTED automatically.
     *
     * @throws IllegalStateException if the orchestrator is not in HALTED state
     */
    public void reset() {
        if (currentState != OrchestratorState.HALTED) {
            throw new IllegalStateException(
                    "reset() may only be called when orchestrator is in HALTED state; current state: "
                    + currentState);
        }
        LOG.info("[MarketDayOrchestrator] Operator reset from HALTED — resuming at PRE_OPEN_SCAN");
        haltReason = null;
        lastCommand = null;
        transitionTo(OrchestratorState.PRE_OPEN_SCAN, null, null, "Operator reset from HALTED");
    }

    // =========================================================================
    // State execution
    // =========================================================================

    /**
     * Dispatches to the handler for the current state and returns the desired next state.
     */
    private OrchestratorState executeCurrentState() throws Exception {
        return switch (currentState) {
            case PRE_OPEN_SCAN        -> executePreOpenScan();
            case WAIT_MARKET_OPEN     -> executeWaitMarketOpen();
            case EVALUATE_ENTRY       -> executeEvaluateEntry();
            case POSITION_OPEN        -> executePositionOpen();
            case MONITOR              -> executeMonitor();
            case ADJUST               -> executeAdjust();
            case BOOK_PROFIT          -> executeBookProfit();
            case RESTART_SCAN         -> executeRestartScan();
            case EXITED               -> executeExited();
            case END_OF_DAY           -> OrchestratorState.END_OF_DAY; // terminal — should not reach
            case HALTED               -> OrchestratorState.HALTED;     // terminal — should not reach
            case WAIT_OPERATOR_CONFIRM -> executeWaitOperatorConfirm();
        };
    }

    /**
     * PRE_OPEN_SCAN — Build the candidate list before market open.
     * In Phase 5A this is a stub: the scanner is called in simulation via DecisionAgent's
     * candidateLoader which is injected at construction time.
     * Transitions to WAIT_MARKET_OPEN once the scan completes (or immediately in simulation).
     */
    private OrchestratorState executePreOpenScan() {
        LOG.info("[MarketDayOrchestrator] PRE_OPEN_SCAN — building candidate list for " + underlying);
        // The actual scan is delegated to the MorningScannerService wired into the
        // DecisionAgent's candidateLoader. The orchestrator advances unconditionally
        // after logging; the heavy scan work happens inside decisionAgent.decide().
        return OrchestratorState.WAIT_MARKET_OPEN;
    }

    /**
     * WAIT_MARKET_OPEN — Hold until the live market session is confirmed active and
     * data is fresh. In simulation mode the SimulationClock is already set to a
     * market-hours instant, so this transitions immediately.
     */
    private OrchestratorState executeWaitMarketOpen() {
        LOG.info("[MarketDayOrchestrator] WAIT_MARKET_OPEN — checking market readiness for " + underlying);
        // Phase 5A: no LiveMarketReadinessService wired yet; advance unconditionally.
        // Phase 5B will wire this to an actual readiness check.
        return OrchestratorState.EVALUATE_ENTRY;
    }

    /**
     * EVALUATE_ENTRY — Ask DecisionAgent whether to enter a position.
     * If the command is ENTER → move to POSITION_OPEN.
     * If SKIP, HOLD, or any risk-guard block → RESTART_SCAN.
     * If a halt-class command is returned → HALTED.
     */
    private OrchestratorState executeEvaluateEntry() {
        LOG.info("[MarketDayOrchestrator] EVALUATE_ENTRY — running decision cycle for " + underlying);

        Instant decisionTs = clock.now();
        DecisionCommand command = decisionAgent.decide(decisionTs);
        lastCommand = command;

        LOG.info("[MarketDayOrchestrator] EVALUATE_ENTRY — decision: " + command.commandType()
                + " | reason: " + command.reasonCode());

        return switch (command.commandType()) {
            case ENTER -> submitOrAdvance(command, OrchestratorState.POSITION_OPEN);
            case EXIT_ALL -> {
                haltOrchestrator("RISK_GUARD_FORCE_EXIT",
                        "Risk guard forced EXIT_ALL during EVALUATE_ENTRY: " + command.reasonCode());
                yield OrchestratorState.HALTED;
            }
            default -> OrchestratorState.RESTART_SCAN;
        };
    }

    /**
     * POSITION_OPEN — The ENTER command has been accepted; the DecisionAgent (via its
     * PlanBuilder / SessionPersister seams) has already created the session snapshot.
     * The orchestrator moves to MONITOR immediately.
     */
    private OrchestratorState executePositionOpen() {
        LOG.info("[MarketDayOrchestrator] POSITION_OPEN — session opened for " + underlying
                + " | command: " + (lastCommand != null ? lastCommand.commandId() : "n/a"));
        return OrchestratorState.MONITOR;
    }

    /**
     * MONITOR — Continuous evaluation by the DecisionAgent each tick.
     * Maps command types to next states:
     * <ul>
     *   <li>HOLD → stay in MONITOR</li>
     *   <li>BOOK_PROFIT → BOOK_PROFIT state (routed through gate in LIVE_ASSIST mode)</li>
     *   <li>SHIFT_STRIKE / ADD / REDUCE / EXIT_LEG → ADJUST (routed through gate in LIVE_ASSIST mode)</li>
     *   <li>EXIT_ALL → EXITED</li>
     *   <li>SKIP → RESTART_SCAN</li>
     *   <li>HALT_SESSION → HALTED (defensive; never emitted by DecisionAgent)</li>
     * </ul>
     */
    private OrchestratorState executeMonitor() {
        LOG.fine("[MarketDayOrchestrator] MONITOR — running decision cycle for " + underlying);

        Instant decisionTs = clock.now();
        DecisionCommand command = decisionAgent.decide(decisionTs);
        lastCommand = command;

        return switch (command.commandType()) {
            case HOLD -> OrchestratorState.MONITOR;
            case BOOK_PROFIT -> submitOrAdvance(command, OrchestratorState.BOOK_PROFIT);
            case SHIFT_STRIKE, ADD, REDUCE, EXIT_LEG -> submitOrAdvance(command, OrchestratorState.ADJUST);
            case EXIT_ALL -> OrchestratorState.EXITED;
            case SKIP -> OrchestratorState.RESTART_SCAN;
            case ENTER -> {
                // Should not happen during MONITOR but treat as no-op
                LOG.warning("[MarketDayOrchestrator] MONITOR received unexpected ENTER command — staying in MONITOR");
                yield OrchestratorState.MONITOR;
            }
            case HALT_SESSION -> {
                // HALT_SESSION is never emitted by DecisionAgent — defensive path only
                LOG.warning("[MarketDayOrchestrator] MONITOR received unexpected HALT_SESSION command — halting");
                haltOrchestrator("HALT_SESSION_IN_MONITOR", "Unexpected HALT_SESSION in MONITOR state");
                yield OrchestratorState.HALTED;
            }
        };
    }

    /**
     * ADJUST — AdjustmentAgent applies the last command's adjustment.
     * After adjustment, returns to MONITOR (or EXITED if all legs closed).
     */
    private OrchestratorState executeAdjust() {
        LOG.info("[MarketDayOrchestrator] ADJUST — applying adjustment for " + underlying
                + " | command: " + (lastCommand != null ? lastCommand.commandType() : "none"));
        // The actual adjustment work is performed by the agent wired into the
        // DecisionAgent's SessionPersister seam or by the caller composing the agents.
        // Phase 5A: the orchestrator records the transition; full wiring is Phase 5B.
        return OrchestratorState.MONITOR;
    }

    /**
     * BOOK_PROFIT — ProfitBookingAgent evaluates the active session and books profit.
     * If full booking occurred → RESTART_SCAN (or EXITED if all legs closed).
     * If partial booking → back to MONITOR.
     */
    private OrchestratorState executeBookProfit() {
        LOG.info("[MarketDayOrchestrator] BOOK_PROFIT — evaluating profit booking for " + underlying);
        // Phase 5A: stub — transitions to RESTART_SCAN after booking.
        // Phase 5B will wire the ProfitBookingAgent.tryBookFromSignals() result.
        return OrchestratorState.RESTART_SCAN;
    }

    /**
     * RESTART_SCAN — Look for the next opportunity. If it is end of day, transition
     * to END_OF_DAY; otherwise go back to EVALUATE_ENTRY.
     */
    private OrchestratorState executeRestartScan() {
        LOG.info("[MarketDayOrchestrator] RESTART_SCAN — scanning for next opportunity for " + underlying);
        // Check whether the exchange has closed for the day. When the clock is at or after
        // MARKET_CLOSE (15:30 IST), end the session rather than re-scanning indefinitely.
        ZonedDateTime ist = clock.now().atZone(MarketSessionStateResolver.EXCHANGE_ZONE);
        if (!ist.toLocalTime().isBefore(MarketSessionStateResolver.MARKET_CLOSE)) {
            LOG.info("[MarketDayOrchestrator] RESTART_SCAN — exchange is closed (" + ist.toLocalTime()
                    + " IST ≥ 15:30) — transitioning to END_OF_DAY");
            return OrchestratorState.END_OF_DAY;
        }
        return OrchestratorState.EVALUATE_ENTRY;
    }

    /**
     * EXITED — No active structure. Log and decide whether to restart or close out.
     */
    private OrchestratorState executeExited() {
        LOG.info("[MarketDayOrchestrator] EXITED — all legs closed for " + underlying);
        return OrchestratorState.RESTART_SCAN;
    }

    /**
     * WAIT_OPERATOR_CONFIRM — polls the {@link LiveAssistConfirmationGate} for the pending
     * command status on each tick.
     *
     * <ul>
     *   <li>CONFIRMED: applies the pending command via {@link #dispatchConfirmedCommand}</li>
     *   <li>EXPIRED / CANCELLED / NOT_FOUND: discards the pending command and
     *       returns to {@link OrchestratorState#RESTART_SCAN}</li>
     *   <li>PENDING: stays in {@link OrchestratorState#WAIT_OPERATOR_CONFIRM}</li>
     * </ul>
     */
    private OrchestratorState executeWaitOperatorConfirm() {
        if (gate == null || pendingCommandId == null) {
            LOG.warning("[MarketDayOrchestrator] WAIT_OPERATOR_CONFIRM with null gate or pendingCommandId"
                    + " — reverting to RESTART_SCAN");
            pendingCommandId = null;
            pendingCommand = null;
            return OrchestratorState.RESTART_SCAN;
        }

        LiveAssistConfirmationGate.GateResult gateResult = gate.checkStatus(pendingCommandId);

        return switch (gateResult.status()) {
            case CONFIRMED -> {
                LOG.info("[MarketDayOrchestrator] WAIT_OPERATOR_CONFIRM — CONFIRMED: " + pendingCommandId);
                DecisionCommand confirmed = pendingCommand;
                pendingCommandId = null;
                pendingCommand = null;
                lastCommand = confirmed;
                yield dispatchConfirmedCommand(confirmed);
            }
            case EXPIRED -> {
                LOG.info("[MarketDayOrchestrator] WAIT_OPERATOR_CONFIRM — EXPIRED: " + pendingCommandId);
                pendingCommandId = null;
                pendingCommand = null;
                yield OrchestratorState.RESTART_SCAN;
            }
            case CANCELLED -> {
                LOG.info("[MarketDayOrchestrator] WAIT_OPERATOR_CONFIRM — CANCELLED: " + pendingCommandId);
                pendingCommandId = null;
                pendingCommand = null;
                yield OrchestratorState.RESTART_SCAN;
            }
            case NOT_FOUND -> {
                LOG.warning("[MarketDayOrchestrator] WAIT_OPERATOR_CONFIRM — NOT_FOUND: " + pendingCommandId
                        + " — reverting to RESTART_SCAN");
                pendingCommandId = null;
                pendingCommand = null;
                yield OrchestratorState.RESTART_SCAN;
            }
            default -> OrchestratorState.WAIT_OPERATOR_CONFIRM; // PENDING — keep waiting
        };
    }

    /**
     * Submits {@code command} through the confirmation gate when one is wired.
     *
     * <p>If the gate returns {@link LiveAssistConfirmationGate.GateStatus#PENDING}, records the
     * pending context and returns {@link OrchestratorState#WAIT_OPERATOR_CONFIRM}.
     * If approved (or no gate), returns {@code approvedNextState}.
     *
     * @param command           the command to submit
     * @param approvedNextState the state to advance to on approval
     * @return the next orchestrator state
     */
    private OrchestratorState submitOrAdvance(DecisionCommand command, OrchestratorState approvedNextState) {
        if (gate != null) {
            LiveAssistConfirmationGate.GateResult result = gate.submit(command);
            if (result.status() == LiveAssistConfirmationGate.GateStatus.PENDING) {
                pendingCommandId = result.commandId();
                pendingCommand = command;
                LOG.info("[MarketDayOrchestrator] LIVE_ASSIST gate PENDING — command: "
                        + command.commandType() + " commandId=" + result.commandId()
                        + " — entering WAIT_OPERATOR_CONFIRM");
                return OrchestratorState.WAIT_OPERATOR_CONFIRM;
            }
        }
        return approvedNextState;
    }

    /**
     * Dispatches a confirmed command to the appropriate next orchestrator state.
     * Maps {@link DecisionCommand.CommandType} to {@link OrchestratorState}.
     *
     * @param cmd the confirmed command
     * @return the next orchestrator state
     */
    private OrchestratorState dispatchConfirmedCommand(DecisionCommand cmd) {
        return switch (cmd.commandType()) {
            case ENTER -> OrchestratorState.POSITION_OPEN;
            case BOOK_PROFIT -> OrchestratorState.BOOK_PROFIT;
            case SHIFT_STRIKE, ADD, REDUCE, EXIT_LEG -> OrchestratorState.ADJUST;
            case EXIT_ALL -> OrchestratorState.EXITED;
            case SKIP, HOLD -> OrchestratorState.RESTART_SCAN;
            case HALT_SESSION -> {
                haltOrchestrator("OPERATOR_CONFIRMED_HALT", "Operator confirmed HALT_SESSION command");
                yield OrchestratorState.HALTED;
            }
        };
    }

    // =========================================================================
    // Transition helpers
    // =========================================================================

    /**
     * Performs a state transition: logs it, updates internal state, and persists a row.
     */
    private void transitionTo(
            OrchestratorState next,
            String lastCommandType,
            String lastCommandId,
            String notes) {

        OrchestratorState previous = currentState;
        Instant now = clock.now();
        long elapsedMs = stateEnteredAt != null
                ? now.toEpochMilli() - stateEnteredAt.toEpochMilli()
                : 0L;

        LOG.info(String.format("[MarketDayOrchestrator] %s → %s | elapsed=%dms | notes=%s",
                previous, next, elapsedMs, notes != null ? notes : ""));

        currentState = next;
        stateEnteredAt = now;

        String cmdType = lastCommandType != null ? lastCommandType
                : (lastCommand != null ? lastCommand.commandType().name() : null);
        String cmdId = lastCommandId != null ? lastCommandId
                : (lastCommand != null ? lastCommand.commandId().toString() : null);

        try {
            statePersister.persist(next, previous, elapsedMs, cmdType, cmdId, haltReason, notes);
        } catch (Exception e) {
            // Persistence failure must never crash the orchestrator loop.
            LOG.log(Level.WARNING,
                    "[MarketDayOrchestrator] Failed to persist state transition " + previous + " → " + next, e);
        }
    }

    /**
     * Transitions to {@link OrchestratorState#HALTED} with a machine-readable reason and notes.
     */
    private void haltOrchestrator(String reason, String notes) {
        this.haltReason = reason;
        transitionTo(OrchestratorState.HALTED, null, null, notes);
        LOG.warning("[MarketDayOrchestrator] HALTED — reason=" + reason + " | " + notes);
    }

    // =========================================================================
    // JDBC StatePersister factory
    // =========================================================================

    /**
     * Functional interface for obtaining a JDBC {@link Connection}.
     *
     * <p>The same pattern used throughout the codebase (e.g., {@link DecisionAgent}).
     */
    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection get() throws Exception;
    }

    /**
     * Creates a production {@link StatePersister} that writes rows to
     * {@code agentic_session_state} via the supplied JDBC connection.
     *
     * @param runId      the run UUID (from {@link #runId()})
     * @param mode       the operating mode string
     * @param underlying the underlying index name
     * @param connSupplier provides a JDBC connection to QuestDB
     * @return a stateless, thread-safe {@link StatePersister}
     */
    public static StatePersister jdbcPersister(
            String runId,
            String mode,
            String underlying,
            ConnectionSupplier connSupplier) {

        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(underlying, "underlying must not be null");
        Objects.requireNonNull(connSupplier, "connSupplier must not be null");

        return (newState, priorState, elapsedMs, lastCommandType, lastCommandId, haltReason, notes) -> {
            try (Connection conn = connSupplier.get();
                 PreparedStatement ps = conn.prepareStatement(INSERT_STATE_SQL)) {

                Timestamp now = Timestamp.from(Instant.now());
                ps.setTimestamp(1, now);                                     // transition_ts
                ps.setString(2, runId);                                      // run_id
                ps.setString(3, mode);                                       // mode
                ps.setString(4, underlying);                                 // underlying
                ps.setString(5, newState.name());                            // state
                ps.setString(6, priorState != null ? priorState.name() : null); // prior_state
                if (elapsedMs != null) ps.setLong(7, elapsedMs);
                else ps.setNull(7, java.sql.Types.BIGINT);                   // elapsed_ms
                ps.setString(8, lastCommandType);                            // last_command_type
                ps.setString(9, lastCommandId);                              // last_command_id
                ps.setString(10, haltReason);                                // halt_reason
                ps.setString(11, notes);                                     // notes

                ps.executeUpdate();
            }
        };
    }
}
