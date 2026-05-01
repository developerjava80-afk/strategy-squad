-- V008 — Scope state persistence
-- Applied manually (no Flyway runtime). Run once against QuestDB via psql or the
-- QuestDB web console before activating scope-first instrument loading.
--
-- Table: scope_state
--
-- One row per scope activation per trading day, keyed on (trading_date, user_id).
-- The row is upserted (INSERT + overwrite) whenever the user activates or changes
-- scope via POST /api/scope. DELETE /api/scope removes today's row.
--
-- Day-scoped semantics mirror KiteDailyTokenStore and KiteDailyTokenStore:
--   - On startup, ScopeService.loadActive() reads the most-recent row for today.
--   - If the expiry on that scope is still valid in instrument_master, the scope
--     is restored automatically without requiring a new POST /api/scope.
--   - If the saved expiry has passed, the row is marked stale and the UI scope
--     picker is shown again (activeScope=null, previousScopeStale=true in metadata).
--
-- Column notes:
--   trading_date   — midnight IST of the trading day (designated timestamp)
--   scope_id       — deterministic key: S_<YYYYMMDD>_<UNDERLYING>_<EXPIRY>_<W|M>_<seq>
--   user_id        — reserved for multi-user future; always "default" today
--   underlying     — NIFTY or BANKNIFTY
--   expiry         — chosen expiry matching instrument_master.expiry_date
--   expiry_type    — WEEKLY or MONTHLY; weekly/monthly must never be mixed in a cohort
--   strategy       — e.g. SHORT_STRANGLE, IRON_CONDOR, ANALYSIS_ONLY
--   strike_window  — JSON-encoded StrikeWindow: {"kind":"ATM_PCT","pct":4.0}
--   max_candidates — bounded at activation time; hard cap enforced at 100
--   created_at     — wall-clock UTC when this row was first written today
--   last_active_at — wall-clock UTC of the most recent POST /api/scope for today

CREATE TABLE IF NOT EXISTS scope_state (

    -- -------------------------------------------------------------------------
    -- Primary timestamp (QuestDB designated timestamp column)
    -- -------------------------------------------------------------------------

    -- Midnight IST of the trading day this scope row belongs to.
    -- Used for day-scoped queries: WHERE trading_date = today().
    trading_date            TIMESTAMP       NOT NULL,

    -- -------------------------------------------------------------------------
    -- Scope identity
    -- -------------------------------------------------------------------------

    -- Deterministic scope ID, constructed at activation:
    --   S_<YYYYMMDD>_<UNDERLYING>_<EXPIRYDATE>_<W|M>_<seq>
    -- Example: S_20260428_NIFTY_20260430_W_001
    scope_id                VARCHAR         NOT NULL,

    -- Single-user system today; reserved for future multi-user support.
    -- Always written as "default".
    user_id                 SYMBOL          NOT NULL,

    -- -------------------------------------------------------------------------
    -- Scope parameters (mirrors Scope record in com.strategysquad.scope)
    -- -------------------------------------------------------------------------

    -- Underlying index. One of: NIFTY, BANKNIFTY.
    underlying              SYMBOL          NOT NULL,

    -- Chosen expiry date. Must match an expiry_date value in instrument_master.
    -- Stored as UTC midnight of the expiry date (IST date boundary).
    expiry                  TIMESTAMP       NOT NULL,

    -- WEEKLY or MONTHLY. Weekly and monthly expiries must never be mixed
    -- in the same cohort or metric (domain invariant).
    expiry_type             SYMBOL          NOT NULL,

    -- Strategy kind. Examples: SHORT_STRANGLE, IRON_CONDOR, BULL_PUT_SPREAD,
    -- BEAR_CALL_SPREAD, LONG_STRADDLE, ANALYSIS_ONLY.
    strategy                SYMBOL          NOT NULL,

    -- JSON-encoded StrikeWindow. One of:
    --   {"kind":"ATM_PCT","pct":4.0}
    --   {"kind":"ATM_POINTS","points":500.0}
    --   {"kind":"EXPLICIT_RANGE","low":24000.0,"high":25000.0}
    --   {"kind":"LEGS_ONLY","instrumentIds":["INS_NIFTY_20260430_24800_CE",...]}
    strike_window           VARCHAR         NOT NULL,

    -- Maximum candidates to return from the scanner. Range: 1–100.
    -- Default at activation: 30.
    max_candidates          INT             NOT NULL,

    -- -------------------------------------------------------------------------
    -- Lifecycle timestamps
    -- -------------------------------------------------------------------------

    -- Wall-clock UTC instant when this scope row was first written for today.
    created_at              TIMESTAMP       NOT NULL,

    -- Wall-clock UTC instant of the most recent update to this row today.
    -- Refreshed on every POST /api/scope (scope change or re-activation).
    last_active_at          TIMESTAMP       NOT NULL

) TIMESTAMP(trading_date) PARTITION BY DAY WAL;
