// scope-picker.js
//
// Scope management for pages OTHER than Strategy Lab.
// Strategy Lab (strategy-lab.html) uses its own inline scope logic
// with the redesigned scope-first panel — this file is a compatibility
// shim for any other pages that may reference window.scopePicker.
//
// APIs consumed by other pages:
//   GET    /api/bootstrap/metadata  -- available underlyings + expiries
//   GET    /api/scope               -- active scope (or null)
//   POST   /api/scope               -- activate / replace scope
//   DELETE /api/scope               -- clear scope
//
// Public API (window.scopePicker):
//   initScopePicker()    -- call once at page load (no-op if no dialog present)
//   getActiveScope()     -- returns current scope object or null
//   onScopeChange(fn)    -- register callback(scope|null) on change

(function () {
    "use strict";

    var _activeScope = null;
    var _metadata    = null;
    var _listeners   = [];

    function _notifyListeners() {
        _listeners.forEach(function (fn) {
            try { fn(_activeScope); } catch (e) {}
        });
    }

    function _apiBase() {
        return window.location.protocol === "file:" ? "http://localhost:8080" : "";
    }

    function _fetchJson(url, opts) {
        return fetch(url, opts).then(function (resp) {
            if (!resp.ok) {
                return resp.json().catch(function () { return {}; }).then(function (body) {
                    throw new Error(body.error || body.message || resp.statusText || String(resp.status));
                });
            }
            return resp.json();
        });
    }

    function _loadMetadata() {
        return _fetchJson(_apiBase() + "/api/bootstrap/metadata")
            .then(function (data) {
                _metadata = data;
                return data;
            })
            .catch(function (err) {
                console.warn("[scope-picker] metadata load failed:", err.message);
                _metadata = { underlyings: ["NIFTY", "BANKNIFTY"], expiries: [] };
                return _metadata;
            });
    }

    function _restoreActiveScope() {
        return _fetchJson(_apiBase() + "/api/scope")
            .then(function (data) {
                if (data && data.activeScope) {
                    _activeScope = { scope: data.activeScope };
                } else {
                    _activeScope = null;
                }
            })
            .catch(function () {
                _activeScope = null;
            })
            .then(function () {
                _renderFooterChip();
            });
    }

    function _buildExpiryOptions(underlying) {
        if (!_metadata || !Array.isArray(_metadata.expiries)) return "";
        var today = new Date().toISOString().slice(0, 10);
        return _metadata.expiries
            .filter(function (e) { return e.underlying === underlying && e.date >= today; })
            .sort(function (a, b) { return a.date < b.date ? -1 : a.date > b.date ? 1 : 0; })
            .map(function (e) {
                var count = e.instrumentCount != null ? e.instrumentCount : "?";
                var label = e.date + " - " + e.type + " (" + count + " instruments)";
                return '<option value="' + e.date + "|" + e.type + '">' + label + "</option>";
            })
            .join("");
    }

    function _refreshExpiryOptions() {
        var underlyingEl = document.getElementById("sp-underlying");
        var expiryEl     = document.getElementById("sp-expiry");
        if (!underlyingEl || !expiryEl) return;
        var underlying = underlyingEl.value;
        var opts = _buildExpiryOptions(underlying);
        expiryEl.innerHTML = opts || '<option value="">No expiries available</option>';
        if (_activeScope && _activeScope.scope.underlying === underlying) {
            var s = _activeScope.scope;
            var target = s.expiry + "|" + s.expiryType;
            for (var i = 0; i < expiryEl.options.length; i++) {
                if (expiryEl.options[i].value === target) {
                    expiryEl.value = target;
                    break;
                }
            }
        }
    }

    function _refreshStrikeInputs() {
        var kindEl  = document.getElementById("sp-strike-kind");
        var pctRow  = document.getElementById("sp-strike-pct-row");
        var ptsRow  = document.getElementById("sp-strike-pts-row");
        var kind = kindEl ? kindEl.value : "ATM_PCT";
        if (pctRow) pctRow.hidden = (kind !== "ATM_PCT");
        if (ptsRow) ptsRow.hidden = (kind !== "ATM_POINTS");
    }

    function _openDialog() {
        var dialog = document.getElementById("scope-picker-dialog");
        if (!dialog) return;
        var underlyingEl = document.getElementById("sp-underlying");
        if (underlyingEl && _metadata) {
            var underlyings = _metadata.underlyings || ["NIFTY", "BANKNIFTY"];
            underlyingEl.innerHTML = underlyings.map(function (u) {
                return '<option value="' + u + '">' + u + "</option>";
            }).join("");
            if (_activeScope) underlyingEl.value = _activeScope.scope.underlying;
        }
        _refreshExpiryOptions();
        if (_activeScope) {
            var s = _activeScope.scope;
            var stratEl = document.getElementById("sp-strategy");
            if (stratEl) stratEl.value = s.strategy || "SHORT_STRANGLE";
            var kindEl = document.getElementById("sp-strike-kind");
            var sw = s.strikeWindow;
            if (kindEl) {
                kindEl.value = (sw && sw.kind) ? sw.kind : "ATM_PCT";
                _refreshStrikeInputs();
            }
            var pctEl = document.getElementById("sp-strike-pct");
            if (pctEl && sw && sw.pct != null) pctEl.value = sw.pct;
            var ptsEl = document.getElementById("sp-strike-pts");
            if (ptsEl && sw && sw.points != null) ptsEl.value = sw.points;
            var maxEl = document.getElementById("sp-max-candidates");
            if (maxEl) maxEl.value = s.maxCandidates || 30;
        }
        try { dialog.showModal(); } catch (err) {}
        _loadMetadata().then(function () { _refreshExpiryOptions(); });
    }

    function _activateScope(payload) {
        var btn   = document.getElementById("sp-confirm-btn");
        var errEl = document.getElementById("sp-error");
        if (btn)   { btn.disabled = true; btn.textContent = "Activating…"; }
        if (errEl) errEl.textContent = "";
        return _fetchJson(_apiBase() + "/api/scope", {
            method:  "POST",
            headers: { "Content-Type": "application/json" },
            body:    JSON.stringify(payload)
        }).then(function (result) {
            _activeScope = {
                scope: {
                    underlying:    result.underlying    || payload.underlying,
                    expiry:        result.expiry        || payload.expiry,
                    expiryType:    result.expiryType    || payload.expiryType,
                    strategy:      result.strategy      || payload.strategy,
                    maxCandidates: payload.maxCandidates,
                    strikeWindow:  result.strikeWindow  || payload.strikeWindow
                },
                universe: {
                    size:      result.instrumentCount != null ? result.instrumentCount : null,
                    truncated: !!result.truncated
                }
            };
            _renderFooterChip();
            _notifyListeners();
            var dlg = document.getElementById("scope-picker-dialog");
            if (dlg) dlg.close();
        }).catch(function (err) {
            if (errEl) errEl.textContent = "Error: " + err.message;
        }).then(function () {
            if (btn) { btn.disabled = false; btn.textContent = "Activate Scope"; }
        });
    }

    function _clearScope() {
        if (!confirm("Clear the active scope? This will unsubscribe all instruments.")) return;
        fetch(_apiBase() + "/api/scope", { method: "DELETE" }).catch(function () {});
        _activeScope = null;
        _renderFooterChip();
        _notifyListeners();
    }

    function _renderFooterChip() {
        var chip     = document.getElementById("scope-footer-chip");
        var clearBtn = document.getElementById("scope-clear-btn");
        if (!chip) return;
        if (_activeScope && _activeScope.scope) {
            var s         = _activeScope.scope;
            var uni       = _activeScope.universe;
            var truncated = !!(uni && uni.truncated);
            var count     = (uni && uni.size != null) ? uni.size : "?";
            chip.textContent = s.underlying + " - " + s.expiry + " - " + s.expiryType +
                               " - " + _strategyLabel(s.strategy) +
                               "  |  " + count + " instruments" +
                               (truncated ? "  ! truncated" : "");
            chip.className = "scope-chip scope-chip--active" + (truncated ? " scope-chip--truncated" : "");
            if (clearBtn) clearBtn.hidden = false;
        } else {
            chip.textContent = "No scope active - click Set Scope to begin";
            chip.className   = "scope-chip scope-chip--inactive";
            if (clearBtn) clearBtn.hidden = true;
        }
    }

    function _strategyLabel(strategy) {
        var map = {
            SHORT_STRANGLE: "Short Strangle",
            SHORT_STRADDLE: "Short Straddle",
            IRON_CONDOR:    "Iron Condor",
            ANALYSIS_ONLY:  "Analysis Only"
        };
        return map[strategy] || strategy || "--";
    }

    function _wireDialog() {
        var spUnderlying = document.getElementById("sp-underlying");
        if (spUnderlying) spUnderlying.addEventListener("change", _refreshExpiryOptions);

        var spStrikeKind = document.getElementById("sp-strike-kind");
        if (spStrikeKind) spStrikeKind.addEventListener("change", _refreshStrikeInputs);

        var spConfirm = document.getElementById("sp-confirm-btn");
        if (spConfirm) {
            spConfirm.addEventListener("click", function () {
                var underlyingEl = document.getElementById("sp-underlying");
                var expiryEl     = document.getElementById("sp-expiry");
                var stratEl      = document.getElementById("sp-strategy");
                var kindEl       = document.getElementById("sp-strike-kind");
                var pctEl        = document.getElementById("sp-strike-pct");
                var ptsEl        = document.getElementById("sp-strike-pts");
                var maxEl        = document.getElementById("sp-max-candidates");
                var errEl        = document.getElementById("sp-error");
                var expiryVal    = expiryEl ? expiryEl.value : "";
                var expiryParts  = expiryVal.split("|");
                if (expiryParts.length < 2 || !expiryParts[0]) {
                    if (errEl) errEl.textContent = "Please select an expiry.";
                    return;
                }
                var kind = kindEl ? kindEl.value : "ATM_PCT";
                var strikeWindow;
                if (kind === "ATM_PCT") {
                    var pct = parseFloat(pctEl ? pctEl.value : "4");
                    if (!isFinite(pct) || pct <= 0) {
                        if (errEl) errEl.textContent = "Enter a valid ATM % (e.g. 4.0).";
                        return;
                    }
                    strikeWindow = { kind: "ATM_PCT", pct: pct };
                } else {
                    var pts = parseFloat(ptsEl ? ptsEl.value : "500");
                    if (!isFinite(pts) || pts <= 0) {
                        if (errEl) errEl.textContent = "Enter valid ATM points (e.g. 500).";
                        return;
                    }
                    strikeWindow = { kind: "ATM_POINTS", points: pts };
                }
                var underlying = underlyingEl ? underlyingEl.value : "NIFTY";
                var btn = document.getElementById("sp-confirm-btn");
                if (btn) { btn.disabled = true; btn.textContent = "Fetching spot…"; }
                if (errEl) errEl.textContent = "";

                // Fix Issue 1: fetch live spot before posting scope so ATM windows
                // resolve around the real underlying price, not the 0.0 default.
                _fetchJson(_apiBase() + "/api/live/spot?underlying=" + encodeURIComponent(underlying))
                    .then(function (spotData) {
                        var spotEstimate = (spotData && spotData.price != null) ? Number(spotData.price) : null;
                        if (spotEstimate == null || !isFinite(spotEstimate) || spotEstimate <= 0) {
                            // Reject if spot is unavailable — do not silently use 0.0
                            var msg = "Cannot activate ATM scope: live spot for " + underlying + " is unavailable. " +
                                      (spotData && spotData.diagnosticReason ? spotData.diagnosticReason : "Check feed connection.");
                            if (errEl) errEl.textContent = msg;
                            if (btn) { btn.disabled = false; btn.textContent = "Activate Scope"; }
                            return;
                        }
                        _activateScope({
                            underlying:    underlying,
                            expiry:        expiryParts[0],
                            expiryType:    expiryParts[1],
                            strategy:      stratEl ? stratEl.value : "SHORT_STRANGLE",
                            strikeWindow:  strikeWindow,
                            maxCandidates: parseInt(maxEl ? maxEl.value : "30", 10),
                            spotEstimate:  spotEstimate
                        });
                    })
                    .catch(function (err) {
                        if (errEl) errEl.textContent = "Could not fetch live spot: " + err.message;
                        if (btn) { btn.disabled = false; btn.textContent = "Activate Scope"; }
                    });
            });
        }

        var spCancel = document.getElementById("sp-cancel-btn");
        if (spCancel) {
            spCancel.addEventListener("click", function () {
                var dlg = document.getElementById("scope-picker-dialog");
                if (dlg) dlg.close();
            });
        }

        var setBtn = document.getElementById("scope-set-btn");
        if (setBtn) setBtn.addEventListener("click", _openDialog);

        var clearBtn = document.getElementById("scope-clear-btn");
        if (clearBtn) clearBtn.addEventListener("click", _clearScope);
    }

    function initScopePicker() {
        _wireDialog();
        _loadMetadata().then(function () {
            return _restoreActiveScope();
        }).then(function () {
            _renderFooterChip();
        });
    }

    function getActiveScope() {
        return _activeScope;
    }

    function onScopeChange(fn) {
        if (typeof fn === "function") _listeners.push(fn);
    }

    window.scopePicker = {
        initScopePicker: initScopePicker,
        getActiveScope:  getActiveScope,
        onScopeChange:   onScopeChange
    };

}());
