const form = document.getElementById("scenario-form");

const els = {
    underlying: document.getElementById("underlying"),
    dte: document.getElementById("dte"),
    spot: document.getElementById("spot"),
    strike: document.getElementById("strike"),
    distancePoints: document.getElementById("distancePoints"),
    optionPrice: document.getElementById("optionPrice"),
    activityBias: document.getElementById("activityBias"),
    researchNote: document.getElementById("researchNote"),
    moneynessSummary: document.getElementById("moneyness-summary"),
    moneynessText: document.getElementById("moneyness-text"),
    dteSummary: document.getElementById("dte-summary"),
    dteText: document.getElementById("dte-text"),
    premiumSummary: document.getElementById("premium-summary"),
    premiumText: document.getElementById("premium-text"),
    cohortKey: document.getElementById("cohort-key"),
    cohortText: document.getElementById("cohort-text"),
    canonicalTimeSummary: document.getElementById("canonical-time-summary"),
    canonicalTimeText: document.getElementById("canonical-time-text"),
    cohortResolutionSummary: document.getElementById("cohort-resolution-summary"),
    cohortResolutionText: document.getElementById("cohort-resolution-text"),
    cohortObservations: document.getElementById("cohort-observations"),
    cohortObservationsText: document.getElementById("cohort-observations-text"),
    cohortStrength: document.getElementById("cohort-strength"),
    cohortStrengthText: document.getElementById("cohort-strength-text"),
    exactContext: document.getElementById("exact-context"),
    interpretedContext: document.getElementById("interpreted-context"),
    queryShape: document.getElementById("query-shape"),
    cohortBody: document.getElementById("cohort-body"),
    valuationBadge: document.getElementById("valuation-badge"),
    fairnessLabel: document.getElementById("fairness-label"),
    fairnessText: document.getElementById("fairness-text"),
    valuationCurrent: document.getElementById("valuation-current"),
    valuationZone: document.getElementById("valuation-zone"),
    valuationPercentile: document.getElementById("valuation-percentile"),
    distributionTitle: document.getElementById("distribution-title"),
    distributionSummary: document.getElementById("distribution-summary"),
    distMin: document.getElementById("dist-min"),
    distP25: document.getElementById("dist-p25"),
    distMedian: document.getElementById("dist-median"),
    distP75: document.getElementById("dist-p75"),
    distMax: document.getElementById("dist-max"),
    currentMarker: document.getElementById("current-marker"),
    sampleStrength: document.getElementById("sample-strength"),
    sampleText: document.getElementById("sample-text"),
    sampleSize: document.getElementById("sample-size"),
    sampleSizeText: document.getElementById("sample-size-text"),
    biasCheck: document.getElementById("bias-check"),
    biasText: document.getElementById("bias-text"),
    outcomeCallout: document.getElementById("outcome-callout"),
    decisionPosture: document.getElementById("decision-posture"),
    decisionRationale: document.getElementById("decision-rationale"),
    nextStepBias: document.getElementById("next-step-bias"),
    asymmetryScore: document.getElementById("asymmetry-score"),
    decisionConfidence: document.getElementById("decision-confidence"),
    nextDayMedian: document.getElementById("next-day-median"),
    nextDayText: document.getElementById("next-day-text"),
    nextDayUp: document.getElementById("next-day-up"),
    nextDayFlat: document.getElementById("next-day-flat"),
    nextDayDown: document.getElementById("next-day-down"),
    shortHorizonMedian: document.getElementById("short-horizon-median"),
    shortHorizonText: document.getElementById("short-horizon-text"),
    shortUp: document.getElementById("short-up"),
    shortFlat: document.getElementById("short-flat"),
    shortDown: document.getElementById("short-down"),
    expiryHorizonMedian: document.getElementById("expiry-horizon-median"),
    expiryHorizonText: document.getElementById("expiry-horizon-text"),
    expiryUp: document.getElementById("expiry-up"),
    expiryFlat: document.getElementById("expiry-flat"),
    expiryDown: document.getElementById("expiry-down"),
    outcomeShapeTitle: document.getElementById("outcome-shape-title"),
    shapeNextLeft: document.getElementById("shape-next-left"),
    shapeNextCenter: document.getElementById("shape-next-center"),
    shapeNextRight: document.getElementById("shape-next-right"),
    shapeNextRange: document.getElementById("shape-next-range"),
    shapeShortLeft: document.getElementById("shape-short-left"),
    shapeShortCenter: document.getElementById("shape-short-center"),
    shapeShortRight: document.getElementById("shape-short-right"),
    shapeShortRange: document.getElementById("shape-short-range"),
    shapeExpiryLeft: document.getElementById("shape-expiry-left"),
    shapeExpiryCenter: document.getElementById("shape-expiry-center"),
    shapeExpiryRight: document.getElementById("shape-expiry-right"),
    shapeExpiryRange: document.getElementById("shape-expiry-range"),
    outcomeDistributionSummary: document.getElementById("outcome-distribution-summary"),
    stanceBody: document.getElementById("stance-body"),
    trustCard: document.getElementById("trust-card"),
    trustLevel: document.getElementById("trust-level"),
    trustText: document.getElementById("trust-text"),
    diagCohortSize: document.getElementById("diag-cohort-size"),
    diagCohortSizeText: document.getElementById("diag-cohort-size-text"),
    diagConcentration: document.getElementById("diag-concentration"),
    diagConcentrationText: document.getElementById("diag-concentration-text"),
    diagSparsity: document.getElementById("diag-sparsity"),
    diagSparsityText: document.getElementById("diag-sparsity-text"),
    warningChip1: document.getElementById("warning-chip-1"),
    warningChip2: document.getElementById("warning-chip-2"),
    warningChip3: document.getElementById("warning-chip-3"),
    coverageNext: document.getElementById("coverage-next"),
    coverageShort: document.getElementById("coverage-short"),
    coverageExpiry: document.getElementById("coverage-expiry"),
    compareList: document.getElementById("compare-list"),
    caseTitle: document.getElementById("case-title"),
    caseList: document.getElementById("case-list"),
    compareBoardBody: document.getElementById("compare-board-body"),
    workspaceList: document.getElementById("workspace-list"),
    attractiveStack: document.getElementById("attractive-stack"),
    uncertainStack: document.getElementById("uncertain-stack"),
    unattractiveStack: document.getElementById("unattractive-stack"),
    journalList: document.getElementById("journal-list"),
    saveScenarioButton: document.getElementById("save-scenario-button"),
    cloneScenarioButton: document.getElementById("clone-scenario-button"),
    refreshBoardButton: document.getElementById("refresh-board-button"),
    workflowStatus: document.getElementById("workflow-status"),
    collectionSelect: document.getElementById("collection-select"),
    collectionNameInput: document.getElementById("collection-name-input"),
    createCollectionButton: document.getElementById("create-collection-button")
};

const pointBucketSize = {
    NIFTY: 50,
    BANKNIFTY: 100
};

let workspaceState = {
    collections: [],
    studies: [],
    activeStudyId: null,
    selectedCollectionId: null
};
let currentScenarioAnalytics = null;
let syncingFrom = null;
let workflowStatusMessage = "Current scenario is live in the builder and ready to be saved into the research workspace.";
let fairValueRequestToken = 0;
let fairValueAbortController = null;
let forwardOutcomeRequestToken = 0;
let forwardOutcomeAbortController = null;
let diagnosticsRequestToken = 0;
let diagnosticsAbortController = null;
let latestFairValuePayload = null;
let latestForwardOutcomePayload = null;
let latestDiagnosticsPayload = null;

form.addEventListener("input", (event) => {
    if (event.target === els.strike || event.target === els.spot) {
        syncDistanceFromStrike();
    }
    if (event.target === els.distancePoints) {
        syncStrikeFromDistance();
    }
    render();
});

form.addEventListener("change", render);
els.saveScenarioButton.addEventListener("click", saveCurrentScenario);
els.cloneScenarioButton.addEventListener("click", cloneCurrentScenario);
els.refreshBoardButton.addEventListener("click", () => {
    workflowStatusMessage = "Refreshing research workspace from canonical stored studies.";
    loadWorkspaceFromServer();
});
els.collectionSelect.addEventListener("change", () => {
    workspaceState.selectedCollectionId = els.collectionSelect.value || null;
    render();
});
els.createCollectionButton.addEventListener("click", createCollection);
els.workspaceList.addEventListener("click", (event) => {
    const button = event.target.closest("[data-load-study]");
    if (!button) {
        return;
    }
    loadSavedStudy(button.getAttribute("data-load-study"));
});

function numberValue(input) {
    return Number.parseFloat(input.value || "0") || 0;
}

function selectedValue(name) {
    return form.querySelector(`input[name="${name}"]:checked`)?.value || "";
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

function classifyFairness(percentile) {
    if (percentile <= 0) {
        return "sparse";
    }
    if (percentile <= 10 || percentile >= 90) {
        return "extreme";
    }
    if (percentile < 35) {
        return "cheap";
    }
    if (percentile > 65) {
        return "rich";
    }
    return "fair";
}

function apiBase() {
    return window.location.protocol === "file:" ? "http://localhost:8080" : "";
}

async function fetchWorkspaceSnapshot() {
    const response = await fetch(`${apiBase()}/api/workflow/studies`);
    if (!response.ok) {
        const payload = await response.json().catch(() => ({}));
        throw new Error(payload.error || "Unable to load research workspace");
    }
    return response.json();
}

async function loadWorkspaceFromServer() {
    try {
        const payload = await fetchWorkspaceSnapshot();
        workspaceState.collections = Array.isArray(payload.collections) ? payload.collections : [];
        workspaceState.studies = Array.isArray(payload.studies) ? payload.studies : [];
        const selectedStillExists = workspaceState.collections.some((collection) => collection.id === workspaceState.selectedCollectionId);
        if ((!workspaceState.selectedCollectionId || !selectedStillExists) && workspaceState.collections.length > 0) {
            workspaceState.selectedCollectionId = workspaceState.collections[0].id;
        }
        renderCollectionOptions();
        render();
    } catch (error) {
        workflowStatusMessage = `Research workspace unavailable: ${error.message}`;
        renderCollectionOptions();
        render();
    }
}

function renderCollectionOptions() {
    const collections = workspaceState.collections.length > 0
        ? workspaceState.collections
        : [{ id: "", name: "No collections yet" }];
    els.collectionSelect.innerHTML = collections.map((collection) => `
        <option value="${collection.id}" ${collection.id === workspaceState.selectedCollectionId ? "selected" : ""}>${collection.name}</option>
    `).join("");
}

function activeStudy() {
    return workspaceState.studies.find((study) => study.scenarioId === workspaceState.activeStudyId) || null;
}

function visibleStudies() {
    const filteredStudies = workspaceState.selectedCollectionId
        ? workspaceState.studies.filter((study) => study.collectionId === workspaceState.selectedCollectionId)
        : workspaceState.studies.slice();
    return filteredStudies
        .slice()
        .sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt));
}

function parsePersistedPayload(rawValue) {
    if (!rawValue || rawValue === "null") {
        return null;
    }
    if (typeof rawValue === "object") {
        return rawValue;
    }
    try {
        return JSON.parse(rawValue);
    } catch (error) {
        return null;
    }
}

function hydrateStoredAnalytics(study) {
    const fairValuePayload = parsePersistedPayload(study?.analysis?.fairValueJson);
    const forwardOutcomePayload = parsePersistedPayload(study?.analysis?.forwardOutcomeJson);
    const diagnosticsPayload = parsePersistedPayload(study?.analysis?.diagnosticsJson);

    latestFairValuePayload = fairValuePayload;
    latestForwardOutcomePayload = forwardOutcomePayload;
    latestDiagnosticsPayload = diagnosticsPayload;

    if (fairValuePayload) {
        applyFairValuePayload(fairValuePayload);
    }
    if (forwardOutcomePayload) {
        applyForwardOutcomePayload(forwardOutcomePayload);
    }
    if (diagnosticsPayload) {
        applyDiagnosticsPayload(diagnosticsPayload);
    }
}

function scenarioInputsFromForm() {
    return {
        underlying: els.underlying.value,
        optionType: selectedValue("optionType"),
        expiryType: selectedValue("expiryType"),
        dte: Math.max(0, Math.round(numberValue(els.dte))),
        spot: numberValue(els.spot),
        strike: numberValue(els.strike),
        distancePoints: numberValue(els.distancePoints),
        optionPrice: numberValue(els.optionPrice),
        activityBias: els.activityBias.value,
        researchNote: els.researchNote.value.trim()
    };
}

function applyScenarioInputs(inputs) {
    els.underlying.value = inputs.underlying || "NIFTY";
    const optionTypeInput = form.querySelector(`input[name="optionType"][value="${inputs.optionType || "CE"}"]`);
    const expiryTypeInput = form.querySelector(`input[name="expiryType"][value="${inputs.expiryType || "WEEKLY"}"]`);
    if (optionTypeInput) {
        optionTypeInput.checked = true;
    }
    if (expiryTypeInput) {
        expiryTypeInput.checked = true;
    }
    els.dte.value = String(inputs.dte ?? 0);
    els.spot.value = String(inputs.spot ?? 0);
    els.strike.value = String(inputs.strike ?? 0);
    els.distancePoints.value = String(inputs.distancePoints ?? ((inputs.strike ?? 0) - (inputs.spot ?? 0)));
    els.optionPrice.value = String(inputs.optionPrice ?? 0);
    els.activityBias.value = inputs.activityBias || "Balanced";
    els.researchNote.value = inputs.researchNote || "";
    syncDistanceFromStrike();
}

function formatTimestamp(value) {
    return new Intl.DateTimeFormat("en-IN", {
        day: "2-digit",
        month: "short",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit"
    }).format(new Date(value));
}

function recommendationBucket(analysis) {
    if (analysis.trustClass === "fragile") {
        return "uncertain";
    }
    if (analysis.decisionClass === "no-trade") {
        return analysis.fairnessState === "extreme" ? "uncertain" : "unattractive";
    }
    if (analysis.decisionClass === "long-premium") {
        if (analysis.fairnessState === "cheap" || analysis.fairnessState === "fair") {
            return "attractive";
        }
        return analysis.fairnessState === "extreme" ? "uncertain" : "unattractive";
    }
    if (analysis.fairnessState === "rich" || analysis.fairnessState === "fair") {
        return "attractive";
    }
    return analysis.fairnessState === "extreme" ? "uncertain" : "unattractive";
}

function recommendationCopy(bucket, analysis) {
    if (bucket === "attractive") {
        return analysis.decisionClass === "long-premium"
            ? "Historically constructive for long-premium follow-up with evidence that is still auditable."
            : "Historically constructive for short-premium follow-up with evidence that is still auditable.";
    }
    if (bucket === "unattractive") {
        return "Historical context does not reward forcing this expression without a materially different setup.";
    }
    return "The setup is interesting, but evidence quality or valuation alignment is not strong enough for clean conviction.";
}

function trustClassFromLevel(level) {
    if (level === "Strong") {
        return "strong";
    }
    if (level === "Weak") {
        return "fragile";
    }
    return "";
}

function createStudyRecord(mode = "save") {
    if (!currentScenarioAnalytics || !latestFairValuePayload || !latestForwardOutcomePayload || !latestDiagnosticsPayload) {
        return null;
    }

    const timestamp = new Date().toISOString();
    const inputs = scenarioInputsFromForm();
    const analysis = currentScenarioAnalytics;
    const existing = activeStudy();
    const cloneCount = workspaceState.studies.filter((study) => study.parentScenarioId === workspaceState.activeStudyId).length + 1;
    const persistedFairnessState = classifyFairness(Number(latestFairValuePayload.current?.percentile || 0));
    const persistedDecisionClass = opportunityClass(latestForwardOutcomePayload.opportunity?.label || "No clear edge");
    const persistedTrustClass = trustClassFromLevel(latestDiagnosticsPayload.diagnostics?.confidenceLevel || "");
    const persistedAnalysis = {
        ...analysis,
        fairnessState: persistedFairnessState,
        decisionClass: persistedDecisionClass,
        trustClass: persistedTrustClass
    };
    const bucket = recommendationBucket(persistedAnalysis);
    const selectedCollection = workspaceState.collections.find((collection) => collection.id === workspaceState.selectedCollectionId)
        || workspaceState.collections[0];

    const baseRecord = {
        id: mode === "save" && existing ? existing.scenarioId : `scenario-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        snapshotId: `snapshot-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        parentId: mode === "clone" ? workspaceState.activeStudyId : existing?.parentScenarioId || null,
        collectionId: selectedCollection?.id || "",
        collectionName: selectedCollection?.name || "Collection",
        title: mode === "clone"
            ? `${analysis.currentScenarioName} branch ${cloneCount}`
            : existing?.title || analysis.currentScenarioName,
        savedAt: timestamp,
        mode,
        inputs,
        analysis: {
            currentScenarioName: analysis.currentScenarioName,
            fairnessLabel: latestFairValuePayload.current.label,
            fairnessPercentile: latestFairValuePayload.current.percentile,
            fairnessState: persistedFairnessState,
            decisionPosture: latestForwardOutcomePayload.opportunity.label,
            decisionClass: persistedDecisionClass,
            trustLevel: latestDiagnosticsPayload.diagnostics.confidenceLevel,
            trustClass: persistedTrustClass,
            sampleSize: latestFairValuePayload.cohort.observationCount,
            cohortKey: analysis.cohortKey,
            dteBand: analysis.dteBand,
            recommendationBucket: bucket,
            recommendationText: recommendationCopy(bucket, persistedAnalysis),
            researchNote: inputs.researchNote,
            cohortStrength: latestFairValuePayload.cohort.strength,
            fairValueJson: JSON.stringify(latestFairValuePayload),
            forwardOutcomeJson: JSON.stringify(latestForwardOutcomePayload),
            diagnosticsJson: JSON.stringify(latestDiagnosticsPayload)
        }
    };

    return baseRecord;
}

async function postStudyRecord(record) {
    const body = new URLSearchParams({
        snapshotId: record.snapshotId,
        scenarioId: record.id,
        parentScenarioId: record.parentId || "",
        collectionId: record.collectionId,
        collectionName: record.collectionName,
        title: record.title,
        mode: record.mode,
        savedAt: record.savedAt,
        underlying: record.inputs.underlying,
        optionType: record.inputs.optionType,
        expiryType: record.inputs.expiryType,
        dte: String(record.inputs.dte),
        spot: String(record.inputs.spot),
        strike: String(record.inputs.strike),
        distancePoints: String(record.inputs.distancePoints),
        optionPrice: String(record.inputs.optionPrice),
        activityBias: record.inputs.activityBias,
        researchNote: record.inputs.researchNote,
        currentScenarioName: record.analysis.currentScenarioName,
        fairnessLabel: record.analysis.fairnessLabel,
        fairnessPercentile: String(record.analysis.fairnessPercentile),
        decisionPosture: record.analysis.decisionPosture,
        decisionClass: record.analysis.decisionClass,
        trustLevel: record.analysis.trustLevel,
        trustClass: record.analysis.trustClass,
        sampleSize: String(record.analysis.sampleSize),
        cohortKey: record.analysis.cohortKey,
        dteBand: record.analysis.dteBand,
        recommendationBucket: record.analysis.recommendationBucket,
        recommendationText: record.analysis.recommendationText,
        cohortStrength: record.analysis.cohortStrength,
        fairValueJson: record.analysis.fairValueJson,
        forwardOutcomeJson: record.analysis.forwardOutcomeJson,
        diagnosticsJson: record.analysis.diagnosticsJson
    });
    const response = await fetch(`${apiBase()}/api/workflow/studies`, {
        method: "POST",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
        },
        body
    });
    if (!response.ok) {
        const payload = await response.json().catch(() => ({}));
        throw new Error(payload.error || "Unable to save research study");
    }
    return response.json();
}

async function saveCurrentScenario() {
    const record = createStudyRecord("save");
    if (!record) {
        workflowStatusMessage = "Wait for historical valuation, outcomes, and diagnostics to finish loading before saving.";
        render();
        return;
    }
    try {
        await postStudyRecord(record);
        workspaceState.activeStudyId = record.id;
        workflowStatusMessage = `Saved "${record.title}" into "${record.collectionName}" with canonical historical outputs attached.`;
        await loadWorkspaceFromServer();
    } catch (error) {
        workflowStatusMessage = error.message;
        render();
    }
}

async function cloneCurrentScenario() {
    const record = createStudyRecord("clone");
    if (!record) {
        workflowStatusMessage = "Wait for historical valuation, outcomes, and diagnostics to finish loading before cloning.";
        render();
        return;
    }
    try {
        await postStudyRecord(record);
        workspaceState.activeStudyId = record.id;
        workflowStatusMessage = `Cloned the active setup into "${record.title}" inside "${record.collectionName}".`;
        await loadWorkspaceFromServer();
    } catch (error) {
        workflowStatusMessage = error.message;
        render();
    }
}

async function loadSavedStudy(studyId) {
    try {
        const response = await fetch(`${apiBase()}/api/workflow/studies/${studyId}`);
        if (!response.ok) {
            const payload = await response.json().catch(() => ({}));
            throw new Error(payload.error || "Unable to load saved study");
        }
        const study = await response.json();
        workspaceState.activeStudyId = study.scenarioId;
        workspaceState.selectedCollectionId = study.collectionId || workspaceState.selectedCollectionId;
        applyScenarioInputs(study.inputs);
        workflowStatusMessage = `Loaded "${study.title}" from "${study.collectionName}" for reproducible review.`;
        renderCollectionOptions();
        render();
        hydrateStoredAnalytics(study);
    } catch (error) {
        workflowStatusMessage = error.message;
        render();
    }
}

async function createCollection() {
    const name = els.collectionNameInput.value.trim();
    if (!name) {
        workflowStatusMessage = "Enter a collection name before creating a research collection.";
        render();
        return;
    }
    try {
        const response = await fetch(`${apiBase()}/api/workflow/collections`, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body: new URLSearchParams({ name })
        });
        if (!response.ok) {
            const payload = await response.json().catch(() => ({}));
            throw new Error(payload.error || "Unable to create research collection");
        }
        const created = await response.json();
        els.collectionNameInput.value = "";
        workflowStatusMessage = `Created research collection "${name}".`;
        await loadWorkspaceFromServer();
        workspaceState.selectedCollectionId = created.id || workspaceState.selectedCollectionId;
        renderCollectionOptions();
        render();
    } catch (error) {
        workflowStatusMessage = error.message;
        render();
    }
}

function syncDistanceFromStrike() {
    if (syncingFrom === "distance") {
        return;
    }
    syncingFrom = "strike";
    const distance = numberValue(els.strike) - numberValue(els.spot);
    els.distancePoints.value = Number.isFinite(distance) ? distance.toFixed(2).replace(/\.00$/, "") : "0";
    syncingFrom = null;
}

function syncStrikeFromDistance() {
    if (syncingFrom === "strike") {
        return;
    }
    syncingFrom = "distance";
    const strike = numberValue(els.spot) + numberValue(els.distancePoints);
    els.strike.value = Number.isFinite(strike) ? strike.toFixed(2).replace(/\.00$/, "") : "0";
    syncingFrom = null;
}

function dteBand(dte) {
    if (dte <= 2) return "0-2";
    if (dte <= 7) return "3-7";
    if (dte <= 21) return "8-21";
    return "22+";
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

function scheduleFairValueFetch(params) {
    fairValueRequestToken += 1;
    const requestToken = fairValueRequestToken;
    latestFairValuePayload = null;
    if (fairValueAbortController) {
        fairValueAbortController.abort();
    }
    fairValueAbortController = new AbortController();

    els.fairnessText.textContent = "Loading historical cohort pricing context from the canonical database.";
    els.distributionSummary.textContent = "Refreshing historical distribution and percentile placement for the resolved cohort.";

    const apiBase = window.location.protocol === "file:" ? "http://localhost:8080" : "";
    const query = new URLSearchParams({
        underlying: params.underlying,
        optionType: params.optionType,
        spot: String(params.spot),
        strike: String(params.strike),
        dte: String(params.dte),
        optionPrice: String(params.optionPrice)
    });

    fetch(`${apiBase}/api/fair-value?${query.toString()}`, {
        signal: fairValueAbortController.signal
    })
        .then((response) => {
            if (!response.ok) {
                return response.json().catch(() => ({})).then((payload) => {
                    throw new Error(payload.error || "Unable to load fair-value cohort data");
                });
            }
            return response.json();
        })
        .then((payload) => {
            if (requestToken !== fairValueRequestToken) {
                return;
            }
            applyFairValuePayload(payload);
        })
        .catch((error) => {
            if (error.name === "AbortError") {
                return;
            }
            if (requestToken !== fairValueRequestToken) {
                return;
            }
            els.fairnessText.textContent = "Canonical historical data is unavailable right now, so the panel is still showing placeholder context.";
            els.distributionSummary.textContent = error.message;
            els.cohortStrength.textContent = "Unavailable";
            els.cohortStrengthText.textContent = "The fair-value endpoint could not reach the canonical historical database for this cohort.";
        });
}

function applyFairValuePayload(payload) {
    latestFairValuePayload = payload;
    const percentile = Number(payload.current?.percentile || 0);
    const badgeState = classifyFairness(percentile);
    const min = Number(payload.distribution?.min || 0);
    const p25 = Number(payload.distribution?.p25 || 0);
    const median = Number(payload.distribution?.median || 0);
    const mean = Number(payload.distribution?.mean || 0);
    const p75 = Number(payload.distribution?.p75 || 0);
    const max = Number(payload.distribution?.max || 0);
    const optionPrice = Number(payload.current?.optionPrice || 0);
    const observationCount = Number(payload.cohort?.observationCount || 0);
    const currentMarkerLeft = clamp(((optionPrice - min) / Math.max(1, max - min)) * 100, 0, 100);

    els.valuationBadge.className = `valuation-badge ${badgeState}`;
    els.fairnessLabel.textContent = payload.current?.label || "Sparse";
    els.fairnessText.textContent = payload.current?.interpretation || "No exact historical cohort was found.";
    els.valuationCurrent.textContent = formatNumber(optionPrice, 2);
    els.valuationZone.textContent = `${formatNumber(p25, 2)} - ${formatNumber(p75, 2)}`;
    els.valuationPercentile.textContent = percentile > 0 ? `${percentile}th percentile` : "No percentile";
    els.distMin.textContent = formatNumber(min, 0);
    els.distP25.textContent = formatNumber(p25, 0);
    els.distMedian.textContent = formatNumber(median, 0);
    els.distP75.textContent = formatNumber(p75, 0);
    els.distMax.textContent = formatNumber(max, 0);
    els.currentMarker.style.left = `${currentMarkerLeft}%`;
    els.sampleSize.textContent = `${observationCount} observations`;
    els.sampleSizeText.textContent = observationCount > 0
        ? `Resolved from actual historical cohort rows in options_enriched. Historical mean ${formatNumber(mean, 2)}, median ${formatNumber(median, 2)}.`
        : "No exact historical rows were found for the resolved cohort.";
    els.sampleStrength.textContent = payload.cohort?.strength || "Sparse";
    els.sampleText.textContent = observationCount >= 320
        ? "This cohort has enough real historical observations to support a stable valuation read."
        : observationCount >= 160
            ? "This cohort is backed by real historical observations, but evidence depth is still mixed."
            : "This cohort is backed by real historical data, but the sample is thin enough to require caution.";
    els.cohortObservations.textContent = `${observationCount} observations`;
    els.cohortObservationsText.textContent = observationCount > 0
        ? "Comparable historical rows found in the resolved canonical cohort."
        : "No exact historical rows found in the resolved canonical cohort.";
    els.cohortStrength.textContent = payload.cohort?.strength || "Sparse";
    els.cohortStrengthText.textContent = observationCount >= 320
        ? "The cohort is backed by deep historical coverage and is strong enough for direct valuation interpretation."
        : observationCount >= 160
            ? "The cohort is backed by real historical data, but remains mixed rather than fully broad."
            : "The cohort is sparse, so the valuation read should be treated as fragile.";
    els.distributionSummary.textContent = observationCount > 0
        ? `Current price is ${payload.current.label.toLowerCase()} versus the real historical cohort, with mean ${formatNumber(mean, 2)}, median ${formatNumber(median, 2)}, and percentile bands drawn from actual observations.`
        : "The resolved cohort has no exact historical observations, so a fair-value judgment is not available.";
}

function scheduleForwardOutcomeFetch(params) {
    forwardOutcomeRequestToken += 1;
    const requestToken = forwardOutcomeRequestToken;
    latestForwardOutcomePayload = null;
    if (forwardOutcomeAbortController) {
        forwardOutcomeAbortController.abort();
    }
    forwardOutcomeAbortController = new AbortController();

    els.decisionRationale.textContent = "Loading real historical forward behavior for the resolved cohort.";
    els.outcomeDistributionSummary.textContent = "Refreshing next-day and expiry behavior from canonical historical matches.";

    const apiBase = window.location.protocol === "file:" ? "http://localhost:8080" : "";
    const query = new URLSearchParams({
        underlying: params.underlying,
        optionType: params.optionType,
        spot: String(params.spot),
        strike: String(params.strike),
        dte: String(params.dte)
    });

    fetch(`${apiBase}/api/forward-outcomes?${query.toString()}`, {
        signal: forwardOutcomeAbortController.signal
    })
        .then((response) => {
            if (!response.ok) {
                return response.json().catch(() => ({})).then((payload) => {
                    throw new Error(payload.error || "Unable to load forward-outcome data");
                });
            }
            return response.json();
        })
        .then((payload) => {
            if (requestToken !== forwardOutcomeRequestToken) {
                return;
            }
            applyForwardOutcomePayload(payload);
        })
        .catch((error) => {
            if (error.name === "AbortError") {
                return;
            }
            if (requestToken !== forwardOutcomeRequestToken) {
                return;
            }
            els.decisionRationale.textContent = "Canonical forward-outcome data is unavailable right now, so the panel is still showing placeholder behavior.";
            els.outcomeDistributionSummary.textContent = error.message;
        });
}

function opportunityClass(label) {
    if (label === "Long premium favored") {
        return "long-premium";
    }
    if (label === "Short premium favored") {
        return "short-premium";
    }
    return "no-trade";
}

function applyForwardOutcomePayload(payload) {
    latestForwardOutcomePayload = payload;
    const opportunityLabel = payload.opportunity?.label || "No clear edge";
    const className = opportunityClass(opportunityLabel);
    const next = payload.nextDay || {};
    const expiry = payload.expiry || {};
    const nextExpand = Number(next.expandProbabilityPct || 0);
    const nextFlat = Number(next.flatProbabilityPct || 0);
    const nextDecay = Number(next.decayProbabilityPct || 0);
    const expiryExpand = Number(expiry.expandProbabilityPct || 0);
    const expiryFlat = Number(expiry.flatProbabilityPct || 0);
    const expiryDecay = Number(expiry.decayProbabilityPct || 0);
    const shortMedian = ((Number(next.medianReturnPct || 0) * 0.6) + (Number(expiry.medianReturnPct || 0) * 0.4));
    const shortExpand = clamp(((nextExpand * 0.6) + (expiryExpand * 0.4)), 0, 100);
    const shortFlat = clamp(((nextFlat * 0.6) + (expiryFlat * 0.4)), 0, 100);
    const shortDecay = clamp(100 - shortExpand - shortFlat, 0, 100);
    const asymmetry = ((nextExpand - nextDecay) + (expiryExpand - expiryDecay)) / 100;
    const nextCount = Number(payload.cohort?.nextDayObservationCount || 0);
    const expiryCount = Number(payload.cohort?.expiryObservationCount || 0);

    els.outcomeCallout.className = `outcome-callout ${className}`;
    els.decisionPosture.textContent = opportunityLabel;
    els.decisionRationale.textContent = payload.opportunity?.interpretation || "Forward behavior is being evaluated from real historical matches.";
    els.nextStepBias.textContent = nextExpand > nextDecay ? "Expansion favored" : nextDecay > nextExpand ? "Decay favored" : "Balanced";
    els.asymmetryScore.textContent = `${asymmetry >= 0 ? "+" : ""}${asymmetry.toFixed(2)}`;
    els.decisionConfidence.textContent = nextCount >= 120 && expiryCount >= 120 ? "Historical" : nextCount >= 40 && expiryCount >= 40 ? "Limited historical" : "Thin historical";

    els.nextDayMedian.textContent = `${Number(next.medianReturnPct || 0) >= 0 ? "+" : ""}${Number(next.medianReturnPct || 0).toFixed(1)}%`;
    els.nextDayText.textContent = `Built from ${nextCount} real matched observations. Historical next-day behavior ${nextExpand > nextDecay ? "leaned toward expansion" : nextDecay > nextExpand ? "leaned toward decay" : "was balanced"}.`;
    setMiniBar("next-day-bars", [nextExpand, nextFlat, nextDecay]);
    els.nextDayUp.textContent = `${Math.round(nextExpand)}% expand`;
    els.nextDayFlat.textContent = `${Math.round(nextFlat)}% flat`;
    els.nextDayDown.textContent = `${Math.round(nextDecay)}% decay`;

    els.shortHorizonMedian.textContent = `${shortMedian >= 0 ? "+" : ""}${shortMedian.toFixed(1)}%`;
    els.shortHorizonText.textContent = "Short-horizon posture is summarized from the real next-day and expiry tendency split for the matched cohort.";
    setMiniBar("short-horizon-bars", [shortExpand, shortFlat, shortDecay]);
    els.shortUp.textContent = `${Math.round(shortExpand)}% expand`;
    els.shortFlat.textContent = `${Math.round(shortFlat)}% flat`;
    els.shortDown.textContent = `${Math.round(shortDecay)}% decay`;

    els.expiryHorizonMedian.textContent = `${Number(expiry.medianReturnPct || 0) >= 0 ? "+" : ""}${Number(expiry.medianReturnPct || 0).toFixed(1)}%`;
    els.expiryHorizonText.textContent = `Built from ${expiryCount} real matched expiry observations. Historical expiry behavior ${expiryExpand > expiryDecay ? "kept expansion favorable" : expiryDecay > expiryExpand ? "tilted toward premium decay" : "remained balanced"}.`;
    setMiniBar("expiry-horizon-bars", [expiryExpand, expiryFlat, expiryDecay]);
    els.expiryUp.textContent = `${Math.round(expiryExpand)}% expand`;
    els.expiryFlat.textContent = `${Math.round(expiryFlat)}% flat`;
    els.expiryDown.textContent = `${Math.round(expiryDecay)}% decay`;

    els.shapeNextRange.textContent = `${(Number(next.meanReturnPct || 0) - 12).toFixed(0)}% to ${(Number(next.meanReturnPct || 0) + 12).toFixed(0)}%`;
    els.shapeShortRange.textContent = `${(shortMedian - 18).toFixed(0)}% to ${(shortMedian + 18).toFixed(0)}%`;
    els.shapeExpiryRange.textContent = `${(Number(expiry.meanReturnPct || 0) - 24).toFixed(0)}% to ${(Number(expiry.meanReturnPct || 0) + 24).toFixed(0)}%`;
    setShapeBands(els.shapeNextLeft, els.shapeNextCenter, els.shapeNextRight, [Number(next.meanReturnPct || 0) - 12, Number(next.meanReturnPct || 0) + 12]);
    setShapeBands(els.shapeShortLeft, els.shapeShortCenter, els.shapeShortRight, [shortMedian - 18, shortMedian + 18]);
    setShapeBands(els.shapeExpiryLeft, els.shapeExpiryCenter, els.shapeExpiryRight, [Number(expiry.meanReturnPct || 0) - 24, Number(expiry.meanReturnPct || 0) + 24]);

    els.outcomeDistributionSummary.textContent =
        `${opportunityLabel} from real historical matches: next-day ${Math.round(nextExpand)}% expansion vs ${Math.round(nextDecay)}% decay, expiry ${Math.round(expiryExpand)}% expansion vs ${Math.round(expiryDecay)}% decay.`;

    els.stanceBody.innerHTML = `
        <tr>
            <td>Short premium?</td>
            <td>${opportunityLabel === "Short premium favored" ? "Favored" : opportunityLabel === "Long premium favored" ? "Pressured" : "No clear edge"}</td>
            <td>${opportunityLabel === "Short premium favored"
                ? "Real historical matches decayed more often than they expanded, especially into expiry."
                : opportunityLabel === "Long premium favored"
                    ? "Real historical matches showed enough expansion risk to make short premium fragile."
                    : "Real historical matches do not separate cleanly enough for confident short-premium framing."}</td>
        </tr>
        <tr>
            <td>Long premium?</td>
            <td>${opportunityLabel === "Long premium favored" ? "Favored" : opportunityLabel === "Short premium favored" ? "Pressured" : "No clear edge"}</td>
            <td>${opportunityLabel === "Long premium favored"
                ? "Real historical matches expanded more often than they decayed in the matched cohort."
                : opportunityLabel === "Short premium favored"
                    ? "Real historical matches did not usually reward premium ownership into the later path."
                    : "Real historical matches do not offer a clean ownership edge for premium."}</td>
        </tr>
        <tr>
            <td>No-trade?</td>
            <td>${opportunityLabel === "No clear edge" ? "Reasonable" : "Still valid if evidence thin"}</td>
            <td>${opportunityLabel === "No clear edge"
                ? "Observed historical behavior remains too mixed for a strong directional premium edge."
                : "If sample quality or regime fit is weak, no-trade remains the disciplined choice."}</td>
        </tr>
    `;
}

function trustCardClass(level) {
    if (level === "Strong") {
        return "trust-card strong";
    }
    if (level === "Weak") {
        return "trust-card fragile";
    }
    return "trust-card";
}

function scheduleDiagnosticsFetch(params) {
    diagnosticsRequestToken += 1;
    const requestToken = diagnosticsRequestToken;
    latestDiagnosticsPayload = null;
    if (diagnosticsAbortController) {
        diagnosticsAbortController.abort();
    }
    diagnosticsAbortController = new AbortController();

    els.trustText.textContent = "Loading real diagnostics and representative historical matches from canonical cohort data.";

    const apiBase = window.location.protocol === "file:" ? "http://localhost:8080" : "";
    const query = new URLSearchParams({
        underlying: params.underlying,
        optionType: params.optionType,
        spot: String(params.spot),
        strike: String(params.strike),
        dte: String(params.dte),
        optionPrice: String(params.optionPrice)
    });

    fetch(`${apiBase}/api/diagnostics?${query.toString()}`, {
        signal: diagnosticsAbortController.signal
    })
        .then((response) => {
            if (!response.ok) {
                return response.json().catch(() => ({})).then((payload) => {
                    throw new Error(payload.error || "Unable to load diagnostics data");
                });
            }
            return response.json();
        })
        .then((payload) => {
            if (requestToken !== diagnosticsRequestToken) {
                return;
            }
            applyDiagnosticsPayload(payload);
        })
        .catch((error) => {
            if (error.name === "AbortError") {
                return;
            }
            if (requestToken !== diagnosticsRequestToken) {
                return;
            }
            els.trustText.textContent = "Canonical diagnostics data is unavailable right now, so the transparency layer is still showing placeholder guidance.";
            els.caseList.innerHTML = `
                <article class="case-card">
                    <div class="case-head">
                        <strong>Diagnostics unavailable</strong>
                        <span class="match-score">Fallback</span>
                    </div>
                    <p class="case-context">${error.message}</p>
                    <p class="case-why">Start the local research server and ensure QuestDB is reachable to load real matched historical cases.</p>
                </article>
            `;
        });
}

function applyDiagnosticsPayload(payload) {
    latestDiagnosticsPayload = payload;
    const diagnostics = payload.diagnostics || {};
    const warnings = payload.warnings || [];
    const cases = payload.cases || [];
    const reasons = payload.comparabilityReasons || [];
    const observationCount = Number(payload.cohort?.observationCount || 0);

    els.trustCard.className = trustCardClass(diagnostics.confidenceLevel || "Weak");
    els.trustLevel.textContent = diagnostics.confidenceLevel || "Weak";
    els.trustText.textContent = diagnostics.confidenceText || "Historical evidence is currently weak.";
    els.diagCohortSize.textContent = String(observationCount);
    els.diagCohortSizeText.textContent = `Matched across ${payload.cohort?.uniqueTradeDateCount || 0} trade dates and ${payload.cohort?.uniqueInstrumentCount || 0} instruments.`;
    els.diagConcentration.textContent = diagnostics.concentrationLabel || "High";
    els.diagConcentrationText.textContent = diagnostics.concentrationText || "Matched history is concentrated into a narrow slice.";
    els.diagSparsity.textContent = diagnostics.sparsityLabel || "Elevated";
    els.diagSparsityText.textContent = diagnostics.sparsityText || "Matched history is sparse.";

    [els.warningChip1, els.warningChip2, els.warningChip3].forEach((chip, index) => {
        const warning = warnings[index] || "No additional warning";
        chip.textContent = warning;
        chip.className = `warning-chip ${/thin|sparse|elevated/i.test(warning) ? "risk" : /mixed|moderate|sensitive/i.test(warning) ? "warn" : ""}`.trim();
    });

    els.coverageNext.textContent = `${Math.round(Number(diagnostics.nextDayCoveragePct || 0))}%`;
    els.coverageShort.textContent = `${Math.round((Number(diagnostics.nextDayCoveragePct || 0) * 0.6) + (Number(diagnostics.expiryCoveragePct || 0) * 0.4))}%`;
    els.coverageExpiry.textContent = `${Math.round(Number(diagnostics.expiryCoveragePct || 0))}%`;
    els.compareList.innerHTML = reasons.map((reason) => `<li>${reason}</li>`).join("");

    els.caseTitle.textContent = `${payload.cohort?.underlying || "Historical"} representative historical matches`;
    els.caseList.innerHTML = cases.length > 0
        ? cases.map((item) => `
            <article class="case-card">
                <div class="case-head">
                    <strong>${item.tradeDate}</strong>
                    <span class="match-score">Match ${Number(item.matchScore).toFixed(2)}</span>
                </div>
                <p class="case-context">${item.context}</p>
                <div class="case-stats">
                    <span>Entry premium ${formatNumber(Number(item.entryPrice), 1)}</span>
                    <span>Next day ${item.nextDayReturnPct == null ? "n/a" : `${Number(item.nextDayReturnPct) >= 0 ? "+" : ""}${Number(item.nextDayReturnPct).toFixed(1)}%`}</span>
                    <span>Expiry ${item.expiryReturnPct == null ? "n/a" : `${Number(item.expiryReturnPct) >= 0 ? "+" : ""}${Number(item.expiryReturnPct).toFixed(1)}%`}</span>
                </div>
                <p class="case-why">${item.whyComparable}</p>
            </article>
        `).join("")
        : `
            <article class="case-card">
                <div class="case-head">
                    <strong>No representative cases</strong>
                    <span class="match-score">Sparse</span>
                </div>
                <p class="case-context">The resolved cohort does not have enough matched historical rows to surface representative cases.</p>
                <p class="case-why">This is a real-data sparsity outcome rather than a UI placeholder.</p>
            </article>
        `;
}

function interpretDte(expiryType, dte) {
    if (expiryType === "WEEKLY" && dte <= 2) {
        return "expiry-pressure weekly cohort with sharp gamma and premium decay behavior";
    }
    if (expiryType === "WEEKLY") {
        return "short-dated weekly expiry cohort where option response tends to be path-sensitive";
    }
    if (dte <= 7) {
        return "front-month monthly cohort where calendar decay starts to dominate intraday directional noise";
    }
    return "monthly carry cohort where premium response is usually steadier and more regime-dependent";
}

function moneynessLabel(optionType, points) {
    if (points === 0) return "ATM";
    if (optionType === "CE") return points > 0 ? "OTM call-style" : "ITM call-style";
    return points > 0 ? "ITM put-style" : "OTM put-style";
}

function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
}

function sampleProfile(underlying, expiryType, dte) {
    let base = underlying === "BANKNIFTY" ? 410 : 320;
    if (expiryType === "WEEKLY") {
        base += 60;
    }
    if (dte <= 2) {
        base -= 90;
    } else if (dte <= 7) {
        base += 20;
    } else if (dte >= 22) {
        base -= 70;
    }
    return Math.max(48, base);
}

function cohortStrengthProfile(sampleSize, activity) {
    if (sampleSize >= 320 && activity === "Balanced") {
        return {
            label: "Strong",
            text: "The resolved cohort is broad and clean enough to support stable historical comparison."
        };
    }
    if (sampleSize < 160) {
        return {
            label: "Sparse",
            text: "The resolved cohort is thin, so downstream analytics should be treated as exploratory rather than robust."
        };
    }
    return {
        label: "Mixed",
        text: "The resolved cohort is usable, but context discipline still matters because depth or regime balance is not fully clean."
    };
}

function fairnessState(percentile) {
    if (percentile <= 10 || percentile >= 90) {
        return "extreme";
    }
    if (percentile < 35) {
        return "cheap";
    }
    if (percentile > 65) {
        return "rich";
    }
    return "fair";
}

function fairnessCopy(state) {
    switch (state) {
        case "cheap":
            return {
                label: "Cheap",
                text: "Current premium is trading below the usual center of the cohort, suggesting softer pricing than comparable history."
            };
        case "rich":
            return {
                label: "Rich",
                text: "Current premium is leaning above the cohort midpoint, so history would frame it as somewhat elevated rather than neutral."
            };
        case "extreme":
            return {
                label: "Extreme",
                text: "Current premium is pressing into the edge of the historical distribution, which deserves extra scrutiny before trusting the read."
            };
        default:
            return {
                label: "Fair",
                text: "Current premium is sitting close to the cohort center of gravity."
            };
    }
}

function confidenceLabel(sampleSize, percentile) {
    const edgeDistance = Math.abs(percentile - 50);
    if (sampleSize >= 360 && edgeDistance <= 35) {
        return ["Strong evidence", "The cohort is deep enough that the fairness read is likely stable across ordinary regime noise."];
    }
    if (sampleSize >= 180) {
        return ["Moderate confidence", "The cohort is useful and directionally trustworthy, but regime mix still matters for interpretation."];
    }
    return ["Weak evidence", "Comparable history is thin, so treat this as an exploratory signal rather than a firm fairness conclusion."];
}

function horizonOutcomes(optionType, distance, dte, expiryType) {
    const directionBias = optionType === "CE" ? 0.06 : 0.03;
    const moneynessPenalty = Math.min(Math.abs(distance) / 900, 0.18);
    const weeklyBoost = expiryType === "WEEKLY" ? 0.05 : 0;
    const urgencyBoost = dte <= 3 ? 0.06 : dte <= 7 ? 0.03 : -0.02;

    const nextMedian = (directionBias + weeklyBoost + urgencyBoost - moneynessPenalty) * 100;
    const shortMedian = nextMedian + (weeklyBoost * 45) - (moneynessPenalty * 35) + 4;
    const expiryMedian = shortMedian - (dte <= 7 ? 12 : 7) - (expiryType === "WEEKLY" ? 5 : 0);

    const nextExpand = clamp(47 + nextMedian * 0.9, 20, 72);
    const nextFlat = clamp(24 - Math.abs(nextMedian) * 0.25, 10, 30);
    const nextDecay = clamp(100 - nextExpand - nextFlat, 12, 70);

    const shortExpand = clamp(50 + shortMedian * 0.75, 18, 76);
    const shortFlat = clamp(20 - Math.abs(shortMedian) * 0.18, 8, 24);
    const shortDecay = clamp(100 - shortExpand - shortFlat, 10, 72);

    const expiryExpand = clamp(38 + expiryMedian * 0.45, 8, 70);
    const expiryFlat = clamp(17 - Math.abs(expiryMedian) * 0.12, 6, 20);
    const expiryDecay = clamp(100 - expiryExpand - expiryFlat, 16, 82);

    return {
        next: {
            median: nextMedian,
            expand: nextExpand,
            flat: nextFlat,
            decay: nextDecay,
            range: [nextMedian - 16, nextMedian + 13]
        },
        short: {
            median: shortMedian,
            expand: shortExpand,
            flat: shortFlat,
            decay: shortDecay,
            range: [shortMedian - 22, shortMedian + 24]
        },
        expiry: {
            median: expiryMedian,
            expand: expiryExpand,
            flat: expiryFlat,
            decay: expiryDecay,
            range: [expiryMedian - 34, expiryMedian + 19]
        }
    };
}

function decisionPostureFromOutcomes(outcomes, sampleSize) {
    const longEdge = (outcomes.short.expand - outcomes.short.decay) + (outcomes.next.expand - outcomes.next.decay) * 0.5;
    const shortEdge = (outcomes.expiry.decay - outcomes.expiry.expand) + (outcomes.next.decay - outcomes.next.expand) * 0.25;
    const asymmetry = (longEdge - shortEdge) / 100;

    if (sampleSize < 120) {
        return {
            posture: "Lean no-trade",
            className: "no-trade",
            rationale: "The cohort is too thin to trust a directional premium posture, so history argues for patience over forced expression.",
            confidence: "Weak",
            nextBias: "Mixed",
            asymmetry
        };
    }

    if (longEdge > shortEdge + 6) {
        return {
            posture: "Lean long premium",
            className: "long-premium",
            rationale: "Comparable setups historically showed more upside expansion than clean decay, especially over the short horizon.",
            confidence: sampleSize >= 260 ? "Moderate-strong" : "Moderate",
            nextBias: "Expansion favored",
            asymmetry
        };
    }

    if (shortEdge > longEdge + 6) {
        return {
            posture: "Lean short premium",
            className: "short-premium",
            rationale: "Comparable setups more often bled premium than expanded, making decay capture historically more natural than chase exposure.",
            confidence: sampleSize >= 260 ? "Moderate-strong" : "Moderate",
            nextBias: "Decay favored",
            asymmetry
        };
    }

    return {
        posture: "Lean no-trade",
        className: "no-trade",
        rationale: "The cohort history is too balanced to offer a clean premium edge, so selectivity is the stronger historical posture.",
        confidence: "Moderate",
        nextBias: "Balanced",
        asymmetry
    };
}

function setMiniBar(containerId, values) {
    const container = document.getElementById(containerId);
    if (!container) {
        return;
    }
    const [expand, flat, decay] = values;
    container.innerHTML = `
        <div class="mini-bar positive" style="width:${expand}%"></div>
        <div class="mini-bar neutral" style="width:${flat}%"></div>
        <div class="mini-bar negative" style="width:${decay}%"></div>
    `;
}

function setShapeBands(leftEl, centerEl, rightEl, range) {
    const total = Math.abs(range[0]) + Math.abs(range[1]) + 18;
    const left = clamp((Math.abs(range[0]) / total) * 100, 12, 58);
    const center = clamp((18 / total) * 100, 12, 34);
    const right = clamp((Math.abs(range[1]) / total) * 100, 12, 58);
    leftEl.style.width = `${left}%`;
    centerEl.style.width = `${center}%`;
    rightEl.style.width = `${right}%`;
}

function diagnosticsProfile(sampleSize, dte, expiryType, activity) {
    const concentrationScore = clamp(
        0.52 + (expiryType === "WEEKLY" ? 0.08 : -0.03) + (dte <= 2 ? 0.12 : dte >= 22 ? 0.06 : 0) + (activity !== "Balanced" ? 0.05 : 0),
        0.24,
        0.91
    );
    const expiryCoverage = clamp(Math.round(82 - (expiryType === "WEEKLY" ? 6 : 0) - (dte <= 2 ? 10 : 0) + sampleSize / 35), 34, 98);
    const nextCoverage = clamp(Math.round(93 + sampleSize / 120), 62, 99);
    const shortCoverage = clamp(Math.round(87 + sampleSize / 150 - (dte >= 22 ? 6 : 0)), 52, 98);
    return {
        concentrationScore,
        nextCoverage,
        shortCoverage,
        expiryCoverage
    };
}

function trustProfile(sampleSize, concentrationScore, expiryCoverage) {
    if (sampleSize >= 300 && concentrationScore <= 0.64 && expiryCoverage >= 72) {
        return {
            level: "Robust for research use",
            className: "strong",
            text: "The cohort is broad, reasonably diversified, and well-covered across horizons, so the research conclusion is materially auditable."
        };
    }
    if (sampleSize >= 140) {
        return {
            level: "Usable with caution",
            className: "",
            text: "The cohort is directionally informative, but concentration and horizon coverage still matter for disciplined interpretation."
        };
    }
    return {
        level: "Fragile evidence",
        className: "fragile",
        text: "The cohort is too thin or concentrated to support strong trust. Use this as exploratory context, not robust evidence."
    };
}

function concentrationLabel(score) {
    if (score >= 0.78) {
        return ["High", "Observations are clustered into a narrower slice of history, so one regime can dominate the read."];
    }
    if (score >= 0.58) {
        return ["Moderate", "Observations are somewhat clustered, but not so tightly that one micro-regime dominates the read."];
    }
    return ["Low", "Matches are distributed across more varied historical settings, which supports a sturdier research conclusion."];
}

function sparsityLabel(sampleSize, expiryCoverage) {
    if (sampleSize < 120 || expiryCoverage < 55) {
        return ["Elevated", "Coverage is thin enough that the platform should explicitly warn against overconfidence."];
    }
    if (sampleSize < 220 || expiryCoverage < 70) {
        return ["Manageable", "Some historical slices are thin, but the conclusion is still usable with caution."];
    }
    return ["Contained", "The cohort is broad enough to avoid major sparsity warnings."];
}

function historicalCases(underlying, optionType, expiryType, bucket, dte, optionPrice, outcomes) {
    const baseDate = [
        "2024-02-14",
        "2024-06-27",
        "2025-01-09"
    ];
    const matches = [0.94, 0.89, 0.84];
    return baseDate.map((date, index) => {
        const premium = optionPrice * (0.96 + index * 0.04);
        const next = outcomes.next.median + (index - 1) * 3.1;
        const expiry = outcomes.expiry.median + (1 - index) * 4.4;
        return {
            date,
            score: matches[index],
            context: `${underlying} ${expiryType.toLowerCase()} ${optionType}, ${formatSigned(bucket, 0)} bucket, ${dte + index - 1} DTE.`,
            premium: formatNumber(premium, 1),
            next: `${next >= 0 ? "+" : ""}${next.toFixed(1)}%`,
            expiry: `${expiry >= 0 ? "+" : ""}${expiry.toFixed(1)}%`,
            why: index === 0
                ? "Closest on moneyness, DTE bucket, and premium-to-spot posture."
                : index === 1
                    ? "Very similar cohort geometry with slightly richer entry premium."
                    : "Same canonical bucket with a looser but still credible regime match."
        };
    });
}

function scenarioLabel(underlying, optionType, expiryType, bucket, dte) {
    return `${underlying} ${expiryType.toLowerCase()} ${optionType} / ${formatSigned(bucket, 0)} / ${dte} DTE`;
}

function render() {
    const underlying = els.underlying.value;
    const optionType = selectedValue("optionType");
    const expiryType = selectedValue("expiryType");
    const dte = Math.max(0, Math.round(numberValue(els.dte)));
    const spot = numberValue(els.spot);
    const strike = numberValue(els.strike);
    const optionPrice = numberValue(els.optionPrice);
    const distance = strike - spot;
    const moneynessPct = spot === 0 ? 0 : (distance / spot) * 100;
    const bucketSize = pointBucketSize[underlying] || 50;
    const bucket = Math.round(distance / bucketSize) * bucketSize;
    const premiumPctOfSpot = spot === 0 ? 0 : (optionPrice / spot) * 100;
    const activity = els.activityBias.value;
    const styleLabel = moneynessLabel(optionType, distance);
    const dteDescription = interpretDte(expiryType, dte);
    const sampleSize = sampleProfile(underlying, expiryType, dte);
    const timeBucket15m = canonicalTimeBucket(dte);
    const centerPrice = Math.max(1, Math.abs(distance) * (underlying === "BANKNIFTY" ? 0.58 : 0.46) + Math.max(6, dte * 4.8));
    const p25 = Math.max(0.5, centerPrice * 0.89);
    const median = centerPrice;
    const p75 = centerPrice * 1.11;
    const min = centerPrice * 0.62;
    const max = centerPrice * 1.52;
    const percentile = Math.round(clamp(((optionPrice - min) / Math.max(1, max - min)) * 100, 1, 99));
    const state = fairnessState(percentile);
    const fairness = fairnessCopy(state);
    const [confidenceTitle, confidenceText] = confidenceLabel(sampleSize, percentile);
    const currentMarkerLeft = clamp(((optionPrice - min) / Math.max(1, max - min)) * 100, 0, 100);
    const outcomes = horizonOutcomes(optionType, distance, dte, expiryType);
    const decision = decisionPostureFromOutcomes(outcomes, sampleSize);
    const diagnostics = diagnosticsProfile(sampleSize, dte, expiryType, activity);
    const trust = trustProfile(sampleSize, diagnostics.concentrationScore, diagnostics.expiryCoverage);
    const [concentrationTitle, concentrationText] = concentrationLabel(diagnostics.concentrationScore);
    const [sparsityTitle, sparsityText] = sparsityLabel(sampleSize, diagnostics.expiryCoverage);
    const cohortStrength = cohortStrengthProfile(sampleSize, activity);
    const cases = historicalCases(underlying, optionType, expiryType, bucket, dte, optionPrice, outcomes);
    const currentScenarioName = scenarioLabel(underlying, optionType, expiryType, bucket, dte);
    const currentAnalysis = {
        currentScenarioName,
        fairnessLabel: fairness.label,
        fairnessState: state,
        decisionPosture: decision.posture,
        decisionClass: decision.className,
        trustLevel: trust.level,
        trustClass: trust.className,
        sampleSize,
        cohortKey: `${underlying} / ${optionType} / TB${timeBucket15m} / ${formatSigned(bucket, 0)}`,
        dteBand: dteBand(dte)
    };
    currentScenarioAnalytics = currentAnalysis;

    els.moneynessSummary.textContent = `${formatSigned(distance, 0)} pts / ${formatSigned(moneynessPct, 2)}%`;
    els.moneynessText.textContent =
        `Strike sits ${formatSigned(distance, 0)} points versus spot, so the setup normalizes into a ${styleLabel} distance band for historical comparison.`;

    els.dteSummary.textContent = `${dte} DTE`;
    els.dteText.textContent =
        `This falls into a ${dteDescription}.`;

    els.premiumSummary.textContent = `${formatNumber(premiumPctOfSpot, 2)}% of spot`;
    els.premiumText.textContent =
        `Current premium of ${formatNumber(optionPrice, 2)} is ${formatNumber(premiumPctOfSpot, 2)}% of spot, which helps frame relative richness inside the cohort.`;

    els.cohortKey.textContent =
        `${underlying} / ${optionType} / TB${timeBucket15m} / ${formatSigned(bucket, 0)} bucket`;
    els.cohortText.textContent =
        `The platform would anchor this setup to the canonical option context bucket using underlying, option type, time_bucket_15m, and moneyness_bucket, with "${activity}" available only as an optional research overlay.`;
    els.canonicalTimeSummary.textContent = `TB${timeBucket15m} (${formatNumber(estimatedMinutesToExpiry(dte), 0)} min est.)`;
    els.canonicalTimeText.textContent =
        `Days-to-expiry is converted into the platform's canonical time context as time_bucket_15m=${timeBucket15m}, then carried forward into cohort resolution.`;
    els.cohortResolutionSummary.textContent = `${underlying} / ${optionType} / TB${timeBucket15m} / ${formatSigned(bucket, 0)}`;
    els.cohortResolutionText.textContent =
        `This scenario maps to the same historical context key shape used by options_context_buckets: underlying + option_type + time_bucket_15m + moneyness_bucket.`;
    els.cohortObservations.textContent = `${sampleSize} observations`;
    els.cohortObservationsText.textContent =
        sampleSize >= 320
            ? "This resolved cohort has enough rows to give downstream analytics a sturdy footing."
            : sampleSize >= 160
                ? "This resolved cohort is usable, but should still be read with some regime awareness."
                : "This resolved cohort is thin, so all later reads should carry explicit sparsity caution.";
    els.cohortStrength.textContent = cohortStrength.label;
    els.cohortStrengthText.textContent = cohortStrength.text;

    els.exactContext.textContent =
        `${underlying} ${expiryType.toLowerCase()} ${optionType}, ${dte} DTE, strike ${formatSigned(distance, 0)} points versus spot, premium ${formatNumber(optionPrice, 2)}.`;
    els.interpretedContext.textContent =
        `Normalized to a ${formatSigned(bucket, 0)} moneyness bucket and resolved into time_bucket_15m=${timeBucket15m} from the ${dteBand(dte)} DTE band before historical cohort lookup.`;
    els.queryShape.textContent =
        `underlying=${underlying}, option_type=${optionType}, time_bucket_15m=${timeBucket15m}, moneyness_bucket=${formatSigned(bucket, 0)}`;

    scheduleFairValueFetch({
        underlying,
        optionType,
        spot,
        strike,
        dte,
        optionPrice
    });
    scheduleForwardOutcomeFetch({
        underlying,
        optionType,
        spot,
        strike,
        dte
    });
    scheduleDiagnosticsFetch({
        underlying,
        optionType,
        spot,
        strike,
        dte,
        optionPrice
    });

    els.valuationBadge.className = `valuation-badge ${state}`;
    els.fairnessLabel.textContent = fairness.label;
    els.fairnessText.textContent = fairness.text;
    els.valuationCurrent.textContent = formatNumber(optionPrice, 2);
    els.valuationZone.textContent = `${formatNumber(p25, 2)} - ${formatNumber(p75, 2)}`;
    els.valuationPercentile.textContent = `${percentile}th percentile`;
    els.distributionTitle.textContent = `${underlying} comparable premium range`;
    els.distributionSummary.textContent =
        `The current premium is ${state === "fair" ? "inside" : state === "rich" ? "above" : state === "cheap" ? "below" : "at the edge of"} the core historical band for this cohort, based on normalized moneyness and DTE comparables.`;
    els.distMin.textContent = formatNumber(min, 0);
    els.distP25.textContent = formatNumber(p25, 0);
    els.distMedian.textContent = formatNumber(median, 0);
    els.distP75.textContent = formatNumber(p75, 0);
    els.distMax.textContent = formatNumber(max, 0);
    els.currentMarker.style.left = `${currentMarkerLeft}%`;
    els.sampleStrength.textContent = confidenceTitle;
    els.sampleText.textContent = confidenceText;
    els.sampleSize.textContent = `${sampleSize} observations`;
    els.sampleSizeText.textContent =
        sampleSize >= 300
            ? "A broad comparable set supports a more stable fairness read."
            : sampleSize >= 150
                ? "Useful depth, though the conclusion should still be checked against regime context."
                : "Thin cohort depth means the valuation read is more fragile.";
    els.biasCheck.textContent = activity === "Balanced" ? "Clean cohort read" : "Watch overlay drift";
    els.biasText.textContent =
        activity === "Balanced"
            ? "Selected context is close to the canonical pricing lens, so interpretation noise is lower."
            : `${activity} can explain part of the premium location, but the base fairness read should still come from the canonical cohort.`;

    els.outcomeCallout.className = `outcome-callout ${decision.className}`;
    els.decisionPosture.textContent = decision.posture;
    els.decisionRationale.textContent = decision.rationale;
    els.nextStepBias.textContent = decision.nextBias;
    els.asymmetryScore.textContent = `${decision.asymmetry >= 0 ? "+" : ""}${decision.asymmetry.toFixed(2)}`;
    els.decisionConfidence.textContent = decision.confidence;

    els.nextDayMedian.textContent = `${outcomes.next.median >= 0 ? "+" : ""}${outcomes.next.median.toFixed(1)}%`;
    els.nextDayText.textContent = `The next-day move historically ${outcomes.next.expand > outcomes.next.decay ? "leaned positive" : "leaned toward decay"}, but still carried meaningful two-sided noise.`;
    setMiniBar("next-day-bars", [outcomes.next.expand, outcomes.next.flat, outcomes.next.decay]);
    els.nextDayUp.textContent = `${Math.round(outcomes.next.expand)}% expand`;
    els.nextDayFlat.textContent = `${Math.round(outcomes.next.flat)}% flat`;
    els.nextDayDown.textContent = `${Math.round(outcomes.next.decay)}% decay`;

    els.shortHorizonMedian.textContent = `${outcomes.short.median >= 0 ? "+" : ""}${outcomes.short.median.toFixed(1)}%`;
    els.shortHorizonText.textContent = `Across the first few sessions, historical outcomes ${outcomes.short.expand > outcomes.short.decay ? "showed stronger skew toward premium follow-through" : "tilted toward premium bleed rather than continuation"}.`;
    setMiniBar("short-horizon-bars", [outcomes.short.expand, outcomes.short.flat, outcomes.short.decay]);
    els.shortUp.textContent = `${Math.round(outcomes.short.expand)}% expand`;
    els.shortFlat.textContent = `${Math.round(outcomes.short.flat)}% flat`;
    els.shortDown.textContent = `${Math.round(outcomes.short.decay)}% decay`;

    els.expiryHorizonMedian.textContent = `${outcomes.expiry.median >= 0 ? "+" : ""}${outcomes.expiry.median.toFixed(1)}%`;
    els.expiryHorizonText.textContent = `Into expiry, the cohort ${outcomes.expiry.decay > outcomes.expiry.expand ? "still retained substantial decay pressure" : "kept enough expansion optionality to avoid a clean decay profile"}.`;
    setMiniBar("expiry-horizon-bars", [outcomes.expiry.expand, outcomes.expiry.flat, outcomes.expiry.decay]);
    els.expiryUp.textContent = `${Math.round(outcomes.expiry.expand)}% expand`;
    els.expiryFlat.textContent = `${Math.round(outcomes.expiry.flat)}% flat`;
    els.expiryDown.textContent = `${Math.round(outcomes.expiry.decay)}% decay`;

    els.outcomeShapeTitle.textContent = `${underlying} forward premium distribution by horizon`;
    setShapeBands(els.shapeNextLeft, els.shapeNextCenter, els.shapeNextRight, outcomes.next.range);
    setShapeBands(els.shapeShortLeft, els.shapeShortCenter, els.shapeShortRight, outcomes.short.range);
    setShapeBands(els.shapeExpiryLeft, els.shapeExpiryCenter, els.shapeExpiryRight, outcomes.expiry.range);
    els.shapeNextRange.textContent = `${outcomes.next.range[0].toFixed(0)}% to ${outcomes.next.range[1].toFixed(0)}%`;
    els.shapeShortRange.textContent = `${outcomes.short.range[0].toFixed(0)}% to ${outcomes.short.range[1].toFixed(0)}%`;
    els.shapeExpiryRange.textContent = `${outcomes.expiry.range[0].toFixed(0)}% to ${outcomes.expiry.range[1].toFixed(0)}%`;
    els.outcomeDistributionSummary.textContent =
        decision.className === "long-premium"
            ? "The opportunity profile is front-loaded: early horizons reward premium expansion more often than they punish it, even though expiry decay still matters."
            : decision.className === "short-premium"
                ? "The opportunity profile is decay-weighted: upside continuation exists, but the balance of history leans toward premium bleed over time."
                : "The opportunity profile is mixed: horizon behavior is too split to reduce this setup to one clean premium stance without stronger cohort evidence.";

    els.stanceBody.innerHTML = `
        <tr>
            <td>Short premium?</td>
            <td>${decision.className === "short-premium" ? "Constructive" : decision.className === "long-premium" ? "Caution" : "Selective only"}</td>
            <td>${decision.className === "short-premium"
                ? "Historical decay dominance makes short-premium structures more defensible than average."
                : decision.className === "long-premium"
                    ? "Early expansion risk was high enough to make clean short-premium expression fragile."
                    : "Short-premium expression needs stronger regime confirmation because the base cohort is mixed."}</td>
        </tr>
        <tr>
            <td>Long premium?</td>
            <td>${decision.className === "long-premium" ? "Constructive" : decision.className === "short-premium" ? "Caution" : "Selective only"}</td>
            <td>${decision.className === "long-premium"
                ? "Early forward path historically favored upside premium response."
                : decision.className === "short-premium"
                    ? "History does not usually reward premium chasing here unless there is a strong catalyst mismatch."
                    : "Long-premium upside exists, but not with enough consistency to stand alone."}</td>
        </tr>
        <tr>
            <td>No-trade?</td>
            <td>${decision.className === "no-trade" ? "Historically sensible" : "Still valid if evidence thin"}</td>
            <td>${decision.className === "no-trade"
                ? "The cohort does not offer a stable enough edge, so historical discipline favors selectivity."
                : "If regime match is weak, defer to sample quality instead of forcing the setup."}</td>
        </tr>
    `;

    els.trustCard.className = `trust-card ${trust.className}`.trim();
    els.trustLevel.textContent = trust.level;
    els.trustText.textContent = trust.text;
    els.diagCohortSize.textContent = String(sampleSize);
    els.diagCohortSizeText.textContent = "Comparable historical observations in the canonical cohort.";
    els.diagConcentration.textContent = concentrationTitle;
    els.diagConcentrationText.textContent = concentrationText;
    els.diagSparsity.textContent = sparsityTitle;
    els.diagSparsityText.textContent = sparsityText;

    els.warningChip1.className = `warning-chip ${sampleSize < 140 ? "risk" : sampleSize < 220 ? "warn" : ""}`.trim();
    els.warningChip1.textContent = sampleSize < 140 ? "Small cohort warning" : sampleSize < 220 ? "Moderate depth warning" : "No major sparsity warning";
    els.warningChip2.className = `warning-chip ${diagnostics.expiryCoverage < 55 ? "risk" : diagnostics.expiryCoverage < 72 ? "warn" : ""}`.trim();
    els.warningChip2.textContent = diagnostics.expiryCoverage < 55 ? "Expiry coverage thin" : diagnostics.expiryCoverage < 72 ? "Expiry coverage moderate" : `${expiryType.charAt(0)}${expiryType.slice(1).toLowerCase()} expiry coverage adequate`;
    els.warningChip3.className = `warning-chip ${diagnostics.concentrationScore >= 0.78 ? "risk" : diagnostics.concentrationScore >= 0.58 ? "warn" : ""}`.trim();
    els.warningChip3.textContent = diagnostics.concentrationScore >= 0.78 ? "Concentration risk elevated" : diagnostics.concentrationScore >= 0.58 ? "Outcome read still regime-sensitive" : "Regime concentration controlled";

    els.coverageNext.textContent = `${diagnostics.nextCoverage}%`;
    els.coverageShort.textContent = `${diagnostics.shortCoverage}%`;
    els.coverageExpiry.textContent = `${diagnostics.expiryCoverage}%`;
    els.compareList.innerHTML = `
        <li>Same underlying and option orientation</li>
        <li>Same expiry family and near-identical DTE bucket</li>
        <li>Moneyness mapped into the same canonical bucket</li>
        <li>Premium posture compared relative to spot, not in isolation</li>
        <li>${activity === "Balanced" ? "Activity context left broad to preserve cohort depth" : `Activity overlay "${activity}" applied only as a secondary refinement`}</li>
    `;

    els.caseTitle.textContent = `${underlying} representative historical matches`;
    els.caseList.innerHTML = cases.map((item) => `
        <article class="case-card">
            <div class="case-head">
                <strong>${item.date}</strong>
                <span class="match-score">Match ${item.score.toFixed(2)}</span>
            </div>
            <p class="case-context">${item.context}</p>
            <div class="case-stats">
                <span>Entry premium ${item.premium}</span>
                <span>Next day ${item.next}</span>
                <span>Expiry ${item.expiry}</span>
            </div>
            <p class="case-why">${item.why}</p>
        </article>
    `).join("");

    const savedStudies = visibleStudies();

    const compareRows = [
        {
            title: `${currentScenarioName} (live)`,
            fairnessLabel: fairness.label,
            decisionPosture: decision.posture,
            trustLevel: trust.level
        },
        ...savedStudies.slice(0, 3).map((study) => ({
            title: study.title,
            fairnessLabel: study.analysis.fairnessLabel,
            decisionPosture: study.analysis.decisionPosture,
            trustLevel: study.analysis.trustLevel
        }))
    ];

    els.compareBoardBody.innerHTML = compareRows.map((row) => `
        <tr>
            <td>${row.title}</td>
            <td>${row.fairnessLabel}</td>
            <td>${row.decisionPosture}</td>
            <td>${row.trustLevel}</td>
        </tr>
    `).join("");

    if (savedStudies.length === 0) {
        els.workspaceList.innerHTML = `
            <article class="workspace-item">
                <div class="workspace-copy">
                    <strong>No saved studies yet</strong>
                    <p>Save the live scenario to start building a reusable opportunity workspace around canonical historical context.</p>
                </div>
                <div class="workspace-meta">
                    <span class="workspace-tag">Ready</span>
                </div>
            </article>
        `;
    } else {
        els.workspaceList.innerHTML = savedStudies.slice(0, 4).map((study) => `
            <article class="workspace-item ${study.scenarioId === workspaceState.activeStudyId ? "active" : ""}">
                <div class="workspace-copy">
                    <strong>${study.title}</strong>
                    <p>${study.analysis.fairnessLabel || "Mixed"} valuation, ${(study.analysis.decisionPosture || "Lean no-trade").toLowerCase()}, ${(study.analysis.trustLevel || "Usable with caution").toLowerCase()}. Updated ${formatTimestamp(study.updatedAt)}.</p>
                </div>
                <div class="workspace-meta">
                    <span class="workspace-tag">${(study.analysis.recommendationBucket || "uncertain") === "attractive" ? "Priority" : (study.analysis.recommendationBucket || "uncertain") === "unattractive" ? "Avoid" : "Review"}</span>
                    <button class="workspace-action" type="button" data-load-study="${study.scenarioId}">Load Study</button>
                </div>
            </article>
        `).join("");
    }

    const boardSeed = [
        {
            title: currentScenarioName,
            text: recommendationCopy(recommendationBucket(currentAnalysis), currentAnalysis),
            bucket: recommendationBucket(currentAnalysis)
        },
        ...savedStudies.slice(0, 5).map((study) => ({
            title: study.title,
            text: study.analysis.recommendationText || "Historical context has been saved for this study and can be revisited in the builder.",
            bucket: study.analysis.recommendationBucket || "uncertain"
        }))
    ];

    const buckets = {
        attractive: boardSeed.filter((item) => item.bucket === "attractive"),
        uncertain: boardSeed.filter((item) => item.bucket === "uncertain"),
        unattractive: boardSeed.filter((item) => item.bucket === "unattractive")
    };

    const renderOpportunityStack = (items, emptyTitle, emptyText) => items.length > 0
        ? items.map((item) => `
            <article class="opportunity-tile">
                <strong>${item.title}</strong>
                <p>${item.text}</p>
            </article>
        `).join("")
        : `
            <article class="opportunity-tile">
                <strong>${emptyTitle}</strong>
                <p>${emptyText}</p>
            </article>
        `;

    els.attractiveStack.innerHTML = renderOpportunityStack(
        buckets.attractive,
        "Awaiting attractive studies",
        "As saved scenarios accumulate, historically aligned premium opportunities will surface here."
    );
    els.uncertainStack.innerHTML = renderOpportunityStack(
        buckets.uncertain,
        "No uncertain studies yet",
        "Contexts with mixed evidence, thin samples, or conflicting valuation/outcome signals will collect here."
    );
    els.unattractiveStack.innerHTML = renderOpportunityStack(
        buckets.unattractive,
        "No unattractive studies yet",
        "Historically weak or low-conviction contexts will be parked here to avoid forcing marginal ideas."
    );

    const journalEntries = [
        {
            title: `Live study: ${currentScenarioName}`,
            text: `${fairness.label} valuation, ${decision.posture.toLowerCase()}, and ${trust.level.toLowerCase()} evidence quality are active in the builder right now.`,
            meta: `Canonical cohort: ${currentAnalysis.cohortKey}`
        },
        ...savedStudies.slice(0, 3).map((study) => ({
            title: study.mode === "clone" ? `Comparison branch: ${study.title}` : `Saved study: ${study.title}`,
            text: study.analysis.researchNote || study.analysis.recommendationText || "Study saved for later comparison.",
            meta: `${formatTimestamp(study.updatedAt)} · ${study.analysis.cohortKey || "Canonical cohort retained in workspace"}`
        }))
    ];

    els.journalList.innerHTML = journalEntries.map((entry) => `
        <article class="journal-entry">
            <strong>${entry.title}</strong>
            <p>${entry.text}</p>
            <span>${entry.meta}</span>
        </article>
    `).join("");

    els.workflowStatus.textContent = workflowStatusMessage;

    els.cohortBody.innerHTML = `
        <tr>
            <td>Underlying</td>
            <td>${underlying}</td>
            <td>Canonical partition and cohort scope anchor</td>
        </tr>
        <tr>
            <td>Option orientation</td>
            <td>${optionType} / ${expiryType}</td>
            <td>Separates weekly and monthly response curves before comparison</td>
        </tr>
        <tr>
            <td>Moneyness</td>
            <td>${formatSigned(distance, 0)} points (${formatSigned(moneynessPct, 2)}%)</td>
            <td>Rounded into ${formatSigned(bucket, 0)} canonical bucket for cross-contract comparability</td>
        </tr>
        <tr>
            <td>DTE</td>
            <td>${dte} days</td>
            <td>Mapped into ${dteBand(dte)} short-horizon bucket for historical context lookup</td>
        </tr>
        <tr>
            <td>Premium stance</td>
            <td>${formatNumber(optionPrice, 2)} (${formatNumber(premiumPctOfSpot, 2)}% of spot)</td>
            <td>Used as observed scenario state, then compared against historical cohort behavior</td>
        </tr>
        <tr>
            <td>Activity lens</td>
            <td>${activity}</td>
            <td>Optional secondary research slice, not part of pricing truth</td>
        </tr>
    `;
}

syncDistanceFromStrike();
renderCollectionOptions();
render();
loadWorkspaceFromServer();
