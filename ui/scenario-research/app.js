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
    snapshotBestWorst: document.getElementById("snapshot-best-worst"),
    timeframeTableBody: document.getElementById("timeframe-table-body"),
    trendLine: document.getElementById("trend-line"),
    trendPoints: document.getElementById("trend-points"),
    trendLabels: document.getElementById("trend-labels"),
    currentPriceLine: document.getElementById("current-price-line"),
    expiryPayout: document.getElementById("expiry-payout"),
    expirySellerPnl: document.getElementById("expiry-seller-pnl"),
    expiryBuyerPnl: document.getElementById("expiry-buyer-pnl"),
    expiryWinRate: document.getElementById("expiry-win-rate"),
    expiryTailLoss: document.getElementById("expiry-tail-loss"),
    expiryDownside: document.getElementById("expiry-downside"),
    recommendationPreferredTitle: document.getElementById("recommendation-preferred-title"),
    recommendationPreferredReason: document.getElementById("recommendation-preferred-reason"),
    recommendationAlternativeTitle: document.getElementById("recommendation-alternative-title"),
    recommendationAlternativeReason: document.getElementById("recommendation-alternative-reason"),
    recommendationAvoidTitle: document.getElementById("recommendation-avoid-title"),
    recommendationAvoidReason: document.getElementById("recommendation-avoid-reason")
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
    BANKNIFTY: 100
};

let legsState = [];
let strategyAbortController = null;
let latestReportPayload = null;

populateStrategyOptions();
initializeStrategy();

form.addEventListener("input", handleFormInput);
form.addEventListener("change", handleFormChange);
els.addLegButton.addEventListener("click", addCustomLeg);
els.runAnalysisButton.addEventListener("click", runAnalysis);
els.downloadReportButton.addEventListener("click", downloadReport);

function apiBase() {
    return window.location.protocol === "file:" ? "http://localhost:8080" : "";
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
}

function handleFormInput(event) {
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
}

function handleFormChange(event) {
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
    legsState = strategy.createLegs(spot, bucketSize());
    legsState = legsState.map((leg) => ({
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
    const nextIndex = legsState.length + 1;
    legsState.push(createLegState({
        label: `Custom leg ${nextIndex}`,
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
    }).format(value);
}

function formatSigned(value, digits = 2) {
    const sign = value > 0 ? "+" : value < 0 ? "-" : "";
    return `${sign}${formatNumber(Math.abs(value), digits)}`;
}

function formatPercent(value, digits = 1) {
    return `${formatNumber(value, digits)}%`;
}

function syncTimeframeFields() {
    const custom = els.timeframe.value === "CUSTOM";
    els.customFromField.hidden = !custom;
    els.customToField.hidden = !custom;
}

function currentInputs() {
    const config = strategyConfigs[els.strategyMode.value];
    return {
        strategyMode: els.strategyMode.value,
        strategyLabel: config.label,
        orientation: config.orientation,
        underlying: els.underlying.value,
        expiryType: els.expiryType.value,
        dte: Math.max(0, Math.round(numberValue(els.dte))),
        spot: numberValue(els.spot),
        timeframe: els.timeframe.value,
        customFrom: els.customFrom.value || "",
        customTo: els.customTo.value || "",
        legs: legsState.map((leg) => ({
            ...leg
        })),
        currentTotalPremium: legsState.reduce((sum, leg) => sum + (Number(leg.entryPrice) || 0), 0)
    };
}

function renderHeader() {
    const inputs = currentInputs();
    els.strategyChip.textContent = inputs.strategyLabel;
    els.orientationChip.textContent = titleCase(inputs.orientation);
    els.timeframeChip.textContent = inputs.timeframe === "CUSTOM"
        ? `${inputs.customFrom || "start"} -> ${inputs.customTo || "end"}`
        : inputs.timeframe;
    els.premiumChip.textContent = formatNumber(inputs.currentTotalPremium, 2);
}

function titleCase(value) {
    return value.charAt(0) + value.slice(1).toLowerCase();
}

function setStatus(message) {
    els.statusLine.textContent = message;
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
        els.expiryPayout,
        els.expirySellerPnl,
        els.expiryBuyerPnl,
        els.expiryWinRate,
        els.expiryTailLoss,
        els.expiryDownside
    ].forEach((element) => {
        element.textContent = "-";
    });

    els.recommendationPreferredTitle.textContent = "-";
    els.recommendationPreferredReason.textContent = "-";
    els.recommendationAlternativeTitle.textContent = "-";
    els.recommendationAlternativeReason.textContent = "-";
    els.recommendationAvoidTitle.textContent = "-";
    els.recommendationAvoidReason.textContent = "-";

    els.timeframeTableBody.innerHTML = ["5Y", "2Y", "1Y", "6M", "3M", "1M"]
        .map((label) => `<tr><td>${label}</td><td>-</td><td>-</td><td>-</td></tr>`)
        .join("");
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
        const payload = await response.json().catch(() => ({}));
        throw new Error(payload.error || "Request failed");
    }
    return response.json();
}

async function runAnalysis() {
    const inputs = currentInputs();
    renderHeader();
    setStatus(`Running ${inputs.strategyLabel} against canonical historical structure matches.`);
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

        applyStrategyAnalysis(payload);
        latestReportPayload = { inputs, payload };
        setStatus(`Structure query complete with ${payload.observationCount} matched historical structures for ${inputs.strategyLabel}.`);
    } catch (error) {
        if (error.name === "AbortError") {
            return;
        }
        setPlaceholderResults();
        setStatus(`Historical structure query unavailable: ${error.message}`);
    }
}

function applyStrategyAnalysis(payload) {
    els.snapshotObservations.textContent = `${Number(payload.observationCount || 0)}`;
    els.snapshotAverageEntry.textContent = formatNumber(Number(payload.snapshot?.averageEntryPremium || 0), 2);
    els.snapshotMedianEntry.textContent = formatNumber(Number(payload.snapshot?.medianEntryPremium || 0), 2);
    els.snapshotPercentile.textContent = payload.snapshot?.currentPremiumPercentile ? `${payload.snapshot.currentPremiumPercentile}th` : "n/a";
    els.snapshotVsHistory.textContent = formatSigned(Number(payload.snapshot?.currentVsHistoricalAverage || 0), 2);
    els.snapshotExpiryValue.textContent = formatNumber(Number(payload.snapshot?.averageExpiryValue || 0), 2);
    els.snapshotAveragePnl.textContent = formatSigned(Number(payload.snapshot?.averagePnl || 0), 2);
    els.snapshotMedianPnl.textContent = formatSigned(Number(payload.snapshot?.medianPnl || 0), 2);
    els.snapshotWinRate.textContent = formatPercent(Number(payload.snapshot?.winRatePct || 0), 1);
    els.snapshotBestWorst.textContent = `${formatSigned(Number(payload.snapshot?.bestCase || 0), 2)} / ${formatSigned(Number(payload.snapshot?.worstCase || 0), 2)}`;

    els.expiryPayout.textContent = formatNumber(Number(payload.expiryOutcome?.averageExpiryPayout || 0), 2);
    els.expirySellerPnl.textContent = formatSigned(Number(payload.expiryOutcome?.averageSellerPnl || 0), 2);
    els.expiryBuyerPnl.textContent = formatSigned(Number(payload.expiryOutcome?.averageBuyerPnl || 0), 2);
    els.expiryWinRate.textContent = formatPercent(Number(payload.expiryOutcome?.winRatePct || 0), 1);
    els.expiryTailLoss.textContent = formatSigned(Number(payload.expiryOutcome?.tailLossP10 || 0), 2);
    els.expiryDownside.textContent = payload.expiryOutcome?.downsideProfile || "-";

    applyRecommendation("preferred", payload.recommendation?.preferred);
    applyRecommendation("alternative", payload.recommendation?.alternative);
    applyRecommendation("avoid", payload.recommendation?.avoid);
    applyPremiumTrend(payload.premiumWindows || [], Number(payload.currentTotalPremium || 0));
}

function applyRecommendation(slot, recommendation) {
    const titleTarget = els[`recommendation${capitalize(slot)}Title`];
    const reasonTarget = els[`recommendation${capitalize(slot)}Reason`];
    if (!recommendation) {
        titleTarget.textContent = "-";
        reasonTarget.textContent = "-";
        return;
    }
    titleTarget.textContent = `${strategyLabel(recommendation.mode)} / ${titleCase(recommendation.orientation)}`;
    reasonTarget.textContent = recommendation.reason || "-";
}

function capitalize(value) {
    return value.charAt(0).toUpperCase() + value.slice(1);
}

function strategyLabel(mode) {
    return strategyConfigs[mode]?.label || mode;
}

function applyPremiumTrend(windows, currentPremium) {
    els.timeframeTableBody.innerHTML = windows.map((item) => `
        <tr>
            <td>${item.label}</td>
            <td>${item.observationCount > 0 ? formatNumber(Number(item.averageTotalPremium || 0), 2) : "-"}</td>
            <td>${item.observationCount > 0 ? formatNumber(Number(item.medianPremium || 0), 2) : "-"}</td>
            <td>${item.observationCount > 0 ? formatSigned(Number(item.currentVsHistoricalAverage || 0), 2) : "-"}</td>
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

    const values = chartWindows.map((item) => Number(item.averageTotalPremium || 0));
    values.push(currentPremium);
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

    const points = chartWindows.map((item, index) => {
        const x = startX + (index * stepX);
        const y = valueToY(Number(item.averageTotalPremium || 0));
        return { x, y, label: item.label, value: Number(item.averageTotalPremium || 0) };
    });

    els.trendLine.setAttribute("points", points.map((point) => `${point.x},${point.y}`).join(" "));
    const currentY = valueToY(currentPremium);
    els.currentPriceLine.setAttribute("y1", `${currentY}`);
    els.currentPriceLine.setAttribute("y2", `${currentY}`);
    els.trendPoints.innerHTML = points.map((point) => `
        <circle class="trend-point" cx="${point.x}" cy="${point.y}" r="4"></circle>
    `).join("");
    els.trendLabels.innerHTML = points.map((point) => `
        <g>
            <text class="trend-value-label" x="${point.x}" y="${point.y - 10}" text-anchor="middle">${formatNumber(point.value, 1)}</text>
            <text class="trend-axis-label" x="${point.x}" y="204" text-anchor="middle">${point.label}</text>
        </g>
    `).join("");
}

function downloadReport() {
    if (!latestReportPayload) {
        setStatus("Run analysis before downloading the report.");
        return;
    }
    const { inputs, payload } = latestReportPayload;
    const rows = [
        ["Section", "Metric", "Value"],
        ["Scenario", "Strategy", inputs.strategyLabel],
        ["Scenario", "Orientation", inputs.orientation],
        ["Scenario", "Underlying", inputs.underlying],
        ["Scenario", "Expiry type", inputs.expiryType],
        ["Scenario", "DTE", inputs.dte],
        ["Scenario", "Spot", inputs.spot],
        ["Scenario", "Timeframe", inputs.timeframe],
        ["Scenario", "Current total premium", inputs.currentTotalPremium],
        ["Snapshot", "Observations", payload.observationCount],
        ["Snapshot", "Average entry premium", payload.snapshot?.averageEntryPremium ?? 0],
        ["Snapshot", "Median entry premium", payload.snapshot?.medianEntryPremium ?? 0],
        ["Snapshot", "Current premium percentile", payload.snapshot?.currentPremiumPercentile ?? 0],
        ["Snapshot", "Current vs historical average", payload.snapshot?.currentVsHistoricalAverage ?? 0],
        ["Snapshot", "Average expiry value", payload.snapshot?.averageExpiryValue ?? 0],
        ["Snapshot", "Average PnL", payload.snapshot?.averagePnl ?? 0],
        ["Snapshot", "Median PnL", payload.snapshot?.medianPnl ?? 0],
        ["Snapshot", "Win rate pct", payload.snapshot?.winRatePct ?? 0],
        ["Snapshot", "Best case", payload.snapshot?.bestCase ?? 0],
        ["Snapshot", "Worst case", payload.snapshot?.worstCase ?? 0],
        ["Expiry", "Average payout", payload.expiryOutcome?.averageExpiryPayout ?? 0],
        ["Expiry", "Average seller PnL", payload.expiryOutcome?.averageSellerPnl ?? 0],
        ["Expiry", "Average buyer PnL", payload.expiryOutcome?.averageBuyerPnl ?? 0],
        ["Expiry", "Win rate pct", payload.expiryOutcome?.winRatePct ?? 0],
        ["Expiry", "Tail loss P10", payload.expiryOutcome?.tailLossP10 ?? 0],
        ["Expiry", "Downside profile", payload.expiryOutcome?.downsideProfile ?? ""],
        ["Recommendation", "Preferred", payload.recommendation?.preferred?.mode ?? ""],
        ["Recommendation", "Preferred reason", payload.recommendation?.preferred?.reason ?? ""],
        ["Recommendation", "Alternative", payload.recommendation?.alternative?.mode ?? ""],
        ["Recommendation", "Alternative reason", payload.recommendation?.alternative?.reason ?? ""],
        ["Recommendation", "Avoid", payload.recommendation?.avoid?.mode ?? ""],
        ["Recommendation", "Avoid reason", payload.recommendation?.avoid?.reason ?? ""]
    ];

    inputs.legs.forEach((leg, index) => {
        rows.push(["Leg", `Leg ${index + 1} label`, leg.label]);
        rows.push(["Leg", `Leg ${index + 1} option type`, leg.optionType]);
        rows.push(["Leg", `Leg ${index + 1} side`, leg.side]);
        rows.push(["Leg", `Leg ${index + 1} strike`, leg.strike]);
        rows.push(["Leg", `Leg ${index + 1} entry price`, leg.entryPrice]);
    });

    (payload.premiumWindows || []).forEach((item) => {
        rows.push(["Premium trend", `${item.label} avg total premium`, item.averageTotalPremium]);
        rows.push(["Premium trend", `${item.label} median premium`, item.medianPremium]);
        rows.push(["Premium trend", `${item.label} current vs historical average`, item.currentVsHistoricalAverage]);
        rows.push(["Premium trend", `${item.label} observations`, item.observationCount]);
    });

    (payload.matchedCases || []).forEach((item, index) => {
        rows.push(["Matched case", `Case ${index + 1} trade date`, item.tradeDate]);
        rows.push(["Matched case", `Case ${index + 1} expiry date`, item.expiryDate]);
        rows.push(["Matched case", `Case ${index + 1} total entry premium`, item.totalEntryPremium]);
        rows.push(["Matched case", `Case ${index + 1} expiry value`, item.expiryValue]);
        rows.push(["Matched case", `Case ${index + 1} selected pnl`, item.selectedPnl]);
        rows.push(["Matched case", `Case ${index + 1} buyer pnl`, item.buyerPnl]);
        rows.push(["Matched case", `Case ${index + 1} seller pnl`, item.sellerPnl]);
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
