'use strict';

// ── Yearly view: own fetch of raw transactions and its own FIFO calculation for the sell-tile breakdown, not shared with holdings.js ──

const YEARLY_APEX_DEFAULTS = {
    chart:   { background: 'transparent', fontFamily: "'IBM Plex Mono', monospace", toolbar: { show: false } },
    theme:   { mode: 'dark' },
    tooltip: {
        theme: 'dark',
        style: { fontFamily: "'IBM Plex Mono', monospace", fontSize: '12px' }
    },
    grid: { borderColor: '#252830' },
    dataLabels: { enabled: false },
    xaxis: { labels: { style: { colors: '#6b6f7a' } } },
    legend: {
        fontSize:   '11px',
        fontFamily: "'IBM Plex Mono', monospace",
        labels:     { colors: '#6b6f7a' },
        markers:    { width: 10, height: 10, radius: 2 }
    }
};

const YEARLY_BALANCE_COLOR = '#F7931A'; // Bitcoin orange
const YEARLY_VALUE_COLOR   = '#1d9e75'; // green (matches --pos)
const YEARLY_PRICE_COLOR   = '#7c5cff'; // violet, distinct from balance/value since it's its own chart
const YEARLY_TAX_FREE_DAYS = 365;       // German tax holding period, informational only, not tax advice

// Tax-free if held >= 365 days and bought before the cutoff date; coins bought on/after the cutoff are always taxable. Informational only.
function _yearlyIsTaxFree(c) {
    if (c.days < YEARLY_TAX_FREE_DAYS) return false;
    if (_yearlyTaxCutoffDate && c.buyTx.date
        && String(c.buyTx.date).substring(0, 10) >= _yearlyTaxCutoffDate) {
        return false;
    }
    return true;
}

const YEARLY_MONTH_SHORT_DE = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'];

let _yearlyChart          = null;
let _yearlyPriceChart     = null; // standalone price chart, overview only
let _yearlySelectedYear   = null; // null = overview (all years)
let _yearlyAllTx          = null; // lazy-loaded, cached across year switches
let _yearlyCurrentPrice   = 0;
let _yearlyAvailableYears = [];
let _yearlyOverviewSeq    = 0; // request generation counter, prevents a late response from a stale fetch overwriting the current view

// ── Persist year selection + tile toggles across reloads, as 3 separate localStorage keys so resetting one doesn't affect the others ──
const YEARLY_SELECTED_YEAR_KEY    = 'yearly-selected-year-v1';
const YEARLY_TILES_FILTER_KEY     = 'yearly-tiles-filter-v1';
const YEARLY_TRANSFERS_FILTER_KEY = 'yearly-transfers-filter-v1';

function _yearlySaveSelectedYear(year) {
    try {
        if (year == null) localStorage.removeItem(YEARLY_SELECTED_YEAR_KEY);
        else localStorage.setItem(YEARLY_SELECTED_YEAR_KEY, String(year));
    } catch (e) { /* ignore */ }
}

function _yearlyLoadSelectedYear() {
    try {
        const raw = localStorage.getItem(YEARLY_SELECTED_YEAR_KEY);
        if (raw == null) return null;
        const num = Number(raw);
        return Number.isFinite(num) ? num : null;
    } catch (e) { return null; }
}

async function initYearly() {
    const yearSelect = document.getElementById('yearlyYearSelect');
    yearSelect.addEventListener('change', () => yearlySelectYear(yearSelect.value));

    const priceModal = document.getElementById('yearlyPriceModal');
    if (priceModal) priceModal.addEventListener('show.bs.modal', yearlyLoadPriceModal);

    // restore saved tile toggle states before the first render to avoid a flash of defaults
    _yearlyRestoreTilesFilter();
    _yearlyRestoreTransfersFilter();

    const savedYear = _yearlyLoadSelectedYear();
    _yearlySelectedYear = savedYear;
    await yearlyLoadOverview(savedYear);
}

// Hook called by tx-form.js after saving/editing a transaction from a tile; reloads chart + tiles since one change can affect several at once.
function onTxSaved() {
    _yearlyAllTx = null;
    yearlyLoadOverview(_yearlySelectedYear);
}

function yearlySelectYear(value) {
    const year = value ? Number(value) : null;
    _yearlySelectedYear = year;
    _yearlySaveSelectedYear(year);
    yearlyLoadOverview(year);
}

async function yearlyLoadOverview(year) {
    const seq = ++_yearlyOverviewSeq; // diese Anfrage als "aktuellste" markieren

    const loadingEl = document.getElementById('yearlyLoading');
    const emptyEl   = document.getElementById('yearlyEmpty');
    const contentEl = document.getElementById('yearlyContent');
    const tilesSection = document.getElementById('yearly-block-tiles');
    const priceChartSection = document.getElementById('yearly-block-pricechart');
    const transfersSection = document.getElementById('yearly-block-transfers');

    loadingEl.classList.remove('d-none');
    emptyEl.classList.add('d-none');
    contentEl.classList.add('d-none');

    try {
        const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';
        const qs = year != null ? `&year=${year}` : '';
        const res = await fetch(`/api/btc-tracking/yearly-overview?currency=${encodeURIComponent(currency)}${qs}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();

        // selection may have already changed and a newer request is in flight; don't let this stale response overwrite the view
        if (seq !== _yearlyOverviewSeq) return;

        loadingEl.classList.add('d-none');

        _yearlyAvailableYears = data.availableYears || [];
        yearlyPopulateYearSelect(_yearlyAvailableYears, year);

        if (!Array.isArray(data.series) || data.series.length === 0) {
            emptyEl.classList.remove('d-none');
            tilesSection.classList.add('d-none');
            priceChartSection.classList.add('d-none');
            transfersSection.classList.add('d-none');
            return;
        }

        contentEl.classList.remove('d-none');
        renderYearlyChart(data.series, currency, year != null);

        // buys & sells tile: filtered to the selected year, or all transactions in the overview
        tilesSection.classList.remove('d-none');
        await yearlyRenderTiles(year, currency, seq);
        // transfers tile: same filter pattern, reuses _yearlyAllTx already loaded by yearlyRenderTiles
        transfersSection.classList.remove('d-none');
        yearlyRenderTransfers(year, seq);
        // price chart: filtered to the selected year, or the full history in the overview
        await yearlyLoadPriceChart(currency, seq, year);

        if (seq !== _yearlyOverviewSeq) return; // recheck since tiles/price chart ran async

        // tile visibility may have just changed, so recompute column spans for the affected rows
        const grid = document.getElementById('yearlyGrid');
        if (grid) updateYearlyRowCols(grid);
        _yearlyTriggerChartResize();
    } catch (err) {
        if (seq !== _yearlyOverviewSeq) return; // stale error from a superseded request
        loadingEl.classList.add('d-none');
        emptyEl.classList.remove('d-none');
        emptyEl.textContent = 'Error: ' + err.message;
        console.error('Yearly overview load failed', err);
    }
}

function yearlyPopulateYearSelect(years, selectedYear) {
    const select = document.getElementById('yearlyYearSelect');
    select.innerHTML = '';

    const allOpt = document.createElement('option');
    allOpt.value = '';
    allOpt.textContent = (typeof t === 'function') ? t('yearly.select.all') : 'Gesamtansicht';
    select.appendChild(allOpt);

    years.slice().sort((a, b) => b - a).forEach(y => {
        const opt = document.createElement('option');
        opt.value = String(y);
        opt.textContent = String(y);
        select.appendChild(opt);
    });

    // always reflect the actually loaded value rather than a stale DOM value
    select.value = selectedYear != null ? String(selectedYear) : '';
}

// ── Line chart: balance (BTC, orange) + value (currency, green) ──

function renderYearlyChart(series, currency, singleYear) {
    const categories = series.map((pt, i) => {
        if (singleYear) return YEARLY_MONTH_SHORT_DE[pt.month - 1];
        // overview: year label only on January (or the first point), to avoid overlapping labels
        return (i === 0 || pt.month === 1) ? String(pt.year) : '';
    });

    const balances = series.map(pt => Number(pt.btcBalance || 0));
    const values    = series.map(pt => pt.value != null ? Number(pt.value) : null);

    const options = {
        ...YEARLY_APEX_DEFAULTS,
        series: [
            { name: (typeof t === 'function') ? t('yearly.chart.balance') : 'Bestand', type: 'line', data: balances },
            { name: (typeof t === 'function') ? t('yearly.chart.value') : 'Wertentwicklung', type: 'line', data: values }
        ],
        chart: {
            ...YEARLY_APEX_DEFAULTS.chart, type: 'line', height: 360,
            // Zoom per Bereichs-Markierung ist auf Tablet/Phone (≤991px) nur
            // hinderlich (kollidiert mit Scrollen/Touch) — dort deaktivieren.
            zoom: { enabled: window.innerWidth > 991 }
        },
        colors: [YEARLY_BALANCE_COLOR, YEARLY_VALUE_COLOR],
        stroke: { width: 2.5, curve: 'straight' },
        markers: { size: 3, strokeWidth: 0 },
        xaxis: {
            ...YEARLY_APEX_DEFAULTS.xaxis,
            categories,
            labels: { show: true, rotate: 0, style: { colors: '#6b6f7a', fontSize: '10px' } },
            axisTicks: { show: false }
        },
        yaxis: [
            {
                seriesName: (typeof t === 'function') ? t('yearly.chart.balance') : 'Bestand',
                labels: { style: { colors: YEARLY_BALANCE_COLOR }, formatter: v => Number(v).toFixed(1) }
            },
            {
                seriesName: (typeof t === 'function') ? t('yearly.chart.value') : 'Wertentwicklung',
                opposite: true,
                labels: { style: { colors: YEARLY_VALUE_COLOR }, formatter: v => _yearlyFmtAxisPrice(v, currency) }
            }
        ],
        tooltip: {
            ...YEARLY_APEX_DEFAULTS.tooltip,
            shared: true,
            intersect: false,
            x: { formatter: (_, opts) => yearlyTooltipX(series, opts) },
            y: [
                { formatter: v => v != null ? fmtBtc(v) : '–' },
                { formatter: v => v != null ? fmt(v, currency) : ((typeof t === 'function') ? t('yearly.chart.noPrice') : 'kein Kurs hinterlegt') }
            ]
        }
    };

    if (_yearlyChart) {
        // reuse the existing instance via updateOptions() instead of destroy()+recreate, which left stale x-axis labels on fast year switches
        _yearlyChart.updateOptions(options, true, true, true);
    } else {
        _yearlyChart = new ApexCharts(document.getElementById('yearlyChart'), options);
        _yearlyChart.render();
    }
}

function yearlyTooltipX(series, opts) {
    const pt = series[opts.dataPointIndex];
    if (!pt) return '';
    const month = YEARLY_MONTH_SHORT_DE[pt.month - 1];
    return `${month} ${pt.year}`;
}

// ── Standalone Bitcoin price chart (full history, or 12 months for a selected year): fetches all monthly_price history and filters client-side, no extra backend endpoint needed ──

async function yearlyLoadPriceChart(currency, seq, year) {
    const section  = document.getElementById('yearly-block-pricechart');
    const titleEl  = document.getElementById('yearlyPriceChartTitle');
    try {
        const res = await fetch(`/api/btc-tracking/monthly-prices/history?currency=${encodeURIComponent(currency)}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const rows = await res.json();
        if (seq !== _yearlyOverviewSeq) return; // selection changed in the meantime

        let priced = rows.filter(row => row.price != null);
        if (year != null) priced = priced.filter(row => row.year === year);

        if (titleEl) {
            titleEl.textContent = year != null
                ? ((typeof t === 'function') ? t('yearly.priceChart.titleYear', { YEAR: year }) : `Bitcoin-Kurs ${year}`)
                : ((typeof t === 'function') ? t('yearly.priceChart.title') : 'Bitcoin-Kurs (Historie)');
        }

        if (priced.length === 0) {
            section.classList.add('d-none');
            return;
        }
        section.classList.remove('d-none');
        renderYearlyPriceChart(priced, currency, year != null);
    } catch (err) {
        if (seq !== _yearlyOverviewSeq) return;
        console.error('Price history chart load failed', err);
        section.classList.add('d-none');
    }
}

function renderYearlyPriceChart(rows, currency, singleYear) {
    const categories = rows.map((pt, i) => {
        if (singleYear) return YEARLY_MONTH_SHORT_DE[pt.month - 1];
        return (i === 0 || pt.month === 1) ? String(pt.year) : '';
    });
    const prices = rows.map(pt => Number(pt.price));

    const options = {
        ...YEARLY_APEX_DEFAULTS,
        series: [{ name: (typeof t === 'function') ? t('yearly.priceChart.series') : 'Kurs', type: 'line', data: prices }],
        chart: {
            ...YEARLY_APEX_DEFAULTS.chart, type: 'line', height: 300,
            zoom: { enabled: window.innerWidth > 991 }
        },
        colors: [YEARLY_PRICE_COLOR],
        stroke: { width: 2, curve: 'straight' },
        markers: { size: 0 },
        xaxis: {
            ...YEARLY_APEX_DEFAULTS.xaxis,
            categories,
            labels: { show: true, rotate: 0, style: { colors: '#6b6f7a', fontSize: '10px' } },
            axisTicks: { show: false }
        },
        yaxis: {
            labels: { style: { colors: YEARLY_PRICE_COLOR }, formatter: v => _yearlyFmtAxisPrice(v, currency) }
        },
        tooltip: {
            ...YEARLY_APEX_DEFAULTS.tooltip,
            x: { formatter: (_, opts) => yearlyPriceTooltipX(rows, opts) },
            y: { formatter: v => v != null ? fmt(v, currency) : '–' }
        }
    };

    if (_yearlyPriceChart) {
        _yearlyPriceChart.updateOptions(options, true, true, true);
    } else {
        _yearlyPriceChart = new ApexCharts(document.getElementById('yearlyPriceChart'), options);
        _yearlyPriceChart.render();
    }
}

function yearlyPriceTooltipX(rows, opts) {
    const pt = rows[opts.dataPointIndex];
    if (!pt) return '';
    const month = YEARLY_MONTH_SHORT_DE[pt.month - 1];
    return `${month} ${pt.year}`;
}

// ── Buy/sell tiles for the selected year ─────────────

// Buy/sell visibility filter (2 independent toggles), client-side only, both on by default
let _yearlyTilesFilter  = { buy: true, sell: true };
let _yearlyTilesYearTx  = [];      // year/overview-filtered tx (before the buy/sell toggle)
let _yearlyTilesBuyMeta  = new Map();
let _yearlyTilesSellMeta = new Map();
let _yearlyTilesCurrency = 'EUR';
let _yearlyTilesYearLabel = null; // for the empty-list message (year vs. overview)

// Tax cutoff date (settings, see navbar.js#saveSettings): coins bought on/after it are always taxable. null/'' = disabled.
let _yearlyTaxCutoffDate = null;

async function yearlyRenderTiles(year, currency, seq) {
    if (_yearlyAllTx == null) {
        const [txs, priceRes, settingsRes] = await Promise.all([
            fetch('/api/btc-tracking/transactions').then(r => r.json()),
            fetch(`/api/btc-tracking/current-price?currency=${encodeURIComponent(currency)}`).then(r => r.json()),
            fetch('/api/btc-tracking/settings').then(r => r.json()).catch(() => ({}))
        ]);
        if (seq !== undefined && seq !== _yearlyOverviewSeq) return; // selection changed in the meantime
        _yearlyAllTx          = txs;
        _yearlyCurrentPrice   = Number(priceRes.price || 0);
        _yearlyTaxCutoffDate  = settingsRes.taxHoldingPeriodCutoffDate || null;
    }

    const { buyMeta, sellMeta } = _yearlyComputeFifo(_yearlyAllTx);

    // overview (year == null) shows all buys/sells across all years; a selected year filters to it
    const yearTx = _yearlyAllTx
        .filter(tx => (tx.type === 'BUY' || tx.type === 'SELL') && tx.date
            && (year == null || String(tx.date).substring(0, 4) === String(year)))
        .sort((a, b) => (a.date || '').localeCompare(b.date || ''));

    _yearlyTilesYearTx    = yearTx;
    _yearlyTilesBuyMeta   = buyMeta;
    _yearlyTilesSellMeta  = sellMeta;
    _yearlyTilesCurrency  = currency;
    _yearlyTilesYearLabel = year;

    _yearlyRenderTilesGrid();
}

// Applies the buy/sell toggle filter to the cached, year-filtered transactions and re-renders (no refetch/FIFO recompute needed)
function _yearlyRenderTilesGrid() {
    const grid    = document.getElementById('yearlyTilesGrid');
    const emptyEl = document.getElementById('yearlyTilesEmpty');
    const countEl = document.getElementById('yearlyTilesCount');
    if (!grid || !emptyEl) return;

    const yearTx   = _yearlyTilesYearTx;
    const currency = _yearlyTilesCurrency;

    if (countEl) {
        const buyCount  = yearTx.filter(tx => tx.type === 'BUY').length;
        const sellCount = yearTx.filter(tx => tx.type === 'SELL').length;
        countEl.textContent = (typeof t === 'function')
            ? t('yearly.tiles.count', { BUYS: buyCount, SELLS: sellCount })
            : `${buyCount} Käufe · ${sellCount} Verkäufe`;
    }

    const filtered = yearTx.filter(tx =>
        (tx.type === 'BUY' && _yearlyTilesFilter.buy) || (tx.type === 'SELL' && _yearlyTilesFilter.sell));

    if (filtered.length === 0) {
        grid.innerHTML = '';
        emptyEl.textContent = (typeof t === 'function')
            ? t(_yearlyTilesYearLabel == null ? 'yearly.tiles.emptyAll' : 'yearly.tiles.empty')
            : (_yearlyTilesYearLabel == null ? 'Keine Transaktionen vorhanden.' : 'Keine Transaktionen in diesem Jahr.');
        emptyEl.classList.remove('d-none');
        return;
    }
    emptyEl.classList.add('d-none');
    grid.innerHTML = filtered.map(tx => {
        if (tx.type === 'BUY') {
            const meta = _yearlyTilesBuyMeta.get(String(tx.id)) || { state: 'held', sells: [] };
            return _yearlyRenderBuyTile(tx, meta, currency);
        }
        const consumed = _yearlyTilesSellMeta.get(String(tx.id)) || [];
        return _yearlyRenderSellTile(tx, consumed, currency);
    }).join('');
}

// Click handler for the two independent "buy"/"sell" toggle buttons
function yearlyToggleTilesFilter(kind) {
    _yearlyTilesFilter[kind] = !_yearlyTilesFilter[kind];
    const btn = document.querySelector(`.yearly-tiles-filter-btn[data-type="${kind}"]`);
    if (btn) btn.classList.toggle('is-active', _yearlyTilesFilter[kind]);
    _yearlySaveTilesFilter();
    _yearlyRenderTilesGrid();
}

function _yearlySaveTilesFilter() {
    try { localStorage.setItem(YEARLY_TILES_FILTER_KEY, JSON.stringify(_yearlyTilesFilter)); } catch (e) { /* ignore */ }
}

// Reads the saved buy/sell filter and syncs the button display; the actual render happens later via yearlyRenderTiles
function _yearlyRestoreTilesFilter() {
    try {
        const saved = JSON.parse(localStorage.getItem(YEARLY_TILES_FILTER_KEY));
        if (saved && typeof saved.buy === 'boolean' && typeof saved.sell === 'boolean') {
            _yearlyTilesFilter = saved;
        }
    } catch (e) { /* ignore malformed storage */ }
    document.querySelectorAll('.yearly-tiles-filter-btn').forEach(btn => {
        btn.classList.toggle('is-active', _yearlyTilesFilter[btn.dataset.type]);
    });
}

// Standalone, portfolio-wide FIFO calculation (not shared with holdings.js): one pass over all BUY/SELL yields both the buy view (which sells consumed it) and the sell view (which buys it consumed).
function _yearlyComputeFifo(allTx) {
    const list = allTx
        .filter(tx => tx.type === 'BUY' || tx.type === 'SELL')
        .sort((a, b) => (a.date || '').localeCompare(b.date || ''));

    const queue    = []; // { tx, remaining }
    const buyMeta  = new Map(); // buyId -> { originalQty, remainingQty, state, sells: [{tx, qty}] }
    const sellMeta = new Map(); // sellId -> [{ buyTx, qty, days }]

    list.forEach(tx => {
        if (tx.type === 'BUY') {
            const qty = Number(tx.quantity);
            queue.push({ tx, remaining: qty });
            buyMeta.set(String(tx.id), { originalQty: qty, remainingQty: qty, state: 'held', sells: [] });
        } else if (tx.type === 'SELL') {
            let toConsume = Number(tx.quantity);
            const consumed = [];
            while (toConsume > 1e-12 && queue.length) {
                const lot  = queue[0];
                const take = Math.min(lot.remaining, toConsume);
                if (take > 1e-12) {
                    const days = _yearlyDaysBetween(lot.tx.date, tx.date);
                    consumed.push({ buyTx: lot.tx, qty: take, days });

                    const bm = buyMeta.get(String(lot.tx.id));
                    bm.remainingQty -= take;
                    bm.sells.push({ tx, qty: take });
                    bm.state = bm.remainingQty <= 1e-12 ? 'realized' : 'partial';
                }
                lot.remaining -= take;
                toConsume -= take;
                if (lot.remaining <= 1e-12) queue.shift();
            }
            sellMeta.set(String(tx.id), consumed);
        }
    });

    return { buyMeta, sellMeta };
}

function _yearlyDaysBetween(dateA, dateB) {
    const a = new Date(dateA);
    const b = new Date(dateB);
    return Math.max(0, Math.round((b - a) / 86400000));
}

// Converts tx.quantityFiat (already a total) into the display currency, same convention as elsewhere in the app
function _yearlyFiatInDisplayCurrency(tx, displayCurrency) {
    if (tx.quantityFiat == null) return null;
    if (tx.currency === displayCurrency) return Number(tx.quantityFiat);
    return Number(tx.quantityFiat) * Number(tx.exchangeRate || 1);
}

// Total amount paid incl. fees for a BUY, in the display currency
function _yearlyBuyPaid(tx, displayCurrency) {
    if (tx.pricePerBtc == null) return null;
    const fees = tx.fees != null ? Number(tx.fees) : 0;
    const cost = Number(tx.quantity) * Number(tx.pricePerBtc) + fees;
    if (tx.currency === displayCurrency) return cost;
    return cost * Number(tx.exchangeRate || 1);
}

// P/L %+amount for a BUY tile: held buys use the current price, partial/realized buys blend actual sell proceeds with the current price for any remaining held portion
function _yearlyBuyGainLoss(tx, meta, currentPrice, currency) {
    const paid = _yearlyBuyPaid(tx, currency);
    if (paid == null || paid === 0) return { percentage: 0, earningAbs: 0 };

    if (meta.state === 'held' || !meta.sells.length) {
        const nowValue = Number(tx.quantity) * currentPrice;
        const earningAbs = nowValue - paid;
        return { percentage: (earningAbs / paid) * 100, earningAbs };
    }

    const qty = Number(tx.quantity);
    let proceeds = 0;
    meta.sells.forEach(s => {
        const sellProceeds = _yearlyFiatInDisplayCurrency(s.tx, currency);
        if (sellProceeds == null) return;
        const sellQty = Number(s.tx.quantity) || 1;
        proceeds += sellProceeds * (s.qty / sellQty);
    });
    if (meta.remainingQty > 1e-12) {
        proceeds += meta.remainingQty * currentPrice;
    }
    const earningAbs = proceeds - paid;
    return { percentage: (earningAbs / paid) * 100, earningAbs };
}

function _yearlyStateBadge(state) {
    if (state === 'held') return '';
    const info = state === 'partial'
        ? { key: 'holdings.buyDetail.state.partial', fallback: 'Teilweise realisiert' }
        : { key: 'holdings.buyDetail.state.realized', fallback: 'Komplett realisiert' };
    return `<span class="flow-tx-card-badge state-${state}">${esc((typeof t === 'function') ? t(info.key) : info.fallback)}</span>`;
}

function _yearlyRenderBuyTile(tx, meta, currency) {
    const date = tx.date ? String(tx.date).replace('T', ' ').substring(0, 19) : '–';
    const { percentage, earningAbs } = _yearlyBuyGainLoss(tx, meta, _yearlyCurrentPrice, currency);
    const posNeg = percentage >= 0 ? 'text-pos' : 'text-neg';

    const buyBadge   = `<span class="flow-tx-card-badge type-buy">${esc((typeof t === 'function') ? t('flow.legend.buy') : 'Kauf')}</span>`;
    const stateBadge = _yearlyStateBadge(meta.state);

    const priceLine = tx.pricePerBtc != null
        ? _yearlyFieldRow((typeof t === 'function') ? t('table.col.pricePerBtc') : 'Preis/BTC', _yearlyFormatFiat(tx.pricePerBtc, tx.currency))
        : '';
    const totalLine = tx.quantityFiat != null
        ? _yearlyFieldRow((typeof t === 'function') ? t('table.col.total') : 'Gesamt', _yearlyFormatFiat(tx.quantityFiat, tx.currency))
        : '';
    const feesLine = tx.fees != null
        ? _yearlyFieldRow((typeof t === 'function') ? t('table.col.fees') : 'Gebühren', _yearlyFormatFiat(tx.fees, tx.feesCurrency || tx.currency))
        : '';
    const commentLine = tx.comment
        ? _yearlyFieldRow((typeof t === 'function') ? t('modal.field.comment') : 'Kommentar', esc(_yearlyTruncateComment(tx.comment)), tx.comment, true)
        : '';

    const gvPercentLine = _yearlyFieldRow(
        (typeof t === 'function') ? t('holdings.buyDetail.gvPercent') : 'G/V %',
        `<span class="${posNeg}">${percentage >= 0 ? '+' : ''}${percentage.toFixed(2)}%</span>`
    );
    const gvAbsLine = _yearlyFieldRow(
        (typeof t === 'function') ? t('holdings.buyDetail.gvAbs') : 'G/V',
        `<span class="${posNeg}">${earningAbs >= 0 ? '+' : ''}${fmt(earningAbs, currency)}</span>`
    );

    const txJson = JSON.stringify(tx).replace(/"/g, '&quot;');

    return `<div class="flow-tx-card yearly-tile">
        <div class="flow-tx-card-head">
            <span>${esc(tx.positionLabel || '–')}</span>
            <span class="flow-tx-card-actions">
                ${buyBadge}${stateBadge}
                <button type="button" class="btn btn-xs depot-btn-icon" title="Edit"
                        onclick="event.stopPropagation(); openEditTx(${txJson})">
                    <i class="bi bi-pencil"></i>
                </button>
            </span>
        </div>
        ${_yearlyFieldRow((typeof t === 'function') ? t('table.col.date') : 'Datum', date)}
        ${_yearlyFieldRow((typeof t === 'function') ? t('table.col.btc') : 'BTC', _yearlyFmt8(tx.quantity))}
        ${priceLine}${totalLine}${feesLine}
        ${gvPercentLine}${gvAbsLine}
        ${commentLine}
    </div>`;
}

function _yearlyRenderSellTile(tx, consumed, currency) {
    const date = tx.date ? String(tx.date).replace('T', ' ').substring(0, 19) : '–';
    const sellBadge = `<span class="flow-tx-card-badge type-sell">${esc((typeof t === 'function') ? t('flow.legend.sell') : 'Verkauf')}</span>`;

    const priceLine = tx.pricePerBtc != null
        ? _yearlyFieldRow((typeof t === 'function') ? t('table.col.pricePerBtc') : 'Preis/BTC', _yearlyFormatFiat(tx.pricePerBtc, tx.currency))
        : '';
    const totalLine = tx.quantityFiat != null
        ? _yearlyFieldRow((typeof t === 'function') ? t('table.col.total') : 'Gesamt', _yearlyFormatFiat(tx.quantityFiat, tx.currency))
        : '';
    const feesLine = tx.fees != null
        ? _yearlyFieldRow((typeof t === 'function') ? t('table.col.fees') : 'Gebühren', _yearlyFormatFiat(tx.fees, tx.feesCurrency || tx.currency))
        : '';
    const commentLine = tx.comment
        ? _yearlyFieldRow((typeof t === 'function') ? t('modal.field.comment') : 'Kommentar', esc(_yearlyTruncateComment(tx.comment)), tx.comment, true)
        : '';

    // realized P/L for this sell: proceeds minus lot-accurate cost basis of the consumed FIFO lots (not the app-wide weighted average)
    const proceeds = _yearlyFiatInDisplayCurrency(tx, currency) || 0;
    let costOfSold = 0;
    consumed.forEach(c => {
        const paid = _yearlyBuyPaid(c.buyTx, currency);
        if (paid == null) return;
        const unitCost = paid / Number(c.buyTx.quantity);
        costOfSold += unitCost * c.qty;
    });
    const gain   = proceeds - costOfSold;
    const posNeg = gain >= 0 ? 'text-pos' : 'text-neg';
    const gvLine = _yearlyFieldRow(
        (typeof t === 'function') ? t('holdings.buyDetail.gvAbs') : 'G/V',
        `<span class="${posNeg}">${gain >= 0 ? '+' : ''}${fmt(gain, currency)}</span>`
    );

    // per-lot P/L: proceeds share (proportional to consumed qty) minus that lot's cost basis
    const sellQty = Number(tx.quantity) || 1;
    const lotsHtml = consumed.map(c => {
        const taxFree = _yearlyIsTaxFree(c);
        const taxBadge = `<span class="yearly-tax-badge ${taxFree ? 'tax-free' : 'tax-liable'}">${
            (typeof t === 'function') ? t(taxFree ? 'yearly.tax.free' : 'yearly.tax.liable') : (taxFree ? 'steuerfrei' : 'steuerpflichtig')
        }</span>`;
        const buyDate = c.buyTx.date ? String(c.buyTx.date).substring(0, 10) : '–';
        const daysLabel = (typeof t === 'function') ? t('yearly.tiles.daysHeld', { DAYS: c.days }) : `${c.days} Tage gehalten`;

        const paid = _yearlyBuyPaid(c.buyTx, currency);
        let lotGainHtml = '';
        if (paid != null) {
            const unitCost   = paid / Number(c.buyTx.quantity);
            const lotCost    = unitCost * c.qty;
            const lotProceeds = proceeds * (c.qty / sellQty);
            const lotGain    = lotProceeds - lotCost;
            const lotPosNeg  = lotGain >= 0 ? 'text-pos' : 'text-neg';
            lotGainHtml = `<span class="yearly-lot-gain ${lotPosNeg}">${lotGain >= 0 ? '+' : ''}${fmt(lotGain, currency)}</span>`;
        }

        return `<div class="yearly-lot-row">
            <span class="yearly-lot-qty">${_yearlyFmt8(c.qty)}</span>
            <span class="yearly-lot-date">${esc(buyDate)}</span>
            <span class="yearly-lot-days">${esc(daysLabel)}</span>
            ${lotGainHtml}
            ${taxBadge}
        </div>`;
    }).join('');

    const lotsBlock = consumed.length
        ? `<div class="yearly-lots-title">${esc((typeof t === 'function') ? t('yearly.tiles.lotsTitle') : 'Zusammensetzung aus historischen Käufen')}</div>
           <div class="yearly-lots-list">${lotsHtml}</div>`
        : '';

    const txJson = JSON.stringify(tx).replace(/"/g, '&quot;');

    return `<div class="flow-tx-card yearly-tile">
        <div class="flow-tx-card-head">
            <span>${esc(tx.positionLabel || '–')}</span>
            <span class="flow-tx-card-actions">
                ${sellBadge}
                <button type="button" class="btn btn-xs depot-btn-icon" title="Edit"
                        onclick="event.stopPropagation(); openEditTx(${txJson})">
                    <i class="bi bi-pencil"></i>
                </button>
            </span>
        </div>
        ${_yearlyFieldRow((typeof t === 'function') ? t('table.col.date') : 'Datum', date)}
        ${_yearlyFieldRow((typeof t === 'function') ? t('table.col.btc') : 'BTC', _yearlyFmt8(tx.quantity))}
        ${priceLine}${totalLine}${feesLine}
        ${gvLine}
        ${commentLine}
        ${lotsBlock}
    </div>`;
}

// ── Transfers tile: same year/overview filter pattern as buys/sells, reusing _yearlyAllTx. Counterpart lookup via transferId is a simple standalone search, not flow.js's backend-based graph pairing. ──
let _yearlyTransfersTx        = [];
let _yearlyTransfersYearLabel = null;

// All/paired/solo filter, radio-style single mode (unlike the independent buy/sell toggle) since paired and solo are mutually exclusive
let _yearlyTransfersFilterMode = 'solo'; // 'all' | 'paired' | 'solo', defaults to solo-only

function yearlyRenderTransfers(year, seq) {
    if (seq !== undefined && seq !== _yearlyOverviewSeq) return; // selection changed in the meantime
    if (_yearlyAllTx == null) return; // loaded by yearlyRenderTiles in the same cycle

    const transferTx = _yearlyAllTx
        .filter(tx => (tx.type === 'TRANSFER_IN' || tx.type === 'TRANSFER_OUT') && tx.date
            && (year == null || String(tx.date).substring(0, 4) === String(year)))
        .sort((a, b) => (a.date || '').localeCompare(b.date || ''));

    _yearlyTransfersTx        = transferTx;
    _yearlyTransfersYearLabel = year;
    _yearlyRenderTransfersGrid();
}

function _yearlyRenderTransfersGrid() {
    const grid    = document.getElementById('yearlyTransfersGrid');
    const emptyEl = document.getElementById('yearlyTransfersEmpty');
    const countEl = document.getElementById('yearlyTransfersCount');
    if (!grid || !emptyEl) return;

    const items = _yearlyTransfersTx;

    if (countEl) {
        const outCount = items.filter(tx => tx.type === 'TRANSFER_OUT').length;
        const inCount  = items.filter(tx => tx.type === 'TRANSFER_IN').length;
        countEl.textContent = (typeof t === 'function')
            ? t('yearly.transfers.count', { OUT: outCount, IN: inCount })
            : `${outCount} Ausgänge · ${inCount} Eingänge`;
    }

    const filtered = items.filter(tx => {
        if (_yearlyTransfersFilterMode === 'all') return true;
        const isPaired = _yearlyFindTransferCounterpart(tx) != null;
        return _yearlyTransfersFilterMode === 'paired' ? isPaired : !isPaired;
    });

    if (filtered.length === 0) {
        grid.innerHTML = '';
        emptyEl.textContent = (typeof t === 'function')
            ? t(_yearlyTransfersYearLabel == null ? 'yearly.transfers.emptyAll' : 'yearly.transfers.empty')
            : (_yearlyTransfersYearLabel == null ? 'Keine Transfers vorhanden.' : 'Keine Transfers in diesem Jahr.');
        emptyEl.classList.remove('d-none');
        return;
    }
    emptyEl.classList.add('d-none');
    grid.innerHTML = filtered.map(tx => _yearlyRenderTransferTile(tx)).join('');
}

// Click handler for the 3 mutually exclusive filter buttons; no refetch, just a re-render
function yearlyToggleTransfersFilter(mode) {
    _yearlyTransfersFilterMode = mode;
    document.querySelectorAll('.yearly-transfers-filter-btn').forEach(btn => {
        btn.classList.toggle('is-active', btn.dataset.mode === mode);
    });
    _yearlySaveTransfersFilter();
    _yearlyRenderTransfersGrid();
}

function _yearlySaveTransfersFilter() {
    try { localStorage.setItem(YEARLY_TRANSFERS_FILTER_KEY, _yearlyTransfersFilterMode); } catch (e) { /* ignore */ }
}

// Same pattern as _yearlyRestoreTilesFilter: reads the saved filter mode and syncs the button display
function _yearlyRestoreTransfersFilter() {
    try {
        const saved = localStorage.getItem(YEARLY_TRANSFERS_FILTER_KEY);
        if (saved === 'all' || saved === 'paired' || saved === 'solo') _yearlyTransfersFilterMode = saved;
    } catch (e) { /* ignore malformed storage */ }
    document.querySelectorAll('.yearly-transfers-filter-btn').forEach(btn => {
        btn.classList.toggle('is-active', btn.dataset.mode === _yearlyTransfersFilterMode);
    });
}

// Finds the counterpart (TRANSFER_IN ↔ TRANSFER_OUT with the same transferId); null means a solo transfer
function _yearlyFindTransferCounterpart(tx) {
    if (!tx.transferId) return null;
    const wantType = tx.type === 'TRANSFER_OUT' ? 'TRANSFER_IN' : 'TRANSFER_OUT';
    return _yearlyAllTx.find(other => other.type === wantType && other.transferId === tx.transferId) || null;
}

function _yearlyRenderTransferTile(tx) {
    const date   = tx.date ? String(tx.date).replace('T', ' ').substring(0, 19) : '–';
    const isOut  = tx.type === 'TRANSFER_OUT';
    const badge  = `<span class="flow-tx-card-badge ${isOut ? 'pair-out' : 'pair-in'}">${
        esc((typeof t === 'function') ? t(isOut ? 'flow.panel.transferOut' : 'flow.panel.transferIn') : (isOut ? 'Ausgang' : 'Eingang'))
    }</span>`;

    const counterpart = _yearlyFindTransferCounterpart(tx);
    const counterpartLine = counterpart
        ? _yearlyFieldRow(
            (typeof t === 'function') ? t(isOut ? 'yearly.transfers.to' : 'yearly.transfers.from') : (isOut ? 'Nach' : 'Von'),
            esc(counterpart.positionLabel || '–'))
        : `<div class="yearly-transfer-solo">${esc((typeof t === 'function') ? t('legend.solo.transfer') : 'Solo-Transfer (Ein-/Auszahlung ohne Gegenbuchung)')}</div>`;

    const commentLine = tx.comment
        ? _yearlyFieldRow((typeof t === 'function') ? t('modal.field.comment') : 'Kommentar', esc(_yearlyTruncateComment(tx.comment)), tx.comment, true)
        : '';

    const txJson = JSON.stringify(tx).replace(/"/g, '&quot;');

    return `<div class="flow-tx-card yearly-tile">
        <div class="flow-tx-card-head">
            <span>${esc(tx.positionLabel || '–')}</span>
            <span class="flow-tx-card-actions">
                ${badge}
                <button type="button" class="btn btn-xs depot-btn-icon" title="Edit"
                        onclick="event.stopPropagation(); openEditTx(${txJson})">
                    <i class="bi bi-pencil"></i>
                </button>
            </span>
        </div>
        ${_yearlyFieldRow((typeof t === 'function') ? t('table.col.date') : 'Datum', date)}
        ${_yearlyFieldRow((typeof t === 'function') ? t('table.col.btc') : 'BTC', _yearlyFmt8(tx.quantity))}
        ${counterpartLine}
        ${commentLine}
    </div>`;
}

// ── Kleine, seiteneigene Format-/Escape-Helfer (bewusst nicht mit holdings.js geteilt) ──

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function fmt(val, currency) {
    if (typeof CURRENCY !== 'undefined') return CURRENCY.format(val, currency);
    return Number(val).toFixed(2);
}

function fmtBtc(val) {
    return Number(val).toLocaleString('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 8 }) + ' BTC';
}

// Compact variant of fmt() for axis labels (0 decimals), standalone copy analogous to holdings.js' _holdingsFmtCompact()
function _yearlyFmtAxisPrice(val, currency) {
    if (typeof CURRENCY !== 'undefined') {
        const cur = CURRENCY.get(currency);
        return Number(val).toLocaleString(cur.locale, { maximumFractionDigits: 0 }) + ' ' + cur.symbol;
    }
    return Number(val).toFixed(0);
}

function _yearlyFormatFiat(val, code) {
    if (val == null) return '–';
    const num = Number(val).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return code ? `${num} ${code}` : num;
}

function _yearlyFmt8(val) {
    if (val == null) return '–';
    return Number(val).toLocaleString('de-DE', { minimumFractionDigits: 8, maximumFractionDigits: 8 });
}

// Truncate to 20 chars (full text in the tooltip), same fix as depot.js, otherwise a long comment widens the card
function _yearlyTruncateComment(comment) {
    return comment && comment.length > 20 ? comment.substring(0, 20) + '…' : comment;
}

function _yearlyFieldRow(label, value, title, alignLeft) {
    return `<div class="flow-tx-field">
        <span class="flow-tx-field-label">${esc(label)}</span>
        <span class="flow-tx-field-value${alignLeft ? ' align-left' : ''}"${title ? ` title="${esc(title)}"` : ''}>${value}</span>
    </div>`;
}

function showToast(msg, type) {
    const toast = document.getElementById('statusToast');
    if (!toast) return;
    toast.innerHTML = msg.replace(/\n/g, '<br>');
    toast.className = 'depot-toast ' + (type || '');
    toast.classList.remove('d-none');
    setTimeout(() => toast.classList.add('d-none'), 5000);
}

// ── Manual monthly price dialog: grouped by year, collapsible, newest year first ──

async function yearlyLoadPriceModal() {
    const body = document.getElementById('yearlyPriceModalBody');
    if (!body) return;
    body.innerHTML = `<div style="color:var(--text-muted);padding:.5rem" data-i18n="holdings.loading">Lade Daten…</div>`;
    if (typeof I18N !== 'undefined') I18N.applyI18n();

    try {
        const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';
        const res = await fetch(`/api/btc-tracking/monthly-prices?currency=${encodeURIComponent(currency)}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const rows = await res.json();

        const byYear = new Map();
        rows.forEach(row => {
            if (!byYear.has(row.year)) byYear.set(row.year, []);
            byYear.get(row.year).push(row);
        });
        const years = Array.from(byYear.keys()).sort((a, b) => b - a);

        body.innerHTML = years.map((year, idx) => {
            const monthsDesc = byYear.get(year).slice().sort((a, b) => b.month - a.month);
            const collapseId = `yearlyPriceYear${year}`;
            const expanded   = idx < 2; // current + previous year expanded by default

            const rowsHtml = monthsDesc.map(row => {
                const monthLabel = YEARLY_MONTH_SHORT_DE[row.month - 1];
                if (row.current) {
                    return `<div class="yearly-price-row">
                        <span class="yearly-price-month">${monthLabel}</span>
                        <span class="yearly-price-current" data-i18n="yearly.priceModal.current">nutzt aktuellen Kurs</span>
                    </div>`;
                }
                return `<div class="yearly-price-row">
                    <span class="yearly-price-month">${monthLabel}</span>
                    <input type="number" step="0.01" min="0" class="yearly-price-input"
                           placeholder="${(typeof t === 'function') ? t('holdings.refPrices.notSet') : 'kein Wert hinterlegt'}"
                           value="${row.price != null ? row.price : ''}"
                           data-year="${row.year}" data-month="${row.month}"/>
                    <button type="button" class="yearly-price-save" onclick="yearlySaveMonthlyPrice(this)">
                        <i class="bi bi-check-lg"></i>
                    </button>
                </div>`;
            }).join('');

            return `<div class="yearly-price-year-block">
                <button type="button" class="yearly-price-year-toggle" data-bs-toggle="collapse" data-bs-target="#${collapseId}"
                        aria-expanded="${expanded}" aria-controls="${collapseId}">
                    <i class="bi bi-chevron-down"></i> ${year}
                </button>
                <div class="collapse${expanded ? ' show' : ''}" id="${collapseId}">
                    <div class="yearly-price-rows">${rowsHtml}</div>
                </div>
            </div>`;
        }).join('');
    } catch (err) {
        body.innerHTML = `<div style="color:var(--neg);padding:.5rem">Error: ${esc(err.message)}</div>`;
    }
}

async function yearlySaveMonthlyPrice(btn) {
    const input = btn.previousElementSibling;
    const value = parseFloat(input.value);
    if (isNaN(value) || value <= 0) {
        showToast((typeof t === 'function') ? t('holdings.refPrices.invalid') : 'Ungültiger Kurs', 'error');
        return;
    }
    const year     = Number(input.dataset.year);
    const month    = Number(input.dataset.month);
    const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';

    try {
        const res = await fetch('/api/btc-tracking/monthly-prices', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ year, month, currency, price: value })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));

        showToast('✓ ' + ((typeof t === 'function') ? t('yearly.toast.priceSaved') : 'Kurs gespeichert'), 'success');
        yearlyLoadOverview(_yearlySelectedYear);
    } catch (err) {
        showToast('✗ ' + err.message, 'error');
    }
}

// ── Draggable grid layout (desktop), standalone implementation (not shared with holdings.js): 3 rows of max 2 slots, no capped-tile concept, and updateYearlyRowCols counts only visible tiles per row so a lone visible tile takes full width. ──

const YEARLY_LAYOUT_KEY = 'yearly-layout-v1';
const YEARLY_MAX_COLS   = 2;
// 3 rows: balance/value chart + price chart, buys/sells + transfers (shared row), and an empty row reserved for future tiles.
const YEARLY_DEFAULT_LAYOUT = [
    ['yearly-block-chart', 'yearly-block-pricechart'],
    ['yearly-block-tiles', 'yearly-block-transfers'],
    []
];

let _yearlyDragEl = null;

function initYearlyLayout() {
    const grid = document.getElementById('yearlyGrid');
    if (!grid) return;

    applyYearlyLayout(grid, _loadYearlyLayout());
    wireYearlyDragAndDrop(grid);
    updateYearlyRowCols(grid);

    const hint = document.getElementById('yearlyLayoutHint');
    if (hint) hint.classList.remove('d-none');
    const resetBtn = document.getElementById('yearlyResetLayoutBtn');
    if (resetBtn) resetBtn.classList.remove('d-none');
}

function _loadYearlyLayout() {
    try {
        const saved = JSON.parse(localStorage.getItem(YEARLY_LAYOUT_KEY));
        if (Array.isArray(saved)) {
            const savedIds   = saved.flat();
            const defaultIds = YEARLY_DEFAULT_LAYOUT.flat();
            if (savedIds.length === defaultIds.length && defaultIds.every(id => savedIds.includes(id))) {
                return saved;
            }
        }
    } catch (e) { /* ignore malformed storage */ }
    return YEARLY_DEFAULT_LAYOUT;
}

function _saveYearlyLayout(grid) {
    const rows = Array.from(grid.querySelectorAll('.yearly-grid-row'));
    const layout = rows.map(row => Array.from(row.querySelectorAll('.yearly-draggable')).map(el => el.id));
    localStorage.setItem(YEARLY_LAYOUT_KEY, JSON.stringify(layout));
}

function applyYearlyLayout(grid, layout) {
    const rows = Array.from(grid.querySelectorAll('.yearly-grid-row'));
    layout.forEach((rowIds, i) => {
        const row = rows[i];
        if (!row) return;
        rowIds.forEach(id => {
            const el = document.getElementById(id);
            if (el) row.appendChild(el);
        });
    });
}

// Counts only visible tiles per row (excludes .d-none), so a hidden tile doesn't cause the visible one to get half width instead of full
function updateYearlyRowCols(grid) {
    grid.querySelectorAll('.yearly-grid-row').forEach(row => {
        const visibleBlocks = Array.from(row.querySelectorAll('.yearly-draggable'))
            .filter(b => !b.classList.contains('d-none'));
        const count = visibleBlocks.length;
        row.style.setProperty('--cols', Math.max(count, 1));
        visibleBlocks.forEach(b => b.style.setProperty('--span', 1));
        row.classList.toggle('empty', count === 0);
    });
}

// ApexCharts only remeasures its SVG width on a window resize event, not on pure CSS grid changes, so fire one synthetically after column/span changes (same fix as depot.js).
function _yearlyTriggerChartResize() {
    window.dispatchEvent(new Event('resize'));
}

// "Reset layout" means a full reset of the yearly view config, not just tile positions, so it also clears the buy/sell filter, transfers filter, and selected year.
function resetYearlyLayout() {
    localStorage.removeItem(YEARLY_LAYOUT_KEY);
    const grid = document.getElementById('yearlyGrid');
    if (grid) {
        applyYearlyLayout(grid, YEARLY_DEFAULT_LAYOUT);
        updateYearlyRowCols(grid);
        _yearlyTriggerChartResize();
    }

    _yearlyTilesFilter = { buy: true, sell: true };
    localStorage.removeItem(YEARLY_TILES_FILTER_KEY);
    document.querySelectorAll('.yearly-tiles-filter-btn').forEach(btn => {
        btn.classList.toggle('is-active', _yearlyTilesFilter[btn.dataset.type]);
    });

    _yearlyTransfersFilterMode = 'solo';
    localStorage.removeItem(YEARLY_TRANSFERS_FILTER_KEY);
    document.querySelectorAll('.yearly-transfers-filter-btn').forEach(btn => {
        btn.classList.toggle('is-active', btn.dataset.mode === 'solo');
    });

    _yearlySelectedYear = null;
    localStorage.removeItem(YEARLY_SELECTED_YEAR_KEY);
    yearlyLoadOverview(null); // reload the overview, also refreshes the year dropdown and re-renders tiles/transfers with the reset filters
}

function wireYearlyDragAndDrop(grid) {
    grid.querySelectorAll('.yearly-draggable').forEach(el => {
        el.querySelectorAll('.yearly-drag-handle').forEach(handle => {
            handle.addEventListener('mousedown', () => el.setAttribute('draggable', 'true'));
        });

        el.addEventListener('dragstart', (e) => {
            _yearlyDragEl = el;
            el.classList.add('dragging');
            e.dataTransfer.effectAllowed = 'move';
        });

        el.addEventListener('dragend', () => {
            el.removeAttribute('draggable');
            el.classList.remove('dragging');
            _yearlyDragEl = null;
            grid.querySelectorAll('.yearly-grid-row.drag-over').forEach(r => r.classList.remove('drag-over'));
            updateYearlyRowCols(grid);
            _yearlyTriggerChartResize();
            _saveYearlyLayout(grid);
        });
    });

    grid.querySelectorAll('.yearly-grid-row').forEach(row => {
        row.addEventListener('dragover', (e) => {
            if (!_yearlyDragEl) return;
            e.preventDefault();

            const visibleExcludingDragged = Array.from(row.querySelectorAll('.yearly-draggable'))
                .filter(b => !b.classList.contains('d-none') && b !== _yearlyDragEl).length;
            if (visibleExcludingDragged >= YEARLY_MAX_COLS) {
                e.dataTransfer.dropEffect = 'none';
                return;
            }
            e.dataTransfer.dropEffect = 'move';
            row.classList.add('drag-over');

            const after = _getYearlyDragAfterElement(row, e.clientX);
            if (after == null) row.appendChild(_yearlyDragEl);
            else row.insertBefore(_yearlyDragEl, after);
            updateYearlyRowCols(grid);
        });

        row.addEventListener('dragleave', (e) => {
            if (e.target === row) row.classList.remove('drag-over');
        });

        row.addEventListener('drop', (e) => e.preventDefault());
    });

    document.addEventListener('mouseup', () => {
        grid.querySelectorAll('.yearly-draggable[draggable="true"]').forEach(el => {
            if (!el.classList.contains('dragging')) el.removeAttribute('draggable');
        });
    });
}

function _getYearlyDragAfterElement(row, x) {
    const els = [...row.querySelectorAll('.yearly-draggable:not(.dragging)')];
    return els.reduce((closest, child) => {
        const box    = child.getBoundingClientRect();
        const offset = x - box.left - box.width / 2;
        if (offset < 0 && offset > closest.offset) return { offset, element: child };
        return closest;
    }, { offset: -Infinity, element: null }).element;
}

// Moves a tile one step via arrow button: swaps within its row, or migrates across a row boundary if the neighbor row has space, otherwise swaps with its edge tile. Same fix as depot.js' moveOverviewBlock/holdings.js' moveHoldingsBlock.
function moveYearlyBlock(id, direction) {
    const grid = document.getElementById('yearlyGrid');
    if (!grid) return;

    const rows   = Array.from(grid.querySelectorAll('.yearly-grid-row'));
    const layout = rows.map(r => Array.from(r.querySelectorAll('.yearly-draggable')).map(el => el.id));

    let rowIdx = -1, posInRow = -1;
    layout.forEach((rowIds, i) => {
        const p = rowIds.indexOf(id);
        if (p !== -1) { rowIdx = i; posInRow = p; }
    });
    if (rowIdx === -1) return;

    const targetPosInRow = posInRow + direction;

    if (targetPosInRow >= 0 && targetPosInRow < layout[rowIdx].length) {
        [layout[rowIdx][posInRow], layout[rowIdx][targetPosInRow]] =
            [layout[rowIdx][targetPosInRow], layout[rowIdx][posInRow]];
    } else {
        const targetRowIdx = rowIdx + direction;
        if (targetRowIdx < 0 || targetRowIdx >= layout.length) return;

        if (layout[targetRowIdx].length < YEARLY_MAX_COLS) {
            layout[rowIdx].splice(posInRow, 1);
            if (direction > 0) layout[targetRowIdx].unshift(id);
            else layout[targetRowIdx].push(id);
        } else {
            const boundaryIdx = direction > 0 ? 0 : layout[targetRowIdx].length - 1;
            const boundaryId  = layout[targetRowIdx][boundaryIdx];
            layout[targetRowIdx][boundaryIdx] = id;
            layout[rowIdx][posInRow] = boundaryId;
        }
    }

    layout.forEach((rowIds, i) => {
        rowIds.forEach(blockId => {
            const el = document.getElementById(blockId);
            if (el) rows[i].appendChild(el);
        });
    });

    updateYearlyRowCols(grid);
    _yearlyTriggerChartResize();
    _saveYearlyLayout(grid);
}

document.addEventListener('DOMContentLoaded', initYearlyLayout);
