-- V005 — Agentic decision audit table
-- Applied manually (no Flyway runtime). Run once against QuestDB via psql or the
-- QuestDB web console before starting the Decision Agent in any mode.
--
-- Table: agentic_decision_audit
--
-- One row per DecisionCommand produced by DecisionAgent, written BEFORE the command
-- is applied. This satisfies the "audit before action" invariant from developer-notes.md.
--
-- All 17 fields from the Required Audit Trail in agentic-live-trading-decision-loop.md
-- are present. Optional fields (position_session_id, theta_state, theta_progress_ratio,
-- liquidity_score) are nullable — the writer must not throw when these are absent.

CREATE TABLE IF NOT EXISTS agentic_decision_audit (

    -- -------------------------------------------------------------------------
    -- Primary identity
    -- -------------------------------------------------------------------------

    -- Stable UUID for this command, matches DecisionCommand.commandId().
    -- Used as the confirmation token in LIVE_ASSIST mode and as the join key
    -- for downstream analytics queries.
    command_id              VARCHAR         NOT NULL,

    -- UTC instant at which the command was issued.
    -- In simulation mode this is the SimulationClock time, not wall clock.
    timestamp               TIMESTAMP       NOT NULL,

    -- Operating mode: SIMULATION, PAPER, or LIVE_ASSIST.
    mode                    VARCHAR         NOT NULL,

    -- -------------------------------------------------------------------------
    -- State machine context
    -- -------------------------------------------------------------------------

    -- State machine state active when this command was produced.
    -- Examples: EVALUATE_ENTRY, MONITOR, BOOK_PROFIT, RESTART_SCAN.
    -- Written as a string so that new states can be added without a schema change.
    state_machine_state     VARCHAR         NOT NULL,

    -- -------------------------------------------------------------------------
    -- Decision output
    -- -------------------------------------------------------------------------

    -- The action type: ENTER, ADD, REDUCE, SHIFT_STRIKE, HOLD,
    -- BOOK_PROFIT, EXIT_LEG, EXIT_ALL, SKIP.
    command_type            VARCHAR         NOT NULL,

    -- Comma-separated list of instrument IDs selected for entry/adjustment.
    -- Empty string for non-entry commands (HOLD, BOOK_PROFIT, EXIT_ALL, SKIP).
    selected_candidate_ids  VARCHAR         NOT NULL,

    -- ID of the position session targeted, if any.
    -- NULL for ENTER and SKIP commands when no session exists.
    position_session_id     VARCHAR,

    -- -------------------------------------------------------------------------
    -- Market snapshot at decision time
    -- -------------------------------------------------------------------------

    -- Live spot price of the underlying at decision time (NSE index points).
    -- NULL if spot data was unavailable.
    live_spot               DOUBLE,

    -- Net signed delta of the active position before the command is applied.
    -- Dimensionless ratio. Zero when no session is active.
    net_delta_before        DOUBLE          NOT NULL,

    -- Net signed delta expected after the command is applied.
    -- Equal to net_delta_before for HOLD and SKIP.
    net_delta_after         DOUBLE          NOT NULL,

    -- -------------------------------------------------------------------------
    -- Theta / signal context
    -- -------------------------------------------------------------------------

    -- ThetaState of the most relevant signal at decision time.
    -- One of PROFIT_BOOK, HOLD, DEFENSIVE_EXIT.
    -- NULL when no signal is available (pre-entry or all signals stale).
    theta_state             VARCHAR,

    -- Theta progress ratio of the most relevant signal at decision time.
    -- Dimensionless 0.0–1.0+ ratio: fraction of expected theta decay realised.
    -- NULL when no signal is available.
    theta_progress_ratio    DOUBLE,

    -- -------------------------------------------------------------------------
    -- PnL snapshot
    -- -------------------------------------------------------------------------

    -- Unrealised PnL of the active session at decision time (NSE index points).
    -- Zero when no session is active.
    live_pnl                DOUBLE          NOT NULL,

    -- Cumulative realised (booked) PnL for the trading day (NSE index points).
    -- Zero at the start of the day.
    booked_pnl              DOUBLE          NOT NULL,

    -- Liquidity score of the top-ranked candidate at decision time (0.0–1.0).
    -- NULL when no candidates are available.
    liquidity_score         DOUBLE,

    -- -------------------------------------------------------------------------
    -- Risk Guard context
    -- -------------------------------------------------------------------------

    -- Risk Guard decision active when this command was produced.
    -- One of ALLOW, WARN, BLOCK_NEW_ENTRY, FORCE_REDUCE, FORCE_EXIT, HALT_SESSION.
    risk_guard_decision     VARCHAR         NOT NULL,

    -- -------------------------------------------------------------------------
    -- Audit reason (mandatory — must never be blank)
    -- -------------------------------------------------------------------------

    -- Machine-readable reason code.
    -- Examples: PREMIUM_RICH_LOW_DELTA, THETA_TARGET_REACHED, NO_QUALIFIED_CANDIDATES.
    reason_code             VARCHAR         NOT NULL,

    -- Trader-readable explanation for the decision.
    -- Example: "CE 24800 premium 18% above historical average, entering short straddle"
    explanation             VARCHAR         NOT NULL,

    -- true when the Risk Guard overrode the policy's preferred command type.
    overridden_by_risk_guard BOOLEAN        NOT NULL

) TIMESTAMP(timestamp) PARTITION BY DAY WAL;
