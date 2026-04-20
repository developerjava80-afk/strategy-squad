const form = document.getElementById("login-form");
const requestTokenInput = document.getElementById("request-token");
const loginButton = document.getElementById("login-button");
const kiteLoginLink = document.getElementById("kite-login-link");
const userIdEl = document.getElementById("user-id");
const tradingDateEl = document.getElementById("trading-date");
const liveStatusEl = document.getElementById("live-status");
const statusLineEl = document.getElementById("status-line");

boot();
form.addEventListener("submit", handleSubmit);

function apiBase() {
    return window.location.protocol === "file:" ? "http://localhost:8080" : "";
}

async function boot() {
    try {
        const status = await fetchJson(`${apiBase()}/api/auth/status`);
        applyStatus(status);
        if (!status.requiresLogin) {
            redirectToMain();
            return;
        }
        captureRequestTokenFromUrl();
    } catch (error) {
        setStatus(`Unable to check daily login state: ${error.message}`);
    }
}

async function handleSubmit(event) {
    event.preventDefault();
    const requestToken = requestTokenInput.value.trim();
    if (!requestToken) {
        setStatus("Enter today's Kite request_token.");
        return;
    }

    loginButton.disabled = true;
    setStatus("Exchanging request_token and starting the live console...");
    try {
        const body = new URLSearchParams({ requestToken });
        const status = await fetchJson(`${apiBase()}/api/auth/login`, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body: body.toString()
        });
        applyStatus(status);
        redirectToMain();
    } catch (error) {
        setStatus(`Login failed: ${error.message}`);
        loginButton.disabled = false;
    }
}

function applyStatus(status) {
    userIdEl.textContent = status.userId || "-";
    tradingDateEl.textContent = status.tradingDate || "-";
    liveStatusEl.textContent = status.liveStatus || "-";
    if (status.loginUrl) {
        kiteLoginLink.href = status.loginUrl;
    }
    setStatus(status.message || "Ready.");
}

function captureRequestTokenFromUrl() {
    const params = new URLSearchParams(window.location.search);
    const requestToken = params.get("request_token");
    if (!requestToken) {
        return;
    }
    requestTokenInput.value = requestToken;
    const cleanUrl = `${window.location.pathname}${window.location.hash || ""}`;
    window.history.replaceState({}, document.title, cleanUrl);
    form.requestSubmit();
}

function redirectToMain() {
    const params = new URLSearchParams(window.location.search);
    const next = params.get("next") || "/";
    window.location.replace(next);
}

function setStatus(message) {
    statusLineEl.textContent = message;
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
