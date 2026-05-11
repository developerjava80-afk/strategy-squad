(function () {
    'use strict';

    /* â”€â”€ state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    var _internalKey  = null;
    var _metaByUl     = {};    // { NIFTY: OptionMetadataResponse, BANKNIFTY: OptionMetadataResponse }
    var _allContracts = [];    // flat list for typeahead: { sym, underlying, expiry, strike, optionType, lotSize }
    var _metadata     = null;  // currently selected underlying's metadata
    var _selected     = null;  // { underlying, expiry, strike, optionType, tradingSymbol, lotSize }
    var _quoteTimer       = null;
    var _execTimer        = null;
    var _orderSide        = null;
    var _sugActive        = -1;    // keyboard-highlighted suggestion index
    var _sessionOpenQty   = {};    // executionId -> openQuantity from position sessions (monitor-adjusted)
    var _positionsCache   = {};    // positionId -> last position row for action button lookup

    /* â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function isKiteMode() {
        return feedSource() === 'KITE';
    }
    function orderMode() {
        return (localStorage.getItem('ss.order.mode') || 'paper').toLowerCase() === 'real' ? 'real' : 'paper';
    }
    function feedSource() {
        return (localStorage.getItem('ss.feed.source') || 'KITE').toUpperCase() === 'DUMMY'
            ? 'DUMMY'
            : 'KITE';
    }
    function sourceParam(prefix) {
        return (prefix || '&') + 'source=' + encodeURIComponent(feedSource());
    }

    function fmt2(v) {
        if (v === null || v === undefined) return '-';
        return Number(v).toFixed(2);
    }
    function fmtPnl(v) {
        if (v === null || v === undefined) return '<span style="color:var(--muted)">-</span>';
        var n = Number(v);
        var cls = n >= 0 ? 'pnl-pos' : 'pnl-neg';
        return '<span class="' + cls + '">' + (n >= 0 ? '+' : '') + n.toFixed(0) + '</span>';
    }
    function fmtTime(isoStr) {
        if (!isoStr) return '-';
        try { return new Date(isoStr).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
        catch (e) { return isoStr; }
    }
    function fetchJson(url, opts) {
        return fetch(url, opts).then(function (r) {
            return r.json().then(function (data) {
                if (!r.ok) throw Object.assign(new Error(data.error || ('HTTP ' + r.status)), { data: data });
                return data;
            });
        });
    }

    // Build a human-readable symbol like "NIFTY 24000 CE  May-05"
    function buildSym(underlying, expiry, strike, optionType) {
        var parts = expiry.split('-');                       // ["2026","05","05"]
        var mon = parts.length === 3
            ? ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][parseInt(parts[1], 10) - 1]
            : '';
        var day = parts.length === 3 ? parts[2] : '';
        var fmt = mon ? (mon + '-' + day + '-' + parts[0].slice(2)) : expiry;
        return underlying + ' ' + strike + ' ' + optionType + '  ' + fmt;
    }

    /* â”€â”€ DOM refs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    var searchInput        = document.getElementById('instrument-search');
    var suggestionsList    = document.getElementById('suggestions-list');
    var underlyingBtns     = document.querySelectorAll('.underlying-btn');
    var expiryPicker       = document.getElementById('expiry-picker');
    var expirySelect       = document.getElementById('expiry-select');
    var contractPicker     = document.getElementById('contract-picker');
    var strikeSelect       = document.getElementById('strike-select');
    var optionTypeBtns     = document.querySelectorAll('.option-type-btn');
    var quoteCard          = document.getElementById('quote-card');
    var quoteInstrumentEl  = document.getElementById('quote-instrument');
    var quotePriceEl       = document.getElementById('quote-price');
    var quoteMetaEl        = document.getElementById('quote-meta');
    var quoteModeBadge     = document.getElementById('quote-mode-badge');
    var quickActions       = document.getElementById('quick-actions');
    var buyBtn             = document.getElementById('btn-buy');
    var sellBtn            = document.getElementById('btn-sell');
    var instrumentChip     = document.getElementById('selected-instrument-chip');
    var positionsTableBody = document.getElementById('positions-table-body');
    var positionsSummary   = document.getElementById('positions-summary');
    var orderLogTableBody  = document.getElementById('order-log-table-body');
    var orderLogSummary    = document.getElementById('order-log-summary');
    var modal              = document.getElementById('order-modal');
    var closeModalBtn      = document.getElementById('btn-close-modal');
    var cancelOrderBtn     = document.getElementById('btn-cancel-order');
    var orderForm          = document.getElementById('order-form');
    var modalTitle         = document.getElementById('modal-title');
    var apiStatusEl        = document.getElementById('order-api-status');
    var orderModeToggle    = document.getElementById('order-mode-toggle');
    var clearAllBtn        = document.getElementById('clear-all-btn');

    /* â”€â”€ boot â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function init() {
        // Bind events immediately â€” do NOT gate on async config load.
        // Typeahead and pickers must work the moment the page is ready.
        bindEvents();
        renderFeedBadge();
        startExecPolling();

        // Load internal key first (fast), then kick off preload with auth header
        loadClientConfig().then(function () {
            preloadAllMetadata();
        });
    }

    function loadClientConfig() {
        return fetch('/api/client-config')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                if (d && d.internalKey) _internalKey = d.internalKey;
                if (d && d.feedSourceDefault) {
                    try {
                        if (!localStorage.getItem('ss.feed.source')) {
                            localStorage.setItem('ss.feed.source', d.feedSourceDefault);
                            renderFeedBadge();
                        }
                    } catch (e) {}
                }
            })
            .catch(function () {});
    }

    function renderFeedBadge() {
        var isPaper = orderMode() === 'paper';
        if (apiStatusEl) {
            apiStatusEl.textContent = 'Feed: ' + feedSource();
            apiStatusEl.style.color = 'var(--muted)';
        }
        if (orderModeToggle) {
            orderModeToggle.textContent = isPaper ? '\uD83D\uDCC4 PAPER' : '\uD83D\uDFE2 REAL';
            orderModeToggle.style.background  = isPaper ? '#fff3cd' : '#d1e7dd';
            orderModeToggle.style.color       = isPaper ? '#856404' : '#0f5132';
            orderModeToggle.style.borderColor = isPaper ? '#ffc107' : '#198754';
            orderModeToggle.title = isPaper
                ? 'Paper trading active \u2014 click to switch to Real orders'
                : 'Real orders active \u2014 click to switch to Paper trading';
        }
        if (quoteModeBadge) {
            quoteModeBadge.textContent = isPaper ? 'PAPER' : 'REAL';
            quoteModeBadge.style.background = isPaper ? '#fff3cd' : '#d1e7dd';
            quoteModeBadge.style.color      = isPaper ? '#856404' : '#0f5132';
        }
    }

    function toggleOrderMode() {
        var current = orderMode();
        var next = current === 'paper' ? 'real' : 'paper';
        if (next === 'real') {
            if (!confirm('Switch to REAL orders? This will place actual orders via Kite. Are you sure?')) return;
        }
        localStorage.setItem('ss.order.mode', next);
        renderFeedBadge();
    }

    function clearAll() {
        if (!confirm('Clear all positions and trade log for today? This cannot be undone.')) return;
        var headers = {};
        if (_internalKey) headers['X-Internal-Key'] = _internalKey;
        fetch('/api/orders/executions', { method: 'DELETE', headers: headers })
            .then(function (r) {
                if (!r.ok) {
                    return r.json().then(function (d) {
                        throw new Error(d.error || ('Server error: HTTP ' + r.status));
                    });
                }
                return r.json();
            })
            .then(function (data) {
                refreshAll();
                alert('Cleared all trading state for today. Orders removed: '
                    + (data && data.deleted != null ? data.deleted : 0)
                    + ', sessions removed: '
                    + (data && data.deletedSessions != null ? data.deletedSessions : 0)
                    + '. Dashboard and Monitor will refresh automatically.');
            })
            .catch(function (err) {
                alert('Clear failed: ' + (err.message || 'Unknown error'));
            });
    }

    /* â”€â”€ pre-load metadata for all underlyings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function preloadAllMetadata() {
        var underlyings = ['NIFTY', 'BANKNIFTY'];
        var headers = {};
        if (_internalKey) headers['X-Internal-Key'] = _internalKey;
        var pending = underlyings.length;

        searchInput.placeholder = 'Loading contracts...';

        underlyings.forEach(function (ul) {
            fetchJson('/api/orders/options?underlying=' + ul + sourceParam('&'), { headers: headers })
                .then(function (data) {
                    _metaByUl[ul] = data;
                    buildContractIndex(data);
                })
                .catch(function (err) {
                    console.error('Failed to load metadata for ' + ul + ':', err);
                })
                .finally(function () {
                    pending--;
                    if (pending === 0) {
                        searchInput.placeholder = _allContracts.length > 0
                            ? 'Type NIF, BANK, 24000 CE... (' + _allContracts.length + ' contracts)'
                            : 'No contracts found - check DB connection';
                    }
                });
        });
    }

    function buildContractIndex(data) {
        var ul = data.underlying;
        (data.expiries || []).forEach(function (eb) {
            (eb.strikes || []).forEach(function (strike) {
                ['CE', 'PE'].forEach(function (optType) {
                    _allContracts.push({
                        underlying:  ul,
                        expiry:      eb.expiry,
                        strike:      String(strike),
                        optionType:  optType,
                        lotSize:     eb.lotSize,
                        sym:         buildSym(ul, eb.expiry, strike, optType),
                        // searchable tokens: underlying + strike + optionType + expiry
                        tokens:      (ul + ' ' + strike + ' ' + optType + ' ' + eb.expiry).toUpperCase()
                    });
                });
            });
        });
    }

    /* â”€â”€ typeahead search â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function handleSearchInput() {
        var raw = searchInput.value.trim();
        if (!raw) { hideSuggestions(); return; }

        var q = raw.toUpperCase();
        // Match against: underlying, strike, optionType, expiry in any order
        var terms = q.split(/\s+/);
        var matches = _allContracts.filter(function (c) {
            return terms.every(function (t) { return c.tokens.includes(t); });
        });

        // Limit to 40 results; sort: nearest expiry first, then by strike
        matches = matches.slice(0, 40);

        renderSuggestions(matches);
    }

    function renderSuggestions(matches) {
        _sugActive = -1;
        if (!matches.length) {
            if (_allContracts.length === 0) {
                suggestionsList.innerHTML = '<div class="search-hint">Loading instruments...</div>';
            } else {
                suggestionsList.innerHTML = '<div class="search-hint">No contracts found</div>';
            }
            suggestionsList.hidden = false;
            return;
        }

        suggestionsList.innerHTML = matches.map(function (c, i) {
            var typeClass = c.optionType === 'CE' ? 'sug-type-ce' : 'sug-type-pe';
            var expiryShort = c.expiry;   // already YYYY-MM-DD, readable enough
            return '<div class="suggestion-item" data-idx="' + i + '">' +
                '<span class="sug-sym">' + c.underlying + ' ' + c.strike +
                ' <span class="' + typeClass + '">' + c.optionType + '</span></span>' +
                '<span class="sug-expiry">' + expiryShort + '</span>' +
            '</div>';
        }).join('');

        // Attach click handlers
        suggestionsList.querySelectorAll('.suggestion-item').forEach(function (el) {
            el.addEventListener('mousedown', function (e) {
                // mousedown before blur â€” prevent input losing focus first
                e.preventDefault();
                var idx = parseInt(el.getAttribute('data-idx'), 10);
                selectSuggestion(matches[idx]);
            });
        });

        suggestionsList.hidden = false;
    }

    function hideSuggestions() {
        suggestionsList.hidden = true;
        suggestionsList.innerHTML = '';
        _sugActive = -1;
    }

    function selectSuggestion(contract) {
        hideSuggestions();
        searchInput.value = contract.underlying + ' ' + contract.strike + ' ' + contract.optionType + '  ' + contract.expiry;
        searchInput.blur();

        // Sync the picker widgets to match
        _metadata = _metaByUl[contract.underlying] || null;

        // Highlight the matching underlying button
        underlyingBtns.forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-underlying') === contract.underlying);
        });

        // Populate expiry dropdown, select the right one
        if (_metadata) {
            populateExpiries(_metadata.expiries || [], contract.expiry);
        }

        // Set strike & option type, then load quote
        _selected = {
            underlying: contract.underlying,
            expiry:     contract.expiry,
            strike:     contract.strike,
            optionType: contract.optionType
        };

        // Sync strike select
        if (strikeSelect.querySelector('option[value="' + contract.strike + '"]')) {
            strikeSelect.value = contract.strike;
        }

        // Sync CE/PE buttons
        optionTypeBtns.forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-type') === contract.optionType);
        });

        loadQuote();
    }

    /* â”€â”€ keyboard navigation in suggestions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function handleSearchKeydown(e) {
        if (suggestionsList.hidden) return;
        var items = suggestionsList.querySelectorAll('.suggestion-item');
        if (!items.length) return;

        if (e.key === 'ArrowDown') {
            e.preventDefault();
            _sugActive = Math.min(_sugActive + 1, items.length - 1);
            highlightSuggestion(items);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            _sugActive = Math.max(_sugActive - 1, 0);
            highlightSuggestion(items);
        } else if (e.key === 'Enter') {
            e.preventDefault();
            if (_sugActive >= 0 && items[_sugActive]) {
                items[_sugActive].dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
            }
        } else if (e.key === 'Escape') {
            hideSuggestions();
        }
    }

    function highlightSuggestion(items) {
        items.forEach(function (el, i) {
            el.classList.toggle('active', i === _sugActive);
        });
        if (_sugActive >= 0 && items[_sugActive]) {
            items[_sugActive].scrollIntoView({ block: 'nearest' });
        }
    }

    /* â”€â”€ event binding â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function bindEvents() {
        searchInput.addEventListener('input',   handleSearchInput);
        searchInput.addEventListener('keydown', handleSearchKeydown);
        searchInput.addEventListener('focus',   function () {
            if (searchInput.value.trim()) handleSearchInput();
        });
        searchInput.addEventListener('blur',    function () {
            // small delay so mousedown on suggestion fires first
            setTimeout(hideSuggestions, 150);
        });

        underlyingBtns.forEach(function (btn) {
            btn.addEventListener('click', function () {
                underlyingBtns.forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');
                searchInput.value = '';
                loadMetadata(btn.getAttribute('data-underlying'));
            });
        });

        expirySelect.addEventListener('change', onExpiryChange);
        strikeSelect.addEventListener('change', onContractChange);

        optionTypeBtns.forEach(function (btn) {
            btn.addEventListener('click', function () {
                optionTypeBtns.forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');
                onContractChange();
            });
        });

        buyBtn.addEventListener('click',  function () { openOrderModal('BUY'); });
        sellBtn.addEventListener('click', function () { openOrderModal('SELL'); });
        closeModalBtn.addEventListener('click', closeModal);
        cancelOrderBtn.addEventListener('click', closeModal);
        orderForm.addEventListener('submit', handleOrderSubmit);
        if (orderModeToggle) orderModeToggle.addEventListener('click', toggleOrderMode);
        if (clearAllBtn)    clearAllBtn.addEventListener('click', clearAll);
    }

    /* â”€â”€ metadata load (from button click) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function loadMetadata(underlying) {
        expiryPicker.hidden = true;
        contractPicker.hidden = true;
        quoteCard.hidden = true;
        quickActions.hidden = true;
        instrumentChip.textContent = 'Loading...';

        // Use cached if already loaded
        if (_metaByUl[underlying]) {
            _metadata = _metaByUl[underlying];
            populateExpiries(_metadata.expiries || [], null);
            renderFeedBadge();
            return;
        }

        var headers = {};
        if (_internalKey) headers['X-Internal-Key'] = _internalKey;
        fetchJson('/api/orders/options?underlying=' + underlying + sourceParam('&'), { headers: headers })
            .then(function (data) {
                _metaByUl[underlying] = data;
                buildContractIndex(data);
                _metadata = data;
                populateExpiries(data.expiries || [], null);
                renderFeedBadge();
            })
            .catch(function (err) {
                instrumentChip.textContent = 'No instrument selected';
                alert('Could not load option metadata: ' + (err.message || 'Unknown error'));
            });
    }

    function populateExpiries(expiries, selectExpiry) {
        expirySelect.innerHTML = '';
        expiries.forEach(function (eb) {
            var opt = document.createElement('option');
            opt.value = eb.expiry;
            opt.textContent = eb.expiry + '  (lot ' + eb.lotSize + ')';
            if (selectExpiry && eb.expiry === selectExpiry) opt.selected = true;
            expirySelect.appendChild(opt);
        });
        expiryPicker.hidden = false;
        onExpiryChange();
    }

    function onExpiryChange() {
        var expiry = expirySelect.value;
        if (!expiry || !_metadata) return;
        var eb = (_metadata.expiries || []).find(function (e) { return e.expiry === expiry; });
        if (!eb) return;
        strikeSelect.innerHTML = '';
        (eb.strikes || []).forEach(function (s) {
            var opt = document.createElement('option');
            opt.value = s;
            opt.textContent = s;
            strikeSelect.appendChild(opt);
        });
        // If _selected has a specific strike, select it
        if (_selected && _selected.expiry === expiry && _selected.strike) {
            strikeSelect.value = _selected.strike;
        }
        contractPicker.hidden = false;
        onContractChange();
    }

    function onContractChange() {
        var underlying = (_metadata && _metadata.underlying) || '';
        var expiry     = expirySelect.value;
        var strike     = strikeSelect.value;
        var activeBtn  = document.querySelector('.option-type-btn.active');
        var optionType = activeBtn ? activeBtn.getAttribute('data-type') : 'CE';
        if (!underlying || !expiry || !strike) return;
        _selected = { underlying: underlying, expiry: expiry, strike: strike, optionType: optionType };
        loadQuote();
    }

    /* â”€â”€ quote â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function loadQuote() {
        if (!_selected) return;
        if (_quoteTimer) clearTimeout(_quoteTimer);
        var url = '/api/orders/quote?underlying=' + _selected.underlying
                + '&expiry=' + _selected.expiry
                + '&strike=' + _selected.strike
                + '&optionType=' + _selected.optionType
                + sourceParam('&');
        fetchJson(url)
            .then(function (data) {
                var contract = data.contract || {};
                var quote    = data.quote    || {};
                _selected.tradingSymbol = contract.tradingSymbol || '';
                _selected.lotSize       = contract.lotSize       || 1;

                var sym = contract.tradingSymbol
                        || (_selected.underlying + ' ' + _selected.expiry + ' ' + _selected.strike + _selected.optionType);
                quoteInstrumentEl.textContent = sym;
                quotePriceEl.textContent  = quote.price != null ? '\u20B9' + fmt2(quote.price) : '-';
                quoteMetaEl.textContent   = quote.source
                        ? 'Source: ' + quote.source + (quote.stale ? ' (stale)' : '') : '';
                quoteCard.hidden    = false;
                quickActions.hidden = false;
                instrumentChip.textContent = sym;
                renderFeedBadge();

                _quoteTimer = setTimeout(loadQuote, 15000);
            })
            .catch(function () {
                quotePriceEl.textContent = 'Quote unavailable';
                quoteCard.hidden    = false;
                quickActions.hidden = false;
                instrumentChip.textContent = _selected.underlying + ' ' + _selected.strike + _selected.optionType;
            });
    }

    /* â”€â”€ order modal â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function openOrderModal(side) {
        if (!_selected) return;
        _orderSide = side;
        var sym = _selected.tradingSymbol
                || (_selected.underlying + ' ' + _selected.strike + _selected.optionType);
        modalTitle.textContent = (side === 'BUY' ? 'Buy' : 'Sell') + ' - ' + sym
                + '  [' + orderMode().toUpperCase() + ']';
        modal.hidden = false;
        document.body.style.overflow = 'hidden';
    }

    function closeModal() {
        modal.hidden = true;
        document.body.style.overflow = '';
        orderForm.reset();
    }

    /* â”€â”€ order submit â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function handleOrderSubmit(e) {
        e.preventDefault();
        if (!_selected || !_orderSide) { alert('No instrument selected.'); return; }

        var lots      = parseInt(document.getElementById('order-lots').value, 10) || 1;
        var priceVal  = document.getElementById('order-price').value;
        var orderType = document.querySelector('input[name="order-type"]:checked').value;
        var product   = document.querySelector('input[name="order-product"]:checked').value;
        var price     = (priceVal && parseFloat(priceVal) > 0) ? parseFloat(priceVal) : null;

        var payload = {
            mode:            orderMode(),
            source:          feedSource(),
            underlying:      _selected.underlying,
            expiry:          _selected.expiry,
            strike:          parseFloat(_selected.strike),
            optionType:      _selected.optionType,
            transactionType: _orderSide,
            lots:            lots,
            orderType:       orderType,
            product:         product,
            price:           price
        };

        var headers = { 'Content-Type': 'application/json' };
        if (_internalKey) headers['X-Internal-Key'] = _internalKey;

        var submitBtn = document.getElementById('btn-submit-order');
        submitBtn.disabled = true;
        submitBtn.textContent = 'Placing...';

        fetchJson('/api/orders/place', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(payload)
        })
        .then(function (result) {
            closeModal();
            refreshAll();
            var sym = result.tradingSymbol
                    || (_selected.underlying + ' ' + _selected.strike + _selected.optionType);
            alert('Order placed: ' + _orderSide + ' ' + lots + ' lot(s) of ' + sym
                + '\nStatus: ' + result.status
                + (result.entryPrice ? '\nEntry: Rs.' + fmt2(result.entryPrice) : ''));
        })
        .catch(function (err) {
            var msg = err.data && err.data.details ? err.data.details : (err.message || 'Unknown error');
            alert('Order failed: ' + msg);
        })
        .finally(function () {
            submitBtn.disabled = false;
            submitBtn.textContent = 'Place Order';
        });
    }

    /* â”€â”€ executions / P&L â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function startExecPolling() {
        refreshAll();
        _execTimer = setInterval(refreshAll, 5000);
    }

    function refreshAll() {
        refreshPositions();
        refreshOrderLog();
        refreshStrategies();
        refreshSessionState();
    }

    function refreshPositions() {
        fetchJson('/api/orders/positions' + sourceParam('?')).catch(function () { return []; }).then(function (rows) {
            renderPositions(rows || []);
        });
    }

    function refreshOrderLog() {
        fetchJson('/api/orders/log').catch(function () { return []; }).then(function (rows) {
            renderOrderLog(rows || []);
        });
    }

    function refreshStrategies() {
        fetchJson('/api/orders/strategies' + sourceParam('?')).catch(function () { return []; }).then(function (rows) {
            renderStrategies(rows || []);
        });
    }

    function refreshSessionState() {
        fetchJson('/api/position-sessions').catch(function () { return []; }).then(function (data) {
            var sessions = Array.isArray(data) ? data
                : (data && data.sessions ? data.sessions : []);
            var map = {};
            sessions.forEach(function (s) {
                (s.legs || []).forEach(function (leg) {
                    if (leg.executionId) {
                        map[leg.executionId] = leg.openQuantity != null ? Number(leg.openQuantity) : null;
                    }
                });
            });
            _sessionOpenQty = map;
        });
    }

    /* ── Strategy Performance & Adjustment ───────────────────────────────────── */
    function renderStrategies(rows) {
        var listEl = document.getElementById('strategy-perf-list');
        var sumEl  = document.getElementById('strategy-perf-summary');
        if (!listEl) return;

        if (!rows || rows.length === 0) {
            listEl.innerHTML = '<div class="empty-cell" style="text-align:center;padding:1.5rem;color:var(--muted);">'
                + 'Place a basket from Strategy Lab to start tracking.</div>';
            if (sumEl) sumEl.textContent = 'No active strategies';
            return;
        }

        var totalLive = 0, totalBooked = 0;
        var critCount = 0, breachCount = 0, watchCount = 0;
        rows.forEach(function (s) {
            if (s.livePnl   != null) totalLive   += Number(s.livePnl);
            totalBooked += Number(s.bookedPnl || 0);
            var st = (s.risk && s.risk.state) || 'UNKNOWN';
            if (st === 'CRITICAL') critCount++;
            else if (st === 'BREACH') breachCount++;
            else if (st === 'WATCH') watchCount++;
        });
        if (sumEl) {
            sumEl.style.color = '';
            sumEl.innerHTML = rows.length + ' strategies'
                + (critCount  ? ' · <span class="pnl-neg">' + critCount + ' CRITICAL</span>' : '')
                + (breachCount ? ' · <span class="pnl-neg">' + breachCount + ' BREACH</span>' : '')
                + (watchCount ? ' · <span style="color:#856404">' + watchCount + ' WATCH</span>' : '')
                + ' · Live ' + fmtPnl(totalLive) + ' · Booked ' + fmtPnl(totalBooked);
        }

        listEl.innerHTML = rows.map(renderStrategyCard).join('');
    }

    function renderStrategyCard(s) {
        var risk = s.risk || { state: 'UNKNOWN', reason: '' };
        var stateColors = {
            OK:       { bg: '#d4edda', fg: '#155724', bd: '#c3e6cb' },
            WATCH:    { bg: '#fff3cd', fg: '#856404', bd: '#ffeeba' },
            BREACH:   { bg: '#f8d7da', fg: '#721c24', bd: '#f5c6cb' },
            CRITICAL: { bg: '#dc3545', fg: '#fff',    bd: '#dc3545' },
            UNKNOWN:  { bg: '#e9ecef', fg: '#495057', bd: '#dee2e6' }
        };
        var c = stateColors[risk.state] || stateColors.UNKNOWN;

        var deltaCell = '—';
        if (s.netDelta != null) {
            var dc = s.netDelta >= 0 ? 'pnl-pos' : 'pnl-neg';
            deltaCell = '<span class="' + dc + '">' + (s.netDelta >= 0 ? '+' : '') + Number(s.netDelta).toFixed(2) + '</span>'
                + ' <small style="color:var(--muted)">(' + (s.netDeltaPerShare != null
                    ? (s.netDeltaPerShare >= 0 ? '+' : '') + Number(s.netDeltaPerShare).toFixed(3) + '/sh'
                    : '—') + ')</small>';
        }
        var thetaCell = '—';
        if (s.netThetaBenefit != null) {
            var tc = s.netThetaBenefit >= 0 ? 'pnl-pos' : 'pnl-neg';
            thetaCell = '<span class="' + tc + '">' + (s.netThetaBenefit >= 0 ? '+' : '')
                + Number(s.netThetaBenefit).toFixed(0) + ' ₹/5m</span>';
        }

        var legsHtml = (s.legs || []).map(function (l) {
            var sideCol = l.transactionType === 'SELL' ? '#dc3545' : '#198754';
            var d = l.d5m != null ? Number(l.d5m).toFixed(3) : '—';
            var origLots = l.lots != null ? l.lots : null;
            var adjLots  = (l.executionId && _sessionOpenQty[l.executionId] != null)
                           ? _sessionOpenQty[l.executionId] : null;
            var lotsHtml = (adjLots != null && adjLots !== origLots)
                ? '<s style="color:var(--muted);font-size:0.8em">' + (origLots != null ? origLots : '—') + '</s>&thinsp;→&thinsp;<strong style="color:#198754">' + adjLots + '</strong>'
                : (origLots != null ? origLots : '—');
            return '<tr>'
                + '<td>' + (l.tradingSymbol || '—') + '</td>'
                + '<td><strong style="color:' + sideCol + '">' + l.transactionType + '</strong></td>'
                + '<td>' + lotsHtml + '</td>'
                + '<td>₹' + fmt2(l.entryPrice) + '</td>'
                + '<td>' + (l.currentPrice != null ? '₹' + fmt2(l.currentPrice) : '—') + '</td>'
                + '<td>' + d + '</td>'
                + '<td>' + fmtPnl(l.unbookedPnl) + '</td>'
                + '</tr>';
        }).join('');

        var sugHtml = (s.adjustments || []).map(function (a) {
            var kindIcon = { ROLL: '🔄', HEDGE: '🛡️', REDUCE: '✂️', EXIT: '🚪', HOLD: '✅' }[a.kind] || '•';
            var pri = a.priority <= 2 ? '#dc3545' : (a.priority <= 4 ? '#856404' : '#198754');
            return '<li style="padding:6px 8px;border-left:3px solid ' + pri + ';margin:4px 0;background:#fafbfc;">'
                + '<div><strong>' + kindIcon + ' ' + a.action + '</strong>'
                + '<span style="float:right;font-size:0.72rem;color:var(--muted)">P' + a.priority + ' · Δ ' + (a.expectedDeltaImpact || '—') + '</span></div>'
                + '<div style="font-size:0.82rem;color:#333;margin-top:2px">' + a.rationale + '</div>'
                + '</li>';
        }).join('');

        return '<div class="strategy-card" style="border:1.5px solid ' + c.bd + ';border-radius:8px;background:#fff;overflow:hidden;">'
            + '<div style="display:flex;justify-content:space-between;align-items:center;padding:10px 14px;background:' + c.bg + ';color:' + c.fg + ';">'
            +   '<div><strong>' + (s.strategyLabel || s.strategyType) + '</strong>'
            +     ' <span style="font-size:0.75rem;opacity:0.8">· ' + s.legCount + ' legs · ' + s.totalLots + ' lots</span></div>'
            +   '<div style="font-size:0.85rem;font-weight:700">' + risk.state + '</div>'
            + '</div>'
            + '<div style="padding:10px 14px;font-size:0.8rem;color:#555;border-bottom:1px solid #f0f0f0;">' + (risk.reason || '') + '</div>'
            + '<div style="display:grid;grid-template-columns:repeat(4, 1fr);gap:10px;padding:10px 14px;font-size:0.85rem;">'
            +   '<div><div style="color:var(--muted);font-size:0.7rem">NET Δ</div><div>' + deltaCell + '</div></div>'
            +   '<div><div style="color:var(--muted);font-size:0.7rem">NET Θ benefit</div><div>' + thetaCell + '</div></div>'
            +   '<div><div style="color:var(--muted);font-size:0.7rem">Live P&amp;L</div><div>' + fmtPnl(s.livePnl) + '</div></div>'
            +   '<div><div style="color:var(--muted);font-size:0.7rem">Booked</div><div>' + fmtPnl(s.bookedPnl) + '</div></div>'
            + '</div>'
            + '<div style="padding:0 14px 10px;">'
            +   '<table class="data-table" style="font-size:0.8rem;">'
            +     '<thead><tr><th>Symbol</th><th>Side</th><th>Lots</th><th>Entry</th><th>LTP</th><th>Δ (5m)</th><th>Live P&amp;L</th></tr></thead>'
            +     '<tbody>' + legsHtml + '</tbody>'
            +   '</table>'
            + '</div>'
            + (sugHtml
                ? '<div style="padding:0 14px 12px;"><div style="font-size:0.78rem;font-weight:700;color:var(--muted);margin-bottom:4px;">SUGGESTED ADJUSTMENTS</div><ul style="list-style:none;padding:0;margin:0;">' + sugHtml + '</ul></div>'
                : '')
            + '</div>';
    }

    /* â”€â”€ position tracking â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function renderPositions(rows) {
        if (!positionsTableBody) return;
        if (!rows || rows.length === 0) {
            positionsTableBody.innerHTML = '<tr><td colspan="11" class="empty-cell">No positions yet</td></tr>';
            if (positionsSummary) positionsSummary.innerHTML = '';
            return;
        }

        var openCount = 0, closedCount = 0;
        var totalLive = 0, totalBooked = 0, hasLive = false;
        rows.forEach(function (r) {
            if (r.status === 'OPEN') openCount++; else closedCount++;
            if (r.livePnl   != null) { totalLive   += Number(r.livePnl); hasLive = true; }
            if (r.bookedPnl != null) { totalBooked += Number(r.bookedPnl); }
        });

        if (positionsSummary) {
            var liveHtml = hasLive
                ? '<span class="' + (totalLive >= 0 ? 'pnl-pos' : 'pnl-neg') + '">' + (totalLive >= 0 ? '+' : '') + totalLive.toFixed(0) + '</span>'
                : '<span style="color:var(--muted)">—</span>';
            var bClass = totalBooked >= 0 ? 'pnl-pos' : 'pnl-neg';
            positionsSummary.innerHTML =
                openCount + ' open · ' + closedCount + ' closed'
                + ' &nbsp;|&nbsp; Live ' + liveHtml
                + ' &nbsp;|&nbsp; Booked <span class="' + bClass + '">' + (totalBooked >= 0 ? '+' : '') + totalBooked.toFixed(0) + '</span>';
        }

        positionsTableBody.innerHTML = rows.map(function (r) {
            var sideLabel = r.side || (r.transactionType === 'SELL' ? 'SHORT' : 'LONG');
            var sideColor = sideLabel === 'SHORT' ? '#dc3545' : '#198754';
            var sideHtml  = '<strong style="color:' + sideColor + '">' + sideLabel + '</strong>';
            var qtyChange = (r.currentLots != null && r.originalLots != null && r.currentLots !== r.originalLots);
            var curQtyHtml = qtyChange
                ? '<strong style="color:#856404">' + r.currentLots + '</strong>'
                : (r.currentLots != null ? r.currentLots : '-');
            var statusPill;
            if (r.status === 'OPEN') {
                statusPill = '<span style="background:#d4edda;color:#155724;padding:2px 8px;border-radius:10px;font-size:0.72rem;font-weight:600;">OPEN</span>';
            } else {
                statusPill = '<span style="background:#e9ecef;color:#495057;padding:2px 8px;border-radius:10px;font-size:0.72rem;font-weight:600;">CLOSED</span>';
            }
            var actionBtn;
            if (r.status === 'OPEN') {
                var addSide   = (sideLabel === 'SHORT') ? 'SELL' : 'BUY';
                var closeSide = (sideLabel === 'SHORT') ? 'BUY'  : 'SELL';
                actionBtn =
                    '<button type="button" class="position-action-btn" '
                    + 'data-position-id="' + (r.positionId || '') + '" '
                    + 'data-action="ADD" data-side="' + addSide + '" '
                    + 'style="font-size:0.7rem;padding:3px 8px;margin-right:4px;background:#198754;color:#fff;border:none;border-radius:4px;cursor:pointer;">+Add</button>'
                    + '<button type="button" class="position-action-btn" '
                    + 'data-position-id="' + (r.positionId || '') + '" '
                    + 'data-action="REDUCE" data-side="' + closeSide + '" '
                    + 'style="font-size:0.7rem;padding:3px 8px;background:#dc3545;color:#fff;border:none;border-radius:4px;cursor:pointer;">−Reduce</button>';
            } else {
                actionBtn = '<span style="color:var(--muted);font-size:0.75rem;">—</span>';
            }
            return '<tr>'
                + '<td>' + fmtTime(r.firstFillAt) + '</td>'
                + '<td>' + (r.tradingSymbol || '-') + '</td>'
                + '<td>' + sideHtml + '</td>'
                + '<td>' + (r.originalLots != null ? r.originalLots : '-') + '</td>'
                + '<td>' + curQtyHtml + '</td>'
                + '<td>&#x20B9;' + fmt2(r.avgEntryPrice) + '</td>'
                + '<td>' + (r.currentPrice != null ? '&#x20B9;' + fmt2(r.currentPrice) : '-') + '</td>'
                + '<td>' + fmtPnl(r.livePnl) + '</td>'
                + '<td>' + fmtPnl(r.bookedPnl) + '</td>'
                + '<td>' + statusPill + '</td>'
                + '<td>' + actionBtn + '</td>'
                + '</tr>';
        }).join('');

        // Wire action buttons
        var btns = positionsTableBody.querySelectorAll('.position-action-btn');
        for (var i = 0; i < btns.length; i++) {
            btns[i].addEventListener('click', onPositionActionClick);
        }
        // Cache rows for action handler lookup
        _positionsCache = {};
        rows.forEach(function (r) { if (r.positionId) _positionsCache[r.positionId] = r; });
    }

    function onPositionActionClick(e) {
        var btn = e.currentTarget;
        var posId  = btn.getAttribute('data-position-id');
        var action = btn.getAttribute('data-action');
        var side   = btn.getAttribute('data-side');
        var pos    = _positionsCache[posId];
        if (!pos) { alert('Position not found.'); return; }

        // Pre-fill _selected from the position so the existing modal works
        _selected = {
            underlying:    pos.underlying,
            expiry:        pos.expiry,
            strike:        pos.strike,
            optionType:    pos.optionType,
            tradingSymbol: pos.tradingSymbol,
            instrumentToken: null,
            lotSize:       pos.lotSize
        };
        instrumentChip.textContent = pos.tradingSymbol || (pos.underlying + ' ' + pos.strike + pos.optionType);

        // Open the modal in side mode; pre-fill lots = currentLots for REDUCE
        _orderSide = side;
        modalTitle.textContent = (action === 'ADD' ? 'Add to' : 'Reduce')
            + ' ' + (pos.tradingSymbol || (pos.underlying + ' ' + pos.strike + pos.optionType))
            + '  [' + (action === 'ADD' ? side : side) + ' · ' + orderMode().toUpperCase() + ']';

        var lotsInput = document.getElementById('order-lots');
        if (lotsInput) {
            lotsInput.value = (action === 'REDUCE' && pos.currentLots) ? pos.currentLots : 1;
        }
        modal.hidden = false;
        document.body.style.overflow = 'hidden';
    }

    /* â”€â”€ order log â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */
    function renderOrderLog(rows) {
        if (!orderLogTableBody) return;
        if (!rows || rows.length === 0) {
            orderLogTableBody.innerHTML = '<tr><td colspan="10" class="empty-cell">No orders today</td></tr>';
            if (orderLogSummary) orderLogSummary.textContent = '';
            return;
        }
        if (orderLogSummary) orderLogSummary.textContent = rows.length + ' events today';

        var typeColors  = { PLACE: '#0d6efd', REDUCE: '#fd7e14', CLOSE: '#6610f2' };
        var srcColors   = { MANUAL: '#6c757d', MONITOR: '#0dcaf0', STRATEGY_LAB: '#198754' };

        orderLogTableBody.innerHTML = rows.map(function (e) {
            var typeColor = typeColors[e.type] || '#495057';
            var srcColor  = srcColors[e.source] || '#6c757d';
            var statusPill;
            if (e.status === 'COMPLETED') {
                statusPill = '<span style="background:#d4edda;color:#155724;padding:2px 8px;border-radius:10px;font-size:0.72rem;font-weight:600;">COMPLETED</span>';
            } else if (e.status === 'REJECTED') {
                statusPill = '<span style="background:#f8d7da;color:#721c24;padding:2px 8px;border-radius:10px;font-size:0.72rem;font-weight:600;">REJECTED</span>';
            } else {
                statusPill = '<span style="background:#e9ecef;color:#495057;padding:2px 8px;border-radius:10px;font-size:0.72rem;font-weight:600;">' + (e.status || '-') + '</span>';
            }
            var sideCol = e.side === 'SELL' ? '#dc3545' : (e.side === 'BUY' ? '#198754' : '#495057');
            var bookedCell = (e.bookedPnlDelta != null && e.bookedPnlDelta !== 0)
                ? fmtPnl(e.bookedPnlDelta)
                : '<span style="color:var(--muted)">—</span>';
            return '<tr>'
                + '<td>' + fmtTime(e.timestamp) + '</td>'
                + '<td><strong style="color:' + typeColor + '">' + (e.type || '-') + '</strong></td>'
                + '<td><span style="background:' + srcColor + ';color:#fff;padding:2px 6px;border-radius:4px;font-size:0.7rem;font-weight:600;">' + (e.source || '-') + '</span></td>'
                + '<td>' + (e.tradingSymbol || '-') + '</td>'
                + '<td>' + (e.side ? '<strong style="color:' + sideCol + '">' + e.side + '</strong>' : '-') + '</td>'
                + '<td>' + (e.lots != null ? e.lots : '-') + '</td>'
                + '<td>' + (e.price != null ? '&#x20B9;' + fmt2(e.price) : '-') + '</td>'
                + '<td>' + bookedCell + '</td>'
                + '<td>' + statusPill + '</td>'
                + '<td style="font-size:0.75rem;color:var(--muted);">' + (e.message || '') + '</td>'
                + '</tr>';
        }).join('');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
