const form = document.getElementById("scenario-form");

const els = {
    underlying: document.getElementById("underlying"),
    dte: document.getElementById("dte"),
    spot: document.getElementById("spot"),
    strike: document.getElementById("strike"),
    distancePoints: document.getElementById("distancePoints"),
    optionPrice: document.getElementById("optionPrice"),
    runAnalysisButton: document.getElementById("run-analysis-button"),
    downloadReportButton: document.getElementById("download-report-button"),
    statusLine: document.getElementById("status-line"),
    scenarioChip: document.getElementById("scenario-chip"),
    cohortChip: document.getElementById("cohort-chip"),
    timeframeChip: document.getElementById("timeframe-chip"),
    strategyChip: document.getElementById("strategy-chip"),
    timeframeTableBody: document.getElementById("timeframe-table-body"),
    trendLine: document.getElementById("trend-line"),
    trendPoints: document.getElementById("trend-points"),
    trendLabels: document.getElementById("trend-labels"),
    currentPriceLine: document.getElementById("current-price-line"),
    snapshotObservations: document.getElementById("snapshot-observations"),
    snapshotContracts: document.getElementById("snapshot-contracts"),
    snapshotAverage: document.getElementById("snapshot-average"),
    snapshotMedian: document.getElementById("snapshot-median"),
    snapshotPercentile: document.getElementById("snapshot-percentile"),
    snapshotDifference: document.getElementById("snapshot-difference"),
    outcomeDecay: document.getElementById("outcome-decay"),
    outcomeNextDay: document.getElementById("outcome-next-day"),
    outcomeExpiry: document.getElementById("outcome-expiry"),
    strategyPremium: document.getElementById("strategy-premium"),
    strategyExpiryValue: document.getElementById("strategy-expiry-value"),
    strategyExpiryPnl: document.getElementById("strategy-expiry-pnl"),
    strategyWinRate: document.getElementById("strategy-win-rate"),
    strategyMax: document.getElementById("strategy-max"),
    customFromField: document.getElementById("custom-from-field"),
    customToField: document.getElementById("custom-to-field"),
    customFrom: document.getElementById("custom-from"),
    customTo: document.getElementById("custom-to")
};

const pointBucketSize = {
    NIFTY: 50,
    BANKNIFTY: 100
};

let timeframeAbortController = null;
let outcomeAbortController = null;
let strategyAbortController = null;
let latestReportPayload = null;

form.addEventListener("input", (event) => {
    if (event.target === els.strike || event.target === els.spot) {
        syncDistanceFromStrike();
    }
    if (event.target === els.distancePoints) {
        syncStrikeFromDistance();
    }
    if (event.target.name === "timeframe") {
        syncTimeframeFields();
    }
    renderScenarioHeader();
});

form.addEventListener("change", () => {
    syncTimeframeFields();
    renderScenarioHeader();
});

els.runAnalysisButton.addEventListener("click", runAnalysis);
els.downloadReportButton.addEventListener("click", downloadReport);

function apiBase() {
    return window.location.protocol === "file:" ? "http://localhost:8080" : "";
}

function selectedValue(name) {
    return form.querySelector(`input[name="${name}"]:checked`)?.value || "";
}

function numberValue(input) {
    return Number.parseFloat(input.value || "0") || 0;
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
    return `${value >= 0 ? "+" : ""}${formatNumber(value, digits)}%`;
}

function syncDistanceFromStrike() {
    const distance = numberValue(els.strike) - numberValue(els.spot);
    els.distancePoints.value = Number.isFinite(distance) ? distance.toFixed(2).replace(/\.00$/, "") : "0";
}

function syncStrikeFromDistance() {
    const strike = numberValue(els.spot) + numberValue(els.distancePoints);
    els.strike.value = Number.isFinite(strike) ? strike.toFixed(2).replace(/\.00$/, "") : "0";
}

function estimatedMinutesToExpiry(dte) {
    if (dte <= 0) {
        return 45;
    }
    return dte * 375;
}

function canonicalTimeBucket(dte) {
    return Math.max(0, Math.floor(estimatedMinutesToExpiry(dte) / 15));
}

function currentInputs() {
    const underlying = els.underlying.value;
    const optionType = selectedValue("optionType");
    const expiryType = selectedValue("expiryType");
    const dte = Math.max(0, Math.round(numberValue(els.dte)));
    const spot = numberValue(els.spot);
    const strike = numberValue(els.strike);
    const optionPrice = numberValue(els.optionPrice);
    const distance = strike - spot;
    const bucketSize = pointBucketSize[underlying] || 50;
    const bucket = Math.round(distance / bucketSize) * bucketSize;
    const timeframe = selectedValue("timeframe") || "1Y";
    const strategyMode = selectedValue("strategyMode") || "SINGLE_OPTION";

    return {
        underlying,
        optionType,
        expiryType,
        dte,
        spot,
        strike,
        optionPrice,
        distance,
        bucket,
        timeframe,
        strategyMode,
        customFrom: els.customFrom.value || "",
        customTo: els.customTo.value || "",
        timeBucket15m: canonicalTimeBucket(dte)
    };
}

function timeframeLabel() {
    const timeframe = selectedValue("timeframe") || "1Y";
    if (timeframe !== "CUSTOM") {
        return timeframe;
    }
    const from = els.customFrom.value || "start";
    const to = els.customTo.value || "end";
    return `${from} -> ${to}`;
}

function syncTimeframeFields() {
    const custom = selectedValue("timeframe") === "CUSTOM";
    els.customFromField.hidden = !custom;
    els.customToField.hidden = !custom;
}

function renderScenarioHeader() {
    const inputs = currentInputs();
    els.scenarioChip.textContent = `${inputs.underlying} / ${inputs.optionType} / ${inputs.expiryType} / ${inputs.dte} DTE`;
    els.cohortChip.textContent = `${inputs.underlying} / ${inputs.optionType} / TB${inputs.timeBucket15m} / ${formatSigned(inputs.bucket, 0)}`;
    els.timeframeChip.textContent = timeframeLabel();
    els.strategyChip.textContent = strategyLabel(inputs.strategyMode);
}

function setStatus(message) {
    els.statusLine.textContent = message;
}

function setPlaceholderResults() {
    [
        els.snapshotObservations,
        els.snapshotContracts,
        els.snapshotAverage,
        els.snapshotMedian,
        els.snapshotPercentile,
        els.snapshotDifference,
        els.outcomeDecay,
        els.outcomeNextDay,
        els.outcomeExpiry,
        els.strategyPremium,
        els.strategyExpiryValue,
        els.strategyExpiryPnl,
        els.strategyWinRate,
        els.strategyMax
    ].forEach((element) => {
        element.textContent = "-";
        element.parentElement.classList.remove("warn");
    });
    els.timeframeTableBody.innerHTML = ["5Y", "2Y", "1Y", "6M", "3M", "1M"]
        .map((label) => `<tr><td>${label}</td><td>-</td></tr>`)
        .join("");
    els.trendLine.setAttribute("points", "");
    els.trendPoints.innerHTML = "";
    els.trendLabels.innerHTML = "";
    els.currentPriceLine.setAttribute("y1", "110");
    els.currentPriceLine.setAttribute("y2", "110");
    latestReportPayload = null;
}

function strategyLabel(mode) {
    switch (mode) {
        case "STRADDLE":
            return "Straddle";
        case "STRANGLE":
            return "Strangle";
        default:
            return "Single Option";
    }
}

async function fetchJson(url, signal) {
    const response = await fetch(url, { signal });
    if (!response.ok) {
        const payload = await response.json().catch(() => ({}));
        throw new Error(payload.error || "Request failed");
    }
    return response.json();
}

async function runAnalysis() {
    const inputs = currentInputs();
    renderScenarioHeader();
    setStatus(`Running canonical historical query for ${inputs.underlying} ${inputs.optionType} ${inputs.expiryType} in ${timeframeLabel()}.`);

    if (timeframeAbortController) {
        timeframeAbortController.abort();
    }
    if (outcomeAbortController) {
        outcomeAbortController.abort();
    }
    if (strategyAbortController) {
        strategyAbortController.abort();
    }

    timeframeAbortController = new AbortController();
    outcomeAbortController = new AbortController();
    strategyAbortController = new AbortController();

    try {
        const timeframeQuery = new URLSearchParams({
            underlying: inputs.underlying,
            optionType: inputs.optionType,
            spot: String(inputs.spot),
            strike: String(inputs.strike),
            dte: String(inputs.dte),
            optionPrice: String(inputs.optionPrice),
            timeframe: inputs.timeframe
        });
        if (inputs.customFrom) {
            timeframeQuery.set("customFrom", inputs.customFrom);
        }
        if (inputs.customTo) {
            timeframeQuery.set("customTo", inputs.customTo);
        }
        const outcomeQuery = new URLSearchParams({
            underlying: inputs.underlying,
            optionType: inputs.optionType,
            spot: String(inputs.spot),
            strike: String(inputs.strike),
            dte: String(inputs.dte)
        });
        const strategyQuery = new URLSearchParams({
            underlying: inputs.underlying,
            optionType: inputs.optionType,
            spot: String(inputs.spot),
            strike: String(inputs.strike),
            dte: String(inputs.dte),
            mode: inputs.strategyMode,
            timeframe: inputs.timeframe
        });
        if (inputs.customFrom) {
            strategyQuery.set("customFrom", inputs.customFrom);
        }
        if (inputs.customTo) {
            strategyQuery.set("customTo", inputs.customTo);
        }

        const [timeframePayload, outcomePayload, strategyPayload] = await Promise.all([
            fetchJson(`${apiBase()}/api/timeframe-analysis?${timeframeQuery.toString()}`, timeframeAbortController.signal),
            fetchJson(`${apiBase()}/api/forward-outcomes?${outcomeQuery.toString()}`, outcomeAbortController.signal),
            fetchJson(`${apiBase()}/api/strategy-analysis?${strategyQuery.toString()}`, strategyAbortController.signal)
        ]);

        applyTimeframeAnalysis(timeframePayload);
        applyOutcomeSummary(outcomePayload);
        applyStrategySummary(strategyPayload);
        latestReportPayload = {
            inputs,
            timeframePayload,
            outcomePayload,
            strategyPayload
        };
        setStatus(`Historical query complete for cohort ${els.cohortChip.textContent} with ${timeframePayload.selectedWindow.label} regime context in ${strategyLabel(inputs.strategyMode)} mode.`);
    } catch (error) {
        if (error.name === "AbortError") {
            return;
        }
        setPlaceholderResults();
        els.snapshotObservations.parentElement.classList.add("warn");
        els.outcomeDecay.parentElement.classList.add("warn");
        els.strategyPremium.parentElement.classList.add("warn");
        setStatus(`Historical query unavailable: ${error.message}`);
    }
}

function applyTimeframeAnalysis(payload) {
    const selected = payload.selectedWindow || {};
    const windows = Array.isArray(payload.windows) ? payload.windows : [];

    els.snapshotObservations.textContent = `${Number(selected.observationCount || 0)}`;
    els.snapshotContracts.textContent = `${Number(selected.uniqueContracts || 0)}`;
    els.snapshotAverage.textContent = formatNumber(Number(selected.averagePrice || 0), 2);
    els.snapshotMedian.textContent = formatNumber(Number(selected.medianPrice || 0), 2);
    els.snapshotPercentile.textContent = Number(selected.percentile || 0) > 0 ? `${selected.percentile}th` : "n/a";
    els.snapshotDifference.textContent = formatSigned(Number(selected.differenceVsCurrent || 0), 2);

    els.timeframeTableBody.innerHTML = windows.map((item) => `
        <tr>
            <td>${item.label}</td>
            <td>${Number(item.observationCount || 0) > 0 ? formatNumber(Number(item.averagePrice || 0), 2) : "-"}</td>
        </tr>
    `).join("");

    renderTrendChart(windows, Number(payload.currentOptionPrice || 0));
}

function renderTrendChart(windows, currentPrice) {
    const chartWindows = windows.filter((item) => Number(item.observationCount || 0) > 0);
    if (chartWindows.length === 0) {
        els.trendLine.setAttribute("points", "");
        els.trendPoints.innerHTML = "";
        els.trendLabels.innerHTML = "";
        els.currentPriceLine.setAttribute("y1", "110");
        els.currentPriceLine.setAttribute("y2", "110");
        return;
    }

    const values = chartWindows.map((item) => Number(item.averagePrice || 0));
    values.push(currentPrice);
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
        const y = valueToY(Number(item.averagePrice || 0));
        return { label: item.label, x, y, value: Number(item.averagePrice || 0) };
    });

    els.trendLine.setAttribute(
        "points",
        points.map((point) => `${point.x},${point.y}`).join(" ")
    );

    const currentY = valueToY(currentPrice);
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

function applyOutcomeSummary(payload) {
    const nextDayMove = Number(payload.nextDay?.meanReturnPct || 0);
    const expiryPnl = Number(payload.expiry?.meanReturnPct || 0);
    const decayPct = Number(payload.expiry?.decayProbabilityPct || 0);

    els.outcomeDecay.textContent = `${formatNumber(decayPct, 1)}%`;
    els.outcomeNextDay.textContent = formatPercent(nextDayMove, 1);
    els.outcomeExpiry.textContent = formatPercent(expiryPnl, 1);
}

function applyStrategySummary(payload) {
    els.strategyPremium.textContent = formatNumber(Number(payload.averagePremiumCollected || 0), 2);
    els.strategyExpiryValue.textContent = formatNumber(Number(payload.averageExpiryValue || 0), 2);
    els.strategyExpiryPnl.textContent = formatSigned(Number(payload.averageExpiryPnl || 0), 2);
    els.strategyWinRate.textContent = `${formatNumber(Number(payload.winRatePct || 0), 1)}%`;
    els.strategyMax.textContent = `${formatSigned(Number(payload.maxGain || 0), 2)} / ${formatSigned(Number(payload.maxLoss || 0), 2)}`;
}

function downloadReport() {
    if (!latestReportPayload) {
        setStatus("Run analysis before downloading the report.");
        return;
    }
    const { inputs, timeframePayload, outcomePayload, strategyPayload } = latestReportPayload;
    const rows = [
        ["Section", "Metric", "Value"],
        ["Scenario", "Underlying", inputs.underlying],
        ["Scenario", "Option type", inputs.optionType],
        ["Scenario", "Expiry type", inputs.expiryType],
        ["Scenario", "DTE", inputs.dte],
        ["Scenario", "Spot", inputs.spot],
        ["Scenario", "Strike", inputs.strike],
        ["Scenario", "Distance points", inputs.distance],
        ["Scenario", "Current option price", inputs.optionPrice],
        ["Scenario", "Strategy mode", strategyLabel(inputs.strategyMode)],
        ["Scenario", "Timeframe", timeframeLabel()],
        ["Snapshot", "Selected window observations", timeframePayload.selectedWindow?.observationCount ?? 0],
        ["Snapshot", "Selected window unique contracts", timeframePayload.selectedWindow?.uniqueContracts ?? 0],
        ["Snapshot", "Selected window average price", timeframePayload.selectedWindow?.averagePrice ?? 0],
        ["Snapshot", "Selected window median price", timeframePayload.selectedWindow?.medianPrice ?? 0],
        ["Snapshot", "Selected window percentile", timeframePayload.selectedWindow?.percentile ?? 0],
        ["Snapshot", "Selected window difference vs current", timeframePayload.selectedWindow?.differenceVsCurrent ?? 0],
        ["Outcome", "Decay pct", outcomePayload.expiry?.decayProbabilityPct ?? 0],
        ["Outcome", "Next-day avg move pct", outcomePayload.nextDay?.meanReturnPct ?? 0],
        ["Outcome", "Expiry avg pnl pct", outcomePayload.expiry?.meanReturnPct ?? 0],
        ["Strategy", "Mode", strategyPayload.mode],
        ["Strategy", "Observation count", strategyPayload.observationCount],
        ["Strategy", "Average premium collected", strategyPayload.averagePremiumCollected],
        ["Strategy", "Average expiry value", strategyPayload.averageExpiryValue],
        ["Strategy", "Average expiry pnl", strategyPayload.averageExpiryPnl],
        ["Strategy", "Win rate pct", strategyPayload.winRatePct],
        ["Strategy", "Max gain", strategyPayload.maxGain],
        ["Strategy", "Max loss", strategyPayload.maxLoss]
    ];

    (timeframePayload.windows || []).forEach((item) => {
        rows.push(["Timeframe trend", `${item.label} avg price`, item.averagePrice]);
        rows.push(["Timeframe trend", `${item.label} observations`, item.observationCount]);
        rows.push(["Timeframe trend", `${item.label} unique contracts`, item.uniqueContracts]);
        rows.push(["Timeframe trend", `${item.label} percentile`, item.percentile]);
    });

    const csv = rows
        .map((row) => row.map(csvEscape).join(","))
        .join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `strategy-squad-report-${inputs.underlying.toLowerCase()}-${Date.now()}.csv`;
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

syncDistanceFromStrike();
syncTimeframeFields();
renderScenarioHeader();
setPlaceholderResults();
