# Dashboard and Strategy Lab Integration Review

Review date: 2026-04-29  
Scope reviewed: `ui/trading-platform-prototype`, `ResearchConsoleServer`, live market services, scope/scanner services, report service, and QuestDB migrations.

## Section 1: Executive Summary

Overall integration status: Not Ready.

Data trust level: Medium for backend live-price primitives, Low for the current Dashboard and Strategy Lab user workflows. The backend contains useful live market, canonical price, scope, scanner, and report services, but the current UI does not consistently consume those canonical surfaces. Several requested Strategy Lab workflow items are not implemented in the active page.

Major blockers:

- Scope activation from the UI does not send `spotEstimate`; ATM percent/points scopes resolve around 0.0 on the backend, producing empty or incorrect universes.
- Strategy Lab does not implement Run Analysis, canonical `analysisRunId`, entry-price locking, LTP vs entry MTM, total premium, confidence/recommendation output, or report generation.
- Strategy Lab signal data bypasses the canonical price resolver and reads `options_live_enriched` / `spot_live` directly, so post-close official close, prior close, and unavailable states are not represented.
- Stale thresholds are inconsistent with the product requirement. UI scan polling is 30 seconds, backend signal stale threshold is 120 seconds, canonical live-price stale threshold is 30 seconds, while the requested limit is 2 seconds.
- The report service writes a markdown report from caller-provided JSON, but the Strategy Lab UI does not call it and no canonical backend snapshot binds UI, report, and recommendation together.
- Dashboard position PnL uses only `bookedPnl` from file-backed position sessions, not live MTM from locked entry versus live price.

Product framing:

The system should remain a historically grounded strategy-ranking engine, not a black-box optimizer. Recommendation output should be deterministic ranking over a controlled candidate set with evidence from live prices and historical cohorts. Existing delta/ADD/REDUCE live adjustment logic should remain auditable; additional theta logic should be scoped to signal snapshots and reports without destabilizing replay or core UI flows.

Final readiness verdict: Not Ready.

## Section 2: Dashboard Screen Review

Primary UI: `ui/trading-platform-prototype/index.html`

Primary APIs:

- `GET /api/live/spot?underlying=NIFTY`
- `GET /api/live/spot?underlying=BANKNIFTY`
- `GET /api/position-sessions`
- `GET /api/scope/candidates`
- fallback `GET /api/agentic/scanner/candidates`
- `POST /api/scope` through `scope-picker.js`
- `DELETE /api/scope` through `scope-picker.js`

Field mapping:

| Screen | UI Element | API Endpoint | DB Source | Market Source | Refresh Type | Status | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Dashboard | Session card: mode/state/detail | `/api/position-sessions` | file store `run/position-sessions/*.json` | none | 2s poll | Partially Integrated | Shows backend connected when position endpoint succeeds; does not use `/api/live/status` or feed freshness. |
| Dashboard | Pause Polling button | client timer only | none | none | user toggle | Fully Integrated | Stops UI polling only, not backend feed. |
| Dashboard | Run Scan button | `/api/scope/candidates`, fallback `/api/agentic/scanner/candidates` | `scope_state`, `instrument_master`, `options_live_enriched`, `options_context_buckets` | Kite ticks persisted to live DB | manual plus 8s poll | Partially Integrated | Error handling silently keeps old table; fallback may scan outside active scope. |
| Dashboard | NIFTY Spot | `/api/live/spot?underlying=NIFTY` | `spot_live`, `spot_historical` through canonical resolver | `LiveSessionState` / Kite quote cache | 2s poll | Fully Integrated | Source and stale state are shown, but stale threshold is 30s, not 2s. |
| Dashboard | NIFTY Spot subtitle | same | same | same | 2s poll | Partially Integrated | Displays `sessionState . source`; does not display `priceType`, `asOf`, or "Official Close"/"Prior Close" labels clearly. |
| Dashboard | BANKNIFTY Spot | `/api/live/spot?underlying=BANKNIFTY` | `spot_live`, `spot_historical` through canonical resolver | `LiveSessionState` / Kite quote cache | 2s poll | Fully Integrated | Same stale threshold mismatch as NIFTY. |
| Dashboard | BANKNIFTY Spot subtitle | same | same | same | 2s poll | Partially Integrated | Same status-label gap as NIFTY. |
| Dashboard | Net PnL session | `/api/position-sessions` | file store only | none | 2s poll | Partially Integrated | Sums `bookedPnl` only; no live MTM from current prices. |
| Dashboard | Open Legs | `/api/position-sessions` | file store only | none | 2s poll | Partially Integrated | Counts open file-backed legs; not reconciled to broker/order DB or live market state. |
| Dashboard | Active Position title/status | `/api/position-sessions` | file store only | none | 2s poll | Partially Integrated | Does not show snapshot freshness or market-data health. |
| Dashboard | Active Position leg table: leg | `/api/position-sessions` | file store only | none | 2s poll | Partially Integrated | Label is persisted/manual session data; no canonical instrument validation in UI. |
| Dashboard | Active Position leg table: entry | `/api/position-sessions` | file store only | none | 2s poll | Partially Integrated | Entry price is persisted in session JSON; no visible locked-entry audit timestamp. |
| Dashboard | Active Position leg table: qty | `/api/position-sessions` | file store only | none | 2s poll | Partially Integrated | Quantity source is session JSON; no DB-backed order reconciliation. |
| Dashboard | Active Position leg table: PnL (pts) | `/api/position-sessions` | file store only | none | 2s poll | Stale Data Risk | Uses `bookedPnl`, not live PnL or MTM. Header implies position PnL but value may exclude open-leg MTM. |
| Dashboard | Agent State card | none | none | none | none | Mock / Hardcoded | Static Phase 1 overlay; not connected to `/api/agentic/status` or `/api/agentic/last-decision`. |
| Dashboard | Agent State timeline | none | none | none | none | Mock / Hardcoded | All steps are hardcoded "Waiting for agent". |
| Dashboard | Risk Guard card | none | none | none | none | Mock / Hardcoded | Static Phase 2 overlay; not connected to `/api/agentic/risk-guard`. |
| Dashboard | Risk Guard checks | none | none | none | none | Mock / Hardcoded | Check names are placeholders without live verdicts. |
| Dashboard | Scanner note | `/api/scope/candidates`, fallback `/api/agentic/scanner/candidates` | `scope_state`, `instrument_master`, `options_live_enriched`, `options_context_buckets` | live DB from Kite | 8s poll/manual | Partially Integrated | Shows candidate count but not snapshot timestamp or stale state. |
| Dashboard | Scanner instrument/type/strike | same | `instrument_master`, `options_live_enriched` | Kite live DB | 8s poll/manual | Partially Integrated | Values are backend-derived when endpoint succeeds; fallback can be non-scope scan. |
| Dashboard | Scanner richness/theta/liquidity/delta risk/score | same | `options_context_buckets`, `options_live_enriched` | Kite live DB | 8s poll/manual | Partially Integrated | Composite fields exist, but no per-row source timestamp/freshness in UI. |
| Dashboard | Scanner action badge/link | same | scanner output | same | 8s poll/manual | Partially Integrated | Link opens Strategy Lab but does not pass selected candidate or canonical snapshot ID. |
| Dashboard | Scope footer chip | `GET/POST/DELETE /api/scope` | `scope_state`, `instrument_master` | none for activation unless UI supplies spot | on change | Broken / Missing | UI does not send `spotEstimate`; ATM windows resolve around 0.0. |

Dashboard stale/mock/hardcoded issues:

- Agent State and Risk Guard are explicitly placeholder cards.
- Dashboard scanner silently falls back from scoped candidates to agentic candidates; source-of-truth is unclear to the user.
- Position PnL is not live MTM.
- Scope activation is likely broken for default ATM windows because `spotEstimate` is omitted.
- `/api/live/status` exists but is not used by Dashboard to show feed disconnected, seconds since last tick, subscribed tokens, or readiness.

## Section 3: Strategy Lab Screen Review

Primary UI: `ui/trading-platform-prototype/strategy-lab.html`

Primary APIs:

- `GET /api/live/spot?underlying=NIFTY`
- `GET /api/live/spot?underlying=BANKNIFTY`
- `GET /api/bootstrap/metadata`
- `GET /api/scope`
- `POST /api/scope`
- `DELETE /api/scope`
- `GET /api/scope/signal-snapshot`

Implemented workflow:

1. Load spot prices and bootstrap metadata.
2. User selects underlying, expiry, strategy, strike window, max contracts.
3. UI activates scope via `POST /api/scope`.
4. UI polls `/api/scope/signal-snapshot` every 30 seconds.
5. UI renders scan rows and per-leg detail.

Requested workflow coverage:

| Strategy Lab Item | UI Call | Backend/DB/Market Source | Refresh | Status | Issue |
| --- | --- | --- | --- | --- | --- |
| Strategy selection | `POST /api/scope` | `scope_state`, `instrument_master` | on Start Scan | Partially Integrated | Persists strategy kind, but no structure builder or recommendation selection. |
| Underlying selection | `POST /api/scope`, spot endpoint | `instrument_master`, spot canonical resolver | spot 30s, scan 30s | Partially Integrated | Spot shown, but stale/source labels are not clear. |
| Expiry type | `GET /api/bootstrap/metadata`, `POST /api/scope` | `instrument_master` | metadata on boot | Fully Integrated | Expiry and type come from metadata. |
| Timeframe | none in active Strategy Lab | none | none | Broken / Missing | Requested timeframe is absent from current screen. |
| Run Analysis | none | none | none | Broken / Missing | No Run Analysis button or analysis run endpoint. |
| Scope Start Scan | `POST /api/scope` | `scope_state`, `instrument_master` | user action | Broken / Missing | Missing `spotEstimate` makes ATM window resolution wrong. |
| Structure legs | `GET /api/scope/signal-snapshot` | resolved universe plus `options_live_enriched` | 30s | Partially Integrated | These are scan legs, not a selected strategy structure. No entry side/qty/locked snapshot. |
| Entry price capture | none | none | none | Broken / Missing | No locked entry price capture in UI or backend run snapshot. |
| LTP/current price | `GET /api/scope/signal-snapshot` | direct `options_live_enriched` latest tick | 30s | Partially Integrated | Current premium is shown, but no `priceType`, source, as-of freshness object, or post-close resolver path. |
| Qty | scan rows show lot size only in backend response; UI does not show qty in scan table | `instrument_master.lot_size` | 30s | Broken / Missing | No user/run quantity or selected structure quantity. |
| MTM | none | none | none | Broken / Missing | No locked entry vs live/current price MTM calculation. |
| Total premium | none | none | none | Broken / Missing | No selected structure total premium. |
| Confidence | none in active Strategy Lab | none | none | Broken / Missing | No confidence/recommendation output in current page. |
| Recommendation output | per-leg `actionStatus` only | deterministic local backend rules in `ScopeSignalSnapshotHandler` | 30s | Partially Integrated | Per-leg candidate/watch/avoid exists, but no strategy-level controlled candidate ranking output. |
| Report generation | none | report endpoint exists but UI does not call it | none | Broken / Missing | No canonical report snapshot or generated PDF/markdown from UI. |
| Post-close prices | spot endpoint can use canonical resolver; signal snapshot cannot | mixed | 30s | Stale Data Risk | Signal snapshot uses direct live tables and can leak stale ticks after close. |

Run Analysis and snapshot consistency:

- No `analysisRunId` is created by the active UI.
- No backend analysis-run persistence table was found.
- `StrategyRunReportService` accepts arbitrary request JSON and writes markdown. It does not fetch the canonical UI snapshot itself.
- Strategy Lab does not call `/api/reports/strategy-run`.
- Therefore the structure legs, top summary, MTM, recommendation output, and report do not share one canonical snapshot.

Entry/LTP/MTM behavior:

- Entry price locking: not implemented in the active Strategy Lab.
- LTP update: current premium is refreshed through `/api/scope/signal-snapshot`, but only every 30 seconds.
- MTM: not implemented. Backend `LiveMarketService` can compute live leg PnL for `/api/live/structure`, but Strategy Lab does not call that endpoint.
- Post-close: canonical spot endpoint has price type support, but Strategy Lab signal rows do not use canonical price objects.

Report consistency:

- Existing report service is useful as a rendering sink, not a canonical source.
- It computes missing booked/live/total PnL from request payload fields and final legs.
- This is not safe for UI/report parity because the report can be generated from a different client-side or caller-side snapshot than the UI displayed.

## Section 4: Data Flow Diagram

Intended flow:

Market Feed -> Backend Normalizer -> DB/Cache -> Strategy Engine -> UI -> Report

Current observed flow:

Kite quote polling -> `KiteTickerAdapter` -> `LiveSessionState` and tick DTOs -> `KiteLiveIngestionJob` -> `spot_live`, `options_live`, `options_live_enriched`, in-memory 15m aggregator -> `LiveMarketService` canonical endpoints for spot/structure or direct `ScopeSignalSnapshotHandler` DB reads -> Dashboard/Strategy Lab UI.

Historical context flow:

Bhavcopy/historical ingestion -> `options_historical`, `spot_historical`, `options_enriched`, `options_context_buckets` -> scanner scoring and research services -> UI metrics.

Position/report flow:

UI or orchestrator payload -> `ResearchPositionSessionService` file-backed JSON store -> Dashboard active position.  
Caller-provided report JSON -> `StrategyRunReportService` -> markdown file in `docs/reports`.

Key mismatch:

The canonical resolver exists for `LIVE_LTP`, `OFFICIAL_CLOSE`, `PRE_CLOSE_LAST_TRADE`, `PRIOR_CLOSE`, and `UNAVAILABLE`, but Strategy Lab signal rows do not emit the requested canonical price object:

```json
{
  "instrumentKey": "...",
  "price": 0,
  "priceType": "LIVE_LTP | OFFICIAL_CLOSE | PRE_CLOSE_LAST_TRADE | PRIOR_CLOSE | UNAVAILABLE",
  "asOf": "...",
  "sessionState": "...",
  "isStale": false,
  "source": "kite | db | bhavcopy | historical"
}
```

## Section 5: Issues Found

### Issue 1: UI scope activation omits spot estimate

Severity: Critical  
Affected screen: Dashboard scope picker, Strategy Lab  
Expected behavior: ATM percent/points windows resolve around the latest underlying spot.  
Current behavior: UI payload omits `spotEstimate`; backend defaults to `0.0`; `UniverseResolver` calculates ranges around zero.  
Probable cause: `scope-picker.js` and `strategy-lab.html` do not include a spot estimate from `/api/live/spot`.  
Recommended fix: Require backend-side spot resolution for ATM windows or include canonical spot price in scope activation payload. Backend should reject ATM windows when spot is unavailable instead of defaulting to zero.

### Issue 2: Restored scope and signal snapshot re-resolve range scopes with 0.0

Severity: Critical  
Affected screen: Dashboard scanner, Strategy Lab scanner  
Expected behavior: Active scope should remain tied to its originally resolved instrument universe or re-resolve using current canonical spot.  
Current behavior: `/api/scope/candidates` and `/api/scope/signal-snapshot` call `universeResolver.resolve(scope, 0.0)`.  
Probable cause: Stored scope persists strike-window parameters but not resolved instrument IDs or activation spot.  
Recommended fix: Persist resolved instrument IDs/snapshot context with scope, or re-resolve ATM windows using canonical spot. Include `scopeResolutionAsOf` and `spotPriceObject`.

### Issue 3: Strategy Lab Run Analysis workflow is missing

Severity: Critical  
Affected screen: Strategy Lab  
Expected behavior: Run Analysis creates a canonical `analysisRunId` with locked entry prices, selected structure, quantities, evidence, recommendation, and report snapshot.  
Current behavior: Page only activates scope and scans current legs.  
Probable cause: Scope-first scan UI has not been integrated with strategy run/session/report domain.  
Recommended fix: Add `POST /api/strategy-lab/runs` that builds the selected structure, locks entry prices through canonical price resolver, persists an analysis run, and returns one canonical snapshot.

### Issue 4: Strategy Lab current premiums bypass canonical price resolver

Severity: High  
Affected screen: Strategy Lab  
Expected behavior: Every displayed price includes source, timestamp, price type, session state, and stale status.  
Current behavior: Signal snapshot directly reads `options_live_enriched` latest rows and exposes `currentPremium` plus `premiumTimestamp` only.  
Probable cause: `ScopeSignalSnapshotHandler` predates the canonical price object contract.  
Recommended fix: Wrap each leg price through `CanonicalPriceResolverService` or move signal snapshot computation behind a canonical market snapshot service.

### Issue 5: Post-close Strategy Lab can show stale live ticks

Severity: High  
Affected screen: Strategy Lab  
Expected behavior: Post-close uses official close, then valid pre-close last trade, then prior close/unavailable with clear label.  
Current behavior: Signal snapshot uses latest live rows regardless of session state and marks stale only after 120s.  
Probable cause: No session-state branch in `ScopeSignalSnapshotHandler`.  
Recommended fix: Use `MarketSessionStateResolver` and canonical price resolution for option legs and spot windows.

### Issue 6: Stale thresholds do not meet 2-second requirement

Severity: High  
Affected screen: Dashboard, Strategy Lab, market data services  
Expected behavior: no tick >2s, no strategy recompute >2s, feed disconnected, and report delay are visibly stale/delayed.  
Current behavior: Dashboard core poll is 2s, scanner poll is 8s, Strategy Lab spot/scan polls are 30s, canonical stale threshold is 30s, signal snapshot stale threshold is 120s.  
Probable cause: Different services have independent freshness assumptions.  
Recommended fix: Define one freshness contract. Add `freshnessStatus`, `lastTickAgeMs`, `lastRecomputeAgeMs`, and UI badges for Live/Delayed/Stale/Official Close/Prior Close/Unavailable.

### Issue 7: Dashboard active PnL is booked-only

Severity: High  
Affected screen: Dashboard  
Expected behavior: Session PnL and leg PnL should include booked and live MTM components with clear labels.  
Current behavior: UI sums `bookedPnl` from position session legs and labels it Net PnL session.  
Probable cause: Dashboard consumes file-backed position snapshots, not `/api/live/structure` or canonical MTM.  
Recommended fix: Add backend session MTM endpoint that combines locked entry prices, live canonical prices, booked PnL, and open-leg MTM.

### Issue 8: Report generation is not bound to UI snapshot

Severity: High  
Affected screen: Strategy Lab / Reports  
Expected behavior: Report uses the same canonical `analysisRunId` snapshot as UI.  
Current behavior: Report service writes from request JSON and the UI does not invoke it.  
Probable cause: Report service is implemented as a rendering utility, not a persistence-backed run report API.  
Recommended fix: Report endpoint should accept `analysisRunId`, load persisted snapshot server-side, and refuse caller-supplied recomputation fields unless explicitly marked as annotations.

### Issue 9: Agent State and Risk Guard dashboard cards are placeholders

Severity: Medium  
Affected screen: Dashboard  
Expected behavior: Cards show live orchestrator state, last decision, and risk guard verdict.  
Current behavior: Static Phase 1/Phase 2 overlay text and hardcoded checklist.  
Probable cause: UI has not consumed `/api/agentic/status`, `/api/agentic/last-decision`, or `/api/agentic/risk-guard`.  
Recommended fix: Wire cards to agentic endpoints and include unavailable/offline states.

### Issue 10: Scanner rows lack source/freshness metadata

Severity: Medium  
Affected screen: Dashboard, Strategy Lab  
Expected behavior: Each candidate price/metric shows source and as-of freshness.  
Current behavior: Dashboard scanner shows numeric metrics only; Strategy Lab shows timestamp for premium but no source or stale badge.  
Probable cause: API responses do not expose full price object or freshness status.  
Recommended fix: Add canonical `price` object per leg and `snapshotTs` from backend; render badges directly from server state.

### Issue 11: Position sessions are file-backed, not DB-backed

Severity: Medium  
Affected screen: Dashboard, Orders, Reports  
Expected behavior: DB persistence is verified for sessions, entries, actions, and reports.  
Current behavior: `ResearchPositionSessionService` persists JSON under `run/position-sessions`.  
Probable cause: Research-console persistence is local-file based.  
Recommended fix: Add DB tables for `strategy_runs`, `strategy_run_legs`, `strategy_run_snapshots`, `strategy_run_actions`, and migrate dashboard reads to DB or explicitly label file-backed research mode.

### Issue 12: Strategy Lab "Last Updated" is client time

Severity: Low  
Affected screen: Strategy Lab  
Expected behavior: Last Updated should show backend snapshot timestamp.  
Current behavior: `renderSummaryStrip` uses `fmtNow()` instead of response `snapshotTs`.  
Probable cause: UI convenience timestamp.  
Recommended fix: Render `data.snapshotTs` and mark delayed/stale based on server freshness.

## Backend/API/DB Mapping Gaps

Backend/API gaps:

- No `POST /api/strategy-lab/runs` or equivalent Run Analysis endpoint.
- No canonical `analysisRunId` or immutable analysis snapshot API.
- No UI-facing endpoint that returns selected structure legs, locked entry, live LTP, MTM, total premium, recommendation, and report-ready payload together.
- No endpoint that returns Dashboard position MTM using canonical live prices.
- `/api/live/status` is not consumed by Dashboard/Strategy Lab.
- `/api/reports/strategy-run` accepts request payloads instead of loading a server-side run snapshot.
- Scope APIs accept missing `spotEstimate` and proceed with 0.0.

DB mapping gaps:

- Position sessions are stored as JSON files, not DB tables.
- Report output is markdown files, not DB snapshots.
- No discovered table/model for `analysisRunId`.
- No discovered table/model for canonical Strategy Lab run snapshots.
- `scope_state` stores the scope definition but not the resolved instrument universe, activation spot, or resolution timestamp.
- Live signal snapshots are computed on demand and not persisted as canonical snapshots for UI/report parity.

Stale-data risks:

- Strategy Lab scan recompute is every 30 seconds.
- Strategy Lab premium stale cutoff is 120 seconds.
- Canonical live price stale cutoff is 30 seconds.
- Dashboard scanner refresh is every 8 seconds.
- Dashboard and Strategy Lab do not show feed-disconnected state from `/api/live/status`.
- Signal snapshot can use live DB rows after market close without official-close fallback.
- Report snapshot can be delayed or inconsistent because it is supplied by caller JSON.

Suggested implementation fixes:

1. Make the backend own scope spot resolution. For ATM windows, resolve current canonical spot server-side and reject unavailable spot.
2. Persist scope resolution: `scopeId`, activation spot price object, resolved instrument IDs, and `resolvedAt`.
3. Add a canonical Strategy Lab run model: `analysisRunId`, `scopeId`, selected strategy, selected legs, locked entry price objects, quantities, recommendation evidence, and snapshot timestamp.
4. Make Strategy Lab poll a single run snapshot endpoint after analysis: locked entry remains fixed, LTP price objects update, MTM derives from locked entry vs current canonical prices.
5. Move signal snapshot prices through `CanonicalPriceResolverService` and emit the required price object for spot and every leg.
6. Standardize freshness: live <=2s, delayed >2s, stale by configurable hard cutoff, official close/prior close/unavailable based on session state.
7. Wire Dashboard Agent State and Risk Guard to agentic endpoints with explicit Offline/Unavailable badges.
8. Replace Dashboard booked-only PnL with booked, live MTM, and total PnL.
9. Change report generation to `POST /api/reports/strategy-run?analysisRunId=...` or equivalent. Server loads the canonical snapshot and writes the report from it.
10. Add tests that assert UI/API contract fields: `priceType`, `asOf`, `sessionState`, `isStale`, `source`, `analysisRunId`, `entryPrice`, `ltp`, `mtm`, and report snapshot parity.

## Section 6: Final Sign-off Checklist

- [x] Dashboard all fields mapped
- [x] Strategy Lab all visible fields mapped
- [ ] no hardcoded values
- [ ] no random post-close values
- [ ] stale-data badges working
- [ ] entry locking working
- [ ] MTM calculation verified
- [ ] report snapshot matches UI snapshot
- [ ] DB persistence verified

Readiness verdict: Not Ready.
