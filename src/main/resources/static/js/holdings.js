'use strict';

// ── Holdings view ─────────────────────

/**
 * All Holdings bar-chart tooltips are pinned to the top of their tile via CSS (see
 * .holdings-chart-block .apexcharts-tooltip in holdings.css) instead of ApexCharts' default
 * height-of-the-bar position, and — by default — anchored to the right. That right anchor sits
 * right on top of a bar that's already near the chart's right edge, so this hover handler flips
 * a CSS class (holdings-tooltip-left) on the containing tile whenever the hovered bar is in the
 * last quarter of the x-axis, switching the tooltip to top-left instead. Wired in via
 * HOLDINGS_APEX_DEFAULTS.chart.events for every chart that doesn't define its own chart.events
 * (which would otherwise shadow this one) — the two per-buy charts add it explicitly alongside
 * their own click handler.
 */
function _holdingsTooltipEdgeFlip(event, chartContext, config) {
    const block = chartContext && chartContext.el && chartContext.el.closest('.holdings-chart-block');
    if (!block) return;
    const categories = config && config.w && config.w.config && config.w.config.xaxis && config.w.config.xaxis.categories;
    const total = categories ? categories.length : 0;
    const idx = config ? config.dataPointIndex : null;
    const isRightEdge = total > 0 && idx != null && idx >= Math.ceil(total * 0.75);
    block.classList.toggle('holdings-tooltip-left', isRightEdge);
}

const HOLDINGS_APEX_DEFAULTS = {
    chart:   {
        background: 'transparent', fontFamily: "'IBM Plex Mono', monospace", toolbar: { show: false },
        events: { dataPointMouseEnter: _holdingsTooltipEdgeFlip }
    },
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

// Standalone copy of the allocation donut palette, moved here from the overview page, not shared with depot.js' CHART_COLORS
const HOLDINGS_ALLOCATION_PALETTE = [
    '#F7931A', '#1D9E75', '#378ADD', '#534AB7', '#D85A30',
    '#BA7517', '#185FA5', '#0F6E56', '#3C3489', '#993C1D'
];

// 21-mode uses its own cyclic orange/cyan/purple palette instead of the 10 default colors
const HOLDINGS_ALLOCATION_PALETTE_MODE21 = ['#F7931A', '#00B4CF', '#A915FF'];

function _holdingsAllocationPalette() {
    return document.body.classList.contains('mode-21')
        ? HOLDINGS_ALLOCATION_PALETTE_MODE21
        : HOLDINGS_ALLOCATION_PALETTE;
}

// Per-buy P/L bars use 3 brightness levels by FIFO status (held/partial/realized) so status is visible without clicking
const HOLDINGS_POS_SHADES = { held: '#1d9e75', partial: '#5fbf9e', realized: '#6f8f83' };
const HOLDINGS_NEG_SHADES = { held: '#d85a30', partial: '#e08f6c', realized: '#8f7367' };

// Symmetric log scale for the P/L-per-buy chart (%): linear within ±100%, compressed beyond that so a 2500% buy doesn't dwarf other bars. Display only, tooltips show the real value. Takes exactly one param since it's called as percents.map(_holdingsSymlog).
const HOLDINGS_SYMLOG_THRESHOLD = 100;
const HOLDINGS_SYMLOG_SCALE      = 100;

function _holdingsSymlog(v) {
    const av = Math.abs(v);
    if (av <= HOLDINGS_SYMLOG_THRESHOLD) return v;
    const sign = v < 0 ? -1 : 1;
    return sign * (HOLDINGS_SYMLOG_THRESHOLD + HOLDINGS_SYMLOG_SCALE * Math.log(av / HOLDINGS_SYMLOG_THRESHOLD));
}

// Fixed "round" axis ticks: base set ±100/±50/0 plus enough log steps to cover the max value
function _holdingsBuildYTicks(maxAbsPercent) {
    const ticks = [-100, -50, 0, 50, 100];
    if (maxAbsPercent > HOLDINGS_SYMLOG_THRESHOLD) {
        const steps = [250, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000];
        for (const v of steps) {
            ticks.push(v);
            if (v >= maxAbsPercent) break;
        }
    }
    return ticks;
}

// ── €-chart: separate symlog implementation since there's no natural threshold like "100% = doubling"; threshold is derived from the data instead ──

// Auto threshold for the €-chart: linear range covers ~1/8 of the total span, rounded to a nice 1/2/5 step
function _holdingsAmtThreshold(maxAbs) {
    if (!(maxAbs > 0)) return 100;
    const raw  = maxAbs / 8;
    const exp  = Math.floor(Math.log10(raw));
    const base = Math.pow(10, exp);
    const f    = raw / base;
    const nice = f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10;
    return nice * base;
}

function _holdingsSymlogAmt(v, threshold) {
    const av = Math.abs(v);
    if (av <= threshold) return v;
    const sign = v < 0 ? -1 : 1;
    return sign * (threshold + threshold * Math.log(av / threshold));
}

// Axis ticks for the €-chart: base set ±threshold/±threshold/2/0 plus enough log steps to cover the max value
function _holdingsBuildYTicksAmt(maxAbs, threshold) {
    const ticks = [-threshold, -threshold / 2, 0, threshold / 2, threshold];
    if (maxAbs > threshold) {
        const multipliers = [2.5, 5, 10, 25, 50, 100, 250, 500, 1000];
        for (const m of multipliers) {
            const v = threshold * m;
            ticks.push(v);
            if (v >= maxAbs) break;
        }
    }
    return ticks;
}

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

let _holdingsBuysChart           = null;
let _holdingsRealizedPnlChart    = null;
let _holdingsUnrealizedPnlChart  = null;
let _holdingsBalanceChart        = null;
let _holdingsBuysByExchangeChart = null;

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
        renderBuysByExchangeChart(data, currency);
        renderRealizedPnlChart(data, currency);
        renderUnrealizedPnlChart(data, currency);
        renderBalanceChart(data);
        loadRefPrices(currency);
        initHoldingsBuyPercent(currency);
        loadHoldingsMetrics(currency);
        loadHoldingsAllocation(currency);
        _holdingsInitMempoolClock();

        // Apply any user-hidden tiles only now, AFTER every chart above has rendered at least once
        // into a visible, correctly sized container — hiding is a pure CSS toggle from here on, so
        // ApexCharts never has to mount into a 0-width display:none element.
        applyHoldingsHiddenTiles();

    } catch (err) {
        loadingEl.classList.add('d-none');
        emptyEl.classList.remove('d-none');
        emptyEl.textContent = 'Error: ' + err.message;
        console.error('Holdings load failed', err);
    }
}

// ── Metrics, moved here from the overview page: own fetch since this page loads via JS, using the same shared HoldingsYearlyService.computePortfolioMetrics calculation to avoid drift between pages ──
async function loadHoldingsMetrics(currency) {
    try {
        const res = await fetch(`/api/btc-tracking/metrics?currency=${encodeURIComponent(currency)}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const m = await res.json();
        renderHoldingsMetrics(m, currency);
    } catch (err) {
        console.error('Holdings metrics load failed', err);
    }
}

function renderHoldingsMetrics(m, currency) {
    const cur = (typeof CURRENCY !== 'undefined') ? CURRENCY.get(currency) : { locale: 'de-DE', symbol: '€' };

    document.getElementById('holdingsMetricTotalBtc').textContent =
        Number(m.totalBtc).toLocaleString(cur.locale, { minimumFractionDigits: 8, maximumFractionDigits: 8 });
    document.getElementById('holdingsMetricTotalValue').textContent = fmt(m.totalValue, currency);
    document.getElementById('holdingsMetricTotalSats').textContent =
        Number(m.totalSats).toLocaleString(cur.locale, { maximumFractionDigits: 0 });
    document.getElementById('holdingsMetricInvested').textContent = fmt(m.invested, currency);
    document.getElementById('holdingsMetricRealized').textContent = fmt(m.realized, currency);
    document.getElementById('holdingsMetricTransactions').textContent = m.transactionCount;

    const gainLossEl = document.getElementById('holdingsMetricGainLoss');
    const gainLoss = Number(m.gainLoss);
    gainLossEl.textContent = (gainLoss >= 0 ? '+' : '') + fmt(m.gainLoss, currency);
    gainLossEl.classList.toggle('text-pos', gainLoss >= 0);
    gainLossEl.classList.toggle('text-neg', gainLoss < 0);

    const perfEl = document.getElementById('holdingsMetricPerformance');
    const perf = Number(m.performancePct);
    perfEl.textContent = (perf >= 0 ? '+' : '') +
        perf.toLocaleString(cur.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' %';
    perfEl.classList.toggle('text-pos', perf >= 0);
    perfEl.classList.toggle('text-neg', perf < 0);
}

// ── Allocation donut, moved here from the overview page: fetches /api/btc-tracking/positions instead of using Thymeleaf model attributes since positions aren't server-rendered here ──
let holdingsDonutInstance = null;

async function loadHoldingsAllocation(currency) {
    try {
        const res = await fetch('/api/btc-tracking/positions');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const positions = await res.json();
        initHoldingsDonut(positions, currency);
    } catch (err) {
        console.error('Holdings allocation load failed', err);
    }
}

function initHoldingsDonut(positions, currency) {
    // only show positions with actual holdings; zero-quantity positions would otherwise show as an empty legend segment
    const activePositions = (positions || []).filter(p => Number(p.quantityInSats) > 0);
    const labels = activePositions.map(p => p.label);
    const values = activePositions.map(p => Number(p.totalValue));

    if (!labels.length) return;
    if (holdingsDonutInstance) { holdingsDonutInstance.destroy(); holdingsDonutInstance = null; }

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series: values,
        labels: labels,
        chart: {
            ...HOLDINGS_APEX_DEFAULTS.chart,
            type:   'donut',
            height: window.innerHeight / 3
        },
        colors: _holdingsAllocationPalette(),
        plotOptions: {
            pie: {
                donut: {
                    size: '80%',
                    labels: {
                        show: true,
                        total: {
                            show:      true,
                            label:     t('chart.total'),
                            color:     '#6b6f7a',
                            fontSize:  '30px',
                            formatter: (w) => {
                                const total = w.globals.seriesTotals.reduce((a, b) => a + b, 0);
                                return CURRENCY.format(total, currency);
                            }
                        },
                        value: {
                            color:     '#ddd9d0',
                            fontSize:  '30px',
                            formatter: (val) => CURRENCY.format(Number(val), currency)
                        }
                    }
                }
            }
        },
        legend: {
            ...HOLDINGS_APEX_DEFAULTS.legend,
            position: 'bottom'
        },
        dataLabels: { enabled: false },
        stroke:     { width: 0 }
    };

    holdingsDonutInstance = new ApexCharts(document.getElementById('holdingsDonutChart'), options);
    holdingsDonutInstance.render();

    // same fix as depot.js' initDonut(): forces a width recalculation
    window.dispatchEvent(new Event('resize'));
}

// Hook called by tx-form.js after saving a buy/sell from the detail card; reloads the whole holdings view since one change can affect multiple charts.
function onTxSaved() {
    initHoldings();
}

function fmt(val, currency) {
    if (typeof CURRENCY !== 'undefined') return CURRENCY.format(val, currency);
    return Number(val).toFixed(2);
}

function fmtBtc(val) {
    return Number(val).toLocaleString('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 8 }) + ' BTC';
}

// Compact variant of fmt() for axis labels (0 decimals instead of 2), since tick values are already "round" numbers
function _holdingsFmtCompact(val, currency) {
    if (typeof CURRENCY !== 'undefined') {
        const cur = CURRENCY.get(currency);
        return Number(val).toLocaleString(cur.locale, { maximumFractionDigits: 0 }) + ' ' + cur.symbol;
    }
    return Number(val).toFixed(0);
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
        yaxis: { ...HOLDINGS_APEX_DEFAULTS.yaxis, labels: { style: { colors: '#6b6f7a' }, formatter: (v) => _holdingsFmtCompact(v, currency) } },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            // intersect:false makes the tooltip react across the full column width; shared:true keeps all series for the year together
            shared: true,
            intersect: false,
            y: { formatter: (v) => fmt(v, currency) }
        }
    };

    _holdingsBuysChart = new ApexCharts(document.getElementById('holdingsBuysChart'), options);
    _holdingsBuysChart.render();
}

/**
 * All-time total buy cost per exchange/wallet, as a donut — same visual style as the Allocation
 * donut (initHoldingsDonut), but sourced from the yearly buysByExchange figures already fetched
 * for renderBuysChart above (summed across every year, no separate request). Unlike Allocation,
 * this intentionally includes every exchange that ever had a BUY, even one since fully sold —
 * it's about historical buying activity, not current holdings.
 */
function renderBuysByExchangeChart(data, currency) {
    const totals = {};
    (data || []).forEach(d => {
        Object.entries(d.buysByExchange || {}).forEach(([label, amount]) => {
            totals[label] = (totals[label] || 0) + Number(amount || 0);
        });
    });
    const labels = Object.keys(totals).sort();
    const values = labels.map(l => totals[l]);

    if (_holdingsBuysByExchangeChart) { _holdingsBuysByExchangeChart.destroy(); _holdingsBuysByExchangeChart = null; }
    if (!labels.length) return;

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series: values,
        labels: labels,
        chart: {
            ...HOLDINGS_APEX_DEFAULTS.chart,
            type:   'donut',
            height: window.innerHeight / 3
        },
        colors: labels.map((_, i) => HOLDINGS_BUY_PALETTE[i % HOLDINGS_BUY_PALETTE.length]),
        plotOptions: {
            pie: {
                donut: {
                    size: '80%',
                    labels: {
                        show: true,
                        total: {
                            show:      true,
                            label:     t('chart.total'),
                            color:     '#6b6f7a',
                            fontSize:  '30px',
                            formatter: (w) => {
                                const total = w.globals.seriesTotals.reduce((a, b) => a + b, 0);
                                return CURRENCY.format(total, currency);
                            }
                        },
                        value: {
                            color:     '#ddd9d0',
                            fontSize:  '30px',
                            formatter: (val) => CURRENCY.format(Number(val), currency)
                        }
                    }
                }
            }
        },
        legend: {
            ...HOLDINGS_APEX_DEFAULTS.legend,
            position: 'bottom'
        },
        dataLabels: { enabled: false },
        stroke:     { width: 0 }
    };

    _holdingsBuysByExchangeChart = new ApexCharts(document.getElementById('holdingsBuysByExchangeChart'), options);
    _holdingsBuysByExchangeChart.render();

    // same fix as initHoldingsDonut(): forces a width recalculation
    window.dispatchEvent(new Event('resize'));
}

// Realized P/L per year, its own tile (previously part of a combined realized+unrealized chart)
function renderRealizedPnlChart(data, currency) {
    const years    = data.map(d => d.year);
    const realized = data.map(d => Number(d.realizedPnl || 0));

    if (_holdingsRealizedPnlChart) { _holdingsRealizedPnlChart.destroy(); _holdingsRealizedPnlChart = null; }

    const realizedName = (typeof t === 'function') ? t('holdings.series.realized') : 'Realisierter G/V';

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series: [{ name: realizedName, data: realized }],
        chart: { ...HOLDINGS_APEX_DEFAULTS.chart, type: 'bar', height: 320 },
        colors: [({ value }) => value >= 0 ? HOLDINGS_POS_COLOR : HOLDINGS_NEG_COLOR],
        plotOptions: { bar: { columnWidth: '55%' } },
        xaxis: { ...HOLDINGS_APEX_DEFAULTS.xaxis, categories: years },
        yaxis: { ...HOLDINGS_APEX_DEFAULTS.yaxis, labels: { style: { colors: '#6b6f7a' }, formatter: (v) => _holdingsFmtCompact(v, currency) } },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            shared: false,
            intersect: false,
            y: { formatter: (v) => fmt(v, currency) }
        }
    };

    _holdingsRealizedPnlChart = new ApexCharts(document.getElementById('holdingsRealizedPnlChart'), options);
    _holdingsRealizedPnlChart.render();
}

// Unrealized P/L per year, null for past years without a Dec-31 reference price (ApexCharts just leaves a gap)
function renderUnrealizedPnlChart(data, currency) {
    const years      = data.map(d => d.year);
    const unrealized = data.map(d => (d.unrealizedPnl === null || d.unrealizedPnl === undefined) ? null : Number(d.unrealizedPnl));

    if (_holdingsUnrealizedPnlChart) { _holdingsUnrealizedPnlChart.destroy(); _holdingsUnrealizedPnlChart = null; }

    const unrealizedName = (typeof t === 'function') ? t('holdings.series.unrealized') : 'Unrealisierter G/V';

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series: [{ name: unrealizedName, data: unrealized }],
        chart: { ...HOLDINGS_APEX_DEFAULTS.chart, type: 'bar', height: 320 },
        colors: [({ value }) => value >= 0 ? HOLDINGS_POS_COLOR : HOLDINGS_NEG_COLOR],
        plotOptions: { bar: { columnWidth: '55%' } },
        xaxis: { ...HOLDINGS_APEX_DEFAULTS.xaxis, categories: years },
        yaxis: { ...HOLDINGS_APEX_DEFAULTS.yaxis, labels: { style: { colors: '#6b6f7a' }, formatter: (v) => _holdingsFmtCompact(v, currency) } },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            shared: false,
            intersect: false,
            y: { formatter: (v) => v === null ? '–' : fmt(v, currency) }
        }
    };

    _holdingsUnrealizedPnlChart = new ApexCharts(document.getElementById('holdingsUnrealizedPnlChart'), options);
    _holdingsUnrealizedPnlChart.render();
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
        yaxis: { ...HOLDINGS_APEX_DEFAULTS.yaxis, labels: { style: { colors: '#6b6f7a' }, formatter: (v) => Number(v).toFixed(1) } },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            shared: false,
            intersect: false,
            y: { formatter: (v) => fmtBtc(v) }
        }
    };

    _holdingsBalanceChart = new ApexCharts(document.getElementById('holdingsBalanceChart'), options);
    _holdingsBalanceChart.render();
}

// ── Profit/loss per buy (fixed row 2): one bar per BUY transaction (or, with the granularity
// toggle, one bar per calendar month/year of BUYs), same % as the main table's P/L column, plus
// a portfolio-wide FIFO realized marker (oldest BUY consumed first, transfers ignored) ──
let _holdingsBuyPercentChart = null;
let _holdingsBuyAbsChart     = null;
let _holdingsBuyTxList       = [];   // all BUY transactions, chronological
let _holdingsBuyMeta         = new Map(); // id (string) -> { originalQty, remainingQty, state, sells: [{tx, qty}] }
let _holdingsSelectedBuyId   = null; // selected single-buy id, 'single' granularity only
let _holdingsSelectedPeriodKey = null; // selected period key ('YYYY' or 'YYYY-MM'), 'month'/'year' granularity only
let _holdingsDrilldownBuyId  = null; // set when a buy is opened from within a period's buy list
let _holdingsSelectedIndex   = null; // index into the currently rendered bars, for arrow-key nav and bar highlight
let _holdingsCurrentBars     = [];   // the bars currently rendered by both charts (see _holdingsBuildBars)
let _holdingsCurrentPrice    = 0;

const HOLDINGS_BUY_GRANULARITY_KEY = 'holdings-buy-granularity';
let _holdingsBuyGranularity = _loadHoldingsBuyGranularity();

function _loadHoldingsBuyGranularity() {
    try {
        const saved = localStorage.getItem(HOLDINGS_BUY_GRANULARITY_KEY);
        if (saved === 'single' || saved === 'month' || saved === 'year') return saved;
    } catch (e) { /* localStorage unavailable, fall through to default */ }
    return 'single';
}

function _saveHoldingsBuyGranularity(g) {
    try { localStorage.setItem(HOLDINGS_BUY_GRANULARITY_KEY, g); } catch (e) { /* ignore */ }
}

function _updateHoldingsGranularityButtons() {
    document.querySelectorAll('.holdings-buy-granularity-btn').forEach(btn => {
        btn.classList.toggle('is-active', btn.dataset.granularity === _holdingsBuyGranularity);
    });
}

/** Switches both P/L-per-buy charts between Einzel-Kauf/Pro Monat/Pro Jahr, persists the choice, and re-renders. */
function setHoldingsBuyGranularity(granularity) {
    if (granularity === _holdingsBuyGranularity) return;
    _holdingsBuyGranularity   = granularity;
    _saveHoldingsBuyGranularity(granularity);
    _holdingsSelectedBuyId    = null;
    _holdingsSelectedPeriodKey = null;
    _holdingsDrilldownBuyId   = null;
    _updateHoldingsGranularityButtons();

    const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';
    _refreshHoldingsBuyCharts(currency);
    renderHoldingsDetail(currency);
}

async function initHoldingsBuyPercent(currency) {
    try {
        const [txs, priceRes] = await Promise.all([
            fetch('/api/btc-tracking/transactions').then(r => r.json()),
            fetch(`/api/btc-tracking/current-price?currency=${encodeURIComponent(currency)}`).then(r => r.json())
        ]);

        _holdingsCurrentPrice = Number(priceRes.price || 0);
        _holdingsBuyMeta      = _computeBuyFifoStates(txs);
        _holdingsBuyTxList    = txs
            .filter(tx => tx.type === 'BUY')
            .sort((a, b) => (a.date || '').localeCompare(b.date || ''));

        // keep the current selection if it still exists after reload (single-buy id in 'single'
        // mode, period keys are date-derived so they persist by construction; only the drilldown
        // buy needs re-validating in 'month'/'year' mode)
        if (_holdingsBuyGranularity === 'single') {
            if (_holdingsSelectedBuyId && !_holdingsBuyTxList.some(tx => String(tx.id) === _holdingsSelectedBuyId)) {
                _holdingsSelectedBuyId = null;
            }
        } else if (_holdingsDrilldownBuyId && !_holdingsBuyTxList.some(tx => String(tx.id) === _holdingsDrilldownBuyId)) {
            _holdingsDrilldownBuyId = null;
        }

        _updateHoldingsGranularityButtons();
        _refreshHoldingsBuyCharts(currency);
        renderHoldingsDetail(currency);
    } catch (err) {
        console.error('Buy-percent load failed', err);
    }
}

/** Rebuilds the bars for the active granularity and (re-)renders both charts + the selection highlight. */
function _refreshHoldingsBuyCharts(currency) {
    const bars = _holdingsBuildBars(_holdingsBuyGranularity);
    _holdingsCurrentBars = bars;

    renderBuyPercentChart(bars, currency);
    renderBuyAbsChart(bars, currency);

    const selectedKey = _holdingsBuyGranularity === 'single' ? _holdingsSelectedBuyId : _holdingsSelectedPeriodKey;
    const idx = selectedKey ? bars.findIndex(b => b.key === selectedKey) : -1;
    _holdingsSelectedIndex = idx >= 0 ? idx : null;
}

// Portfolio-wide FIFO across all positions (only BUY/SELL count, transfers ignored, oldest buy consumed first). Visual helper only, doesn't affect the existing weighted-average P/L numbers elsewhere.
function _computeBuyFifoStates(allTx) {
    const meta = new Map();

    const list = allTx
        .filter(tx => tx.type === 'BUY' || tx.type === 'SELL')
        .sort((a, b) => (a.date || '').localeCompare(b.date || ''));

    const queue = []; // { tx, remaining }, a single global queue

    list.forEach(tx => {
        if (tx.type === 'BUY') {
            const qty = Number(tx.quantity) || 0;
            queue.push({ tx, remaining: qty });
            meta.set(String(tx.id), { originalQty: qty, remainingQty: qty, state: 'held', sells: [] });
        } else if (tx.type === 'SELL') {
            let toConsume = Number(tx.quantity) || 0;
            for (const entry of queue) {
                if (toConsume <= 1e-9) break;
                if (entry.remaining <= 1e-9) continue;
                const consumed = Math.min(entry.remaining, toConsume);
                entry.remaining -= consumed;
                toConsume -= consumed;
                const m = meta.get(String(entry.tx.id));
                m.remainingQty = entry.remaining;
                m.sells.push({ tx, qty: consumed });
            }
        }
    });

    meta.forEach(m => {
        if (m.remainingQty >= m.originalQty - 1e-9) m.state = 'held';
        else if (m.remainingQty <= 1e-9) m.state = 'realized';
        else m.state = 'partial';
    });

    return meta;
}

// Same formula as the P/L column in the main table; used directly only for fully-held buys, see _holdingsBuyPercentEffective
function _holdingsBuyPercent(tx, currentPrice) {
    let earning, paid;
    if (tx.currency !== CURRENCY.current()) {
        earning = (currentPrice - ((tx.pricePerBtc + tx.fees) * tx.exchangeRate)) * tx.quantity;
        paid    = (tx.quantityFiat + tx.fees) * tx.exchangeRate;
    } else {
        earning = (currentPrice - (tx.pricePerBtc + tx.fees)) * tx.quantity;
        paid    = (tx.quantityFiat + tx.fees);
    }
    const percentage = paid ? (100 / paid * (paid + earning)) - 100 : 0;
    return { percentage, earningAbs: earning };
}

// Total cost basis of a buy in the current display currency (same conversion as _holdingsBuyPercent)
function _holdingsBuyPaid(tx) {
    return (tx.currency !== CURRENCY.current())
        ? (tx.quantityFiat + tx.fees) * tx.exchangeRate
        : (tx.quantityFiat + tx.fees);
}

// For partial/realized buys: the sold portion uses the actual proceeds of the consuming SELL transaction(s), not the current price; any remaining held portion still uses the current price.
function _holdingsBuyPercentRealized(tx, meta, currentPrice) {
    const paid = _holdingsBuyPaid(tx);
    const displayCurrency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';

    let proceeds = 0;
    (meta.sells || []).forEach(entry => {
        const sellTx  = entry.tx;
        const sellQty = Number(sellTx.quantity) || 0;
        // the sell's fees apply to its whole quantity; take only the share attributable to this buy
        const feeShare = sellQty > 0 ? (Number(sellTx.fees) || 0) * (entry.qty / sellQty) : 0;
        let entryProceeds = entry.qty * (Number(sellTx.pricePerBtc) || 0) - feeShare;
        if (sellTx.currency && sellTx.currency !== displayCurrency) {
            entryProceeds *= (Number(sellTx.exchangeRate) || 1);
        }
        proceeds += entryProceeds;
    });

    // remaining held portion (only for 'partial') still valued at the current price
    proceeds += (meta.remainingQty || 0) * currentPrice;

    const earning = proceeds - paid;
    const percentage = paid ? (100 / paid * (paid + earning)) - 100 : 0;
    return { percentage, earningAbs: earning };
}

// Picks the right calculation by FIFO status: held buys use the current-price formula, partial/realized use actual sell proceeds
function _holdingsBuyPercentEffective(tx, currentPrice) {
    const meta = _holdingsBuyMeta.get(String(tx.id));
    if (!meta || !meta.sells || meta.sells.length === 0) {
        return _holdingsBuyPercent(tx, currentPrice);
    }
    return _holdingsBuyPercentRealized(tx, meta, currentPrice);
}

// ── Granularity aggregation: turns the chronological BUY list into the "bars" that both
// P/L-per-buy charts render, normalizing 'single' (one bar per BUY) and 'month'/'year' (one bar
// per calendar period) into the same shape: { key, label, tooltipX, percentage, earningAbs, state, buys }.
// Aggregation stays FIFO-faithful: each underlying buy is valued exactly as in 'single' mode
// (held -> current price, sold -> actual sell proceeds) and only the resulting amounts are summed,
// so a period's numbers are the sum of what its individual bars would show. ──

function _holdingsPeriodKey(tx, granularity) {
    const d = tx.date ? String(tx.date) : '';
    return granularity === 'year' ? d.substring(0, 4) : d.substring(0, 7);
}

/** Groups buys into calendar periods, chronological (YYYY / YYYY-MM sort lexicographically = chronologically). Periods with no buys are omitted (no zero-filled gaps). */
function _computeBuyPeriods(buys, granularity) {
    const map = new Map();
    buys.forEach(tx => {
        const key = _holdingsPeriodKey(tx, granularity);
        if (!key) return;
        if (!map.has(key)) map.set(key, { key, buys: [] });
        map.get(key).buys.push(tx);
    });
    return Array.from(map.values()).sort((a, b) => a.key.localeCompare(b.key));
}

/** Sums each buy's already FIFO-correct paid/earning across a period, and derives the period's
 *  held/partial/realized status from the aggregate remaining-vs-original quantity (same three-way
 *  rule as a single buy, applied to the period's totals). */
function _holdingsPeriodAggregate(periodBuys, currentPrice) {
    let paid = 0, earning = 0, originalQty = 0, remainingQty = 0;
    periodBuys.forEach(tx => {
        paid    += _holdingsBuyPaid(tx);
        earning += _holdingsBuyPercentEffective(tx, currentPrice).earningAbs;
        const meta = _holdingsBuyMeta.get(String(tx.id));
        if (meta) {
            originalQty  += meta.originalQty;
            remainingQty += meta.remainingQty;
        }
    });
    const percentage = paid ? (100 / paid * (paid + earning)) - 100 : 0;
    let state = 'held';
    if (remainingQty <= 1e-9) state = 'realized';
    else if (remainingQty < originalQty - 1e-9) state = 'partial';
    return { percentage, earningAbs: earning, paid, originalQty, remainingQty, state };
}

/** Builds the normalized bars for the active granularity, used by both P/L-per-buy charts and the detail tile. */
function _holdingsBuildBars(granularity) {
    if (granularity === 'single') {
        return _holdingsBuyTxList.map(tx => {
            const { percentage, earningAbs } = _holdingsBuyPercentEffective(tx, _holdingsCurrentPrice);
            const meta = _holdingsBuyMeta.get(String(tx.id));
            return {
                key: String(tx.id),
                label: tx.date ? String(tx.date).substring(0, 4) : '',
                tooltipX: _holdingsTxTooltipX(tx),
                percentage, earningAbs,
                state: meta ? meta.state : 'held',
                buys: [tx]
            };
        });
    }

    const periods = _computeBuyPeriods(_holdingsBuyTxList, granularity);
    return periods.map(period => {
        const agg = _holdingsPeriodAggregate(period.buys, _holdingsCurrentPrice);
        const countLabel = (typeof t === 'function')
            ? t('holdings.buyDetail.periodCount', { COUNT: period.buys.length })
            : `${period.buys.length} Käufe`;
        return {
            key: period.key,
            label: period.key,
            tooltipX: `${period.key} · ${countLabel}`,
            percentage: agg.percentage,
            earningAbs: agg.earningAbs,
            state: agg.state,
            buys: period.buys
        };
    });
}

// X-axis categories: 'single' keeps the sparse per-year label (only the first bar of each year, to
// avoid overlap with potentially many bars); 'month'/'year' label every bar since there are few.
function _holdingsBarCategories(bars, granularity) {
    if (granularity !== 'single') return bars.map(b => b.label);
    return bars.map((bar, i) => {
        const year = bar.label;
        const prevYear = i > 0 ? bars[i - 1].label : null;
        return (i === 0 || year !== prevYear) ? year : '';
    });
}

// Bar color by sign + FIFO status, shared by both P/L-per-buy charts
function _holdingsColorForBar(bars, values, dataPointIndex) {
    const bar = bars[dataPointIndex];
    if (!bar) return HOLDINGS_POS_COLOR;
    const state = bar.state || 'held';
    const value = values[dataPointIndex];
    return (value >= 0 ? HOLDINGS_POS_SHADES : HOLDINGS_NEG_SHADES)[state] || (value >= 0 ? HOLDINGS_POS_COLOR : HOLDINGS_NEG_COLOR);
}

function _holdingsBarTooltipX(bars, opts) {
    const bar = bars[opts.dataPointIndex];
    return bar ? bar.tooltipX : '';
}

function renderBuyPercentChart(bars, currency) {
    if (_holdingsBuyPercentChart) { _holdingsBuyPercentChart.destroy(); _holdingsBuyPercentChart = null; }

    const percents     = bars.map(b => b.percentage);
    const transformed  = percents.map(_holdingsSymlog);
    const seriesName   = (typeof t === 'function') ? t('holdings.chart.buyPercent') : 'Gewinn/Verlust je Kauf (Prozent)';
    const categories   = _holdingsBarCategories(bars, _holdingsBuyGranularity);

    const maxAbsPercent = Math.max(HOLDINGS_SYMLOG_THRESHOLD, ...percents.map(v => Math.abs(v)));
    const yTicks = _holdingsBuildYTicks(maxAbsPercent);
    const yAnnotations = yTicks.map(tv => ({
        y: _holdingsSymlog(tv),
        borderColor: tv === 0 ? '#3a3f4a' : '#252830',
        label: {
            text: tv + '%',
            borderWidth: 0,
            position: 'left',
            textAnchor: 'end',
            offsetX: -4,
            style: {
                background: 'transparent',
                color: '#6b6f7a',
                fontSize: '10px',
                fontFamily: "'IBM Plex Mono', monospace"
            }
        }
    }));

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series: [{ name: seriesName, data: transformed }],
        chart: {
            ...HOLDINGS_APEX_DEFAULTS.chart,
            type: 'bar',
            height: 360,
            events: {
                dataPointMouseEnter: _holdingsTooltipEdgeFlip,
                click: (event, chartContext, config) => {
                    if (config.dataPointIndex == null || config.dataPointIndex < 0) return;
                    const bar = bars[config.dataPointIndex];
                    if (bar) selectHoldingsBar(bar, config.dataPointIndex);
                }
            }
        },
        colors: [({ dataPointIndex }) => _holdingsColorForBar(bars, percents, dataPointIndex)],
        plotOptions: { bar: { columnWidth: '70%' } },
        // yaxis.labels hidden since percent labels come from y-annotations instead; padding.left reserves the space ApexCharts otherwise wouldn't, avoiding clipped annotation text.
        grid: { ...HOLDINGS_APEX_DEFAULTS.grid, yaxis: { lines: { show: false } }, padding: { left: 46 } },
        annotations: { yaxis: yAnnotations },
        xaxis: {
            ...HOLDINGS_APEX_DEFAULTS.xaxis,
            categories,
            labels: { show: true, rotate: 0, style: { colors: '#6b6f7a', fontSize: '10px' } },
            axisTicks: { show: false }
        },
        yaxis: { labels: { show: false } },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            // shared:false + intersect:false makes the tooltip react across the full column width, not just the (sometimes tiny) visible bar
            shared: false,
            intersect: false,
            x: { formatter: (_, opts) => _holdingsBarTooltipX(bars, opts) },
            y: { formatter: (_, opts) => Number(percents[opts.dataPointIndex]).toFixed(2) + '%' }
        }
    };

    _holdingsBuyPercentChart = new ApexCharts(document.getElementById('holdingsBuyPercentChart'), options);
    _holdingsBuyPercentChart.render().then(() => _holdingsHighlightBar(_holdingsSelectedIndex));
}

// Second chart: same bars/FIFO/color rules and x-axis as renderBuyPercentChart, but the absolute P/L amount instead of percent. Shares the same detail tile via _holdingsSelectedIndex/selectHoldingsBar.
function renderBuyAbsChart(bars, currency) {
    if (_holdingsBuyAbsChart) { _holdingsBuyAbsChart.destroy(); _holdingsBuyAbsChart = null; }

    const amounts      = bars.map(b => b.earningAbs);
    const maxAbsAmount = Math.max(0, ...amounts.map(v => Math.abs(v)));
    const threshold    = _holdingsAmtThreshold(maxAbsAmount);
    const transformed  = amounts.map(v => _holdingsSymlogAmt(v, threshold));
    const seriesName   = (typeof t === 'function') ? t('holdings.chart.buyAbs') : 'Gewinn/Verlust je Kauf (Betrag)';
    const categories   = _holdingsBarCategories(bars, _holdingsBuyGranularity);

    const yTicks = _holdingsBuildYTicksAmt(Math.max(maxAbsAmount, threshold), threshold);
    const yAnnotations = yTicks.map(tv => ({
        y: _holdingsSymlogAmt(tv, threshold),
        borderColor: tv === 0 ? '#3a3f4a' : '#252830',
        label: {
            text: _holdingsFmtCompact(tv, currency),
            borderWidth: 0,
            position: 'left',
            textAnchor: 'end',
            offsetX: -4,
            style: {
                background: 'transparent',
                color: '#6b6f7a',
                fontSize: '10px',
                fontFamily: "'IBM Plex Mono', monospace"
            }
        }
    }));

    const options = {
        ...HOLDINGS_APEX_DEFAULTS,
        series: [{ name: seriesName, data: transformed }],
        chart: {
            ...HOLDINGS_APEX_DEFAULTS.chart,
            type: 'bar',
            height: 300,
            events: {
                dataPointMouseEnter: _holdingsTooltipEdgeFlip,
                click: (event, chartContext, config) => {
                    if (config.dataPointIndex == null || config.dataPointIndex < 0) return;
                    const bar = bars[config.dataPointIndex];
                    if (bar) selectHoldingsBar(bar, config.dataPointIndex);
                }
            }
        },
        colors: [({ dataPointIndex }) => _holdingsColorForBar(bars, amounts, dataPointIndex)],
        plotOptions: { bar: { columnWidth: '70%' } },
        grid: { ...HOLDINGS_APEX_DEFAULTS.grid, yaxis: { lines: { show: false } }, padding: { left: 64 } },
        annotations: { yaxis: yAnnotations },
        xaxis: {
            ...HOLDINGS_APEX_DEFAULTS.xaxis,
            categories,
            labels: { show: true, rotate: 0, style: { colors: '#6b6f7a', fontSize: '10px' } },
            axisTicks: { show: false }
        },
        yaxis: { labels: { show: false } },
        tooltip: {
            ...HOLDINGS_APEX_DEFAULTS.tooltip,
            shared: false,
            intersect: false,
            x: { formatter: (_, opts) => _holdingsBarTooltipX(bars, opts) },
            y: { formatter: (_, opts) => fmt(amounts[opts.dataPointIndex], currency) }
        }
    };

    _holdingsBuyAbsChart = new ApexCharts(document.getElementById('holdingsBuyAbsChart'), options);
    _holdingsBuyAbsChart.render().then(() => _holdingsHighlightBar(_holdingsSelectedIndex));
}

// Tooltip X formatter for a single BUY (date + position), used when building 'single'-granularity bars
function _holdingsTxTooltipX(tx) {
    if (!tx) return '';
    const date = tx.date ? String(tx.date).substring(0, 10) : '';
    return `${date} — ${tx.positionLabel || ''}`;
}

// Highlights the bar at the given index in both P/L-per-buy charts via direct SVG class manipulation, without re-rendering
const HOLDINGS_BUY_CHART_IDS = ['holdingsBuyPercentChart', 'holdingsBuyAbsChart'];

function _holdingsHighlightBar(index) {
    HOLDINGS_BUY_CHART_IDS.forEach(containerId => {
        const container = document.getElementById(containerId);
        if (!container) return;
        const bars = container.querySelectorAll('.apexcharts-bar-area');
        bars.forEach(el => el.classList.remove('holdings-bar-selected'));
        if (index != null && index >= 0 && bars[index]) bars[index].classList.add('holdings-bar-selected');
    });
}

// Selects a bar (a single buy in 'single' mode, a period in 'month'/'year' mode) and shows its detail.
// Picking a new bar always drops any open period-drilldown, so the detail tile follows the click.
function selectHoldingsBar(bar, index) {
    _holdingsDrilldownBuyId = null;
    if (_holdingsBuyGranularity === 'single') {
        _holdingsSelectedBuyId = bar.key;
    } else {
        _holdingsSelectedPeriodKey = bar.key;
    }
    _holdingsSelectedIndex = index != null ? index : _holdingsCurrentBars.indexOf(bar);
    _holdingsHighlightBar(_holdingsSelectedIndex);
    renderHoldingsDetail();
}

// Backward-compatible single-buy selector, still used to re-select a buy id directly (arrow-key nav in 'single' mode).
function selectHoldingsBuy(id) {
    const index = _holdingsCurrentBars.findIndex(b => b.key === String(id));
    if (index === -1) return;
    selectHoldingsBar(_holdingsCurrentBars[index], index);
}

// Arrow keys step to the previous/next bar while a selection is active, no input is focused, and no modal is open (avoids colliding with flatpickr's arrow-key nav)
function _holdingsHandleArrowKey(e) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    if (_holdingsSelectedIndex == null) return;

    const active = document.activeElement;
    const tag = active ? active.tagName : '';
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (active && active.isContentEditable)) return;

    const modal = document.getElementById('txModal');
    if (modal && modal.classList.contains('show')) return;

    const newIndex = _holdingsSelectedIndex + (e.key === 'ArrowRight' ? 1 : -1);
    if (newIndex < 0 || newIndex >= _holdingsCurrentBars.length) return;

    e.preventDefault();
    selectHoldingsBar(_holdingsCurrentBars[newIndex], newIndex);
}

document.addEventListener('keydown', _holdingsHandleArrowKey);

function _holdingsFormatFiat(val, code) {
    if (val == null) return '–';
    const num = Number(val).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return code ? `${num} ${code}` : num;
}

function _holdingsFmt8(val) {
    if (val == null) return '–';
    return Number(val).toLocaleString('de-DE', { minimumFractionDigits: 8, maximumFractionDigits: 8 });
}

function _holdingsFieldRow(label, value, title) {
    return `<div class="flow-tx-field">
        <span class="flow-tx-field-label">${esc(label)}</span>
        <span class="flow-tx-field-value"${title ? ` title="${esc(title)}"` : ''}>${value}</span>
    </div>`;
}

// Dispatches to the right detail renderer for the current granularity/selection/drilldown state.
// Called after every selection change, granularity switch, and data reload.
function renderHoldingsDetail(currency) {
    currency = currency || ((typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR');
    if (_holdingsBuyGranularity === 'single') {
        renderHoldingsBuyDetail(_holdingsSelectedBuyId, currency, false);
    } else if (_holdingsDrilldownBuyId) {
        renderHoldingsBuyDetail(_holdingsDrilldownBuyId, currency, true);
    } else {
        renderHoldingsPeriodDetail(_holdingsSelectedPeriodKey, currency);
    }
}

/** Opens a single buy's full detail card from within a period's buy list, with a link back to that list. */
function drilldownHoldingsBuy(id) {
    _holdingsDrilldownBuyId = String(id);
    renderHoldingsDetail();
}

/** Leaves the single-buy drilldown and returns to the period's buy list. */
function holdingsBackToPeriodList() {
    _holdingsDrilldownBuyId = null;
    renderHoldingsDetail();
}

function renderHoldingsBuyDetail(buyId, currency, showBackLink) {
    const emptyEl    = document.getElementById('holdingsBuyDetailEmpty');
    const contentEl  = document.getElementById('holdingsBuyDetailContent');
    const cardEl     = document.getElementById('holdingsBuyDetailCard');
    const sellsWrap  = document.getElementById('holdingsBuyDetailSellsWrap');
    const sellsEl    = document.getElementById('holdingsBuyDetailSells');
    const periodWrap = document.getElementById('holdingsBuyDetailPeriodWrap');
    if (!emptyEl || !contentEl || !cardEl) return;

    const tx = buyId ? _holdingsBuyTxList.find(item => String(item.id) === String(buyId)) : null;

    if (!tx) {
        emptyEl.classList.remove('d-none');
        contentEl.classList.add('d-none');
        return;
    }
    emptyEl.classList.add('d-none');
    contentEl.classList.remove('d-none');
    if (periodWrap) periodWrap.classList.add('d-none');

    const meta = _holdingsBuyMeta.get(String(tx.id)) || { state: 'held', sells: [] };
    const backLink = showBackLink
        ? `<button type="button" class="btn btn-xs depot-btn-outline mb-2" onclick="holdingsBackToPeriodList()">
               <i class="bi bi-arrow-left me-1"></i>${esc((typeof t === 'function') ? t('holdings.buyDetail.backToPeriod') : 'Zurück zur Periode')}
           </button>`
        : '';
    cardEl.innerHTML = backLink + _renderHoldingsBuyCard(tx, meta, currency);

    if (meta.sells && meta.sells.length) {
        sellsWrap.classList.remove('d-none');
        sellsEl.innerHTML = meta.sells.map(entry => _renderHoldingsSellCard(entry, currency)).join('');
    } else if (sellsWrap) {
        sellsWrap.classList.add('d-none');
        if (sellsEl) sellsEl.innerHTML = '';
    }
}

/** Period ('month'/'year' granularity) detail: an aggregate summary card plus a clickable list of the period's individual buys. */
function renderHoldingsPeriodDetail(periodKey, currency) {
    const emptyEl    = document.getElementById('holdingsBuyDetailEmpty');
    const contentEl  = document.getElementById('holdingsBuyDetailContent');
    const cardEl     = document.getElementById('holdingsBuyDetailCard');
    const sellsWrap  = document.getElementById('holdingsBuyDetailSellsWrap');
    const sellsEl    = document.getElementById('holdingsBuyDetailSells');
    const periodWrap = document.getElementById('holdingsBuyDetailPeriodWrap');
    const periodEl   = document.getElementById('holdingsBuyDetailPeriodList');
    if (!emptyEl || !contentEl || !cardEl || !periodWrap || !periodEl) return;

    const bar = periodKey ? _holdingsCurrentBars.find(b => b.key === periodKey) : null;

    if (!bar) {
        emptyEl.classList.remove('d-none');
        contentEl.classList.add('d-none');
        return;
    }
    emptyEl.classList.add('d-none');
    contentEl.classList.remove('d-none');
    if (sellsWrap) { sellsWrap.classList.add('d-none'); if (sellsEl) sellsEl.innerHTML = ''; }

    cardEl.innerHTML = _renderHoldingsPeriodSummaryCard(bar, currency);
    periodWrap.classList.remove('d-none');
    periodEl.innerHTML = bar.buys.map(tx => _renderHoldingsPeriodBuyRow(tx, currency)).join('');
}

function _renderHoldingsPeriodSummaryCard(bar, currency) {
    const posNeg = bar.percentage >= 0 ? 'text-pos' : 'text-neg';
    const stateInfo = HOLDINGS_STATE_BADGE[bar.state];
    const stateBadge = stateInfo
        ? `<span class="flow-tx-card-badge state-${bar.state}">${esc((typeof t === 'function') ? t(stateInfo.key) : stateInfo.fallback)}</span>`
        : '';
    const countBadge = `<span class="flow-tx-card-badge type-buy">${esc((typeof t === 'function') ? t('holdings.buyDetail.periodCount', { COUNT: bar.buys.length }) : `${bar.buys.length} Käufe`)}</span>`;

    const totalQty = bar.buys.reduce((s, tx) => s + (Number(tx.quantity) || 0), 0);
    const paid     = bar.buys.reduce((s, tx) => s + _holdingsBuyPaid(tx), 0);
    const avgPrice = totalQty > 0 ? paid / totalQty : 0;

    const qtyLine      = _holdingsFieldRow((typeof t === 'function') ? t('table.col.btc') : 'BTC', _holdingsFmt8(totalQty));
    const avgPriceLine = _holdingsFieldRow((typeof t === 'function') ? t('holdings.buyDetail.avgPrice') : 'Ø Preis/BTC', _holdingsFormatFiat(avgPrice, currency));
    const paidLine     = _holdingsFieldRow((typeof t === 'function') ? t('table.col.total') : 'Gesamt', _holdingsFormatFiat(paid, currency));
    const gvPercentLine = _holdingsFieldRow(
        (typeof t === 'function') ? t('holdings.buyDetail.gvPercent') : 'G/V %',
        `<span class="${posNeg}">${bar.percentage >= 0 ? '+' : ''}${bar.percentage.toFixed(2)}%</span>`
    );
    const gvAbsLine = _holdingsFieldRow(
        (typeof t === 'function') ? t('holdings.buyDetail.gvAbs') : 'G/V',
        `<span class="${posNeg}">${bar.earningAbs >= 0 ? '+' : ''}${fmt(bar.earningAbs, currency)}</span>`
    );

    return `<div class="flow-tx-card">
        <div class="flow-tx-card-head">
            <span>${esc(bar.label)}</span>
            <span class="flow-tx-card-actions">${countBadge}${stateBadge}</span>
        </div>
        ${qtyLine}${avgPriceLine}${paidLine}
        ${gvPercentLine}${gvAbsLine}
    </div>`;
}

/** A single clickable row within a period's buy list — drills into that buy's full detail card. */
function _renderHoldingsPeriodBuyRow(tx, currency) {
    const meta = _holdingsBuyMeta.get(String(tx.id)) || { state: 'held' };
    const { percentage } = _holdingsBuyPercentEffective(tx, _holdingsCurrentPrice);
    const posNeg = percentage >= 0 ? 'text-pos' : 'text-neg';
    const date = tx.date ? String(tx.date).substring(0, 10) : '–';
    const stateInfo = HOLDINGS_STATE_BADGE[meta.state];
    const stateBadge = stateInfo
        ? `<span class="flow-tx-card-badge state-${meta.state}">${esc((typeof t === 'function') ? t(stateInfo.key) : stateInfo.fallback)}</span>`
        : '';

    return `<div class="flow-tx-card holdings-period-buy-row" onclick="drilldownHoldingsBuy('${esc(String(tx.id))}')">
        <div class="flow-tx-card-head">
            <span>${esc(date)} · ${esc(tx.positionLabel || '–')}</span>
            <span class="flow-tx-card-actions">${stateBadge}</span>
        </div>
        ${_holdingsFieldRow((typeof t === 'function') ? t('table.col.btc') : 'BTC', _holdingsFmt8(tx.quantity))}
        ${_holdingsFieldRow((typeof t === 'function') ? t('holdings.buyDetail.gvPercent') : 'G/V %', `<span class="${posNeg}">${percentage >= 0 ? '+' : ''}${percentage.toFixed(2)}%</span>`)}
    </div>`;
}

const HOLDINGS_STATE_BADGE = {
    held:     null,
    partial:  { key: 'holdings.buyDetail.state.partial',  fallback: 'Teilweise realisiert' },
    realized: { key: 'holdings.buyDetail.state.realized', fallback: 'Komplett realisiert' }
};

function _renderHoldingsBuyCard(tx, meta, currency) {
    const date = tx.date ? String(tx.date).replace('T', ' ').substring(0, 19) : '–';
    const { percentage, earningAbs } = _holdingsBuyPercentEffective(tx, _holdingsCurrentPrice);
    const posNeg = percentage >= 0 ? 'text-pos' : 'text-neg';

    const stateInfo = HOLDINGS_STATE_BADGE[meta.state];
    const stateBadge = stateInfo
        ? `<span class="flow-tx-card-badge state-${meta.state}">${esc((typeof t === 'function') ? t(stateInfo.key) : stateInfo.fallback)}</span>`
        : '';
    const buyBadge = `<span class="flow-tx-card-badge type-buy">${esc((typeof t === 'function') ? t('flow.legend.buy') : 'Kauf')}</span>`;

    const priceLine = tx.pricePerBtc != null
        ? _holdingsFieldRow((typeof t === 'function') ? t('table.col.pricePerBtc') : 'Preis/BTC', _holdingsFormatFiat(tx.pricePerBtc, tx.currency))
        : '';
    const totalLine = tx.quantityFiat != null
        ? _holdingsFieldRow((typeof t === 'function') ? t('table.col.total') : 'Gesamt', _holdingsFormatFiat(tx.quantityFiat, tx.currency))
        : '';
    const feesLine = tx.fees != null
        ? _holdingsFieldRow((typeof t === 'function') ? t('table.col.fees') : 'Gebühren', _holdingsFormatFiat(tx.fees, tx.feesCurrency || tx.currency))
        : '';
    const commentLine = tx.comment
        ? _holdingsFieldRow((typeof t === 'function') ? t('modal.field.comment') : 'Kommentar', esc(tx.comment), tx.comment)
        : '';

    const gvPercentLine = _holdingsFieldRow(
        (typeof t === 'function') ? t('holdings.buyDetail.gvPercent') : 'G/V %',
        `<span class="${posNeg}">${percentage >= 0 ? '+' : ''}${percentage.toFixed(2)}%</span>`
    );
    const gvAbsLine = _holdingsFieldRow(
        (typeof t === 'function') ? t('holdings.buyDetail.gvAbs') : 'G/V',
        `<span class="${posNeg}">${earningAbs >= 0 ? '+' : ''}${fmt(earningAbs, currency)}</span>`
    );

    const txJson = JSON.stringify(tx).replace(/"/g, '&quot;');

    return `<div class="flow-tx-card">
        <div class="flow-tx-card-head">
            <span>${esc(tx.positionLabel || '–')}</span>
            <span class="flow-tx-card-actions">
                ${buyBadge}${stateBadge}
                <button type="button" class="btn btn-xs depot-btn-icon" title="Edit"
                        onclick="event.stopPropagation(); openEditTx(${txJson})">
                    <i class="bi bi-pencil"></i>
                </button>
                ${tx.blockchainTxId ? `<button type="button" class="btn btn-xs depot-btn-icon" title="${esc((typeof t === 'function') ? t('modal.field.blockchainTxId.jump') : 'Zu mempool springen')}"
                        onclick="event.stopPropagation(); jumpToMempoolTx(${JSON.stringify(tx.blockchainTxId).replace(/"/g,'&quot;')})">
                    <i class="bi bi-box-arrow-up-right"></i>
                </button>` : ''}
            </span>
        </div>
        ${_holdingsFieldRow((typeof t === 'function') ? t('table.col.date') : 'Datum', date)}
        ${_holdingsFieldRow((typeof t === 'function') ? t('table.col.btc') : 'BTC', _holdingsFmt8(tx.quantity))}
        ${priceLine}${totalLine}${feesLine}
        ${gvPercentLine}${gvAbsLine}
        ${commentLine}
    </div>`;
}

function _renderHoldingsSellCard(entry, currency) {
    const tx  = entry.tx;
    const date = tx.date ? String(tx.date).replace('T', ' ').substring(0, 19) : '–';
    const priceLine = tx.pricePerBtc != null
        ? _holdingsFieldRow((typeof t === 'function') ? t('table.col.pricePerBtc') : 'Preis/BTC', _holdingsFormatFiat(tx.pricePerBtc, tx.currency))
        : '';
    const totalLine = tx.quantityFiat != null
        ? _holdingsFieldRow((typeof t === 'function') ? t('table.col.total') : 'Gesamt', _holdingsFormatFiat(tx.quantityFiat, tx.currency))
        : '';
    const sellBadge = `<span class="flow-tx-card-badge type-sell">${esc((typeof t === 'function') ? t('flow.legend.sell') : 'Verkauf')}</span>`;
    const consumedNote = (typeof t === 'function')
        ? t('holdings.buyDetail.consumedFromBuy', { AMOUNT: _holdingsFmt8(entry.qty) })
        : `davon aus diesem Kauf: ${_holdingsFmt8(entry.qty)} BTC`;

    const txJson = JSON.stringify(tx).replace(/"/g, '&quot;');

    return `<div class="flow-tx-card">
        <div class="flow-tx-card-head">
            <span>${esc(tx.positionLabel || '–')}</span>
            <span class="flow-tx-card-actions">
                ${sellBadge}
                <button type="button" class="btn btn-xs depot-btn-icon" title="Edit"
                        onclick="event.stopPropagation(); openEditTx(${txJson})">
                    <i class="bi bi-pencil"></i>
                </button>
                ${tx.blockchainTxId ? `<button type="button" class="btn btn-xs depot-btn-icon" title="${esc((typeof t === 'function') ? t('modal.field.blockchainTxId.jump') : 'Zu mempool springen')}"
                        onclick="event.stopPropagation(); jumpToMempoolTx(${JSON.stringify(tx.blockchainTxId).replace(/"/g,'&quot;')})">
                    <i class="bi bi-box-arrow-up-right"></i>
                </button>` : ''}
            </span>
        </div>
        ${_holdingsFieldRow((typeof t === 'function') ? t('table.col.date') : 'Datum', date)}
        ${_holdingsFieldRow((typeof t === 'function') ? t('table.col.btc') : 'BTC', _holdingsFmt8(tx.quantity))}
        ${priceLine}${totalLine}
        <div class="holdings-buydetail-sell-note">${esc(consumedNote)}</div>
    </div>`;
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

    const fillBtn = document.getElementById('holdingsRefPricesFillBtn');
    if (fillBtn && typeof _mempoolConfigReady !== 'undefined') {
        _mempoolConfigReady.then(() => fillBtn.classList.toggle('d-none', !_mempoolConfigured));
    }

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

// Bulk-fills missing (empty) year-end (31.12.) reference prices for every
// year shown in the table, from the mempool instance configured in
// Settings → Mempool-Integration (EUR+USD together, one historical-price
// call per currency on the backend). Never touches years that already have
// a value (seeded or manual) — mirrors yearlyFillMissingFromMempool in
// yearly.js, but for the historical_price table (one button for the whole
// table, not per-year, since this table has no per-year collapsible block).
async function holdingsFillMissingFromMempool(btn) {
    if (btn) btn.disabled = true;
    try {
        const res = await fetch('/api/btc-tracking/historical-prices/fill-missing', { method: 'POST' });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));

        const msg = (typeof t === 'function')
            ? t('holdings.refPrices.fillMissingResult', { FILLED: data.filled, NOTFOUND: data.notFound })
            : `${data.filled} Jahr(e) befüllt, ${data.notFound} ohne Daten`;
        showToast('✓ ' + msg, 'success');

        // Reload everything — newly filled prices also affect the unrealized G/V chart.
        initHoldings();
    } catch (err) {
        showToast('✗ ' + err.message, 'error');
    } finally {
        if (btn) btn.disabled = false;
    }
}

// ── Mempool tile: embedded "clock" (next-block) visualization, 1-slot, only shown when mempool is
// configured (same _mempoolConfigReady gating as the ref-price fill button above). ──

function _holdingsInitMempoolClock() {
    const block = document.getElementById('holdings-block-mempoolclock');
    const frame = document.getElementById('holdingsMempoolClockFrame');
    if (!block || !frame || typeof _mempoolConfigReady === 'undefined') return;

    _mempoolConfigReady.then(() => {
        const url = (typeof buildMempoolClockUrl === 'function') ? buildMempoolClockUrl() : null;
        block.classList.toggle('d-none', !url);
        block.dataset.mempoolReady = url ? '1' : '';
        if (url && frame.src !== url) frame.src = url;
        // block visibility just changed — recompute its row (collapse it away if mempool isn't
        // configured, or reveal it if it was collapsed during the initial synchronous layout pass)
        const grid = document.getElementById('holdingsGrid');
        if (grid && typeof updateHoldingsRowCols === 'function') updateHoldingsRowCols(grid);
        // the manage-tiles modal's mempool-clock checkbox is only enabled once we know for sure
        _renderHoldingsManageTilesList();
    });
}

function openHoldingsMempoolClock() {
    const url = (typeof buildMempoolRootUrl === 'function') ? buildMempoolRootUrl() : null;
    if (!url) return; // button only shown once configured, but defensive nonetheless
    window.open(url, '_blank', 'noopener');
}

// ── Draggable grid layout (desktop) ───────────────────────
// Same interaction convention as depot.js's dashboard section reordering:
// draggable is only enabled while the handle is held down (dragend clears
// it again), so drag never fights with normal clicks/text-selection inside
// a card. Up/down buttons cover the same reordering for touch/mobile,
// where native drag-and-drop isn't available and the grid collapses to a
// single column via CSS anyway.

const HOLDINGS_LAYOUT_KEY = 'holdings-layout-v7'; // v7: added holdings-block-buysbyexchange
const HOLDINGS_MAX_COLS   = 3;
// 7 rows: metrics (full width), balance/P-L per year, the 2 P/L-per-buy charts + buy detail (capped to 1 slot), buys & sells + reference prices + allocation donut (capped), the mempool clock tile (capped), buys-by-exchange donut (full width, own row).
const HOLDINGS_DEFAULT_LAYOUT = [
    ['holdings-block-metrics'],
    ['holdings-block-balance', 'holdings-block-unrealized-pnl', 'holdings-block-realized-pnl'],
    ['holdings-block-buyabs', 'holdings-block-buypercent', 'holdings-block-buydetail'],
    ['holdings-block-buys', 'holdings-block-refprices', 'holdings-block-allocation'],
    ['holdings-block-mempoolclock'],
    ['holdings-block-buysbyexchange'],
    []
];

let _holdingsDragEl = null;

// ── Tile visibility (remove/re-add via the X button on a tile or the "Kacheln verwalten" modal) ──
// Deliberately tracked in its OWN localStorage key, independent of HOLDINGS_LAYOUT_KEY: the layout
// key's loader (_loadHoldingsLayout below) requires an exact id-set match against the full default
// tile list, so a layout missing hidden tiles would fail that check and silently revert. Keeping
// hidden-state separate means the row/position layout never needs to know about it — a hidden tile
// simply stays in its row/position, only its CSS visibility (.holdings-user-hidden) changes.
const HOLDINGS_HIDDEN_KEY = 'holdings-hidden-tiles-v1';

// id + i18n key for every tile, in the order shown in the "Kacheln verwalten" modal. mempoolGated
// tiles additionally require mempool to be configured (see _holdingsInitMempoolClock) before their
// checkbox can be used.
const HOLDINGS_TILE_META = [
    { id: 'holdings-block-metrics',        i18n: 'metrics.title' },
    { id: 'holdings-block-balance',        i18n: 'holdings.chart.balance' },
    { id: 'holdings-block-unrealized-pnl', i18n: 'holdings.chart.unrealizedPnl' },
    { id: 'holdings-block-realized-pnl',   i18n: 'holdings.chart.realizedPnl' },
    { id: 'holdings-block-buyabs',         i18n: 'holdings.chart.buyAbs' },
    { id: 'holdings-block-buypercent',     i18n: 'holdings.chart.buyPercent' },
    { id: 'holdings-block-buydetail',      i18n: 'holdings.buyDetail.title' },
    { id: 'holdings-block-buys',           i18n: 'holdings.chart.buysAndSells' },
    { id: 'holdings-block-refprices',      i18n: 'holdings.refPrices.title' },
    { id: 'holdings-block-allocation',     i18n: 'chart.allocation' },
    { id: 'holdings-block-mempoolclock',   i18n: 'holdings.mempoolClock.title', mempoolGated: true },
    { id: 'holdings-block-buysbyexchange', i18n: 'holdings.chart.buysByExchange' }
];

function _loadHoldingsHiddenIds() {
    try {
        const saved = JSON.parse(localStorage.getItem(HOLDINGS_HIDDEN_KEY));
        if (Array.isArray(saved)) return saved.filter(id => typeof id === 'string');
    } catch (e) { /* ignore malformed storage */ }
    return [];
}

function _saveHoldingsHiddenIds(ids) {
    localStorage.setItem(HOLDINGS_HIDDEN_KEY, JSON.stringify(ids));
}

/** Applies the persisted hidden-tile set to the DOM. Called once after the initial chart render in
 *  initHoldings() — see the comment there for why this must run after rendering, not before. */
function applyHoldingsHiddenTiles() {
    const hidden = _loadHoldingsHiddenIds();
    HOLDINGS_DEFAULT_LAYOUT.flat().forEach(id => {
        const el = document.getElementById(id);
        if (el) el.classList.toggle('holdings-user-hidden', hidden.includes(id));
    });
    const grid = document.getElementById('holdingsGrid');
    if (grid) updateHoldingsRowCols(grid);
    _renderHoldingsManageTilesList();
}

/** Hides or re-shows a single tile — used by both the modal checkboxes and each tile's own X button. */
function holdingsSetTileHidden(id, hide) {
    const hidden = _loadHoldingsHiddenIds();
    const idx = hidden.indexOf(id);
    if (hide && idx === -1) hidden.push(id);
    if (!hide && idx !== -1) hidden.splice(idx, 1);
    _saveHoldingsHiddenIds(hidden);

    const el = document.getElementById(id);
    if (el) el.classList.toggle('holdings-user-hidden', hide);
    const grid = document.getElementById('holdingsGrid');
    if (grid) updateHoldingsRowCols(grid);
    _renderHoldingsManageTilesList();
}

/** X button on a tile — shorthand for hiding it. */
function holdingsRemoveTile(id) {
    holdingsSetTileHidden(id, true);
}

function _renderHoldingsManageTilesList() {
    const list = document.getElementById('holdingsManageTilesList');
    if (!list) return;
    const hidden = _loadHoldingsHiddenIds();

    list.innerHTML = HOLDINGS_TILE_META.map(tile => {
        const el             = document.getElementById(tile.id);
        const mempoolBlocked = !!tile.mempoolGated && (!el || el.dataset.mempoolReady !== '1');
        const checked        = !hidden.includes(tile.id) ? ' checked' : '';
        const disabled        = mempoolBlocked ? ' disabled' : '';
        return `
            <label class="holdings-manage-tile-row${mempoolBlocked ? ' disabled' : ''}">
                <input type="checkbox"${checked}${disabled} onchange="holdingsSetTileHidden('${tile.id}', !this.checked)"/>
                <span data-i18n="${esc(tile.i18n)}"></span>
                ${mempoolBlocked ? '<span class="holdings-manage-tile-hint" data-i18n="holdings.layout.manage.mempoolHint"></span>' : ''}
            </label>`;
    }).join('');

    if (typeof I18N !== 'undefined') I18N.applyI18n();
}

function initHoldingsLayout() {
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;

    applyHoldingsLayout(grid, _loadHoldingsLayout());
    wireHoldingsDragAndDrop(grid);
    updateHoldingsRowCols(grid);
    _renderHoldingsManageTilesList();

    const hint = document.getElementById('holdingsLayoutHint');
    if (hint) hint.classList.remove('d-none');
    const resetBtn = document.getElementById('holdingsResetLayoutBtn');
    if (resetBtn) resetBtn.classList.remove('d-none');
    const manageBtn = document.getElementById('holdingsManageTilesBtn');
    if (manageBtn) manageBtn.classList.remove('d-none');
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
        const blocks        = Array.from(row.querySelectorAll('.holdings-draggable'));
        // blocks hidden via d-none (e.g. the mempool tile when mempool isn't configured) or via
        // holdings-user-hidden (removed by the user through the X button / "Kacheln verwalten"
        // modal) take no visual space and must not reserve a column or keep the row "occupied" —
        // otherwise a row whose only tile(s) are currently unavailable leaves a blank gap instead
        // of collapsing away.
        const visibleBlocks = blocks.filter(b => !b.classList.contains('d-none') && !b.classList.contains('holdings-user-hidden'));
        const count  = visibleBlocks.length;
        // if a "max 1 slot" tile (buy detail, reference prices) is in this row, force 3 equal columns so it fills exactly 1 of 3, instead of a too-large dynamic cell
        const cappedBlocks = visibleBlocks.filter(b => b.classList.contains('holdings-block-capped'));
        const hasCapped    = cappedBlocks.length > 0;

        if (hasCapped) {
            row.style.setProperty('--cols', HOLDINGS_MAX_COLS);
            const freeBlocks = visibleBlocks.filter(b => !b.classList.contains('holdings-block-capped'));
            cappedBlocks.forEach(b => b.style.setProperty('--span', 1));

            // remaining non-capped blocks split the leftover columns evenly
            const remaining = Math.max(HOLDINGS_MAX_COLS - cappedBlocks.length, 0);
            if (freeBlocks.length > 0) {
                const base = Math.floor(remaining / freeBlocks.length);
                let extra  = remaining - base * freeBlocks.length; // give the remainder to the first blocks
                freeBlocks.forEach(b => {
                    const span = Math.max(base + (extra > 0 ? 1 : 0), 1);
                    if (extra > 0) extra--;
                    b.style.setProperty('--span', span);
                });
            }
        } else {
            row.style.setProperty('--cols', Math.max(count, 1));
            visibleBlocks.forEach(b => b.style.setProperty('--span', 1));
        }
        // no tile assigned to this row at all -> show the "Leer" drag-and-drop placeholder;
        // tile(s) assigned but all currently hidden (config-gated, not user layout) -> collapse the
        // row entirely instead, so no blank gap remains and no drop target is falsely implied.
        row.classList.toggle('empty', blocks.length === 0);
        row.classList.toggle('d-none', blocks.length > 0 && count === 0);
    });
}

function resetHoldingsLayout() {
    localStorage.removeItem(HOLDINGS_LAYOUT_KEY);
    localStorage.removeItem(HOLDINGS_HIDDEN_KEY);
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;
    applyHoldingsLayout(grid, HOLDINGS_DEFAULT_LAYOUT);
    HOLDINGS_DEFAULT_LAYOUT.flat().forEach(id => {
        const el = document.getElementById(id);
        if (el) el.classList.remove('holdings-user-hidden');
    });
    updateHoldingsRowCols(grid);
    _renderHoldingsManageTilesList();
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

            const countExcludingDragged = row.querySelectorAll('.holdings-draggable:not(.holdings-user-hidden)').length
                - (row.contains(_holdingsDragEl) && !_holdingsDragEl.classList.contains('holdings-user-hidden') ? 1 : 0);
            if (countExcludingDragged >= HOLDINGS_MAX_COLS) {
                // Row is already full (and doesn't contain the dragged tile itself) — swap the
                // dragged tile with whichever tile in the row is closest to the cursor instead of
                // blocking the drop. Same "swap with a tile instead of rejecting" idea as
                // moveHoldingsBlock's edge-swap fallback below, just cursor-aware here since drag
                // has a pointer position to work with. Live during the drag, same as the normal
                // reorder-within-a-row case just below.
                const target = _getHoldingsClosestElement(row, e.clientX);
                if (!target || target === _holdingsDragEl) {
                    e.dataTransfer.dropEffect = 'none';
                    return;
                }
                e.dataTransfer.dropEffect = 'move';
                row.classList.add('drag-over');
                _holdingsSwapTiles(_holdingsDragEl, target);
                updateHoldingsRowCols(grid);
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
    const els = [...row.querySelectorAll('.holdings-draggable:not(.dragging):not(.holdings-user-hidden)')];
    return els.reduce((closest, child) => {
        const box    = child.getBoundingClientRect();
        const offset = x - box.left - box.width / 2;
        if (offset < 0 && offset > closest.offset) return { offset, element: child };
        return closest;
    }, { offset: -Infinity, element: null }).element;
}

/** Whichever tile in the row has its horizontal center closest to x — used for the full-row swap
 *  above, where (unlike the insert-position logic in _getHoldingsDragAfterElement) we need one
 *  specific tile to trade places with, not a gap to insert into. */
function _getHoldingsClosestElement(row, x) {
    const els = [...row.querySelectorAll('.holdings-draggable:not(.dragging):not(.holdings-user-hidden)')];
    let closest = null, closestDist = Infinity;
    els.forEach(el => {
        const box    = el.getBoundingClientRect();
        const center = box.left + box.width / 2;
        const dist   = Math.abs(x - center);
        if (dist < closestDist) { closestDist = dist; closest = el; }
    });
    return closest;
}

/** Swaps two tiles' positions in the DOM, across rows or within one — used when a is the dragged
 *  tile and b is the tile it's being dropped onto in an already-full row. */
function _holdingsSwapTiles(a, b) {
    const aNextSibling = a.nextSibling;
    const aParent      = a.parentNode;
    const bParent      = b.parentNode;
    bParent.replaceChild(a, b);
    aParent.insertBefore(b, aNextSibling);
}

/** Moves a block one step earlier/later in reading order (row by row, left to right). */
// Moves a tile one step via arrow button: swaps within its row, or migrates across a row boundary if the neighbor row has space, otherwise swaps with its edge tile. Same fix as depot.js' moveOverviewBlock/yearly.js' moveYearlyBlock.
function moveHoldingsBlock(id, direction) {
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;

    const rows   = Array.from(grid.querySelectorAll('.holdings-grid-row'));
    const layout = rows.map(r => Array.from(r.querySelectorAll('.holdings-draggable')).map(el => el.id));

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

        // Row "fullness" only counts currently visible tiles — a hidden (user-removed) tile takes
        // no visual column, so it must not block a move into a row that still looks like it has room.
        const visibleCountInTargetRow = layout[targetRowIdx].filter(blockId => {
            const el = document.getElementById(blockId);
            return el && !el.classList.contains('holdings-user-hidden');
        }).length;

        if (visibleCountInTargetRow < HOLDINGS_MAX_COLS) {
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

    updateHoldingsRowCols(grid);
    _saveHoldingsLayout(grid);
}

document.addEventListener('DOMContentLoaded', initHoldingsLayout);
