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

// Per-buy G/V-Balken: 3 Helligkeitsstufen je nach FIFO-Realisiert-Status
// (voll gehalten = kräftig, teilweise realisiert = mittel, komplett realisiert
// = gedämpft), damit man den Status auch ohne Klick auf einen Balken erahnen kann.
const HOLDINGS_POS_SHADES = { held: '#1d9e75', partial: '#5fbf9e', realized: '#6f8f83' };
const HOLDINGS_NEG_SHADES = { held: '#d85a30', partial: '#e08f6c', realized: '#8f7367' };

// Symmetrische Log-Skala fürs Gewinn/Verlust-je-Kauf-Chart: innerhalb ±100%
// bleibt linear, darüber wird moderat gestaucht (k=100), damit ein 2500%-Kauf
// nicht mehr alle anderen Balken winzig aussehen lässt. Nur zur Darstellung —
// Tooltip/Achsen-Beschriftung zeigen weiterhin den echten Prozentwert.
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

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

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
        initHoldingsBuyPercent(currency);

    } catch (err) {
        loadingEl.classList.add('d-none');
        emptyEl.classList.remove('d-none');
        emptyEl.textContent = 'Error: ' + err.message;
        console.error('Holdings load failed', err);
    }
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

// ── Gewinn/Verlust je Kauf (Row 2, feste Reihe) ────────────
// Ein Balken pro BUY-Transaktion (chronologisch), Prozentwert exakt wie die
// bestehende G/V-Spalte in der Haupttabelle (depot.js renderTxTable). Zusätzlich
// eine rein visuelle FIFO-Realisiert-Markierung PORTFOLIO-WEIT (positionsübergreifend):
// jeder SELL verbraucht schlicht die ältesten noch offenen BUY-Mengen im gesamten
// Portfolio, unabhängig von Position/Exchange/Wallet — Transfers/Deposits/Withdraws
// ändern an den G/V-Zahlen nichts und werden hier ignoriert.
let _holdingsBuyPercentChart = null;
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
    const seriesName   = (typeof t === 'function') ? t('holdings.chart.buyPercent') : 'Gewinn/Verlust je Kauf';

    // Jahreszahl nur am jeweils ersten Kauf eines Jahres, sonst leer — grobe
    // Zeitachse ohne dass sich hunderte Labels überlagern.
    const categories = buys.map((tx, i) => {
        const year = tx.date ? String(tx.date).substring(0, 4) : '';
        const prevYear = i > 0 && buys[i - 1].date ? String(buys[i - 1].date).substring(0, 4) : null;
        return (i === 0 || year !== prevYear) ? year : '';
    });

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

    const colorForIndex = (dataPointIndex) => {
        const tx = buys[dataPointIndex];
        if (!tx) return HOLDINGS_POS_COLOR;
        const meta  = _holdingsBuyMeta.get(String(tx.id));
        const state = meta ? meta.state : 'held';
        const value = percents[dataPointIndex];
        return (value >= 0 ? HOLDINGS_POS_SHADES : HOLDINGS_NEG_SHADES)[state] || (value >= 0 ? HOLDINGS_POS_COLOR : HOLDINGS_NEG_COLOR);
    };

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
        colors: [({ dataPointIndex }) => colorForIndex(dataPointIndex)],
        plotOptions: { bar: { columnWidth: '70%' } },
        grid: { ...HOLDINGS_APEX_DEFAULTS.grid, yaxis: { lines: { show: false } } },
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
            x: {
                formatter: (_, opts) => {
                    const tx = buys[opts.dataPointIndex];
                    if (!tx) return '';
                    const date = tx.date ? String(tx.date).substring(0, 10) : '';
                    return `${date} — ${tx.positionLabel || ''}`;
                }
            },
            y: { formatter: (_, opts) => Number(percents[opts.dataPointIndex]).toFixed(2) + '%' }
        }
    };

    _holdingsBuyPercentChart = new ApexCharts(document.getElementById('holdingsBuyPercentChart'), options);
    _holdingsBuyPercentChart.render().then(() => _holdingsHighlightBar(_holdingsSelectedIndex));
}

/** Hebt genau den Balken mit dem übergebenen Datenindex optisch hervor (Border),
 *  ohne den Chart neu zu rendern — direkte SVG-Klassenmanipulation, da
 *  ApexCharts-Annotationen auf einer Kategorie-Achse mit mehrheitlich leeren
 *  (doppelten) Labels keine eindeutige Balken-Zuordnung mehr erlauben. */
function _holdingsHighlightBar(index) {
    const container = document.getElementById('holdingsBuyPercentChart');
    if (!container) return;
    container.querySelectorAll('.apexcharts-bar-area').forEach(el => el.classList.remove('holdings-bar-selected'));
    if (index == null || index < 0) return;
    const bars = container.querySelectorAll('.apexcharts-bar-area');
    if (bars[index]) bars[index].classList.add('holdings-bar-selected');
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

const HOLDINGS_LAYOUT_KEY = 'holdings-layout-v1';
const HOLDINGS_MAX_COLS   = 3;
const HOLDINGS_DEFAULT_LAYOUT = [
    ['holdings-block-buys', 'holdings-block-pnl'],
    ['holdings-block-balance', 'holdings-block-refprices'],
    [], [], []
];

// Reihenfolge der 5 Reihen selbst (unabhängig vom Block-Layout innerhalb einer
// Reihe). Nötig, weil die feste Gewinn/Verlust-je-Kauf-Reihe als GANZES mit
// jeder anderen Reihe die Position tauschen können soll (siehe wireHoldingsRowDragAndDrop),
// ohne dass ihre 2 Kacheln einzeln in das normale Block-Drag-System einsteigen.
const HOLDINGS_ROW_ORDER_KEY = 'holdings-row-order-v1';
const HOLDINGS_DEFAULT_ROW_ORDER = [
    'holdings-row-0', 'holdings-row-1', 'holdings-row-buypercent', 'holdings-row-3', 'holdings-row-4'
];

let _holdingsDragEl    = null;
let _holdingsDragRowEl = null;

function initHoldingsLayout() {
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;

    // Reihenfolge der Reihen zuerst wiederherstellen, DANACH das Block-Layout
    // innerhalb der Reihen — sonst würde applyHoldingsLayout die falschen
    // (noch nicht vertauschten) Reihen-Indizes befüllen.
    applyHoldingsRowOrder(grid, _loadHoldingsRowOrder());
    applyHoldingsLayout(grid, _loadHoldingsLayout());
    wireHoldingsDragAndDrop(grid);
    wireHoldingsRowDragAndDrop(grid);
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
        // Feste Reihe hat ihr eigenes fest verdrahtetes 3-Spalten-Raster (CSS
        // .holdings-fixed-row) und wird hier bewusst nicht angetastet.
        if (row.classList.contains('holdings-fixed-row')) return;
        const count = row.querySelectorAll('.holdings-draggable').length;
        row.style.setProperty('--cols', Math.max(count, 1));
        row.classList.toggle('empty', count === 0);
    });
}

function resetHoldingsLayout() {
    localStorage.removeItem(HOLDINGS_LAYOUT_KEY);
    localStorage.removeItem(HOLDINGS_ROW_ORDER_KEY);
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;
    applyHoldingsRowOrder(grid, HOLDINGS_DEFAULT_ROW_ORDER);
    applyHoldingsLayout(grid, HOLDINGS_DEFAULT_LAYOUT);
    updateHoldingsRowCols(grid);
}

// ── Reihen-Reihenfolge (die feste Gewinn/Verlust-je-Kauf-Reihe als Ganzes) ──

function _loadHoldingsRowOrder() {
    try {
        const saved = JSON.parse(localStorage.getItem(HOLDINGS_ROW_ORDER_KEY));
        if (Array.isArray(saved) && saved.length === HOLDINGS_DEFAULT_ROW_ORDER.length
            && HOLDINGS_DEFAULT_ROW_ORDER.every(id => saved.includes(id))) {
            return saved;
        }
    } catch (e) { /* ignore malformed storage */ }
    return HOLDINGS_DEFAULT_ROW_ORDER;
}

function _saveHoldingsRowOrder(grid) {
    const order = Array.from(grid.querySelectorAll('.holdings-grid-row')).map(r => r.id);
    localStorage.setItem(HOLDINGS_ROW_ORDER_KEY, JSON.stringify(order));
}

/** Ordnet die 5 Reihen-Container gemäß der übergebenen id-Reihenfolge neu an. */
function applyHoldingsRowOrder(grid, order) {
    order.forEach(id => {
        const el = document.getElementById(id);
        if (el) grid.appendChild(el); // wiederholtes appendChild = ans Ende schieben → finale Reihenfolge
    });
}

/** Tauscht zwei Geschwister-Reihen-Container (unabhängig von ihrem Abstand). */
function _swapHoldingsRows(grid, rowA, rowB) {
    if (rowA === rowB) return;
    const placeholder = document.createComment('holdings-row-swap');
    grid.insertBefore(placeholder, rowA);
    grid.insertBefore(rowA, rowB);
    grid.insertBefore(rowB, placeholder);
    grid.removeChild(placeholder);
}

function wireHoldingsRowDragAndDrop(grid) {
    grid.querySelectorAll('.holdings-fixed-row').forEach(row => {
        row.querySelectorAll('.holdings-row-drag-handle').forEach(handle => {
            handle.addEventListener('mousedown', () => row.setAttribute('draggable', 'true'));
        });

        row.addEventListener('dragstart', (e) => {
            _holdingsDragRowEl = row;
            row.classList.add('dragging');
            e.dataTransfer.effectAllowed = 'move';
        });

        row.addEventListener('dragend', () => {
            row.removeAttribute('draggable');
            row.classList.remove('dragging');
            _holdingsDragRowEl = null;
            grid.querySelectorAll('.holdings-grid-row.row-drag-over').forEach(r => r.classList.remove('row-drag-over'));
        });
    });

    grid.querySelectorAll('.holdings-grid-row').forEach(row => {
        row.addEventListener('dragover', (e) => {
            if (!_holdingsDragRowEl || _holdingsDragRowEl === row) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = 'move';
            row.classList.add('row-drag-over');
        });

        row.addEventListener('dragleave', (e) => {
            if (e.target === row) row.classList.remove('row-drag-over');
        });

        row.addEventListener('drop', (e) => {
            if (!_holdingsDragRowEl || _holdingsDragRowEl === row) return;
            e.preventDefault();
            row.classList.remove('row-drag-over');
            _swapHoldingsRows(grid, _holdingsDragRowEl, row);
            updateHoldingsRowCols(grid);
            _saveHoldingsRowOrder(grid);
        });
    });

    document.addEventListener('mouseup', () => {
        grid.querySelectorAll('.holdings-fixed-row[draggable="true"]').forEach(row => {
            if (!row.classList.contains('dragging')) row.removeAttribute('draggable');
        });
    });
}

/** Vertauscht die ganze Reihe (per Button) mit der vorherigen/nächsten Reihe. */
function moveHoldingsRowByButton(rowId, direction) {
    const grid = document.getElementById('holdingsGrid');
    if (!grid) return;
    const row = document.getElementById(rowId);
    if (!row) return;

    const rows    = Array.from(grid.querySelectorAll('.holdings-grid-row'));
    const idx     = rows.indexOf(row);
    const swapIdx = idx + direction;
    if (idx === -1 || swapIdx < 0 || swapIdx >= rows.length) return;

    _swapHoldingsRows(grid, row, rows[swapIdx]);
    updateHoldingsRowCols(grid);
    _saveHoldingsRowOrder(grid);
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
            // Die feste Gewinn/Verlust-je-Kauf-Reihe nimmt keine der frei
            // verschiebbaren Blöcke auf — sie hat ihr eigenes, fest verdrahtetes
            // 2:1-Spaltenraster für genau ihre 2 zusammengehörigen Kacheln.
            if (row.classList.contains('holdings-fixed-row')) {
                e.preventDefault();
                e.dataTransfer.dropEffect = 'none';
                return;
            }
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
