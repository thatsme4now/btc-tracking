'use strict';

// ── Jahresansicht ──────────────────────────────────────────
// Eigene, unabhängige Seite: eigener Fetch der Rohdaten (/api/btc-tracking/
// transactions, wie auch von holdings.js genutzt) und eine EIGENE FIFO-
// Berechnung für die Verkauf-Kachel-Aufschlüsselung (bewusst nicht mit
// holdings.js geteilt — siehe Entscheidung zur unabhängigen Implementierung).

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
const YEARLY_PRICE_COLOR   = '#7c5cff'; // violet — bewusst anders als Bestand/Wert, eigener Chart
const YEARLY_TAX_FREE_DAYS = 365;       // DE Spekulationsfrist — rein informativ, keine Steuerberatung

const YEARLY_MONTH_SHORT_DE = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'];

let _yearlyChart          = null;
let _yearlyPriceChart     = null; // eigenständiger Kurs-Chart, nur Gesamtansicht
let _yearlySelectedYear   = null; // null = Gesamtansicht
let _yearlyAllTx          = null; // lazy-loaded, cached across year switches
let _yearlyCurrentPrice   = 0;
let _yearlyAvailableYears = [];
let _yearlyOverviewSeq    = 0; // Request-Generation-Zähler — verhindert, dass eine spät
                                // eintreffende Antwort eines vorherigen (z.B. beim schnellen
                                // Umschalten noch offenen) Fetches die aktuell gewählte
                                // Ansicht mit veralteten Daten/x-Achsen-Kategorien überschreibt.

// ── Persistenz von Jahresauswahl + Kachel-Toggles über F5/Neuladen hinweg ──
// Eigene, unabhängige localStorage-Keys (analog zu YEARLY_LAYOUT_KEY für die
// Drag&Drop-Position) — bewusst 3 getrennte Keys statt einem gemeinsamen
// Objekt, damit ein künftiger Reset/eine künftige Änderung eines einzelnen
// Werts die anderen nicht mit anfasst.
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

    // Gespeicherte Kachel-Toggle-Zustände (Käufe/Verkäufe-Filter, Transfers-Filter)
    // VOR dem ersten yearlyLoadOverview() wiederherstellen, damit der erste Render
    // bereits die richtigen Werte nutzt statt kurz mit den Defaults aufzublitzen.
    _yearlyRestoreTilesFilter();
    _yearlyRestoreTransfersFilter();

    const savedYear = _yearlyLoadSelectedYear();
    _yearlySelectedYear = savedYear;
    await yearlyLoadOverview(savedYear);
}

// Hook, den tx-form.js (saveOrAddTx) nach erfolgreichem Speichern/Bearbeiten
// einer Transaktion aus einer Kachel heraus aufruft — lädt Chart + Kacheln
// mit frischen Daten neu (eine geänderte Transaktion kann Bestand, Wert und
// die FIFO-Zusammensetzung mehrerer Kacheln gleichzeitig betreffen).
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

        // Inzwischen wurde die Auswahl gewechselt (z.B. schnell Jahr → Gesamtansicht) und ein
        // neuerer Request läuft bereits — diese veraltete Antwort darf die Ansicht (Chart-
        // Kategorien, Kacheln) nicht mehr überschreiben.
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

        // Käufe & Verkäufe in beiden Fällen — für ein gewähltes Jahr auf dieses Jahr
        // gefiltert, in der Gesamtansicht alle Transaktionen (siehe yearlyRenderTiles).
        tilesSection.classList.remove('d-none');
        await yearlyRenderTiles(year, currency, seq);
        // Transfers ebenfalls in beiden Fällen sichtbar, gleiches Filter-Muster wie
        // Käufe & Verkäufe — nutzt die von yearlyRenderTiles bereits geladene/
        // gecachte _yearlyAllTx (kein zweiter Fetch nötig).
        transfersSection.classList.remove('d-none');
        yearlyRenderTransfers(year, seq);
        // Kurs-Chart ebenfalls in beiden Fällen — für ein gewähltes Jahr auf dieses Jahr
        // gefiltert, in der Gesamtansicht die komplette Historie (siehe yearlyLoadPriceChart).
        await yearlyLoadPriceChart(currency, seq, year);

        if (seq !== _yearlyOverviewSeq) return; // erneut prüfen — Tiles/Preis-Chart liefen async

        // Sichtbarkeit von Kacheln kann sich gerade geändert haben (Kurs-Chart/Käufe &
        // Verkäufe werden je nach Ansicht ein-/ausgeblendet) — Spaltenaufteilung der
        // betroffenen Grid-Reihen neu berechnen (nur sichtbare Kacheln zählen).
        const grid = document.getElementById('yearlyGrid');
        if (grid) updateYearlyRowCols(grid);
        _yearlyTriggerChartResize();
    } catch (err) {
        if (seq !== _yearlyOverviewSeq) return; // veralteter Fehler einer überholten Anfrage
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

    // Immer exakt den tatsächlich geladenen Wert widerspiegeln (null → Gesamtansicht-
    // Option) statt eines alten DOM-Werts — sonst bleibt das Dropdown z.B. nach
    // resetYearlyLayout() optisch auf dem vorherigen Jahr stehen, obwohl bereits die
    // Gesamtansicht geladen wurde.
    select.value = selectedYear != null ? String(selectedYear) : '';
}

// ── Linien-Chart: Bestand (BTC, orange) + Wertentwicklung (Währung, grün) ──

function renderYearlyChart(series, currency, singleYear) {
    const categories = series.map((pt, i) => {
        if (singleYear) return YEARLY_MONTH_SHORT_DE[pt.month - 1];
        // Gesamtansicht: Jahreszahl nur am Januar (bzw. am ersten Punkt), sonst
        // leer — grobe Zeitachse ohne dass sich viele Jahre gegenseitig überlagern.
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
        chart: { ...YEARLY_APEX_DEFAULTS.chart, type: 'line', height: 360 },
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
                labels: { style: { colors: YEARLY_BALANCE_COLOR }, formatter: v => Number(v).toFixed(4) }
            },
            {
                seriesName: (typeof t === 'function') ? t('yearly.chart.value') : 'Wertentwicklung',
                opposite: true,
                labels: { style: { colors: YEARLY_VALUE_COLOR }, formatter: v => fmt(v, currency) }
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
        // Bestehende Instanz per updateOptions() wiederverwenden statt destroy()+neu
        // erstellen — destroy()+recreate zeigte beim schnellen Jahr/Gesamtansicht-Wechsel
        // hartnäckig veraltete x-Achsen-Beschriftungen (vermutlich ein hängender interner
        // ApexCharts-Listener/Cache der alten Instanz). updateOptions(options, redrawPaths=true,
        // animate=true) ersetzt series UND xaxis.categories vollständig auf derselben Instanz.
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

// ── Reiner Bitcoin-Kurs-Chart (Gesamtansicht: komplette Historie; ein
// gewähltes Jahr: nur dessen 12 Monate) ────────────────────
// Eigener, unabhängiger Fetch + eigene Render-Funktion — zeigt die komplette
// in der monthly_price Tabelle verfügbare Kurshistorie, unabhängig vom
// transaktions-beschränkten Zeitraum des Bestand/Wert-Charts oben. Für ein
// gewähltes Jahr wird dieselbe vollständige Historie nur client-seitig auf
// das Jahr gefiltert — kein zusätzlicher Backend-Endpoint nötig.

async function yearlyLoadPriceChart(currency, seq, year) {
    const section  = document.getElementById('yearly-block-pricechart');
    const titleEl  = document.getElementById('yearlyPriceChartTitle');
    try {
        const res = await fetch(`/api/btc-tracking/monthly-prices/history?currency=${encodeURIComponent(currency)}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const rows = await res.json();
        if (seq !== _yearlyOverviewSeq) return; // Auswahl hat sich zwischenzeitlich geändert

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
        chart: { ...YEARLY_APEX_DEFAULTS.chart, type: 'line', height: 300 },
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
            labels: { style: { colors: YEARLY_PRICE_COLOR }, formatter: v => fmt(v, currency) }
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

// ── Kauf/Verkauf-Kacheln für das gewählte Jahr ─────────────

// Kauf/Verkauf-Sichtbarkeitsfilter der Kachel (2 unabhängige Toggles) — rein
// clientseitig, kein Refetch nötig. Standard: beide an (alles sichtbar).
let _yearlyTilesFilter  = { buy: true, sell: true };
let _yearlyTilesYearTx  = [];      // Jahr-/Gesamtansicht-gefilterte Tx (vor dem Kauf/Verkauf-Toggle)
let _yearlyTilesBuyMeta  = new Map();
let _yearlyTilesSellMeta = new Map();
let _yearlyTilesCurrency = 'EUR';
let _yearlyTilesYearLabel = null; // für die leere-Liste-Meldung (Jahr vs. Gesamtansicht)

async function yearlyRenderTiles(year, currency, seq) {
    if (_yearlyAllTx == null) {
        const [txs, priceRes] = await Promise.all([
            fetch('/api/btc-tracking/transactions').then(r => r.json()),
            fetch(`/api/btc-tracking/current-price?currency=${encodeURIComponent(currency)}`).then(r => r.json())
        ]);
        if (seq !== undefined && seq !== _yearlyOverviewSeq) return; // Auswahl inzwischen gewechselt
        _yearlyAllTx        = txs;
        _yearlyCurrentPrice = Number(priceRes.price || 0);
    }

    const { buyMeta, sellMeta } = _yearlyComputeFifo(_yearlyAllTx);

    // Gesamtansicht (year == null) zeigt alle Käufe/Verkäufe über alle Jahre hinweg,
    // ein gewähltes Jahr filtert wie bisher nur auf dessen Transaktionen.
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

/** Wendet den Kauf/Verkauf-Toggle-Filter auf die zwischengespeicherten,
 *  bereits Jahr-gefilterten Transaktionen an und rendert neu — kein Refetch,
 *  keine erneute FIFO-Berechnung nötig (siehe yearlyRenderTiles). */
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

/** Klick-Handler der beiden unabhängigen Toggle-Buttons ("Käufe"/"Verkäufe"). */
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

/** Liest den gespeicherten Käufe/Verkäufe-Filter (falls vorhanden) und
 *  synchronisiert direkt die Button-Darstellung im (bereits im HTML
 *  vorhandenen) DOM — der eigentliche Render erfolgt erst später über
 *  yearlyRenderTiles/_yearlyRenderTilesGrid. */
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

/**
 * Eigenständige, globale (portfolio-weite) FIFO-Berechnung — bewusst NICHT
 * mit holdings.js geteilt. Läuft einmal über alle BUY/SELL chronologisch und
 * liefert in einem Durchgang sowohl die Kauf-Sicht (welche Verkäufe haben
 * diesen Kauf ganz/teilweise verbraucht) als auch die Verkauf-Sicht (aus
 * welchen Käufen setzt sich dieser Verkauf zusammen, inkl. Haltedauer).
 */
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

/** Wandelt tx.quantityFiat (bereits ein Gesamtbetrag) in die Anzeigewährung um — dieselbe
 *  Konvention wie überall sonst in der App (kein zweites Mal mit pricePerBtc multiplizieren). */
function _yearlyFiatInDisplayCurrency(tx, displayCurrency) {
    if (tx.quantityFiat == null) return null;
    if (tx.currency === displayCurrency) return Number(tx.quantityFiat);
    return Number(tx.quantityFiat) * Number(tx.exchangeRate || 1);
}

/** Bezahlter Gesamtbetrag inkl. Gebühren für einen BUY, in der Anzeigewährung. */
function _yearlyBuyPaid(tx, displayCurrency) {
    if (tx.pricePerBtc == null) return null;
    const fees = tx.fees != null ? Number(tx.fees) : 0;
    const cost = Number(tx.quantity) * Number(tx.pricePerBtc) + fees;
    if (tx.currency === displayCurrency) return cost;
    return cost * Number(tx.exchangeRate || 1);
}

/** G/V %+Betrag für eine BUY-Kachel: gehaltene Käufe gegen den aktuellen Kurs,
 *  teilweise/komplett realisierte Käufe blended aus tatsächlichem Verkaufserlös
 *  (für den verbrauchten Teil) + aktuellem Kurs (für einen evtl. noch gehaltenen Rest). */
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
        ? _yearlyFieldRow((typeof t === 'function') ? t('modal.field.comment') : 'Kommentar', esc(tx.comment), tx.comment)
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
        ? _yearlyFieldRow((typeof t === 'function') ? t('modal.field.comment') : 'Kommentar', esc(tx.comment), tx.comment)
        : '';

    // Realisierter G/V dieses Verkaufs: Erlös minus gewichteter Kostenbasis der
    // tatsächlich verbrauchten FIFO-Lots (nicht der App-weite gewichtete
    // Durchschnitt — hier bewusst lot-genau, konsistent zur FIFO-Aufschlüsselung
    // direkt darunter).
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

    // Gewinn/Verlust je einzelnem Lot: Erlösanteil (proportional zur verbrauchten
    // Menge an der Gesamtmenge dieses Verkaufs) minus Kostenbasis dieses Lots —
    // dieselbe Grundlage wie beim aggregierten G/V oben (proceeds/costOfSold),
    // hier nur pro Zeile statt aufsummiert.
    const sellQty = Number(tx.quantity) || 1;
    const lotsHtml = consumed.map(c => {
        const taxFree = c.days >= YEARLY_TAX_FREE_DAYS;
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

// ── Transfers-Kachel (TRANSFER_IN/TRANSFER_OUT) ────────────
// Eigenständig, analog zur Käufe/Verkäufe-Kachel: gleiches Jahr-/Gesamtansicht-
// Filtermuster, gleicher Kachel-Grid-Container. Nutzt die von yearlyRenderTiles
// bereits geladene _yearlyAllTx (kein eigener Fetch). Transfers haben keinen
// Fiat-Wert/G-V — stattdessen wird die Gegenbuchung (falls vorhanden) über die
// transferId in der bereits geladenen Gesamtliste gesucht (eigenständige,
// einfache Suche — NICHT die flow.js-Graph-Paarung, die auf dem Backend-
// Flow-Endpoint basiert und hier nicht verfügbar ist).
let _yearlyTransfersTx        = [];
let _yearlyTransfersYearLabel = null;

// Alle/Paare/Solo-Filter der Transfers-Kachel — im Gegensatz zum unabhängigen
// Käufe/Verkäufe-Toggle hier bewusst EIN einzelner, sich gegenseitig
// ausschließender Modus (Radio-artig), da "Paare" und "Solo" sich per
// Definition ausschließen und "Alle" beide vereint — ein unabhängiges
// Doppel-Toggle wie bei Käufe/Verkäufe wäre hier nur redundant.
let _yearlyTransfersFilterMode = 'solo'; // 'all' | 'paired' | 'solo' — Default: nur Solo-Transfers (siehe Reset/HTML-Default)

function yearlyRenderTransfers(year, seq) {
    if (seq !== undefined && seq !== _yearlyOverviewSeq) return; // Auswahl inzwischen gewechselt
    if (_yearlyAllTx == null) return; // wird von yearlyRenderTiles im selben Zyklus geladen

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

/** Klick-Handler der 3 sich gegenseitig ausschließenden Filter-Buttons
 *  ("Alle"/"Nur Paare"/"Nur Solo") — kein Refetch/keine erneute Paarungssuche
 *  nötig, nur ein Re-Render (siehe _yearlyRenderTransfersGrid). */
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

/** Analog zu _yearlyRestoreTilesFilter — liest den gespeicherten Alle/Paare/
 *  Solo-Modus und synchronisiert direkt die Button-Darstellung. */
function _yearlyRestoreTransfersFilter() {
    try {
        const saved = localStorage.getItem(YEARLY_TRANSFERS_FILTER_KEY);
        if (saved === 'all' || saved === 'paired' || saved === 'solo') _yearlyTransfersFilterMode = saved;
    } catch (e) { /* ignore malformed storage */ }
    document.querySelectorAll('.yearly-transfers-filter-btn').forEach(btn => {
        btn.classList.toggle('is-active', btn.dataset.mode === _yearlyTransfersFilterMode);
    });
}

/** Sucht die Gegenbuchung (TRANSFER_IN ↔ TRANSFER_OUT mit gleicher transferId)
 *  in der bereits geladenen Gesamtliste. Liefert null, wenn keine gefunden
 *  wird — dann handelt es sich um einen Solo-Transfer (siehe depot.js). */
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
        ? _yearlyFieldRow((typeof t === 'function') ? t('modal.field.comment') : 'Kommentar', esc(tx.comment), tx.comment)
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

function _yearlyFormatFiat(val, code) {
    if (val == null) return '–';
    const num = Number(val).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return code ? `${num} ${code}` : num;
}

function _yearlyFmt8(val) {
    if (val == null) return '–';
    return Number(val).toLocaleString('de-DE', { minimumFractionDigits: 8, maximumFractionDigits: 8 });
}

function _yearlyFieldRow(label, value, title) {
    return `<div class="flow-tx-field">
        <span class="flow-tx-field-label">${esc(label)}</span>
        <span class="flow-tx-field-value"${title ? ` title="${esc(title)}"` : ''}>${value}</span>
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

// ── "Preise laden" (CoinGecko-Bulk-Backfill, alle 3 Währungen) ────────────

function yearlyLoadPrices() {
    if (!OFFLINE.isOnline()) {
        const modal = bootstrap.Modal.getInstance(document.getElementById('yearlyOfflineConfirmModal'))
            || new bootstrap.Modal(document.getElementById('yearlyOfflineConfirmModal'));
        modal.show();
        return;
    }
    _yearlyDoLoadPrices();
}

function yearlyConfirmOfflineLoadPrices() {
    bootstrap.Modal.getInstance(document.getElementById('yearlyOfflineConfirmModal'))?.hide();
    _yearlyDoLoadPrices();
}

function _yearlyDoLoadPrices() {
    const btn = document.getElementById('yearlyLoadPricesBtn');
    const originalHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = `<span class="depot-spinner"></span>${(typeof t === 'function') ? t('toast.refreshLoading') : 'Lade…'}`;

    fetch('/api/btc-tracking/monthly-prices/backfill', { method: 'POST' })
        .then(r => r.json())
        .then(data => {
            if (data.error) { showToast('✗ ' + data.error, 'error'); return; }
            const n = data.totalNew || 0;
            showToast('✓ ' + n + ' ' + ((typeof t === 'function') ? t('yearly.toast.pricesLoaded') : 'neue Monatspreise geladen'), 'success');
            _yearlyAllTx = null; // Cache invalidieren, falls FIFO-relevante Werte sich indirekt ändern
            yearlyLoadOverview(_yearlySelectedYear);
        })
        .catch(err => showToast('✗ ' + err.message, 'error'))
        .finally(() => {
            btn.disabled = false;
            btn.innerHTML = originalHtml;
        });
}

// ── Manueller Monatskurs-Dialog: nach Jahr gruppiert, einklappbar, neuestes Jahr oben ──

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
            const expanded   = idx < 2; // aktuelles + letztes Jahr standardmäßig offen

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

// ── Draggable grid layout (desktop) ───────────────────────
// Eigenständige, unabhängige Implementierung — bewusst NICHT mit
// holdings.js geteilt (gleiche Grundidee, aber andere Regeln): hier gibt es
// 3 Reihen mit je maximal 2 Slots statt 3, es gibt kein "auf 1 Slot
// gecapptes" Kachel-Konzept, und Kacheln können je nach Ansicht (Jahr/
// Gesamtansicht) ein-/ausgeblendet sein — updateYearlyRowCols zählt daher
// bewusst nur SICHTBARE Kacheln pro Reihe, damit eine einzelne sichtbare
// Kachel in einer Reihe stets die volle Breite einnimmt, auch wenn eine
// zweite (aktuell ausgeblendete) Kachel dort ebenfalls "wohnt".

const YEARLY_LAYOUT_KEY = 'yearly-layout-v1';
const YEARLY_MAX_COLS   = 2;
// 3 Reihen. Reihe 1: Bestand/Wert-Chart + Kurs-Chart. Reihe 2: Käufe & Verkäufe
// + Transfers (teilen sich die Reihe). Reihe 3: leer, für künftige Kacheln
// reserviert.
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

/** Zählt pro Reihe nur SICHTBARE Kacheln (kein .d-none) — eine Kachel kann je
 *  nach Ansicht ausgeblendet sein (Kurs-Chart/Käufe & Verkäufe), soll dann
 *  aber nicht dazu führen, dass die andere, sichtbare Kachel in derselben
 *  Reihe fälschlich nur eine halbe statt die volle Breite bekommt. */
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

/**
 * Nach updateYearlyRowCols() geänderte --cols/--span-Werte ändern die tatsächliche
 * Container-Breite eines Charts per CSS — ApexCharts misst seine SVG-Breite aber
 * nur beim (Neu-)Rendern bzw. bei einem window "resize"-Event, nicht bei reinen
 * CSS-Grid-Änderungen. Ohne diesen Trigger blieb z.B. beim ersten Laden/F5 in der
 * Gesamtansicht der Kurs-Chart (der erst später sichtbar wird, sobald seine Zeile
 * von 1 auf 2 sichtbare Spalten wechselt) auf der zu diesem früheren Zeitpunkt
 * falschen (zu breiten) Größe stehen und überlappte visuell die Nachbar-Kachel.
 * Ein synthetisches resize-Event lässt beide Charts ihre tatsächliche, aktuelle
 * Containerbreite neu einlesen und sich korrekt neu zeichnen.
 */
function _yearlyTriggerChartResize() {
    window.dispatchEvent(new Event('resize'));
}

/**
 * "Layout zurücksetzen" versteht der Nutzer als vollständigen Reset der
 * gesamten Jahresansicht-Konfiguration, nicht nur der Kachel-Positionen —
 * setzt daher zusätzlich Käufe/Verkäufe-Filter, Transfers-Filter und die
 * gespeicherte Jahresauswahl (→ Gesamtansicht) zurück, inkl. der jeweiligen
 * localStorage-Keys (siehe YEARLY_TILES_FILTER_KEY/YEARLY_TRANSFERS_FILTER_KEY/
 * YEARLY_SELECTED_YEAR_KEY).
 */
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
    yearlyLoadOverview(null); // Gesamtansicht neu laden — aktualisiert auch das Jahres-Dropdown und rendert Tiles/Transfers mit den zurückgesetzten Filtern
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

/** Bewegt eine Kachel eine Position weiter (Lesereihenfolge, Reihe für Reihe,
 *  links nach rechts) — für Touch/Mobile, wo natives Drag & Drop fehlt. */
function moveYearlyBlock(id, direction) {
    const grid = document.getElementById('yearlyGrid');
    if (!grid) return;

    const rows     = Array.from(grid.querySelectorAll('.yearly-grid-row'));
    const rowSizes = rows.map(r => r.querySelectorAll('.yearly-draggable').length);
    const flat     = rows.flatMap(r => Array.from(r.querySelectorAll('.yearly-draggable')).map(el => el.id));

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

    updateYearlyRowCols(grid);
    _yearlyTriggerChartResize();
    _saveYearlyLayout(grid);
}

document.addEventListener('DOMContentLoaded', initYearlyLayout);
