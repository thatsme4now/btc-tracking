'use strict';

// ── Bestandsansicht (yearly holdings) ─────────────────────

const HOLDINGS_APEX_DEFAULTS = {
    chart:   { background: 'transparent', fontFamily: "'IBM Plex Mono', monospace", toolbar: { show: false } },
    theme:   { mode: 'dark' },
    tooltip: {
        theme: 'dark',
        style: { fontFamily: "'IBM Plex Mono', monospace", fontSize: '12px' }
    },
    grid: { borderColor: '#252830' },
    dataLabels: { enabled: false },
    xaxis: { labels: { style: { colors: '#6b6f7a' } } },
    yaxis: { labels: { style: { colors: '#6b6f7a' } } },
    legend: {
        fontSize:   '11px',
        fontFamily: "'IBM Plex Mono', monospace",
        labels:     { colors: '#6b6f7a' },
        markers:    { width: 10, height: 10, radius: 2 }
    }
};

// Blue/turquoise shades, cycled per exchange/wallet label (stable, alphabetical order)
const HOLDINGS_BUY_PALETTE = [
    '#378ADD', '#1D9E75', '#185FA5', '#0F6E56',
    '#5DADE2', '#20B2AA', '#3C6E9E', '#3CB8A8'
];

const HOLDINGS_SELL_COLOR    = '#8B6FD8'; // purple
const HOLDINGS_POS_COLOR     = '#1d9e75'; // green (matches --pos)
const HOLDINGS_NEG_COLOR     = '#d85a30'; // red/orange (matches --neg)
const HOLDINGS_BALANCE_COLOR = '#F7931A'; // Bitcoin orange

let _holdingsBuysChart    = null;
let _holdingsPnlChart     = null;
let _holdingsBalanceChart = null;

async function initHoldings() {
    const loadingEl = document.getElementById('holdingsLoading');
    const emptyEl   = document.getElementById('holdingsEmpty');
    const contentEl = document.getElementById('holdingsContent');

    try {
        const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';
        const res = await fetch(`/api/btc-tracking/holdings/yearly?currency=${encodeURIComponent(currency)}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();

        loadingEl.classList.add('d-none');

        if (!Array.isArray(data) || data.length === 0) {
            emptyEl.classList.remove('d-none');
            return;
        }

        contentEl.classList.remove('d-none');
        renderBuysChart(data, currency);
        renderPnlChart(data, currency);
        renderBalanceChart(data);
        loadRefPrices(currency);

    } catch (err) {
        loadingEl.classList.add('d-none');
        emptyEl.classList.remove('d-none');
        emptyEl.textContent = 'Error: ' + err.message;
        console.error('Holdings load failed', err);
    }
}

function fmt(val, currency) {
    if (typeof CURRENCY !== 'undefined') return CURRENCY.format(val, currency);
    return Number(val).toFixed(2);
}

function fmtBtc(val) {
    return Number(val).toLocaleString('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 8 }) + ' BTC';
}

/**
 * Käufe (stacked by exchange, one series per exchange with group:'buys') next to
 * Verkäufe (single series, group:'sells') — two bars per year, side by side.
 */
function renderBuysChart(data, currency) {
    const years = data.map(d => d.year);

    // Stable, alphabetically-ordered set of exchange/wallet labels across all years
    const labelSet = new Set();
    data.forEach(d => Object.keys(d.buysByExchange || {}).forEach(l => labelSet.add(l)));
    const labels = Array.from(labelSet).sort();

    const buySeries = labels.map(label => ({
        name: label,
        group: 'buys',
        data: data.map(d => Number((d.buysByExchange || {})[label] || 0))
    }));

    const sellsName = (typeof t === 'function') ? t('holdings.series.sells') : 'Verkäufe';
    const sellSeries = {
        name: sellsName,
        group: 'sells',
        data: data.map(d => Number(d.totalSells || 0))
    };

    const series = [...buySeries, sellSeries];
    const colors = [...labels.map((_, i) => HOLDINGS_BUY_PALETTE[i % HOLDINGS_BUY_PALETTE.length]), HOLDINGS_SELL_COLOR];

    if (_holdingsBuysChart) { _holdingsBuysChart.destroy(); _holdingsBuysChart = null; }

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series,
        chart: { ...HOLDINGS_APEX_DEFAULTS.chart, type: 'bar', height: 340, stacked: true },
        colors,
        plotOptions: { bar: { columnWidth: '65%' } },
        xaxis: { ...HOLDINGS_APEX_DEFAULTS.xaxis, categories: years },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            y: { formatter: (v) => fmt(v, currency) }
        }
    };

    _holdingsBuysChart = new ApexCharts(document.getElementById('holdingsBuysChart'), options);
    _holdingsBuysChart.render();
}

/**
 * Realisierter G/V + Unrealisierter G/V, one bar group per year. Unrealized is
 * null for past years without a stored 31.12. reference price (see the
 * reference-price table below) — ApexCharts simply leaves a gap there.
 */
function renderPnlChart(data, currency) {
    const years    = data.map(d => d.year);
    const realized = data.map(d => Number(d.realizedPnl || 0));
    const unrealized = data.map(d => (d.unrealizedPnl === null || d.unrealizedPnl === undefined) ? null : Number(d.unrealizedPnl));

    if (_holdingsPnlChart) { _holdingsPnlChart.destroy(); _holdingsPnlChart = null; }

    const realizedName   = (typeof t === 'function') ? t('holdings.series.realized')   : 'Realisierter G/V';
    const unrealizedName = (typeof t === 'function') ? t('holdings.series.unrealized') : 'Unrealisierter G/V';

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series: [
            { name: realizedName, data: realized },
            { name: unrealizedName, data: unrealized }
        ],
        chart: { ...HOLDINGS_APEX_DEFAULTS.chart, type: 'bar', height: 320, stacked: false },
        colors: [
            ({ value }) => value >= 0 ? HOLDINGS_POS_COLOR : HOLDINGS_NEG_COLOR,
            ({ value }) => value >= 0 ? HOLDINGS_POS_COLOR : HOLDINGS_NEG_COLOR
        ],
        plotOptions: { bar: { columnWidth: '65%' } },
        xaxis: { ...HOLDINGS_APEX_DEFAULTS.xaxis, categories: years },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            y: { formatter: (v) => v === null ? '–' : fmt(v, currency) }
        }
    };

    _holdingsPnlChart = new ApexCharts(document.getElementById('holdingsPnlChart'), options);
    _holdingsPnlChart.render();
}

/** Total BTC held across the whole portfolio, as of 31.12. of each year (now, for the current year). */
function renderBalanceChart(data) {
    const years   = data.map(d => d.year);
    const balance = data.map(d => Number(d.btcBalance || 0));

    if (_holdingsBalanceChart) { _holdingsBalanceChart.destroy(); _holdingsBalanceChart = null; }

    const balanceName = (typeof t === 'function') ? t('holdings.series.balance') : 'BTC-Bestand';

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series: [{ name: balanceName, data: balance }],
        chart: { ...HOLDINGS_APEX_DEFAULTS.chart, type: 'bar', height: 280 },
        colors: [HOLDINGS_BALANCE_COLOR],
        plotOptions: { bar: { columnWidth: '55%' } },
        xaxis: { ...HOLDINGS_APEX_DEFAULTS.xaxis, categories: years },
        yaxis: { ...HOLDINGS_APEX_DEFAULTS.yaxis, labels: { style: { colors: '#6b6f7a' }, formatter: (v) => Number(v).toFixed(4) } },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            y: { formatter: (v) => fmtBtc(v) }
        }
    };

    _holdingsBalanceChart = new ApexCharts(document.getElementById('holdingsBalanceChart'), options);
    _holdingsBalanceChart.render();
}

// ── Editable year-end reference prices ────────────────────

function showToast(msg, type) {
    const toast = document.getElementById('statusToast');
    if (!toast) return;
    toast.innerHTML = msg.replace(/\n/g, '<br>');
    toast.className = 'depot-toast ' + (type || '');
    toast.classList.remove('d-none');
    setTimeout(() => toast.classList.add('d-none'), 5000);
}

async function loadRefPrices(currency) {
    const body = document.getElementById('holdingsRefPricesBody');
    if (!body) return;

    try {
        const res = await fetch(`/api/btc-tracking/historical-prices?currency=${encodeURIComponent(currency)}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const rows = await res.json();

        body.innerHTML = '';
        rows.slice().reverse().forEach(row => {
            const tr = document.createElement('tr');

            const tdYear = document.createElement('td');
            tdYear.textContent = row.year;

            const tdInput = document.createElement('td');
            const input = document.createElement('input');
            input.type = 'number';
            input.step = '0.01';
            input.min = '0';
            input.className = 'holdings-refprices-input' + (row.price === null ? ' missing' : '');
            input.placeholder = (typeof t === 'function') ? t('holdings.refPrices.notSet') : 'kein Wert hinterlegt';
            if (row.price !== null && row.price !== undefined) input.value = row.price;
            input.dataset.year = row.year;
            tdInput.appendChild(input);

            const tdBtn = document.createElement('td');
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'holdings-refprices-save';
            btn.innerHTML = '<i class="bi bi-check-lg"></i>';
            btn.title = (typeof t === 'function') ? t('holdings.refPrices.save') : 'Speichern';
            btn.onclick = () => saveRefPrice(row.year, currency, input);
            tdBtn.appendChild(btn);

            tr.appendChild(tdYear);
            tr.appendChild(tdInput);
            tr.appendChild(tdBtn);
            body.appendChild(tr);
        });
    } catch (err) {
        console.error('Reference price load failed', err);
    }
}

async function saveRefPrice(year, currency, input) {
    const value = parseFloat(input.value);
    if (isNaN(value) || value <= 0) {
        showToast((typeof t === 'function') ? t('holdings.refPrices.invalid') : 'Ungültiger Kurs', 'error');
        return;
    }

    try {
        const res = await fetch('/api/btc-tracking/historical-prices', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ year, currency, price: value })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));

        showToast((typeof t === 'function') ? t('holdings.refPrices.saved', { YEAR: year }) : `Kurs für ${year} gespeichert`, 'success');
        // Reload everything — the newly saved price also affects the unrealized G/V chart for that year.
        initHoldings();
    } catch (err) {
        showToast(err.message, 'error');
    }
}

// ── Draggable grid layout (desktop) ───────────────────────
// Same interaction convention as depot.js's dashboard section reordering:
// draggable is only enabled while the handle is held down (dragend clears
// it again), so drag never fights with normal clicks/text-selection inside
// a card. Up/down buttons cover the same reordering for touch/mobile,
// where native drag-and-drop isn't available and the grid collapses to a
// single column via CSS anyway.

const HOLDINGS_LAYOUT_KEY = 'holdings-layout-v1';
const HOLDINGS_MAX_COLS   = 3;
const HOLDINGS_DEFAULT_LAYOUT = [
    ['holdings-block-buys', 'holdings-block-pnl'],
    ['holdings-block-balance', 'holdings-block-refprices'],
    [], [], []
];

let _holdingsDragEl = null;

function initHoldingsLayout() {
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;

    applyHoldingsLayout(grid, _loadHoldingsLayout());
    wireHoldingsDragAndDrop(grid);
    updateHoldingsRowCols(grid);

    const hint = document.getElementById('holdingsLayoutHint');
    if (hint) hint.classList.remove('d-none');
    const resetBtn = document.getElementById('holdingsResetLayoutBtn');
    if (resetBtn) resetBtn.classList.remove('d-none');
}

function _loadHoldingsLayout() {
    try {
        const saved = JSON.parse(localStorage.getItem(HOLDINGS_LAYOUT_KEY));
        if (Array.isArray(saved)) {
            const savedIds   = saved.flat();
            const defaultIds = HOLDINGS_DEFAULT_LAYOUT.flat();
            if (savedIds.length === defaultIds.length && defaultIds.every(id => savedIds.includes(id))) {
                return saved;
            }
        }
    } catch (e) { /* ignore malformed storage */ }
    return HOLDINGS_DEFAULT_LAYOUT;
}

function _saveHoldingsLayout(grid) {
    const rows = Array.from(grid.querySelectorAll('.holdings-grid-row'));
    const layout = rows.map(row => Array.from(row.querySelectorAll('.holdings-draggable')).map(el => el.id));
    localStorage.setItem(HOLDINGS_LAYOUT_KEY, JSON.stringify(layout));
}

function applyHoldingsLayout(grid, layout) {
    const rows = Array.from(grid.querySelectorAll('.holdings-grid-row'));
    layout.forEach((rowIds, i) => {
        const row = rows[i];
        if (!row) return;
        rowIds.forEach(id => {
            const el = document.getElementById(id);
            if (el) row.appendChild(el);
        });
    });
}

function updateHoldingsRowCols(grid) {
    grid.querySelectorAll('.holdings-grid-row').forEach(row => {
        const count = row.querySelectorAll('.holdings-draggable').length;
        row.style.setProperty('--cols', Math.max(count, 1));
        row.classList.toggle('empty', count === 0);
    });
}

function resetHoldingsLayout() {
    localStorage.removeItem(HOLDINGS_LAYOUT_KEY);
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;
    applyHoldingsLayout(grid, HOLDINGS_DEFAULT_LAYOUT);
    updateHoldingsRowCols(grid);
}

function wireHoldingsDragAndDrop(grid) {
    grid.querySelectorAll('.holdings-draggable').forEach(el => {
        el.querySelectorAll('.holdings-drag-handle').forEach(handle => {
            handle.addEventListener('mousedown', () => el.setAttribute('draggable', 'true'));
        });

        el.addEventListener('dragstart', (e) => {
            _holdingsDragEl = el;
            el.classList.add('dragging');
            e.dataTransfer.effectAllowed = 'move';
        });

        el.addEventListener('dragend', () => {
            el.removeAttribute('draggable');
            el.classList.remove('dragging');
            _holdingsDragEl = null;
            grid.querySelectorAll('.holdings-grid-row.drag-over').forEach(r => r.classList.remove('drag-over'));
            updateHoldingsRowCols(grid);
            _saveHoldingsLayout(grid);
        });
    });

    grid.querySelectorAll('.holdings-grid-row').forEach(row => {
        row.addEventListener('dragover', (e) => {
            if (!_holdingsDragEl) return;
            e.preventDefault();

            const countExcludingDragged = row.querySelectorAll('.holdings-draggable').length
                - (row.contains(_holdingsDragEl) ? 1 : 0);
            if (countExcludingDragged >= HOLDINGS_MAX_COLS) {
                e.dataTransfer.dropEffect = 'none';
                return;
            }
            e.dataTransfer.dropEffect = 'move';
            row.classList.add('drag-over');

            const after = _getHoldingsDragAfterElement(row, e.clientX);
            if (after == null) row.appendChild(_holdingsDragEl);
            else row.insertBefore(_holdingsDragEl, after);
            updateHoldingsRowCols(grid);
        });

        row.addEventListener('dragleave', (e) => {
            if (e.target === row) row.classList.remove('drag-over');
        });

        row.addEventListener('drop', (e) => e.preventDefault());
    });

    document.addEventListener('mouseup', () => {
        grid.querySelectorAll('.holdings-draggable[draggable="true"]').forEach(el => {
            if (!el.classList.contains('dragging')) el.removeAttribute('draggable');
        });
    });
}

function _getHoldingsDragAfterElement(row, x) {
    const els = [...row.querySelectorAll('.holdings-draggable:not(.dragging)')];
    return els.reduce((closest, child) => {
        const box    = child.getBoundingClientRect();
        const offset = x - box.left - box.width / 2;
        if (offset < 0 && offset > closest.offset) return { offset, element: child };
        return closest;
    }, { offset: -Infinity, element: null }).element;
}

/** Moves a block one step earlier/later in reading order (row by row, left to right). */
function moveHoldingsBlock(id, direction) {
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;

    const rows     = Array.from(grid.querySelectorAll('.holdings-grid-row'));
    const rowSizes = rows.map(r => r.querySelectorAll('.holdings-draggable').length);
    const flat     = rows.flatMap(r => Array.from(r.querySelectorAll('.holdings-draggable')).map(el => el.id));

    const idx     = flat.indexOf(id);
    const swapIdx = idx + direction;
    if (idx === -1 || swapIdx < 0 || swapIdx >= flat.length) return;

    [flat[idx], flat[swapIdx]] = [flat[swapIdx], flat[idx]];

    let pos = 0;
    rows.forEach((row, i) => {
        flat.slice(pos, pos + rowSizes[i]).forEach(blockId => {
            const el = document.getElementById(blockId);
            if (el) row.appendChild(el);
        });
        pos += rowSizes[i];
    });

    updateHoldingsRowCols(grid);
    _saveHoldingsLayout(grid);
}

document.addEventListener('DOMContentLoaded', initHoldingsLayout);
