# Scoped Instrument Loading & Scanning Redesign

Status: Draft proposal
Owner: Strategy Squad backend
Last updated: 2026-04-28
Supersedes (in part): two-phase ATM auto-expansion in `KiteLiveSessionManager.startSession`

## 1. Problem

The current login + live-assist path is structured as **load-then-filter**: at session start the backend pulls the whole NFO instrument dump for NIFTY/BANKNIFTY (cached for the day), inserts every filtered contract into `instrument_master`, and brings up a `KiteTickerSession` whose subscription set is the entire configured strike window. The morning scanner then scans every active weekly/monthly contract for both underlyings, regardless of what the user actually wants to trade.

This produces several stability problems:

1. **Wide universe even when user only cares about one expiry.** `KiteInstrumentFilter.filter` keeps current weekly + (optionally) next weekly + current monthly + (optionally) next monthly, both underlyings, across configurable strike windows (`niftyStrikeWindowPoints`=2000, `bankNiftyStrikeWindowPoints`=4000 by default). Even with conservative defaults, that is hundreds of contracts before the user has chosen a strategy.
2. **Heavy ticker subscription.** `KiteTickerSession` polls `/quote` every second for every subscribed instrument in chunks of 500. Subscription size is fixed at session start; there is no way to narrow it after the user picks a scope.
3. **Scanner reads the whole active universe.** `ScannerQuery.fetchActiveWeeklyContracts()` selects all rows from `instrument_master WHERE is_active = true AND underlying IN ('NIFTY','BANKNIFTY') AND expiry_date >= now()-1d`, then loops one-by-one to attach prices. The single-underlying overload helps, but expiry/strike scope is still implicit.
4. **UI ingests the same broad payload.** Scanner candidates and structure snapshots return ranked lists derived from this wide universe, so the UI and the JVM heap both carry far more state than the user is acting on.
5. **Phase-1/Phase-2 dance is opaque.** `KiteLiveSessionManager.startSession` fires a Phase 1 ATM-only download, schedules Phase 2 expansion 30s later, then **rebuilds the entire `KiteTickerSession`** to swap the subscription set — a non-trivial reconnect that happens automatically without user intent.

The functional direction is to flip this: **the user's chosen scope is the contract universe**. Backend never resolves more than the user has asked for. Kite is treated as a quote/order source, not a UI data fountain.

## 2. Principles

- **Scope first, instruments second.** No live subscription, no scanner run, no UI list of contracts is ever produced before the user's scope is known.
- **Bounded by construction.** Every public API takes scope as a required input and refuses unbounded selections at the controller boundary.
- **Lazy and cancelable.** Loading more instruments than the current scope requires must be explicit (e.g. user widens the strike window). Narrowing scope must release subscriptions and free memory.
- **Cache for identity, query for price.** `instrument_master` is a lookup table and source of truth for `instrument_id`/token mapping. Kite `/quote` is called only for the active subscribed token set.
- **Soft limits, not silent truncation.** Live subscription/scanner output use **hard caps** (reject with a narrowing hint). Read-only research queries use **soft caps with truncation + a warning header**, so power-user inspection still works.
- **Domain invariants untouched.** `docs/options-strategy-domain-contract.md` rules — no mixing weekly/monthly cohorts, point-in-time joins, signed internal metrics — must hold under the new flow.

## 3. Current vs Target Flow

### 3.1 Current (load-then-filter)

```
Login (POST /api/auth/login)
    ↓
KiteLiveSessionManager.validateAndStartSession
    ↓
KiteLiveSessionManager.startSession
    ↓ Phase 1 (sync)
KiteInstrumentsDumpJob.downloadAtmOnly      ← full NFO HTTP download (cached for day)
KiteInstrumentFilter.atmOnly                 ← ±3 strikes, current weekly only
upsertNewInstruments(connection, ...)        ← instrument_master writes
KiteTickerSession(... ATM list ...).connect()
    ↓ Phase 2 (background, 30s later)
KiteInstrumentsDumpJob.downloadFull
KiteInstrumentFilter.filter                  ← full configured window
upsertNewInstruments(...)
shutdown old KiteTickerSession
new KiteTickerSession(... full list ...).connect()
    ↓
Scanner (GET /api/agentic/scanner/candidates?underlying=NIFTY)
ScannerQuery.fetchActiveWeeklyContracts(underlying)
    ↓
UI receives ranked list across all weekly+monthly active strikes
```

Problems: every step happens before the user has expressed any intent beyond "I logged in."

### 3.2 Target (scope-first)

```
Login (POST /api/auth/login)
    ↓
KiteLiveSessionManager.validateAndPrepare        ← validates token, NO instrument download
    ↓
GET /api/bootstrap/metadata
    {
      "underlyings": ["NIFTY","BANKNIFTY"],
      "expiries": [
        { "underlying":"NIFTY", "date":"2026-04-30", "type":"WEEKLY", "instrumentCount":138 },
        ...
      ],          ← read from instrument_master, no Kite call
      "instrumentMasterFreshness": "2026-04-28T08:55:14Z",
      "activeScope": null,
      "appConfig": { ... }
    }
    ↓
UI shows scope picker. User chooses underlying / expiry / strategy / strike window.
    ↓
POST /api/scope                                  ← persist day-scoped Scope
    { "underlying":"NIFTY",
      "expiry":"2026-04-30",
      "expiryType":"WEEKLY",
      "strategy":"SHORT_STRANGLE",
      "strikeWindow": { "kind":"ATM_PCT", "pct": 4.0 },
      "maxCandidates": 30 }
    ↓
ScopeStore.save(today, scope)                    ← persisted to scope_state table
ScopeService.activate(scope)
    ↓
1. UniverseResolver.resolve(scope)               ← bounded query against instrument_master
2. SubscriptionManager.bind(activeScope.tokens)  ← subscribe ONLY those tokens
3. ScannerService.scan(scope, candidates)        ← scoped scanner
    ↓
GET /api/scope/candidates                        ← top-N candidates only
GET /api/scope/quotes                            ← live quotes for active tokens only
GET /api/live/structure?scope=ACTIVE             ← bounded structure snapshot
    ↓
User narrows further (selects 2 legs).
SubscriptionManager.narrow(legs)                 ← optionally drop strikes outside legs
    ↓
User changes scope → ScopeService.deactivate prior → activate next
```

Concretely: the JVM never holds the full NFO list past parsing, never subscribes to instruments outside the scope, and the UI never receives the broad universe.

## 4. Domain Model

### 4.1 New types

All in `com.strategysquad.scope`:

```java
public record Scope(
        String underlying,            // "NIFTY" | "BANKNIFTY"
        LocalDate expiry,             // chosen from /api/bootstrap/metadata
        ExpiryType expiryType,        // WEEKLY | MONTHLY
        StrategyKind strategy,        // e.g. SHORT_STRANGLE, IRON_CONDOR, ANALYSIS_ONLY
        StrikeWindow strikeWindow,    // ATM_PCT | ATM_POINTS | EXPLICIT_RANGE | LEGS_ONLY
        int maxCandidates             // bounded, default 30, hard cap 100
) { ... }

public sealed interface StrikeWindow {
    record AtmPct(double pct) implements StrikeWindow {}              // ±pct% of spot
    record AtmPoints(double points) implements StrikeWindow {}        // ±N pts of spot
    record ExplicitRange(double low, double high) implements StrikeWindow {}
    record LegsOnly(List<String> instrumentIds) implements StrikeWindow {}
}

public record ResolvedUniverse(
        Scope scope,
        List<InstrumentRef> instruments,   // bounded; size <= cap
        boolean truncated,                 // soft-limit signal
        String narrowingHint               // populated if truncated
) {}

public record InstrumentRef(
        String instrumentId,
        long kiteToken,
        String tradingSymbol,
        String optionType,
        BigDecimal strike,
        LocalDate expiry,
        ExpiryType expiryType,
        int lotSize
) {}
```

`Scope` is the boundary object. **Every controller method that touches live data takes `Scope` (or a `scopeId` referencing the active scope) as input.** The previous "implicit underlying" overloads on `MorningScannerService` and `ScannerQuery` are deprecated.

### 4.2 ScopeStore (day-scoped persistence)

New table:

```sql
CREATE TABLE scope_state (
    trading_date   TIMESTAMP,                -- midnight IST of the trading day
    scope_id       STRING,
    user_id        SYMBOL,                   -- single-user today; future-proof
    underlying     SYMBOL,
    expiry         TIMESTAMP,                -- matches instrument_master.expiry_date
    expiry_type    SYMBOL,
    strategy       SYMBOL,
    strike_window  STRING,                   -- JSON-encoded StrikeWindow
    max_candidates INT,
    created_at     TIMESTAMP,
    last_active_at TIMESTAMP
) timestamp(created_at) PARTITION BY DAY;
```

Add migration `db/migration/V004__scope_state.sql` (matching the existing `V###__name.sql` convention used by `V001__golden_source_tables.sql`, etc.). Day-scoped semantics mirror `KiteDailyTokenStore`:

- On startup, `ScopeService.loadActive()` reads the most recent row for `trading_date = today()`. If the expiry on that scope is still valid in `instrument_master`, scope is restored. Otherwise the saved row is marked stale and the user is prompted via the bootstrap response (`activeScope = null`, `previousScopeStale = true`).
- `POST /api/scope` upserts the row for today and atomically swaps `SubscriptionManager`.
- `DELETE /api/scope` clears today's row and tears down subscriptions.

## 5. Component Changes

### 5.1 `KiteLiveSessionManager`

- **Remove** the two-phase ATM auto-expansion (`startSession`, `expandUniverse`, `EXPANSION_DELAY_SECONDS`, the `expansionScheduler`).
- **Remove** automatic `KiteInstrumentsDumpJob.downloadAtmOnly` and full-window download from login.
- **Replace** with `validateAndPrepare(accessToken)`:
  - Validates token (existing `KiteTokenValidator`).
  - Triggers a **lightweight** instrument-master refresh **only if** `instrument_master` has no rows newer than the most recent expiry boundary or it's the first login of the day. The refresh still uses `KiteInstrumentsDumpJob.downloadFull` but does **not** create a `KiteTickerSession`. Output stays in `instrument_master` for lookup.
  - Sets `LiveSessionState.Status = AUTHENTICATED_NO_SCOPE` (new status). `subscribedInstruments = 0`.
- **Keep** `/api/admin/instruments/refresh` as a manual hook for ops; document that ordinary users never need it.

### 5.2 `KiteTickerSession` becomes `KiteSubscriptionManager`

- Lifetime is the **active scope**, not the login.
- Subscription set is **mutable**: `bind(Set<Long> tokens)`, `addAll`, `removeAll`, `unbindAll`.
- Hard cap on subscribed tokens (default 250; configurable via `kite.live.max.subscribed.tokens`).
- Polling cadence and chunking unchanged.
- Adapter (`KiteTickerAdapter`) keeps a single `quoteKeyToId` map — but the underlying instrument list is replaced atomically when scope changes (`reload(quoteKeys, quoteKeyToId)`).
- On scope change: `SubscriptionManager.swap(oldTokens → newTokens)` issues an unbind + bind in one critical section so a concurrent poll never sees a hybrid set.

### 5.3 `KiteInstrumentsDumpJob`

- The day-scoped raw-dump cache (`RAW_DUMP_CACHE`) stays — it protects against rate limits. But **the cache is the only place the full list lives**, and it is parsed → filtered → discarded inside the resolver. Nothing downstream keeps the full list.
- Add `downloadForExpiry(KiteLiveConfig config, LocalDate expiry, String underlying, double atmPx, StrikeWindow window)` returning only the contracts a `Scope` requires. Reuses the cached raw list.
- Mark `run(...)` and `downloadAtmOnly(...)` as `@Deprecated` and remove the Phase-1 callers.

### 5.4 `UniverseResolver` (new, `com.strategysquad.scope.UniverseResolver`)

```java
public ResolvedUniverse resolve(Scope scope, double spotEstimate) throws SQLException;
```

Pipeline:
1. SQL: `SELECT instrument_id, ... FROM instrument_master
   WHERE underlying = ? AND expiry_date = ? AND option_type IN (...) AND is_active = true AND strike BETWEEN ? AND ?`.
2. Apply `StrikeWindow` to derive the `BETWEEN` bounds. For `LegsOnly`, the SQL becomes `instrument_id IN (...)`.
3. Cap at `max(scope.maxCandidates(), HARD_CAP)`. If exceeded, set `truncated = true` and populate `narrowingHint`.
4. Return `ResolvedUniverse`. Resolver does **not** call Kite — it operates on the cached `instrument_master`.

Validation (per design item 8):
- Reject if `instrument_master` lacks the requested expiry → `MissingExpiryException("expiry %s not in instrument_master")` with hint to refresh.
- Reject if any `LegsOnly` instrument_id is missing or has inconsistent CE/PE pairing.
- Reject if scope underlying ∉ {`NIFTY`, `BANKNIFTY`}.

### 5.5 `ScannerQuery` / `MorningScannerService`

- Add `ScannerQuery.fetchScoped(Scope scope)` that takes the resolved instrument set and only loads prices for those `instrument_id`s. Internally: `WHERE instrument_id IN (...)` against `options_live_enriched` / `options_enriched`.
- Add `MorningScannerService.scanScoped(Scope scope, ResolvedUniverse universe, ...)`. The existing `scan(...)` overload stays for the agentic loop's headless callers (with a deprecation note), but new endpoints use the scoped version.
- Cohort map loading is unchanged; cohort matching is per-instrument anyway.
- Soft-cap the candidate output at `scope.maxCandidates()`. If the resolver was truncated, the scanner result inherits `truncated=true`.

### 5.6 REST API surface

| Method | Path | Purpose |
|---|---|---|
| GET  | `/api/bootstrap/metadata` | Lightweight startup payload: underlyings, expiries (from `instrument_master`), instrument-master freshness, active scope, app config. **No Kite call.** |
| GET  | `/api/scope` | Returns active scope or `null`. |
| POST | `/api/scope` | Activate or replace scope; tears down/swaps subscriptions atomically. |
| DELETE | `/api/scope` | Clear active scope; unsubscribes everything. |
| GET  | `/api/scope/universe` | Returns the resolved bounded universe for the active scope (or 409 if none). |
| GET  | `/api/scope/candidates` | Scoped scanner output (top-N). |
| GET  | `/api/scope/quotes?instrument_id=...` | Live quotes only for the subscribed set. Rejects unknown ids. |
| GET  | `/api/live/structure` | Becomes scope-aware; returns 409 if no active scope. |
| POST | `/api/admin/instruments/refresh` | Manual NFO refresh (existing). |

`POST /api/scope` request body:

```json
{
  "underlying": "NIFTY",
  "expiry": "2026-04-30",
  "expiryType": "WEEKLY",
  "strategy": "SHORT_STRANGLE",
  "strikeWindow": { "kind": "ATM_PCT", "pct": 4.0 },
  "maxCandidates": 30
}
```

Response (success):

```json
{
  "scopeId": "S_20260428_NIFTY_20260430_W_001",
  "scope": { ... echoed ... },
  "universe": {
    "size": 22,
    "truncated": false,
    "instruments": [ { "instrumentId":"INS_NIFTY_20260430_24800_CE", ... }, ... ]
  },
  "subscription": { "tokenCount": 22, "kiteSubscribedAt": "2026-04-28T09:14:02Z" }
}
```

Validation errors (400):
- `INVALID_UNDERLYING`, `INVALID_EXPIRY`, `EXPIRY_NOT_IN_MASTER`, `STRIKE_WINDOW_TOO_WIDE`, `MAX_CANDIDATES_EXCEEDED`, `LEGS_INSTRUMENT_NOT_FOUND`, `INSTRUMENT_PAIR_INCOMPLETE`.

## 6. Guardrails

| Guardrail | Cap | Mode | Where enforced |
|---|---|---|---|
| Subscribed Kite tokens | 250 | Hard reject | `KiteSubscriptionManager.bind` |
| Strikes per expiry per request | 100 | Hard reject | `UniverseResolver.resolve` |
| Scanner candidate count | 100 | Hard reject (`maxCandidates > 100`) | scope validator |
| Universe response payload size | 256 KB | Soft truncate | `/api/scope/universe` handler |
| Research read-only payloads | 1 MB | Soft truncate (warn header `X-Truncated: true`) | research handlers |
| `instrument_master` freshness | 24 h | Warn (not block); surface in metadata | bootstrap handler |

Hard rejects return HTTP 400 with:

```json
{
  "error": "STRIKE_WINDOW_TOO_WIDE",
  "details": "ATM_PCT=15.0 yields 312 strikes; max=100. Narrow to <=8% or use EXPLICIT_RANGE.",
  "hint": "Try strikeWindow={\"kind\":\"ATM_PCT\",\"pct\":4.0}"
}
```

Soft truncation returns the bounded slice with `truncated: true` and a `narrowingHint` field plus the `X-Truncated: true` header.

Defaults are conservative; expose them as config keys in `kite.properties` (`scope.max.subscribed.tokens`, `scope.max.strikes.per.expiry`, `scope.max.candidates`, `scope.research.payload.bytes`).

## 7. UI Behavior

- App boot calls `/api/bootstrap/metadata` only. No instrument list is loaded.
- A scope picker is shown if `activeScope = null`. Underlying + expiry come from the metadata payload; strategy and strike window are local form state.
- After `POST /api/scope` succeeds, the UI receives the bounded universe inline (no second fetch). Lazy-loaded contract searches use `/api/scope/universe?search=` against the cached scope.
- Quotes are pulled by `/api/scope/quotes` polling at 1–2 s for **only the active legs and visible candidates**. Background lists are not subscribed.
- Scope change triggers a single confirmation if there are open simulated/live legs ("Switching scope unsubscribes 22 contracts. Continue?").
- A persistent footer chip shows: scope underlying/expiry/strategy + subscribed-token count + truncated indicator.

## 8. Phased Implementation Plan

Each phase ships independently, behind a feature flag (`scope.enabled`, default `false` until Phase 5). Until then the legacy two-phase login keeps working.

### Phase 1 — Bootstrap slim (foundations)

**Scope:** new metadata endpoint + Scope/StrikeWindow domain types + ScopeStore migration. No behavior change to login.

Files to add:
- `db/migration/V004__scope_state.sql`
- `src/main/java/com/strategysquad/scope/Scope.java`
- `src/main/java/com/strategysquad/scope/StrikeWindow.java`
- `src/main/java/com/strategysquad/scope/ExpiryType.java`
- `src/main/java/com/strategysquad/scope/StrategyKind.java`
- `src/main/java/com/strategysquad/scope/InstrumentRef.java`
- `src/main/java/com/strategysquad/scope/ScopeStore.java` (JDBC; mirrors `KiteDailyTokenStore`)
- `src/main/java/com/strategysquad/scope/BootstrapMetadataService.java`
- `src/main/java/com/strategysquad/research/handlers/BootstrapMetadataHandler.java` (or inner class in `ResearchConsoleServer`)

Tests:
- `ScopeStoreTest` — round-trip per-day persistence.
- `BootstrapMetadataServiceTest` — emits expiries from `instrument_master`, omits past-expiry rows, marks freshness.

DoD: `GET /api/bootstrap/metadata` returns valid payload pointing at existing data; no live wiring touched.

### Phase 2 — Bounded UniverseResolver

Files to add:
- `src/main/java/com/strategysquad/scope/UniverseResolver.java`
- `src/main/java/com/strategysquad/scope/ResolvedUniverse.java`
- `src/main/java/com/strategysquad/scope/ScopeValidationException.java`

Tests:
- `UniverseResolverTest` — `ATM_PCT`, `ATM_POINTS`, `ExplicitRange`, `LegsOnly`; rejects missing expiry, unknown legs, mismatched CE/PE pair; honours hard caps.
- `UniverseResolverIntegrationTest` (uses existing `BhavcopyJdbcTestSupport`) — runs against an ephemeral QuestDB row set seeded with NIFTY/BANKNIFTY rows.

DoD: resolver returns ≤ scope.maxCandidates instruments; never reads from Kite.

### Phase 3 — ScopeService + atomic subscription swap

Files to add/edit:
- New: `src/main/java/com/strategysquad/scope/ScopeService.java` — owns active scope, persists via `ScopeStore`, drives `KiteSubscriptionManager`.
- New: `src/main/java/com/strategysquad/ingestion/kite/KiteSubscriptionManager.java` — extracted mutable surface of `KiteTickerSession`.
- Edit: `KiteTickerSession` — refactor so the constructor takes a `KiteSubscriptionManager` instead of a fixed `instruments` list; expose `swap(newQuoteKeys, newQuoteKeyToId)`; ensure `poll()` reads the current snapshot atomically (e.g. an immutable `Subscription` value object behind an `AtomicReference`).
- Edit: `KiteLiveSessionManager` — `startSession` becomes a no-op for instruments; spins up `KiteSubscriptionManager` with empty subscriptions and connects to `/quote` only for the spot keys (`NSE:NIFTY 50`, `NSE:NIFTY BANK`) until a scope is activated.

Tests:
- `KiteSubscriptionManagerTest` — bind/unbind with hard cap; concurrent swap atomicity (CountDownLatch-driven race).
- `ScopeServiceTest` — activating scope swaps subscription set; deactivating clears it; activating an already-active scope is a no-op.

DoD: with `scope.enabled=true`, `POST /api/scope` produces a `KiteSubscriptionManager` whose subscribed token count equals `universe.size + 2` (2 for spot keys).

### Phase 4 — Scoped scanner

Files to add/edit:
- Edit: `ScannerQuery` — add `fetchScoped(Scope, ResolvedUniverse)`; existing two methods stay (deprecated) for the agentic loop.
- Edit: `MorningScannerService` — add `scanScoped`; reuse `CandidateScoringEngine` unchanged.
- New handler: `/api/scope/candidates` reading the active scope.

Tests:
- `MorningScannerServiceScopedTest` — scoped scanner produces ≤ `maxCandidates`; respects truncation flag; never loads instruments outside scope.
- `ScannerQueryScopedTest` — uses `IN (...)` against `options_live_enriched` only.

DoD: scanner output size proportional to scope, not full universe. No regression in `Phase1SmokeTest`.

### Phase 5 — UI scope picker

Files to add/edit (UI is vanilla HTML/JS at `ui/scenario-research/`):
- Add: scope picker module — calls `/api/bootstrap/metadata`, shows underlying/expiry/strategy/strike inputs.
- Edit: existing scanner/structure panels to gate on active scope; add scope-change confirmation modal.
- Add: persistent scope chip (footer).

Tests:
- Manual: walk through login → metadata → pick scope → see candidates.
- Add Selenium-free smoke: hit endpoints with `curl` per the API table; assert payload sizes are bounded.

DoD: legacy "load everything on login" flow no longer executes when `scope.enabled=true`; UI never sees more than the scoped universe.

### Phase 6 — Remove two-phase legacy + flip default

- Delete `KiteLiveSessionManager.expandUniverse`, `expansionScheduler`, `EXPANSION_DELAY_SECONDS`.
- Remove `KiteInstrumentsDumpJob.downloadAtmOnly` (after agentic-loop callers move to `downloadForExpiry`).
- Default `scope.enabled=true` in `kite.properties.example`.
- Update `docs/agentic-live-trading-decision-loop.md` to reference scoped APIs; note that headless agentic callers must construct an explicit `Scope` instead of relying on auto-loaded universes.

DoD: app starts cold, idle JVM heap (after login but before scope activation) holds ≤ instrument_master row count × ~200 bytes; live subscription = 2 (spot keys only).

### Phase 7 — Guardrails + observability

- Wire hard/soft caps with `kite.properties` knobs.
- Add structured log lines at scope activation: `scope.activated underlying=NIFTY expiry=2026-04-30 universe_size=22 subscribed=24`.
- Add `subscribed_token_count` and `active_scope_count` gauges to `LiveStatusReport` JSON (consumed by `/api/live/status`).

DoD: rejecting an over-wide scope returns the exact error contract in §6; metadata endpoint completes in <100 ms on cold cache.

## 9. Definition of Done (top-level)

The redesign is complete when **all** of the following hold simultaneously:

- `mvn -DskipTests compile` succeeds; `mvn test` is green.
- App starts without calling `https://api.kite.trade/instruments/NFO` and without populating any subscription set.
- `POST /api/scope` is the only path that triggers Kite subscription.
- Backend rejects any scope yielding >100 strikes or any subscription request >250 tokens with a clear narrowing hint.
- UI receives only the scope's bounded universe + active subscription quotes; no broad instrument list ever reaches the browser.
- `Phase1SmokeTest`, `MorningScannerServiceTest`, all enrichment/aggregation tests, and existing research tests stay green; new `ScopeServiceTest`, `UniverseResolverTest`, `KiteSubscriptionManagerTest`, `MorningScannerServiceScopedTest` are added and green.
- Domain contracts in `docs/options-strategy-domain-contract.md` are not violated: weekly/monthly never mixed in a scope, point-in-time cohort matching unchanged, signed internal metrics unchanged.
- Idle heap (post-login, no active scope) is materially smaller than today's baseline — measure with `jcmd <pid> GC.heap_info` before/after on the same dataset and record the delta in the rollout report.

## 10. Risks & Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Agentic loop's headless callers still expect a wide universe | Med | Phase 4 keeps the legacy `MorningScannerService.scan` path; phase out only after agentic-loop owner migrates. |
| Atomic subscription swap races a polling cycle | Med | Wrap subscription in immutable record behind `AtomicReference`; poller reads-then-uses snapshot; covered by `KiteSubscriptionManagerTest`. |
| Scope persistence collides with existing day-scoped login | Low | Same per-day model as `KiteDailyTokenStore`; document in `CLAUDE.md`. |
| Soft-truncation hides a real problem | Low | Always emit `X-Truncated: true` header + structured log; reviewable in `LiveStatusReport`. |
| User loses subscription on UI refresh | Low | Scope is server-side; refresh re-reads `/api/bootstrap/metadata` and resumes the active scope without re-subscribing. |

## 11. Out of Scope

- Multi-user scope (single scope per process today; `user_id` reserved).
- Order placement (`/quote` only; no `/orders` integration).
- Cross-session scope sharing across days (deliberately day-bounded per design decision).
- Replacing the cached NFO dump with paginated/streaming reads — current cache is acceptable as long as nothing downstream retains the full list.

## 12. References

- `docs/options-strategy-domain-contract.md` — invariants the redesign must preserve
- `docs/scenario-research-workstation.md` — existing API/UI shape
- `docs/live-kite-overlay.md` — current live-data model
- `docs/agentic-live-trading-decision-loop.md` — agentic loop's instrument expectations
- Source: `KiteLiveSessionManager`, `KiteTickerSession`, `KiteInstrumentsDumpJob`, `KiteInstrumentFilter`, `ScannerQuery`, `MorningScannerService`, `ResearchConsoleServer`
