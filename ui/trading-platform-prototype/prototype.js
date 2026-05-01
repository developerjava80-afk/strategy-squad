// Static placeholder strategies — displayed only when live scanner has no data.
// All numeric fields (strikes, premiums, dates) are illustrative only.
// Real values are loaded by loadScannerCandidates() from /api/agentic/scanner/candidates.
const PLACEHOLDER_LABEL = "⚠ Illustrative — awaiting live scanner data";

function makePlaceholderStrategy(id, name, type, underlying, expiryType, note, theta, liquidity, delta, premium, legs, why, decisionMap, adjustmentPlan, riskWarnings) {
    const lotSize = underlying === 'BANKNIFTY' ? 210 : 65;
    return {
        id, name, type, underlying, expiryType,
        expiry: "Next expiry",
        dte: expiryType === 'WEEKLY' ? 2 : 15,
        confidence: null,
        confidenceLabel: "—",
        latestCohortDate: "—",
        management: "Active",
        marketState: "Awaiting live data",
        premium,  // illustrative 0-100 richness score, not actual premium
        theta, liquidity, delta,
        note: PLACEHOLDER_LABEL + ". " + note,
        placeholder: true,
        legs,
        why,
        historical: {
            matchedObservations: "—",
            premiumPercentile: "—",
            averageHistoricalPremium: "—",
            currentPremiumVsHistory: "—",
            expectedDecayZone: "—",
            winRate: "—",
            tailRisk: "—"
        },
        decisionMap,
        liveBehavior: [
            "Live scanner data not yet loaded — decision rules will populate once candidates are available.",
            "Adjustments, profit booking, and risk guard rules are derived from real-time scan output."
        ],
        adjustmentPlan,
        riskWarnings,
        trace: []
    };
}

const strategies = [
    makePlaceholderStrategy(
        "nifty-short-straddle",
        "NIFTY Short Straddle",
        "High theta, high gamma",
        "NIFTY", "WEEKLY",
        "Short CE and PE at the same ATM strike. Strong theta profile, needs strict delta and profit booking.",
        92, 91, 22, 27,
        [
            { leg: "Short CE (ATM)", side: "Sell", optionType: "CE", strike: "ATM", expiry: "Weekly", qty: 65, entryPremium: 0, delta: "~+0.50", thetaScore: 94, liquidity: 96, status: "Awaiting" },
            { leg: "Short PE (ATM)", side: "Sell", optionType: "PE", strike: "ATM", expiry: "Weekly", qty: 65, entryPremium: 0, delta: "~-0.50", thetaScore: 93, liquidity: 95, status: "Awaiting" }
        ],
        [
            "ATM straddle captures maximum theta when premium is rich versus matched weekly sessions.",
            "Theta opportunity is strongest among current candidates when score exceeds 85.",
            "Delta can be managed near neutral while spot stays inside the expected decay zone.",
            "ATM liquidity is strong enough for fast reduce or defensive exit actions.",
            "Risk is acceptable only with tight gamma and profit-booking rules."
        ],
        {
            theta: [
                ["Theta score", "Live — awaiting scanner"],
                ["Expected decay", "22% to 28% by mid-session"],
                ["Decay capture threshold", "55%"],
                ["Profit booking condition", "Book when 55% credit is captured"]
            ],
            delta: [
                ["Net delta target", "0.08 to 0.12"],
                ["Current estimated net delta", "Live — awaiting entry"],
                ["Adjustment trigger", "Absolute net delta above 0.20"],
                ["Hard risk threshold", "Absolute net delta above 0.28 or IV spike above 9%"]
            ]
        },
        [
            ["ADD", "Add one lot pair only if net delta returns inside target and liquidity remains above 85."],
            ["REDUCE", "Reduce 30-50% exposure if theta profit is captured or net delta reaches warning level."],
            ["HOLD", "Hold while theta score stays above 78 and net delta remains inside target."],
            ["EXIT", "Exit if theta fails, premium expands, and net delta breaches hard threshold."]
        ],
        [["Gamma Risk", "hard"], ["Liquidity Risk", "info"], ["Directional Drift Risk", "warning"], ["Theta Failure Risk", "watch"]]
    ),
    makePlaceholderStrategy(
        "nifty-short-strangle",
        "NIFTY Short Strangle",
        "Delta neutral income",
        "NIFTY", "WEEKLY",
        "Short OTM CE and PE at symmetric distances from spot.",
        88, 82, 9, 18,
        [
            { leg: "Short CE (OTM)", side: "Sell", optionType: "CE", strike: "ATM+150", expiry: "Weekly", qty: 65, entryPremium: 0, delta: "~+0.30", thetaScore: 89, liquidity: 84, status: "Awaiting" },
            { leg: "Short PE (OTM)", side: "Sell", optionType: "PE", strike: "ATM-150", expiry: "Weekly", qty: 65, entryPremium: 0, delta: "~-0.30", thetaScore: 88, liquidity: 83, status: "Awaiting" }
        ],
        [
            "OTM strangle provides wider breakeven range than straddle at lower premium.",
            "Best when both OTM legs stay liquid and premium is rich versus historical context.",
            "Delta starts near zero and remains manageable while spot stays inside the strikes.",
            "Liquidity must be monitored — OTM legs can widen quickly under directional stress."
        ],
        {
            theta: [
                ["Theta score", "Live — awaiting scanner"],
                ["Expected decay", "18% to 24% by mid-session"],
                ["Decay capture threshold", "55%"],
                ["Profit booking condition", "Book when 55% credit is captured"]
            ],
            delta: [
                ["Net delta target", "0.06 to 0.10"],
                ["Current estimated net delta", "Live — awaiting entry"],
                ["Adjustment trigger", "Absolute net delta above 0.18"],
                ["Hard risk threshold", "Absolute net delta above 0.25 or spot breaks beyond either strike"]
            ]
        },
        [
            ["ADD", "Add only if net delta is inside target and both legs remain above liquidity threshold."],
            ["REDUCE", "Reduce 30-50% when theta capture is strong or delta approaches warning level."],
            ["HOLD", "Hold while theta score stays above 72 and delta remains inside target."],
            ["EXIT", "Exit if spot breaks beyond either strike with rising IV and delta breaches hard stop."]
        ],
        [["Directional Risk", "warning"], ["Liquidity Risk", "watch"], ["Gamma Risk", "info"]]
    ),
    makePlaceholderStrategy(
        "banknifty-iron-condor",
        "BANKNIFTY Iron Condor",
        "Defined risk, range income",
        "BANKNIFTY", "WEEKLY",
        "Four-leg structure — short OTM strangle with long wings for defined risk.",
        76, 68, 14, 22,
        [
            { leg: "Short CE (OTM)", side: "Sell", optionType: "CE", strike: "ATM+500", expiry: "Weekly", qty: 210, entryPremium: 0, delta: "~+0.28", thetaScore: 77, liquidity: 70, status: "Awaiting" },
            { leg: "Long CE (Wing)", side: "Buy",  optionType: "CE", strike: "ATM+900", expiry: "Weekly", qty: 210, entryPremium: 0, delta: "~+0.12", thetaScore: 60, liquidity: 65, status: "Awaiting" },
            { leg: "Short PE (OTM)", side: "Sell", optionType: "PE", strike: "ATM-500", expiry: "Weekly", qty: 210, entryPremium: 0, delta: "~-0.28", thetaScore: 76, liquidity: 69, status: "Awaiting" },
            { leg: "Long PE (Wing)", side: "Buy",  optionType: "PE", strike: "ATM-900", expiry: "Weekly", qty: 210, entryPremium: 0, delta: "~-0.12", thetaScore: 60, liquidity: 65, status: "Awaiting" }
        ],
        [
            "Iron condor caps maximum loss while retaining theta decay income.",
            "Preferred when BankNifty movement is noisy — defined risk protects against gaps.",
            "Net spread value decays as premium compresses between the short strikes.",
            "Wings reduce margin requirement and limit tail exposure."
        ],
        {
            theta: [
                ["Theta score", "Live — awaiting scanner"],
                ["Expected decay", "Net spread decays toward zero by expiry"],
                ["Decay capture threshold", "50%"],
                ["Profit booking condition", "Book when 50% of net credit is captured"]
            ],
            delta: [
                ["Net delta target", "0.08 to 0.12"],
                ["Current estimated net delta", "Live — awaiting entry"],
                ["Adjustment trigger", "Absolute net delta above 0.20"],
                ["Hard risk threshold", "Spot touches either short strike or delta above 0.28"]
            ]
        },
        [
            ["ADD", "Add only after delta improves and wing liquidity remains acceptable."],
            ["REDUCE", "Reduce 30-50% if spot approaches either short strike."],
            ["HOLD", "Hold while spot stays inside the short strikes and theta decays normally."],
            ["EXIT", "Exit entire structure if spot breaks beyond a short strike with increasing IV."]
        ],
        [["Gamma Risk", "warning"], ["Gap Risk", "hard"], ["Liquidity Risk", "watch"]]
    ),
    makePlaceholderStrategy(
        "banknifty-put-ratio",
        "BANKNIFTY Put Ratio",
        "Directional credit with hedge",
        "BANKNIFTY", "WEEKLY",
        "Sell 2x ATM puts, buy 1x OTM put hedge. Directional bias with defined risk floor.",
        71, 61, 18, 15,
        [
            { leg: "Long PE (Hedge)", side: "Buy",  optionType: "PE", strike: "ATM-400", expiry: "Weekly", qty: 210, entryPremium: 0, delta: "~-0.20", thetaScore: 65, liquidity: 63, status: "Awaiting" },
            { leg: "Short PE (ATM)", side: "Sell", optionType: "PE", strike: "ATM",     expiry: "Weekly", qty: 420, entryPremium: 0, delta: "~-0.50", thetaScore: 71, liquidity: 62, status: "Awaiting" },
            { leg: "Long PE (Wing)", side: "Buy",  optionType: "PE", strike: "ATM+400", expiry: "Weekly", qty: 210, entryPremium: 0, delta: "~-0.08", thetaScore: 60, liquidity: 60, status: "Awaiting" }
        ],
        [
            "Put ratio earns credit while maintaining a directional put bias.",
            "Preferred when BankNifty is expected to stay flat or drift moderately upward.",
            "Hedge wing limits downside tail risk from a sharp move lower.",
            "Liquidity risk is higher — requires monitoring of hedge quote width."
        ],
        {
            theta: [
                ["Theta score", "Live — awaiting scanner"],
                ["Expected decay", "Net credit decays toward zero by expiry"],
                ["Decay capture threshold", "45%"],
                ["Profit booking condition", "Book when 45% of net credit is captured"]
            ],
            delta: [
                ["Net delta target", "0.10 to 0.15"],
                ["Current estimated net delta", "Live — awaiting entry"],
                ["Adjustment trigger", "Absolute net delta above 0.22"],
                ["Hard risk threshold", "Absolute net delta above 0.30 or hedge quote stale above 60s"]
            ]
        },
        [
            ["HOLD", "Hold while delta stays inside target and hedge remains liquid."],
            ["REDUCE", "Reduce short PE exposure if delta drifts toward trigger or hedge widens."],
            ["EXIT", "Exit if hard delta threshold is breached or hedge becomes illiquid."]
        ],
        [["Directional Risk", "hard"], ["Liquidity Risk", "warning"], ["Hedge Staleness Risk", "watch"]]
    )
];

let latestRankedStrategies = [];
let selectedStrategyId = null;
let selectedAction = "build";

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

function money(value) {
    return "Rs " + Math.round(value).toLocaleString("en-IN");
}

function statusClass(score) {
    if (score >= 78) return "good";
    if (score >= 62) return "neutral";
    return "warn";
}

function riskClass(level) {
    if (level === "hard") return "hard";
    if (level === "warning") return "warning";
    if (level === "watch") return "watch";
    return "info";
}

function statusBadgeClass(status) {
    if (status === "EXECUTED") return "good";
    if (status === "BLOCKED") return "warn";
    if (status === "SKIPPED") return "warn";
    if (status === "ELIGIBLE") return "neutral";
    if (status === "MONITORING") return "neutral";
    return "neutral";
}

function priorityBadgeClass(priority) {
    if (priority === "CRITICAL") return "hard";
    if (priority === "HIGH") return "warning";
    if (priority === "MEDIUM") return "watch";
    return "info";
}

function metricCompare(label, current, threshold) {
    return `${label} ${current} / ${threshold}`;
}

function proximityMetric(label, current, threshold, suffix) {
    const ratio = threshold === 0 ? 0 : Math.round((current / threshold) * 100);
    return `${label} ${current} / ${threshold} (${ratio}% to ${suffix})`;
}

function decayPhaseFor(item) {
    if (item.dte <= 2 && item.theta >= 85) return "early decay phase";
    if (item.dte <= 4) return "active decay phase";
    return "carry decay phase";
}

function evidenceBullets(item, params) {
    const richnessDelta = Math.round(item.premium - params.richness);
    const richnessLabel = richnessDelta >= 0 ? `+${richnessDelta}` : `${richnessDelta}`;
    const deltaEstimate = item.decisionMap?.delta?.[1]?.[1] || "Flat";
    const liquidityLabel = item.liquidity >= params.liquidity ? "stable" : "watch";
    return [
        `Premium ${richnessLabel} pts versus fair context`,
        `Theta ${Math.round(item.thetaScore ?? item.theta)} / 100, ${decayPhaseFor(item)}`,
        `Delta target achievable: ${deltaEstimate}`,
        `Liquidity ${Math.round(item.liquidityScore ?? item.liquidity)} / 100, ${liquidityLabel}`
    ];
}

function evidenceTags(item, params) {
    const tags = [];
    if (item.theta >= 85) tags.push("Early Decay");
    if (item.liquidity >= Math.max(80, params.liquidity)) tags.push("Good Liquidity");
    if (item.delta <= params.deltaBand + 2) tags.push("Delta Adjustable");
    if (item.name.includes("Straddle")) tags.push("High Gamma");
    if (item.liquidity < params.liquidity) tags.push("Liquidity Watch");
    if (item.riskWarnings.some(([label]) => label === "Directional Drift Risk")) tags.push("Directional Risk");
    return tags.slice(0, 4);
}

function modeLabelFor(action) {
    if (action === "build") return "Paper Live";
    if (action === "simulate") return "Scoring Preview";
    return "Scoring Preview";
}

function planStateFor(action, logs) {
    if (logs.some(log => log.status === "EXECUTED")) return "Completed";
    if (action === "build") return logs.some(log => log.status === "ELIGIBLE") ? "Armed" : "Watching";
    return "Watching";
}

function scoreStrategy(item, params) {
    const richnessScore = clamp((item.premium - params.richness + 30) / 60, 0, 1) * 100;
    const thetaScore = item.theta;
    const liquidityScore = item.liquidity >= params.liquidity ? item.liquidity : item.liquidity * 0.58;
    const deltaScore = clamp(100 - Math.max(0, item.delta - params.deltaBand) * 4, 0, 100);
    const dteBoost = params.dte <= 2 ? 6 : params.dte <= 5 ? 2 : -4;
    const underlyingFit = item.underlying === params.underlying ? 4 : -4;
    const expiryFit = item.expiryType === params.expiry ? 2 : -2;
    const total = clamp(
        (richnessScore * 0.30) +
        (thetaScore * 0.30) +
        (liquidityScore * 0.22) +
        (deltaScore * 0.18) +
        dteBoost +
        underlyingFit +
        expiryFit,
        0,
        100
    );
    return {
        ...item,
        richnessScore,
        thetaScore,
        liquidityScore,
        deltaScore,
        total
    };
}

function labParams() {
    return {
        underlying: document.querySelector("#lab-underlying").value,
        expiry: document.querySelector("#lab-expiry").value,
        maxLots: Number(document.querySelector("#lab-lots").value),
        richness: Number(document.querySelector("#lab-richness").value),
        deltaBand: Number(document.querySelector("#lab-delta").value),
        liquidity: Number(document.querySelector("#lab-liquidity").value),
        dte: Number(document.querySelector("#lab-dte").value)
    };
}

function renderMetricPairs(containerId, pairs) {
    const container = document.querySelector(containerId);
    if (!container) return;
    container.innerHTML = pairs.map(([label, value]) => `
        <div>
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value)}</strong>
        </div>
    `).join("");
}

function renderList(containerId, items) {
    const container = document.querySelector(containerId);
    if (!container) return;
    container.innerHTML = items.map(item => `<li>${escapeHtml(item)}</li>`).join("");
}

function renderActionRules(item) {
    const container = document.querySelector("#detail-adjustment-plan");
    if (!container) return;
    container.innerHTML = item.adjustmentPlan.map(([action, text]) => `
        <div>
            <strong>${escapeHtml(action)}</strong>
            <span>${escapeHtml(text)}</span>
        </div>
    `).join("");
}

function buildDecisionLogs(item) {
    const rejectedCandidates = latestRankedStrategies
        .filter(candidate => candidate.id !== item.id)
        .slice(0, 2)
        .map(candidate => candidate.name)
        .join(", ");
    const score = Math.round(item.total ?? 0);
    const theta = Math.round(item.thetaScore ?? item.theta);
    const richness = Math.round(item.richnessScore ?? item.premium);
    const liquidity = Math.round(item.liquidityScore ?? item.liquidity);

    // Compute time offsets from current time
    const now = new Date();
    const timeOffset = (minutesDelta) => {
        const d = new Date(now);
        d.setMinutes(d.getMinutes() + minutesDelta);
        return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
    };

    // Extract decision thresholds from item's decisionMap when available.
    // Theta capture %: look for first %-number in the capture/book threshold row.
    const thetaCapturePct = (() => {
        const raw = item.decisionMap?.theta?.[2]?.[1] || item.decisionMap?.theta?.[3]?.[1] || "";
        return raw.match(/(\d+)%/)?.[1] || "55";
    })();
    // Book threshold %: second distinct %-number, or default 70.
    const thetaBookThreshold = (() => {
        const raw = item.decisionMap?.theta?.[3]?.[1] || item.decisionMap?.theta?.[2]?.[1] || "";
        const all = [...raw.matchAll(/(\d+)%/g)].map(m => m[1]);
        return all[1] || all[0] || "70";
    })();
    // Delta target: first decimal number in the delta target row.
    const deltaTarget = (() => {
        const raw = item.decisionMap?.delta?.[0]?.[1] || "";
        return raw.match(/([\d.]+)\s+to\s+([\d.]+)/)?.[1] || raw.match(/[\d.]+/)?.[0] || "0.08";
    })();
    // Delta hard stop: last decimal number in the hard risk threshold row.
    const deltaHardStop = (() => {
        const raw = item.decisionMap?.delta?.[3]?.[1] || item.decisionMap?.delta?.[2]?.[1] || "";
        const all = [...raw.matchAll(/[\d.]+/g)].map(m => m[0]).filter(v => parseFloat(v) < 1);
        return all[all.length - 1] || "0.28";
    })();
    // Compute profit thresholds based on premium and legs
    const initialPremium = item.legs?.reduce((sum, leg) => sum + (leg.entryPremium || 0), 0) || (item.premium * 65);
    const profitBookThreshold = Math.round(initialPremium * (Number(thetaCapturePct) / 100));
    const estMaxProfit = Math.round(initialPremium * 0.80);

    return [
        {
            time: timeOffset(0),
            type: "SIGNAL",
            severity: "INFO",
            action: "ENTRY SIGNAL",
            status: "SIGNAL",
            priority: "LOW",
            reason: `${item.name} cleared score and premium richness gates.`,
            metrics: [
                metricCompare("Score", score, "pass 80"),
                metricCompare("Liquidity", liquidity, "min 65")
            ],
            result: "Not executed",
            change: ["Δ delta flat", "Δ theta flat"],
            details: [
                ["Selected candidate", item.name],
                ["Rejected candidates", rejectedCandidates || "None"],
                ["Score breakdown", `Premium ${Math.round(item.richnessScore)} | Theta ${theta} | Liquidity ${liquidity} | Delta ${Math.round(item.deltaScore)}`],
                ["Premium richness", `${item.premium} pts raw, ${richness} / 100 score`],
                ["Initial net delta", item.decisionMap?.delta?.[1]?.[1] || "Flat"]
            ]
        },
        {
            time: timeOffset(25),
            type: "DECISION",
            severity: "INFO",
            action: "HOLD",
            status: "MONITORING",
            priority: "LOW",
            reason: "Theta working and net delta remains inside the target band.",
            metrics: [
                proximityMetric("Net delta", +(Number(deltaTarget) * 0.25).toFixed(3), Number(deltaTarget), "target"),
                proximityMetric("Theta capture", Math.round(initialPremium * 0.32), Number(thetaBookThreshold), "book")
            ],
            result: "Waiting trigger",
            change: ["Δ delta -0.01", "Δ theta +3%"],
            details: [
                ["Theta working status", "Working, decay still ahead of schedule"],
                ["Net delta before / after", `~${+(Number(deltaTarget) * 0.3).toFixed(3)} → ~${+(Number(deltaTarget) * 0.25).toFixed(3)}`],
                ["Cooldown / churn state", "No churn block, hold bias remains"],
                ["Plan state", "Paper run active, no execution yet"]
            ]
        },
        {
            time: timeOffset(70),
            type: "ADJUSTMENT",
            severity: "WATCH",
            action: "ADD OPPORTUNITY",
            status: "ELIGIBLE",
            priority: "LOW",
            reason: "Delta improved and spare lots remain available.",
            metrics: [
                proximityMetric("Net delta", +(Number(deltaTarget) * 0.55).toFixed(3), Number(deltaTarget) * 2.5, "trigger"),
                metricCompare("Liquidity", String(liquidity), "min 65")
            ],
            result: "Eligible only",
            change: ["Δ delta +0.08", "Δ theta +4%"],
            details: [
                ["Add candidate", `${item.name} add-on pair`],
                ["Available lots", "2 of remaining lots"],
                ["Post-add net delta", `~${+(Number(deltaTarget) * 0.25).toFixed(3)} estimated`],
                ["Theta / carry score", `${theta} / 100 theta, carry stable`],
                ["Liquidity", String(liquidity) + " / 100, acceptable for paper add"]
            ]
        },
        {
            time: timeOffset(125),
            type: "PNL",
            severity: "INFO",
            action: "PROFIT BOOK SIGNAL",
            status: "ELIGIBLE",
            priority: "MEDIUM",
            reason: "Decay capture threshold is reached before risk expands.",
            metrics: [
                proximityMetric("Theta capture", Math.round(initialPremium * Number(thetaCapturePct) / 100), Number(thetaBookThreshold), "book"),
                proximityMetric("Net delta", +(Number(deltaTarget) * 0.6).toFixed(3), Number(deltaTarget), "target")
            ],
            result: "Eligible only",
            change: ["Δ delta +0.03", "Δ theta +19%"],
            details: [
                ["Theta capture %", thetaCapturePct + "%"],
                ["Capture threshold", thetaBookThreshold + "%"],
                ["Estimated booked PnL", "+Rs " + profitBookThreshold],
                ["Remaining risk", "Gamma rises if premium stalls near noon"]
            ]
        },
        {
            time: timeOffset(200),
            type: "RISK",
            severity: "WARNING",
            action: "REDUCE WATCH",
            status: "MONITORING",
            priority: "HIGH",
            reason: "Directional drift approaches the adjustment trigger.",
            metrics: [
                proximityMetric("Net delta", Number(deltaTarget) * 2, Number(deltaTarget) * 2.5, "trigger"),
                metricCompare("Score", score, "pass 80")
            ],
            result: "Waiting trigger",
            change: ["Δ delta +0.09", "Δ theta -2%"],
            details: [
                ["Net delta current / threshold", Number(deltaTarget) * 2 + " current / " + Number(deltaTarget) * 2.5 + " trigger"],
                ["Threatened side", "Call side carrying drift"],
                ["Expected post-reduce delta", Number(deltaTarget * 1.1) + " estimated"],
                ["Reason not yet executed", "Spot reverted inside zone before trigger confirmed"]
            ]
        },
        {
            time: timeOffset(250),
            type: "SYSTEM",
            severity: "HARD",
            action: "EXIT RULE ARMED",
            status: "MONITORING",
            priority: "CRITICAL",
            reason: "Hard risk threshold would invalidate the structure.",
            metrics: [
                proximityMetric("Net delta", Number(deltaHardStop) * 0.86, deltaHardStop, "trigger"),
                proximityMetric("Theta capture", Math.round(initialPremium * Number(thetaCapturePct) / 100), Number(thetaBookThreshold), "book")
            ],
            result: "Armed only",
            change: ["Δ delta +0.08", "Δ theta -4%"],
            details: [
                ["Hard stop condition", "Absolute delta above " + deltaHardStop + " or IV spike above 9%"],
                ["Current risk value", Number(deltaHardStop) * 0.86 + " net delta"],
                ["Trigger value", deltaHardStop + " net delta"],
                ["Expected action if triggered", "EXIT COMPLETED on entire open structure"],
                ["Trading paused", "No, still active until hard stop hits"]
            ]
        }
    ];
}

function renderPlanSummary(item, logs) {
    const summary = document.querySelector("#lab-plan-summary");
    if (!summary || !item) return;
    const nextAction = logs.find(log => log.priority === "CRITICAL")
        ?.action || logs.find(log => log.priority === "HIGH")
        ?.action || logs.find(log => log.status === "ELIGIBLE")
        ?.action || logs.find(log => log.status === "MONITORING")
        ?.action || "HOLD";
    const mode = modeLabelFor(selectedAction);
    const planState = planStateFor(selectedAction, logs);

    // Compute real theta capture and delta targets from item's decision map
    const thetaCapturePct = item.decisionMap?.theta?.[2]?.[1]?.match(/(\d+)%/)?.[1] || "55";
    const thetaBookThreshold = item.decisionMap?.theta?.[3]?.[1]?.match(/(\d+)/)?.[1] || "70";
    const deltaTarget = item.decisionMap?.delta?.[0]?.[1]?.match(/[\d.]+/)?.[0] || "0.08";

    summary.innerHTML = [
        ["Selected Plan", item.name],
        ["Mode", mode],
        ["Plan State", planState],
        ["Next Likely Action", nextAction],
        ["Primary Risk", item.riskWarnings.find(([, level]) => level === "warning" || level === "hard")?.[0] || item.riskWarnings[0][0]],
        ["Theta Capture", thetaCapturePct + "% / " + thetaBookThreshold + "%"],
        ["Net Delta", deltaTarget + " / " + deltaTarget + " target"]
    ].map(([label, value]) => `
        <div class="summary-chip">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value)}</strong>
        </div>
    `).join("");
}

function renderLabLogs(item) {
    const table = document.querySelector("#lab-log-table");
    if (!table || !item) return;

    const logs = buildDecisionLogs(item);
    renderPlanSummary(item, logs);

    table.innerHTML = logs.map(log => {
        const details = log.details.map(([label, value]) => `
            <span><b>${escapeHtml(label)}</b>${escapeHtml(value || "None")}</span>
        `).join("");
        const metrics = [...log.metrics, ...(log.change || [])].map(metric => `<span>${escapeHtml(metric)}</span>`).join("");

        return `
            <tr>
                <td>${escapeHtml(log.time)}</td>
                <td><span class="log-type">${escapeHtml(log.type)}</span></td>
                <td><span class="severity-pill severity-${escapeHtml(log.severity.toLowerCase())}">${escapeHtml(log.severity)}</span></td>
                <td><span class="status-pill ${statusBadgeClass(log.status)}">${escapeHtml(log.status)}</span></td>
                <td><span class="severity-pill severity-${priorityBadgeClass(log.priority)}">${escapeHtml(log.priority)}</span></td>
                <td>${escapeHtml(log.action)}</td>
                <td>${escapeHtml(log.reason)}</td>
                <td><div class="metric-stack">${metrics}</div></td>
                <td>
                    <details>
                        <summary>${escapeHtml(log.result)}</summary>
                        <div class="log-details">${details}</div>
                    </details>
                </td>
            </tr>
        `;
    }).join("");
}

function openStructureDetail(item, action = "build", shouldScroll = true) {
    const panel = document.querySelector("#structure-detail-panel");
    if (!panel || !item) return;

    selectedStrategyId = item.id;
    selectedAction = action;
    panel.hidden = false;

    const actionLabels = {
        details: "Open Paper Plan",
        build: "Start Paper Run",
        simulate: "Start Simulation"
    };
    const title = document.querySelector("#detail-title");
    const subtitle = document.querySelector("#detail-subtitle");
    const score = document.querySelector("#detail-score");
    const confidence = document.querySelector("#detail-confidence");
    const market = document.querySelector("#detail-market-badge");
    const primaryAction = document.querySelector("#detail-primary-action");

    title.textContent = item.name;
    subtitle.textContent = `${item.underlying} | ${item.expiry} | DTE ${item.dte} | ${item.type}`;
    score.textContent = "Score " + Math.round(item.total ?? 0);
    score.className = "status-pill " + statusClass(item.total ?? 0);
    confidence.textContent = "Confidence " + item.confidence + "%";
    confidence.className = "status-pill " + statusClass(item.confidence);
    market.textContent = item.marketState;
    market.className = "status-pill neutral";
    primaryAction.textContent = actionLabels[action] || actionLabels.build;

    document.querySelector("#detail-leg-table").innerHTML = item.legs.map(leg => `
        <tr>
            <td>${escapeHtml(leg.leg)}</td>
            <td>${escapeHtml(leg.side)}</td>
            <td>${escapeHtml(leg.optionType)}</td>
            <td>${escapeHtml(leg.strike)}</td>
            <td>${escapeHtml(leg.expiry)}</td>
            <td>${escapeHtml(leg.qty)}</td>
            <td>${money(leg.entryPremium)}</td>
            <td>${escapeHtml(leg.delta)}</td>
            <td>${escapeHtml(leg.thetaScore)}</td>
            <td>${escapeHtml(leg.liquidity)}</td>
            <td><span class="status-pill ${leg.status === "Watch" ? "warn" : leg.status === "Hedge" ? "neutral" : "good"}">${escapeHtml(leg.status)}</span></td>
        </tr>
    `).join("");

    renderList("#detail-why", item.why);
    renderMetricPairs("#detail-historical", [
        ["Matched observations", item.historical.matchedObservations],
        ["Premium percentile", item.historical.premiumPercentile],
        ["Avg historical premium", item.historical.averageHistoricalPremium],
        ["Current vs history", item.historical.currentPremiumVsHistory],
        ["Expected decay zone", item.historical.expectedDecayZone],
        ["Win rate", item.historical.winRate],
        ["Tail risk", item.historical.tailRisk]
    ]);
    renderMetricPairs("#detail-theta-map", item.decisionMap.theta);
    renderMetricPairs("#detail-delta-map", item.decisionMap.delta);
    renderList("#detail-live-behavior", item.liveBehavior);
    renderActionRules(item);

    document.querySelector("#detail-risk-badges").innerHTML = item.riskWarnings.map(([label, level]) => `
        <span class="risk-badge ${riskClass(level)}">${escapeHtml(label)}</span>
    `).join("");

    document.querySelector("#detail-trace-table").innerHTML = item.trace.map(row => `
        <tr>
            <td>${escapeHtml(row[0])}</td>
            <td><strong>${escapeHtml(row[1])}</strong></td>
            <td>${escapeHtml(row[2])}</td>
            <td>${escapeHtml(row[3])}</td>
            <td>${escapeHtml(row[4])}</td>
        </tr>
    `).join("");

    renderLabLogs(item);

    if (shouldScroll) {
        panel.scrollIntoView({ behavior: "smooth", block: "start" });
    }
}

function renderStrategyLab() {
    const grid = document.querySelector("#strategy-score-grid");
    const table = document.querySelector("#strategy-score-table");
    if (!grid || !table) return;

    const params = labParams();
    document.querySelector("#lab-richness-value").textContent = params.richness;
    document.querySelector("#lab-delta-value").textContent = (params.deltaBand / 100).toFixed(2);
    document.querySelector("#lab-liquidity-value").textContent = params.liquidity;
    document.querySelector("#lab-lots-value").textContent = params.maxLots;

    latestRankedStrategies = strategies
        .map(item => scoreStrategy(item, params))
        .sort((a, b) => b.total - a.total);

    document.querySelector("#lab-count").textContent = latestRankedStrategies.length + " candidates";

    grid.innerHTML = latestRankedStrategies.map((item, index) => `
        <article class="score-card">
            <header>
                <div>
                    <h3>${index + 1}. ${escapeHtml(item.name)}</h3>
                    <small>${escapeHtml(item.type)}</small>
                </div>
                <span class="status-pill ${statusClass(item.total)}">${Math.round(item.total)}</span>
            </header>
            <p>${escapeHtml(item.note)}</p>
            <div class="card-confidence">
                <span><b>Confidence:</b> ${escapeHtml(item.confidenceLabel)}</span>
                <span><b>Matches:</b> ${escapeHtml(item.historical.matchedObservations)}</span>
                <span><b>Latest cohort:</b> ${escapeHtml(item.latestCohortDate)}</span>
                <span><b>Management:</b> ${escapeHtml(item.management)}</span>
            </div>
            <ul class="score-evidence">
                ${evidenceBullets(item, params).map(entry => `<li>${escapeHtml(entry)}</li>`).join("")}
            </ul>
            <div class="tag-row">
                ${evidenceTags(item, params).map(tag => `<span class="mini-tag">${escapeHtml(tag)}</span>`).join("")}
            </div>
            <div class="score-meter"><span style="width:${Math.round(item.total)}%"></span></div>
            <div class="card-actions" aria-label="${escapeHtml(item.name)} actions">
                <button class="quiet-button compact-action" data-action="details" data-strategy-id="${escapeHtml(item.id)}" type="button">View Details</button>
                <button class="primary-button compact-action" data-action="build" data-strategy-id="${escapeHtml(item.id)}" type="button">Build Position</button>
                <button class="quiet-button compact-action" data-action="simulate" data-strategy-id="${escapeHtml(item.id)}" type="button">Simulate</button>
            </div>
        </article>
    `).join("");

    table.innerHTML = latestRankedStrategies.map(item => `
        <tr>
            <td>${escapeHtml(item.name)}</td>
            <td>${Math.round(item.richnessScore)}</td>
            <td>${Math.round(item.thetaScore)}</td>
            <td>${Math.round(item.liquidityScore)}</td>
            <td>${Math.round(item.deltaScore)}</td>
            <td><strong>${Math.round(item.total)}</strong></td>
        </tr>
    `).join("");

    const selected = latestRankedStrategies.find(item => item.id === selectedStrategyId);
    if (selected) {
        openStructureDetail(selected, selectedAction, false);
    } else {
        renderLabLogs(latestRankedStrategies[0]);
    }
}

function calculateDTE(expiryDate) {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const expiry = new Date(expiryDate);
    expiry.setHours(0, 0, 0, 0);
    const diff = Math.ceil((expiry - today) / (1000 * 60 * 60 * 24));
    return Math.max(0, diff);
}

function loadScannerCandidates(underlying) {
    const statusEl = document.querySelector("#scanner-status");

    fetch(`/api/agentic/scanner/candidates?underlying=${underlying}`)
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(candidates => {
            if (!Array.isArray(candidates) || candidates.length === 0) {
                if (statusEl) statusEl.textContent = "Using static data";
                return;
            }

            const liveCandidates = candidates.map(candidate => ({
                id: candidate.candidateId || `live-${candidate.tradingSymbol}`,
                name: candidate.tradingSymbol,
                type: candidate.optionType === 'CE' ? 'Call option' : 'Put option',
                underlying: candidate.underlying,
                expiryType: candidate.expiryType,
                expiry: candidate.expiryDate,
                dte: calculateDTE(candidate.expiryDate),
                confidence: Math.round((candidate.totalScore || 0) * 100),
                confidenceLabel: candidate.totalScore > 0.8 ? 'High' : candidate.totalScore > 0.65 ? 'Medium' : 'Low',
                latestCohortDate: new Date().toISOString().slice(0, 10),
                management: 'Active',
                marketState: candidate.disqualifierReason ? 'Disqualified' : 'Active',
                premium: Math.round((candidate.premiumRichnessPoints || 0) * 10) / 10,
                theta: Math.round((candidate.thetaOpportunityScore || 0) * 100),
                liquidity: Math.round((candidate.liquidityScore || 0) * 100),
                delta: Math.round((candidate.deltaRiskScore || 0) * 100),
                note: candidate.disqualifierReason || 'Live scanner candidate',
                legs: [{
                    leg: candidate.tradingSymbol,
                    side: 'Sell',
                    optionType: candidate.optionType,
                    strike: String(candidate.strike),
                    expiry: candidate.expiryDate,
                    qty: candidate.underlying === 'BANKNIFTY' ? 210 : 650,
                    entryPremium: candidate.lastPrice || 0,
                    delta: candidate.optionType === 'CE' ? '+0.30' : '-0.30',
                    thetaScore: Math.round((candidate.thetaOpportunityScore || 0) * 100),
                    liquidity: Math.round((candidate.liquidityScore || 0) * 100),
                    status: candidate.disqualifierReason ? 'Watch' : 'Ready'
                }],
                why: [
                    `Live scanner: ${candidate.tradingSymbol}`,
                    `Premium richness: ${candidate.premiumRichnessPoints || 0} pts`,
                    `Total score: ${Math.round((candidate.totalScore || 0) * 100)}`
                ],
                historical: {
                    matchedObservations: 'Live',
                    premiumPercentile: 'N/A',
                    averageHistoricalPremium: 'N/A',
                    currentPremiumVsHistory: candidate.premiumRichnessPct ? `+${candidate.premiumRichnessPct}%` : 'N/A',
                    expectedDecayZone: 'N/A',
                    winRate: 'N/A',
                    tailRisk: 'N/A'
                },
                decisionMap: {
                    theta: [
                        ['Theta score', String(Math.round((candidate.thetaOpportunityScore || 0) * 100)) + ' / 100']
                    ],
                    delta: [
                        ['Delta risk', String(Math.round((candidate.deltaRiskScore || 0) * 100)) + ' / 100']
                    ]
                },
                liveBehavior: ['Live scanner candidate — historical detail not available.'],
                adjustmentPlan: [['HOLD', 'Monitor live candidate.']],
                riskWarnings: [['Live Data Risk', 'watch']],
                trace: []
            }));

            // Merge live candidates with static strategies, live ones first
            const merged = [...liveCandidates, ...strategies];
            strategies.length = 0;
            strategies.push(...merged);

            if (statusEl) statusEl.textContent = `Live data loaded (${liveCandidates.length} candidates)`;
            renderStrategyLab();
        })
        .catch(err => {
            console.error('Failed to load scanner candidates:', err);
            if (statusEl) statusEl.textContent = "Using static data";
        });
}

function setupStrategyLab() {
    const form = document.querySelector("#lab-form");
    const reset = document.querySelector("#reset-lab");
    const grid = document.querySelector("#strategy-score-grid");
    const underlying = document.querySelector("#lab-underlying");
    if (!form) return;

    form.addEventListener("input", renderStrategyLab);

    if (underlying) {
        underlying.addEventListener("change", () => {
            loadScannerCandidates(underlying.value);
        });
    }

    grid?.addEventListener("click", event => {
        const button = event.target.closest("[data-action][data-strategy-id]");
        if (!button) return;
        const strategy = latestRankedStrategies.find(item => item.id === button.dataset.strategyId);
        openStructureDetail(strategy, button.dataset.action, true);
    });
    reset?.addEventListener("click", () => {
        document.querySelector("#lab-underlying").value = "NIFTY";
        document.querySelector("#lab-expiry").value = "WEEKLY";
        document.querySelector("#lab-dte").value = "2";
        document.querySelector("#lab-lots").value = "6";
        document.querySelector("#lab-richness").value = "12";
        document.querySelector("#lab-delta").value = "12";
        document.querySelector("#lab-liquidity").value = "65";
        selectedStrategyId = null;
        selectedAction = "build";
        const panel = document.querySelector("#structure-detail-panel");
        if (panel) panel.hidden = true;
        renderStrategyLab();
    });

    // Load live scanner candidates on startup
    loadScannerCandidates(underlying?.value || 'NIFTY');
    renderStrategyLab();
}

function setupOrderTicket() {
    const lots = document.querySelector("#order-lots");
    const credit = document.querySelector("#order-credit");
    const total = document.querySelector("#order-total");
    if (!lots || !credit || !total) return;

    const update = () => {
        const estimated = Number(lots.value) * 65 * Number(credit.value);
        total.textContent = money(estimated);
    };
    lots.addEventListener("input", update);
    credit.addEventListener("input", update);
    update();
}

setupStrategyLab();
setupOrderTicket();
