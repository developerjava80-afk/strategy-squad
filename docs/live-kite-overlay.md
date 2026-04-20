# Live Kite Overlay

This document describes the current live-market integration layer for Strategy Squad.

Required pre-task review:

- review [options-strategy-domain-contract.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/options-strategy-domain-contract.md)
- review [developer-notes.md](/abs/path/c:/Users/shiva/OptionAlpha/strategy-squad/docs/developer-notes.md)

## Purpose

The live layer adds current-market state on top of the canonical historical research engine without weakening replayability.

It must:

- persist live `NIFTY` / `BANKNIFTY` spot and option ticks
- enrich live options against live spot at or before tick time
- maintain current-session 15-minute aggregates
- compare live structures against canonical historical baselines
- keep the UI aware of feed status and staleness

It must not:

- merge live data into historical tables
- redefine historical fair-price truth
- move pricing logic into the browser
- become an execution or broker workflow

## Runtime path

1. `KiteLiveConsoleMain`
   - loads `kite.properties`
   - checks for a saved day-scoped token in `kite.local.properties`
   - bootstraps the live-session tables if they do not already exist
   - refreshes today's option universe into `instrument_master`
   - starts the research console with live endpoints
   - starts live quote ingestion only when today's token is available

2. Daily login
   - if no valid token exists for the current trading day, the user is taken to `login.html`
   - the user logs in through the Kite login URL and gets a `request_token`
   - the backend exchanges that `request_token` at `/session/token` using `api_key + api_secret`
   - the first successful login of the day saves the resulting access token locally in ignored file `kite.local.properties`
   - the same token is reused for the rest of the day
   - if Kite returns token expiry, the app requires login again

Required local config:

- create local ignored `kite.properties` from `kite.properties.example`
- fill:
  - `kite.api.key`
  - `kite.api.secret`
  - `kite.user.id`
- `kite.access.token` is optional and usually left blank because the login page now performs the session exchange

Recommended login path:

- start `scripts\start-research-console.bat`
- open `http://localhost:8080/login.html`
- click the Zerodha login link
- if your Kite Connect redirect URL points back to `login.html`, the page will auto-capture `request_token` and auto-submit
- otherwise, copy the `request_token` from the redirect URL and paste it into the login page

3. `KiteInstrumentsDumpJob`
   - downloads the NFO instruments dump
   - filters to `NIFTY` / `BANKNIFTY`
   - normalizes quoted CSV fields before option filtering
   - derives current weekly/monthly expiries from the live dump instead of hard-coded weekday assumptions
   - uses fresh live spot as the ATM baseline for strike-window filtering
   - upserts into `instrument_master`

4. `KiteTickerSession`
   - polls Kite `/quote`
   - normalizes spot and option ticks
   - writes into isolated live raw/enriched/session tables

5. `LiveMarketService`
   - resolves UI-defined legs into the active live contracts
   - computes current live structure net premium
   - materializes `live_structure_snapshot`
   - builds current-session structure trend from `options_live_15m`
   - reuses `StrategyAnalysisService` for live-vs-history comparison
   - hydrates live spot and live leg prices back into the UI input form through the overlay payload

## Tables

Migration:

- `db/migration/V004__live_session_tables.sql`

Live-session tables:

- `spot_live`
- `options_live`
- `options_live_enriched`
- `options_live_15m`
- `live_structure_snapshot`

Historical tables remain untouched:

- `spot_historical`
- `options_historical`
- `options_enriched`
- `options_15m_buckets`
- `options_context_buckets`

## Endpoints

Live endpoints are available only when the console is started through `KiteLiveConsoleMain`.

- `GET /api/live/status`
  - feed status
  - disconnect reason
  - last tick timestamp
  - seconds since last tick
  - subscribed instrument count
  - ticks processed

- `GET /api/live/spot`
  - latest live spot for one underlying or both

- `POST /api/live/structure`
  - current live structure net debit / credit
  - per-leg current quotes
  - structure snapshot persistence

- `POST /api/live/structure-trend`
  - current-session 15-minute structure trend
  - economic premium points by bucket

- `POST /api/live/overlay`
  - combined live status
  - live spot
  - live structure snapshot
  - current-session live trend
  - canonical historical comparison via `EconomicMetrics`

## Current transport note

The current implementation uses Kite `/quote` polling every 2 seconds instead of the Kite WebSocket SDK.

That is a transport-level compromise, not a data-model change:

- live writes still remain isolated
- current-state overlays still remain separate from historical truth
- historical comparison still remains DB-backed and deterministic

## Operating notes

- historical spot should still exist, but live startup now prefers fresh Kite index spot as the ATM baseline for current option-universe selection
- `TOKEN_EXPIRED` status means the saved day token is no longer usable; repeat the login page flow with a fresh `request_token`
- live session buckets are flushed on shutdown and remain session-local
- structure snapshots store trader-readable economic premium points, not raw signed internals
- if live status shows connected spot but no option rows are being written, the likely failure modes are:
  - bad NFO option-universe filtering
  - option raw write datatype mismatch
  - session not restarted after code/schema fixes
