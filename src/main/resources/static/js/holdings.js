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

// Eigenständige Kopie der Farbpalette für den Allocation-Donut (von der
// Übersicht hierher verschoben) — bewusst nicht mit depot.js' CHART_COLORS
// geteilt, siehe "eigenständige Implementierung"-Konvention weiter oben.
const HOLDINGS_ALLOCATION_PALETTE = [
    '#F7931A', '#1D9E75', '#378ADD', '#534AB7', '#D85A30',
    '#BA7517', '#185FA5', '#0F6E56', '#3C3489', '#993C1D'
];

// Per-buy G/V-Balken: 3 Helligkeitsstufen je nach FIFO-Realisiert-Status
// (voll gehalten = kräftig, teilweise realisiert = mittel, komplett realisiert
// = gedämpft), damit man den Status auch ohne Klick auf einen Balken erahnen kann.
const HOLDINGS_POS_SHADES = { held: '#1d9e75', partial: '#5fbf9e', realized: '#6f8f83' };
const HOLDINGS_NEG_SHADES = { held: '#d85a30', partial: '#e08f6c', realized: '#8f7367' };

// Symmetrische Log-Skala fürs Gewinn/Verlust-je-Kauf-Chart (%): innerhalb ±100%
// bleibt linear, darüber wird moderat gestaucht (k=100), damit ein 2500%-Kauf
// nicht mehr alle anderen Balken winzig aussehen lässt. Nur zur Darstellung —
// Tooltip/Achsen-Beschriftung zeigen weiterhin den echten Prozentwert.
// EIGENSTÄNDIGE Implementierung (bewusst NICHT mit dem €-Chart geteilt, siehe
// _holdingsSymlogAmt/_holdingsBuildYTicksAmt weiter unten) — beide Charts
// haben unterschiedliche Skalen-Logik (fester vs. automatisch hergeleiteter
// Schwellenwert) und sollen unabhängig voneinander bleiben. Wichtig: nur EIN
// Parameter, da diese Funktion direkt als percents.map(_holdingsSymlog)
// aufgerufen wird — Array.map ruft den Callback mit (value, index, array)
// auf, ein zusätzlicher Default-Parameter würde durch den map-Index
// überschrieben und die Transformation pro Balken verfälschen.
const HOLDINGS_SYMLOG_THRESHOLD = 100;
const HOLDINGS_SYMLOG_SCALE      = 100;

function _holdingsSymlog(v) {
    const av = Math.abs(v);
    if (av <= HOLDINGS_SYMLOG_THRESHOLD) return v;
    const sign = v < 0 ? -1 : 1;
    return sign * (HOLDINGS_SYMLOG_THRESHOLD + HOLDINGS_SYMLOG_SCALE * Math.log(av / HOLDINGS_SYMLOG_THRESHOLD));
}

/** Feste, "runde" Achsen-Marken: Basis-Set ±100/±50/0, plus so viele der
 *  logarithmischen Zwischenschritte (250/500/1000/2500/...) wie nötig, um den
 *  größten vorkommenden Wert noch abzudecken. */
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

// ── €-Chart: eigene, unabhängige Symlog-Implementierung ──────────────────
// Anders als beim %-Chart gibt es hier keinen natürlichen Schwellenwert (kein
// Äquivalent zu "100% = Verdopplung"), daher wird er aus den Daten hergeleitet
// (_holdingsAmtThreshold). Bewusst als eigene Funktionen (nicht mit dem
// %-Chart generalisiert), damit beide Charts unabhängig bleiben und sich
// nicht gegenseitig über geteilten Code beeinflussen können.

/** Automatischer Schwellenwert für den €-Chart: linearer Bereich deckt grob
 *  den kleineren Teil (~1/8) der Gesamtspanne ab, gerundet auf eine "runde"
 *  1/2/5-Stufe, damit die Achsen-Marken lesbar bleiben (z.B. 500 statt
 *  486,32). Passt sich damit automatisch an Portfoliogröße und
 *  Anzeigewährung an. */
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

/** Achsen-Marken für den €-Chart: Basis-Set ±threshold/±threshold/2/0, plus
 *  so viele der logarithmischen Zwischenschritte (2.5x/5x/10x/25x/... des
 *  Schwellenwerts) wie nötig, um den größten vorkommenden Wert noch abzudecken. */
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

let _holdingsBuysChart          = null;
let _holdingsRealizedPnlChart   = null;
let _holdingsUnrealizedPnlChart = null;
let _holdingsBalanceChart       = null;

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
        renderRealizedPnlChart(data, currency);
        renderUnrealizedPnlChart(data, currency);
        renderBalanceChart(data);
        loadRefPrices(currency);
        initHoldingsBuyPercent(currency);
        loadHoldingsMetrics(currency);
        loadHoldingsAllocation(currency);

    } catch (err) {
        loadingEl.classList.add('d-none');
        emptyEl.classList.remove('d-none');
        emptyEl.textContent = 'Error: ' + err.message;
        console.error('Holdings load failed', err);
    }
}

// ── Kennzahlen (von der Übersicht hierher verschoben) ──────────────────────
// Eigener Fetch statt Thymeleaf-Modellattribute, da diese Seite ihre Inhalte
// generell per JS lädt (siehe initHoldings) — der Endpoint liefert dieselbe
// geteilte Berechnung, die zuvor die Übersicht inline berechnet hat (siehe
// HoldingsYearlyService.computePortfolioMetrics, Grund: Drift-Vermeidung
// zwischen beiden Seiten).
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

// ── Allocation-Donut (von der Übersicht hierher verschoben) ───────────────
// Eigenständige Kopie von initDonut() (depot.js), siehe "eigenständige
// Implementierung"-Konvention — Datenquelle hier ist ein Fetch von
// /api/btc-tracking/positions statt Thymeleaf-Modellattribute, da Positionen
// auf dieser Seite (anders als auf der Übersicht) nie serverseitig gerendert
// werden.
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
    // Nur Positionen mit tatsächlichem Bestand in der Legende/im Donut zeigen —
    // Positionen mit quantityInSats <= 0 (z.B. komplett verkauft/abgezogen)
    // würden sonst als 0-Segment in der Legende auftauchen, ohne sichtbaren
    // Anteil im Ring. Gleicher Filter-Gedanke wie beim "Leere ausblenden"-
    // Toggle der Positionsliste (siehe depot.js togglePosEmptyFilter).
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
        colors: HOLDINGS_ALLOCATION_PALETTE,
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

    // Gleicher Fix wie in depot.js' initDonut(): erzwingt eine korrekte
    // Breiten-Neuberechnung, falls initHoldingsLayout() (DOMContentLoaded)
    // die Grid-Spalten bereits vor diesem async-Callback final gesetzt hat.
    window.dispatchEvent(new Event('resize'));
}

// Hook, den tx-form.js (saveOrAddTx) nach erfolgreichem Speichern eines Kaufs/
// Verkaufs aus der Detail-Karte (Kachel B) aufruft. Lädt die komplette
// Bestandsansicht neu, da eine geänderte Transaktion mehrere Charts gleichzeitig
// betreffen kann (Käufe/Verkäufe pro Jahr, G/V, Bestand, und den neuen
// Gewinn/Verlust-je-Kauf-Chart selbst).
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

/** Kompakte Variante von fmt() für Achsen-Beschriftungen (0 statt 2 Nachkommastellen) —
 *  die Tick-Werte sind bereits "runde" Zahlen (siehe _holdingsAmtThreshold/_holdingsBuildYTicksAmt),
 *  Nachkommastellen wären dort nur Rauschen und machen die Labels unnötig lang. */
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
            // intersect:false → Tooltip reagiert auf die ganze Spaltenbreite (auch
            // oberhalb/unterhalb kleiner Balken), shared:true behält die kombinierte
            // Anzeige aller Exchange/Wallet-Anteile + Verkäufe für dieses Jahr bei.
            shared: true,
            intersect: false,
            y: { formatter: (v) => fmt(v, currency) }
        }
    };

    _holdingsBuysChart = new ApexCharts(document.getElementById('holdingsBuysChart'), options);
    _holdingsBuysChart.render();
}

/** Realisierter G/V pro Jahr — eigene Kachel (früher Teil des kombinierten
 *  Realisiert+Unrealisiert-Charts, auf Wunsch in 2 separate Kacheln aufgeteilt). */
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

/** Unrealisierter G/V pro Jahr — eigene Kachel. Ist null für vergangene Jahre
 *  ohne hinterlegten 31.12.-Referenzkurs (siehe Referenzkurs-Tabelle) —
 *  ApexCharts lässt dort einfach eine Lücke. */
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

// ── Gewinn/Verlust je Kauf (Row 2, feste Reihe) ────────────
// Ein Balken pro BUY-Transaktion (chronologisch), Prozentwert exakt wie die
// bestehende G/V-Spalte in der Haupttabelle (depot.js renderTxTable). Zusätzlich
// eine rein visuelle FIFO-Realisiert-Markierung PORTFOLIO-WEIT (positionsübergreifend):
// jeder SELL verbraucht schlicht die ältesten noch offenen BUY-Mengen im gesamten
// Portfolio, unabhängig von Position/Exchange/Wallet — Transfers/Deposits/Withdraws
// ändern an den G/V-Zahlen nichts und werden hier ignoriert.
let _holdingsBuyPercentChart = null;
let _holdingsBuyAbsChart     = null;
let _holdingsBuyTxList       = [];   // alle BUY-Transaktionen, chronologisch
let _holdingsBuyMeta         = new Map(); // id (string) -> { originalQty, remainingQty, state, sells: [{tx, qty}] }
let _holdingsSelectedBuyId   = null;
let _holdingsSelectedIndex   = null; // Index in _holdingsBuyTxList, für ←/→-Navigation und Balken-Highlight
let _holdingsCurrentPrice    = 0;

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

        // Bereits gewählten Kauf (falls noch vorhanden) beibehalten, sonst Platzhalter.
        // _holdingsSelectedIndex VOR renderBuyPercentChart() aktualisieren, damit das
        // Highlight nach dem (asynchronen) Chart-Render den richtigen Balken trifft.
        const stillExistsIndex = _holdingsSelectedBuyId
            ? _holdingsBuyTxList.findIndex(tx => String(tx.id) === _holdingsSelectedBuyId)
            : -1;
        _holdingsSelectedIndex = stillExistsIndex >= 0 ? stillExistsIndex : null;
        if (_holdingsSelectedIndex === null) _holdingsSelectedBuyId = null;

        renderBuyPercentChart(_holdingsBuyTxList, _holdingsCurrentPrice, currency);
        renderBuyAbsChart(_holdingsBuyTxList, _holdingsCurrentPrice, currency);
        renderHoldingsBuyDetail(_holdingsSelectedBuyId, currency);
    } catch (err) {
        console.error('Buy-percent load failed', err);
    }
}

/**
 * FIFO portfolio-weit über ALLE Positionen hinweg (nur BUY/SELL werden
 * betrachtet, ältester Kauf zuerst verbraucht — unabhängig davon, auf welcher
 * Position/Exchange/Wallet Kauf und Verkauf jeweils stattfanden). Nur SELL
 * zählt als "realisiert" — TRANSFER_IN/TRANSFER_OUT/WITHDRAW/etc. verschieben
 * BTC nur zwischen Positionen, verkaufen es nicht, und bleiben hier
 * unberücksichtigt. Rein visuelle Hilfsberechnung, ändert nichts an den
 * bestehenden (weighted-average) G/V-Zahlen an anderer Stelle.
 */
function _computeBuyFifoStates(allTx) {
    const meta = new Map();

    const list = allTx
        .filter(tx => tx.type === 'BUY' || tx.type === 'SELL')
        .sort((a, b) => (a.date || '').localeCompare(b.date || ''));

    const queue = []; // { tx, remaining } — eine einzige globale Queue

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

/** Identische Formel wie die G/V-Spalte in der Haupttabelle (depot.js renderTxTable).
 *  Wird NUR noch für rein gehaltene Käufe (state 'held', keine Sells) direkt
 *  verwendet — siehe _holdingsBuyPercentEffective. */
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

/** Gesamt-Kostenbasis eines Kaufs in der aktuell gewählten Anzeigewährung
 *  (identischer Umrechnungs-Ausschnitt wie in _holdingsBuyPercent). */
function _holdingsBuyPaid(tx) {
    return (tx.currency !== CURRENCY.current())
        ? (tx.quantityFiat + tx.fees) * tx.exchangeRate
        : (tx.quantityFiat + tx.fees);
}

/**
 * Für teilweise oder komplett realisierte Käufe (FIFO-Status 'partial'/
 * 'realized'): der bereits verkaufte Anteil fließt mit dem TATSÄCHLICHEN
 * Verkaufserlös der jeweils konsumierenden SELL-Transaktion(en) ein
 * (Verkaufspreis × verkaufte Menge, abzüglich anteiliger Verkaufsgebühren,
 * währungskonvertiert über den Wechselkurs der jeweiligen SELL-Transaktion),
 * NICHT mit dem aktuellen BTC-Kurs — der ist für damals bereits realisierte
 * Gewinne/Verluste irrelevant. Ein bei 'partial' noch offener Rest wird
 * weiterhin zum aktuellen Kurs bewertet. Wird derselbe Kauf von mehreren
 * SELLs teilweise konsumiert, werden deren Erlöse anteilig aufsummiert.
 */
function _holdingsBuyPercentRealized(tx, meta, currentPrice) {
    const paid = _holdingsBuyPaid(tx);
    const displayCurrency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';

    let proceeds = 0;
    (meta.sells || []).forEach(entry => {
        const sellTx  = entry.tx;
        const sellQty = Number(sellTx.quantity) || 0;
        // Gebühren der SELL-Transaktion gelten für deren GESAMTE verkaufte Menge —
        // hier nur der auf diesen Kauf entfallende Anteil, sonst würden Gebühren
        // mehrfach gezählt, falls ein SELL mehrere Käufe gleichzeitig konsumiert.
        const feeShare = sellQty > 0 ? (Number(sellTx.fees) || 0) * (entry.qty / sellQty) : 0;
        let entryProceeds = entry.qty * (Number(sellTx.pricePerBtc) || 0) - feeShare;
        if (sellTx.currency && sellTx.currency !== displayCurrency) {
            entryProceeds *= (Number(sellTx.exchangeRate) || 1);
        }
        proceeds += entryProceeds;
    });

    // Noch gehaltener Rest (nur bei 'partial' > 0) weiterhin zum aktuellen Kurs.
    proceeds += (meta.remainingQty || 0) * currentPrice;

    const earning = proceeds - paid;
    const percentage = paid ? (100 / paid * (paid + earning)) - 100 : 0;
    return { percentage, earningAbs: earning };
}

/** Wählt je nach FIFO-Status die passende Berechnung: reine Käufe (held) über
 *  die bestehende, aktuelle-Kurs-basierte Formel; teilweise/komplett
 *  realisierte Käufe über den tatsächlichen Verkaufserlös (s.o.). */
function _holdingsBuyPercentEffective(tx, currentPrice) {
    const meta = _holdingsBuyMeta.get(String(tx.id));
    if (!meta || !meta.sells || meta.sells.length === 0) {
        return _holdingsBuyPercent(tx, currentPrice);
    }
    return _holdingsBuyPercentRealized(tx, meta, currentPrice);
}

function renderBuyPercentChart(buys, currentPrice, currency) {
    if (_holdingsBuyPercentChart) { _holdingsBuyPercentChart.destroy(); _holdingsBuyPercentChart = null; }

    const percents    = buys.map(tx => _holdingsBuyPercentEffective(tx, currentPrice).percentage);
    const transformed  = percents.map(_holdingsSymlog);
    const seriesName   = (typeof t === 'function') ? t('holdings.chart.buyPercent') : 'Gewinn/Verlust je Kauf (Prozent)';

    // Jahreszahl nur am jeweils ersten Kauf eines Jahres, sonst leer — grobe
    // Zeitachse ohne dass sich hunderte Labels überlagern.
    const categories = _holdingsYearCategories(buys);

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
                click: (event, chartContext, config) => {
                    if (config.dataPointIndex == null || config.dataPointIndex < 0) return;
                    const tx = buys[config.dataPointIndex];
                    if (tx) selectHoldingsBuy(tx.id);
                }
            }
        },
        colors: [({ dataPointIndex }) => _holdingsColorForIndex(buys, percents, dataPointIndex)],
        plotOptions: { bar: { columnWidth: '70%' } },
        // yaxis.labels sind hier ausgeblendet (die Prozent-Beschriftung kommt
        // stattdessen von den y-Annotations links außen, siehe yAnnotations).
        // Ohne eigene y-Achsen-Labels reserviert ApexCharts KEINEN Platz links
        // vom Plot-Bereich — die Annotation-Texte (bis zu "-2500%") ragen dann
        // über den linken SVG-Rand hinaus und werden dort abgeschnitten (z.B.
        // "-100%" → sichtbar nur "00%", "250%" → sichtbar nur "50%", was wie
        // ein falscher Skalen-Wert aussieht, aber nur ein Clipping-Bug ist).
        // padding.left schafft den fehlenden Rand.
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
            // shared:false + intersect:false → Tooltip reagiert auf die ganze Spalten-
            // Breite (oberhalb/unterhalb des Balkens), nicht nur exakt auf die (bei
            // kleinen Werten manchmal winzige) sichtbare Balkenfläche.
            shared: false,
            intersect: false,
            x: { formatter: (_, opts) => _holdingsTxTooltipX(buys, opts) },
            y: { formatter: (_, opts) => Number(percents[opts.dataPointIndex]).toFixed(2) + '%' }
        }
    };

    _holdingsBuyPercentChart = new ApexCharts(document.getElementById('holdingsBuyPercentChart'), options);
    _holdingsBuyPercentChart.render().then(() => _holdingsHighlightBar(_holdingsSelectedIndex));
}

/**
 * Zweite Grafik (eigene feste Reihe): identische Balken/FIFO/Farb-Regeln und
 * X-Achse wie renderBuyPercentChart, aber der ABSOLUTE Gewinn/Verlust je Kauf
 * in der aktuell gewählten Währung. Ebenfalls symlog-skaliert wie der %-Chart —
 * anders als bei Prozent gibt es hier aber keinen natürlichen Schwellenwert
 * (kein Äquivalent zu "100% = Verdopplung"), daher wird er automatisch aus den
 * Daten hergeleitet (_holdingsAmtThreshold) und passt sich so an Portfoliogröße
 * und Anzeigewährung an. Teilt sich über die gemeinsame Auswahl
 * (_holdingsSelectedIndex/selectHoldingsBuy) dieselbe Detail-Kachel wie der
 * %-Chart, um sie nicht zu duplizieren.
 */
function renderBuyAbsChart(buys, currentPrice, currency) {
    if (_holdingsBuyAbsChart) { _holdingsBuyAbsChart.destroy(); _holdingsBuyAbsChart = null; }

    const amounts     = buys.map(tx => _holdingsBuyPercentEffective(tx, currentPrice).earningAbs);
    const maxAbsAmount = Math.max(0, ...amounts.map(v => Math.abs(v)));
    const threshold    = _holdingsAmtThreshold(maxAbsAmount);
    const transformed  = amounts.map(v => _holdingsSymlogAmt(v, threshold));
    const seriesName   = (typeof t === 'function') ? t('holdings.chart.buyAbs') : 'Gewinn/Verlust je Kauf (Betrag)';
    const categories   = _holdingsYearCategories(buys);

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
                click: (event, chartContext, config) => {
                    if (config.dataPointIndex == null || config.dataPointIndex < 0) return;
                    const tx = buys[config.dataPointIndex];
                    if (tx) selectHoldingsBuy(tx.id);
                }
            }
        },
        colors: [({ dataPointIndex }) => _holdingsColorForIndex(buys, amounts, dataPointIndex)],
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
            x: { formatter: (_, opts) => _holdingsTxTooltipX(buys, opts) },
            y: { formatter: (_, opts) => fmt(amounts[opts.dataPointIndex], currency) }
        }
    };

    _holdingsBuyAbsChart = new ApexCharts(document.getElementById('holdingsBuyAbsChart'), options);
    _holdingsBuyAbsChart.render().then(() => _holdingsHighlightBar(_holdingsSelectedIndex));
}

/** Jahreszahl nur am jeweils ersten Kauf eines Jahres, sonst leer — von beiden
 *  Gewinn/Verlust-je-Kauf-Charts (%, Betrag) gemeinsam genutzt. */
function _holdingsYearCategories(buys) {
    return buys.map((tx, i) => {
        const year = tx.date ? String(tx.date).substring(0, 4) : '';
        const prevYear = i > 0 && buys[i - 1].date ? String(buys[i - 1].date).substring(0, 4) : null;
        return (i === 0 || year !== prevYear) ? year : '';
    });
}

/** Balkenfarbe nach Vorzeichen + FIFO-Status — von beiden Gewinn/Verlust-je-Kauf-
 *  Charts gemeinsam genutzt, jeweils mit ihrem eigenen Werte-Array (Prozent bzw.
 *  absoluter Betrag) zur Vorzeichen-/Zustands-Bestimmung. */
function _holdingsColorForIndex(buys, values, dataPointIndex) {
    const tx = buys[dataPointIndex];
    if (!tx) return HOLDINGS_POS_COLOR;
    const meta  = _holdingsBuyMeta.get(String(tx.id));
    const state = meta ? meta.state : 'held';
    const value = values[dataPointIndex];
    return (value >= 0 ? HOLDINGS_POS_SHADES : HOLDINGS_NEG_SHADES)[state] || (value >= 0 ? HOLDINGS_POS_COLOR : HOLDINGS_NEG_COLOR);
}

/** Tooltip-X-Formatter (Datum + Position) — von beiden Charts gemeinsam genutzt. */
function _holdingsTxTooltipX(buys, opts) {
    const tx = buys[opts.dataPointIndex];
    if (!tx) return '';
    const date = tx.date ? String(tx.date).substring(0, 10) : '';
    return `${date} — ${tx.positionLabel || ''}`;
}

/** Hebt genau den Balken mit dem übergebenen Datenindex optisch hervor (Border),
 *  in BEIDEN Gewinn/Verlust-je-Kauf-Charts gleichzeitig (synchronisierte Auswahl,
 *  siehe selectHoldingsBuy) — ohne die Charts neu zu rendern (direkte SVG-
 *  Klassenmanipulation, da ApexCharts-Annotationen auf einer Kategorie-Achse mit
 *  mehrheitlich leeren (doppelten) Labels keine eindeutige Balken-Zuordnung mehr
 *  erlauben). */
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

function selectHoldingsBuy(id) {
    _holdingsSelectedBuyId = String(id);
    _holdingsSelectedIndex = _holdingsBuyTxList.findIndex(tx => String(tx.id) === _holdingsSelectedBuyId);
    if (_holdingsSelectedIndex === -1) _holdingsSelectedIndex = null;
    _holdingsHighlightBar(_holdingsSelectedIndex);
    const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';
    renderHoldingsBuyDetail(_holdingsSelectedBuyId, currency);
}

/** ←/→ blättert zum vorherigen/nächsten Kauf (chronologische Reihenfolge wie im
 *  Chart), solange eine Auswahl aktiv ist, kein Eingabefeld fokussiert ist und
 *  kein Modal-Dialog offen ist (sonst Kollision mit flatpickr-Pfeiltasten-Nav.
 *  im Bearbeiten-Dialog). Kein Wrap-Around an den Rändern der Liste.
 */
function _holdingsHandleArrowKey(e) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    if (_holdingsSelectedBuyId == null) return;

    const active = document.activeElement;
    const tag = active ? active.tagName : '';
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (active && active.isContentEditable)) return;

    const modal = document.getElementById('txModal');
    if (modal && modal.classList.contains('show')) return;

    const currentIndex = _holdingsBuyTxList.findIndex(tx => String(tx.id) === _holdingsSelectedBuyId);
    if (currentIndex === -1) return;

    const newIndex = currentIndex + (e.key === 'ArrowRight' ? 1 : -1);
    if (newIndex < 0 || newIndex >= _holdingsBuyTxList.length) return;

    e.preventDefault();
    selectHoldingsBuy(_holdingsBuyTxList[newIndex].id);
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

function renderHoldingsBuyDetail(buyId, currency) {
    const emptyEl   = document.getElementById('holdingsBuyDetailEmpty');
    const contentEl = document.getElementById('holdingsBuyDetailContent');
    const cardEl    = document.getElementById('holdingsBuyDetailCard');
    const sellsWrap = document.getElementById('holdingsBuyDetailSellsWrap');
    const sellsEl   = document.getElementById('holdingsBuyDetailSells');
    if (!emptyEl || !contentEl || !cardEl) return;

    const tx = buyId ? _holdingsBuyTxList.find(item => String(item.id) === String(buyId)) : null;

    if (!tx) {
        emptyEl.classList.remove('d-none');
        contentEl.classList.add('d-none');
        return;
    }
    emptyEl.classList.add('d-none');
    contentEl.classList.remove('d-none');

    const meta = _holdingsBuyMeta.get(String(tx.id)) || { state: 'held', sells: [] };
    cardEl.innerHTML = _renderHoldingsBuyCard(tx, meta, currency);

    if (meta.sells && meta.sells.length) {
        sellsWrap.classList.remove('d-none');
        sellsEl.innerHTML = meta.sells.map(entry => _renderHoldingsSellCard(entry, currency)).join('');
    } else if (sellsWrap) {
        sellsWrap.classList.add('d-none');
        if (sellsEl) sellsEl.innerHTML = '';
    }
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

const HOLDINGS_LAYOUT_KEY = 'holdings-layout-v5';
const HOLDINGS_MAX_COLS   = 3;
// 7 Reihen. Reihe 1: Kennzahlen (volle Breite, von der Übersicht hierher
// verschoben). Reihe 2: Bestand/G-V pro Jahr. Reihe 3: die beiden Gewinn/
// Verlust-je-Kauf-Charts zusammen mit Kauf-Details (letztere ist "capped"
// auf 1 Slot, siehe updateHoldingsRowCols). Reihe 4: Käufe & Verkäufe (füllt
// den verbleibenden freien Slot) neben Referenzkurse und Allocation-Donut
// (beide "capped", ebenfalls von der Übersicht hierher verschoben).
const HOLDINGS_DEFAULT_LAYOUT = [
    ['holdings-block-metrics'],
    ['holdings-block-balance', 'holdings-block-unrealized-pnl', 'holdings-block-realized-pnl'],
    ['holdings-block-buyabs', 'holdings-block-buypercent', 'holdings-block-buydetail'],
    ['holdings-block-buys', 'holdings-block-refprices', 'holdings-block-allocation'],
    [],
    [],
    []
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
        const blocks = Array.from(row.querySelectorAll('.holdings-draggable'));
        const count  = blocks.length;
        // Steht eine "max. 1 Slot"-Kachel (Kauf-Details, Referenzkurse) in dieser
        // Reihe, MUSS die Reihe immer echte 3 gleich breite Spalten haben, damit
        // diese Kachel sauber genau 1 von 3 Spalten ausfüllt — statt (bei
        // dynamischem --cols) eine viel zu große Zelle nur teilweise zu füllen.
        // Andere Kacheln bleiben dynamisch (1/2/3 Spalten je nach Anzahl) und
        // dürfen allein eine Reihe komplett ausfüllen.
        const cappedBlocks = blocks.filter(b => b.classList.contains('holdings-block-capped'));
        const hasCapped    = cappedBlocks.length > 0;

        if (hasCapped) {
            row.style.setProperty('--cols', HOLDINGS_MAX_COLS);
            const freeBlocks = blocks.filter(b => !b.classList.contains('holdings-block-capped'));
            cappedBlocks.forEach(b => b.style.setProperty('--span', 1));

            // Die restlichen (nicht-gecappten) Blöcke teilen sich die übrigen
            // Spalten gleichmäßig auf — z.B. 1 gecappte + 1 freie Kachel in
            // einer 3er-Reihe → die freie Kachel bekommt --span:2, statt (ohne
            // explizites Spanning) selbst nur 1 Spalte einzunehmen.
            const remaining = Math.max(HOLDINGS_MAX_COLS - cappedBlocks.length, 0);
            if (freeBlocks.length > 0) {
                const base = Math.floor(remaining / freeBlocks.length);
                let extra  = remaining - base * freeBlocks.length; // Rest den ersten Blöcken zuteilen
                freeBlocks.forEach(b => {
                    const span = Math.max(base + (extra > 0 ? 1 : 0), 1);
                    if (extra > 0) extra--;
                    b.style.setProperty('--span', span);
                });
            }
        } else {
            row.style.setProperty('--cols', Math.max(count, 1));
            blocks.forEach(b => b.style.setProperty('--span', 1));
        }
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
/**
 * Bewegt eine Kachel einen Schritt per Pfeil-Button. Innerhalb der eigenen Row
 * wird einfach mit dem Nachbarn getauscht. An der Row-Grenze WANDERT die Kachel
 * in die Nachbar-Row (Ziel wächst, Quelle schrumpft), sofern dort noch Platz ist
 * (< HOLDINGS_MAX_COLS) — direkt an der überschrittenen Grenze eingefügt (runter
 * → wird erste Kachel der nächsten Row, hoch → wird letzte Kachel der vorherigen
 * Row). Ist die Nachbar-Row bereits voll, wird stattdessen mit deren Rand-Kachel
 * getauscht (Row-Größen bleiben dann unverändert) — sonst würde die Kachel gegen
 * die 3-Slot-Grenze "anstoßen" und der Pfeil täte nichts.
 * (Vorher: rein Flat-Index-basierter Tausch — hatte keinen Swap-Partner für leere
 * oder nicht volle Nachbar-Rows, Pfeil war dann wirkungslos. Eigenständige Kopie,
 * siehe identischer Fix in depot.js' moveOverviewBlock/yearly.js' moveYearlyBlock.)
 */
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

        if (layout[targetRowIdx].length < HOLDINGS_MAX_COLS) {
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
