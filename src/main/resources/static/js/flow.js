'use strict';

function _flowVar(name) {
    return getComputedStyle(document.body).getPropertyValue(name).trim() || '#888';
}

function _flowFmt8(val) {
    if (val == null) return '–';
    return Number(val).toLocaleString('de-DE', { minimumFractionDigits: 8, maximumFractionDigits: 8 });
}

function _flowNodeLabel(node) {
    switch (node.kind) {
        case 'POSITION':     return node.positionLabel;
        case 'BUY':          return t('flow.node.buy', { label: node.positionLabel });
        case 'SELL':         return t('flow.node.sell', { label: node.positionLabel });
        case 'EXTERNAL_IN':  return t('flow.node.externalIn', { label: node.positionLabel });
        case 'EXTERNAL_OUT': return t('flow.node.externalOut', { label: node.positionLabel });
        default:              return node.id;
    }
}

let _flowGraphCache   = null;
let _flowSelection    = null; // { type: 'node'|'link', id: string } | null — Chart-Klick, filtert auch die Liste
let _flowCardHighlight = null; // { linkId, txId } | null — Card-Klick, hebt nur im Chart hervor
let _flowTxSearchTerm = '';
let _flowHoveredLinkId = null;

// Eigenständiger, UNGEFILTERTER Fetch aller Transaktionen für die portfolio-weite
// FIFO-Berechnung (Verkauf-Card-Zusatzinfos) — bewusst unabhängig vom (evtl. per
// Datum/Position gefilterten) _flowGraphCache, da FIFO die komplette chronologische
// Historie braucht, siehe _flowLoadFifo/_flowComputeFifo.
let _flowSellMeta = new Map(); // sellId (string) -> [{ buyTx, qty, days }]

async function initFlow() {
    if (typeof initFlatpickr === 'function') initFlatpickr();
    _wireFlowTxListEvents();
    await _loadFlowPositionOptions();
    await _flowLoadFifo();
    await loadFlowGraph();
    _ensureFlowResizeObserver();
}

/** Hook called by tx-form.js (saveOrAddTx) after a transaction was saved. */
function onTxSaved() {
    _flowLoadFifo().then(loadFlowGraph);
}

/** Lädt/berechnet die FIFO-Lot-Zuordnung neu — vor dem ersten Render (initFlow)
 *  und nach jeder Transaktionsänderung (onTxSaved), damit _renderFlowTxCard
 *  synchron auf bereits aktuelle Daten zugreifen kann. */
async function _flowLoadFifo() {
    try {
        const allTx = await fetch('/api/btc-tracking/transactions').then(r => r.json());
        _flowSellMeta = _flowComputeFifo(allTx);
    } catch (err) {
        console.warn('FIFO load failed', err.message);
        _flowSellMeta = new Map();
    }
}

/**
 * Eigenständige, globale (portfolio-weite) FIFO-Berechnung — bewusst NICHT mit
 * yearly.js/holdings.js geteilt (siehe dortige Entscheidung zur unabhängigen
 * Implementierung). Läuft einmal chronologisch über alle BUY/SELL und liefert
 * pro Verkauf die verbrauchten Kauf-Lots (Menge, Kauf-Transaktion, Haltedauer).
 */
function _flowComputeFifo(allTx) {
    const list = allTx
        .filter(tx => tx.type === 'BUY' || tx.type === 'SELL')
        .sort((a, b) => (a.date || '').localeCompare(b.date || ''));

    const queue    = []; // { tx, remaining }
    const sellMeta = new Map();

    list.forEach(tx => {
        if (tx.type === 'BUY') {
            queue.push({ tx, remaining: Number(tx.quantity) || 0 });
        } else if (tx.type === 'SELL') {
            let toConsume = Number(tx.quantity) || 0;
            const consumed = [];
            while (toConsume > 1e-12 && queue.length) {
                const lot  = queue[0];
                const take = Math.min(lot.remaining, toConsume);
                if (take > 1e-12) {
                    consumed.push({ buyTx: lot.tx, qty: take, days: _flowDaysBetween(lot.tx.date, tx.date) });
                }
                lot.remaining -= take;
                toConsume -= take;
                if (lot.remaining <= 1e-12) queue.shift();
            }
            sellMeta.set(String(tx.id), consumed);
        }
    });

    return sellMeta;
}

function _flowDaysBetween(dateA, dateB) {
    const a = new Date(dateA);
    const b = new Date(dateB);
    return Math.max(0, Math.round((b - a) / 86400000));
}

const FLOW_TAX_FREE_DAYS = 365; // DE Spekulationsfrist — rein informativ, keine Steuerberatung

/** Gesamt-Kostenbasis eines Kaufs in der Anzeigewährung (inkl. Gebühren, währungskonvertiert). */
function _flowBuyPaid(tx, displayCurrency) {
    if (tx.pricePerBtc == null) return null;
    const fees = tx.fees != null ? Number(tx.fees) : 0;
    const cost = Number(tx.quantity) * Number(tx.pricePerBtc) + fees;
    if (tx.currency === displayCurrency) return cost;
    return cost * Number(tx.exchangeRate || 1);
}

/** tx.quantityFiat (bereits Gesamtbetrag) in die Anzeigewährung umgerechnet. */
function _flowFiatInDisplayCurrency(tx, displayCurrency) {
    if (tx.quantityFiat == null) return null;
    if (tx.currency === displayCurrency) return Number(tx.quantityFiat);
    return Number(tx.quantityFiat) * Number(tx.exchangeRate || 1);
}

function _flowFmt(val, currency) {
    if (typeof CURRENCY !== 'undefined') return CURRENCY.format(val, currency);
    return Number(val).toFixed(2);
}

async function _loadFlowPositionOptions() {
    const sel = document.getElementById('flowFilterPosition');
    if (!sel) return;
    try {
        const data = await fetch('/api/btc-tracking/positions').then(r => r.json());
        sel.innerHTML = `<option value="">${t('flow.filter.position.all')}</option>` +
            data.map(p => `<option value="${p.id}">${esc(p.label)}</option>`).join('');
    } catch (err) {
        console.warn('positions load failed', err.message);
    }
}

function _buildFlowQuery() {
    const from = document.getElementById('flowFilterFrom')?.value;
    const to   = document.getElementById('flowFilterTo')?.value;
    const positionId = document.getElementById('flowFilterPosition')?.value;

    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (positionId) params.set('positionId', positionId);
    return params.toString();
}

function applyFlowFilter() {
    loadFlowGraph();
}

function resetFlowFilter() {
    document.getElementById('flowFilterFrom').value = '';
    document.getElementById('flowFilterTo').value = '';
    document.getElementById('flowFilterPosition').value = '';
    loadFlowGraph();
}

async function loadFlowGraph() {
    const loading = document.getElementById('flowLoading');
    const emptyEl = document.getElementById('flowEmpty');
    const svgEl   = document.getElementById('flowSvg');

    emptyEl.classList.add('d-none');
    svgEl.style.display = 'none';
    loading.classList.remove('d-none');
    loading.textContent = t('flow.loading');

    const query = _buildFlowQuery();
    let data;
    try {
        data = await fetch('/api/btc-tracking/flow' + (query ? '?' + query : '')).then(r => r.json());
    } catch (err) {
        loading.textContent = t('toast.error') + ': ' + err.message;
        return;
    }

    loading.classList.add('d-none');
    _flowGraphCache    = data;
    _flowSelection     = null;
    _flowCardHighlight = null;
    _flowTxSearchTerm  = '';
    const searchInput = document.getElementById('flowTxSearch');
    if (searchInput) searchInput.value = '';
    document.getElementById('flowTxSearchClear')?.classList.add('d-none');

    if (!data.nodes?.length || !data.links?.length) {
        emptyEl.classList.remove('d-none');
        _renderFlowTxPanel();
        return;
    }

    renderSankey(data);
    _renderFlowTxPanel();
    _scheduleFlowSettleRerender();
}

/**
 * Sicherheitsnetz gegen einen Chrome-DevTools-Device-Toolbar-Bug: Beim (Re-)Load mit
 * aktivem Device-Emulator wird die Seite manchmal kurzzeitig noch mit der alten/Desktop-
 * Viewport-Breite gelayoutet, bevor die Media Query auf die Mobile-/Tablet-Breakpoints
 * (siehe flow.css) umschaltet. DevTools korrigiert das zwar wenig später, feuert dabei
 * aber weder ein reguläres 'resize'-Event noch ändert sich die Box-Größe von
 * #flowChartWrapper nach außen sichtbar – der ResizeObserver greift also nicht.
 * Ergebnis: Das Sankey-SVG bleibt mit der falschen (zu breiten) Größe stehen, bis man
 * manuell resized. Dieser einmalige Nachzügler-Redraw kurz nach dem Erstrender fängt
 * genau dieses Zeitfenster ab, unabhängig davon, ob ein Resize-Event ausgelöst wurde.
 */
function _scheduleFlowSettleRerender() {
    setTimeout(() => {
        if (_flowGraphCache) renderSankey(_flowGraphCache);
    }, 400);
}

window.addEventListener('resize', () => {
    if (_flowGraphCache) renderSankey(_flowGraphCache);
});

// Reagiert explizit auf den .flow-main-row-Breakpoint (siehe flow.css), unabhängig davon,
// ob dabei ein 'resize'-Event feuert (z.B. bei DevTools-Device-Toolbar-Metrikwechseln).
if (typeof window.matchMedia === 'function') {
    window.matchMedia('(max-width: 991px)').addEventListener('change', () => {
        if (_flowGraphCache) renderSankey(_flowGraphCache);
    });
}

let _flowResizeObserver = null;

/**
 * Beobachtet die tatsächliche Box-Größe des Chart-Containers (statt nur window-resize).
 * Fängt u.a. den Fall ab, dass die Seite direkt in einem schmalen Tablet-/Phone-Viewport
 * geladen wird und clientWidth beim allerersten Render noch nicht die endgültige,
 * bereits umgebrochene Flex-Layout-Breite widerspiegelt (z.B. DevTools-Device-Toolbar).
 */
function _ensureFlowResizeObserver() {
    if (_flowResizeObserver || typeof ResizeObserver === 'undefined') return;
    const wrapper = document.getElementById('flowChartWrapper');
    if (!wrapper) return;
    _flowResizeObserver = new ResizeObserver(() => {
        if (_flowGraphCache) renderSankey(_flowGraphCache);
    });
    _flowResizeObserver.observe(wrapper);
}

let _flowResizeTimeout = null;

function renderSankey(data) {
    clearTimeout(_flowResizeTimeout);
    _flowResizeTimeout = setTimeout(() => {
        // Zwei rAF-Ticks abwarten, damit das Flex-/Media-Query-Layout sicher final
        // eingerastet ist, bevor wir clientWidth/clientHeight für die SVG messen.
        requestAnimationFrame(() => requestAnimationFrame(() => _doRenderSankey(data)));
    }, 50);
}

function _doRenderSankey(data) {
    const svgEl = document.getElementById('flowSvg');
    svgEl.style.display = 'block';

    const width  = svgEl.clientWidth || 1000;
    const height = svgEl.clientHeight || 560;

    const nodeIndex = new Map(data.nodes.map((n, i) => [n.id, i]));
    const links = data.links.map(l => ({
        source: nodeIndex.get(l.source),
        target: nodeIndex.get(l.target),
        value:  Math.max(Number(l.value), 0.00000001),
        raw: l
    }));

    const sankeyGen = d3.sankey()
        .nodeId((_, i) => i)
        .nodeWidth(14)
        .nodePadding(18)
        .extent([[1, 5], [width - 1, height - 5]]);

    const graph = sankeyGen({
        nodes: data.nodes.map(d => ({ ...d })),
        links: links
    });

    const svg = d3.select(svgEl).attr('viewBox', [0, 0, width, height]);
    svg.selectAll('*').remove();

    const tooltip = document.getElementById('flowTooltip');
	
	function _linkCategory(link) {
	    const s = link.source, t = link.target;
	    if (s.kind === 'BUY') return 'buy';
	    if (t.kind === 'SELL') return 'sell';
	    if (s.kind === 'EXTERNAL_IN' || t.kind === 'EXTERNAL_OUT') return 'external';
	    if (s.kind === 'POSITION' && t.kind === 'POSITION') return 'transfer';
	    return 'external';
	}

	/** Erzeugt eine Palette heller/dunkler Varianten einer Basisfarbe, im Zickzack sortiert
	 *  (hell, dunkel, hell, dunkel, ...), damit aufeinanderfolgende Kanten maximal kontrastieren. */
	function _buildShadePalette(cssVarName, steps) {
	    const baseHex = getComputedStyle(document.body).getPropertyValue(cssVarName).trim() || '#888888';
	    const base = d3.hsl(baseHex);
	    const spread = 0.34;
	    const shades = [];
	    for (let i = 0; i < steps; i++) {
	        const frac = steps === 1 ? 0.5 : i / (steps - 1);
	        const l = Math.min(0.82, Math.max(0.18, base.l - spread / 2 + spread * frac));
	        shades.push(d3.hsl(base.h, Math.min(1, base.s * 1.05 || 0.5), l).formatHex());
	    }
	    const zigzag = [];
	    let lo = 0, hi = shades.length - 1;
	    while (lo <= hi) {
	        zigzag.push(shades[lo++]);
	        if (lo <= hi) zigzag.push(shades[hi--]);
	    }
	    return zigzag;
	}

	const shadePalettes = {
        buy:      _buildShadePalette('--pos', 6),
        sell:     _buildShadePalette('--neg', 6),
        transfer: _buildShadePalette('--accent', 6),
        external: _buildShadePalette('--text-muted', 6)
    };
    const shadeCounters = { buy: 0, sell: 0, transfer: 0, external: 0 };

    function colorForLink(link) {
        const cat = _linkCategory(link);
        const palette = shadePalettes[cat];
        const idx = shadeCounters[cat] % palette.length;
        shadeCounters[cat]++;
        return palette[idx];
    }

    svg.append('g')
        .attr('fill', 'none')
        .selectAll('path')
        .data(graph.links)
        .join('path')
        .attr('class', 'flow-link')
        .attr('d', d3.sankeyLinkHorizontal())
        .attr('stroke', colorForLink)
        .attr('stroke-width', d => Math.max(1, d.width))
        .on('mousemove', (event, d) => _showLinkTooltip(event, d, tooltip))
        .on('mouseleave', () => tooltip.style.display = 'none')
        .on('click', (event, d) => { event.stopPropagation(); onFlowLinkClick(d); });

    const node = svg.append('g')
        .selectAll('g')
        .data(graph.nodes)
        .join('g')
        .attr('class', 'flow-node')
        .on('click', (event, d) => { event.stopPropagation(); onFlowNodeClick(d); });

    node.append('rect')
        .attr('x', d => d.x0)
        .attr('y', d => d.y0)
        .attr('width', d => d.x1 - d.x0)
        .attr('height', d => Math.max(1, d.y1 - d.y0))
        .attr('fill', d => d.kind === 'POSITION' ? _flowVar('--accent') : _flowVar('--border'))
        .on('mousemove', (event, d) => _showNodeTooltip(event, d, tooltip))
        .on('mouseleave', () => tooltip.style.display = 'none');

    node.append('text')
        .attr('x', d => d.x0 < width / 2 ? d.x1 + 6 : d.x0 - 6)
        .attr('y', d => (d.y0 + d.y1) / 2)
        .attr('dy', '0.35em')
        .attr('text-anchor', d => d.x0 < width / 2 ? 'start' : 'end')
        .text(d => _flowNodeLabel(d));

    _applyFlowHighlight();
}

function _showLinkTooltip(event, d, tooltip) {
    const raw = d.raw;
    const details = raw.details.slice()
        .sort((a, b) => a.date.localeCompare(b.date))
        .map(det => {
            const isClipped = det.originalQuantity != null
                && Number(det.originalQuantity) > Number(det.quantity) + 1e-9;
            const suffix = isClipped
                ? ` <span style="color:var(--text-muted)">(${t('flow.link.originalAmount', { AMOUNT: _flowFmt8(det.originalQuantity) })})</span>`
                : '';
            return `${det.date.substring(0, 10)} — ${_flowFmt8(det.quantity)} BTC${suffix}`;
        })
        .join('<br>');
    tooltip.innerHTML = `
        <div style="font-weight:600;margin-bottom:.3rem">${raw.month}</div>
        <div>${t('flow.link.total')}: ${_flowFmt8(raw.value)} BTC</div>
        <div style="margin-top:.3rem;color:var(--text-muted)">${t('flow.link.transactions', { COUNT: raw.details.length })}</div>
        <div style="margin-top:.2rem;max-height:150px;overflow-y:auto">${details}</div>`;
    _positionTooltip(event, tooltip);
}

function _showNodeTooltip(event, d, tooltip) {
    let html = `<div style="font-weight:600">${_flowNodeLabel(d)}</div>`;
    if (d.positionType) html += `<div style="color:var(--text-muted)">${d.positionType}</div>`;
    if (d.kind === 'POSITION' && d.currentBalance != null) {
        html += `<div style="margin-top:.3rem">${t('flow.node.balance')}: ${_flowFmt8(d.currentBalance)} BTC</div>`;
    }
    tooltip.innerHTML = html;
    _positionTooltip(event, tooltip);
}

/**
 * Positioniert das Tooltip relativ zum Cursor und klappt es nach links bzw. oben um,
 * falls es sonst rechts oder unten über den sichtbaren Viewport hinausragen würde
 * (z.B. bei Nodes/Links ganz am rechten Rand des Sankey-Diagramms). Gilt für jede
 * Fenstergröße, nicht nur Tablet/Phone-Breakpoints.
 */
function _positionTooltip(event, tooltip) {
    tooltip.style.display = 'block';

    const OFFSET = 14;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const tw = tooltip.offsetWidth;
    const th = tooltip.offsetHeight;

    let left = event.clientX + OFFSET;
    if (left + tw > vw) {
        left = event.clientX - OFFSET - tw;
    }
    left = Math.max(0, Math.min(left, vw - tw));

    let top = event.clientY + OFFSET;
    if (top + th > vh) {
        top = event.clientY - OFFSET - th;
    }
    top = Math.max(0, Math.min(top, vh - th));

    tooltip.style.left = left + 'px';
    tooltip.style.top  = top + 'px';
}

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

// ── Node/Link-Klick → Auswahl & Transaktionsliste rechts ──────────────────

function onFlowNodeClick(d) {
    _toggleFlowSelection('node', d.id);
}

function onFlowLinkClick(d) {
    _toggleFlowSelection('link', d.raw.id);
}

function _toggleFlowSelection(type, id) {
    if (_flowSelection && _flowSelection.type === type && _flowSelection.id === id) {
        _flowSelection = null; // erneuter Klick auf gleiche Auswahl → zurücksetzen
    } else {
        _flowSelection = { type, id }; // Klick auf andere Node/Link → sofort umschalten
    }
    _flowCardHighlight = null; // Chart-Auswahl hat Vorrang vor einer reinen Card-Vorschau
    _flowHoveredLinkId = null;
    _applyFlowHighlight();
    _renderFlowTxPanel();
}

function clearFlowSelection() {
    _flowSelection = null;
    _flowCardHighlight = null;
    _flowHoveredLinkId = null;
    _applyFlowHighlight();
    _renderFlowTxPanel();
}

/**
 * Klick auf eine Transaktions-Card rechts hebt NUR die zugehörige Transaktion im
 * Sankey hervor – im Gegensatz zum Klick auf eine Node/Link im Diagramm selbst
 * filtert das NICHT die Liste und ändert nicht deren Titel/Zähler. Ein Link kann
 * mehrere Transaktionen bündeln (mehrere Cards teilen sich dieselbe linkId),
 * daher wird zusätzlich die konkrete tx-Id verglichen, damit ein Klick auf eine
 * ANDERE Card derselben Gruppe nicht fälschlich als "gleiche Auswahl" gilt.
 */
function _toggleFlowCardHighlight(linkId, txId) {
    txId = txId ?? null;
    const isSame = _flowCardHighlight
        && _flowCardHighlight.linkId === linkId
        && _flowCardHighlight.txId === txId;
    _flowCardHighlight = isSame ? null : { linkId, txId };
    _applyFlowCardPinnedClass();
    _applyFlowHighlight();
}

function _applyFlowCardPinnedClass() {
    const listEl = document.getElementById('flowTxList');
    if (!listEl) return;
    const pinnedTxId = _flowCardHighlight ? _flowCardHighlight.txId : null;
    listEl.querySelectorAll('.flow-tx-card').forEach(card => {
        card.classList.toggle('flow-tx-card-pinned', pinnedTxId != null && card.dataset.txId === pinnedTxId);
    });
}

/**
 * Hover über eine Transaktions-Card zeigt immer eine reine Vorschau-Hervorhebung
 * im Sankey (temporär, ändert keinen Zustand). Klick pinnt die Hervorhebung für
 * genau diese Transaktion, ohne die Liste zu filtern.
 */
function _wireFlowTxListEvents() {
    const listEl = document.getElementById('flowTxList');
    if (!listEl || listEl._flowWired) return;
    listEl._flowWired = true;

    listEl.addEventListener('mouseover', (event) => {
        if (_flowSelection) return;
        const card = event.target.closest('.flow-tx-card');
        if (!card) return;
        const linkId = card.dataset.linkId;
        if (!linkId || linkId === _flowHoveredLinkId) return;
        _flowHoveredLinkId = linkId;
        _applyFlowHighlight({ type: 'link', id: linkId });
    });

    listEl.addEventListener('mouseout', (event) => {
        const card = event.target.closest('.flow-tx-card');
        if (!card || card.contains(event.relatedTarget)) return;
        _flowHoveredLinkId = null;
        _applyFlowHighlight();
    });

    listEl.addEventListener('click', (event) => {
        const card = event.target.closest('.flow-tx-card');
        if (!card) return;
        const linkId = card.dataset.linkId;
        if (!linkId) return;
        _flowHoveredLinkId = null;
        _toggleFlowCardHighlight(linkId, card.dataset.txId);
    });
}

function _flowLinksForNode(nodeId) {
    if (!_flowGraphCache) return [];
    return _flowGraphCache.links.filter(l => l.source === nodeId || l.target === nodeId);
}

/**
 * Zeichnet Highlight/Dimmed-Klassen im Sankey. Ohne Argument gilt Priorität:
 * 1) die "harte" Chart-Auswahl (_flowSelection, filtert auch die Liste),
 * 2) sonst eine per Card-Klick gepinnte reine Hervorhebung (_flowCardHighlight).
 * Mit expliziter selection (auch null) kann eine rein visuelle Vorschau (Hover
 * über eine Transaktions-Card) angezeigt werden, ohne einen der beiden
 * Zustände zu verändern.
 */
function _applyFlowHighlight(selectionOverride) {
    const sel = selectionOverride !== undefined
        ? selectionOverride
        : (_flowSelection || (_flowCardHighlight ? { type: 'link', id: _flowCardHighlight.linkId } : null));
    const svg = d3.select('#flowSvg');
    if (!sel) {
        svg.selectAll('.flow-link, .flow-node').classed('flow-dimmed', false).classed('flow-highlighted', false);
        return;
    }

    const linkIds = new Set();
    const nodeIds = new Set();

    if (sel.type === 'link') {
        linkIds.add(sel.id);
        const link = _flowGraphCache.links.find(l => l.id === sel.id);
        if (link) { nodeIds.add(link.source); nodeIds.add(link.target); }
    } else if (sel.type === 'node') {
        nodeIds.add(sel.id);
        for (const l of _flowLinksForNode(sel.id)) {
            linkIds.add(l.id);
            nodeIds.add(l.source);
            nodeIds.add(l.target);
        }
    }

    svg.selectAll('.flow-link')
        .classed('flow-highlighted', d => linkIds.has(d.raw.id))
        .classed('flow-dimmed', d => !linkIds.has(d.raw.id));
    svg.selectAll('.flow-node')
        .classed('flow-highlighted', d => nodeIds.has(d.id))
        .classed('flow-dimmed', d => !nodeIds.has(d.id));
}

// ── Transaktionsliste rechts ───────────────────────────────────────────────

function _flowTxListFromLinks(links) {
    const list = [];
    const seen = new Set();
    for (const link of links) {
        for (const det of (link.details || [])) {
            if (det.transaction && !seen.has(det.transaction.id)) {
                seen.add(det.transaction.id);
                list.push({ tx: det.transaction, pairRole: det.pairedTransaction ? 'out' : null, linkId: link.id });
            }
            if (det.pairedTransaction && !seen.has(det.pairedTransaction.id)) {
                seen.add(det.pairedTransaction.id);
                list.push({ tx: det.pairedTransaction, pairRole: 'in', linkId: link.id });
            }
        }
    }
    list.sort((a, b) => (b.tx.date || '').localeCompare(a.tx.date || ''));
    return list;
}

/**
 * Baut einen durchsuchbaren Text aus allen auf der Card sichtbaren Feldern.
 * Zahlenwerte werden sowohl mit Punkt (Rohwert, z.B. aus der API) als auch mit
 * Komma (so wie sie auf der Card angezeigt werden, de-DE-Format) aufgenommen,
 * damit die Suche unabhängig vom eingegebenen Dezimaltrennzeichen funktioniert.
 */
function _flowTxSearchHaystack(item) {
    const tx = item.tx;
    const parts = [
        tx.positionLabel, tx.positionType, tx.type, tx.date,
        tx.currency, tx.feesCurrency, tx.transferId, tx.transactionId, tx.comment
    ];
    for (const n of [tx.quantity, tx.pricePerBtc, tx.fees, tx.quantityFiat, tx.exchangeRate]) {
        if (n == null) continue;
        const raw = String(n);
        parts.push(raw, raw.replace('.', ','));
    }
    return parts.filter(v => v != null).join(' ').toLowerCase();
}

function onFlowTxSearchInput(value) {
    _flowTxSearchTerm = (value || '').trim().toLowerCase();
    const clearBtn = document.getElementById('flowTxSearchClear');
    if (clearBtn) clearBtn.classList.toggle('d-none', !value);
    _renderFlowTxPanel();
}

function clearFlowTxSearch() {
    const input = document.getElementById('flowTxSearch');
    if (input) input.value = '';
    onFlowTxSearchInput('');
    input?.focus();
}

function _flowSelectionTxList() {
    if (!_flowGraphCache) return [];
    if (!_flowSelection) return _flowTxListFromLinks(_flowGraphCache.links);

    if (_flowSelection.type === 'node') {
        return _flowTxListFromLinks(_flowLinksForNode(_flowSelection.id));
    }
    if (_flowSelection.type === 'link') {
        const link = _flowGraphCache.links.find(l => l.id === _flowSelection.id);
        return link ? _flowTxListFromLinks([link]) : [];
    }
    return _flowTxListFromLinks(_flowGraphCache.links);
}

function _flowSelectionLabel() {
    if (!_flowSelection || !_flowGraphCache) return '';
    if (_flowSelection.type === 'node') {
        const node = _flowGraphCache.nodes.find(n => n.id === _flowSelection.id);
        return node ? _flowNodeLabel(node) : _flowSelection.id;
    }
    const link = _flowGraphCache.links.find(l => l.id === _flowSelection.id);
    if (!link) return _flowSelection.id;
    const srcNode = _flowGraphCache.nodes.find(n => n.id === link.source);
    const tgtNode = _flowGraphCache.nodes.find(n => n.id === link.target);
    const src = srcNode ? _flowNodeLabel(srcNode) : link.source;
    const tgt = tgtNode ? _flowNodeLabel(tgtNode) : link.target;
    return `${src} → ${tgt} (${link.month})`;
}

function _renderFlowTxPanel() {
    const listEl   = document.getElementById('flowTxList');
    const emptyEl  = document.getElementById('flowTxEmpty');
    const titleEl  = document.getElementById('flowTxTitle');
    const countEl  = document.getElementById('flowTxCount');
    const clearBtn = document.getElementById('flowTxClearBtn');
    if (!listEl) return;

    let items = _flowSelectionTxList();
    if (_flowTxSearchTerm) {
        items = items.filter(item => _flowTxSearchHaystack(item).includes(_flowTxSearchTerm));
    }

    if (_flowSelection) {
        titleEl.textContent = t('flow.panel.filteredBy', { LABEL: _flowSelectionLabel() });
        clearBtn.classList.remove('d-none');
    } else {
        titleEl.textContent = t('flow.panel.all');
        clearBtn.classList.add('d-none');
    }
    countEl.textContent = t('flow.panel.count', { COUNT: items.length });

    if (!items.length) {
        listEl.innerHTML = '';
        emptyEl.classList.remove('d-none');
        return;
    }
    emptyEl.classList.add('d-none');
    listEl.innerHTML = items.map(_renderFlowTxCard).join('');
    _applyFlowCardPinnedClass();
}

function _flowFormatFiat(val, code) {
    if (val == null) return '–';
    const num = Number(val).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return code ? `${num} ${code}` : num;
}

function _renderFlowTxCard(item) {
    const tx = item.tx;
    const date = tx.date ? String(tx.date).replace('T', ' ').substring(0, 19) : '–';

    let badge = '';
    if (item.pairRole === 'out') {
        badge = `<span class="flow-tx-card-badge pair-out">${esc(t('flow.panel.transferOut'))}</span>`;
    } else if (item.pairRole === 'in') {
        badge = `<span class="flow-tx-card-badge pair-in">${esc(t('flow.panel.transferIn'))}</span>`;
    } else if (tx.type === 'BUY') {
        badge = `<span class="flow-tx-card-badge type-buy">${esc(t('flow.legend.buy'))}</span>`;
    } else if (tx.type === 'SELL') {
        badge = `<span class="flow-tx-card-badge type-sell">${esc(t('flow.legend.sell'))}</span>`;
    }

    const priceLine = tx.pricePerBtc != null
        ? _flowFieldRow(t('table.col.pricePerBtc'), _flowFormatFiat(tx.pricePerBtc, tx.currency))
        : '';
    const totalLine = tx.quantityFiat != null
        ? _flowFieldRow(t('table.col.total'), _flowFormatFiat(tx.quantityFiat, tx.currency))
        : '';
    const feesLine = tx.fees != null
        ? _flowFieldRow(t('table.col.fees'), _flowFormatFiat(tx.fees, tx.feesCurrency || tx.currency))
        : '';
    const exRateLine = (tx.exchangeRate != null && Number(tx.exchangeRate) !== 1)
        ? _flowFieldRow(t('modal.field.exchangeRate'), tx.exchangeRate)
        : '';
    const transferLine = tx.transferId
        ? _flowFieldRow(t('table.col.transferId'), esc(tx.transferId.substring(0, 8)) + '…', tx.transferId)
        : '';
    const commentLine = tx.comment
        ? _flowFieldRow(t('modal.field.comment'), esc(tx.comment), tx.comment)
        : '';
    const dupClass = tx.duplicate ? ' warning-duplicate' : '';

    const positionSub = tx.positionType
        ? `<div style="color:var(--text-muted);font-size:.62rem;letter-spacing:.03em;text-transform:uppercase;margin:-.2rem 0 .35rem">${esc(tx.positionType)}</div>`
        : '';

    // Zusatzinfos nur bei Verkäufen: realisierter G/V + Aufschlüsselung, aus
    // welchen historischen Käufen sich dieser Verkauf zusammensetzt (Menge,
    // Kaufdatum, Haltedauer, Steuerfrei/-pflichtig-Badge ab 365 Tagen). Eigene,
    // unabhängige FIFO-Berechnung (siehe _flowComputeFifo) — bewusst nicht mit
    // der Kauf-Card geteilt, um diese nicht unübersichtlich zu machen.
    let gvLine    = '';
    let lotsBlock = '';
    if (tx.type === 'SELL') {
        const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';
        const consumed = _flowSellMeta.get(String(tx.id)) || [];

        const proceeds = _flowFiatInDisplayCurrency(tx, currency) || 0;
        let costOfSold = 0;
        consumed.forEach(c => {
            const paid = _flowBuyPaid(c.buyTx, currency);
            if (paid == null) return;
            costOfSold += (paid / Number(c.buyTx.quantity)) * c.qty;
        });
        const gain   = proceeds - costOfSold;
        const posNeg = gain >= 0 ? 'text-pos' : 'text-neg';
        gvLine = _flowFieldRow(
            t('holdings.buyDetail.gvAbs'),
            `<span class="${posNeg}">${gain >= 0 ? '+' : ''}${_flowFmt(gain, currency)}</span>`
        );

        const sellQty = Number(tx.quantity) || 1;
        const lotsHtml = consumed.map(c => {
            const taxFree  = c.days >= FLOW_TAX_FREE_DAYS;
            const taxBadge = `<span class="yearly-tax-badge ${taxFree ? 'tax-free' : 'tax-liable'}">${
                esc(t(taxFree ? 'yearly.tax.free' : 'yearly.tax.liable'))
            }</span>`;
            const buyDate   = c.buyTx.date ? String(c.buyTx.date).substring(0, 10) : '–';
            const daysLabel = t('yearly.tiles.daysHeld', { DAYS: c.days });

            const paid = _flowBuyPaid(c.buyTx, currency);
            let lotGainHtml = '';
            if (paid != null) {
                const unitCost    = paid / Number(c.buyTx.quantity);
                const lotCost     = unitCost * c.qty;
                const lotProceeds = proceeds * (c.qty / sellQty);
                const lotGain     = lotProceeds - lotCost;
                const lotPosNeg   = lotGain >= 0 ? 'text-pos' : 'text-neg';
                lotGainHtml = `<span class="yearly-lot-gain ${lotPosNeg}">${lotGain >= 0 ? '+' : ''}${_flowFmt(lotGain, currency)}</span>`;
            }

            return `<div class="yearly-lot-row">
                <span class="yearly-lot-qty">${_flowFmt8(c.qty)}</span>
                <span class="yearly-lot-date">${esc(buyDate)}</span>
                <span class="yearly-lot-days">${esc(daysLabel)}</span>
                ${lotGainHtml}
                ${taxBadge}
            </div>`;
        }).join('');

        lotsBlock = consumed.length
            ? `<div class="yearly-lots-title">${esc(t('yearly.tiles.lotsTitle'))}</div>
               <div class="yearly-lots-list">${lotsHtml}</div>`
            : '';
    }

    const txJson = JSON.stringify(tx).replace(/"/g, '&quot;');

    return `<div class="flow-tx-card${dupClass}" data-link-id="${esc(item.linkId || '')}" data-tx-id="${esc(tx.id)}">
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
        ${positionSub}
        ${_flowFieldRow(t('table.col.date'), date)}
        ${_flowFieldRow(t('table.col.type'), esc(tx.type))}
        ${_flowFieldRow(t('table.col.btc'), _flowFmt8(tx.quantity))}
        ${priceLine}${totalLine}${feesLine}${exRateLine}${transferLine}
        ${gvLine}
        ${commentLine}
        ${lotsBlock}
    </div>`;
}

function _flowFieldRow(label, value, title) {
    return `<div class="flow-tx-field">
        <span class="flow-tx-field-label">${esc(label)}</span>
        <span class="flow-tx-field-value"${title ? ` title="${esc(title)}"` : ''}>${value}</span>
    </div>`;
}