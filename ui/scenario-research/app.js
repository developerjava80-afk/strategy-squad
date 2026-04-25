const form = document.getElementById("scenario-form");

const els = {
    strategyMode: document.getElementById("strategy-mode"),
    underlying: document.getElementById("underlying"),
    expiryType: document.getElementById("expiry-type"),
    timeframe: document.getElementById("timeframe"),
    scenarioQty: document.getElementById("scenario-qty"),
    scenarioQtyHint: document.getElementById("scenario-qty-hint"),
    scenarioQtyError: document.getElementById("scenario-qty-error"),
    addLegButton: document.getElementById("add-leg-button"),
    legsContainer: document.getElementById("legs-container"),
    baselineStamp: document.getElementById("baseline-stamp"),
    liveRefreshStamp: document.getElementById("live-refresh-stamp"),
    combinedDeltaTracking: document.getElementById("combined-delta-tracking"),
    combinedTotalDelta: document.getElementById("combined-total-delta"),
    legsTotalPremium: document.getElementById("legs-total-premium"),
    legsTotalDelta: document.getElementById("legs-total-delta"),
    legsTotalMtm: document.getElementById("legs-total-mtm"),
    runAnalysisButton: document.getElementById("run-analysis-button"),
    clearDataButton: document.getElementById("clear-data-button"),
    downloadReportButton: document.getElementById("download-report-button"),
    statusLine: document.getElementById("status-line"),
    marketUnderlyingLabel: document.getElementById("market-underlying-label"),
    marketUnderlyingChip: document.getElementById("market-underlying-chip"),
    livePremiumLabel: document.getElementById("live-premium-label"),
    liveStatusChip: document.getElementById("live-status-chip"),
    liveSpotChip: document.getElementById("live-spot-chip"),
    livePremiumChip: document.getElementById("live-premium-chip"),
    livePnlLabel: document.getElementById("live-pnl-label"),
    livePnlChip: document.getElementById("live-pnl-chip"),
    liveLotChip: document.getElementById("live-lot-chip"),
    liveConfidenceChip: document.getElementById("live-confidence-chip"),
    liveLastTickChip: document.getElementById("live-last-tick-chip"),
    strategyChip: document.getElementById("strategy-chip"),
    orientationChip: document.getElementById("orientation-chip"),
    timeframeChip: document.getElementById("timeframe-chip"),
    summaryPremiumLabel: document.getElementById("summary-premium-label"),
    premiumChip: document.getElementById("premium-chip"),
    legsMarketValueHeader: document.getElementById("legs-market-value-header"),
    terminalLivePnl: document.getElementById("terminal-live-pnl"),
    terminalLegsGrid: document.getElementById("terminal-legs-grid"),
    snapshotObservations: document.getElementById("snapshot-observations"),
    snapshotAverageEntry: document.getElementById("snapshot-average-entry"),
    snapshotMedianEntry: document.getElementById("snapshot-median-entry"),
    snapshotPercentile: document.getElementById("snapshot-percentile"),
    snapshotVsHistory: document.getElementById("snapshot-vs-history"),
    snapshotExpiryValue: document.getElementById("snapshot-expiry-value"),
    snapshotAveragePnl: document.getElementById("snapshot-average-pnl"),
    snapshotMedianPnl: document.getElementById("snapshot-median-pnl"),
    snapshotWinRate: document.getElementById("snapshot-win-rate"),
    snapshotBestWorst: document.getElementById("snapshot-bounds"),
    snapshotBoundsLabel: document.getElementById("snapshot-bounds-label"),
    snapshotAnchorNote: document.getElementById("snapshot-anchor-note"),
    snapshotWinRateLabel: document.getElementById("snapshot-win-rate-label"),
    snapshotPayoffRatio: document.getElementById("snapshot-payoff-ratio"),
    snapshotExpectancy: document.getElementById("snapshot-expectancy"),
    snapshotAverageEntryLabel: document.getElementById("snapshot-average-entry-label"),
    snapshotMedianEntryLabel: document.getElementById("snapshot-median-entry-label"),
    snapshotVsHistoryNote: document.getElementById("snapshot-vs-history-note"),
    trendAvgHeader: document.getElementById("trend-avg-header"),
    trendMedianHeader: document.getElementById("trend-median-header"),
    trendVsCurrentHeader: document.getElementById("trend-vs-current-header"),
    timeframeTableBody: document.getElementById("timeframe-table-body"),
    trendLine: document.getElementById("trend-line"),
    trendPoints: document.getElementById("trend-points"),
    trendLabels: document.getElementById("trend-labels"),
    currentPriceLine: document.getElementById("current-price-line"),
    expiryPayout: document.getElementById("expiry-payout"),
    expiryPayoutLabel: document.getElementById("expiry-payout-label"),
    expirySellerPnl: document.getElementById("expiry-seller-pnl"),
    expiryBuyerPnl: document.getElementById("expiry-buyer-pnl"),
    expiryWinRate: document.getElementById("expiry-seller-win-rate"),
    expiryBuyerWinRate: document.getElementById("expiry-buyer-win-rate"),
    expiryTailLoss: document.getElementById("expiry-tail-loss"),
    expiryDownside: document.getElementById("expiry-downside"),
    expiryExpectancy: document.getElementById("expiry-expectancy"),
    recommendationPreferredTitle: document.getElementById("recommendation-preferred-title"),
    recommendationPreferredReason: document.getElementById("recommendation-preferred-reason"),
    recommendationAlternativeTitle: document.getElementById("recommendation-alternative-title"),
    recommendationAlternativeReason: document.getElementById("recommendation-alternative-reason"),
    recommendationAvoidTitle: document.getElementById("recommendation-avoid-title"),
    recommendationAvoidReason: document.getElementById("recommendation-avoid-reason"),
    insightPremium: document.getElementById("insight-premium"),
    insightPremiumDetail: document.getElementById("insight-premium-detail"),
    insightEdge: document.getElementById("insight-edge"),
    insightEdgeDetail: document.getElementById("insight-edge-detail"),
    insightRisk: document.getElementById("insight-risk"),
    insightRiskDetail: document.getElementById("insight-risk-detail"),
    insightVerdict: document.getElementById("insight-verdict"),
    snapshotAveragePnlRaw: document.getElementById("snapshot-average-pnl-raw"),
    snapshotPayoffRatioRaw: document.getElementById("snapshot-payoff-ratio-raw"),
    expiryTailLossRaw: document.getElementById("expiry-tail-loss-raw"),
    addLegDialog: document.getElementById("add-leg-dialog"),
    algUnderlying: document.getElementById("alg-underlying"),
    algOptionType: document.getElementById("alg-option-type"),
    algSide: document.getElementById("alg-side"),
    algStrike: document.getElementById("alg-strike"),
    algExpiry: document.getElementById("alg-expiry"),
    algEntryPrice: document.getElementById("alg-entry-price"),
    algLabel: document.getElementById("alg-label"),
    algCancelBtn: document.getElementById("alg-cancel-btn"),
    simOpenButton: document.getElementById("sim-open-button"),
    simDialog: document.getElementById("sim-dialog"),
    simDate: document.getElementById("sim-date"),
    simSpeed: document.getElementById("sim-speed"),
    simConfirmBtn: document.getElementById("sim-confirm-btn"),
    simCancelBtn: document.getElementById("sim-cancel-btn"),
    simBar: document.getElementById("sim-bar"),
    simBarDate: document.getElementById("sim-bar-date"),
    simBarTime: document.getElementById("sim-bar-time"),
    simBarProgress: document.getElementById("sim-bar-progress"),
    simStopButton: document.getElementById("sim-stop-button"),
    positionsBookedPnl: document.getElementById("positions-booked-pnl"),
    positionsLivePnl: document.getElementById("positions-live-pnl"),
    positionsTotalPnl: document.getElementById("positions-total-pnl"),
    exitSelectedButton: document.getElementById("exit-selected-button"),
    exitAllButton: document.getElementById("exit-all-button"),
    deltaAdjustmentMessage: document.getElementById("delta-adjustment-message"),
    deltaAdjustmentLogBody: document.getElementById("delta-adjustment-log-body"),
    deltaAdjustmentLogEmpty: document.getElementById("delta-adjustment-log-empty")
};

const strategyConfigs = {
    SINGLE_OPTION: {
        label: "Single Option",
        orientation: "BUYER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "Single leg", optionType: "CE", side: "LONG", strike: roundToBucket(spot, bucket), entryPrice: 142.5, editableType: true, editableSide: false })
        ]
    },
    LONG_STRADDLE: {
        label: "Long Straddle",
        orientation: "BUYER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "ATM call", optionType: "CE", side: "LONG", strike: roundToBucket(spot, bucket), entryPrice: 142.5 }),
            createLegState({ label: "ATM put", optionType: "PE", side: "LONG", strike: roundToBucket(spot, bucket), entryPrice: 135 })
        ]
    },
    SHORT_STRADDLE: {
        label: "Short Straddle",
        orientation: "SELLER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "ATM call", optionType: "CE", side: "SHORT", strike: roundToBucket(spot, bucket), entryPrice: 142.5 }),
            createLegState({ label: "ATM put", optionType: "PE", side: "SHORT", strike: roundToBucket(spot, bucket), entryPrice: 135 })
        ]
    },
    LONG_STRANGLE: {
        label: "Long Strangle",
        orientation: "BUYER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "OTM put", optionType: "PE", side: "LONG", strike: roundToBucket(spot - (2 * bucket), bucket), entryPrice: 88 }),
            createLegState({ label: "OTM call", optionType: "CE", side: "LONG", strike: roundToBucket(spot + (2 * bucket), bucket), entryPrice: 76 })
        ]
    },
    SHORT_STRANGLE: {
        label: "Short Strangle",
        orientation: "SELLER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "OTM put", optionType: "PE", side: "SHORT", strike: roundToBucket(spot - (2 * bucket), bucket), entryPrice: 88 }),
            createLegState({ label: "OTM call", optionType: "CE", side: "SHORT", strike: roundToBucket(spot + (2 * bucket), bucket), entryPrice: 76 })
        ]
    },
    BULL_CALL_SPREAD: {
        label: "Bull Call Spread",
        orientation: "BUYER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "Long call", optionType: "CE", side: "LONG", strike: roundToBucket(spot, bucket), entryPrice: 142.5 }),
            createLegState({ label: "Short call", optionType: "CE", side: "SHORT", strike: roundToBucket(spot + bucket, bucket), entryPrice: 92 })
        ]
    },
    BEAR_PUT_SPREAD: {
        label: "Bear Put Spread",
        orientation: "BUYER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "Long put", optionType: "PE", side: "LONG", strike: roundToBucket(spot, bucket), entryPrice: 136 }),
            createLegState({ label: "Short put", optionType: "PE", side: "SHORT", strike: roundToBucket(spot - bucket, bucket), entryPrice: 86 })
        ]
    },
    IRON_CONDOR: {
        label: "Iron Condor",
        orientation: "SELLER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "Long put wing", optionType: "PE", side: "LONG", strike: roundToBucket(spot - (3 * bucket), bucket), entryPrice: 36 }),
            createLegState({ label: "Short put", optionType: "PE", side: "SHORT", strike: roundToBucket(spot - (2 * bucket), bucket), entryPrice: 82 }),
            createLegState({ label: "Short call", optionType: "CE", side: "SHORT", strike: roundToBucket(spot + (2 * bucket), bucket), entryPrice: 79 }),
            createLegState({ label: "Long call wing", optionType: "CE", side: "LONG", strike: roundToBucket(spot + (3 * bucket), bucket), entryPrice: 34 })
        ]
    },
    IRON_BUTTERFLY: {
        label: "Iron Butterfly",
        orientation: "SELLER",
        custom: false,
        createLegs: (spot, bucket) => [
            createLegState({ label: "Long put wing", optionType: "PE", side: "LONG", strike: roundToBucket(spot - (2 * bucket), bucket), entryPrice: 48 }),
            createLegState({ label: "Short put", optionType: "PE", side: "SHORT", strike: roundToBucket(spot, bucket), entryPrice: 138 }),
            createLegState({ label: "Short call", optionType: "CE", side: "SHORT", strike: roundToBucket(spot, bucket), entryPrice: 142.5 }),
            createLegState({ label: "Long call wing", optionType: "CE", side: "LONG", strike: roundToBucket(spot + (2 * bucket), bucket), entryPrice: 44 })
        ]
    },
    CUSTOM_MULTI_LEG: {
        label: "Custom Multi-Leg",
        orientation: "BUYER",
        custom: true,
        createLegs: (spot, bucket) => [
            createLegState({ label: "Custom leg 1", optionType: "CE", side: "LONG", strike: roundToBucket(spot, bucket), entryPrice: 142.5, editableType: true, editableSide: true })
        ]
    }
};

const pointBucketSize = {
    NIFTY: 50,
    BANKNIFTY: 50
};

const lotSizeByUnderlying = {
    NIFTY: 65,
    BANKNIFTY: 30
};

function lotSizeForUnderlying(underlying) {
    return lotSizeByUnderlying[String(underlying || "").toUpperCase()] || 1;
}

function defaultScenarioQty(underlying) {
    return lotSizeForUnderlying(underlying);
}

let legsState = [];
let strategyAbortController = null;
let liveAbortController = null;
let latestReportPayload = null;
let liveSyncTimer = null;
let liveBatchTimer = null;
let liveHydrationMuted = false;
let liveStructureFetchInFlight = false;
let entryBaselinePinned = false;
let latestLiveStructure = null;
let latestLiveStatus = null;
let latestSimulationStatus = null;
let latestPublishedStrategyState = null;
let previousLiveLegPrices = [];
let strategyDteDays = 4;
let strategySpot = Number.NaN;
let baselineCapturedAt = null;
let analysisRunId = 0;
let liveStateDirty = false;
let scenarioQtyState = defaultScenarioQty("NIFTY");
let lastDeltaAdjustmentTs = null;
let pendingAdjustmentSinceTs = null;
let deltaAdjustmentLog = [];
let lastDeltaAdjustmentLogKey = null;
let positionSessionId = null;
let positionSessionStatus = "DRAFT";
let positionSessionCreatedAt = null;
let activeLiveRunReportContext = null;
let activeSimulationRunReportContext = null;
const ACTIVE_POSITION_SESSION_KEY = "scenario-research.active-position-session";
const LIVE_POLL_MS = 1500;
const LIVE_BATCH_MS = 125;
const STALE_AFTER_MS = 2000;
const DISCONNECTED_AFTER_MS = 5000;
const EXCHANGE_TIME_ZONE = "Asia/Kolkata";

boot();

form.addEventListener("input", handleFormInput);
form.addEventListener("change", handleFormChange);
els.legsContainer?.addEventListener("input", handleFormInput);
els.legsContainer?.addEventListener("change", handleFormChange);
els.addLegButton.addEventListener("click", showAddLegDialog);
els.algCancelBtn.addEventListener("click", () => els.addLegDialog.close());
els.addLegDialog.addEventListener("submit", (e) => {
    e.preventDefault();
    addCustomLeg();
    els.addLegDialog.close();
});
els.runAnalysisButton.addEventListener("click", runAnalysis);
els.clearDataButton?.addEventListener("click", clearData);
els.downloadReportButton.addEventListener("click", downloadReport);
els.exitSelectedButton?.addEventListener("click", () => executeManualExit("manual_exit_selected"));
els.exitAllButton?.addEventListener("click", () => executeManualExit("manual_exit_all"));

// ─── Simulation controls ───────────────────────────────────────────────────

let simStatusTimer = null;
const SIM_POLL_MS = 1500;

/** Called once after boot to check whether simulation endpoints are available */
async function initSimulationUI() {
    try {
        const status = await fetchJson(`${apiBase()}/api/simulation/status`);
        els.simOpenButton.hidden = false;
        applySimStatus(status);
    } catch {
        // Simulation endpoints not available – keep button hidden
    }
}

function applySimStatus(status) {
    const wasActive = Boolean(latestSimulationStatus?.active);
    latestSimulationStatus = status || null;
    if (!status) return;
    if (status.active) {
        els.simBar.hidden = false;
        els.simBarDate.textContent = status.replayDate || "-";
        els.simBarTime.textContent = status.replayTimeIst || "--:--:--";
        const pct = status.totalTicks > 0
            ? Math.round((status.ticksReplayed / status.totalTicks) * 100)
            : 0;
        els.simBarProgress.textContent = `${pct}%`;
        startSimPolling();
    } else {
        els.simBar.hidden = true;
        stopSimPolling();
        if (wasActive && activeSimulationRunReportContext) {
            void finalizeSimulationRunReport();
        }
    }
}

function startSimPolling() {
    if (simStatusTimer) return;
    simStatusTimer = window.setInterval(async () => {
        try {
            const status = await fetchJson(`${apiBase()}/api/simulation/status`);
            applySimStatus(status);
        } catch {
            // ignore transient errors
        }
    }, SIM_POLL_MS);
}

function stopSimPolling() {
    if (simStatusTimer) {
        window.clearInterval(simStatusTimer);
        simStatusTimer = null;
    }
}

els.simOpenButton.addEventListener("click", () => {
    // Default replay date to yesterday
    const d = new Date();
    d.setDate(d.getDate() - 1);
    const iso = d.toISOString().slice(0, 10);
    els.simDate.value = iso;
    els.simDialog.showModal();
});

els.simCancelBtn.addEventListener("click", () => els.simDialog.close());

els.simDialog.addEventListener("submit", async (e) => {
    e.preventDefault();
    const date = els.simDate.value;
    const speed = els.simSpeed.value;
    if (!date) return;
    els.simDialog.close();
    try {
        const body = new URLSearchParams({ date, speed });
        const resp = await fetch(`${apiBase()}/api/simulation/start`, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
            body: body.toString()
        });
        const status = await resp.json();
        if (!resp.ok) {
            alert("Could not start simulation: " + (status.error || resp.status));
            return;
        }
        activeSimulationRunReportContext = buildRunReportContext("simulation", {
            startTime: new Date().toISOString(),
            runId: `simulation-${date}-${Date.now()}`,
            replayDate: date
        });
        applySimStatus(status);
    } catch (err) {
        alert("Simulation error: " + err.message);
    }
});

els.simStopButton.addEventListener("click", async () => {
    try {
        const resp = await fetch(`${apiBase()}/api/simulation/stop`, { method: "POST" });
        const status = await resp.json();
        applySimStatus(status);
    } catch (err) {
        alert("Stop error: " + err.message);
    }
});

function apiBase() {
    return window.location.protocol === "file:" ? "http://localhost:8080" : "";
}

function activePositionSessionIdFromStorage() {
    try {
        return window.localStorage.getItem(ACTIVE_POSITION_SESSION_KEY);
    } catch (_error) {
        return null;
    }
}

function persistActivePositionSessionId(sessionId) {
    try {
        if (!sessionId) {
            window.localStorage.removeItem(ACTIVE_POSITION_SESSION_KEY);
            return;
        }
        window.localStorage.setItem(ACTIVE_POSITION_SESSION_KEY, sessionId);
    } catch (_error) {
        // ignore storage failures
    }
}

function createPositionSessionId() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return window.crypto.randomUUID();
    }
    return `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function openQuantityForLegState(leg) {
    const value = toFiniteNumber(leg?.quantity);
    if (value === null) {
        return 0;
    }
    return Math.max(0, Math.round(value));
}

function originalQuantityForLegState(leg) {
    const value = toFiniteNumber(leg?.originalQuantity);
    if (value !== null) {
        return Math.max(0, Math.round(value));
    }
    return openQuantityForLegState(leg);
}

function bookedPnlForLegState(leg) {
    const value = toFiniteNumber(leg?.bookedPnl);
    return value === null ? 0 : value;
}

function deriveLegStatusValue(leg) {
    const explicit = String(leg?.status || "").trim().toUpperCase();
    if (explicit) {
        return explicit;
    }
    const openQuantity = openQuantityForLegState(leg);
    const originalQuantity = originalQuantityForLegState(leg);
    if (openQuantity <= 0) {
        return "CLOSED";
    }
    if (openQuantity < originalQuantity) {
        return "PARTIALLY_EXITED";
    }
    return "OPEN";
}

function derivePositionSessionStatus(legs = legsState) {
    if (!Array.isArray(legs) || legs.length === 0) {
        return "DRAFT";
    }
    const anyOpen = legs.some((leg) => openQuantityForLegState(leg) > 0);
    const anyExited = legs.some((leg) => openQuantityForLegState(leg) < originalQuantityForLegState(leg));
    if (!anyOpen) {
        return "CLOSED";
    }
    return anyExited ? "PARTIALLY_EXITED" : "OPEN";
}

function hasOpenPositionSession() {
    return Boolean(positionSessionId) && legsState.some((leg) => openQuantityForLegState(leg) > 0);
}

function positionEditingLocked() {
    return hasOpenPositionSession();
}

function renderPositionControlState() {
    const locked = positionEditingLocked();
    if (els.strategyMode) {
        els.strategyMode.disabled = locked;
    }
    if (els.underlying) {
        els.underlying.disabled = locked;
    }
    if (els.expiryType) {
        els.expiryType.disabled = locked;
    }
    if (els.scenarioQty) {
        els.scenarioQty.disabled = locked;
    }
    if (els.addLegButton) {
        els.addLegButton.disabled = locked;
    }
    const openCount = legsState.filter((leg) => openQuantityForLegState(leg) > 0).length;
    const selectedCount = legsState.filter((leg) => leg.selected && openQuantityForLegState(leg) > 0).length;
    if (els.exitSelectedButton) {
        els.exitSelectedButton.disabled = selectedCount === 0;
    }
    if (els.exitAllButton) {
        els.exitAllButton.disabled = openCount === 0;
    }
}

function createLegId() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return `leg-${window.crypto.randomUUID()}`;
    }
    return `leg-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

async function fetchPositionSession(sessionId) {
    return fetchJson(`${apiBase()}/api/position-sessions/${encodeURIComponent(sessionId)}`);
}

async function savePositionSessionSnapshot(snapshot) {
    return fetchJson(`${apiBase()}/api/position-sessions`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json;charset=UTF-8"
        },
        body: JSON.stringify(snapshot)
    });
}

async function applyPositionSessionAction(sessionId, payload) {
    return fetchJson(`${apiBase()}/api/position-sessions/${encodeURIComponent(sessionId)}/actions`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json;charset=UTF-8"
        },
        body: JSON.stringify(payload)
    });
}

function buildPositionSessionLeg(leg) {
    return {
        legId: leg.legId || createLegId(),
        label: leg.label,
        optionType: leg.optionType,
        side: leg.side,
        strike: Number(leg.strike || 0),
        expiryDate: leg.expiryDate || leg.expiry || null,
        symbol: leg.symbol || "",
        instrumentId: leg.instrumentId || "",
        entryPrice: Number(leg.entryPrice || 0),
        originalQuantity: originalQuantityForLegState(leg),
        openQuantity: openQuantityForLegState(leg),
        bookedPnl: bookedPnlForLegState(leg),
        status: deriveLegStatusValue(leg),
        createdAt: leg.createdAt || new Date().toISOString(),
        updatedAt: new Date().toISOString()
    };
}

function buildPositionSessionSnapshot() {
    const createdAt = positionSessionCreatedAt || new Date().toISOString();
    return {
        sessionId: positionSessionId || createPositionSessionId(),
        mode: els.strategyMode.value,
        strategyLabel: currentStrategyLabel(),
        orientation: currentOrientation(),
        underlying: els.underlying.value,
        expiryType: els.expiryType.value,
        timeframe: els.timeframe.value,
        dte: strategyDteDays,
        spot: Number.isFinite(strategySpot) ? strategySpot : 0,
        scenarioQty: scenarioQtyState,
        createdAt,
        updatedAt: new Date().toISOString(),
        lastDeltaAdjustmentTs,
        status: derivePositionSessionStatus(),
        legs: legsState.map(buildPositionSessionLeg),
        auditLog: Array.isArray(deltaAdjustmentLog) ? deltaAdjustmentLog.slice(0, 200) : []
    };
}

function sessionLegUiFlags(mode, index) {
    if (mode === "CUSTOM_MULTI_LEG") {
        return {
            editableType: true,
            editableSide: true,
            removable: true
        };
    }
    return {
        editableType: false,
        editableSide: false,
        removable: false
    };
}

function applyPositionSessionSnapshot(snapshot, { restored = false } = {}) {
    if (!snapshot || !Array.isArray(snapshot.legs)) {
        return false;
    }
    positionSessionId = snapshot.sessionId || positionSessionId || createPositionSessionId();
    positionSessionCreatedAt = snapshot.createdAt || positionSessionCreatedAt || new Date().toISOString();
    persistActivePositionSessionId(positionSessionId);
    positionSessionStatus = snapshot.status || derivePositionSessionStatus();
    lastDeltaAdjustmentTs = snapshot.lastDeltaAdjustmentTs || null;
    const transientAdjustmentEntries = Array.isArray(deltaAdjustmentLog)
        ? deltaAdjustmentLog.filter((entry) => {
            const actionType = reportTimelineActionType(entry);
            return !entry?.actionId && (actionType === "SKIP" || actionType === "DELAYED");
        })
        : [];
    const persistedAuditLog = Array.isArray(snapshot.auditLog) ? snapshot.auditLog.slice(0, 200) : [];
    const mergedAuditLog = [...persistedAuditLog];
    const seenAuditKeys = new Set(mergedAuditLog.map((entry) => deltaAdjustmentLogKey(entry)));
    transientAdjustmentEntries.forEach((entry) => {
        const key = deltaAdjustmentLogKey(entry);
        if (!seenAuditKeys.has(key)) {
            mergedAuditLog.push(entry);
            seenAuditKeys.add(key);
        }
    });
    deltaAdjustmentLog = mergedAuditLog.slice(0, 200);
    lastDeltaAdjustmentLogKey = deltaAdjustmentLog.length > 0 ? deltaAdjustmentLog[0].actionId || deltaAdjustmentLogKey : null;
    if (snapshot.mode) {
        els.strategyMode.value = snapshot.mode;
    }
    if (snapshot.underlying) {
        els.underlying.value = snapshot.underlying;
    }
    if (snapshot.expiryType) {
        els.expiryType.value = snapshot.expiryType;
    }
    if (snapshot.timeframe) {
        els.timeframe.value = snapshot.timeframe;
    }
    if (Number.isFinite(Number(snapshot.dte))) {
        strategyDteDays = Math.round(Number(snapshot.dte));
    }
    if (Number.isFinite(Number(snapshot.spot))) {
        strategySpot = Number(Number(snapshot.spot).toFixed(2));
    }
    if (Number.isFinite(Number(snapshot.scenarioQty))) {
        scenarioQtyState = Math.max(0, Math.round(Number(snapshot.scenarioQty)));
        if (els.scenarioQty) {
            els.scenarioQty.value = String(scenarioQtyState);
        }
    }
    legsState = snapshot.legs.map((leg, index) => {
        const uiFlags = sessionLegUiFlags(snapshot.mode, index);
        const openQuantity = Number(leg.openQuantity || 0);
        const originalQuantity = Number(leg.originalQuantity || openQuantity || scenarioQtyState || currentScenarioLotSize());
        const entryPrice = Number(leg.entryPrice || 0);
        const strike = Number(leg.strike || 0);
        return {
            legId: leg.legId || createLegId(),
            label: leg.label || `Leg ${index + 1}`,
            optionType: leg.optionType || "CE",
            side: leg.side || "LONG",
            strike,
            distance: moneynessDistanceFromStrike(leg.optionType || "CE", strike, strategySpot),
            entryPrice,
            lockedEntryPrice: entryPrice,
            quantity: Math.max(0, Math.round(openQuantity)),
            originalQuantity: Math.max(0, Math.round(originalQuantity)),
            bookedPnl: Number(leg.bookedPnl || 0),
            status: deriveLegStatusValue({
                quantity: openQuantity,
                originalQuantity,
                status: leg.status
            }),
            symbol: leg.symbol || "",
            instrumentId: leg.instrumentId || "",
            expiryDate: leg.expiryDate || "",
            selected: false,
            createdAt: leg.createdAt || positionSessionCreatedAt,
            updatedAt: leg.updatedAt || snapshot.updatedAt || positionSessionCreatedAt,
            editableType: uiFlags.editableType,
            editableSide: uiFlags.editableSide,
            removable: uiFlags.removable
        };
    });
    entryBaselinePinned = true;
    baselineCapturedAt = restored
        ? (snapshot.createdAt ? new Date(snapshot.createdAt) : new Date())
        : new Date();
    renderScenarioQtyUi();
    renderDeltaAdjustmentLog();
    return true;
}

async function restorePersistedPositionSession() {
    const activeSessionId = activePositionSessionIdFromStorage();
    if (!activeSessionId) {
        return false;
    }
    try {
        const snapshot = await fetchPositionSession(activeSessionId);
        return applyPositionSessionSnapshot(snapshot, { restored: true });
    } catch (_error) {
        persistActivePositionSessionId("");
        return false;
    }
}

function syncLegMetadataFromLiveStructure(structure = latestLiveStructure) {
    const liveLegs = Array.isArray(structure?.legs) ? structure.legs : [];
    legsState = legsState.map((leg, index) => {
        const liveLeg = liveLegs[index] || {};
        return {
            ...leg,
            instrumentId: liveLeg.instrumentId || leg.instrumentId || "",
            symbol: liveLeg.tradingSymbol || liveLeg.symbol || leg.symbol || "",
            expiryDate: resolveLegExpiryDateText(liveLeg) !== "-" ? resolveLegExpiryDateText(liveLeg) : (leg.expiryDate || ""),
            updatedAt: new Date().toISOString()
        };
    });
}

async function persistCurrentPositionSession() {
    if (!positionSessionId) {
        return null;
    }
    const snapshot = buildPositionSessionSnapshot();
    const saved = await savePositionSessionSnapshot(snapshot);
    applyPositionSessionSnapshot(saved);
    return saved;
}

async function ensurePositionSessionInitialized() {
    if (hasOpenPositionSession()) {
        return false;
    }
    if (!latestLiveStructure || !Array.isArray(latestLiveStructure.legs) || latestLiveStructure.legs.length === 0) {
        return false;
    }
    const nowIso = new Date().toISOString();
    const nextSessionId = createPositionSessionId();
    positionSessionId = nextSessionId;
    positionSessionCreatedAt = nowIso;
    syncLegMetadataFromLiveStructure(latestLiveStructure);
    legsState = legsState.map((leg, index) => {
        const liveLeg = latestLiveStructure.legs[index] || {};
        const liveEntry = toFiniteNumber(liveLeg.lastPrice);
        const entryPrice = liveEntry === null ? Number(leg.entryPrice || 0) : Number(liveEntry.toFixed(2));
        const openQuantity = resolvedQuantityForLeg(leg, liveLeg, currentScenarioLotSize());
        return {
            ...leg,
            legId: leg.legId || createLegId(),
            entryPrice,
            lockedEntryPrice: entryPrice,
            quantity: openQuantity,
            originalQuantity: openQuantity,
            bookedPnl: 0,
            status: openQuantity > 0 ? "OPEN" : "CLOSED",
            createdAt: nowIso,
            updatedAt: nowIso,
            selected: false
        };
    });
    entryBaselinePinned = true;
    baselineCapturedAt = new Date(nowIso);
    const saved = await persistCurrentPositionSession();
    if (saved) {
        setStatus(`Position session created and persisted: ${positionSessionId}.`);
    }
    return true;
}

function buildPositionActionLeg(index, actionType, exitedQuantity, reason, message, overrides = {}) {
    const leg = legsState[index];
    const liveLeg = (Array.isArray(latestLiveStructure?.legs) ? latestLiveStructure.legs : [])[index] || {};
    const lastPrice = overrides.exitPrice === null
        ? null
        : (Object.prototype.hasOwnProperty.call(overrides, "exitPrice")
            ? toFiniteNumber(overrides.exitPrice)
            : toFiniteNumber(liveLeg.lastPrice));
    const currentVolume = toFiniteNumber(liveLeg.currentVolume);
    if (!leg || !leg.legId) {
        return null;
    }
    if (exitedQuantity > 0 && lastPrice === null) {
        return null;
    }
    return {
        legId: overrides.legId || leg.legId,
        entryPrice: Object.prototype.hasOwnProperty.call(overrides, "entryPrice") ? toFiniteNumber(overrides.entryPrice) : null,
        exitPrice: lastPrice,
        addedQuantity: Math.max(0, Math.round(Number(overrides.addedQuantity || 0))),
        exitedQuantity,
        delta2m: toFiniteNumber(liveLeg.deltaResponse2m),
        delta5m: toFiniteNumber(liveLeg.deltaResponse5m),
        deltaSod: toFiniteNumber(liveLeg.deltaResponseSod),
        currentVolume: currentVolume === null ? null : Math.round(currentVolume),
        dayAverageVolume: toFiniteNumber(liveLeg.dayAverageVolume),
        adjustmentActionType: overrides.adjustmentActionType || null,
        label: overrides.label || leg.label || liveLeg.label || "",
        optionType: overrides.optionType || liveLeg.optionType || leg.optionType || "CE",
        side: overrides.side || liveLeg.side || leg.side || "LONG",
        strike: Object.prototype.hasOwnProperty.call(overrides, "strike")
            ? toFiniteNumber(overrides.strike)
            : (toFiniteNumber(liveLeg.strike) ?? toFiniteNumber(leg.strike)),
        reason,
        message,
        symbol: overrides.symbol || liveLeg.tradingSymbol || liveLeg.symbol || leg.symbol || "",
        instrumentId: overrides.instrumentId || liveLeg.instrumentId || leg.instrumentId || "",
        expiryDate: overrides.expiryDate || (resolveLegExpiryDateText(liveLeg) !== "-" ? resolveLegExpiryDateText(liveLeg) : (leg.expiryDate || ""))
    };
}

function findMatchingLegIndexForOutcome(outcome) {
    const instrumentId = String(outcome?.instrumentId || "").trim();
    if (instrumentId) {
        const matchByInstrument = legsState.findIndex((leg) => String(leg.instrumentId || "").trim() === instrumentId);
        if (matchByInstrument >= 0) {
            return matchByInstrument;
        }
    }
    const optionType = String(outcome?.optionType || "").trim().toUpperCase();
    const side = String(outcome?.side || "").trim().toUpperCase();
    const strike = toFiniteNumber(outcome?.strike);
    if (!optionType || !side || strike === null) {
        return -1;
    }
    return legsState.findIndex((leg) =>
        String(leg.optionType || "").trim().toUpperCase() === optionType
        && String(leg.side || "").trim().toUpperCase() === side
        && toFiniteNumber(leg.strike) === strike
        && openQuantityForLegState(leg) > 0
    );
}

function buildAddedPositionActionLeg(outcome) {
    const addedQuantity = Math.max(0, Number(outcome?.newQuantity || 0) - Number(outcome?.oldQuantity || 0));
    const entryPrice = toFiniteNumber(outcome?.marketPrice);
    if (addedQuantity <= 0 || entryPrice === null) {
        return null;
    }
    const targetIndex = findMatchingLegIndexForOutcome(outcome);
    if (targetIndex >= 0) {
        return buildPositionActionLeg(
            targetIndex,
            "delta_adjustment",
            0,
            outcome.reason || outcome.code || "delta_adjustment",
            outcome.message || null,
            {
                entryPrice,
                addedQuantity,
                adjustmentActionType: "ADD",
                label: outcome.leg || legsState[targetIndex]?.label || "Leg",
                optionType: outcome.optionType || legsState[targetIndex]?.optionType || "CE",
                side: outcome.side || legsState[targetIndex]?.side || "LONG",
                strike: outcome.strike,
                instrumentId: outcome.instrumentId || legsState[targetIndex]?.instrumentId || "",
                symbol: outcome.symbol || legsState[targetIndex]?.symbol || "",
                expiryDate: outcome.expiryDate || legsState[targetIndex]?.expiryDate || "",
                exitPrice: null
            }
        );
    }
    return {
        legId: createLegId(),
        entryPrice,
        exitPrice: null,
        addedQuantity,
        exitedQuantity: 0,
        delta2m: toFiniteNumber(outcome.delta2m),
        delta5m: toFiniteNumber(outcome.delta5m),
        deltaSod: toFiniteNumber(outcome.deltaSod),
        currentVolume: toFiniteNumber(outcome.currentVolume),
        dayAverageVolume: toFiniteNumber(outcome.dayAverageVolume),
        adjustmentActionType: "ADD",
        label: outcome.leg || "Added leg",
        optionType: outcome.optionType || "CE",
        side: outcome.side || "LONG",
        strike: toFiniteNumber(outcome.strike),
        reason: outcome.reason || outcome.code || "delta_adjustment",
        message: outcome.message || null,
        symbol: outcome.symbol || "",
        instrumentId: outcome.instrumentId || "",
        expiryDate: outcome.expiryDate || ""
    };
}

async function executeManualExit(actionType) {
    if (!positionSessionId) {
        setStatus("Create or restore a position session before exiting legs.");
        return;
    }
    const indexes = actionType === "manual_exit_all"
        ? legsState.map((_, index) => index).filter((index) => openQuantityForLegState(legsState[index]) > 0)
        : legsState.map((leg, index) => leg.selected ? index : -1).filter((index) => index >= 0 && openQuantityForLegState(legsState[index]) > 0);
    if (!indexes.length) {
        setStatus(actionType === "manual_exit_all" ? "No open legs available for Exit All." : "Select one or more open legs to exit.");
        return;
    }
    const legActions = [];
    for (const index of indexes) {
        const leg = legsState[index];
        const exitedQuantity = openQuantityForLegState(leg);
        const liveLeg = (Array.isArray(latestLiveStructure?.legs) ? latestLiveStructure.legs : [])[index] || {};
        const exitPrice = toFiniteNumber(liveLeg.lastPrice);
        if (exitPrice === null) {
            setStatus(`Latest market price unavailable for ${leg.label}. Exit skipped.`);
            return;
        }
        legActions.push(buildPositionActionLeg(
            index,
            actionType,
            exitedQuantity,
            actionType,
            `Exited ${leg.label} qty ${exitedQuantity} at price ${formatNumber(exitPrice, 2)}`
        ));
    }
    try {
        const updated = await applyPositionSessionAction(positionSessionId, {
            sessionId: positionSessionId,
            actionType,
            timestamp: new Date().toISOString(),
            lastDeltaAdjustmentTs,
            legs: legActions.filter(Boolean)
        });
        applyPositionSessionSnapshot(updated);
        renderLegs();
        renderHeader();
        if (els.deltaAdjustmentMessage) {
            const recentEntries = Array.isArray(updated.auditLog) ? updated.auditLog.slice(0, legActions.length) : [];
            const firstEntry = recentEntries[0];
            els.deltaAdjustmentMessage.hidden = false;
            els.deltaAdjustmentMessage.textContent = legActions.length === 1 && firstEntry
                ? `Exited ${firstEntry.legLabel || "leg"} qty ${firstEntry.exitedQuantity} at price ${formatNumber(firstEntry.exitPrice || 0, 2)}, booked pnl = ${formatResolvedRupees(firstEntry.bookedPnl || 0)}`
                : `Exited ${legActions.length} legs and updated booked pnl.`;
        }
    } catch (error) {
        setStatus(`Unable to exit positions: ${error.message}`);
    }
}

async function persistDeltaAdjustmentOutcome(outcome) {
    if (!positionSessionId || !outcome) {
        return false;
    }
    const actionType = String(outcome.actionType || "REDUCE").trim().toUpperCase();
    const targetIndex = findMatchingLegIndexForOutcome(outcome);
    const exitedQuantity = Math.max(0, Number(outcome.oldQuantity || 0) - Number(outcome.newQuantity || 0));
    let legAction = null;
    if (actionType === "ADD") {
        legAction = buildAddedPositionActionLeg(outcome);
    } else if (targetIndex >= 0) {
        legAction = buildPositionActionLeg(
            targetIndex,
            "delta_adjustment",
            exitedQuantity,
            outcome.reason || outcome.code || "delta_adjustment",
            outcome.message || null,
            {
                adjustmentActionType: "REDUCE"
            }
        );
    }
    const request = {
        sessionId: positionSessionId,
        actionType: "delta_adjustment",
        timestamp: outcome.timestamp || new Date().toISOString(),
        lastDeltaAdjustmentTs: outcome.updatedLastAdjustmentTs || lastDeltaAdjustmentTs,
        legs: [
            legAction
        ].filter(Boolean).map((entry) => ({
            ...entry,
            entryPrice: Object.prototype.hasOwnProperty.call(entry, "entryPrice") ? toFiniteNumber(entry.entryPrice) : null,
            delta2m: toFiniteNumber(outcome.delta2m),
            delta5m: toFiniteNumber(outcome.delta5m),
            deltaSod: toFiniteNumber(outcome.deltaSod),
            currentVolume: toFiniteNumber(outcome.currentVolume),
            dayAverageVolume: toFiniteNumber(outcome.dayAverageVolume),
            triggerType: outcome.triggerType || null,
            reasonCode: outcome.reasonCode || outcome.code || null,
            livePnlPoints: toFiniteNumber(outcome.livePnlPoints),
            livePnlChange2mPoints: toFiniteNumber(outcome.livePnlChange2mPoints),
            livePnlChange5mPoints: toFiniteNumber(outcome.livePnlChange5mPoints),
            netDelta2m: toFiniteNumber(outcome.netDelta2m),
            netDelta5m: toFiniteNumber(outcome.netDelta5m),
            netDeltaSod: toFiniteNumber(outcome.netDeltaSod),
            postAdjNetDelta: toFiniteNumber(outcome.postAdjNetDelta),
            improvementAbsDelta: toFiniteNumber(outcome.improvementAbsDelta),
            improvementRatio: toFiniteNumber(outcome.improvementRatio),
            underlyingDirection: outcome.underlyingDirection || null,
            profitAlignment: outcome.profitAlignment || null,
            volumeConfirmed: typeof outcome.volumeConfirmed === "boolean" ? outcome.volumeConfirmed : null,
            volumeBypassed: typeof outcome.volumeBypassed === "boolean" ? outcome.volumeBypassed : null,
            thetaScore: toFiniteNumber(outcome.thetaScore),
            liquidityScore: toFiniteNumber(outcome.liquidityScore),
            score: toFiniteNumber(outcome.score),
            adjustmentActionType: actionType,
            reason: outcome.reason || outcome.code || "delta_adjustment",
            message: outcome.message || null
        }))
    };
    if (!request.legs.length) {
        return false;
    }
    const updated = await applyPositionSessionAction(positionSessionId, request);
    applyPositionSessionSnapshot(updated);
    renderLegs();
    renderHeader();
    return true;
}

async function boot() {
    const authenticated = await ensureAuthenticated();
    if (!authenticated) {
        return;
    }
    populateStrategyOptions();
    const restored = await restorePersistedPositionSession();
    if (!restored) {
        resetScenarioQtyForUnderlying();
        renderDeltaAdjustmentLog();
    }
    await bootstrapSpotFromLive(els.underlying.value);
    if (restored) {
        setPlaceholderResults();
        renderLegs();
        renderHeader();
        setStatus(`Restored persisted position session ${positionSessionId}.`);
    } else {
        initializeStrategy();
    }
    startLiveSync();
    initSimulationUI();
}

async function bootstrapSpotFromLive(underlying) {
    try {
        const spot = await fetchJson(`${apiBase()}/api/live/spot?underlying=${encodeURIComponent(underlying)}`);
        const resolved = Number(spot?.price);
        if (Number.isFinite(resolved)) {
            strategySpot = Number(resolved.toFixed(2));
            return true;
        }
    } catch (error) {
        // Spot resolution failed.
    }
    strategySpot = Number.NaN;
    return false;
}

async function ensureAuthenticated() {
    try {
        const status = await fetchJson(`${apiBase()}/api/auth/status`);
        if (status.requiresLogin) {
            const next = encodeURIComponent(window.location.pathname + window.location.search);
            window.location.replace(`/login.html?next=${next}`);
            return false;
        }
        return true;
    } catch (error) {
        return true;
    }
}

function populateStrategyOptions() {
    els.strategyMode.innerHTML = Object.entries(strategyConfigs)
        .map(([value, config]) => `<option value="${value}">${config.label}</option>`)
        .join("");
}

function initializeStrategy() {
    positionSessionId = null;
    positionSessionStatus = "DRAFT";
    positionSessionCreatedAt = null;
    persistActivePositionSessionId("");
    resetLegsForStrategy();
    renderLegs();
    renderHeader();
    renderScenarioQtyUi();
    setPlaceholderResults();
    resetLiveStrip();
    if (!Number.isFinite(strategySpot)) {
        setStatus("Spot unavailable. Waiting for live/close spot before seeding legs.");
    }
}

function handleFormInput(event) {
    if (liveHydrationMuted) {
        return;
    }
    if (event.target.matches("[data-leg-index][data-field='selected']")) {
        const index = Number(event.target.dataset.legIndex);
        if (legsState[index]) {
            legsState[index].selected = Boolean(event.target.checked);
            renderPositionControlState();
        }
        return;
    }
    if (event.target === els.scenarioQty) {
        entryBaselinePinned = false;
        baselineCapturedAt = null;
        clearLockedEntryBaseline();
        if (applyScenarioQtyFromInput()) {
            renderLegs();
            renderHeader();
            refreshLiveOverlay();
        } else {
            renderScenarioQtyUi();
        }
        return;
    }
    if (event.target.matches("[data-leg-index][data-field='strike']")) {
        entryBaselinePinned = false;
        baselineCapturedAt = null;
        clearLockedEntryBaseline();
        clearDeltaAdjustmentState();
        syncDistanceFromStrike(Number(event.target.dataset.legIndex));
    }
    if (event.target.matches("[data-leg-index][data-field='distance']")) {
        entryBaselinePinned = false;
        baselineCapturedAt = null;
        clearLockedEntryBaseline();
        clearDeltaAdjustmentState();
        syncStrikeFromDistance(Number(event.target.dataset.legIndex));
    }
    if (event.target.matches("[data-leg-index][data-field='entryPrice'], [data-leg-index][data-field='optionType'], [data-leg-index][data-field='side']")) {
        entryBaselinePinned = false;
        baselineCapturedAt = null;
        clearLockedEntryBaseline();
        clearDeltaAdjustmentState();
        syncLegStateFromDom(Number(event.target.dataset.legIndex));
        if (event.target.matches("[data-leg-index][data-field='optionType']")) {
            syncDistanceFromStrike(Number(event.target.dataset.legIndex));
        }
    }
    renderHeader();
    renderLegs();
    refreshLiveOverlay();
}

function handleFormChange(event) {
    if (liveHydrationMuted) {
        return;
    }
    if (event.target.matches("[data-leg-index][data-field='selected']")) {
        return;
    }
    if (event.target === els.strategyMode) {
        entryBaselinePinned = false;
        baselineCapturedAt = null;
        clearLockedEntryBaseline();
        clearDeltaAdjustmentState();
        resetLegsForStrategy();
        renderLegs();
    }
    if (event.target === els.underlying || event.target === els.expiryType) {
        entryBaselinePinned = false;
        baselineCapturedAt = null;
        clearLockedEntryBaseline();
        clearDeltaAdjustmentState();
        if (event.target === els.underlying) {
            resetScenarioQtyForUnderlying();
        } else {
            renderScenarioQtyUi();
        }
        bootstrapSpotFromLive(els.underlying.value)
            .finally(() => {
                resetLegsForStrategy();
                renderLegs();
                renderHeader();
                refreshLiveOverlay();
            });
        return;
    }
    renderHeader();
    refreshLiveOverlay();
}

function createLegState({
    label,
    optionType,
    side,
    strike,
    entryPrice,
    editableType = false,
    editableSide = false,
    removable = false
}) {
    return {
        legId: createLegId(),
        label,
        optionType,
        side,
        strike,
        distance: 0,
        entryPrice,
        lockedEntryPrice: null,
        quantity: scenarioQtyState,
        originalQuantity: scenarioQtyState,
        bookedPnl: 0,
        status: "OPEN",
        symbol: "",
        instrumentId: "",
        expiryDate: "",
        selected: false,
        createdAt: null,
        updatedAt: null,
        editableType,
        editableSide,
        removable
    };
}

function clearLockedEntryBaseline() {
    legsState = legsState.map((leg) => ({
        ...leg,
        lockedEntryPrice: null
    }));
}

function currentScenarioLotSize() {
    return lotSizeForUnderlying(els.underlying?.value);
}

function resetScenarioQtyForUnderlying() {
    scenarioQtyState = defaultScenarioQty(els.underlying?.value);
    if (els.scenarioQty) {
        els.scenarioQty.value = String(scenarioQtyState);
    }
    renderScenarioQtyUi();
}

function validateScenarioQtyValue(rawValue) {
    const lotSize = currentScenarioLotSize();
    if (rawValue === null || rawValue === undefined || String(rawValue).trim() === "") {
        return { valid: false, error: "Qty is required.", lotSize };
    }
    const parsed = Number(rawValue);
    if (!Number.isInteger(parsed)) {
        return { valid: false, error: "Qty must be an integer.", lotSize };
    }
    if (parsed < lotSize) {
        return { valid: false, error: `Minimum Qty is ${lotSize}.`, lotSize };
    }
    if ((parsed % lotSize) !== 0) {
        return { valid: false, error: `Qty must be a multiple of ${lotSize}.`, lotSize };
    }
    return { valid: true, quantity: parsed, lotSize };
}

function renderScenarioQtyUi(validation = null) {
    const result = validation || validateScenarioQtyValue(els.scenarioQty ? els.scenarioQty.value : scenarioQtyState);
    const lotSize = result.lotSize || currentScenarioLotSize();
    if (els.scenarioQty) {
        els.scenarioQty.min = String(lotSize);
        els.scenarioQty.step = String(lotSize);
        els.scenarioQty.required = true;
        els.scenarioQty.setCustomValidity(result.valid ? "" : result.error || "Invalid Qty");
    }
    if (els.scenarioQtyHint) {
        els.scenarioQtyHint.textContent = `Qty must be multiple of ${lotSize} (${els.underlying.value})`;
    }
    if (els.scenarioQtyError) {
        els.scenarioQtyError.textContent = result.valid ? "" : (result.error || "");
    }
    return result;
}

function applyScenarioQtyToLegs(quantity) {
    legsState = legsState.map((leg) => ({
        ...leg,
        quantity,
        originalQuantity: quantity
    }));
}

function applyScenarioQtyFromInput() {
    const result = renderScenarioQtyUi();
    if (!result.valid) {
        return false;
    }
    scenarioQtyState = result.quantity;
    clearDeltaAdjustmentState();
    applyScenarioQtyToLegs(result.quantity);
    return true;
}

function clearDeltaAdjustmentState() {
    if (!hasOpenPositionSession()) {
        positionSessionId = null;
        positionSessionStatus = "DRAFT";
        positionSessionCreatedAt = null;
        persistActivePositionSessionId("");
    }
    lastDeltaAdjustmentTs = null;
    pendingAdjustmentSinceTs = null;
    deltaAdjustmentLog = [];
    lastDeltaAdjustmentLogKey = null;
    if (els.deltaAdjustmentMessage) {
        els.deltaAdjustmentMessage.hidden = true;
        els.deltaAdjustmentMessage.textContent = "";
    }
    renderDeltaAdjustmentLog();
}

function deltaAdjustmentLogKey(entry) {
    if (entry.actionId) {
        return entry.actionId;
    }
    return [
        entry.code || "",
        entry.actionType || "",
        entry.leg || "",
        entry.legId || "",
        entry.oldQuantity ?? "",
        entry.exitedQuantity ?? "",
        entry.newQuantity ?? "",
        entry.applied ? (entry.timestamp || "") : (entry.updatedLastAdjustmentTs || entry.reason || "")
    ].join("|");
}

function formatAdjustmentTimestamp(timestamp) {
    if (!timestamp) {
        return "-";
    }
    return new Date(timestamp).toLocaleTimeString("en-IN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
}

function formatAdjustmentDelta(entry) {
    return [
        entry.delta2m,
        entry.delta5m,
        entry.deltaSod
    ].map((value) => {
        const number = toFiniteNumber(value);
        return number === null ? "-" : formatSigned(number, 2);
    }).join(" / ");
}

function formatAdjustmentVolume(entry) {
    const current = toFiniteNumber(entry.currentVolume);
    const baseline = toFiniteNumber(entry.dayAverageVolume);
    const currentText = current === null ? "-" : formatNumber(current, 0);
    const baselineText = baseline === null ? "-" : formatNumber(baseline, 0);
    return `${currentText} / ${baselineText}`;
}

function formatAdjustmentBookedPnl(entry) {
    const value = toFiniteNumber(entry.bookedPnl);
    return value === null ? "-" : formatResolvedRupees(value);
}

function formatActionType(entry) {
    const actionType = String(entry.actionType || "").trim();
    if (!actionType) {
        return entry.applied ? "delta_adjustment" : "delta_skip";
    }
    return actionType.replace(/_/g, " ");
}

function renderDeltaAdjustmentLog() {
    if (!els.deltaAdjustmentLogBody || !els.deltaAdjustmentLogEmpty) {
        return;
    }
    els.deltaAdjustmentLogEmpty.hidden = deltaAdjustmentLog.length > 0;
    els.deltaAdjustmentLogBody.innerHTML = deltaAdjustmentLog.map((entry) => `
        <tr>
            <td class="mono">${escapeHtml(formatAdjustmentTimestamp(entry.timestamp))}</td>
            <td>${escapeHtml(formatActionType(entry))}</td>
            <td>${escapeHtml(entry.legLabel || entry.leg || "-")}</td>
            <td class="mono">${escapeHtml(`${entry.oldQuantity ?? "-"} -> ${entry.remainingQuantity ?? entry.newQuantity ?? "-"}`)}</td>
            <td class="mono ${mtmClass(toFiniteNumber(entry.bookedPnl))}">${escapeHtml(formatAdjustmentBookedPnl(entry))}</td>
            <td class="mono">${escapeHtml(formatAdjustmentDelta(entry))}</td>
            <td class="mono">${escapeHtml(formatAdjustmentVolume(entry))}</td>
            <td>${escapeHtml(entry.reason || entry.code || "-")}</td>
        </tr>
    `).join("");
}

async function applyDeltaAdjustmentOutcome(outcome, nextLastAdjustmentTs) {
    if (!positionSessionId) {
        return;
    }
    if (nextLastAdjustmentTs) {
        lastDeltaAdjustmentTs = nextLastAdjustmentTs;
    }
    if (!outcome) {
        return;
    }
    const logKey = deltaAdjustmentLogKey(outcome);
    if (logKey === lastDeltaAdjustmentLogKey) {
        return;
    }
    if (outcome.applied && outcome.message && els.deltaAdjustmentMessage) {
        els.deltaAdjustmentMessage.hidden = false;
        els.deltaAdjustmentMessage.textContent = outcome.message;
    }
    let persisted = false;
    if (positionSessionId) {
        try {
            persisted = await persistDeltaAdjustmentOutcome(outcome);
        } catch (error) {
            setStatus(`Unable to persist delta adjustment: ${error.message}`);
        }
        if (persisted) {
            return;
        }
    }
    lastDeltaAdjustmentLogKey = logKey;
    deltaAdjustmentLog = [outcome, ...deltaAdjustmentLog].slice(0, 200);
    renderDeltaAdjustmentLog();
    logLiveDiagnostic("delta-adjustment", outcome);
}

function roundToBucket(value, bucketSize) {
    return Math.round(value / bucketSize) * bucketSize;
}

function bucketSize() {
    return pointBucketSize[els.underlying.value] || 50;
}

function resetLegsForStrategy() {
    if (!Number.isFinite(strategySpot)) {
        legsState = [];
        els.addLegButton.hidden = true;
        return;
    }
    const strategy = strategyConfigs[els.strategyMode.value];
    const spot = strategySpot;
    legsState = strategy.createLegs(spot, bucketSize()).map((leg) => ({
        ...leg,
        distance: moneynessDistanceFromStrike(leg.optionType, leg.strike, spot)
    }));
    els.addLegButton.hidden = false;
}

function showAddLegDialog() {
    if (!Number.isFinite(strategySpot)) {
        return;
    }
    // Pre-fill sensible defaults
    if (els.algUnderlying) {
        els.algUnderlying.value = els.underlying.value;
    }
    if (els.algStrike) {
        els.algStrike.value = roundToBucket(strategySpot, bucketSize());
    }
    if (els.algLabel) {
        els.algLabel.value = "";
    }
    els.addLegDialog.showModal();
}

function addCustomLeg() {
    clearDeltaAdjustmentState();
    const spot = strategySpot;
    const index = legsState.length + 1;
    const optionType = els.algOptionType ? els.algOptionType.value : "CE";
    const side = els.algSide ? els.algSide.value : "LONG";
    const strike = els.algStrike ? Number(els.algStrike.value) || roundToBucket(spot, bucketSize()) : roundToBucket(spot, bucketSize());
    const entryPrice = els.algEntryPrice ? Number(els.algEntryPrice.value) || 40 : 40;
    const labelInput = els.algLabel ? els.algLabel.value.trim() : "";
    const label = labelInput || `Manual leg ${index}`;
    const expiryRaw = els.algExpiry ? els.algExpiry.value : "";
    const expiry = expiryRaw || "";
    const newLeg = createLegState({
        label,
        optionType,
        side,
        strike,
        entryPrice,
        editableType: true,
        editableSide: true,
        removable: true
    });
    newLeg.expiry = expiry;
    newLeg.distance = moneynessDistanceFromStrike(optionType, strike, spot);
    legsState.push(newLeg);
    renderLegs();
    renderHeader();
}

function removeCustomLeg(index) {
    if (!legsState[index]?.removable || legsState.length <= 1) {
        return;
    }
    clearDeltaAdjustmentState();
    legsState.splice(index, 1);
    renderLegs();
    renderHeader();
}

function renderLegs() {
    return renderLegsTable();
    const spot = strategySpot;
    const liveLegs = Array.isArray(latestLiveStructure?.legs) ? latestLiveStructure.legs : [];
    const lotSize = resolvedDisplayLotSize();
    let totalPremiumPoints = 0;
    let totalMtmPoints = 0;
    let hasLiveMtm = false;
    els.legsContainer.innerHTML = legsState.map((leg, index) => {
        const liveLeg = liveLegs[index] || {};
        const lastPrice = Number(liveLeg.lastPrice);
        const entryPrice = Number(leg.entryPrice || 0);
        const isShort = String(leg.side || "").toUpperCase() === "SHORT";
        const mtmPoints = Number.isFinite(lastPrice)
            ? (isShort ? entryPrice - lastPrice : lastPrice - entryPrice)
            : null;
        const signedPremium = isShort ? entryPrice : -entryPrice;
        totalPremiumPoints += signedPremium;
        if (mtmPoints !== null) {
            totalMtmPoints += mtmPoints;
            hasLiveMtm = true;
        }
        return `
        <tr>
            <td>
                <div class="type-cell-wrap">
                    <select data-leg-index="${index}" data-field="optionType" ${leg.editableType ? "" : "disabled"}>
                        <option value="CE" ${leg.optionType === "CE" ? "selected" : ""}>CE</option>
                        <option value="PE" ${leg.optionType === "PE" ? "selected" : ""}>PE</option>
                    </select>
                    <div class="type-cell-meta">
                        <span class="type-cell-label">${escapeHtml(leg.label || `Leg ${index + 1}`)}</span>
                        ${els.strategyMode.value === "CUSTOM_MULTI_LEG" ? `<button class="remove-leg-button" data-remove-leg="${index}" type="button">Remove</button>` : ""}
                    </div>
                </div>
            </td>
            <td>
                <select data-leg-index="${index}" data-field="side" ${leg.editableSide ? "" : "disabled"}>
                    <option value="LONG" ${leg.side === "LONG" ? "selected" : ""}>Buy</option>
                    <option value="SHORT" ${leg.side === "SHORT" ? "selected" : ""}>Sell</option>
                </select>
            </td>
            <td class="num-cell">
                <input class="mono" data-leg-index="${index}" data-field="strike" type="number" min="0" step="0.05" value="${stripTrailingZeros(leg.strike)}">
            </td>
            <td class="num-cell">
                <div class="compound-input">
                    <input class="mono" data-leg-index="${index}" data-field="distance" type="number" step="0.05" value="${stripTrailingZeros(moneynessDistanceFromStrike(leg.optionType, leg.strike, spot))}">
                    <span class="suffix">pts</span>
                </div>
            </td>
            <td class="num-cell">
                <input class="mono" data-leg-index="${index}" data-field="entryPrice" type="number" min="0" step="0.05" value="${stripTrailingZeros(leg.entryPrice)}">
            </td>
            <td class="num-cell">
                <span class="ltp-value mono">${Number.isFinite(lastPrice) ? `${formatNumber(lastPrice, 2)}` : "-"}</span>
            </td>
            <td class="num-cell">
                <span class="qty-value mono">${lotSize ? `${lotSize}` : "-"}</span>
            </td>
            <td class="num-cell">
                <span class="mtm-value mono">${mtmPoints === null ? "-" : `${formatSigned(mtmPoints, 2)} pts`}</span>
            </td>
        </tr>
    `;
    }).join("");

    if (els.legsTotalPremium) {
        els.legsTotalPremium.textContent = `Premium ${formatSigned(totalPremiumPoints, 2)} pts`;
    }
    if (els.legsTotalMtm) {
        if (hasLiveMtm && lotSize) {
            const mtmRupees = totalMtmPoints * lotSize;
            els.legsTotalMtm.textContent = `${formatSigned(mtmRupees, 0)} ₹`;
        } else if (hasLiveMtm) {
            els.legsTotalMtm.textContent = `${formatSigned(totalMtmPoints, 2)} pts`;
        } else {
            els.legsTotalMtm.textContent = "-";
        }
    }

    els.legsContainer.querySelectorAll("[data-remove-leg]").forEach((button) => {
        button.addEventListener("click", () => removeCustomLeg(Number(button.dataset.removeLeg)));
    });
}

function resolvedDisplayLotSize() {
    const liveLegs = Array.isArray(latestLiveStructure?.legs) ? latestLiveStructure.legs : [];
    const legLot = liveLegs
        .map((leg) => Number(leg.lotSize))
        .find((value) => Number.isFinite(value) && value > 0);
    if (legLot) {
        return Math.round(legLot);
    }
    const structureLot = Number(latestLiveStructure?.effectiveLotSize);
    if (Number.isFinite(structureLot) && structureLot > 0) {
        return Math.round(structureLot);
    }
    const chipValue = Number.parseInt(String(els.liveLotChip?.textContent || "").replace(/[^\d]/g, ""), 10);
    if (Number.isFinite(chipValue) && chipValue > 0) {
        return chipValue;
    }
    return currentScenarioLotSize();
}

function renderLegsTable() {
    const summary = calculateLiveStructureSummary();
    const editLocked = positionEditingLocked();
    els.legsContainer.innerHTML = summary.rows.map((row) => {
        const blinkClass = row.ltpChangeClass ? ` ${row.ltpChangeClass}` : "";
        const deltaResponses = [
            renderDeltaResponseTag("2m", row.deltaResponse2m),
            renderDeltaResponseTag("5m", row.deltaResponse5m),
            renderDeltaResponseTag("SOD", row.deltaResponseSod)
        ].join("");
        const deltaPattern = renderDeltaPatternState(row.deltaPatternState);
        const optionTypeText = row.leg.optionType === "PE" ? "PE" : "CE";
        const sideText = row.leg.side === "SHORT" ? "Sell" : "Buy";
        const strikeText = stripTrailingZeros(row.leg.strike);
        const distanceText = stripTrailingZeros(moneynessDistanceFromStrike(row.leg.optionType, row.leg.strike, summary.spot));
        const entryText = stripTrailingZeros(row.entryPrice);
        return `
        <tr>
            <td class="leg-check-cell">
                <input class="row-selector" data-leg-index="${row.index}" data-field="selected" type="checkbox" ${row.selected ? "checked" : ""} ${row.canSelect ? "" : "disabled"}>
            </td>
            <td>
                <div class="type-cell-wrap">
                    <span class="plain-value mono">${optionTypeText}</span>
                    <div class="type-cell-meta">
                        <span class="type-cell-label">${escapeHtml(row.leg.label || `Leg ${row.index + 1}`)}</span>
                        ${row.leg.removable ? `<button class="remove-leg-button" data-remove-leg="${row.index}" type="button" ${editLocked ? "disabled" : ""}>Remove</button>` : ""}
                    </div>
                </div>
            </td>
            <td>
                <span class="plain-value">${sideText}</span>
            </td>
            <td class="num-cell">
                <span class="plain-value mono">${strikeText}</span>
            </td>
            <td class="num-cell expiry-cell">
                <span class="mono expiry-text">${escapeHtml(row.expiryText)}</span>
            </td>
            <td class="num-cell">
                <div class="plain-value-group">
                    <span class="plain-value mono">${distanceText}</span>
                    <span class="plain-suffix">pts</span>
                </div>
            </td>
            <td class="num-cell">
                <div class="entry-cell">
                    <span class="plain-value mono">${entryText}</span>
                    ${summary.baselineActive ? '<span class="entry-lock-indicator">Baseline active</span>' : ''}
                </div>
            </td>
            <td class="num-cell">
                <div class="ltp-cell-wrap" title="${escapeHtml(row.priceMetaTitle)}">
                    <span class="ltp-value mono${blinkClass} ${row.priceClass}">${row.displayedPriceText}</span>
                    <span class="ltp-source ${row.priceClass}">${row.priceLabel}</span>
                </div>
            </td>
            <td class="num-cell">
                <span class="qty-value mono">${row.originalQuantityDisplay}</span>
            </td>
            <td class="num-cell">
                <span class="qty-value mono">${row.openQuantityDisplay}</span>
            </td>
            <td>
                <div class="delta-tracking-cell">
                    <div class="delta-response-strip">${deltaResponses}</div>
                    <div class="delta-pattern-row">${deltaPattern}</div>
                </div>
            </td>
            <td class="num-cell">
                <span class="delta-value mono ${mtmClass(row.legDelta)}">${row.legDeltaDisplay}</span>
            </td>
            <td class="num-cell">
                <span class="mtm-value mono ${mtmClass(row.bookedPnl)}">${row.bookedPnlDisplay}</span>
            </td>
            <td class="num-cell">
                <span class="mtm-value mono ${mtmClass(row.livePnlRupees)}">${row.livePnlDisplay}</span>
            </td>
            <td class="num-cell">
                <span class="mtm-value mono ${mtmClass(row.totalPnlRupees)}">${row.totalPnlDisplay}</span>
            </td>
            <td>
                <span class="status-pill ${row.statusClass}">${escapeHtml(row.statusLabel)}</span>
            </td>
        </tr>
    `;
    }).join("");

    if (els.legsTotalPremium) {
        els.legsTotalPremium.textContent = summary.rows.length === 0
            ? "-"
            : `${formatResolvedRupees(summary.totalPremiumRupees)} / ${formatSigned(summary.totalPremiumPoints, 2)} pts`;
    }
    if (els.combinedDeltaTracking) {
        els.combinedDeltaTracking.innerHTML = renderCombinedDeltaTracking(summary);
    }
    if (els.combinedTotalDelta) {
        els.combinedTotalDelta.textContent = summary.hasValidDelta ? formatSigned(summary.totalDelta, 2) : "--";
        els.combinedTotalDelta.className = `legs-footer-value ${mtmClass(summary.totalDelta)}`;
    }
    if (els.legsTotalDelta) {
        els.legsTotalDelta.textContent = summary.hasValidDelta
            ? `${formatSigned(summary.totalDelta, 2)}${summary.totalDeltaBias ? ` ${summary.totalDeltaBias}` : ""}`
            : "--";
        els.legsTotalDelta.className = `legs-footer-value ${mtmClass(summary.totalDelta)}`;
    }
    if (els.legsTotalMtm) {
        els.legsTotalMtm.textContent = !summary.baselineActive
            ? "Run Analysis to start MTM"
            : summary.hasLiveMtm
            ? `${formatResolvedRupees(summary.totalMtmRupees)} / ${formatSigned(summary.totalMtmPoints, 2)} pts`
            : "Awaiting live marks";
        els.legsTotalMtm.className = `legs-footer-value ${mtmClass(summary.totalMtmRupees)}`;
    }
    applyLiveMtmTotals(summary);
    renderBaselineStamp();
    previousLiveLegPrices = summary.rows.map((row) => row.lastPrice);

    els.legsContainer.querySelectorAll("[data-remove-leg]").forEach((button) => {
        button.addEventListener("click", () => removeCustomLeg(Number(button.dataset.removeLeg)));
    });
    renderPositionControlState();
}

function calculateLiveStructureSummary() {
    const spot = strategySpot;
    const liveLegs = Array.isArray(latestLiveStructure?.legs) ? latestLiveStructure.legs : [];
    const baseLotSize = resolvedDisplayLotSize();
    const baselineActive = entryBaselinePinned;
    let totalPremiumRupees = 0;
    let totalPremiumPoints = 0;
    let totalMtmRupees = 0;
    let totalMtmPoints = 0;
    let totalBookedPnl = 0;
    let totalFinalPnlRupees = 0;
    let totalDelta = 0;
    let totalDeltaTracking2mWeighted = 0;
    let totalDeltaTracking5mWeighted = 0;
    let totalDeltaTrackingSodWeighted = 0;
    let totalDeltaTracking2mWeight = 0;
    let totalDeltaTracking5mWeight = 0;
    let totalDeltaTrackingSodWeight = 0;
    let hasLiveMtm = false;
    let hasValidDelta = false;
    let hasCombinedDeltaTracking = false;
    const rows = legsState.map((leg, index) => {
        const liveLeg = liveLegs[index] || {};
        const lastPrice = toFiniteNumber(liveLeg.lastPrice);
        const lockedEntry = toFiniteNumber(leg.lockedEntryPrice);
        const entryPrice = baselineActive && lockedEntry !== null
            ? lockedEntry
            : Number(leg.entryPrice || 0);
        const quantity = resolvedQuantityForLeg(leg, liveLeg, baseLotSize);
        const originalQuantity = originalQuantityForLegState({
            ...leg,
            quantity
        });
        const bookedPnl = bookedPnlForLegState(leg);
        const isShort = String(leg.side || "").toUpperCase() === "SHORT";
        const mtmPerUnit = (!baselineActive || lastPrice === null) ? null : (isShort ? entryPrice - lastPrice : lastPrice - entryPrice);
        const signedPremiumPerUnit = isShort ? entryPrice : -entryPrice;
        const rowPremiumRupees = quantity === null ? null : signedPremiumPerUnit * quantity;
        const rowPremiumPoints = quantity === null || !baseLotSize ? signedPremiumPerUnit : rowPremiumRupees / baseLotSize;
        const mtmRupees = mtmPerUnit === null || quantity === null ? null : mtmPerUnit * quantity;
        const mtmPoints = mtmPerUnit === null ? null : (quantity !== null && baseLotSize ? mtmRupees / baseLotSize : mtmPerUnit);
        const totalPnlRupees = bookedPnl + (mtmRupees === null ? 0 : mtmRupees);
        const liveDeltaValue = resolveLiveDeltaValue(liveLeg);
        const sideMultiplier = isShort ? -1 : 1;
        const legDelta = liveDeltaValue === null || quantity === null
            ? null
            : liveDeltaValue * quantity * sideMultiplier;
        const deltaTracking2m = scaleDeltaTracking(liveLeg.deltaResponse2m, quantity, sideMultiplier);
        const deltaTracking5m = scaleDeltaTracking(liveLeg.deltaResponse5m, quantity, sideMultiplier);
        const deltaTrackingSod = scaleDeltaTracking(liveLeg.deltaResponseSod, quantity, sideMultiplier);
        const previousPrice = toFiniteNumber(previousLiveLegPrices[index]);
        if (rowPremiumRupees !== null) {
            totalPremiumRupees += rowPremiumRupees;
            totalPremiumPoints += rowPremiumPoints;
        }
        if (legDelta !== null) {
            totalDelta += legDelta;
            hasValidDelta = true;
        }
        if (deltaTracking2m !== null) {
            totalDeltaTracking2mWeighted += deltaTracking2m.weightedValue;
            totalDeltaTracking2mWeight += deltaTracking2m.weight;
            hasCombinedDeltaTracking = true;
        }
        if (deltaTracking5m !== null) {
            totalDeltaTracking5mWeighted += deltaTracking5m.weightedValue;
            totalDeltaTracking5mWeight += deltaTracking5m.weight;
            hasCombinedDeltaTracking = true;
        }
        if (deltaTrackingSod !== null) {
            totalDeltaTrackingSodWeighted += deltaTrackingSod.weightedValue;
            totalDeltaTrackingSodWeight += deltaTrackingSod.weight;
            hasCombinedDeltaTracking = true;
        }
        if (mtmRupees !== null) {
            totalMtmRupees += mtmRupees;
            totalMtmPoints += mtmPoints;
            hasLiveMtm = true;
        }
        totalBookedPnl += bookedPnl;
        totalFinalPnlRupees += totalPnlRupees;
        const statusCode = deriveLegStatusValue({
            ...leg,
            quantity,
            originalQuantity
        });
        return {
            index,
            leg,
            entryPrice,
            lastPrice,
            expiryText: resolveLegExpiryDateText(liveLeg) || (leg.expiry ? formatDateLabel(leg.expiry) : "-"),
            priceType: String(liveLeg.priceType || "UNAVAILABLE").toUpperCase(),
            source: liveLeg.source || "",
            asOf: liveLeg.asOf || latestLiveStructure?.asOf || null,
            sessionState: latestLiveStructure?.sessionState || "UNKNOWN",
            isStale: Boolean(liveLeg.isStale || liveLeg.stale),
            diagnosticReason: liveLeg.diagnosticReason || "",
            priceLabel: canonicalPriceLabel(liveLeg.priceType),
            priceClass: canonicalPriceClass(liveLeg.priceType, Boolean(liveLeg.isStale || liveLeg.stale), liveLeg.missing || lastPrice === null),
            displayedPriceText: lastPrice === null ? "Unavailable" : formatNumber(lastPrice, 2),
            deltaResponse2m: toFiniteNumber(liveLeg.deltaResponse2m),
            deltaResponse5m: toFiniteNumber(liveLeg.deltaResponse5m),
            deltaResponseSod: toFiniteNumber(liveLeg.deltaResponseSod),
            scaledDeltaTracking2m: deltaTracking2m === null ? null : deltaTracking2m.weightedValue,
            scaledDeltaTracking5m: deltaTracking5m === null ? null : deltaTracking5m.weightedValue,
            scaledDeltaTrackingSod: deltaTrackingSod === null ? null : deltaTrackingSod.weightedValue,
            deltaPatternState: deriveDeltaPatternState(
                toFiniteNumber(liveLeg.deltaResponse2m),
                toFiniteNumber(liveLeg.deltaResponse5m),
                toFiniteNumber(liveLeg.deltaResponseSod)
            ),
            priceMetaTitle: buildPriceMetaTitle({
                priceType: liveLeg.priceType,
                source: liveLeg.source,
                asOf: liveLeg.asOf || latestLiveStructure?.asOf || null,
                sessionState: latestLiveStructure?.sessionState || "UNKNOWN",
                isStale: Boolean(liveLeg.isStale || liveLeg.stale),
                diagnosticReason: liveLeg.diagnosticReason || (liveLeg.missing ? "Price unavailable" : "")
            }),
            quantity,
            quantityDisplay: quantity === null ? "-" : formatNumber(quantity, 0),
            originalQuantity,
            originalQuantityDisplay: formatNumber(originalQuantity, 0),
            openQuantityDisplay: quantity === null ? "-" : formatNumber(quantity, 0),
            bookedPnl,
            bookedPnlDisplay: formatResolvedRupees(bookedPnl),
            livePnlRupees: mtmRupees,
            livePnlDisplay: mtmRupees === null ? "-" : formatResolvedRupees(mtmRupees),
            totalPnlRupees,
            totalPnlDisplay: (!baselineActive && bookedPnl === 0) ? "-" : formatResolvedRupees(totalPnlRupees),
            statusLabel: titleCase(statusCode.replace(/_/g, " ")),
            statusClass: `status-${String(statusCode || "").toLowerCase()}`,
            canSelect: (quantity || 0) > 0,
            selected: Boolean(leg.selected) && (quantity || 0) > 0,
            legDelta,
            legDeltaDisplay: legDelta === null ? "--" : formatSigned(legDelta, 2),
            mtmRupees,
            mtmDisplaySafe: mtmRupees === null ? "-" : formatResolvedRupees(mtmRupees),
            mtmDisplay: mtmRupees === null ? "-" : `${formatSigned(mtmRupees, 0)} ₹`,
            ltpChangeClass: lastPrice === null || previousPrice === null || lastPrice === previousPrice
                ? ""
                : lastPrice > previousPrice ? "ltp-up" : "ltp-down"
        };
    });
    return {
        spot,
        baselineActive,
        baseLotSize,
        rows,
        totalPremiumRupees,
        totalPremiumPoints,
        totalPremiumDisplay: rows.length === 0
            ? "-"
            : `${formatSigned(totalPremiumRupees, 0)} ₹ / ${formatSigned(totalPremiumPoints, 2)} pts`,
        combinedDeltaTracking2m: aggregateWeightedTracking(totalDeltaTracking2mWeighted, totalDeltaTracking2mWeight),
        combinedDeltaTracking5m: aggregateWeightedTracking(totalDeltaTracking5mWeighted, totalDeltaTracking5mWeight),
        combinedDeltaTrackingSod: aggregateWeightedTracking(totalDeltaTrackingSodWeighted, totalDeltaTrackingSodWeight),
        combinedDeltaPatternState: deriveDeltaPatternState(
            aggregateWeightedTracking(totalDeltaTracking2mWeighted, totalDeltaTracking2mWeight),
            aggregateWeightedTracking(totalDeltaTracking5mWeighted, totalDeltaTracking5mWeight),
            aggregateWeightedTracking(totalDeltaTrackingSodWeighted, totalDeltaTrackingSodWeight)
        ),
        hasCombinedDeltaTracking: Boolean(
            aggregateWeightedTracking(totalDeltaTracking2mWeighted, totalDeltaTracking2mWeight) !== null
            || aggregateWeightedTracking(totalDeltaTracking5mWeighted, totalDeltaTracking5mWeight) !== null
            || aggregateWeightedTracking(totalDeltaTrackingSodWeighted, totalDeltaTrackingSodWeight) !== null
        ),
        totalDelta,
        totalDeltaBias: classifyDeltaBias(totalDelta, hasValidDelta),
        hasValidDelta,
        totalBookedPnl,
        totalMtmRupees,
        totalMtmPoints,
        totalFinalPnlRupees,
        totalMtmDisplay: !baselineActive
            ? "Run Analysis to start MTM"
            : hasLiveMtm
            ? `${formatSigned(totalMtmRupees, 0)} ₹ / ${formatSigned(totalMtmPoints, 2)} pts`
            : "Awaiting live marks",
        hasLiveMtm
    };
}

function resolveLegExpiryDateText(liveLeg) {
    if (liveLeg && liveLeg.expiryDate) {
        return formatDateLabel(liveLeg.expiryDate);
    }
    const instrumentId = String(liveLeg?.instrumentId || "");
    const match = instrumentId.match(/_(\d{8})_/);
    if (!match) {
        return "-";
    }
    const raw = match[1];
    const yyyy = raw.slice(0, 4);
    const mm = raw.slice(4, 6);
    const dd = raw.slice(6, 8);
    return formatDateLabel(`${yyyy}-${mm}-${dd}`);
}

function formatDateLabel(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return "-";
    }
    return date.toLocaleDateString("en-IN", {
        day: "2-digit",
        month: "short",
        year: "numeric"
    });
}

function resolvedQuantityForLeg(leg, liveLeg, baseLotSize) {
    const override = toFiniteNumber(leg.quantity);
    if (override !== null && override >= 0) {
        return Math.max(0, Math.round(override));
    }
    const liveQuantity = toFiniteNumber(liveLeg?.quantity);
    if (liveQuantity !== null && liveQuantity >= 0) {
        return Math.max(0, Math.round(liveQuantity));
    }
    const scenarioQuantity = toFiniteNumber(scenarioQtyState);
    if (scenarioQuantity !== null && scenarioQuantity > 0) {
        return Math.round(scenarioQuantity);
    }
    const legLotSize = toFiniteNumber(liveLeg?.lotSize);
    if (legLotSize !== null && legLotSize > 0) {
        return Math.round(legLotSize);
    }
    if (baseLotSize && baseLotSize > 0) {
        return Math.round(baseLotSize);
    }
    return currentScenarioLotSize();
}

function applyLiveMtmTotals(summary) {
    const liveText = !summary.baselineActive
        ? "Run Analysis to start MTM"
        : summary.hasLiveMtm
        ? `${formatResolvedRupees(summary.totalMtmRupees)} / ${formatSigned(summary.totalMtmPoints, 2)} pts`
        : "Awaiting live marks";
    const bookedText = formatResolvedRupees(summary.totalBookedPnl || 0);
    const totalText = !summary.baselineActive && !summary.totalBookedPnl
        ? "Run Analysis to start PnL"
        : formatResolvedRupees(summary.totalFinalPnlRupees || 0);
    setText(els.positionsBookedPnl, bookedText);
    setText(els.positionsLivePnl, liveText);
    setText(els.positionsTotalPnl, totalText);
    setText(els.terminalLivePnl, `${bookedText} booked / ${liveText} live / ${totalText} total`);
    setText(els.livePnlChip, totalText);
    applyMtmClass(els.positionsBookedPnl, summary.totalBookedPnl || 0);
    applyMtmClass(els.positionsLivePnl, summary.hasLiveMtm ? summary.totalMtmRupees : null);
    applyMtmClass(els.positionsTotalPnl, summary.totalFinalPnlRupees || 0);
    applyMtmClass(els.terminalLivePnl, summary.totalFinalPnlRupees || 0);
    applyMtmClass(els.livePnlChip, summary.totalFinalPnlRupees || 0);
}

function renderBaselineStamp() {
    if (!els.baselineStamp) {
        return;
    }
    if (!entryBaselinePinned || !baselineCapturedAt) {
        els.baselineStamp.textContent = "Baseline: Not captured";
        return;
    }
    const timeText = baselineCapturedAt.toLocaleTimeString("en-IN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
    els.baselineStamp.textContent = `Baseline captured at ${timeText}`;
}

function renderLiveRefreshStamp() {
    if (!els.liveRefreshStamp) {
        return;
    }
    els.liveRefreshStamp.textContent = `Live refresh: ${LIVE_POLL_MS}ms`;
}

function markLiveStateDirty(reason) {
    liveStateDirty = true;
    logLiveDiagnostic("state-dirty", {
        analysisRunId,
        reason
    });
}

function flushLiveStrategyState() {
    if (!liveStateDirty) {
        return;
    }
    const summary = calculateLiveStructureSummary();
    latestPublishedStrategyState = {
        summary,
        feedConnected: Boolean(latestLiveStatus && latestLiveStatus.status && latestLiveStatus.status !== "Disabled" && latestLiveStatus.status !== "Unavailable"),
        lastUnderlyingTickAt: latestLiveStructure?.liveSpotAsOf || latestLiveStatus?.lastTickTs || null,
        lastLegTickAt: summary.rows.map((row) => latestLiveStructure?.asOf || latestLiveStatus?.lastTickTs || null),
        lastStrategyRecalcAt: new Date().toISOString(),
        isStale: isLiveStateStale()
    };
    liveStateDirty = false;
    renderLegs();
    renderHeader();
    renderLiveHealth(latestPublishedStrategyState);
    logLiveDiagnostic("strategy-published", {
        analysisRunId,
        lastStrategyRecalcAt: latestPublishedStrategyState.lastStrategyRecalcAt,
        isStale: latestPublishedStrategyState.isStale,
        totalMtmRupees: latestPublishedStrategyState.summary.totalMtmRupees
    });
}

function isLiveStateStale() {
    const secondsSinceLastTick = Number(latestLiveStatus?.secondsSinceLastTick);
    if (Number.isFinite(secondsSinceLastTick) && secondsSinceLastTick >= (STALE_AFTER_MS / 1000)) {
        return true;
    }
    const asOf = latestLiveStructure?.asOf ? new Date(latestLiveStructure.asOf) : null;
    return !asOf || (Date.now() - asOf.getTime()) > STALE_AFTER_MS;
}

function renderLiveHealth(publishedState) {
    if (!publishedState) {
        return;
    }
    const secondsSinceLastTick = Number(latestLiveStatus?.secondsSinceLastTick);
    const structure = latestLiveStructure || {};
    const sessionConfig = sessionModeConfig(structure.sessionState, String(structure.liveSpotPriceType || "UNAVAILABLE").toUpperCase());
    const feedHealth = Number.isFinite(secondsSinceLastTick) && secondsSinceLastTick >= (DISCONNECTED_AFTER_MS / 1000)
        ? "feed delayed"
        : publishedState.isStale
        ? "state stale"
        : publishedState.feedConnected
        ? "feed current"
        : "feed unavailable";
    const freshnessLabel = publishedState.isStale ? "stale" : "fresh";
    const recalcLabel = publishedState.lastStrategyRecalcAt
        ? new Date(publishedState.lastStrategyRecalcAt).toLocaleTimeString("en-IN")
        : "-";
    setStatus(`${sessionConfig.marketStatusText}. ${feedHealth}. Run ${analysisRunId || "-"}. Recalc ${recalcLabel}.`);
}

function logLiveDiagnostic(eventName, payload) {
    console.debug(`[live-terminal] ${eventName}`, payload);
}

function applyMtmClass(target, value) {
    if (!target) {
        return;
    }
    target.classList.remove("value-positive", "value-negative", "value-neutral");
    target.classList.add(mtmClass(value));
}

function mtmClass(value) {
    if (!Number.isFinite(Number(value))) {
        return "value-neutral";
    }
    if (Number(value) > 0) {
        return "value-positive";
    }
    if (Number(value) < 0) {
        return "value-negative";
    }
    return "value-neutral";
}

function toFiniteNumber(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
}

function normalizeSessionState(sessionState) {
    const value = String(sessionState || "").toUpperCase();
    return value || "UNKNOWN";
}

function canonicalPriceLabel(priceType) {
    const normalized = String(priceType || "").toUpperCase();
    if (normalized === "LIVE_LTP") {
        return "Live";
    }
    if (normalized === "OFFICIAL_CLOSE") {
        return "Official Close";
    }
    if (normalized === "PRE_CLOSE_LAST_TRADE") {
        return "Pre-close";
    }
    if (normalized === "PRIOR_CLOSE") {
        return "Prior Close";
    }
    return "Unavailable";
}

function canonicalPriceClass(priceType, isStale, missing) {
    if (missing || String(priceType || "").toUpperCase() === "UNAVAILABLE") {
        return "source-unavailable";
    }
    const normalized = String(priceType || "").toUpperCase();
    if (normalized === "LIVE_LTP") {
        return isStale ? "source-stale" : "source-live";
    }
    if (normalized === "OFFICIAL_CLOSE") {
        return "source-official-close";
    }
    if (normalized === "PRE_CLOSE_LAST_TRADE") {
        return "source-pre-close";
    }
    if (normalized === "PRIOR_CLOSE") {
        return "source-prior-close";
    }
    return "source-unavailable";
}

function sessionModeConfig(sessionState, spotPriceType) {
    const normalized = normalizeSessionState(sessionState);
    if (normalized === "PREOPEN") {
        return {
            underlyingLabel: "Prior Close",
            premiumLabel: "Prior Structure Close",
            summaryPremiumLabel: "Prior Close Premium",
            marketValueHeader: "Prior Close",
            marketStatusText: "Pre-open - using prior close",
            marketStatusTone: "prior-close"
        };
    }
    if (normalized === "LIVE_MARKET") {
        return {
            underlyingLabel: "Live Spot",
            premiumLabel: "Live Structure Premium",
            summaryPremiumLabel: "Current Premium",
            marketValueHeader: "LTP",
            marketStatusText: "Market live",
            marketStatusTone: "live"
        };
    }
    if (normalized === "POST_CLOSE") {
        return {
            underlyingLabel: spotPriceType === "OFFICIAL_CLOSE" ? "Spot Official Close" : "Spot Close",
            premiumLabel: "Structure Close",
            summaryPremiumLabel: "Displayed Premium",
            marketValueHeader: "Close",
            marketStatusText: spotPriceType === "OFFICIAL_CLOSE"
                ? "Market closed - using official close"
                : "Market closed - using pre-close fallback",
            marketStatusTone: "close"
        };
    }
    if (normalized === "EOD_FINALIZED") {
        return {
            underlyingLabel: "Official Spot Close",
            premiumLabel: "Official Structure Close",
            summaryPremiumLabel: "Official Premium",
            marketValueHeader: "Official Close",
            marketStatusText: "End of day finalized",
            marketStatusTone: "official-close"
        };
    }
    if (normalized === "HOLIDAY_NO_SESSION") {
        return {
            underlyingLabel: "Prior Close",
            premiumLabel: "Prior Structure Close",
            summaryPremiumLabel: "Prior Close Premium",
            marketValueHeader: "Prior Close",
            marketStatusText: "No active session - using prior close",
            marketStatusTone: "prior-close"
        };
    }
    return {
        underlyingLabel: "Underlying",
        premiumLabel: "Structure Price",
        summaryPremiumLabel: "Displayed Premium",
        marketValueHeader: "Value",
        marketStatusText: "Price mode unavailable",
        marketStatusTone: "unavailable"
    };
}

function inferStructurePremiumPriceType(structure) {
    const priceTypes = Array.isArray(structure?.legs)
        ? [...new Set(structure.legs.map((leg) => String(leg.priceType || "").toUpperCase()).filter(Boolean))]
        : [];
    if (!priceTypes.length) {
        return "UNAVAILABLE";
    }
    if (priceTypes.length === 1) {
        return priceTypes[0];
    }
    if (priceTypes.includes("PRE_CLOSE_LAST_TRADE")) {
        return "PRE_CLOSE_LAST_TRADE";
    }
    if (priceTypes.includes("PRIOR_CLOSE")) {
        return "PRIOR_CLOSE";
    }
    if (priceTypes.includes("OFFICIAL_CLOSE")) {
        return "OFFICIAL_CLOSE";
    }
    return priceTypes[0];
}

function formatResolvedPrice(value) {
    return Number.isFinite(Number(value)) ? `${formatNumber(value, 2)} pts` : "Unavailable";
}

function formatResolvedRupees(value) {
    return Number.isFinite(Number(value)) ? `${formatSigned(value, 0)} \u20B9` : "Unavailable";
}

function buildPriceMetaTitle(meta) {
    if (!meta) {
        return "";
    }
    const tradeDate = normalizeIsoDate(meta.tradeDate);
    const warning = historicalPriceWarning(meta, tradeDate);
    return [
        `session ${normalizeSessionState(meta.sessionState)}`,
        `type ${String(meta.priceType || "UNAVAILABLE").toUpperCase()}`,
        tradeDate ? `${historicalDateLabel(meta.priceType)} ${formatDateLabel(tradeDate)}` : "",
        meta.source ? `source ${meta.source}` : "",
        meta.asOf ? `as of ${new Date(meta.asOf).toLocaleString("en-IN")}` : "",
        meta.isStale ? "stale" : "",
        warning,
        meta.diagnosticReason || ""
    ].filter(Boolean).join(" | ");
}

function historicalDateLabel(priceType) {
    const normalized = String(priceType || "").toUpperCase();
    if (normalized === "OFFICIAL_CLOSE" || normalized === "PRE_CLOSE_LAST_TRADE" || normalized === "PRIOR_CLOSE") {
        return "close date";
    }
    return "trade date";
}

function historicalPriceWarning(meta, tradeDate) {
    const normalizedPriceType = String(meta?.priceType || "").toUpperCase();
    const normalizedSessionState = normalizeSessionState(meta?.sessionState);
    if (!tradeDate) {
        return "";
    }
    if (normalizedPriceType === "PRIOR_CLOSE") {
        const exchangeToday = exchangeTodayIsoDate();
        if (exchangeToday && businessDayDiff(tradeDate, exchangeToday) > 3) {
            return `warning stale prior close: latest available close is ${formatDateLabel(tradeDate)}`;
        }
        return "";
    }
    if ((normalizedPriceType === "OFFICIAL_CLOSE" || normalizedPriceType === "PRE_CLOSE_LAST_TRADE")
        && (normalizedSessionState === "POST_CLOSE" || normalizedSessionState === "EOD_FINALIZED")) {
        const exchangeToday = exchangeTodayIsoDate();
        if (exchangeToday && tradeDate < exchangeToday) {
            return `warning stale close: expected current-day close for ${formatDateLabel(exchangeToday)}`;
        }
    }
    return "";
}

function exchangeTodayIsoDate() {
    const parts = new Intl.DateTimeFormat("en-CA", {
        timeZone: EXCHANGE_TIME_ZONE,
        year: "numeric",
        month: "2-digit",
        day: "2-digit"
    }).formatToParts(new Date());
    const year = parts.find((part) => part.type === "year")?.value;
    const month = parts.find((part) => part.type === "month")?.value;
    const day = parts.find((part) => part.type === "day")?.value;
    return year && month && day ? `${year}-${month}-${day}` : "";
}

function businessDayDiff(startIsoDate, endIsoDate) {
    const start = normalizeIsoDate(startIsoDate);
    const end = normalizeIsoDate(endIsoDate);
    if (!start || !end || start >= end) {
        return 0;
    }
    let cursor = new Date(`${start}T00:00:00Z`);
    const finish = new Date(`${end}T00:00:00Z`);
    if (Number.isNaN(cursor.getTime()) || Number.isNaN(finish.getTime())) {
        return 0;
    }
    let count = 0;
    while (cursor < finish) {
        cursor.setUTCDate(cursor.getUTCDate() + 1);
        if (cursor > finish) {
            break;
        }
        if (cursor.getUTCDay() !== 0 && cursor.getUTCDay() !== 6) {
            count += 1;
        }
    }
    return count;
}

function normalizeIsoDate(value) {
    if (typeof value !== "string") {
        return "";
    }
    const normalized = value.trim();
    return /^\d{4}-\d{2}-\d{2}$/.test(normalized) ? normalized : "";
}

function syncLegStateFromDom(index) {
    const leg = legsState[index];
    if (!leg) {
        return;
    }
    const optionType = els.legsContainer.querySelector(`[data-leg-index="${index}"][data-field="optionType"]`);
    const side = els.legsContainer.querySelector(`[data-leg-index="${index}"][data-field="side"]`);
    const entryPrice = els.legsContainer.querySelector(`[data-leg-index="${index}"][data-field="entryPrice"]`);
    if (optionType) {
        leg.optionType = optionType.value;
    }
    if (side) {
        leg.side = side.value;
    }
    if (entryPrice) {
        leg.entryPrice = Number.parseFloat(entryPrice.value || "0") || 0;
    }
}

function syncDistanceFromStrike(index) {
    const strikeInput = els.legsContainer.querySelector(`[data-leg-index="${index}"][data-field="strike"]`);
    const distanceInput = els.legsContainer.querySelector(`[data-leg-index="${index}"][data-field="distance"]`);
    if (!strikeInput || !distanceInput) {
        return;
    }
    const strike = Number.parseFloat(strikeInput.value || "0") || 0;
    const optionType = legsState[index]?.optionType || "CE";
    const distance = moneynessDistanceFromStrike(optionType, strike, strategySpot);
    distanceInput.value = stripTrailingZeros(distance);
    legsState[index].strike = strike;
    legsState[index].distance = distance;
}

function syncStrikeFromDistance(index) {
    const strikeInput = els.legsContainer.querySelector(`[data-leg-index="${index}"][data-field="strike"]`);
    const distanceInput = els.legsContainer.querySelector(`[data-leg-index="${index}"][data-field="distance"]`);
    if (!strikeInput || !distanceInput) {
        return;
    }
    const distance = Number.parseFloat(distanceInput.value || "0") || 0;
    const optionType = legsState[index]?.optionType || "CE";
    const strike = strikeFromMoneynessDistance(optionType, distance, strategySpot);
    strikeInput.value = stripTrailingZeros(strike);
    legsState[index].strike = strike;
    legsState[index].distance = distance;
}

function moneynessDistanceFromStrike(optionType, strike, spot) {
    const safeStrike = Number(strike || 0);
    const safeSpot = Number(spot || 0);
    return String(optionType || "").toUpperCase() === "PE"
        ? safeSpot - safeStrike
        : safeStrike - safeSpot;
}

function strikeFromMoneynessDistance(optionType, distance, spot) {
    const safeDistance = Number(distance || 0);
    const safeSpot = Number(spot || 0);
    return String(optionType || "").toUpperCase() === "PE"
        ? safeSpot - safeDistance
        : safeSpot + safeDistance;
}

function stripTrailingZeros(value) {
    return Number(value).toFixed(2).replace(/\.00$/, "").replace(/(\.\d)0$/, "$1");
}

function formatNumber(value, digits = 2) {
    return new Intl.NumberFormat("en-IN", {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits
    }).format(Number(value || 0));
}

function formatSigned(value, digits = 2) {
    const number = Number(value || 0);
    const sign = number > 0 ? "+" : number < 0 ? "-" : "";
    return `${sign}${formatNumber(Math.abs(number), digits)}`;
}

function formatPercent(value, digits = 1) {
    return `${formatNumber(value, digits)}%`;
}

function formatDeltaResponse(value) {
    if (!Number.isFinite(Number(value))) {
        return "--";
    }
    return formatSigned(value, 2);
}

function renderDeltaResponseTag(label, value) {
    const number = Number(value);
    const cssClass = Number.isFinite(number) ? mtmClass(number) : "value-neutral";
    return `
        <span class="delta-response-tag ${cssClass}">
            <span class="delta-response-label">${label}</span>
            <span class="delta-response-value mono">${formatDeltaResponse(number)}</span>
        </span>
    `;
}

function deriveDeltaPatternState(delta2m, delta5m, deltaSod) {
    if (![delta2m, delta5m, deltaSod].every((value) => Number.isFinite(Number(value)))) {
        return "Unavailable";
    }
    if (delta2m > delta5m && delta5m > deltaSod) {
        return "Strengthening";
    }
    if (delta2m < delta5m && delta5m < deltaSod) {
        return "Weakening";
    }
    return "Mixed";
}

function deltaPatternClass(state) {
    if (state === "Strengthening") {
        return "value-positive";
    }
    if (state === "Weakening") {
        return "value-negative";
    }
    return "value-neutral";
}

function renderDeltaPatternState(state) {
    return `
        <span class="delta-pattern-badge ${deltaPatternClass(state)}">${escapeHtml(state || "Unavailable")}</span>
    `;
}

function resolveLiveDeltaValue(liveLeg) {
    const candidates = [
        liveLeg?.liveDeltaValue,
        liveLeg?.liveDelta,
        liveLeg?.deltaValue,
        liveLeg?.delta,
        liveLeg?.deltaResponse2m
    ];
    for (const candidate of candidates) {
        const number = toFiniteNumber(candidate);
        if (number !== null) {
            return number;
        }
    }
    return null;
}

function scaleDeltaTracking(value, quantity, sideMultiplier) {
    const number = toFiniteNumber(value);
    if (number === null || quantity === null) {
        return null;
    }
    return {
        weightedValue: number * quantity * sideMultiplier,
        weight: Math.abs(quantity)
    };
}

function aggregateWeightedTracking(weightedValue, weight) {
    if (!Number.isFinite(Number(weightedValue)) || !Number.isFinite(Number(weight)) || Number(weight) <= 0) {
        return null;
    }
    return Number(weightedValue) / Number(weight);
}

function classifyDeltaBias(totalDelta, hasValidDelta) {
    if (!hasValidDelta || !Number.isFinite(Number(totalDelta))) {
        return "";
    }
    if (Math.abs(Number(totalDelta)) < 0.01) {
        return "Neutral";
    }
    return Number(totalDelta) > 0 ? "Bullish Bias" : "Bearish Bias";
}

function renderCombinedDeltaTracking(summary) {
    if (!summary.hasCombinedDeltaTracking) {
        return '<span class="combined-delta-empty">Unavailable</span>';
    }
    const tags = [
        renderDeltaResponseTag("2M", summary.combinedDeltaTracking2m),
        renderDeltaResponseTag("5M", summary.combinedDeltaTracking5m),
        renderDeltaResponseTag("SOD", summary.combinedDeltaTrackingSod)
    ].join("");
    return `
        <div class="delta-tracking-cell combined-delta-tracking">
            <div class="delta-response-strip">${tags}</div>
            <div class="delta-pattern-row">${renderDeltaPatternState(summary.combinedDeltaPatternState)}</div>
        </div>
    `;
}

function titleCase(value) {
    const lower = String(value || "").toLowerCase();
    return lower ? lower.charAt(0).toUpperCase() + lower.slice(1) : "";
}

function currentOrientation() {
    return strategyConfigs[els.strategyMode.value]?.orientation || "BUYER";
}

function currentStrategyLabel() {
    return strategyConfigs[els.strategyMode.value]?.label || "Strategy";
}

function lotCountFromQuantity(quantity, lotSize) {
    const safeQuantity = Number(quantity);
    const safeLotSize = Number(lotSize);
    if (!Number.isFinite(safeQuantity) || !Number.isFinite(safeLotSize) || safeLotSize <= 0) {
        return 1;
    }
    if (safeQuantity <= 0) {
        return 0;
    }
    return Math.max(1, safeQuantity / safeLotSize);
}

function currentEconomicPremiumFromInputs() {
    const lotSize = currentScenarioLotSize();
    const signed = legsState.reduce((sum, leg) => {
        const sign = leg.side === "SHORT" ? -1 : 1;
        const quantity = resolvedQuantityForLeg(leg, null, lotSize);
        return sum + (sign * Number(leg.entryPrice || 0) * lotCountFromQuantity(quantity, lotSize));
    }, 0);
    return currentOrientation() === "SELLER" ? -signed : signed;
}

function currentInputs() {
    return {
        strategyMode: els.strategyMode.value,
        strategyLabel: currentStrategyLabel(),
        orientation: currentOrientation(),
        underlying: els.underlying.value,
        expiryType: els.expiryType.value,
        dte: strategyDteDays,
        spot: strategySpot,
        timeframe: els.timeframe.value,
        scenarioQty: scenarioQtyState,
        lastDeltaAdjustmentTs,
        pendingAdjustmentSinceTs,
        legs: legsState.map((leg) => ({ ...leg })),
        currentEconomicPremium: currentEconomicPremiumFromInputs()
    };
}

function currentExecutionMode() {
    return latestSimulationStatus?.active ? "simulation" : "live";
}

function buildRunReportContext(mode, overrides = {}) {
    const inputs = currentInputs();
    return {
        runId: overrides.runId || `${mode}-${analysisRunId || 0}-${Date.now()}`,
        mode,
        underlying: inputs.underlying,
        strategyName: inputs.strategyLabel,
        startTime: overrides.startTime || new Date().toISOString(),
        initialMaxLots: 20,
        lotSize: currentScenarioLotSize(),
        replayDate: overrides.replayDate || latestSimulationStatus?.replayDate || null,
        initialStructure: captureInitialReportStructure(inputs)
    };
}

function captureInitialReportStructure(inputs = currentInputs()) {
    const liveLegs = Array.isArray(latestLiveStructure?.legs) ? latestLiveStructure.legs : [];
    const lotSize = currentScenarioLotSize();
    return (Array.isArray(inputs.legs) ? inputs.legs : []).map((leg, index) => {
        const liveLeg = liveLegs[index] || {};
        const quantity = resolvedQuantityForLeg(leg, liveLeg, lotSize);
        return {
            legId: leg.legId || `leg-${index + 1}`,
            label: leg.label || liveLeg.label || `Leg ${index + 1}`,
            side: leg.side || liveLeg.side || "LONG",
            optionType: leg.optionType || liveLeg.optionType || "CE",
            strike: toFiniteNumber(leg.strike ?? liveLeg.strike),
            expiryDate: leg.expiryDate || resolveLegExpiryDateText(liveLeg),
            entryPrice: toFiniteNumber(leg.lockedEntryPrice ?? leg.entryPrice ?? liveLeg.lastPrice),
            marketPrice: toFiniteNumber(liveLeg.lastPrice),
            initialQuantity: Math.max(0, Math.round(Number(quantity || 0))),
            finalQuantity: Math.max(0, Math.round(Number(quantity || 0))),
            initialDelta: toFiniteNumber(resolveLiveDeltaValue(liveLeg)),
            bookedPnl: toFiniteNumber(leg.bookedPnl) ?? 0,
            livePnl: null,
            totalPnl: toFiniteNumber(leg.bookedPnl) ?? 0,
            status: deriveLegStatusValue({
                ...leg,
                quantity,
                originalQuantity: originalQuantityForLegState({ ...leg, quantity })
            })
        };
    });
}

function captureFinalReportStructure(summary = calculateLiveSummary()) {
    return (Array.isArray(summary?.rows) ? summary.rows : []).map((row, index) => ({
        legId: row.leg?.legId || `leg-${index + 1}`,
        label: row.leg?.label || `Leg ${index + 1}`,
        side: row.leg?.side || "LONG",
        optionType: row.leg?.optionType || "CE",
        strike: toFiniteNumber(row.leg?.strike),
        expiryDate: row.expiryText && row.expiryText !== "-" ? row.expiryText : (row.leg?.expiryDate || ""),
        entryPrice: toFiniteNumber(row.entryPrice),
        marketPrice: toFiniteNumber(row.lastPrice),
        initialQuantity: Math.max(0, Math.round(Number(row.originalQuantity || 0))),
        finalQuantity: Math.max(0, Math.round(Number(row.quantity || 0))),
        initialDelta: null,
        bookedPnl: toFiniteNumber(row.bookedPnl) ?? 0,
        livePnl: toFiniteNumber(row.livePnlRupees),
        totalPnl: toFiniteNumber(row.totalPnlRupees) ?? 0,
        status: row.leg?.status || row.statusLabel || "OPEN"
    }));
}

function reportTimelineActionType(entry) {
    const adjustmentActionType = String(entry?.adjustmentActionType || "").trim().toUpperCase();
    if (adjustmentActionType) {
        return adjustmentActionType;
    }
    if (entry?.applied === false) {
        return String(entry?.triggerType || "").trim().toUpperCase() === "DELAYED" ? "DELAYED" : "SKIP";
    }
    const actionType = String(entry?.actionType || "").trim().toUpperCase();
    if (actionType === "MANUAL_EXIT_SELECTED") {
        return "MANUAL_EXIT";
    }
    if (actionType === "MANUAL_EXIT_ALL") {
        return "EXIT_ALL";
    }
    return actionType || "SKIP";
}

function timelineLotsBefore(entry) {
    const explicit = toFiniteNumber(entry?.totalLotsBefore);
    if (explicit !== null) {
        return Math.max(0, Math.round(explicit));
    }
    const currentTotalLots = toFiniteNumber(entry?.currentTotalLots);
    if (currentTotalLots !== null) {
        return Math.max(0, Math.round(currentTotalLots));
    }
    return 0;
}

function timelineLotsAfter(entry) {
    const explicit = toFiniteNumber(entry?.totalLotsAfter);
    if (explicit !== null) {
        return Math.max(0, Math.round(explicit));
    }
    const before = timelineLotsBefore(entry);
    const actionType = reportTimelineActionType(entry);
    if (actionType === "ADD") {
        return before + 1;
    }
    if (actionType === "REDUCE") {
        return Math.max(0, before - 1);
    }
    return before;
}

function captureReportTimeline() {
    return (Array.isArray(deltaAdjustmentLog) ? deltaAdjustmentLog : []).map((entry) => {
        const actionType = reportTimelineActionType(entry);
        const oldQuantity = Math.max(0, Math.round(Number(entry?.oldQuantity || 0)));
        const newQuantity = Math.max(0, Math.round(Number(entry?.newQuantity ?? entry?.remainingQuantity ?? oldQuantity)));
        return {
            timestamp: entry?.timestamp || entry?.updatedLastAdjustmentTs || entry?.updatedPendingAdjustmentSinceTs || new Date().toISOString(),
            actionType: entry?.actionType || "delta_adjustment",
            adjustmentActionType: actionType,
            legId: entry?.legId || entry?.instrumentId || "",
            legLabel: entry?.legLabel || entry?.leg || "",
            optionType: entry?.optionType || "",
            side: entry?.side || "",
            strike: toFiniteNumber(entry?.strike),
            oldQuantity,
            newQuantity,
            totalLotsBefore: timelineLotsBefore(entry),
            totalLotsAfter: timelineLotsAfter(entry),
            triggerType: entry?.triggerType || "",
            reasonCode: entry?.reasonCode || entry?.code || "",
            reason: entry?.reason || entry?.code || "",
            message: entry?.message || null,
            bookedPnlImpact: toFiniteNumber(entry?.bookedPnl) ?? 0,
            delta2m: toFiniteNumber(entry?.delta2m),
            delta5m: toFiniteNumber(entry?.delta5m),
            deltaSod: toFiniteNumber(entry?.deltaSod),
            livePnlPoints: toFiniteNumber(entry?.livePnlPoints),
            livePnlChange2mPoints: toFiniteNumber(entry?.livePnlChange2mPoints),
            livePnlChange5mPoints: toFiniteNumber(entry?.livePnlChange5mPoints),
            netDelta2m: toFiniteNumber(entry?.netDelta2m ?? entry?.delta2m),
            netDelta5m: toFiniteNumber(entry?.netDelta5m),
            netDeltaSod: toFiniteNumber(entry?.netDeltaSod),
            postAdjNetDelta: toFiniteNumber(entry?.postAdjNetDelta),
            improvementAbsDelta: toFiniteNumber(entry?.improvementAbsDelta),
            improvementRatio: toFiniteNumber(entry?.improvementRatio),
            underlyingDirection: entry?.underlyingDirection || "",
            profitAlignment: entry?.profitAlignment || "",
            volumeConfirmed: typeof entry?.volumeConfirmed === "boolean" ? entry.volumeConfirmed : null,
            volumeBypassed: typeof entry?.volumeBypassed === "boolean" ? entry.volumeBypassed : null,
            thetaScore: toFiniteNumber(entry?.thetaScore),
            liquidityScore: toFiniteNumber(entry?.liquidityScore),
            score: toFiniteNumber(entry?.score)
        };
    });
}

function buildStrategyRunReportPayload(runContext, modeOverride = null) {
    const summary = calculateLiveSummary();
    const mode = modeOverride || runContext?.mode || currentExecutionMode();
    const startTime = runContext?.startTime || new Date().toISOString();
    const durationStartMs = Date.parse(startTime);
    return {
        runId: runContext?.runId || `${mode}-${Date.now()}`,
        sessionId: positionSessionId || null,
        mode,
        underlying: runContext?.underlying || currentInputs().underlying,
        strategyName: runContext?.strategyName || currentStrategyLabel(),
        startTime,
        endTime: new Date().toISOString(),
        durationMs: Number.isFinite(durationStartMs) ? Math.max(0, Date.now() - durationStartMs) : 0,
        initialMaxLots: Number(runContext?.initialMaxLots || 20),
        lotSize: Number(runContext?.lotSize || currentScenarioLotSize()),
        liveSpot: toFiniteNumber(latestLiveStructure?.liveSpot),
        finalNetPremiumPoints: toFiniteNumber(latestLiveStructure?.economicNetPremiumPoints),
        bookedPnl: toFiniteNumber(summary.totalBookedPnl) ?? 0,
        livePnl: toFiniteNumber(summary.totalMtmRupees),
        totalPnl: toFiniteNumber(summary.totalFinalPnlRupees) ?? 0,
        initialStructure: Array.isArray(runContext?.initialStructure) ? runContext.initialStructure : captureInitialReportStructure(),
        finalStructure: captureFinalReportStructure(summary),
        timeline: captureReportTimeline()
    };
}

async function saveStrategyRunReport(runContext, modeOverride = null) {
    if (!runContext) {
        return null;
    }
    try {
        const response = await fetchJson(`${apiBase()}/api/reports/strategy-run`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json;charset=UTF-8"
            },
            body: JSON.stringify(buildStrategyRunReportPayload(runContext, modeOverride))
        });
        return response?.path || null;
    } catch (error) {
        console.warn("Unable to write strategy run report", error);
        return null;
    }
}

async function finalizeSimulationRunReport() {
    const runContext = activeSimulationRunReportContext;
    activeSimulationRunReportContext = null;
    if (!runContext) {
        return;
    }
    await saveStrategyRunReport(runContext, "simulation");
}

function latestDeltaAdjustmentAuditEntry() {
    return deltaAdjustmentLog.find((entry) =>
        String(entry?.actionType || "").trim().toLowerCase() === "delta_adjustment"
        && String(entry?.adjustmentActionType || "").trim() !== ""
    ) || null;
}

function renderHeader() {
    const inputs = currentInputs();
    const structure = latestLiveStructure || {};
    const spotPriceType = String(structure.liveSpotPriceType || "UNAVAILABLE").toUpperCase();
    const sessionConfig = sessionModeConfig(structure.sessionState, spotPriceType);
    const spotValue = Number.isFinite(Number(structure.liveSpot))
        ? formatResolvedPrice(structure.liveSpot)
        : "Unavailable";
    if (els.marketUnderlyingLabel) {
        els.marketUnderlyingLabel.textContent = sessionConfig.underlyingLabel;
    }
    if (els.livePremiumLabel) {
        els.livePremiumLabel.textContent = sessionConfig.premiumLabel;
    }
    if (els.summaryPremiumLabel) {
        els.summaryPremiumLabel.textContent = sessionConfig.summaryPremiumLabel;
    }
    if (els.legsMarketValueHeader) {
        els.legsMarketValueHeader.textContent = sessionConfig.marketValueHeader;
    }
    els.marketUnderlyingChip.textContent = `${inputs.underlying} ${spotValue}`;
    els.marketUnderlyingChip.title = buildPriceMetaTitle({
        priceType: structure.liveSpotPriceType,
        source: structure.liveSpotSource,
        asOf: structure.liveSpotAsOf,
        tradeDate: structure.liveSpotTradeDate,
        sessionState: structure.sessionState,
        isStale: structure.liveSpotIsStale,
        diagnosticReason: structure.liveSpotDiagnosticReason
    });
    els.strategyChip.textContent = inputs.strategyLabel;
    els.orientationChip.textContent = titleCase(inputs.orientation);
    els.timeframeChip.textContent = inputs.timeframe;
    els.premiumChip.textContent = Number.isFinite(Number(structure.economicNetPremiumPoints))
        ? formatResolvedPrice(structure.economicNetPremiumPoints)
        : `${formatNumber(inputs.currentEconomicPremium, 2)} pts`;
    els.premiumChip.title = buildPriceMetaTitle({
        priceType: inferStructurePremiumPriceType(structure),
        source: structure.liveSpotSource,
        asOf: structure.asOf,
        sessionState: structure.sessionState,
        isStale: structure.partialData,
        diagnosticReason: structure.partialData ? "Structure pricing incomplete" : ""
    });
}

function setStatus(message) {
    els.statusLine.textContent = message;
}

function resetLiveStrip() {
    els.liveStatusChip.textContent = "Disabled";
    els.liveSpotChip.textContent = "-";
    els.livePremiumChip.textContent = "-";
    els.livePnlChip.textContent = "-";
    els.liveLotChip.textContent = "-";
    els.liveConfidenceChip.textContent = "-";
    els.liveLastTickChip.textContent = "-";
    latestLiveStructure = null;
    previousLiveLegPrices = [];
    if (!positionSessionId) {
        clearLockedEntryBaseline();
        baselineCapturedAt = null;
        entryBaselinePinned = false;
    }
    renderLegs();
    renderHeader();
}

function setText(target, value) {
    if (!target) {
        return;
    }
    target.textContent = value;
}

function setPlaceholderResults() {
    [
        els.terminalEntryPremium,
        els.terminalEntryLabel,
        els.terminalLivePremium,
        els.terminalLiveLabel,
        els.terminalLivePnl,
        els.terminalLivePnlState,
        els.terminalConfidence,
        els.terminalConfidenceDetail,
        els.terminalObservations,
        els.terminalEvidence,
        els.terminalPremiumVerdict,
        els.terminalPremiumDetail,
        els.terminalEdgeVerdict,
        els.terminalEdgeDetail,
        els.terminalOverallVerdict,
        els.terminalOverallDetail,
        els.snapshotObservations,
        els.snapshotAverageEntry,
        els.snapshotMedianEntry,
        els.snapshotPercentile,
        els.snapshotVsHistory,
        els.snapshotExpiryValue,
        els.snapshotAveragePnl,
        els.snapshotMedianPnl,
        els.snapshotWinRate,
        els.snapshotBestWorst,
        els.snapshotBoundsLabel,
        els.snapshotAnchorNote,
        els.snapshotPayoffRatio,
        els.snapshotExpectancy,
        els.expiryPayout,
        els.expirySellerPnl,
        els.expiryBuyerPnl,
        els.expiryWinRate,
        els.expiryBuyerWinRate,
        els.expiryTailLoss,
        els.expiryDownside,
        els.expiryExpectancy,
        els.recommendationPreferredTitle,
        els.recommendationPreferredReason,
        els.recommendationAlternativeTitle,
        els.recommendationAlternativeReason,
        els.recommendationAvoidTitle,
        els.recommendationAvoidReason,
        els.insightPremium,
        els.insightPremiumDetail,
        els.insightEdge,
        els.insightEdgeDetail,
        els.insightRisk,
        els.insightRiskDetail
    ].forEach((node) => setText(node, "-"));

    els.snapshotAveragePnlRaw.textContent = "";
    els.snapshotPayoffRatioRaw.textContent = "";
    els.expiryTailLossRaw.textContent = "";
    if (els.insightVerdict) {
        els.insightVerdict.textContent = "-";
        els.insightVerdict.className = "verdict-badge verdict-neutral";
    }
    els.snapshotAverageEntryLabel.innerHTML = 'Average entry <small class="unit">pts</small>';
    els.snapshotMedianEntryLabel.innerHTML = 'Median entry <small class="unit">pts</small>';
    els.snapshotVsHistoryNote.textContent = "Positive means current premium is above the matched average.";
    els.snapshotWinRateLabel.textContent = "Win rate";
    els.expiryPayoutLabel.innerHTML = 'Average expiry value <small class="unit">pts</small>';
    els.trendAvgHeader.textContent = "Avg";
    els.trendMedianHeader.textContent = "Median";
    els.trendVsCurrentHeader.textContent = "Vs current";
    els.timeframeTableBody.innerHTML = ["5Y", "2Y", "1Y", "6M", "3M", "1M"].map((label) => `<tr><td>${label}</td><td>-</td><td>-</td><td>-</td></tr>`).join("");
    els.trendLine.setAttribute("points", "");
    els.trendPoints.innerHTML = "";
    els.trendLabels.innerHTML = "";
    els.currentPriceLine.setAttribute("y1", "110");
    els.currentPriceLine.setAttribute("y2", "110");
    latestReportPayload = null;
}

async function fetchJson(url, options = {}) {
    const response = await fetch(url, options);
    if (!response.ok) {
        let detail = `${response.status} ${response.statusText}`;
        try {
            const payload = await response.json();
            if (payload.error) {
                detail = payload.details ? `${payload.error}: ${payload.details}` : payload.error;
            }
        } catch (error) {
            // ignore parse failures
        }
        throw new Error(detail);
    }
    return response.json();
}

async function clearData() {
    if (!confirm("Clear all data? This will delete the position session and audit log.")) {
        return;
    }
    if (positionSessionId) {
        try {
            await fetch(`${apiBase()}/api/position-sessions/${encodeURIComponent(positionSessionId)}`, {
                method: "DELETE"
            });
        } catch (_e) {
            // best-effort; proceed with UI reset regardless
        }
    }
    positionSessionId = null;
    positionSessionStatus = "DRAFT";
    positionSessionCreatedAt = null;
    persistActivePositionSessionId("");
    legsState = [];
    latestReportPayload = null;
    activeLiveRunReportContext = null;
    activeSimulationRunReportContext = null;
    clearDeltaAdjustmentState();
    resetLegsForStrategy();
    setPlaceholderResults();
    resetLiveStrip();
    setStatus("Data cleared.");
}

async function runAnalysis() {
    const qtyValidation = renderScenarioQtyUi();
    if (!qtyValidation.valid) {
        els.scenarioQty?.reportValidity();
        setStatus(qtyValidation.error || "Qty is invalid.");
        return;
    }
    if (!Number.isFinite(strategySpot)) {
        setStatus("Spot unavailable. Cannot run analysis until spot resolves.");
        return;
    }
    await refreshLiveStructure(true);
    if (!positionSessionId && latestLiveStructure?.deltaAdjustment?.applied) {
        const adjustment = latestLiveStructure.deltaAdjustment;
        latestLiveStructure.legs = (latestLiveStructure.legs || [])
            .map((liveLeg) => {
                const matches = String(adjustment.instrumentId || "").trim()
                    ? String(liveLeg.instrumentId || "").trim() === String(adjustment.instrumentId || "").trim()
                    : (liveLeg.label === adjustment.leg
                        && String(liveLeg.optionType || "") === String(adjustment.optionType || "")
                        && String(liveLeg.side || "") === String(adjustment.side || "")
                        && toFiniteNumber(liveLeg.strike) === toFiniteNumber(adjustment.strike));
                return matches
                    ? { ...liveLeg, quantity: Number(adjustment.oldQuantity || 0) }
                    : liveLeg;
            })
            .filter((liveLeg) => !(String(adjustment.actionType || "").toUpperCase() === "ADD"
                && Number(adjustment.oldQuantity || 0) <= 0
                && (String(adjustment.instrumentId || "").trim()
                    ? String(liveLeg.instrumentId || "").trim() === String(adjustment.instrumentId || "").trim()
                    : (liveLeg.label === adjustment.leg
                        && String(liveLeg.optionType || "") === String(adjustment.optionType || "")
                        && String(liveLeg.side || "") === String(adjustment.side || "")
                        && toFiniteNumber(liveLeg.strike) === toFiniteNumber(adjustment.strike)))));
        latestLiveStructure.deltaAdjustment = null;
        latestLiveStructure.lastDeltaAdjustmentTs = null;
    }
    const sessionCreated = await ensurePositionSessionInitialized();
    if (sessionCreated) {
        await refreshLiveStructure(true);
    }
    analysisRunId += 1;
    const inputs = currentInputs();
    const executionMode = currentExecutionMode();
    activeLiveRunReportContext = executionMode === "live"
        ? buildRunReportContext("live", {
            startTime: new Date().toISOString(),
            runId: `live-${analysisRunId}-${Date.now()}`
        })
        : activeLiveRunReportContext;
    renderHeader();
    setStatus(`Running canonical historical structure query for ${inputs.strategyLabel}...`);

    if (strategyAbortController) {
        strategyAbortController.abort();
    }
    strategyAbortController = new AbortController();

    try {
        const body = buildStrategyRequestBody(inputs);

        const payload = await fetchJson(`${apiBase()}/api/strategy-analysis`, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body: body.toString(),
            signal: strategyAbortController.signal
        });

        applyStrategyAnalysis(payload, inputs, null);
        latestReportPayload = { inputs, payload, analysisRunId };
        const warning = payload.observation?.lowSampleWarning ? ` ${payload.observation.lowSampleWarning}` : "";
        setStatus(`EconomicMetrics loaded from canonical history: ${payload.observation?.observationCount || 0} matched structures. Analysis run ${analysisRunId}.${warning}`);
        markLiveStateDirty("analysis-run");
        flushLiveStrategyState();
        if (executionMode === "live") {
            await saveStrategyRunReport(activeLiveRunReportContext, "live");
            activeLiveRunReportContext = null;
        }
    } catch (error) {
        if (error.name === "AbortError") {
            return;
        }
        if (executionMode === "live") {
            activeLiveRunReportContext = null;
        }
        setPlaceholderResults();
        setStatus(`Historical structure query unavailable: ${error.message}`);
    }
}

function captureEntryBaselineFromLive() {
    const liveLegs = Array.isArray(latestLiveStructure?.legs) ? latestLiveStructure.legs : [];
    const nextLegs = legsState.map((leg, index) => {
        const lastPrice = toFiniteNumber(liveLegs[index]?.lastPrice);
        const baselineEntry = lastPrice === null ? Number(leg.entryPrice || 0) : Number(lastPrice.toFixed(2));
        return {
            ...leg,
            lockedEntryPrice: baselineEntry
        };
    });
    legsState = nextLegs;
    entryBaselinePinned = true;
    baselineCapturedAt = new Date();
    markLiveStateDirty("baseline-captured");
    logLiveDiagnostic("baseline-captured", {
        analysisRunId: analysisRunId + 1,
        baselineCapturedAt: baselineCapturedAt.toISOString(),
        lockedEntries: nextLegs.map((leg) => leg.lockedEntryPrice)
    });
    renderLegs();
}

function startLiveSync() {
    renderLiveRefreshStamp();
    refreshLiveStatus();
    refreshLiveStructure(true);
    if (liveSyncTimer) {
        window.clearInterval(liveSyncTimer);
    }
    if (liveBatchTimer) {
        window.clearInterval(liveBatchTimer);
    }
    liveSyncTimer = window.setInterval(() => {
        refreshLiveStatus();
        refreshLiveStructure();
    }, LIVE_POLL_MS);
    liveBatchTimer = window.setInterval(() => {
        flushLiveStrategyState();
    }, LIVE_BATCH_MS);
}

async function refreshLiveStatus() {
    try {
        const status = await fetchJson(`${apiBase()}/api/live/status`);
        latestLiveStatus = status;
        els.liveStatusChip.textContent = status.status || "Disabled";
        els.liveLastTickChip.textContent = formatLiveTime(status.lastTickTs, status.secondsSinceLastTick);
        markLiveStateDirty("status");
    } catch (error) {
        resetLiveStrip();
    }
}

async function refreshLiveOverlay() {
    return refreshLiveStructure();
    const inputs = currentInputs();
    if (liveAbortController) {
        liveAbortController.abort();
    }
    liveAbortController = new AbortController();
    try {
        const body = new URLSearchParams({
            mode: inputs.strategyMode,
            orientation: inputs.orientation,
            underlying: inputs.underlying,
            expiryType: inputs.expiryType,
            dte: String(inputs.dte),
            spot: String(inputs.spot),
            timeframe: inputs.timeframe,
            legCount: String(inputs.legs.length)
        });
        inputs.legs.forEach((leg, index) => {
            body.set(`leg${index}Label`, leg.label);
            body.set(`leg${index}OptionType`, leg.optionType);
            body.set(`leg${index}Side`, leg.side);
            body.set(`leg${index}Strike`, String(leg.strike));
            body.set(`leg${index}EntryPrice`, String(leg.entryPrice));
        });

        const overlay = await fetchJson(`${apiBase()}/api/live/overlay`, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body: body.toString(),
            signal: liveAbortController.signal
        });

        await applyLiveOverlay(overlay, inputs);
    } catch (error) {
        if (error.name === "AbortError") {
            return;
        }
        if (!String(error.message || "").includes("404")) {
            els.liveStatusChip.textContent = "Unavailable";
        }
    }
}

async function refreshLiveStructure(force = false) {
    if (!Number.isFinite(strategySpot)) {
        return;
    }
    if (liveStructureFetchInFlight && !force) {
        return;
    }
    const inputs = currentInputs();
    if (force && liveAbortController) {
        liveAbortController.abort();
    }
    liveAbortController = new AbortController();
    liveStructureFetchInFlight = true;
    try {
        const startedAt = performance.now();
        const body = buildStrategyRequestBody(inputs);
        const structure = await fetchJson(`${apiBase()}/api/live/structure`, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body: body.toString(),
            signal: liveAbortController.signal
        });
        await applyLiveStructureSnapshot(structure, inputs, startedAt, force);
    } catch (error) {
        if (error.name === "AbortError") {
            return;
        }
        if (!String(error.message || "").includes("404")) {
            els.liveStatusChip.textContent = "Unavailable";
        }
    } finally {
        liveStructureFetchInFlight = false;
    }
}

function buildStrategyRequestBody(inputs) {
    const body = new URLSearchParams({
        mode: inputs.strategyMode,
        orientation: inputs.orientation,
        underlying: inputs.underlying,
        expiryType: inputs.expiryType,
        dte: String(inputs.dte),
        spot: String(inputs.spot),
        timeframe: inputs.timeframe,
        scenarioQty: String(inputs.scenarioQty || currentScenarioLotSize()),
        legCount: String(inputs.legs.length)
    });
    if (inputs.lastDeltaAdjustmentTs) {
        body.set("lastDeltaAdjustmentTs", inputs.lastDeltaAdjustmentTs);
    }
    const lastAdjustmentEntry = latestDeltaAdjustmentAuditEntry();
    if (lastAdjustmentEntry && inputs.lastDeltaAdjustmentTs) {
        const leg = legsState.find((item) => item.legId === lastAdjustmentEntry.legId) || {};
        const strike = toFiniteNumber(leg.strike);
        body.set("lastDeltaAdjustmentActionType", String(lastAdjustmentEntry.adjustmentActionType || "").toUpperCase());
        if (leg.optionType) {
            body.set("lastDeltaAdjustmentOptionType", leg.optionType);
        }
        if (leg.side) {
            body.set("lastDeltaAdjustmentSide", leg.side);
        }
        if (strike !== null) {
            body.set("lastDeltaAdjustmentStrike", String(strike));
        }
    }
    if (inputs.pendingAdjustmentSinceTs) {
        body.set("pendingAdjustmentSinceTs", inputs.pendingAdjustmentSinceTs);
    }
    inputs.legs.forEach((leg, index) => {
        body.set(`leg${index}Label`, leg.label);
        body.set(`leg${index}OptionType`, leg.optionType);
        body.set(`leg${index}Side`, leg.side);
        body.set(`leg${index}Strike`, String(leg.strike));
        body.set(`leg${index}EntryPrice`, String(leg.entryPrice));
        body.set(`leg${index}Quantity`, String(resolvedQuantityForLeg(leg, null, currentScenarioLotSize())));
    });
    return body;
}

async function applyLiveStructureSnapshot(structure, inputs, startedAt, force) {
    latestLiveStructure = structure;
    syncLegMetadataFromLiveStructure(structure);
    await applyDeltaAdjustmentOutcome(structure?.deltaAdjustment || null, structure?.lastDeltaAdjustmentTs || null);
    pendingAdjustmentSinceTs = structure?.pendingAdjustmentSinceTs || null;
    const derivedDte = deriveDteFromStructure(structure);
    if (derivedDte !== null) {
        strategyDteDays = derivedDte;
    }
    const sessionConfig = sessionModeConfig(structure.sessionState, String(structure.liveSpotPriceType || "UNAVAILABLE").toUpperCase());
    els.liveSpotChip.textContent = formatResolvedPrice(structure.liveSpot);
    els.liveSpotChip.title = buildPriceMetaTitle({
        priceType: structure.liveSpotPriceType,
        source: structure.liveSpotSource,
        asOf: structure.liveSpotAsOf,
        tradeDate: structure.liveSpotTradeDate,
        sessionState: structure.sessionState,
        isStale: structure.liveSpotIsStale,
        diagnosticReason: structure.liveSpotDiagnosticReason
    });
    els.livePremiumChip.textContent = Number.isFinite(Number(structure.economicNetPremiumPoints))
        ? formatResolvedPrice(structure.economicNetPremiumPoints)
        : "Unavailable";
    els.livePremiumChip.title = buildPriceMetaTitle({
        priceType: inferStructurePremiumPriceType(structure),
        source: structure.liveSpotSource,
        asOf: structure.asOf,
        sessionState: structure.sessionState,
        isStale: structure.partialData,
        diagnosticReason: structure.partialData ? "Structure pricing incomplete" : ""
    });
    els.liveLotChip.textContent = Number.isFinite(Number(structure.effectiveLotSize))
        && Number(structure.effectiveLotSize) > 0
        && structure.partialData !== true
        ? `${Math.round(Number(structure.effectiveLotSize))} x`
        : "-";
    if (els.marketUnderlyingLabel) {
        els.marketUnderlyingLabel.textContent = sessionConfig.underlyingLabel;
    }
    if (els.livePremiumLabel) {
        els.livePremiumLabel.textContent = sessionConfig.premiumLabel;
    }
    if (els.summaryPremiumLabel) {
        els.summaryPremiumLabel.textContent = sessionConfig.summaryPremiumLabel;
    }
    if (els.legsMarketValueHeader) {
        els.legsMarketValueHeader.textContent = sessionConfig.marketValueHeader;
    }
    els.marketUnderlyingChip.textContent = `${inputs.underlying} ${formatResolvedPrice(structure.liveSpot)}`;
    els.marketUnderlyingChip.title = els.liveSpotChip.title;
    hydrateInputsFromLiveOverlay({ price: structure.liveSpot }, structure);
    logLiveDiagnostic("structure-cache-update", {
        analysisRunId,
        latencyMs: Math.round(performance.now() - startedAt),
        asOf: structure.asOf,
        force
    });
    markLiveStateDirty("structure");
}

function deriveDteFromStructure(structure) {
    const instrumentId = String(structure?.legs?.[0]?.instrumentId || "");
    const match = instrumentId.match(/_(\d{8})_/);
    if (!match) {
        return null;
    }
    const raw = match[1];
    const expiry = new Date(`${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6, 8)}T00:00:00`);
    if (Number.isNaN(expiry.getTime())) {
        return null;
    }
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const diffMs = expiry.getTime() - today.getTime();
    return Math.max(0, Math.round(diffMs / 86400000));
}

async function applyLiveOverlay(overlay, inputs) {
    const status = overlay.status || {};
    const spot = overlay.spot || {};
    const structure = overlay.structure || {};
    const liveEconomic = overlay.liveEconomic || {};
    latestLiveStructure = structure;
    syncLegMetadataFromLiveStructure(structure);
    await applyDeltaAdjustmentOutcome(structure?.deltaAdjustment || null, structure?.lastDeltaAdjustmentTs || null);

    els.liveStatusChip.textContent = status.status || "Disabled";
    els.liveSpotChip.textContent = Number.isFinite(Number(spot.price)) ? `${formatNumber(spot.price, 2)} pts` : "-";
    els.liveSpotChip.title = buildPriceMetaTitle({
        priceType: spot.priceType,
        source: spot.source,
        asOf: spot.asOf,
        tradeDate: spot.tradeDate,
        sessionState: spot.sessionState,
        isStale: spot.isStale,
        diagnosticReason: spot.diagnosticReason
    });
    els.livePremiumChip.textContent = Number.isFinite(Number(structure.economicNetPremiumPoints))
        ? `${formatNumber(structure.economicNetPremiumPoints, 2)} pts`
        : "-";
    els.liveLotChip.textContent = Number.isFinite(Number(liveEconomic.lot?.lotSize))
        && Number(liveEconomic.lot.lotSize) > 0
        && structure.partialData !== true
        ? `${Math.round(Number(liveEconomic.lot.lotSize))} x`
        : "-";
    els.liveConfidenceChip.textContent = liveEconomic.confidence?.effectiveConfidenceLevel || "-";
    els.liveLastTickChip.textContent = formatLiveTime(status.lastTickTs, status.secondsSinceLastTick);
    els.marketUnderlyingChip.textContent = Number.isFinite(Number(spot.price))
        ? `${inputs.underlying} ${formatNumber(spot.price, 2)} pts`
        : `${inputs.underlying} ${formatNumber(inputs.spot, 2)} pts`;
    els.marketUnderlyingChip.title = els.liveSpotChip.title;
    hydrateInputsFromLiveOverlay(spot, structure);
    renderLegs();

    if (overlay.historicalComparison) {
        const liveInputs = {
            ...inputs,
            spot: Number.isFinite(Number(spot.price)) ? Number(spot.price) : inputs.spot,
            legs: legsState.map((leg) => ({ ...leg })),
            currentEconomicPremium: Number(structure.economicNetPremiumPoints || currentEconomicPremiumFromInputs())
        };
        applyStrategyAnalysis(overlay.historicalComparison, liveInputs, liveEconomic);
        latestReportPayload = { inputs: liveInputs, payload: overlay.historicalComparison, liveEconomic };
        const liveNote = structure.partialData
            ? " Live feed partial; comparison held to available legs only."
            : " Live structure compared against canonical history.";
        const confidenceNote = liveEconomic.confidence?.effectiveConfidenceLevel
            ? ` Confidence ${liveEconomic.confidence.effectiveConfidenceLevel}.`
            : "";
        const pnlNote = Number.isFinite(Number(liveEconomic.pnl?.livePnlPoints))
            ? ` Live one-lot MTM ${formatSigned(liveEconomic.pnl.livePnlPoints, 2)} pts.`
            : "";
        setStatus(`${status.status || "LIVE"} overlay active.${liveNote}${pnlNote}${confidenceNote}`);
    }
}

function hydrateInputsFromLiveOverlay(spot, structure) {
    const liveSpot = Number(spot?.price);
    const liveLegs = Array.isArray(structure?.legs) ? structure.legs : [];
    const canHydrateSpot = Number.isFinite(liveSpot);
    const canHydrateLegs = !entryBaselinePinned
        && !positionSessionId
        && liveLegs.length > 0
        && liveLegs.some((leg) => Number.isFinite(Number(leg.lastPrice)));
    if (!canHydrateSpot && !canHydrateLegs) {
        return;
    }

    liveHydrationMuted = true;
    try {
        if (canHydrateSpot) {
            strategySpot = Number(liveSpot.toFixed(2));
        }
        liveLegs.forEach((liveLeg, index) => {
            if (!legsState[index]) {
                return;
            }
            const lastPrice = Number(liveLeg.lastPrice);
            if (Number.isFinite(lastPrice)) {
                legsState[index].entryPrice = Number(lastPrice.toFixed(2));
            }
            if (Number.isFinite(Number(liveLeg.strike))) {
                legsState[index].strike = Number(liveLeg.strike);
                legsState[index].distance = moneynessDistanceFromStrike(
                    legsState[index].optionType,
                    Number(liveLeg.strike),
                    strategySpot
                );
            }
            const liveQuantity = Number(liveLeg.quantity);
            if (Number.isFinite(liveQuantity) && liveQuantity > 0) {
                legsState[index].quantity = Math.round(liveQuantity);
            }
        });
        renderLegs();
        renderHeader();
    } finally {
        liveHydrationMuted = false;
    }
}

function formatLiveTime(timestamp, secondsSinceLastTick) {
    if (!timestamp) {
        return "-";
    }
    if (Number.isFinite(Number(secondsSinceLastTick)) && Number(secondsSinceLastTick) >= 0) {
        return `${secondsSinceLastTick}s ago`;
    }
    return new Date(timestamp).toLocaleTimeString("en-IN");
}

function applyStrategyAnalysis(payload, inputs, liveEconomic = null) {
    renderHeader();
    applyInsight(payload.insight, payload.observation);
    applyCompactSummary(payload, inputs, liveEconomic);
    applySnapshot(payload);
    applyPremiumTrend(payload.timeframeTrend);
    applyExpiry(payload);
    applyRecommendation("preferred", payload.recommendation?.preferred);
    applyRecommendation("alternative", payload.recommendation?.alternative);
    applyRecommendation("avoid", payload.recommendation?.avoid);
    if (payload.recommendation?.contextNote) {
        els.recommendationAvoidReason.textContent = `${payload.recommendation.avoid?.reason || "-"} ${payload.recommendation.contextNote}`.trim();
    }
    renderHeader();
}

function applyCompactSummary(payload, inputs, liveEconomic) {
    const summary = calculateLiveStructureSummary();
    applyLiveMtmTotals(summary);
    renderTerminalLegMtm(inputs, latestLiveStructure);
}

function renderTerminalLegMtm(inputs, structure) {
    if (!els.terminalLegsGrid) {
        return;
    }
    const summary = calculateLiveStructureSummary();
    const legCards = summary.rows.map((row, index) => {
        const mtmPoints = row.mtmRupees === null || !summary.baseLotSize
            ? null
            : row.mtmRupees / summary.baseLotSize;
        return `
            <article class="metric-tile terminal-leg-tile">
                <span>${escapeHtml(row.leg.label || `Leg ${index + 1}`)}</span>
                <strong>${mtmPoints === null ? "-" : `${formatSigned(mtmPoints, 2)} pts`}</strong>
            </article>
        `;
    });
    els.terminalLegsGrid.innerHTML = legCards.join("");
}

function applyInsight(insight, observation) {
    setText(els.insightPremium, insight?.premiumVerdict || "-");
    setText(els.insightPremiumDetail, insight?.premiumDetail || "-");
    setText(els.insightEdge, insight?.edgeVerdict || "-");
    setText(els.insightEdgeDetail, insight?.edgeDetail || "-");
    setText(els.insightRisk, insight?.riskVerdict || "-");
    setText(els.insightRiskDetail, insight?.riskDetail || "-");
    if (els.insightVerdict) {
        els.insightVerdict.textContent = insight?.overallVerdict || "-";
        els.insightVerdict.className = `verdict-badge ${verdictClass(insight?.overallVerdict, observation?.lowSampleDowngrade)}`;
    }
}

function verdictClass(text, lowSample) {
    if (lowSample) {
        return "verdict-neutral";
    }
    const value = String(text || "").toLowerCase();
    if (value.includes("supportive") || value.includes("preferred")) {
        return "verdict-positive";
    }
    if (value.includes("unattractive") || value.includes("avoid")) {
        return "verdict-negative";
    }
    return "verdict-neutral";
}

function applySnapshot(payload) {
    const observation = payload.observation || {};
    const premium = payload.premium || {};
    const expiry = payload.expiry || {};
    const pnl = payload.pnl || {};
    const risk = payload.risk || {};

    els.snapshotAverageEntryLabel.innerHTML = `${premium.averageEntryLabel || "Average entry"} <small class="unit">pts</small>`;
    els.snapshotMedianEntryLabel.innerHTML = `${premium.medianEntryLabel || "Median entry"} <small class="unit">pts</small>`;
    els.snapshotVsHistoryNote.textContent = "Positive means current premium is above the matched average for the selected side.";
    els.snapshotWinRateLabel.textContent = `${pnl.selectedSideLabel || expiry.selectedSideLabel || "Selected side"} win rate`;
    els.expiryPayoutLabel.innerHTML = `${expiry.averageExpiryValueLabel || "Average expiry value"} <small class="unit">pts</small>`;
    els.trendAvgHeader.textContent = premium.averageEntryLabel || "Average entry";
    els.trendMedianHeader.textContent = premium.medianEntryLabel || "Median entry";
    els.trendVsCurrentHeader.textContent = "Current vs avg";

    els.snapshotObservations.textContent = `${observation.observationCount || 0}`;
    els.snapshotAverageEntry.textContent = formatNumber(premium.averageEntryPoints || 0, 2);
    els.snapshotMedianEntry.textContent = formatNumber(premium.medianEntryPoints || 0, 2);
    els.snapshotPercentile.textContent = percentileText(
        premium.rawPricePercentile,
        premium.economicPercentile,
        premium.percentileReliable
    );
    els.snapshotVsHistory.textContent = `${formatSigned(premium.currentVsAveragePoints || 0, 2)} pts`;
    els.snapshotExpiryValue.textContent = formatNumber(expiry.averageExpiryValuePoints || 0, 2);
    els.snapshotAveragePnl.textContent = `${formatSigned(pnl.averagePnlPoints || 0, 2)} pts`;
    els.snapshotAveragePnlRaw.textContent = pnl.expectancyLabel || "";
    els.snapshotMedianPnl.textContent = `${formatSigned(pnl.medianPnlPoints || 0, 2)} pts`;
    els.snapshotWinRate.textContent = formatPercent(expiry.selectedSideWinRatePct || 0, 1);
    els.snapshotBestWorst.textContent = `${boundText(risk.currentTheoreticalMaxProfitPoints)} / ${boundText(risk.currentTheoreticalMaxLossPoints)}`;
    els.snapshotBoundsLabel.textContent = [risk.boundsLabel, risk.lowSampleWarning].filter(Boolean).join(" | ");
    els.snapshotPayoffRatio.textContent = payoffLabel(pnl.payoffRatio);
    els.snapshotPayoffRatioRaw.textContent = Number.isFinite(pnl.payoffRatio) && pnl.payoffRatio > 0 ? `Raw ratio ${formatNumber(pnl.payoffRatio, 2)}` : "";
    els.snapshotExpectancy.textContent = `${formatSigned(pnl.expectancyPoints || 0, 2)} pts`;
    els.snapshotAnchorNote.textContent = [observation.anchorDate ? `Anchor ${observation.anchorDate}` : "", observation.evidenceStrength].filter(Boolean).join(" | ");
}

function percentileText(rawPercentile, economicPercentile, percentileReliable = true) {
    const raw = Number(rawPercentile || 0);
    const economic = Number(economicPercentile || 0);
    if ((!raw && !economic) || percentileReliable === false) {
        return percentileReliable === false ? "Not reliable (low sample)" : "-";
    }
    if (!raw && !economic) {
        return "-";
    }
    return `Price ${raw}th / Economic ${economic}th`;
}

function payoffLabel(payoffRatio) {
    const value = Number(payoffRatio || 0);
    if (!Number.isFinite(value) || value <= 0) {
        return "-";
    }
    if (value >= 2.0) {
        return "Asymmetric upside";
    }
    if (value >= 1.0) {
        return "Balanced payoff";
    }
    return "Thin payoff";
}

function boundText(value) {
    if (value === null || value === undefined) {
        return "Unlimited";
    }
    return `${formatNumber(value, 2)} pts`;
}

function applyPremiumTrend(timeframeTrend) {
    const windows = timeframeTrend?.windows || [];
    els.timeframeTableBody.innerHTML = windows.map((item) => `
        <tr>
            <td>${item.label}</td>
            <td>${item.observationCount > 0 ? formatNumber(item.averagePremiumPoints || 0, 2) : "-"}</td>
            <td>${item.observationCount > 0 ? formatNumber(item.medianPremiumPoints || 0, 2) : "-"}</td>
            <td>${item.observationCount > 0 ? `${formatSigned(item.currentVsAveragePoints || 0, 2)} pts` : "-"}</td>
        </tr>
    `).join("");

    const chartWindows = windows.filter((item) => Number(item.observationCount || 0) > 0);
    if (chartWindows.length === 0) {
        els.trendLine.setAttribute("points", "");
        els.trendPoints.innerHTML = "";
        els.trendLabels.innerHTML = "";
        els.currentPriceLine.setAttribute("y1", "110");
        els.currentPriceLine.setAttribute("y2", "110");
        return;
    }

    const currentPremium = Number(timeframeTrend.currentPremiumPoints || 0);
    const values = chartWindows.map((item) => Number(item.averagePremiumPoints || 0)).concat(currentPremium);
    const minValue = Math.min(...values);
    const maxValue = Math.max(...values);
    const padTop = 20;
    const padBottom = 42;
    const plotHeight = 220 - padTop - padBottom;
    const startX = 44;
    const endX = 616;
    const stepX = chartWindows.length > 1 ? (endX - startX) / (chartWindows.length - 1) : 0;

    const valueToY = (value) => {
        if (maxValue === minValue) {
            return padTop + (plotHeight / 2);
        }
        const ratio = (value - minValue) / (maxValue - minValue);
        return padTop + ((1 - ratio) * plotHeight);
    };

    const points = chartWindows.map((item, index) => ({
        x: startX + (index * stepX),
        y: valueToY(Number(item.averagePremiumPoints || 0)),
        label: item.label,
        value: Number(item.averagePremiumPoints || 0)
    }));

    els.trendLine.setAttribute("points", points.map((point) => `${point.x},${point.y}`).join(" "));
    const currentY = valueToY(currentPremium);
    els.currentPriceLine.setAttribute("y1", `${currentY}`);
    els.currentPriceLine.setAttribute("y2", `${currentY}`);
    els.trendPoints.innerHTML = points.map((point) => `<circle class="trend-point" cx="${point.x}" cy="${point.y}" r="4"></circle>`).join("");
    els.trendLabels.innerHTML = points.map((point) => `
        <g>
            <text class="trend-value-label" x="${point.x}" y="${point.y - 10}" text-anchor="middle">${formatNumber(point.value, 1)}</text>
            <text class="trend-axis-label" x="${point.x}" y="204" text-anchor="middle">${point.label}</text>
        </g>
    `).join("");
}

function applyExpiry(payload) {
    const expiry = payload.expiry || {};
    const pnl = payload.pnl || {};
    const risk = payload.risk || {};
    const selectedSide = String(expiry.selectedSideLabel || "Selected").toLowerCase();
    const oppositeSide = selectedSide === "seller" ? "buyer" : "seller";

    els.expiryPayout.textContent = `${formatNumber(expiry.averageExpiryPayoutPoints || expiry.averageExpiryValuePoints || 0, 2)} pts`;
    els.expirySellerPnl.textContent = `${formatSigned(selectedSide === "seller" ? expiry.selectedSideAveragePnlPoints || 0 : expiry.oppositeSideAveragePnlPoints || 0, 2)} pts`;
    els.expiryBuyerPnl.textContent = `${formatSigned(selectedSide === "buyer" ? expiry.selectedSideAveragePnlPoints || 0 : expiry.oppositeSideAveragePnlPoints || 0, 2)} pts`;
    els.expiryWinRate.textContent = formatPercent(selectedSide === "seller" ? expiry.selectedSideWinRatePct || 0 : expiry.oppositeSideWinRatePct || 0, 1);
    els.expiryBuyerWinRate.textContent = formatPercent(selectedSide === "buyer" ? expiry.selectedSideWinRatePct || 0 : expiry.oppositeSideWinRatePct || 0, 1);
    els.expiryTailLoss.textContent = risk.downsideProfile || "-";
    els.expiryTailLossRaw.textContent = `${risk.historicalExtremesLabel || "Historical sample"} | P10 ${formatSigned(risk.tailLossP10Points || 0, 2)} pts`;
    els.expiryDownside.textContent = [risk.boundsLabel, `${selectedSide} primary / ${oppositeSide} opposite`].join(" | ");
    els.expiryExpectancy.textContent = `${formatSigned(pnl.expectancyPoints || 0, 2)} pts`;
}

function applyRecommendation(slot, recommendation) {
    const titleTarget = els[`recommendation${capitalize(slot)}Title`];
    const reasonTarget = els[`recommendation${capitalize(slot)}Reason`];
    if (!recommendation) {
        titleTarget.textContent = "-";
        reasonTarget.textContent = "-";
        return;
    }
    titleTarget.textContent = recommendation.title || `${strategyLabel(recommendation.mode)} / ${titleCase(recommendation.orientation)}`;
    const reasonParts = [
        recommendation.verdict,
        `${recommendation.observationCount || 0} observations`,
        recommendation.economicPercentile ? `economic ${recommendation.economicPercentile}th` : "",
        Number.isFinite(recommendation.averagePnlPoints) ? `avg pnl ${formatSigned(recommendation.averagePnlPoints, 2)} pts` : "",
        Number.isFinite(recommendation.winRatePct) ? `win rate ${formatPercent(recommendation.winRatePct, 1)}` : "",
        recommendation.lowSampleWarning || "",
        recommendation.reason || ""
    ].filter(Boolean);
    reasonTarget.textContent = reasonParts.join(" | ");
}

function capitalize(value) {
    return value.charAt(0).toUpperCase() + value.slice(1);
}

function strategyLabel(mode) {
    return strategyConfigs[mode]?.label || mode;
}

async function fetchHistoricalReportPayload(inputs) {
    const body = new URLSearchParams({
        mode: inputs.strategyMode,
        orientation: inputs.orientation,
        underlying: inputs.underlying,
        expiryType: inputs.expiryType,
        dte: String(inputs.dte),
        spot: String(inputs.spot),
        timeframe: inputs.timeframe,
        legCount: String(inputs.legs.length)
    });
    inputs.legs.forEach((leg, index) => {
        body.set(`leg${index}Label`, leg.label);
        body.set(`leg${index}OptionType`, leg.optionType);
        body.set(`leg${index}Side`, leg.side);
        body.set(`leg${index}Strike`, String(leg.strike));
        body.set(`leg${index}EntryPrice`, String(leg.entryPrice));
    });
    const payload = await fetchJson(`${apiBase()}/api/strategy-analysis`, {
        method: "POST",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
        },
        body: body.toString()
    });
    return { inputs, payload };
}

function openPrintableReport(html) {
    const reportWindow = window.open("", "_blank", "noopener,noreferrer");
    if (reportWindow) {
        try {
            reportWindow.document.open();
            reportWindow.document.write(html);
            reportWindow.document.close();
            try {
                reportWindow.history.replaceState({}, "", "/report-preview");
            } catch (_historyError) {
                // ignore same-origin history issues in popup environments
            }
        } catch (_writeError) {
            reportWindow.close();
            return false;
        }
        const finishPopup = () => {
            try {
                reportWindow.focus();
                reportWindow.print();
            } catch (_error) {
                // Let the opened report remain visible even if auto-print is blocked.
            }
        };
        try {
            reportWindow.addEventListener("load", finishPopup, { once: true });
        } catch (_error) {
            // Some browsers do not expose addEventListener on popup windows consistently.
        }
        window.setTimeout(finishPopup, 400);
        return true;
    }

    // Popup-block fallback: print through same-origin iframe.
    const frame = document.createElement("iframe");
    frame.style.position = "fixed";
    frame.style.right = "-10000px";
    frame.style.bottom = "-10000px";
    frame.style.width = "1px";
    frame.style.height = "1px";
    frame.setAttribute("aria-hidden", "true");
    document.body.appendChild(frame);

    const doc = frame.contentDocument;
    if (!doc) {
        frame.remove();
        return false;
    }

    doc.open();
    doc.write(html);
    doc.close();
    try {
        frame.contentWindow?.history.replaceState({}, "", "/report-preview");
    } catch (_historyError) {
        // ignore iframe history issues
    }

    const finish = () => {
        try {
            frame.contentWindow?.focus();
            frame.contentWindow?.print();
        } finally {
            window.setTimeout(() => frame.remove(), 2000);
        }
    };
    frame.onload = finish;
    window.setTimeout(finish, 300);
    return true;
}

async function downloadReport() {
    if (!latestReportPayload) {
        setStatus("Generating fresh historical report payload...");
        try {
            latestReportPayload = await fetchHistoricalReportPayload(currentInputs());
        } catch (error) {
            setStatus(`Unable to generate report from historical data: ${error.message}`);
            return;
        }
    }

    const preflight = validateReportPreflightV2(latestReportPayload);
    if (preflight.errors.length) {
        setStatus(`Report export blocked: ${preflight.errors.join(" ")}`);
        return;
    }

    const printableHtml = buildPrintableReportHtmlV2(latestReportPayload);
    const reportOpenedV2 = openPrintableReport(printableHtml);
    if (!reportOpenedV2) {
        setStatus("Unable to open printable report window. Check browser popup/iframe restrictions.");
        return;
    }
    const warningLine = preflight.warnings.length
        ? ` Preflight warning: ${preflight.warnings.join(" ")}`
        : "";
    setStatus(`Report prepared from historical data. Use the print dialog to save as PDF.${warningLine}`);
}

function buildPrintableReportHtmlV2(reportPayload) {
    const { inputs, payload } = reportPayload;
    const observation = payload.observation || {};
    const premium = payload.premium || {};
    const expiry = payload.expiry || {};
    const pnl = payload.pnl || {};
    const risk = payload.risk || {};
    const insight = payload.insight || {};
    const recommendation = payload.recommendation || {};
    const windows = payload.timeframeTrend?.windows || [];
    const cases = payload.historicalCases || [];

    const confidenceLabel = deriveConfidenceLabelV2(observation);
    const verdictLine = buildDecisionVerdictLineV2(observation, premium, insight);
    const trendSummary = summarizeTrendWindowsV2(windows);
    const caseSummary = summarizeHistoricalCasesV2(cases, pnl, risk);
    const positionSummary = latestPublishedStrategyState?.summary || calculateLiveStructureSummary();
    const reportLegs = buildReportLegRowsV2(inputs.legs);
    const generatedAt = formatReportTimestampV2(new Date());
    const summaryBlocks = [
        reportFieldSectionV2("Scenario Context", [
            ["Strategy", inputs.strategyLabel],
            ["Orientation", titleCase(inputs.orientation)],
            ["Underlying", inputs.underlying],
            ["Expiry type", inputs.expiryType],
            ["Timeframe", inputs.timeframe],
            ["Anchor date", observation.anchorDate || "-"],
            ["Matched instances", String(observation.observationCount || 0)],
            ["Valid expiry observations", String(caseSummary.validExpiryObservations)],
            ["Evidence strength", observation.evidenceStrength || "-"]
        ]),
        reportFieldSectionV2("Premium Context", [
            [toSentenceCaseV2(premium.currentPremiumLabel || "Current premium"), formatPointsV2(premium.currentPremiumPoints ?? inputs.currentEconomicPremium)],
            [toSentenceCaseV2(premium.averageEntryLabel || "Average entry"), formatPointsV2(premium.averageEntryPoints ?? 0)],
            [toSentenceCaseV2(premium.medianEntryLabel || "Median entry"), formatPointsV2(premium.medianEntryPoints ?? 0)],
            ["Economic percentile", formatPercentileDisplayV2(premium.economicPercentile, premium.percentileReliable)],
            ["Raw price percentile", formatPercentileDisplayV2(premium.rawPricePercentile, premium.percentileReliable)],
            ["Vs average", `${formatSigned(premium.currentVsAveragePoints ?? 0, 2)} pts / ${formatSigned(premium.currentVsAveragePct ?? 0, 1)}%`],
            ["Premium condition", premium.priceConditionLabel || "-"],
            ["Trend note", trendSummary.summaryLine]
        ]),
        reportFieldSectionV2("Performance Snapshot", [
            [expiry.averageExpiryValueLabel || "Average expiry value", formatPointsV2(expiry.averageExpiryValuePoints ?? 0)],
            ["Average expiry payout", formatPointsV2(expiry.averageExpiryPayoutPoints ?? 0)],
            ["Average P&L", formatSignedPointsV2(pnl.averagePnlPoints ?? 0)],
            ["Median P&L", formatSignedPointsV2(pnl.medianPnlPoints ?? 0)],
            ["Booked P&L", formatResolvedRupees(positionSummary.totalBookedPnl || 0)],
            ["Live P&L", positionSummary.hasLiveMtm ? formatResolvedRupees(positionSummary.totalMtmRupees || 0) : "Awaiting live marks"],
            ["Final P&L", formatResolvedRupees(positionSummary.totalFinalPnlRupees || 0)],
            ["Median expiry payoff", formatPointsV2(caseSummary.medianExpiryPayoff)],
            ["Expectancy", formatSignedPointsV2(pnl.expectancyPoints ?? 0)],
            ["Win rate", formatPercent(expiry.selectedSideWinRatePct ?? 0, 1)],
            ["Payoff ratio", formatNumber(pnl.payoffRatio ?? 0, 2)]
        ]),
        reportFieldSectionV2("Risk Snapshot", [
            ["Max risk", formatBoundValueV2(risk.currentTheoreticalMaxLossPoints)],
            ["Max profit", formatBoundValueV2(risk.currentTheoreticalMaxProfitPoints)],
            ["Tail loss P10", formatSignedPointsV2(risk.tailLossP10Points ?? 0)],
            ["Downside profile", risk.downsideProfile || "-"],
            ["Best observed P&L", formatSignedPointsV2(caseSummary.bestObservedPnl)],
            ["Worst observed P&L", formatSignedPointsV2(caseSummary.worstObservedPnl)],
            ["Historical extremes", risk.historicalExtremesLabel || "Raw historical sample values"],
            ["Valid expiry observations", String(caseSummary.validExpiryObservations)]
        ])
    ].join("");

    const appendixSections = [
        buildTrendAppendixV2(trendSummary),
        buildHistoricalCasesAppendixV2(cases)
    ].filter(Boolean).join("");

    return `
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Strategy Squad Report - ${escapeHtml(inputs.strategyLabel)}</title>
<style>
@page { size: A4; margin: 12mm 12mm 16mm; }
body {
    font-family: "Segoe UI", Aptos, sans-serif;
    margin: 0;
    color: #182129;
    background: #ffffff;
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
}
.report { padding: 0 0 14mm; }
.page {
    page-break-after: always;
    min-height: 255mm;
    position: relative;
    padding-bottom: 12mm;
}
.page:last-child { page-break-after: auto; }
.header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 16px;
    margin-bottom: 14px;
    padding-bottom: 10px;
    border-bottom: 2px solid #d8e1e7;
}
.header-copy h1 {
    margin: 0 0 6px;
    font-size: 24px;
    letter-spacing: 0.01em;
}
.header-copy p {
    margin: 0;
    color: #4f5c65;
    font-size: 12px;
}
.header-meta {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 6px 12px;
    min-width: 56%;
}
.meta-item {
    padding: 8px 10px;
    border: 1px solid #d8e1e7;
    border-radius: 8px;
    background: #f7fafc;
}
.meta-label {
    display: block;
    margin-bottom: 2px;
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: #6b7881;
}
.meta-value {
    font-size: 13px;
    font-weight: 600;
}
.decision-strip {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px;
    margin-bottom: 12px;
}
.decision-card {
    border: 1px solid #d8e1e7;
    border-radius: 8px;
    padding: 10px 12px;
    background: #ffffff;
}
.decision-card .label {
    display: block;
    margin-bottom: 4px;
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: #6b7881;
}
.decision-card .value,
.report-table .numeric {
    font-family: "Consolas", "SFMono-Regular", monospace;
    text-align: right;
}
.decision-card .value {
    display: block;
    font-size: 16px;
    font-weight: 700;
    color: #182129;
}
.verdict-line {
    margin: 0 0 14px;
    padding: 10px 12px;
    border-left: 4px solid #2f7c79;
    background: #eef7f6;
    font-size: 13px;
    line-height: 1.45;
}
.section-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
    margin-bottom: 12px;
}
.section-card {
    break-inside: avoid;
    border: 1px solid #d8e1e7;
    border-radius: 10px;
    padding: 12px;
    background: #ffffff;
}
.section-card h2,
.appendix h2 {
    margin: 0 0 10px;
    font-size: 13px;
    text-transform: uppercase;
    letter-spacing: 0.09em;
    color: #2f7c79;
}
.field-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 12px;
}
.field-row {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 10px;
    padding-bottom: 5px;
    border-bottom: 1px solid #edf2f5;
}
.field-row:last-child {
    border-bottom: none;
    padding-bottom: 0;
}
.field-label {
    font-size: 11px;
    color: #5a6871;
}
.field-value {
    font-size: 12px;
    font-weight: 600;
    text-align: right;
}
.field-value.numeric {
    font-family: "Consolas", "SFMono-Regular", monospace;
}
.section-note,
.appendix-note {
    margin: 10px 0 0;
    font-size: 11px;
    color: #5a6871;
    line-height: 1.45;
}
.report-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12px;
}
.report-table th,
.report-table td {
    border: 1px solid #d8e1e7;
    padding: 8px 10px;
    vertical-align: top;
}
.report-table thead th {
    background: #f2f7fa;
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: #4f5c65;
}
.rank-cell {
    width: 86px;
    font-weight: 700;
}
.reason-cell { width: 32%; }
.summary-total {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    margin-top: 10px;
    padding-top: 10px;
    border-top: 1px solid #d8e1e7;
    font-size: 12px;
    font-weight: 700;
}
.summary-total .numeric { font-family: "Consolas", "SFMono-Regular", monospace; }
.appendix { padding-top: 6px; }
.appendix + .appendix { margin-top: 18px; }
.footer {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    display: flex;
    justify-content: space-between;
    gap: 12px;
    padding-top: 6px;
    border-top: 1px solid #d8e1e7;
    font-size: 10px;
    color: #6b7881;
    background: #ffffff;
}
@media print { body { margin: 0; } }
</style>
</head>
<body>
<div class="report">
    <section class="page">
        <header class="header">
            <div class="header-copy">
                <h1>Strategy Squad</h1>
                <p>Decision-first historical structure report</p>
            </div>
            <div class="header-meta">
                ${reportMetaItemV2("Strategy", inputs.strategyLabel)}
                ${reportMetaItemV2("Underlying", inputs.underlying)}
                ${reportMetaItemV2("Expiry type", inputs.expiryType)}
                ${reportMetaItemV2("Timeframe", inputs.timeframe)}
                ${reportMetaItemV2("Anchor date", observation.anchorDate || "-")}
                ${reportMetaItemV2("Matched instances", String(observation.observationCount || 0))}
            </div>
        </header>

        <section class="section-card" style="margin-bottom: 12px;">
            <h2>Decision Snapshot</h2>
            <div class="decision-strip">
                ${reportDecisionCardV2(toSentenceCaseV2(premium.currentPremiumLabel || "Current premium"), formatPointsV2(premium.currentPremiumPoints ?? inputs.currentEconomicPremium))}
                ${reportDecisionCardV2(toSentenceCaseV2(premium.averageEntryLabel || "Average entry"), formatPointsV2(premium.averageEntryPoints ?? 0))}
                ${reportDecisionCardV2("Percentile", formatPercentileDisplayV2(premium.economicPercentile, premium.percentileReliable))}
                ${reportDecisionCardV2("Expectancy", formatSignedPointsV2(pnl.expectancyPoints ?? 0))}
                ${reportDecisionCardV2("Win rate", formatPercent(expiry.selectedSideWinRatePct ?? 0, 1))}
                ${reportDecisionCardV2("Tail loss P10", formatSignedPointsV2(risk.tailLossP10Points ?? 0))}
                ${reportDecisionCardV2("Max risk", formatBoundValueV2(risk.currentTheoreticalMaxLossPoints))}
                ${reportDecisionCardV2("Confidence", confidenceLabel)}
            </div>
            <p class="verdict-line">${escapeHtml(verdictLine)}</p>
        </section>

        <div class="section-grid">
            ${summaryBlocks}
        </div>

        ${buildRecommendationSectionV2(recommendation, observation, premium, pnl, expiry)}
        ${buildStructureLegSectionV2(reportLegs, premium)}

        ${buildReportFooterV2("Decision summary", generatedAt)}
    </section>
    ${appendixSections}
</div>
<script>
document.title = "Strategy Squad Report - ${escapeJsV2(inputs.strategyLabel)}";
Array.from(document.querySelectorAll(".page")).forEach((page, index, pages) => {
  page.querySelectorAll(".page-index").forEach((node) => { node.textContent = String(index + 1); });
  page.querySelectorAll(".page-total").forEach((node) => { node.textContent = String(pages.length); });
});
</script>
</body>
</html>`;
}

function reportMetaItemV2(label, value) {
    return `
        <div class="meta-item">
            <span class="meta-label">${escapeHtml(label)}</span>
            <span class="meta-value">${escapeHtml(String(value ?? "-"))}</span>
        </div>
    `;
}

function reportDecisionCardV2(label, value) {
    return `
        <div class="decision-card">
            <span class="label">${escapeHtml(label)}</span>
            <span class="value">${escapeHtml(value)}</span>
        </div>
    `;
}

function buildReportFooterV2(sectionLabel, generatedAt) {
    const centerText = generatedAt
        ? `${sectionLabel} | Generated ${generatedAt}`
        : sectionLabel;
    return `
        <div class="footer">
            <span>Strategy Squad</span>
            <span>${escapeHtml(centerText)}</span>
            <span>Page <span class="page-index"></span> of <span class="page-total"></span></span>
        </div>
    `;
}

function reportFieldSectionV2(title, rows) {
    return `
        <section class="section-card">
            <h2>${escapeHtml(title)}</h2>
            <div class="field-grid">
                ${rows.map(([label, value]) => reportFieldRowV2(label, value)).join("")}
            </div>
        </section>
    `;
}

function reportFieldRowV2(label, value) {
    const numericClass = /pts|%|Unlimited|\d/.test(String(value ?? "")) ? " numeric" : "";
    return `
        <div class="field-row">
            <span class="field-label">${escapeHtml(label)}</span>
            <span class="field-value${numericClass}">${escapeHtml(String(value ?? "-"))}</span>
        </div>
    `;
}

function buildRecommendationSectionV2(recommendation, observation, premium = {}, pnl = {}, expiry = {}) {
    const candidateRows = prepareRecommendationRowsV2(recommendation, premium, pnl, expiry);
    const validCandidates = [];
    const seen = new Set();
    for (const [slot, item] of candidateRows) {
        if (!isRecommendationCandidateRenderableV2(item)) {
            continue;
        }
        const dedupeKey = `${item.mode || ""}|${item.orientation || ""}`;
        if (seen.has(dedupeKey)) {
            continue;
        }
        seen.add(dedupeKey);
        validCandidates.push([slot, item]);
    }
    if (!validCandidates.length) {
        return "";
    }

    const omittedCount = candidateRows.length - validCandidates.length;
    const lowSampleNote = Number(observation.observationCount || 0) < 30
        ? `Sample size limited (${observation.observationCount || 0} observations).`
        : "";

    const sectionNote = uniqueNonEmptyV2([
        sanitizeRecommendationNoteV2(recommendation.contextNote || ""),
        lowSampleNote,
        omittedCount > 0
            ? `${omittedCount} ranking slot(s) omitted because candidate metrics were incomplete or duplicated.`
            : ""
    ]).join(" ");

    return `
        <section class="section-card" style="margin-bottom: 12px;">
            <h2>Recommendation Ranking</h2>
            <table class="report-table">
                <thead>
                    <tr>
                        <th>Rank</th>
                        <th>Candidate</th>
                        <th>Orientation</th>
                        <th class="numeric">Economic percentile</th>
                        <th class="numeric">Avg P&amp;L</th>
                        <th class="numeric">Win rate</th>
                        <th class="numeric">Downside</th>
                        <th class="numeric">Score</th>
                        <th>Reason</th>
                    </tr>
                </thead>
                <tbody>
                    ${validCandidates.map(([slot, item]) => `
                        <tr>
                            <td class="rank-cell">${escapeHtml(slot)}</td>
                            <td>${escapeHtml(item.title || strategyLabel(item.mode || ""))}</td>
                            <td>${escapeHtml(titleCase(item.orientation || ""))}</td>
                            <td class="numeric">${escapeHtml(formatRecommendationPercentileV2(item.economicPercentile))}</td>
                            <td class="numeric">${escapeHtml(formatSignedPointsV2(item.averagePnlPoints || 0))}</td>
                            <td class="numeric">${escapeHtml(formatPercent(item.winRatePct || 0, 1))}</td>
                            <td class="numeric">${escapeHtml(formatPointsV2(Math.abs(item.downsideSeverityPoints || 0)))}</td>
                            <td class="numeric">${escapeHtml(Number.isFinite(Number(item.score)) ? formatNumber(item.score, 1) : "-")}</td>
                            <td class="reason-cell">${escapeHtml(item.reportReason || buildRecommendationReasonV2(item, slot, premium))}</td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
            ${sectionNote ? `<p class="section-note">${escapeHtml(sectionNote)}</p>` : ""}
        </section>
    `;
}

function buildStructureLegSectionV2(legs, premium) {
    const totalLabel = toSentenceCaseV2(premium.currentPremiumLabel || "Total net credit");
    const totalValue = `${formatPointsV2(premium.currentPremiumPoints ?? 0)} net`;
    return `
        <section class="section-card">
            <h2>Structure Legs</h2>
            <table class="report-table">
                <thead>
                    <tr>
                        <th>Label</th>
                        <th>Type</th>
                        <th>Side</th>
                        <th class="numeric">Strike</th>
                        <th class="numeric">Entry</th>
                        <th class="numeric">Orig Qty</th>
                        <th class="numeric">Open Qty</th>
                        <th class="numeric">Booked PnL</th>
                        <th class="numeric">Live PnL</th>
                        <th class="numeric">Total PnL</th>
                        <th>Status</th>
                        <th class="numeric">Leg premium (pts x qty)</th>
                    </tr>
                </thead>
                <tbody>
                    ${legs.map((leg) => {
                        return `
                            <tr>
                                <td>${escapeHtml(leg.label)}</td>
                                <td>${escapeHtml(leg.optionType)}</td>
                                <td>${escapeHtml(titleCase(leg.side))}</td>
                                <td class="numeric">${escapeHtml(leg.strikeDisplay)}</td>
                                <td class="numeric">${escapeHtml(leg.entryDisplay)}</td>
                                <td class="numeric">${escapeHtml(leg.originalQuantityDisplay)}</td>
                                <td class="numeric">${escapeHtml(leg.openQuantityDisplay)}</td>
                                <td class="numeric">${escapeHtml(leg.bookedPnlDisplay)}</td>
                                <td class="numeric">${escapeHtml(leg.livePnlDisplay)}</td>
                                <td class="numeric">${escapeHtml(leg.totalPnlDisplay)}</td>
                                <td>${escapeHtml(leg.statusDisplay)}</td>
                                <td class="numeric">${escapeHtml(leg.totalLegPremiumDisplay)}</td>
                            </tr>
                        `;
                    }).join("")}
                </tbody>
            </table>
            <div class="summary-total">
                <span>${escapeHtml(totalLabel)}</span>
                <span class="numeric">${escapeHtml(totalValue)}</span>
            </div>
            <p class="section-note">Qty is stored in contract units. Leg premium shows the signed rupee exposure from entry points multiplied by Qty.</p>
        </section>
    `;
}

function buildTrendAppendixV2(trendSummary) {
    if (!trendSummary.hasContent) {
        return "";
    }

    const detail = trendSummary.collapsed
        ? `
            <table class="report-table">
                <thead>
                    <tr>
                        <th>Window group</th>
                        <th class="numeric">Average</th>
                        <th class="numeric">Median</th>
                        <th class="numeric">Economic percentile</th>
                        <th class="numeric">Vs current</th>
                        <th class="numeric">Observations</th>
                        <th>Notes</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td>${escapeHtml(trendSummary.windowLabel)}</td>
                        <td class="numeric">${escapeHtml(formatPointsV2(trendSummary.reference.averagePremiumPoints))}</td>
                        <td class="numeric">${escapeHtml(formatPointsV2(trendSummary.reference.medianPremiumPoints))}</td>
                        <td class="numeric">${escapeHtml(formatPercentileDisplayV2(trendSummary.reference.economicPercentile, trendSummary.reference.percentileReliable))}</td>
                        <td class="numeric">${escapeHtml(formatSignedPointsV2(trendSummary.reference.currentVsAveragePoints))}</td>
                        <td class="numeric">${escapeHtml(String(trendSummary.reference.observationCount || 0))}</td>
                        <td>Merged: windows share identical effective sample and premium statistics.</td>
                    </tr>
                </tbody>
            </table>
        `
        : `
            <table class="report-table">
                <thead>
                    <tr>
                        <th>Window</th>
                        <th class="numeric">Average</th>
                        <th class="numeric">Median</th>
                        <th class="numeric">Economic percentile</th>
                        <th class="numeric">Raw percentile</th>
                        <th class="numeric">Vs current</th>
                        <th class="numeric">Observations</th>
                        <th>Notes</th>
                    </tr>
                </thead>
                <tbody>
                    ${trendSummary.windows.map((item) => `
                        <tr>
                            <td>${escapeHtml(item.label)}</td>
                            <td class="numeric">${escapeHtml(formatPointsV2(item.averagePremiumPoints || 0))}</td>
                            <td class="numeric">${escapeHtml(formatPointsV2(item.medianPremiumPoints || 0))}</td>
                            <td class="numeric">${escapeHtml(formatPercentileDisplayV2(item.economicPercentile, item.percentileReliable))}</td>
                            <td class="numeric">${escapeHtml(formatPercentileDisplayV2(item.rawPricePercentile, item.percentileReliable))}</td>
                            <td class="numeric">${escapeHtml(formatSignedPointsV2(item.currentVsAveragePoints || 0))}</td>
                            <td class="numeric">${escapeHtml(String(item.observationCount || 0))}</td>
                            <td>${escapeHtml(item.duplicateNote || "Distinct sample profile")}</td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
        `;

    return `
        <section class="page appendix">
            <section class="section-card">
                <h2>Appendix - Premium Trend</h2>
                <p class="appendix-note">${escapeHtml(trendSummary.summaryLine)}</p>
                ${detail}
            </section>
            ${buildReportFooterV2("Appendix - Premium trend")}
        </section>
    `;
}

function buildHistoricalCasesAppendixV2(cases) {
    if (!cases.length) {
        return "";
    }
    return `
        <section class="page appendix">
            <section class="section-card">
                <h2>Appendix - Historical Cases</h2>
                <table class="report-table">
                    <thead>
                        <tr>
                            <th>Trade date</th>
                            <th>Expiry date</th>
                            <th class="numeric">Entry</th>
                            <th class="numeric">Expiry value</th>
                            <th class="numeric">Selected P&amp;L</th>
                            <th class="numeric">Buyer P&amp;L</th>
                            <th class="numeric">Seller P&amp;L</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${cases.map((item) => `
                            <tr>
                                <td>${escapeHtml(item.tradeDate || "-")}</td>
                                <td>${escapeHtml(item.expiryDate || "-")}</td>
                                <td class="numeric">${escapeHtml(formatPointsV2(item.entryPremiumPoints || 0))}</td>
                                <td class="numeric">${escapeHtml(formatPointsV2(item.expiryValuePoints || 0))}</td>
                                <td class="numeric">${escapeHtml(formatSignedPointsV2(item.selectedSidePnlPoints || 0))}</td>
                                <td class="numeric">${escapeHtml(formatSignedPointsV2(item.buyerPnlPoints || 0))}</td>
                                <td class="numeric">${escapeHtml(formatSignedPointsV2(item.sellerPnlPoints || 0))}</td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </section>
            ${buildReportFooterV2("Appendix - Historical cases")}
        </section>
    `;
}

function deriveConfidenceLabelV2(observation) {
    const count = Number(observation.observationCount || 0);
    if (count >= 50) {
        return "Adequate";
    }
    if (count < 30) {
        return "Low confidence";
    }
    return "Limited";
}

function buildDecisionVerdictLineV2(observation, premium, insight) {
    const premiumLine = toSentenceCaseV2(
        premium.attractivenessLabel
        || premium.priceConditionLabel
        || insight.premiumVerdict
        || "Historical premium read is neutral"
    );
    const count = Number(observation.observationCount || 0);
    if (count < 30) {
        return `${premiumLine}, but confidence is low because only ${count} matched observations qualified.`;
    }
    if (count >= 50) {
        return `${premiumLine}, with adequate historical support from ${count} matched observations.`;
    }
    return `${premiumLine}, with moderate historical support from ${count} matched observations.`;
}

function summarizeTrendWindowsV2(windows) {
    if (!windows.length) {
        return {
            hasContent: false,
            collapsed: false,
            windows: [],
            summaryLine: "No timeframe trend data available."
        };
    }

    const decorated = windows.map((item) => ({
        ...item,
        signature: stableWindowSignatureV2(item),
        duplicateNote: ""
    }));
    const groups = new Map();
    decorated.forEach((item) => {
        const existing = groups.get(item.signature) || [];
        existing.push(item.label);
        groups.set(item.signature, existing);
    });
    const materiallyIdentical = groups.size === 1;

    if (materiallyIdentical) {
        return {
            hasContent: true,
            collapsed: true,
            reference: windows[0],
            windowLabel: `${windows[0].label} to ${windows[windows.length - 1].label}`,
            windows,
            summaryLine: "Timeframe windows are materially identical, so the trend is collapsed into one line because the same matched observation set is driving each window."
        };
    }

    const hasDuplicateGroups = Array.from(groups.values()).some((labels) => labels.length > 1);
    const annotatedWindows = decorated.map((item) => {
        const labels = groups.get(item.signature) || [];
        if (labels.length <= 1) {
            return item;
        }
        return {
            ...item,
            duplicateNote: `Same effective sample as ${labels.filter((label) => label !== item.label).join(", ")}`
        };
    });

    return {
        hasContent: true,
        collapsed: false,
        windows: annotatedWindows,
        summaryLine: hasDuplicateGroups
            ? "Timeframe windows show multiple regimes; rows annotated where windows share the same effective sample set."
            : "Timeframe windows show differentiated premium regimes. Detailed window comparison appears in the appendix."
    };
}

function summarizeHistoricalCasesV2(cases, pnl, risk) {
    const selectedPnls = cases
        .map((item) => Number(item.selectedSidePnlPoints))
        .filter((value) => Number.isFinite(value))
        .sort((left, right) => left - right);
    const expiryValues = cases
        .map((item) => Number(item.expiryValuePoints))
        .filter((value) => Number.isFinite(value))
        .sort((left, right) => left - right);

    const medianObservedPnl = selectedPnls.length
        ? (selectedPnls.length % 2 === 0
            ? (selectedPnls[(selectedPnls.length / 2) - 1] + selectedPnls[selectedPnls.length / 2]) / 2
            : selectedPnls[Math.floor(selectedPnls.length / 2)])
        : Number(pnl.medianPnlPoints || 0);
    const medianExpiryPayoff = expiryValues.length
        ? (expiryValues.length % 2 === 0
            ? (expiryValues[(expiryValues.length / 2) - 1] + expiryValues[expiryValues.length / 2]) / 2
            : expiryValues[Math.floor(expiryValues.length / 2)])
        : 0;

    return {
        observationCount: cases.length,
        validExpiryObservations: cases.length,
        bestObservedPnl: selectedPnls.length ? selectedPnls[selectedPnls.length - 1] : Number(risk.historicalBestPnlPoints || 0),
        medianObservedPnl,
        medianExpiryPayoff,
        worstObservedPnl: selectedPnls.length ? selectedPnls[0] : Number(risk.historicalWorstPnlPoints || 0)
    };
}

function derivePremiumOrientationBiasV2(premium) {
    const percentile = Number(premium.economicPercentile);
    const lower = [
        premium.attractivenessLabel,
        premium.priceConditionLabel,
        premium.currentPremiumLabel
    ].map((value) => String(value || "").toLowerCase()).join(" ");

    if (lower.includes("seller")) {
        return {
            favoredOrientation: "SELLER",
            summary: "Premium favorable for seller"
        };
    }
    if (lower.includes("buyer")) {
        return {
            favoredOrientation: "BUYER",
            summary: "Premium favorable for buyer"
        };
    }
    if (Number.isFinite(percentile) && percentile >= 60) {
        return {
            favoredOrientation: "SELLER",
            summary: "Rich premium regime favors seller structures"
        };
    }
    if (Number.isFinite(percentile) && percentile <= 40) {
        return {
            favoredOrientation: "BUYER",
            summary: "Low premium regime favors buyer structures"
        };
    }
    return {
        favoredOrientation: "",
        summary: "Premium context is broadly neutral"
    };
}

function recommendationDisplayScoreV2(item, premiumBias) {
    const baseScore = Number.isFinite(Number(item.score)) ? Number(item.score) : 0;
    const averagePnl = Number(item.averagePnlPoints || 0);
    const winRate = Number(item.winRatePct || 0);
    const downside = Number(item.downsideSeverityPoints || 0);
    const orientation = String(item.orientation || "").toUpperCase();
    const alignsWithPremium = premiumBias.favoredOrientation && orientation === premiumBias.favoredOrientation;
    const historicalOverride = averagePnl >= 10 && winRate >= 54;
    let score = baseScore + (averagePnl * 0.18) + ((winRate - 50) * 0.6) - (Math.abs(Math.min(downside, 0)) * 0.05);
    if (premiumBias.favoredOrientation) {
        score += alignsWithPremium ? 8 : -8;
    }
    if (!alignsWithPremium && historicalOverride) {
        score += 10;
    }
    if (item.lowSampleWarning) {
        score -= 4;
    }
    return score;
}

function buildRecommendationReasonV2(item, slot, premium = {}) {
    const premiumBias = derivePremiumOrientationBiasV2(premium);
    const orientation = String(item.orientation || "").toUpperCase();
    const averagePnl = Number(item.averagePnlPoints || 0);
    const winRate = Number(item.winRatePct || 0);
    const alignsWithPremium = premiumBias.favoredOrientation && orientation === premiumBias.favoredOrientation;
    const historicalOverride = averagePnl >= 10 && winRate >= 54;

    if (slot === "Preferred") {
        if (!alignsWithPremium && historicalOverride && premiumBias.favoredOrientation) {
            return `Despite ${premiumBias.summary.toLowerCase()}, historical realized outcomes favor the ${orientation === "SELLER" ? "seller" : "buyer"} side.`;
        }
        if (alignsWithPremium) {
            return `Preferred due to ${premiumBias.summary.toLowerCase()} and strongest historical outcome profile.`;
        }
        return "Preferred due to strongest historical outcome profile.";
    }

    if (slot === "Alternative") {
        if (averagePnl >= 0 && winRate >= 50) {
            return "Alternative with similar payoff but weaker consistency.";
        }
        return "Alternative with mixed evidence and lower conviction.";
    }

    if (averagePnl < 0) {
        return "Avoid due to negative average P&L.";
    }
    if (winRate < 50) {
        return "Avoid due to weak consistency across matched cases.";
    }
    return "Avoid due to weaker downside-adjusted profile.";
}

function prepareRecommendationRowsV2(recommendation, premium, pnl, expiry) {
    const premiumBias = derivePremiumOrientationBiasV2(premium);
    const candidates = [
        recommendation.preferred,
        recommendation.alternative,
        recommendation.avoid
    ].filter((item) => isRecommendationCandidateRenderableV2(item))
        .map((item) => {
            const reportScore = recommendationDisplayScoreV2(item, premiumBias);
            return {
                ...item,
                reportScore,
                reportReason: buildRecommendationReasonV2(
                    {
                        ...item,
                        score: reportScore,
                        averagePnlPoints: Number.isFinite(Number(item.averagePnlPoints)) ? Number(item.averagePnlPoints) : Number(pnl.averagePnlPoints || 0),
                        winRatePct: Number.isFinite(Number(item.winRatePct)) ? Number(item.winRatePct) : Number(expiry.selectedSideWinRatePct || 0)
                    },
                    "Preferred",
                    premium
                )
            };
        })
        .sort((left, right) => right.reportScore - left.reportScore);

    return candidates.slice(0, 3).map((item, index) => {
        const slot = index === 0 ? "Preferred" : index === 1 ? "Alternative" : "Avoid";
        return [slot, {
            ...item,
            reportReason: buildRecommendationReasonV2(item, slot, premium)
        }];
    });
}

function sanitizeRecommendationNoteV2(value) {
    return String(value || "")
        .replace(/[￾�﻿]/g, "")
        .replace(/candidate\s*=\s*[^|.]*[|.]?/gi, "")
        .replace(/composite profile[^.]*\.?/gi, "")
        .replace(/diagnostic[^.]*\.?/gi, "")
        .replace(/limited sample[^.]*\.?/gi, "")
        .replace(/low-confidence[^.]*\.?/gi, "")
        .replace(/\s+/g, " ")
        .trim();
}

function formatPercentileDisplayV2(value, reliable) {
    if (!reliable) {
        return "Not reliable";
    }
    const percentile = Number(value || 0);
    if (percentile <= 0) {
        return "0% (lowest percentile)";
    }
    return formatOrdinalV2(percentile);
}

function formatRecommendationPercentileV2(value) {
    const percentile = Number(value || 0);
    if (percentile <= 0) {
        return "0% (lowest percentile)";
    }
    return formatOrdinalV2(percentile);
}

function formatOrdinalV2(value) {
    const number = Math.max(0, Number(value || 0));
    const mod10 = number % 10;
    const mod100 = number % 100;
    if (mod10 === 1 && mod100 !== 11) {
        return `${number}st`;
    }
    if (mod10 === 2 && mod100 !== 12) {
        return `${number}nd`;
    }
    if (mod10 === 3 && mod100 !== 13) {
        return `${number}rd`;
    }
    return `${number}th`;
}

function formatPointsV2(value) {
    return `${formatNumber(value || 0, 2)} pts`;
}

function formatSignedPointsV2(value) {
    return `${formatSigned(value || 0, 2)} pts`;
}

function formatBoundValueV2(value) {
    return value == null ? "Unlimited" : formatPointsV2(value);
}

function toSentenceCaseV2(value) {
    const text = String(value || "").trim();
    return text ? text.charAt(0).toUpperCase() + text.slice(1) : "";
}

function formatReportTimestampV2(value) {
    return new Intl.DateTimeFormat("en-IN", {
        dateStyle: "medium",
        timeStyle: "short"
    }).format(value);
}

function uniqueNonEmptyV2(values) {
    return [...new Set(values.map((value) => String(value || "").trim()).filter(Boolean))];
}

function isRecommendationCandidateRenderableV2(item) {
    if (!item) {
        return false;
    }
    const observationCount = Number(item.observationCount || 0);
    const economicPercentile = Number(item.economicPercentile || 0);
    const averagePnl = Number(item.averagePnlPoints);
    const winRate = Number(item.winRatePct);
    const downside = Number(item.downsideSeverityPoints);
    const score = Number(item.score);
    return observationCount > 0
        && economicPercentile > 0
        && Number.isFinite(averagePnl)
        && Math.abs(averagePnl) > 0.0001
        && Number.isFinite(winRate)
        && winRate > 0
        && winRate <= 100
        && Number.isFinite(downside)
        && downside > 0
        && Number.isFinite(score);
}

function validateReportPreflightV2(reportPayload) {
    const errors = [];
    const warnings = [];
    if (!reportPayload || !reportPayload.payload) {
        return {
            errors: ["Historical payload is missing."],
            warnings
        };
    }
    const recommendation = reportPayload.payload.recommendation || {};
    if (!isRecommendationCandidateRenderableV2(recommendation.preferred)) {
        errors.push("Preferred recommendation is missing or has incomplete metrics.");
    }
    if (!Array.isArray(reportPayload.inputs?.legs) || reportPayload.inputs.legs.length === 0) {
        errors.push("At least one structure leg is required to export the report.");
    }

    const windows = Array.isArray(reportPayload.payload.timeframeTrend?.windows)
        ? reportPayload.payload.timeframeTrend.windows
        : [];
    if (windows.length > 1) {
        const uniqueSignatures = new Set(windows.map((item) => stableWindowSignatureV2(item)));
        if (uniqueSignatures.size < windows.length) {
            warnings.push("Some timeframe windows share identical effective samples and are annotated in the appendix.");
        }
    }

    return { errors, warnings };
}

function stableWindowSignatureV2(item) {
    const average = Number(item?.averagePremiumPoints || 0).toFixed(2);
    const median = Number(item?.medianPremiumPoints || 0).toFixed(2);
    const currentVsAverage = Number(item?.currentVsAveragePoints || 0).toFixed(2);
    const observationCount = Number(item?.observationCount || 0);
    const economicPercentile = Number(item?.economicPercentile || 0);
    const rawPricePercentile = Number(item?.rawPricePercentile || 0);
    return [average, median, currentVsAverage, observationCount, economicPercentile, rawPricePercentile].join("|");
}

function buildReportLegRowsV2(inputLegs) {
    const summary = latestPublishedStrategyState?.summary || calculateLiveStructureSummary();
    const liveLegs = Array.isArray(latestLiveStructure?.legs) ? latestLiveStructure.legs : [];
    const rows = Array.isArray(summary?.rows) ? summary.rows : [];
    return inputLegs.map((leg, index) => {
        const summaryRow = rows[index];
        const liveLeg = liveLegs[index] || {};
        const side = String(leg.side || summaryRow?.leg?.side || "").toUpperCase();
        const quantity = Number(summaryRow?.quantity || leg.quantity || liveLeg.quantity || liveLeg.lotSize || summary?.baseLotSize || currentScenarioLotSize());
        const originalQuantity = Number(summaryRow?.originalQuantity || leg.originalQuantity || quantity);
        const strike = resolveReportStrikeV2(leg, summaryRow?.leg, liveLeg);
        const entry = Number(summaryRow?.entryPrice ?? leg.lockedEntryPrice ?? leg.entryPrice ?? 0);
        const signedLegPremium = (side === "SHORT" ? entry : -entry) * quantity;
        return {
            label: sanitizeInlineTextV2(leg.label || summaryRow?.leg?.label || `Leg ${index + 1}`),
            optionType: sanitizeInlineTextV2(leg.optionType || summaryRow?.leg?.optionType || inferOptionTypeFromInstrumentV2(liveLeg.instrumentId) || "N/A"),
            side: side || "N/A",
            strikeDisplay: strike > 0 ? formatNumber(strike, 2) : "N/A",
            entryDisplay: formatPointsV2(entry),
            originalQuantityDisplay: formatNumber(originalQuantity, 0),
            openQuantityDisplay: formatNumber(quantity, 0),
            bookedPnlDisplay: summaryRow?.bookedPnlDisplay || formatResolvedRupees(leg.bookedPnl || 0),
            livePnlDisplay: summaryRow?.livePnlDisplay || "-",
            totalPnlDisplay: summaryRow?.totalPnlDisplay || formatResolvedRupees((leg.bookedPnl || 0)),
            statusDisplay: summaryRow?.statusLabel || titleCase(deriveLegStatusValue(leg).replace(/_/g, " ")),
            totalLegPremiumDisplay: `${formatSigned(entry, 2)} pts x ${formatNumber(quantity, 0)} = ${formatSigned(signedLegPremium, 0)} ₹`
        };
    });
}

function resolveReportStrikeV2(...sources) {
    for (const source of sources) {
        const strike = Number(source?.strike);
        if (Number.isFinite(strike) && strike > 0) {
            return strike;
        }
        const fromInstrument = extractStrikeFromInstrumentIdV2(source?.instrumentId);
        if (Number.isFinite(fromInstrument) && fromInstrument > 0) {
            return fromInstrument;
        }
    }
    return 0;
}

function inferOptionTypeFromInstrumentV2(instrumentId) {
    const text = String(instrumentId || "").toUpperCase();
    if (text.endsWith("_CE")) {
        return "CE";
    }
    if (text.endsWith("_PE")) {
        return "PE";
    }
    return "";
}

function extractStrikeFromInstrumentIdV2(instrumentId) {
    const text = String(instrumentId || "");
    const match = text.match(/_(\d+(?:\.\d+)?)_(?:CE|PE)$/i);
    return match ? Number(match[1]) : NaN;
}

function sanitizeInlineTextV2(value) {
    return String(value || "")
        .replace(/[￾�﻿]/g, "")
        .replace(/\s+/g, " ")
        .trim();
}

function escapeJsV2(value) {
    return String(value ?? "")
        .replace(/\\/g, "\\\\")
        .replace(/`/g, "\\`")
        .replace(/\$\{/g, "\\${");
}

function reportMetricTable(title, rows) {
    return `
        <section class="section">
            <h2>${escapeHtml(title)}</h2>
            <table>
                <tbody>
                    ${rows.map(([label, value]) => `<tr><th>${escapeHtml(label)}</th><td>${escapeHtml(String(value ?? ""))}</td></tr>`).join("")}
                </tbody>
            </table>
        </section>
    `;
}

function reportRecommendationSection(recommendation) {
    const candidates = [
        ["Preferred", recommendation.preferred],
        ["Alternative", recommendation.alternative],
        ["Avoid", recommendation.avoid]
    ].filter(([, item]) => item);
    if (!candidates.length) {
        return "";
    }
    return `
        <section class="section">
            <h2>Recommendation</h2>
            <table>
                <thead><tr><th>Slot</th><th>Candidate</th><th>Detail</th></tr></thead>
                <tbody>
                    ${candidates.map(([slot, item]) => `
                        <tr>
                            <th>${escapeHtml(slot)}</th>
                            <td>${escapeHtml(item.title || "-")}<br>${escapeHtml(item.verdict || "")}</td>
                            <td>${escapeHtml([
                                `${item.observationCount || 0} observations`,
                                `economic ${item.economicPercentile || 0}th`,
                                `avg pnl ${formatSigned(item.averagePnlPoints || 0, 2)} pts`,
                                `win rate ${formatPercent(item.winRatePct || 0, 1)}`,
                                item.lowSampleWarning || "",
                                item.reason || ""
                            ].filter(Boolean).join(" | "))}</td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
            ${recommendation.contextNote ? `<p>${escapeHtml(recommendation.contextNote)}</p>` : ""}
        </section>
    `;
}

function reportTrendSection(windows) {
    if (!windows.length) {
        return "";
    }
    return `
        <section class="section">
            <h2>Premium Trend</h2>
            <table>
                <thead><tr><th>Window</th><th>Average</th><th>Median</th><th>Raw percentile</th><th>Economic percentile</th><th>Vs current</th><th>Observations</th></tr></thead>
                <tbody>
                    ${windows.map((item) => `
                        <tr>
                            <th>${escapeHtml(item.label)}</th>
                            <td>${escapeHtml(formatNumber(item.averagePremiumPoints || 0, 2))} pts</td>
                            <td>${escapeHtml(formatNumber(item.medianPremiumPoints || 0, 2))} pts</td>
                            <td>${escapeHtml(String(item.rawPricePercentile || 0))}</td>
                            <td>${escapeHtml(String(item.economicPercentile || 0))}</td>
                            <td>${escapeHtml(formatSigned(item.currentVsAveragePoints || 0, 2))} pts</td>
                            <td>${escapeHtml(String(item.observationCount || 0))}</td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
        </section>
    `;
}

function reportLegSection(legs) {
    return `
        <section class="section">
            <h2>Structure Legs</h2>
            <table>
                <thead><tr><th>Label</th><th>Type</th><th>Side</th><th>Strike</th><th>Entry</th></tr></thead>
                <tbody>
                    ${legs.map((leg) => `
                        <tr>
                            <th>${escapeHtml(leg.label)}</th>
                            <td>${escapeHtml(leg.optionType)}</td>
                            <td>${escapeHtml(leg.side)}</td>
                            <td>${escapeHtml(formatNumber(leg.strike, 2))}</td>
                            <td>${escapeHtml(formatNumber(leg.entryPrice, 2))}</td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
        </section>
    `;
}

function reportHistoricalCases(cases) {
    if (!cases.length) {
        return "";
    }
    return `
        <section class="section">
            <h2>Closest Historical Cases</h2>
            <table>
                <thead><tr><th>Trade date</th><th>Expiry date</th><th>Entry</th><th>Expiry value</th><th>Selected pnl</th><th>Buyer pnl</th><th>Seller pnl</th></tr></thead>
                <tbody>
                    ${cases.map((item) => `
                        <tr>
                            <th>${escapeHtml(item.tradeDate)}</th>
                            <td>${escapeHtml(item.expiryDate)}</td>
                            <td>${escapeHtml(formatNumber(item.entryPremiumPoints || 0, 2))} pts</td>
                            <td>${escapeHtml(formatNumber(item.expiryValuePoints || 0, 2))} pts</td>
                            <td>${escapeHtml(formatSigned(item.selectedSidePnlPoints || 0, 2))} pts</td>
                            <td>${escapeHtml(formatSigned(item.buyerPnlPoints || 0, 2))} pts</td>
                            <td>${escapeHtml(formatSigned(item.sellerPnlPoints || 0, 2))} pts</td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
        </section>
    `;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

renderHeader();
