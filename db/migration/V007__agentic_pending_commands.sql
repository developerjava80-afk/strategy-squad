-- V007 — Agentic live-assist pending command log
-- Applied manually (no Flyway runtime). Run once against QuestDB before starting
-- MarketDayOrchestrator in LIVE_ASSIST mode.
--
-- Table: agentic_pending_commands
--
-- Append-only event log for the LiveAssistConfirmationGate. One row per lifecycle
-- event: SUBMITTED, CONFIRMED, CANCELLED, EXPIRED. The gate uses in-memory state
-- as the authoritative source during a live session; this table provides a durable
-- audit trail of every confirmation-gate interaction.
--
-- Do not query this table for current gate state during a live session — the gate's
-- in-memory map is the source of truth for the running orchestrator instance.

CREATE TABLE IF NOT EXISTS agentic_pending_commands (

    -- -------------------------------------------------------------------------
    -- Primary timestamp (QuestDB designated timestamp column)
    -- -------------------------------------------------------------------------

    -- UTC instant this event was recorded. In simulation mode this is the
    -- SimulationClock time; in live-assist mode it is the wall clock.
    event_ts                TIMESTAMP       NOT NULL,

    -- -------------------------------------------------------------------------
    -- Command identity
    -- -------------------------------------------------------------------------

    -- UUID of the pending DecisionCommand this event relates to.
    -- Same as DecisionCommand.commandId(). Used to join with agentic_decision_audit.
    command_id              VARCHAR         NOT NULL,

    -- Run UUID from MarketDayOrchestrator.runId(). Groups events for one market-day
    -- loop.
    run_id                  VARCHAR         NOT NULL,

    -- Operating mode — always LIVE_ASSIST for real pending commands; may be
    -- SIMULATION in test scenarios.
    mode                    VARCHAR         NOT NULL,

    -- Underlying index this command targets (NIFTY or BANKNIFTY).
    underlying              VARCHAR         NOT NULL,

    -- -------------------------------------------------------------------------
    -- Command metadata
    -- -------------------------------------------------------------------------

    -- Type of the recommended action: ENTER, BOOK_PROFIT, ADD, REDUCE, etc.
    command_type            VARCHAR         NOT NULL,

    -- Machine-readable reason code from the DecisionCommand.
    reason_code             VARCHAR         NOT NULL,

    -- Trader-readable explanation from the DecisionCommand.
    explanation             VARCHAR         NOT NULL,

    -- -------------------------------------------------------------------------
    -- Gate lifecycle event
    -- -------------------------------------------------------------------------

    -- One of: SUBMITTED, CONFIRMED, CANCELLED, EXPIRED.
    event_type              VARCHAR         NOT NULL,

    -- UTC instant the pending command was first submitted to the gate.
    -- Repeated on every event row for this command_id (for simpler queries).
    submitted_ts            TIMESTAMP       NOT NULL,

    -- UTC instant at which this command expires if not confirmed by the operator.
    -- Equal to submitted_ts + timeout_seconds.
    expires_at_ts           TIMESTAMP       NOT NULL,

    -- Timeout applied to this command in seconds (configurable at gate construction).
    timeout_seconds         INT             NOT NULL

) TIMESTAMP(event_ts) PARTITION BY DAY WAL;
