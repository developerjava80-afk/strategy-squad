# ARCHITECTURE AUDIT — PART 1
### Strategy Squad · Structure-Testing Console
### Chief Architect Deep Review

---

## 1. SYSTEM UNDERSTANDING SUMMARY

### 1.1 What the system does

The Strategy Squad research console is a **local historical structure-testing engine** for NIFTY and BANKNIFTY options. Given a multi-leg strategy definition (underlying, DTE, spot, strikes, entry prices), it:

1. **Resolves** each leg into a canonical cohort key (`CanonicalScenarioResolver`) using moneyness-bucket (50-point granularity) and time-bucket (15-minute calendar-minute buckets with ±96 range).
2. **Queries** QuestDB `options_enriched` table for matching historical observations.
3. **Aggregates** per-leg matches by `tradeDate|expiryDate`, then cross-joins legs on common date keys to build historical structure scenarios.
4. **Computes** compact metrics: entry premium distribution, expiry outcome, P&L statistics, premium trend windows, recommendation ranking, and matched cases.
5. **Serves** results via `com.sun.net.httpserver.HttpServer` on port 8080 with a static single-page UI.

### 1.2 Data flow

```
User inputs → parseStrategyDefinition()
  → StrategyAnalysisService.loadSnapshot()
    → per-leg: CanonicalScenarioResolver.resolve() → CanonicalCohortKey
    → per-leg: HistoricalContext.loadLegMatches() → SQL query with ±96 bucket range
    → per-leg: HistoricalContext.expiryValuesFor() → last observed price per instrument
    → aggregateLegMatches() → group by tradeDate|expiryDate, average within bucket
    → cross-leg join on common date keys → StrategyScenario list
    → per-scenario: P&L computed per-leg with sign awareness
    → StrategyAnalysisCalculator.calculate() → StrategyAnalysisSnapshot
    → buildRecommendations() → 9 candidate strategies scored and ranked
  → ResearchConsoleServer.toJson() → hand-rolled JSON
→ app.js applyStrategyAnalysis() → DOM updates
```

### 1.3 Key design decisions (as-built)

| Decision | Implementation | Concern level |
|---|---|---|
| "Entry premium" = gross sum of all leg prices | `totalEntryPremium += point.entryAverage()` regardless of side | **High** |
| "Expiry value" = gross sum of all leg terminal prices | `expiryValue += point.expiryAverage()` | **High** |
| P&L computed per-leg with side awareness | `isShort ? entry - expiry : expiry - entry` | Correct |
| ±96 bucket range for time matching | `BETWEEN bucketLo AND bucketHi` | Reasonable |
| Expiry value = last observed price per instrument | `ORDER BY exchange_ts`, last row wins | Reasonable but fragile |
| Anchor date = max trade date across legs | Two-pass loop in `analyzeDefinition()` | Correct but wasteful |
| Recommendation = weighted score of 5 mixed-unit terms | Fixed coefficients on different scales | **High** |
| All JSON hand-rolled via `String.formatted()` | ~400 lines of template strings | Fragile |
| No JDBC connection pooling | `DriverManager.getConnection()` per request | Acceptable for local tool |

---

## 2. ISSUE INVENTORY

### 2.1 P0 — CRITICAL (wrong answers or active trader deception)

#### P0-1: Recommendation score formula is dimensionally incoherent

**Location:** [StrategyAnalysisService.java](src/main/java/com/strategysquad/research/StrategyAnalysisService.java#L119-L124)

**Code:**
```java
double score = (richnessScore * 0.28d)      // premium points, range ~[-50, +50]
             + (avgPnl * 0.32d)              // premium points, range ~[-100, +100]
             + (winRate * 0.18d)             // percentage, range [0, 100]
             - (downsideSeverity * 0.14d)    // |premium points|, range [0, 200+]
             + (sampleScore * 0.08d);        // capped count, range [0, 100]
```

**Problem:** The five terms have different natural scales. `winRate` ranges 0–100 (percentage), `sampleScore` ranges 0–100 (count), but `richnessScore` and `avgPnl` are premium points (typically ±10 to ±50 for NIFTY). Contribution analysis for a typical NIFTY scenario:

| Term | Typical value | × Weight | Contribution |
|---|---|---|---|
| richnessScore | +15 | 0.28 | 4.2 |
| avgPnl | +5 | 0.32 | 1.6 |
| winRate | 55% | 0.18 | **9.9** |
| downsideSeverity | 45 pts | 0.14 | −6.3 |
| sampleScore | 100 | 0.08 | **8.0** |

Win rate and sample size **dominate** the ranking not because of their stated weights but because of their scale (0–100 vs ±50). The ranking is systematically biased toward strategies with high observation counts and moderate win rates, regardless of P&L magnitude.

**Violated contracts:**
- Domain contract §2.3: "Must remain explainable"
- Domain contract §7: "Never mix units silently"

**Impact:** The "Preferred", "Alternative", and "Avoid" recommendations may be systematically wrong.

---

#### P0-2: Gross-sum premium semantics for mixed-side strategies

**Location:** [StrategyAnalysisService.java](src/main/java/com/strategysquad/research/StrategyAnalysisService.java#L300-L303) (currentTotalPremium), lines 236–239 (scenario building), [app.js](ui/scenario-research/app.js#L340) (UI currentTotalPremium)

**Code (backend):**
```java
private static double currentTotalPremium(StrategyStructureDefinition definition) {
    return definition.legs().stream()
            .map(StrategyStructureDefinition.StrategyLeg::entryPrice)
            .mapToDouble(BigDecimal::doubleValue)
            .sum();  // sums ALL legs regardless of LONG/SHORT
}
```

**Code (UI):**
```javascript
currentTotalPremium: legsState.reduce((sum, leg) => sum + (Number(leg.entryPrice) || 0), 0)
```

**Code (historical scenario building):**
```java
for (Map<String, AggregatedLegPoint> legMap : alignedMaps) {
    totalEntryPremium += point.entryAverage();   // sums all legs
    expiryValue += point.expiryAverage();        // sums all legs
}
```

**Problem:** For strategies with mixed sides (Bull Call Spread, Bear Put Spread, Iron Condor, Iron Butterfly), the system adds all leg premiums rather than computing net debit/credit.

**Example — Bull Call Spread (Long CE 22500 @ 142.5, Short CE 22550 @ 92):**

| Metric | System shows | Trader expects |
|---|---|---|
| Current premium | 234.50 | 50.50 (net debit) |
| Avg entry | ~230 (gross historical sum) | ~50 (net historical debit) |
| Avg expiry value | ~80 (gross sum) | ~40 (net spread value) |
| Vs history | +4.50 | +0.50 |

**Internally consistent?** Yes — percentile compares gross-to-gross, and P&L is correctly computed per-leg with signs. The final P&L numbers are correct.

**Why still P0:** The domain contract §7 mandates unit clarity. A trader looking at "Current premium: 234.50" for a 50-point spread will immediately distrust the tool. Every premium-related metric (6 of 10 in the snapshot block plus the header chip) shows a number that no trader would recognize as their position's economics.

**Affected metrics:** `currentTotalPremium`, `averageEntryPremium`, `medianEntryPremium`, `currentVsHistoricalAverage`, `averageExpiryValue`, `averageExpiryPayout`, all `PremiumWindow` values, matched case `totalEntryPremium` and `expiryValue`, "Current premium" header chip.

**Unaffected metrics (correctly computed):** `averagePnl`, `medianPnl`, `winRatePct`, `bestCase`, `worstCase`, `buyerPnl`, `sellerPnl`, `tailLossP10`.

---

#### P0-3: Best/worst case violates payoff invariant for seller strategies

**Location:** [StrategyAnalysisCalculator.java](src/main/java/com/strategysquad/research/StrategyAnalysisCalculator.java#L172-L173)

**Code:**
```java
double bestCase = scenarios.stream().mapToDouble(StrategyScenario::selectedPnl).max().orElse(0);
double worstCase = scenarios.stream().mapToDouble(StrategyScenario::selectedPnl).min().orElse(0);
```

**Problem:** These are raw extremes from historical scenarios where the HISTORICAL premium may differ from the CURRENT premium. For a Short Straddle:

- Current premium collected: 277.50 (current CE + PE entry prices)
- Historical scenario where premium was 350 and both expired at 0: P&L = +350
- UI shows: **Best case: +350.00**

Domain contract §3.4: *"Short straddle — Max profit = total premium collected. Must never show: Profit > premium collected."*

The system shows a historical profit of 350 when the current premium collected is only 277.50, implying profit exceeding what's achievable in the current trade.

**Applies to:** SHORT_STRADDLE (§3.4), SHORT_STRANGLE (§3.6), all credit strategies (§3.8–§3.10).

**Required by §6.8:** Best/worst must be labeled *"Raw historical sample extreme"* — not implied as current-trade bounds.

---

### 2.2 P1 — SIGNIFICANT (confusing, incomplete, or domain-contract violations)

#### P1-1: No unit labels on any metric

**Violated contract:** §7 — "Every metric must clearly indicate one of: premium points, rupees, per lot, per structure, normalized %"

**Current state:** Neither the JSON API, UI HTML, nor CSV export includes units for any metric. Examples:
- "Avg entry: 142.50" — points? rupees? per lot?
- "Tail loss: −45.00" — per structure? per lot?
- "Vs history: +15.00" — points? percentage?

The experienced trader can likely infer premium points, but the contract explicitly requires it.

---

#### P1-2: Anchor date not exposed in strategy analysis response

**Location:** [StrategyAnalysisCalculator.java](src/main/java/com/strategysquad/research/StrategyAnalysisCalculator.java#L67) (anchorDate computed but not returned)

The `TimeframeAnalysisSnapshot` includes `anchorDate`, but `StrategyAnalysisSnapshot` does not. When a trader selects "1Y" timeframe, the window is trailing from the **latest matched historical date** — which could be weeks or months old. Without seeing the anchor date, the trader has no way to know whether "1Y" means the last 12 months or a stale 12-month window ending in the past.

**UI Guidance doc** acknowledges: *"fixed windows are trailing windows from the latest matched historical date"* — but the UI doesn't communicate this.

---

#### P1-3: Missing expectancy support metrics

**Violated contract:** §6.10 — "Prefer support from: average win, average loss, payoff ratio, expectancy, tail-loss view"

Currently computed: avg P&L, win rate, tail loss P10.
**Not computed:** average win (only winners), average loss (only losers), payoff ratio (avg win / avg loss), expectancy (win% × avg win − loss% × avg loss).

The system shows avg P&L and win rate alone, which §6.10 explicitly says is insufficient.

---

#### P1-4: "Vs history" sign convention not shown on screen

**Location:** [index.html](ui/scenario-research/index.html#L156) — label says "Vs history", [app.js](ui/scenario-research/app.js#L561) — shows `formatSigned(payload.snapshot.currentVsHistoricalAverage)`

The UI guidance doc defines: *"positive means richer than history, negative means cheaper than history."* But the UI itself shows only a signed number with no tooltip, legend, or parenthetical indicating the convention. A trader seeing "+15.00" must guess whether that means "15 points above average" or "15% richer."

---

#### P1-5: ExpiryOutcome win rate ambiguity

**Location:** [StrategyAnalysisCalculator.java](src/main/java/com/strategysquad/research/StrategyAnalysisCalculator.java#L189-L190)

```java
double selectedWinRate = scenarios.stream()
    .filter(item -> item.selectedPnl() > 0).count() * 100.0d / scenarios.size();
```

The Realized Expiry block shows:
- "Avg seller P&L" (both sides always shown)
- "Avg buyer P&L" (both sides always shown)
- "Win rate" (only selected side)

A seller seeing `Avg buyer P&L: +12.0` and `Win rate: 62%` might think the 62% is the buyer's win rate. It's actually the **selected orientation's** win rate. The UI doesn't indicate which side the win rate refers to.

---

#### P1-6: Historical best case can exceed current-trade theoretical bounds (labeling)

**Distinct from P0-3** (which is the invariant violation). This is about the UI label.

The HTML shows: `<span>Best / worst</span>` followed by the values. Domain contract §6.8 requires the label: *"Raw historical sample extreme."* The current label "Best / worst" invites the trader to interpret it as "my best/worst case outcome" for the current trade.

---

#### P1-7: "Observation" not clarified as non-trade

**Violated contract:** §5.1 — "Observation does not mean trade. Observation means: matched historical structure instance."

The UI shows `Observations: 413` with no tooltip or note. A trader may assume 413 actual trades occurred, which overstates confidence.

---

#### P1-8: No regime awareness in observation pooling

Historical observations from different market regimes (low-vol 2023, high-vol 2020) are pooled together. The premium windows (5Y/2Y/.../1M) partially address this by showing trend, but the snapshot metrics and P&L stats mix all regimes. There is no regime label, VIX band, or realized-vol indicator attached to matched cases.

Domain contract §2.2 acknowledges VIX as an "overlay, not pricing truth," which justifies not incorporating it into matching. But the UI guidance's "functional contract summary" requires outputs to be "economically safe and trader-readable" — and regime-blind stats can mislead in high-vol environments.

---

### 2.3 P2 — MINOR (code quality, developer confusion, or edge cases)

#### P2-1: BANKNIFTY bucket size inconsistency across layers

| Component | BANKNIFTY bucket | Purpose |
|---|---|---|
| `CanonicalScenarioResolver` | 50 | Moneyness bucketing |
| `StrategyAnalysisService.candidateDefinitions()` | 100 | Strike placement for candidates |
| `app.js pointBucketSize` | 100 | Default strike placement in UI |

Different purposes (moneyness granularity vs strike grid), but a developer reading the code would reasonably assume they should match. The previous fix intentionally changed the resolver to 50 for data alignment, but the other two layers weren't updated.

**Not a data bug** — candidate strikes at 100-point intervals resolve to valid moneyness buckets. But confusing.

---

#### P2-2: Wasted first pass in `analyzeDefinition()`

**Location:** [StrategyAnalysisService.java](src/main/java/com/strategysquad/research/StrategyAnalysisService.java#L169-L194) (first loop) vs lines 196–218 (second loop)

The first loop populates `legMaps` (never read after the loop) just to compute `anchorDate`. The second loop reloads the same data (cached by `HistoricalContext`) using the final `anchorDate`. The first loop should be replaced with a simple anchor-date-discovery pass.

---

#### P2-3: Duplicated statistical utilities

`FairValueSnapshotCalculator` implements `percentileRank()` and `quantile()`.
`StrategyAnalysisCalculator` independently implements `percentileRank()`, `percentileValue()`, and `median()`.
`TimeframeAnalysisSnapshotCalculator` delegates to `FairValueSnapshotCalculator.percentileRank()`.

Three different files, two independent implementations of percentile rank, no shared utility class.

---

#### P2-4: Hand-rolled JSON serialization (~400 lines)

`ResearchConsoleServer` has 8 `toJson()` methods totaling ~400 lines of `String.formatted()` templates. Edge cases in field values (e.g., strings containing `"`, `\n`, or `%`) could produce malformed JSON. The `escapeJson()` helper exists but only escapes `"` and `\` — not control characters.

---

#### P2-5: No validation of DTE input range

DTE is parsed from user input via `Integer.parseInt()` with no bounds check. Negative DTE produces `estimatedMinutesToExpiry = 0` and `timeBucket15m = 0`, which queries bucket range 0–96. DTE = 999 produces bucket 95,230 (no matching data, empty result). Neither case crashes, but both return meaningless results without error messaging.

---

#### P2-6: `PremiumWindow.currentVsHistoricalAverage` naming

The field name suggests a ratio ("vs") but the value is `currentTotalPremium - average` (a signed point difference). Developers may read "currentVsHistoricalAverage" as current/historicalAverage (a ratio). This matches the UI guidance definition but the Java field name is misleading.

---

#### P2-7: Expiry value relies on "last observed price" not settlement

**Location:** [StrategyAnalysisService.java](src/main/java/com/strategysquad/research/StrategyAnalysisService.java) — `HistoricalContext.loadExpiryValues()`

```java
// Iterates all rows ORDER BY exchange_ts, last row overwrites → last observed price
while (rs.next()) {
    expiryValueCache.put(rs.getString("instrument_id"), rs.getDouble("last_price"));
}
```

The "expiry value" for each instrument is the **last chronological price in the database**, not the official settlement price. For instruments that stopped trading before expiry (delisted, illiquid), this could produce incorrect terminal values. This is a data-quality concern rather than a code bug.

---

#### P2-8: No abort/cancel feedback in UI

When the user clicks "Run Analysis" while a previous request is in-flight, `strategyAbortController.abort()` is called. The aborted request silently returns (caught by `AbortError`). No user feedback indicates the previous query was cancelled.

---

## 3. METRIC AUDIT TABLE

Every metric produced by `StrategyAnalysisCalculator` and displayed in the UI, checked against all eight audit dimensions.

| # | Metric | Math correct | Econ correct | Unit explicit | Comparable to current trade | Misread risk | Payoff-safe | Domain-contract aligned | Issue refs |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Observations | ✓ | ✓ | N/A | N/A | **⚠** "trades" vs "instances" | N/A | **⚠** §5.1 | P1-7 |
| 2 | Avg entry | ✓ | **⚠** gross sum for spreads | **✗** no unit | ✓ self-consistent | **⚠⚠** trader expects net | ✓ | **✗** §7 | P0-2, P1-1 |
| 3 | Median entry | ✓ | **⚠** gross sum for spreads | **✗** | ✓ self-consistent | **⚠⚠** | ✓ | **✗** §7 | P0-2, P1-1 |
| 4 | Current percentile | ✓ | **⚠** percentile of gross sum | **✗** | ✓ self-consistent | **⚠** misleading for spreads | ✓ | **✗** §7 | P0-2 |
| 5 | Vs history | ✓ | **⚠** gross difference | **✗** pts vs % ambiguous | ✓ self-consistent | **⚠** sign convention unclear | ✓ | **✗** §6.9, §7 | P0-2, P1-4 |
| 6 | Avg expiry value | ✓ | **⚠** gross sum of terminal values | **✗** | **✗** not meaningful for spreads | **⚠⚠** | ✓ | **✗** §7 | P0-2 |
| 7 | Avg P&L | ✓ | ✓ correctly per-leg | **✗** | ✓ | Low | ✓ | **✗** §7 | P1-1 |
| 8 | Median P&L | ✓ | ✓ | **✗** | ✓ | Low | ✓ | **✗** §7 | P1-1 |
| 9 | Win rate | ✓ | ✓ | ✓ (% shown) | ✓ | **⚠** which side? | ✓ | ✓ | P1-5 |
| 10 | Best case | ✓ | ✓ historical | **✗** | **⚠** historical, not current | **⚠⚠⚠** | **✗** §3.4, §6.8 | **✗** §6.8 | P0-3, P1-6 |
| 11 | Worst case | ✓ | ✓ historical | **✗** | **⚠** historical, not current | **⚠⚠** | **⚠** | **✗** §6.8 | P0-3, P1-6 |
| 12 | Current premium (chip) | ✓ | **⚠** gross sum | **✗** | N/A | **⚠⚠⚠** | ✓ | **✗** §7 | P0-2 |
| 13 | Avg expiry payout | ✓ | **⚠** gross sum | **✗** | **✗** | **⚠** | ✓ | **✗** §7 | P0-2 |
| 14 | Avg seller P&L | ✓ | ✓ | **✗** | ✓ | Low | ✓ | **✗** §7 | P1-1 |
| 15 | Avg buyer P&L | ✓ | ✓ | **✗** | ✓ | Low | ✓ | **✗** §7 | P1-1 |
| 16 | Expiry win rate | ✓ | ✓ | ✓ | ✓ | **⚠** side ambiguity | ✓ | ✓ | P1-5 |
| 17 | Tail loss P10 | ✓ | ✓ | **✗** | ✓ | Low | ✓ | **✗** §7 | P1-1 |
| 18 | Downside profile | ✓ | ✓ | Text | N/A | Low | ✓ | ✓ | — |
| 19 | Premium windows | ✓ | **⚠** gross sums | **✗** | ✓ self-consistent | **⚠** | ✓ | **✗** §7 | P0-2 |
| 20 | Recommendation score | **✗** mixed units | **✗** biased | Hidden | N/A | N/A | N/A | **✗** §2.3, §7 | P0-1 |

**Summary:** 0 of 20 metrics fully pass all 8 dimensions. 6 metrics have **✗** on economic correctness. 17 of 20 have **✗** on unit explicitness. 2 metrics have **✗** on mathematical correctness (recommendation score).

---

## 4. STRATEGY PAYOFF AUDIT

For each supported strategy, checking payoff invariants from domain contract §3.

| Strategy | Orientation | Max profit invariant (§3) | Max loss invariant (§3) | `bestCase` safe? | `worstCase` safe? | `expiryValue` meaningful? | Net premium correct? |
|---|---|---|---|---|---|---|---|
| Single Option | BUYER | Unlimited (CE) / Spot (PE) | Premium paid | ✓ Unbounded OK | ⚠ Should ≥ −entryPrice | ✓ | ✓ Single leg |
| Long Straddle | BUYER | Unlimited | Total premium paid | ✓ | ⚠ Should ≥ −totalPremium | ✓ | ✓ Same-side legs |
| **Short Straddle** | **SELLER** | **Premium collected** | **Unlimited** | **✗ Can show profit > current premium** | ✓ | ✓ | ✓ Same-side legs |
| Long Strangle | BUYER | Unlimited | Total premium paid | ✓ | ⚠ Should ≥ −totalPremium | ✓ | ✓ Same-side legs |
| **Short Strangle** | **SELLER** | **Premium collected** | **Unlimited** | **✗ Same as Short Straddle** | ✓ | ✓ | ✓ Same-side legs |
| **Bull Call Spread** | BUYER | Strike diff − debit | Debit paid | ⚠ Historical max may exceed theoretical | ⚠ Should ≥ −net debit | **⚠ Gross sum** | **✗ Gross sum ≠ net debit** |
| **Bear Put Spread** | BUYER | Strike diff − debit | Debit paid | ⚠ Same | ⚠ Same | **⚠ Gross sum** | **✗ Gross sum ≠ net debit** |
| **Iron Condor** | SELLER | Net credit | Spread width − credit | **✗ Can exceed net credit** | ⚠ Historical, not theoretical | **⚠ Gross sum** | **✗ Gross sum ≠ net credit** |
| **Iron Butterfly** | SELLER | Net credit | Wing width − credit | **✗ Can exceed net credit** | ⚠ Historical, not theoretical | **⚠ Gross sum** | **✗ Gross sum ≠ net credit** |
| Custom Multi-Leg | Varies | §3.11: "do not guess" | §3.11 | ⚠ Unknown | ⚠ Unknown | **⚠ Gross sum** | **✗ Gross sum ≠ net** |

**Failing strategies:** 6 of 10 strategies have at least one payoff invariant concern. All mixed-side strategies (4 of 10) show gross premium instead of net.

---

## 5. SEMANTIC RISK ANALYSIS

Assessment of each user-facing term that could cause a trader to make a wrong decision.

### 5.1 HIGH RISK — Likely to cause wrong decisions

| Term shown | What trader reads | What system means | Gap | Impact |
|---|---|---|---|---|
| **"Current premium: 234.50"** (Bull Call Spread) | "My spread costs 234.50" | Gross sum of both leg premiums | **184 points** off from net debit of 50.50 | Trader may abandon analysis, distrust tool |
| **"Best case: +350"** (Short Straddle, current premium 277) | "I can make 350" | A historical scenario where premium was 350 and expired at 0 | **73 points above** what's achievable | Trader overestimates upside |
| **"Preferred: Iron Condor"** | "Best strategy for this setup" | Highest scorer in a biased formula where win-rate dominates | Ranking may favor moderate-win-rate strategies over higher-P&L strategies | **Wrong strategy selection** |

### 5.2 MEDIUM RISK — Could confuse but unlikely to cause wrong trades

| Term shown | What trader reads | What system means | Gap |
|---|---|---|---|
| "Observations: 413" | "413 trades happened" | 413 matched structure instances, many from same contracts | Overstates independence §5.1 |
| "Vs history: +15.00" | Unclear: +15 what? | Current gross premium − historical average gross premium, in points | No sign convention legend |
| "Win rate: 62.0%" | "I win 62% of the time" | 62% of historical matched scenarios had positive selected-side P&L | May not apply to current premium level |
| "1Y" timeframe | "Last 12 months from today" | 12 months trailing from latest matched date (could be months old) | Staleness not visible |
| "Avg expiry value: 80.00" | "My structure expires around 80" | Gross sum of all leg terminal prices | Meaningless for spreads |

### 5.3 LOW RISK — Technically imprecise but unlikely to mislead

| Term shown | What system means | Minor gap |
|---|---|---|
| "Avg P&L: +5.00" | Correctly computed per-leg P&L, averaged | No unit label |
| "Tail loss: −45.00" | P10 of selected-side P&L distribution | "P10" not mentioned in UI |
| "Downside profile: seller-side tail −45.00" | One-line description | Could be more actionable |

---

## 6. ROOT CAUSE ANALYSIS

The issues above trace to **three architectural root causes**:

### Root Cause A: Gross-Sum Premium Model

**Decision:** `totalEntryPremium` and `expiryValue` are computed as the sum of all leg prices, regardless of long/short side.

**Why it was likely made:** Simplicity — just add all legs. P&L is computed per-leg with correct signs, so the final P&L is correct. For same-side strategies (straddles, strangles), gross sum = net premium, so the model works.

**Where it breaks:** Every mixed-side strategy (spreads, condors, butterflies) shows premium metrics that no trader would recognize.

**Blast radius:** 8 of 20 display metrics, the header chip, all premium windows, matched case display, the CSV export, and the "currentVsHistoricalAverage" comparison.

**Fix direction:** Compute `netPremium = Σ(signedLegPremium)` where `signedLegPremium = entryPrice` for LONG and `−entryPrice` for SHORT. Treat SHORT legs as credits and LONG legs as debits. Use net premium for display and percentile; keep gross sum internally if needed for computation.

---

### Root Cause B: Unnormalized Recommendation Scoring

**Decision:** Five terms with different natural scales are combined with fixed weights into a single score.

**Why it was likely made:** Quick implementation of a "good enough" ranking. The weights (0.28, 0.32, 0.18, 0.14, 0.08) suggest an intentional priority ordering, but without normalization they don't produce that ordering.

**Where it breaks:** Any scenario where premium-denominated terms (richnessScore, avgPnl) are small in magnitude relative to the percentage/count terms (winRate, sampleScore).

**Fix direction:** Normalize each term to a common 0–100 scale before applying weights, OR use z-score normalization across candidates, OR switch to a rank-based scoring system.

---

### Root Cause C: Missing Metric Metadata Layer

**Decision:** Metrics are bare `double` values in Java records and bare numbers in JSON/HTML. No unit, no label qualifier, no range indication.

**Why it was likely made:** The system is a local research tool, and the developer assumed the single user (themselves) would know the context.

**Where it breaks:** Domain contract §7 compliance. Also breaks when any other person (or the same person months later) reads the output.

**Fix direction:** Add a `MetricValue` concept (value + unit + optional qualifier) or at minimum attach units/qualifiers at the JSON and UI rendering layer. For best/worst, add the §6.8 label: "Raw historical sample extreme."

---

## APPENDIX A: FILES REVIEWED

| File | Lines | Status |
|---|---|---|
| `CanonicalScenarioResolver.java` | ~48 | Fully reviewed |
| `CanonicalCohortKey.java` | ~8 | Fully reviewed |
| `FairValueCohortService.java` | ~65 | Fully reviewed |
| `FairValueSnapshotCalculator.java` | ~130 | Fully reviewed |
| `FairValueSnapshot.java` | — | Fully reviewed |
| `TimeframeAnalysisService.java` | ~80 | Fully reviewed |
| `TimeframeAnalysisSnapshotCalculator.java` | ~150 | Fully reviewed |
| `TimeframeAnalysisSnapshot.java` | ~18 | Fully reviewed |
| `ForwardOutcomeCohortService.java` | — | Fully reviewed |
| `ForwardOutcomeSnapshot.java` | — | Fully reviewed |
| `DiagnosticsCohortService.java` | — | Fully reviewed |
| `DiagnosticsSnapshot.java` | — | Fully reviewed |
| `StrategyAnalysisService.java` | ~500 | Fully reviewed |
| `StrategyAnalysisCalculator.java` | ~260 | Fully reviewed |
| `StrategyAnalysisSnapshot.java` | ~90 | Fully reviewed |
| `StrategyStructureDefinition.java` | — | Fully reviewed |
| `ResearchConsoleServer.java` | ~900 | Fully reviewed |
| `ResearchWorkspaceService.java` | — | Known to exist |
| `ui/scenario-research/app.js` | ~800 | Fully reviewed |
| `ui/scenario-research/index.html` | ~300 | Fully reviewed |
| `docs/options-strategy-domain-contract.md` | — | Fully reviewed |
| `docs/scenario-research-workstation.md` | — | Fully reviewed |
| `docs/developer-notes.md` | — | Fully reviewed |
| `docs/ui-guidance-algo-testing-console.md` | — | Fully reviewed |

## APPENDIX B: ISSUE CROSS-REFERENCE

| ID | Severity | Title | Root cause | Affected files |
|---|---|---|---|---|
| P0-1 | Critical | Recommendation score dimensionally incoherent | B | `StrategyAnalysisService.java` |
| P0-2 | Critical | Gross-sum premium for mixed-side strategies | A | `StrategyAnalysisService.java`, `StrategyAnalysisCalculator.java`, `app.js` |
| P0-3 | Critical | Best/worst violates seller payoff invariants | C | `StrategyAnalysisCalculator.java`, `index.html` |
| P1-1 | Significant | No unit labels on any metric | C | All output files |
| P1-2 | Significant | Anchor date missing from strategy analysis | — | `StrategyAnalysisSnapshot.java`, `StrategyAnalysisCalculator.java` |
| P1-3 | Significant | Missing expectancy support metrics | — | `StrategyAnalysisCalculator.java` |
| P1-4 | Significant | "Vs history" sign convention not shown | C | `index.html`, `app.js` |
| P1-5 | Significant | Expiry win rate side ambiguity | C | `StrategyAnalysisCalculator.java`, `index.html` |
| P1-6 | Significant | Best/worst label missing "historical extreme" qualifier | C | `index.html` |
| P1-7 | Significant | "Observation" not clarified as non-trade | C | `index.html` |
| P1-8 | Significant | No regime awareness in observation pooling | — | `StrategyAnalysisService.java` |
| P2-1 | Minor | BANKNIFTY bucket inconsistency across layers | — | `CanonicalScenarioResolver.java`, `StrategyAnalysisService.java`, `app.js` |
| P2-2 | Minor | Wasted first pass in analyzeDefinition | — | `StrategyAnalysisService.java` |
| P2-3 | Minor | Duplicated statistical utilities | — | `FairValueSnapshotCalculator.java`, `StrategyAnalysisCalculator.java` |
| P2-4 | Minor | Hand-rolled JSON (~400 lines) | — | `ResearchConsoleServer.java` |
| P2-5 | Minor | No DTE input validation | — | `ResearchConsoleServer.java` |
| P2-6 | Minor | `currentVsHistoricalAverage` naming misleading | — | `StrategyAnalysisSnapshot.java` |
| P2-7 | Minor | Expiry value = last observed price, not settlement | — | `StrategyAnalysisService.java` |
| P2-8 | Minor | No abort/cancel UI feedback | — | `app.js` |

---

**END OF PART 1 — AUDIT ONLY, NO FIXES APPLIED**

*Part 2 will address fix prioritization, implementation plan, and execution.*
