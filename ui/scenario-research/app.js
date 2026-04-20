const form = document.getElementById("scenario-form");

const els = {
    strategyMode: document.getElementById("strategy-mode"),
    underlying: document.getElementById("underlying"),
    expiryType: document.getElementById("expiry-type"),
    dte: document.getElementById("dte"),
    spot: document.getElementById("spot"),
    timeframe: document.getElementById("timeframe"),
    customFromField: document.getElementById("custom-from-field"),
    customToField: document.getElementById("custom-to-field"),
    customFrom: document.getElementById("custom-from"),
    customTo: document.getElementById("custom-to"),
    addLegButton: document.getElementById("add-leg-button"),
    legsContainer: document.getElementById("legs-container"),
    runAnalysisButton: document.getElementById("run-analysis-button"),
    downloadReportButton: document.getElementById("download-report-button"),
    statusLine: document.getElementById("status-line"),
    liveStatusChip: document.getElementById("live-status-chip"),
    liveSpotChip: document.getElementById("live-spot-chip"),
    livePremiumChip: document.getElementById("live-premium-chip"),
    liveLastTickChip: document.getElementById("live-last-tick-chip"),
    strategyChip: document.getElementById("strategy-chip"),
    orientationChip: document.getElementById("orientation-chip"),
    timeframeChip: document.getElementById("timeframe-chip"),
    premiumChip: document.getElementById("premium-chip"),
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
    expiryTailLossRaw: document.getElementById("expiry-tail-loss-raw")
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

let legsState = [];
let strategyAbortController = null;
let liveAbortController = null;
let latestReportPayload = null;
let liveSyncTimer = null;
let liveHydrationMuted = false;

boot();

form.addEventListener("input", handleFormInput);
form.addEventListener("change", handleFormChange);
els.addLegButton.addEventListener("click", addCustomLeg);
els.runAnalysisButton.addEventListener("click", runAnalysis);
els.downloadReportButton.addEventListener("click", downloadReport);

function apiBase() {
    return window.location.protocol === "file:" ? "http://localhost:8080" : "";
}

async function boot() {
    const authenticated = await ensureAuthenticated();
    if (!authenticated) {
        return;
    }
    populateStrategyOptions();
    initializeStrategy();
    startLiveSync();
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
    resetLegsForStrategy();
    syncTimeframeFields();
    renderLegs();
    renderHeader();
    setPlaceholderResults();
    resetLiveStrip();
}

function handleFormInput(event) {
    if (liveHydrationMuted) {
        return;
    }
    if (event.target.matches("[data-leg-index][data-field='strike']")) {
        syncDistanceFromStrike(Number(event.target.dataset.legIndex));
    }
    if (event.target.matches("[data-leg-index][data-field='distance']")) {
        syncStrikeFromDistance(Number(event.target.dataset.legIndex));
    }
    if (event.target.matches("[data-leg-index][data-field='entryPrice'], [data-leg-index][data-field='optionType'], [data-leg-index][data-field='side']")) {
        syncLegStateFromDom(Number(event.target.dataset.legIndex));
    }
    renderHeader();
    refreshLiveOverlay();
}

function handleFormChange(event) {
    if (liveHydrationMuted) {
        return;
    }
    if (event.target === els.strategyMode) {
        resetLegsForStrategy();
        renderLegs();
    }
    if (event.target === els.underlying || event.target === els.spot) {
        resetLegsForStrategy();
        renderLegs();
    }
    syncTimeframeFields();
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
    editableSide = false
}) {
    return {
        label,
        optionType,
        side,
        strike,
        distance: 0,
        entryPrice,
        editableType,
        editableSide
    };
}

function roundToBucket(value, bucketSize) {
    return Math.round(value / bucketSize) * bucketSize;
}

function bucketSize() {
    return pointBucketSize[els.underlying.value] || 50;
}

function resetLegsForStrategy() {
    const strategy = strategyConfigs[els.strategyMode.value];
    const spot = numberValue(els.spot);
    legsState = strategy.createLegs(spot, bucketSize()).map((leg) => ({
        ...leg,
        distance: leg.strike - spot
    }));
    els.addLegButton.hidden = !strategy.custom;
}

function addCustomLeg() {
    if (els.strategyMode.value !== "CUSTOM_MULTI_LEG") {
        return;
    }
    const spot = numberValue(els.spot);
    const index = legsState.length + 1;
    legsState.push(createLegState({
        label: `Custom leg ${index}`,
        optionType: "CE",
        side: "LONG",
        strike: roundToBucket(spot, bucketSize()),
        entryPrice: 40,
        editableType: true,
        editableSide: true
    }));
    legsState[legsState.length - 1].distance = legsState[legsState.length - 1].strike - spot;
    renderLegs();
    renderHeader();
}

function removeCustomLeg(index) {
    if (els.strategyMode.value !== "CUSTOM_MULTI_LEG" || legsState.length <= 1) {
        return;
    }
    legsState.splice(index, 1);
    renderLegs();
    renderHeader();
}

function renderLegs() {
    const spot = numberValue(els.spot);
    els.legsContainer.innerHTML = legsState.map((leg, index) => `
        <article class="leg-card">
            <div class="leg-card-header">
                <div>
                    <div class="leg-title">${leg.label}</div>
                    <div class="leg-meta">${leg.optionType} / ${leg.side}</div>
                </div>
                ${els.strategyMode.value === "CUSTOM_MULTI_LEG" ? `<button class="remove-leg-button" data-remove-leg="${index}" type="button">Remove</button>` : ""}
            </div>
            <div class="leg-grid">
                <label class="field">
                    <span>Option type</span>
                    <select data-leg-index="${index}" data-field="optionType" ${leg.editableType ? "" : "disabled"}>
                        <option value="CE" ${leg.optionType === "CE" ? "selected" : ""}>Call</option>
                        <option value="PE" ${leg.optionType === "PE" ? "selected" : ""}>Put</option>
                    </select>
                </label>
                <label class="field">
                    <span>Side</span>
                    <select data-leg-index="${index}" data-field="side" ${leg.editableSide ? "" : "disabled"}>
                        <option value="LONG" ${leg.side === "LONG" ? "selected" : ""}>Long</option>
                        <option value="SHORT" ${leg.side === "SHORT" ? "selected" : ""}>Short</option>
                    </select>
                </label>
                <label class="field">
                    <span>Strike</span>
                    <input data-leg-index="${index}" data-field="strike" type="number" min="0" step="0.05" value="${stripTrailingZeros(leg.strike)}">
                </label>
                <label class="field">
                    <span>Distance</span>
                    <div class="compound-input">
                        <input data-leg-index="${index}" data-field="distance" type="number" step="0.05" value="${stripTrailingZeros(leg.strike - spot)}">
                        <span class="suffix">pts</span>
                    </div>
                </label>
                <label class="field">
                    <span>Entry price</span>
                    <input data-leg-index="${index}" data-field="entryPrice" type="number" min="0" step="0.05" value="${stripTrailingZeros(leg.entryPrice)}">
                </label>
            </div>
        </article>
    `).join("");

    els.legsContainer.querySelectorAll("[data-remove-leg]").forEach((button) => {
        button.addEventListener("click", () => removeCustomLeg(Number(button.dataset.removeLeg)));
    });
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
    const distance = strike - numberValue(els.spot);
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
    const strike = numberValue(els.spot) + distance;
    strikeInput.value = stripTrailingZeros(strike);
    legsState[index].strike = strike;
    legsState[index].distance = distance;
}

function syncTimeframeFields() {
    const isCustom = els.timeframe.value === "CUSTOM";
    els.customFromField.hidden = !isCustom;
    els.customToField.hidden = !isCustom;
}

function numberValue(input) {
    return Number.parseFloat(input.value || "0") || 0;
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

function currentEconomicPremiumFromInputs() {
    const signed = legsState.reduce((sum, leg) => {
        const sign = leg.side === "SHORT" ? -1 : 1;
        return sum + (sign * Number(leg.entryPrice || 0));
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
        dte: Number.parseInt(els.dte.value || "0", 10) || 0,
        spot: numberValue(els.spot),
        timeframe: els.timeframe.value,
        customFrom: els.customFrom.value,
        customTo: els.customTo.value,
        legs: legsState.map((leg) => ({ ...leg })),
        currentEconomicPremium: currentEconomicPremiumFromInputs()
    };
}

function renderHeader() {
    const inputs = currentInputs();
    els.strategyChip.textContent = inputs.strategyLabel;
    els.orientationChip.textContent = titleCase(inputs.orientation);
    els.timeframeChip.textContent = inputs.timeframe;
    els.premiumChip.textContent = `${formatNumber(inputs.currentEconomicPremium, 2)} pts`;
}

function setStatus(message) {
    els.statusLine.textContent = message;
}

function resetLiveStrip() {
    els.liveStatusChip.textContent = "Disabled";
    els.liveSpotChip.textContent = "-";
    els.livePremiumChip.textContent = "-";
    els.liveLastTickChip.textContent = "-";
}

function setText(target, value) {
    target.textContent = value;
}

function setPlaceholderResults() {
    [
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
    els.insightVerdict.textContent = "-";
    els.insightVerdict.className = "verdict-badge verdict-neutral";
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

async function runAnalysis() {
    const inputs = currentInputs();
    renderHeader();
    setStatus(`Running canonical historical structure query for ${inputs.strategyLabel}...`);

    if (strategyAbortController) {
        strategyAbortController.abort();
    }
    strategyAbortController = new AbortController();

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
        if (inputs.customFrom) {
            body.set("customFrom", inputs.customFrom);
        }
        if (inputs.customTo) {
            body.set("customTo", inputs.customTo);
        }
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
            body: body.toString(),
            signal: strategyAbortController.signal
        });

        applyStrategyAnalysis(payload, inputs);
        latestReportPayload = { inputs, payload };
        const warning = payload.observation?.lowSampleWarning ? ` ${payload.observation.lowSampleWarning}` : "";
        setStatus(`EconomicMetrics loaded from canonical history: ${payload.observation?.observationCount || 0} matched structures.${warning}`);
        await refreshLiveOverlay();
    } catch (error) {
        if (error.name === "AbortError") {
            return;
        }
        setPlaceholderResults();
        setStatus(`Historical structure query unavailable: ${error.message}`);
    }
}

function startLiveSync() {
    refreshLiveStatus();
    refreshLiveOverlay();
    if (liveSyncTimer) {
        window.clearInterval(liveSyncTimer);
    }
    liveSyncTimer = window.setInterval(() => {
        refreshLiveStatus();
        refreshLiveOverlay();
    }, 15000);
}

async function refreshLiveStatus() {
    try {
        const status = await fetchJson(`${apiBase()}/api/live/status`);
        els.liveStatusChip.textContent = status.status || "Disabled";
        els.liveLastTickChip.textContent = formatLiveTime(status.lastTickTs, status.secondsSinceLastTick);
    } catch (error) {
        resetLiveStrip();
    }
}

async function refreshLiveOverlay() {
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
        if (inputs.customFrom) {
            body.set("customFrom", inputs.customFrom);
        }
        if (inputs.customTo) {
            body.set("customTo", inputs.customTo);
        }
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

        applyLiveOverlay(overlay, inputs);
    } catch (error) {
        if (error.name === "AbortError") {
            return;
        }
        if (!String(error.message || "").includes("404")) {
            els.liveStatusChip.textContent = "Unavailable";
        }
    }
}

function applyLiveOverlay(overlay, inputs) {
    const status = overlay.status || {};
    const spot = overlay.spot || {};
    const structure = overlay.structure || {};

    els.liveStatusChip.textContent = status.status || "Disabled";
    els.liveSpotChip.textContent = Number.isFinite(Number(spot.price)) ? `${formatNumber(spot.price, 2)} pts` : "-";
    els.livePremiumChip.textContent = Number.isFinite(Number(structure.economicNetPremiumPoints))
        ? `${formatNumber(structure.economicNetPremiumPoints, 2)} pts`
        : "-";
    els.liveLastTickChip.textContent = formatLiveTime(status.lastTickTs, status.secondsSinceLastTick);
    hydrateInputsFromLiveOverlay(spot, structure);

    if (overlay.historicalComparison) {
        const liveInputs = {
            ...inputs,
            spot: Number.isFinite(Number(spot.price)) ? Number(spot.price) : inputs.spot,
            legs: legsState.map((leg) => ({ ...leg })),
            currentEconomicPremium: Number(structure.economicNetPremiumPoints || currentEconomicPremiumFromInputs())
        };
        applyStrategyAnalysis(overlay.historicalComparison, liveInputs);
        latestReportPayload = { inputs: liveInputs, payload: overlay.historicalComparison };
        const liveNote = structure.partialData
            ? " Live feed partial; comparison held to available legs only."
            : " Live structure compared against canonical history.";
        setStatus(`${status.status || "LIVE"} overlay active.${liveNote}`);
    }
}

function hydrateInputsFromLiveOverlay(spot, structure) {
    const liveSpot = Number(spot?.price);
    const liveLegs = Array.isArray(structure?.legs) ? structure.legs : [];
    const canHydrateSpot = Number.isFinite(liveSpot);
    const canHydrateLegs = liveLegs.length > 0 && liveLegs.some((leg) => Number.isFinite(Number(leg.lastPrice)));
    if (!canHydrateSpot && !canHydrateLegs) {
        return;
    }

    liveHydrationMuted = true;
    try {
        if (canHydrateSpot) {
            const roundedSpot = Number(liveSpot.toFixed(2));
            els.spot.value = stripTrailingZeros(roundedSpot);
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
                legsState[index].distance = Number(liveLeg.strike) - numberValue(els.spot);
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

function applyStrategyAnalysis(payload, inputs) {
    renderHeader();
    applyInsight(payload.insight, payload.observation);
    applySnapshot(payload);
    applyPremiumTrend(payload.timeframeTrend);
    applyExpiry(payload);
    applyRecommendation("preferred", payload.recommendation?.preferred);
    applyRecommendation("alternative", payload.recommendation?.alternative);
    applyRecommendation("avoid", payload.recommendation?.avoid);
    if (payload.recommendation?.contextNote) {
        els.recommendationAvoidReason.textContent = `${payload.recommendation.avoid?.reason || "-"} ${payload.recommendation.contextNote}`.trim();
    }
    els.premiumChip.textContent = `${formatNumber(payload.premium?.currentPremiumPoints || inputs.currentEconomicPremium, 2)} pts`;
}

function applyInsight(insight, observation) {
    els.insightPremium.textContent = insight?.premiumVerdict || "-";
    els.insightPremiumDetail.textContent = insight?.premiumDetail || "-";
    els.insightEdge.textContent = insight?.edgeVerdict || "-";
    els.insightEdgeDetail.textContent = insight?.edgeDetail || "-";
    els.insightRisk.textContent = insight?.riskVerdict || "-";
    els.insightRiskDetail.textContent = insight?.riskDetail || "-";
    els.insightVerdict.textContent = insight?.overallVerdict || "-";
    els.insightVerdict.className = `verdict-badge ${verdictClass(insight?.overallVerdict, observation?.lowSampleDowngrade)}`;
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

function downloadReport() {
    if (!latestReportPayload) {
        setStatus("Run analysis before downloading the report.");
        return;
    }

    const { inputs, payload } = latestReportPayload;
    const observation = payload.observation || {};
    const premium = payload.premium || {};
    const expiry = payload.expiry || {};
    const pnl = payload.pnl || {};
    const risk = payload.risk || {};
    const insight = payload.insight || {};
    const recommendation = payload.recommendation || {};

    const rows = [
        ["Section", "Metric", "Value"],
        ["Scenario", "Strategy", inputs.strategyLabel],
        ["Scenario", "Orientation", inputs.orientation],
        ["Scenario", "Underlying", inputs.underlying],
        ["Scenario", "Expiry type", inputs.expiryType],
        ["Scenario", "DTE", inputs.dte],
        ["Scenario", "Spot", inputs.spot],
        ["Scenario", "Timeframe", inputs.timeframe],
        ["Scenario", premium.currentPremiumLabel || "Current premium", premium.currentPremiumPoints ?? inputs.currentEconomicPremium],
        ["Observation", "Anchor date", observation.anchorDate || ""],
        ["Observation", "Observation count", observation.observationCount || 0],
        ["Observation", "Evidence strength", observation.evidenceStrength || ""],
        ["Observation", "Low sample warning", observation.lowSampleWarning || ""],
        ["Premium", premium.averageEntryLabel || "Average entry", premium.averageEntryPoints ?? 0],
        ["Premium", premium.medianEntryLabel || "Median entry", premium.medianEntryPoints ?? 0],
        ["Premium", "Raw price percentile", premium.rawPricePercentile ?? 0],
        ["Premium", "Economic percentile", premium.economicPercentile ?? 0],
        ["Premium", "Current vs average pts", premium.currentVsAveragePoints ?? 0],
        ["Premium", "Current vs average pct", premium.currentVsAveragePct ?? 0],
        ["Premium", "Price condition", premium.priceConditionLabel || ""],
        ["Premium", "Attractiveness label", premium.attractivenessLabel || ""],
        ["Expiry", expiry.averageExpiryValueLabel || "Average expiry value", expiry.averageExpiryValuePoints ?? 0],
        ["Expiry", "Average expiry payout pts", expiry.averageExpiryPayoutPoints ?? 0],
        ["Expiry", `${expiry.selectedSideLabel || "Selected"} avg pnl pts`, expiry.selectedSideAveragePnlPoints ?? 0],
        ["Expiry", "Opposite side avg pnl pts", expiry.oppositeSideAveragePnlPoints ?? 0],
        ["Expiry", `${expiry.selectedSideLabel || "Selected"} win rate pct`, expiry.selectedSideWinRatePct ?? 0],
        ["Expiry", "Opposite side win rate pct", expiry.oppositeSideWinRatePct ?? 0],
        ["PnL", "Average pnl pts", pnl.averagePnlPoints ?? 0],
        ["PnL", "Median pnl pts", pnl.medianPnlPoints ?? 0],
        ["PnL", "Average win pnl pts", pnl.avgWinPnlPoints ?? 0],
        ["PnL", "Average loss pnl pts", pnl.avgLossPnlPoints ?? 0],
        ["PnL", "Payoff ratio", pnl.payoffRatio ?? 0],
        ["PnL", "Expectancy pts", pnl.expectancyPoints ?? 0],
        ["PnL", "Expectancy label", pnl.expectancyLabel || ""],
        ["Risk", "Current theoretical max profit pts", risk.currentTheoreticalMaxProfitPoints ?? "Unlimited"],
        ["Risk", "Current theoretical max loss pts", risk.currentTheoreticalMaxLossPoints ?? "Unlimited"],
        ["Risk", "Bounds label", risk.boundsLabel || ""],
        ["Risk", "Tail loss P10 pts", risk.tailLossP10Points ?? 0],
        ["Risk", "Downside profile", risk.downsideProfile || ""],
        ["Risk", "Historical best pnl pts", risk.historicalBestPnlPoints ?? 0],
        ["Risk", "Historical worst pnl pts", risk.historicalWorstPnlPoints ?? 0],
        ["Risk", "Historical extremes label", risk.historicalExtremesLabel || ""],
        ["Insight", "Premium verdict", insight.premiumVerdict || ""],
        ["Insight", "Premium detail", insight.premiumDetail || ""],
        ["Insight", "Edge verdict", insight.edgeVerdict || ""],
        ["Insight", "Edge detail", insight.edgeDetail || ""],
        ["Insight", "Risk verdict", insight.riskVerdict || ""],
        ["Insight", "Risk detail", insight.riskDetail || ""],
        ["Insight", "Overall verdict", insight.overallVerdict || ""],
        ["Insight", "Overall detail", insight.overallDetail || ""],
        ["Recommendation", "Context note", recommendation.contextNote || ""]
    ];

    [
        ["Preferred", recommendation.preferred],
        ["Alternative", recommendation.alternative],
        ["Avoid", recommendation.avoid]
    ].forEach(([label, candidate]) => {
        if (!candidate) {
            return;
        }
        rows.push(["Recommendation", `${label} title`, candidate.title || ""]);
        rows.push(["Recommendation", `${label} verdict`, candidate.verdict || ""]);
        rows.push(["Recommendation", `${label} score`, candidate.score ?? 0]);
        rows.push(["Recommendation", `${label} observations`, candidate.observationCount ?? 0]);
        rows.push(["Recommendation", `${label} raw percentile`, candidate.rawPricePercentile ?? 0]);
        rows.push(["Recommendation", `${label} economic percentile`, candidate.economicPercentile ?? 0]);
        rows.push(["Recommendation", `${label} premium vs average pts`, candidate.premiumVsAveragePoints ?? 0]);
        rows.push(["Recommendation", `${label} avg pnl pts`, candidate.averagePnlPoints ?? 0]);
        rows.push(["Recommendation", `${label} win rate pct`, candidate.winRatePct ?? 0]);
        rows.push(["Recommendation", `${label} downside severity pts`, candidate.downsideSeverityPoints ?? 0]);
        rows.push(["Recommendation", `${label} low sample warning`, candidate.lowSampleWarning || ""]);
        rows.push(["Recommendation", `${label} reason`, candidate.reason || ""]);
    });

    inputs.legs.forEach((leg, index) => {
        rows.push(["Leg", `Leg ${index + 1} label`, leg.label]);
        rows.push(["Leg", `Leg ${index + 1} option type`, leg.optionType]);
        rows.push(["Leg", `Leg ${index + 1} side`, leg.side]);
        rows.push(["Leg", `Leg ${index + 1} strike`, leg.strike]);
        rows.push(["Leg", `Leg ${index + 1} entry price`, leg.entryPrice]);
    });

    (payload.timeframeTrend?.windows || []).forEach((item) => {
        rows.push(["Trend", `${item.label} average premium pts`, item.averagePremiumPoints ?? 0]);
        rows.push(["Trend", `${item.label} median premium pts`, item.medianPremiumPoints ?? 0]);
        rows.push(["Trend", `${item.label} raw percentile`, item.rawPricePercentile ?? 0]);
        rows.push(["Trend", `${item.label} economic percentile`, item.economicPercentile ?? 0]);
        rows.push(["Trend", `${item.label} current vs average pts`, item.currentVsAveragePoints ?? 0]);
        rows.push(["Trend", `${item.label} observations`, item.observationCount ?? 0]);
    });

    (payload.historicalCases || []).forEach((item, index) => {
        rows.push(["Historical case", `Case ${index + 1} trade date`, item.tradeDate]);
        rows.push(["Historical case", `Case ${index + 1} expiry date`, item.expiryDate]);
        rows.push(["Historical case", `Case ${index + 1} entry premium pts`, item.entryPremiumPoints]);
        rows.push(["Historical case", `Case ${index + 1} expiry value pts`, item.expiryValuePoints]);
        rows.push(["Historical case", `Case ${index + 1} selected side pnl pts`, item.selectedSidePnlPoints]);
        rows.push(["Historical case", `Case ${index + 1} buyer pnl pts`, item.buyerPnlPoints]);
        rows.push(["Historical case", `Case ${index + 1} seller pnl pts`, item.sellerPnlPoints]);
        rows.push(["Historical case", `Case ${index + 1} label`, item.historicalExtremesLabel || ""]);
    });

    const csv = rows.map((row) => row.map(csvEscape).join(",")).join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `strategy-structure-report-${inputs.underlying.toLowerCase()}-${Date.now()}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
}

function csvEscape(value) {
    const text = String(value ?? "");
    if (/[",\n]/.test(text)) {
        return `"${text.replace(/"/g, "\"\"")}"`;
    }
    return text;
}

renderHeader();
