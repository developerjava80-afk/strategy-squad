-- V006 — Agentic session state persistence
-- Applied manually (no Flyway runtime). Run once against QuestDB via psql or the
-- QuestDB web console before starting MarketDayOrchestrator in any mode.
--
-- Table: agentic_session_state
--
-- One row per state transition produced by MarketDayOrchestrator. Written
-- AFTER the transition has been logged so that each row is a fact: the orchestrator
-- entered state X at timestamp T and the prior state was Y.
--
-- This table is the single source of truth for replaying a market-day loop.
-- It is never mutated after insert — corrections are new rows.
--
-- Column notes:
--   state         — the state the orchestrator just transitioned INTO
--   prior_state   — the state just left (NULL for the very first transition)
--   elapsed_ms    — wall-clock millis from prior_state entry to this row
--   mode          — SIMULATION, PAPER, or LIVE_ASSIST
--   underlying    — NIFTY or BANKNIFTY
--   last_command_type  — command type of the most recent DecisionCommand, if any
--   last_command_id    — UUID of the most recent DecisionCommand, if any
--   halt_reason   — non-NULL only when state = HALTED; machine-readable stop code
--   notes         — trader-readable summary of what triggered the transition

CREATE TABLE IF NOT EXISTS agentic_session_state (

    -- -------------------------------------------------------------------------
    -- Primary timestamp (QuestDB designated timestamp column)
    -- -------------------------------------------------------------------------

    -- UTC instant at which the orchestrator entered the new state.
    -- In simulation mode this is the SimulationClock time, not wall clock.
    transition_ts           TIMESTAMP       NOT NULL,

    -- -------------------------------------------------------------------------
    -- State identity
    -- -------------------------------------------------------------------------

    -- Unique ID for the orchestrator run (day-level UUID).
    -- Groups all transitions for a single market-day loop run.
    run_id                  VARCHAR         NOT NULL,

    -- Operating mode: SIMULATION, PAPER, or LIVE_ASSIST.
    mode                    VARCHAR         NOT NULL,

    -- Underlying this orchestrator manages. One of NIFTY, BANKNIFTY.
    underlying              VARCHAR         NOT NULL,

    -- -------------------------------------------------------------------------
    -- State transition
    -- -------------------------------------------------------------------------

    -- The state the orchestrator just transitioned INTO.
    -- One of: PRE_OPEN_SCAN, WAIT_MARKET_OPEN, EVALUATE_ENTRY, POSITION_OPEN,
    --         MONITOR, ADJUST, BOOK_PROFIT, RESTART_SCAN, EXITED,
    --         END_OF_DAY, HALTED.
    state                   VARCHAR         NOT NULL,

    -- The state just exited. NULL for the first row of a run (initial transition).
    prior_state             VARCHAR,

    -- Wall-clock milliseconds elapsed in the prior state before this transition.
    -- NULL for the first row of a run.
    -- In simulation mode this reflects simulated-time elapsed, not wall clock.
    elapsed_ms              LONG,

    -- -------------------------------------------------------------------------
    -- Last decision context at transition time
    -- -------------------------------------------------------------------------

    -- Command type of the most recent DecisionCommand that triggered this
    -- transition, if any. NULL for time-driven transitions (e.g., market open).
    last_command_type       VARCHAR,

    -- UUID of the most recent DecisionCommand. NULL when no command is involved.
    last_command_id         VARCHAR,

    -- -------------------------------------------------------------------------
    -- Halt context (HALTED state only)
    -- -------------------------------------------------------------------------

    -- Machine-readable reason code for why the session was halted.
    -- Non-NULL only when state = HALTED.
    -- Examples: CHURN_DETECTED, MAX_LOSS_BREACH, OPERATOR_HALT.
    halt_reason             VARCHAR,

    -- -------------------------------------------------------------------------
    -- Human-readable summary
    -- -------------------------------------------------------------------------

    -- Short trader-readable description of what triggered this state transition.
    -- Example: "Theta capture 82% — full profit booked, restarting scanner."
    notes                   VARCHAR

) TIMESTAMP(transition_ts) PARTITION BY DAY WAL;
