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
    workflowStatus: document.getElementById("workflow-status")
};

const pointBucketSize = {
    NIFTY: 50,
    BANKNIFTY: 100
};

const workspaceStorageKey = "strategy-squad-scenario-research-v2";

let workspaceState = loadWorkspaceState();
let currentScenarioAnalytics = null;
let syncingFrom = null;
let workflowStatusMessage = "Current scenario is live in the builder and ready to be saved into the research workspace.";

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
    workflowStatusMessage = "Opportunity board refreshed from the current scenario and saved study set.";
    render();
});
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

function loadWorkspaceState() {
    try {
        const raw = window.localStorage.getItem(workspaceStorageKey);
        if (!raw) {
            return {
                studies: [],
                activeStudyId: null
            };
        }
        const parsed = JSON.parse(raw);
        return {
            studies: Array.isArray(parsed.studies) ? parsed.studies : [],
            activeStudyId: parsed.activeStudyId || null
        };
    } catch (error) {
        return {
            studies: [],
            activeStudyId: null
        };
    }
}

function persistWorkspaceState() {
    try {
        window.localStorage.setItem(workspaceStorageKey, JSON.stringify(workspaceState));
    } catch (error) {
        // Keep the workstation usable even when persistence is unavailable.
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

function createStudyRecord(mode = "save") {
    if (!currentScenarioAnalytics) {
        return null;
    }

    const timestamp = new Date().toISOString();
    const inputs = scenarioInputsFromForm();
    const analysis = currentScenarioAnalytics;
    const existing = workspaceState.studies.find((study) => study.id === workspaceState.activeStudyId);
    const cloneCount = workspaceState.studies.filter((study) => study.parentId === workspaceState.activeStudyId).length + 1;
    const bucket = recommendationBucket(analysis);

    const baseRecord = {
        id: mode === "save" && existing ? existing.id : `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        parentId: mode === "clone" ? workspaceState.activeStudyId : existing?.parentId || null,
        title: mode === "clone"
            ? `${analysis.currentScenarioName} branch ${cloneCount}`
            : existing?.title || analysis.currentScenarioName,
        updatedAt: timestamp,
        createdAt: existing?.createdAt || timestamp,
        mode,
        inputs,
        analysis: {
            currentScenarioName: analysis.currentScenarioName,
            fairnessLabel: analysis.fairnessLabel,
            fairnessState: analysis.fairnessState,
            decisionPosture: analysis.decisionPosture,
            decisionClass: analysis.decisionClass,
            trustLevel: analysis.trustLevel,
            trustClass: analysis.trustClass,
            sampleSize: analysis.sampleSize,
            cohortKey: analysis.cohortKey,
            dteBand: analysis.dteBand,
            recommendationBucket: bucket,
            recommendationText: recommendationCopy(bucket, analysis),
            researchNote: inputs.researchNote
        }
    };

    return baseRecord;
}

function saveCurrentScenario() {
    const record = createStudyRecord("save");
    if (!record) {
        return;
    }

    const index = workspaceState.studies.findIndex((study) => study.id === record.id);
    if (index >= 0) {
        workspaceState.studies[index] = record;
        workflowStatusMessage = `Updated "${record.title}" in the research workspace.`;
    } else {
        workspaceState.studies.unshift(record);
        workflowStatusMessage = `Saved "${record.title}" into the research workspace.`;
    }
    workspaceState.activeStudyId = record.id;
    persistWorkspaceState();
    render();
}

function cloneCurrentScenario() {
    const record = createStudyRecord("clone");
    if (!record) {
        return;
    }

    workspaceState.studies.unshift(record);
    workspaceState.activeStudyId = record.id;
    persistWorkspaceState();
    workflowStatusMessage = `Cloned the active setup into "${record.title}" for side-by-side research.`;
    render();
}

function loadSavedStudy(studyId) {
    const study = workspaceState.studies.find((item) => item.id === studyId);
    if (!study) {
        return;
    }
    workspaceState.activeStudyId = study.id;
    applyScenarioInputs(study.inputs);
    persistWorkspaceState();
    workflowStatusMessage = `Loaded "${study.title}" back into the builder for review and iteration.`;
    render();
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
        cohortKey: `${underlying} / ${optionType} / ${expiryType} / ${formatSigned(bucket, 0)} / ${dteBand(dte)}`,
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
        `${underlying} / ${optionType} / ${expiryType} / ${dte} DTE / ${formatSigned(bucket, 0)} bucket`;
    els.cohortText.textContent =
        `The platform would anchor this setup to the canonical option context bucket, with "${activity}" available only as an optional research overlay.`;

    els.exactContext.textContent =
        `${underlying} ${expiryType.toLowerCase()} ${optionType}, ${dte} DTE, strike ${formatSigned(distance, 0)} points versus spot, premium ${formatNumber(optionPrice, 2)}.`;
    els.interpretedContext.textContent =
        `Normalized to a ${formatSigned(bucket, 0)} moneyness bucket and ${dteBand(dte)} DTE band before historical cohort lookup.`;
    els.queryShape.textContent =
        `underlying=${underlying}, option_type=${optionType}, expiry_type=${expiryType}, moneyness_bucket=${formatSigned(bucket, 0)}, dte_bucket=${dteBand(dte)}`;

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

    const savedStudies = workspaceState.studies
        .slice()
        .sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt));

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
            <article class="workspace-item ${study.id === workspaceState.activeStudyId ? "active" : ""}">
                <div class="workspace-copy">
                    <strong>${study.title}</strong>
                    <p>${study.analysis.fairnessLabel || "Mixed"} valuation, ${(study.analysis.decisionPosture || "Lean no-trade").toLowerCase()}, ${(study.analysis.trustLevel || "Usable with caution").toLowerCase()}. Updated ${formatTimestamp(study.updatedAt)}.</p>
                </div>
                <div class="workspace-meta">
                    <span class="workspace-tag">${(study.analysis.recommendationBucket || "uncertain") === "attractive" ? "Priority" : (study.analysis.recommendationBucket || "uncertain") === "unattractive" ? "Avoid" : "Review"}</span>
                    <button class="workspace-action" type="button" data-load-study="${study.id}">Load Study</button>
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

if (workspaceState.activeStudyId) {
    const activeStudy = workspaceState.studies.find((study) => study.id === workspaceState.activeStudyId);
    if (activeStudy) {
        applyScenarioInputs(activeStudy.inputs);
        workflowStatusMessage = `Restored "${activeStudy.title}" from the research workspace.`;
    }
}

syncDistanceFromStrike();
render();
