'use strict';
// Theme (Dark/Light) init/toggle/apply now lives in theme.js (shared with flow.html/holdings.html).
// Hinweis Sticky-Suche (siehe depot.css): #posTable_wrapper/#txTable_wrapper sind
// bereits eigene Scroll-Container (max-height + overflow-y:auto), daher genügt dort
// top:0 für die sticky Such-/Length-Zeile — kein Navbar-Höhen-Offset per JS nötig.

// ── Font + Density Toggle: jetzt in theme.js (siehe dort) ──
// Auf allen 4 Seiten geladen statt nur hier, damit die Einstellung überall wirkt.

// ── Flatpickr Date Pickers / Add-Edit-Transaction modal ───
// Moved to tx-form.js (shared with the Flow Diagram page): FLATPICKR_LOCALES,
// _fpAdd/_fpEdit/_fpTransferIn, _fpLocale(), initFlatpickr().

// ── Kachel-Grid (Drag&Drop Desktop, Pfeil-Buttons Mobile) ──
// Eigenständige, unabhängige Implementierung — ersetzt das frühere einfache
// Auf/Ab-Sortieren einer flachen Liste (SECTION_ORDER_KEY) durch ein echtes
// Grid: 4 Reihen, max. 3 Slots pro Reihe (wie Bestandsansicht), analog zum
// Muster auf Jahresansicht/Bestandsansicht (siehe yearly.js/holdings.js),
// aber eigener Namensraum/Key (bewusst nicht geteilt).
const OVERVIEW_LAYOUT_KEY = 'overview-layout-v2';
const OVERVIEW_MAX_COLS   = 3;
// 3 Reihen — die Übersicht ist reine Daten-Verwaltung (Kennzahlen und
// Aufteilung nach Wallet/Börse leben jetzt auf der Bestandsansicht, siehe
// holdings.js' HOLDINGS_DEFAULT_LAYOUT). Reihe 1: Wallets & Börsen (füllt
// allein). Reihe 2: Alle Transaktionen (füllt allein). Reihe 3: leer, für
// künftige Kacheln reserviert (z.B. Import).
const OVERVIEW_DEFAULT_LAYOUT = [
    ['overview-block-wallets'],
    ['transactionsPanel'],
    ['overview-block-import-history']
];

// Tablet/Phone (≤991px, siehe App-weite Konvention): Wallets- und Transaktions-
// Kachel dürfen dort nur gemeinsam in EINER Row stehen (kein Drag & Drop auf
// Mobile, aber Pfeil-Buttons könnten sie sonst in getrennte Rows schieben).
const OVERVIEW_LAYOUT_MOBILE_BREAKPOINT = 991;

function _isOverviewMobileLayout() {
    return window.innerWidth <= OVERVIEW_LAYOUT_MOBILE_BREAKPOINT;
}

/** Führt Rows zusammen, in denen Wallets- und Transaktions-Kachel getrennt
 *  stehen (z.B. ein auf Desktop gespeichertes Layout) — Ziel-Row ist die mit
 *  dem kleineren Index, Reihenfolge der Blöcke bleibt erhalten. Kein Effekt,
 *  wenn beide bereits in derselben Row sind. */
function _enforceOverviewMobileRowMerge(layout) {
    const rowIdxOf = id => layout.findIndex(row => row.includes(id));
    const rowIdxs = [...new Set(OVERVIEW_COLLAPSIBLE_BLOCKS.map(rowIdxOf).filter(i => i !== -1))];
    if (rowIdxs.length <= 1) return layout;

    const merged = layout.map(row => row.slice());
    const targetIdx = Math.min(...rowIdxs);
    rowIdxs.forEach(i => {
        if (i === targetIdx) return;
        merged[targetIdx] = merged[targetIdx].concat(merged[i]);
        merged[i] = [];
    });
    return merged;
}

/** Wendet die Mobile-Row-Regel auf ein Layout an, sofern gerade Tablet/Phone
 *  aktiv ist — zentrale Stelle, die von Load, Reset UND Resize genutzt wird. */
function _overviewLayoutForBreakpoint(layout) {
    return _isOverviewMobileLayout() ? _enforceOverviewMobileRowMerge(layout) : layout;
}

let _overviewDragEl = null;

function initOverviewLayout() {
    const grid = document.getElementById('overviewGrid');
    if (!grid) return;

    applyOverviewLayout(grid, _loadOverviewLayout());
    wireOverviewDragAndDrop(grid);
    updateOverviewRowCols(grid);
    _overviewTriggerChartResize();
    _overviewApplyTxViewMode();
    _overviewApplyPosViewMode();
    _overviewApplyHistoryViewMode();
    _applyOverviewCollapseState();

    const hint = document.getElementById('overviewLayoutHint');
    if (hint) hint.classList.remove('d-none');
    const resetBtn = document.getElementById('overviewResetLayoutBtn');
    if (resetBtn) resetBtn.classList.remove('d-none');
}

function _loadOverviewLayout() {
    try {
        const saved = JSON.parse(localStorage.getItem(OVERVIEW_LAYOUT_KEY));
        if (Array.isArray(saved)) {
            const savedIds   = saved.flat();
            const defaultIds = OVERVIEW_DEFAULT_LAYOUT.flat();
            if (savedIds.length === defaultIds.length && defaultIds.every(id => savedIds.includes(id))) {
                return _overviewLayoutForBreakpoint(saved);
            }
        }
    } catch (e) { /* ignore malformed storage */ }
    return _overviewLayoutForBreakpoint(OVERVIEW_DEFAULT_LAYOUT);
}

function _saveOverviewLayout(grid) {
    const rows = Array.from(grid.querySelectorAll('.overview-grid-row'));
    const layout = rows.map(row => Array.from(row.querySelectorAll('.overview-draggable')).map(el => el.id));
    localStorage.setItem(OVERVIEW_LAYOUT_KEY, JSON.stringify(layout));
}

function applyOverviewLayout(grid, layout) {
    const rows = Array.from(grid.querySelectorAll('.overview-grid-row'));
    layout.forEach((rowIds, i) => {
        const row = rows[i];
        if (!row) return;
        rowIds.forEach(id => {
            const el = document.getElementById(id);
            if (el) row.appendChild(el);
        });
    });
}

function updateOverviewRowCols(grid) {
    grid.querySelectorAll('.overview-grid-row').forEach(row => {
        const blocks = Array.from(row.querySelectorAll('.overview-draggable'));
        const count = blocks.length;
        row.style.setProperty('--cols', Math.max(count, 1));
        blocks.forEach(b => b.style.setProperty('--span', 1));
        row.classList.toggle('empty', count === 0);
    });
}

/**
 * Nach updateOverviewRowCols() geänderte --cols/--span-Werte ändern die tatsächliche
 * Container-Breite des Donut-Charts per CSS — ApexCharts misst seine SVG-Breite aber
 * nur beim (Neu-)Rendern bzw. bei einem window "resize"-Event, nicht bei reinen
 * CSS-Grid-Änderungen (siehe derselbe, bereits einmal auf der Jahresansicht behobene
 * Bug-Fall in yearly.js). Vorsorglich hier ebenfalls nach jeder Layout-Änderung ein
 * synthetisches resize-Event auslösen.
 */
function _overviewTriggerChartResize() {
    window.dispatchEvent(new Event('resize'));
}

function resetOverviewLayout() {
    localStorage.removeItem(OVERVIEW_LAYOUT_KEY);
    const grid = document.getElementById('overviewGrid');
    if (!grid) return;
    applyOverviewLayout(grid, _overviewLayoutForBreakpoint(OVERVIEW_DEFAULT_LAYOUT));
    updateOverviewRowCols(grid);
    _overviewTriggerChartResize();
    _overviewApplyTxViewMode();
    _overviewApplyPosViewMode();
    _overviewApplyHistoryViewMode();
}

// ── Zuklapp-Feature für Kacheln (nur Tablet/Phone ≤991px, siehe depot.css) ──
// Eigener Namensraum/Key, unabhängig vom Layout-Key (Kollabieren betrifft nur
// die Sichtbarkeit des Kachel-Inhalts, nicht die Grid-Reihenfolge).
const OVERVIEW_COLLAPSE_KEY = 'overview-collapse-v1';
const OVERVIEW_COLLAPSIBLE_BLOCKS = ['overview-block-wallets', 'transactionsPanel', 'overview-block-import-history'];

function _loadOverviewCollapseState() {
    try {
        const saved = JSON.parse(localStorage.getItem(OVERVIEW_COLLAPSE_KEY));
        if (saved && typeof saved === 'object') return saved;
    } catch (e) { /* ignore malformed storage */ }
    return {};
}

function _setOverviewCollapseIcon(id, collapsed) {
    const icon = document.querySelector(`#${id}-collapseBtn i`);
    if (icon) icon.className = collapsed ? 'bi bi-plus-square' : 'bi bi-dash-square';
}

function _applyOverviewCollapseState() {
    const state = _loadOverviewCollapseState();
    OVERVIEW_COLLAPSIBLE_BLOCKS.forEach(id => {
        const block = document.getElementById(id);
        const collapsed = !!state[id];
        if (block) block.classList.toggle('collapsed', collapsed);
        _setOverviewCollapseIcon(id, collapsed);
    });
}

function toggleOverviewBlockCollapse(id) {
    const block = document.getElementById(id);
    if (!block) return;
    const collapsed = block.classList.toggle('collapsed');
    _setOverviewCollapseIcon(id, collapsed);
    const state = _loadOverviewCollapseState();
    state[id] = collapsed;
    localStorage.setItem(OVERVIEW_COLLAPSE_KEY, JSON.stringify(state));
    _overviewTriggerChartResize();
}

function wireOverviewDragAndDrop(grid) {
    grid.querySelectorAll('.overview-draggable').forEach(el => {
        el.querySelectorAll('.overview-drag-handle').forEach(handle => {
            handle.addEventListener('mousedown', () => el.setAttribute('draggable', 'true'));
        });

        el.addEventListener('dragstart', (e) => {
            _overviewDragEl = el;
            el.classList.add('dragging');
            e.dataTransfer.effectAllowed = 'move';
        });

        el.addEventListener('dragend', () => {
            el.removeAttribute('draggable');
            el.classList.remove('dragging');
            _overviewDragEl = null;
            grid.querySelectorAll('.overview-grid-row.drag-over').forEach(r => r.classList.remove('drag-over'));
            updateOverviewRowCols(grid);
            _overviewTriggerChartResize();
            _overviewApplyTxViewMode();
            _overviewApplyPosViewMode();
            _overviewApplyHistoryViewMode();
            _saveOverviewLayout(grid);
        });
    });

    grid.querySelectorAll('.overview-grid-row').forEach(row => {
        row.addEventListener('dragover', (e) => {
            if (!_overviewDragEl) return;
            e.preventDefault();

            const countExcludingDragged = Array.from(row.querySelectorAll('.overview-draggable'))
                .filter(b => b !== _overviewDragEl).length;
            if (countExcludingDragged >= OVERVIEW_MAX_COLS) {
                e.dataTransfer.dropEffect = 'none';
                return;
            }
            e.dataTransfer.dropEffect = 'move';
            row.classList.add('drag-over');

            const after = _getOverviewDragAfterElement(row, e.clientX);
            if (after == null) row.appendChild(_overviewDragEl);
            else row.insertBefore(_overviewDragEl, after);
            updateOverviewRowCols(grid);
        });

        row.addEventListener('dragleave', (e) => {
            if (e.target === row) row.classList.remove('drag-over');
        });

        row.addEventListener('drop', (e) => e.preventDefault());
    });

    document.addEventListener('mouseup', () => {
        grid.querySelectorAll('.overview-draggable[draggable="true"]').forEach(el => {
            if (!el.classList.contains('dragging')) el.removeAttribute('draggable');
        });
    });
}

function _getOverviewDragAfterElement(row, x) {
    const els = [...row.querySelectorAll('.overview-draggable:not(.dragging)')];
    return els.reduce((closest, child) => {
        const box    = child.getBoundingClientRect();
        const offset = x - box.left - box.width / 2;
        if (offset < 0 && offset > closest.offset) return { offset, element: child };
        return closest;
    }, { offset: -Infinity, element: null }).element;
}

/** Bewegt eine Kachel eine Position weiter (Lesereihenfolge, Reihe für Reihe,
 *  links nach rechts) — für Mobile/Touch, wo natives Drag & Drop fehlt bzw.
 *  über die Pfeil-Buttons in .overview-block-actions angesteuert wird. */
/**
 * Bewegt eine Kachel einen Schritt per Pfeil-Button. Innerhalb der eigenen Row
 * wird einfach mit dem Nachbarn getauscht. An der Row-Grenze WANDERT die Kachel
 * in die Nachbar-Row (Ziel wächst, Quelle schrumpft), sofern dort noch Platz ist
 * (< OVERVIEW_MAX_COLS) — direkt an der überschrittenen Grenze eingefügt (runter
 * → wird erste Kachel der nächsten Row, hoch → wird letzte Kachel der vorherigen
 * Row). Ist die Nachbar-Row bereits voll, wird stattdessen mit deren Rand-Kachel
 * getauscht (Row-Größen bleiben dann unverändert) — sonst würde die Kachel gegen
 * die 3-Slot-Grenze "anstoßen" und der Pfeil täte nichts.
 * (Vorher: rein Flat-Index-basierter Tausch — hatte keinen Swap-Partner für leere
 * oder nicht volle Nachbar-Rows, Pfeil war dann wirkungslos.)
 */
function moveOverviewBlock(id, direction) {
    const grid = document.getElementById('overviewGrid');
    if (!grid) return;

    const rows   = Array.from(grid.querySelectorAll('.overview-grid-row'));
    const layout = rows.map(r => Array.from(r.querySelectorAll('.overview-draggable')).map(el => el.id));

    let rowIdx = -1, posInRow = -1;
    layout.forEach((rowIds, i) => {
        const p = rowIds.indexOf(id);
        if (p !== -1) { rowIdx = i; posInRow = p; }
    });
    if (rowIdx === -1) return;

    const targetPosInRow = posInRow + direction;

    if (targetPosInRow >= 0 && targetPosInRow < layout[rowIdx].length) {
        // Innerhalb der Row: einfacher Tausch mit dem Nachbarn.
        [layout[rowIdx][posInRow], layout[rowIdx][targetPosInRow]] =
            [layout[rowIdx][targetPosInRow], layout[rowIdx][posInRow]];
    } else {
        // Row-Grenze überschritten.
        const targetRowIdx = rowIdx + direction;
        if (targetRowIdx < 0 || targetRowIdx >= layout.length) return;

        // Tablet/Phone (≤991px): Wallets- und Transaktions-Kachel dürfen die
        // gemeinsame Row nicht verlassen (siehe OVERVIEW_LAYOUT_MOBILE_BREAKPOINT) —
        // Pfeil tut in diesem Fall bewusst nichts, statt sie zu trennen.
        if (_isOverviewMobileLayout() && OVERVIEW_COLLAPSIBLE_BLOCKS.includes(id)) return;

        if (layout[targetRowIdx].length < OVERVIEW_MAX_COLS) {
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

    updateOverviewRowCols(grid);
    _overviewTriggerChartResize();
    _overviewApplyTxViewMode();
    _overviewApplyPosViewMode();
    _overviewApplyHistoryViewMode();
    _saveOverviewLayout(grid);
}

document.addEventListener('DOMContentLoaded', initOverviewLayout);


// ── Exchange Dropdown / Add-Edit-Transaction helpers ──────
// Moved to tx-form.js (shared with the Flow Diagram page): _positionsCache,
// _ensurePositionsLoaded(), _fillExchangeDropdown(), onExchangeSelectChange(),
// _showExchangeNewInput(), _getExchangeValue(), _setExchangeValue().

// ── i18n + currency init ─────────────────────────────────
I18N.ready.then(() => {
    I18N.applyI18n();
    OFFLINE.init();

    // Sync JS currency state with what the server used (cookie may lag)
    if (typeof SERVER_CURRENCY !== 'undefined' && SERVER_CURRENCY !== CURRENCY.current()) {
        CURRENCY.setCurrency(SERVER_CURRENCY);
    }

    // Show no-price banner if server flagged it
    if (typeof NO_PRICE_AVAILABLE !== 'undefined' && NO_PRICE_AVAILABLE) {
        const banner = document.getElementById('noPriceBanner');
        const text   = document.getElementById('noPriceBannerText');
        if (banner && text) {
            text.textContent = t('currency.no_price', { currency: CURRENCY.current() });
            banner.classList.remove('d-none');
        }
    }

	initFlatpickr();
	// Tabellen-/Kartenansicht (Transaktionen + Wallets) nach dem Laden erneut
	// anwenden: initOverviewLayout() lief bereits bei DOMContentLoaded, ggf.
	// bevor der Viewport (window.innerWidth) am Handy zuverlässig feststand —
	// führte dazu, dass am Handy nach einem frischen Reload fälschlich die
	// Tabelle (statt der Kartenansicht) stehen blieb, bis irgendeine Aktion
	// (z.B. Filtern) zufällig einen weiteren Umschalt-Aufruf auslöste. Nach dem
	// Laden ist der Viewport garantiert stabil, hier daher sicherheitshalber
	// erneut prüfen/umschalten.
	loadTransactions().then(() => {
	    _overviewApplyTxViewMode();
	    _overviewApplyPosViewMode();
	    _overviewApplyHistoryViewMode();
	});
	// sorting for exchange/wallet table
	if ($.fn.DataTable.isDataTable('#posTable')) {
        $('#posTable').DataTable().destroy();
    }

    $('#posTable').DataTable({
        order:      [[3, 'desc']],
        pageLength: 100,
		paging:     false,
		searching:  true,
		autoWidth:  false,
        language: {
            search:     t('dt.search'),
            lengthMenu: t('dt.lengthMenu'),
            info:       t('dt.info'),
            paginate:   { previous: t('dt.previous'), next: t('dt.next') }
        },
        columnDefs: [{ orderable: false, targets: [0] }]
    });
    _applyPosEmptyFilter();

});

// ── Hilfe / Settings-Modal / Sats-Modal / Preis-Badge / Refresh: jetzt in
// navbar.js (siehe dort) — auf allen 4 Seiten geladen statt nur hier.

// ── Positionen: Toggle "Leere ausblenden" ──────────────────
// Eigenständige Implementierung, analog zum früheren Solo-Transfer-Filter
// (Tabelle: DataTables ext.search.push; Kartenansicht: einfaches d-none
// anhand von data-qty-sats, da Karten serverseitig gerendert werden).
const POS_EMPTY_FILTER_KEY = 'pos-hide-empty-v1';
let _posEmptyFilterActive = _loadPosEmptyFilter();

function _loadPosEmptyFilter() {
    const saved = localStorage.getItem(POS_EMPTY_FILTER_KEY);
    // Default AN (matcht das frühere hart codierte Verhalten der Kartenansicht).
    return saved === null ? true : saved === '1';
}

$.fn.dataTable.ext.search.push(function (settings, searchData, dataIndex, rowData, counter) {
    if (settings.nTable.id !== 'posTable' || !_posEmptyFilterActive) return true;
    const row = settings.aoData[dataIndex].nTr;
    return row ? Number(row.dataset.qtySats) > 0 : true;
});

function _applyPosEmptyFilter() {
    document.querySelectorAll('#posFilterGroup .pill-filter-btn').forEach(btn => {
        btn.classList.toggle('is-active', btn.dataset.mode === (_posEmptyFilterActive ? 'withHoldings' : 'all'));
    });

    if ($.fn.DataTable.isDataTable('#posTable')) {
        $('#posTable').DataTable().draw();
    }

    _applyPosCardFilters();
}

// Aktueller Suchtext aus dem sichtbaren Suchfeld (siehe onPosVisibleSearchInput) —
// separat vom Leer-Filter gehalten, da beide Filter gleichzeitig aktiv sein
// können und unabhängig ausgelöst werden (Tabelle wird direkt über die
// DataTables-API gefiltert, die Kartenansicht braucht dafür diesen eigenen
// Abgleich, da Karten serverseitig gerendert und nie neu aufgebaut werden).
let _posSearchTerm = '';

/** Wendet Leer-Filter UND Suchtext gemeinsam auf die Kartenansicht an — eine
 *  Karte ist sichtbar, wenn sie BEIDE Kriterien erfüllt. Von _applyPosEmptyFilter()
 *  (Leer-Filter geändert) und onPosVisibleSearchInput() (Suchtext geändert)
 *  gleichermaßen aufgerufen. */
function _applyPosCardFilters() {
    const term = _posSearchTerm.trim().toLowerCase();
    document.querySelectorAll('#posCardsList .overview-pos-card').forEach(card => {
        const emptyHidden  = _posEmptyFilterActive && Number(card.dataset.qtySats) <= 0;
        const searchHidden = term && !(card.dataset.label || '').toLowerCase().includes(term);
        card.classList.toggle('d-none', emptyHidden || searchHidden);
    });
}

function togglePosEmptyFilter(mode) {
    _posEmptyFilterActive = mode === 'withHoldings';
    localStorage.setItem(POS_EMPTY_FILTER_KEY, _posEmptyFilterActive ? '1' : '0');
    _applyPosEmptyFilter();
}

function filterExchangeTransaction(exchange) {
    const panel = document.getElementById('transactionsPanel');
    panel.classList.remove('d-none');

    const doSearch = () => {
        $('#txTable').DataTable().search('"' + exchange + '"').draw();
        // Sichtbares Suchfeld in der Bulk-Toolbar mitziehen, sonst zeigt es einen
        // veralteten (leeren) Stand, obwohl im Hintergrund schon gefiltert ist.
        const visibleInput = document.getElementById('txSearchVisible');
        if (visibleInput) visibleInput.value = exchange;
        document.getElementById('txSearchVisibleClear')?.classList.toggle('d-none', !exchange);
    };

    if (!txLoaded) {
        loadTransactions().then(doSearch);
    } else {
        doSearch();
    }
}

// ── Transactions Panel ────────────────────────────────────
let txLoaded = false;
let _lastTxData = null; // letzte geladenen Rohdaten, für Re-Render bei Viewport-/Compact-Wechsel
let _lastTxIsCompact = null;
// txModal / txModalAdd (bootstrap.Modal instances) now live in tx-form.js

async function loadTransactions() {
	
    return fetch('/api/btc-tracking/transactions')
        .then(r => r.json())
        .then(data => {
            txLoaded = true;
            _lastTxData = data;
            renderTxTable(data);
        })
        .catch(err => {
            // Legende ist bereits statisch im HTML vorhanden (mit data-i18n) — hier nur
            // die Fehlermeldung in der Tabelle anzeigen.
            document.getElementById('txTableBody').innerHTML =
                `<tr><td></td><td></td><td></td><td></td><td></td><td class="text-neg py-3 text-center">${t('toast.error')}: ${err.message}</td><td></td><td></td><td></td><td></td></tr>`;
        });
}

function truncateTwoDecimals(num) {
  return Math.trunc(num * 100 + 1e-8) / 100;
}

const TX_TYPE_COLORS = {
    BUY:          'text-pos',
    SELL:         'text-neg',
    TRANSFER_IN:  'text-pos',
    TRANSFER_OUT: 'text-neg'
};

/** Baut das HTML einer einzelnen Transaktionszeile (ohne sie irgendwo einzufügen). */
function _buildTxRowHtml(tx, transferIdCounts, isCompact) {
    const color   = TX_TYPE_COLORS[tx.type] || '';
    const date    = tx.date ? tx.date.replace('T', ' ').substring(0, 19) : '–';
    const shortId = tx.transferId ? tx.transferId.substring(0, 8) + '…' : '–';

    const isSolo = tx.transferId
        && transferIdCounts[tx.transferId] === 1
        && (tx.type === 'TRANSFER_IN' || tx.type === 'TRANSFER_OUT');

    let earning;
    let paid;
    let posNeg = "";
    if (tx.type == "BUY") {
        if (tx.currency !== CURRENCY.current()) {
            earning = (CURRENT_PRICE - ((tx.pricePerBtc + tx.fees) * tx.exchangeRate)) * tx.quantity;
            paid = (tx.quantityFiat + tx.fees) * tx.exchangeRate;
        } else {
            earning = (CURRENT_PRICE - ((tx.pricePerBtc + tx.fees))) * tx.quantity;
            paid = (tx.quantityFiat + tx.fees);
        }

        let percentage = (100/paid * (paid + earning)) - 100;
        earning = formatEur(earning) + " (" + truncateTwoDecimals(percentage) + "%)";

        if (earning.startsWith("-")) {
            posNeg = "text-neg";
        } else {
            posNeg = "text-pos";
        }
    } else {
        earning="–";
    }
    return `<tr class="depot-row ${tx.currency !== CURRENCY.current() && tx.exchangeRate == 1 ?  'warning'  : ''} ${isSolo ? 'solo-transfer' : ''} ${tx.duplicate ? 'warning-duplicate' : ''}" data-id="${tx.id}" data-type="${tx.type}" data-transfer-id="${tx.transferId || ''}" ${tx.comment ? `title="${esc(tx.comment)}"` : ''} onclick="const cb=this.querySelector('.tx-row-check');cb.checked=!cb.checked;this.classList.toggle('selected',cb.checked);_updateBulkToolbar()">
	    <td class="${isCompact ? 'd-none' : ''}" onclick="event.stopPropagation()">
	        <input type="checkbox" class="tx-row-check" data-id="${tx.id}"
	               style="accent-color:var(--accent)"/>
	    </td>
	    <td class="depot-actions-cell" style="white-space:nowrap;padding-left:.4rem;padding-right:.4rem">
	        <span class="depot-actions-icon">${tx.comment ? `<i class="bi bi-info-circle" title="${esc(tx.comment)}"></i>` : ''}</span>
	        <span class="depot-actions">
	            <button class="btn btn-xs depot-btn-icon" onclick="event.stopPropagation(); openEditTx(${JSON.stringify(tx).replace(/"/g,'&quot;')})" title="Edit">
	                <i class="bi bi-pencil"></i>
	            </button>
	            <button class="btn btn-xs depot-btn-icon" onclick="event.stopPropagation(); openAddTx(${JSON.stringify(tx).replace(/"/g,'&quot;')})" title="Copy">
	                <i class="bi bi-copy"></i>
	            </button>
	            <button class="btn btn-xs depot-btn-icon text-neg" onclick="event.stopPropagation(); deleteTx(${tx.id})" title="Delete">
	                <i class="bi bi-trash"></i>
	            </button>
	        </span>
	    </td>
	    <td class="tx-date-col" style="white-space:nowrap" data-cell-label="${esc(t('table.col.date'))}">${date}</td>
	    <td data-cell-label="${esc(t('table.col.position'))}">${tx.positionLabel || '–'}</td>
	    <td data-cell-label="${esc(t('table.col.type'))}"><span class="${color}">${tx.type}</span></td>
	    <td class="text-end" data-cell-label="${esc(t('table.col.btc'))}">${fmt8(tx.quantity)}</td>
	    <td class="text-end" data-cell-label="${esc(t('table.col.pricePerBtc'))}">${tx.pricePerBtc != null ? tx.currency !== CURRENCY.current() ?  formatEur(tx.pricePerBtc * tx.exchangeRate) : formatEur(tx.pricePerBtc) : '–'}</td>
	    <td class="text-end" data-cell-label="${esc(t('table.col.total'))}">${tx.quantityFiat != null ? tx.currency !== CURRENCY.current() ? formatEur((tx.quantityFiat + tx.fees) * tx.exchangeRate) + ' <span class="text-end" style="font-size:.7rem">[' + tx.currency + ' × ' + tx.exchangeRate + ']</span>' : formatEur((tx.quantityFiat + tx.fees)) : '–'}</td>
	    <td class="text-end" data-cell-label="${esc(t('table.col.gl'))}"><span class="${posNeg}">${tx.quantityFiat != null ? earning : '–'}</span></td>
	    <td class="text-end text-muted" style="font-size:.7rem" title="${tx.transferId || ''}" data-cell-label="${esc(t('table.col.transferId'))}">${shortId}</td>
	</tr>`;
}

/**
 * Kopiert alle Attribute und den inneren Inhalt von newRowHtml auf den bestehenden
 * rowNode, OHNE den DOM-Knoten selbst auszutauschen. Wichtig, damit DataTables
 * (bei DOM-Datenquelle) die Zeile per row(node).invalidate() weiterhin korrekt
 * zuordnen kann, statt sie als "verschwunden + neu" zu behandeln.
 */
function _replaceTxRowInPlace(rowNode, newRowHtml) {
    const tmp = document.createElement('tbody');
    tmp.innerHTML = newRowHtml;
    const newNode = tmp.firstElementChild;
    if (!newNode) return;
    [...rowNode.attributes].forEach(a => rowNode.removeAttribute(a.name));
    [...newNode.attributes].forEach(a => rowNode.setAttribute(a.name, a.value));
    rowNode.innerHTML = newNode.innerHTML;
}

// Letzter gerenderter Stand pro Zeile (id → HTML), für den Diff bei Folge-Renders
// nach einer Mutation (Edit/Duplizieren/Löschen) — siehe renderTxTable().
let _txRowHtmlById = new Map();

function renderTxTable(data) {
	const isCompact = getDeviceType() !== 'DESKTOP';

	// NEU: Häufigkeit jeder transferId zählen → genau 1x = Solo-Transfer
    const transferIdCounts = {};
    data.forEach(tx => {
        if (tx.transferId) {
            transferIdCounts[tx.transferId] = (transferIdCounts[tx.transferId] || 0) + 1;
        }
    });

    const newHtmlById = new Map();
    data.forEach(tx => newHtmlById.set(String(tx.id), _buildTxRowHtml(tx, transferIdCounts, isCompact)));

    const existingTable = $.fn.DataTable.isDataTable('#txTable') ? $('#txTable').DataTable() : null;

    // Voller (Neu-)Aufbau nur beim allerersten Laden, wenn die Tabelle komplett leer
    // ist/wird, oder wenn sich Kompakt-/Desktop-Modus geändert hat (andere Spalten-
    // struktur). Sonst: inkrementelles Patchen einzelner Zeilen (siehe unten), damit
    // Scrollposition, aktuelle Seite und aktiver Suchfilter erhalten bleiben.
    const compactChanged = _lastTxIsCompact !== null && _lastTxIsCompact !== isCompact;
    _lastTxIsCompact = isCompact;
    const needsFullRebuild = !existingTable || data.length === 0 || compactChanged;

    if (needsFullRebuild) {
        if (existingTable) existingTable.destroy();

        document.getElementById('txTableBody').innerHTML = data.length
            ? [...newHtmlById.values()].join('')
            : `<tr><td></td><td></td><td></td><td></td><td></td><td class="text-center text-muted py-3">${t('dt.empty')}</td><td></td><td></td><td></td><td></td></tr>`;
        _markDuplicates();

        $('#txTable').DataTable({
            order:      [[2, 'desc']],
            pageLength: 500,
            lengthMenu: [25, 50, 100, 250, 500],
            autoWidth:  false,
            language: {
                search:     t('dt.search'),
                lengthMenu: t('dt.lengthMenu'),
                info:       t('dt.info'),
                paginate:   { previous: t('dt.previous'), next: t('dt.next') }
            },
            columnDefs: [{ orderable: false, targets: [0, 1] }],
            // Jeder Draw (initial, Suche, Solo-Filter, Sortierung) hält die
            // Kartenansicht synchron — siehe _overviewRenderTxCardsIfActive().
            drawCallback: () => _overviewRenderTxCardsIfActive()
        });
        _txRowHtmlById = newHtmlById;
        // Neu aufgebaute Tabelle hat keine mehr angehakten Checkboxen (z.B. nach
        // Bulk-Löschen) — Toolbar-Count/Select-All-Status sonst fälschlich stehen.
        _updateBulkToolbar();
        return;
    }

    // ── Inkrementelles Update ────────────────────────────────
    // Wichtig: DataTables hängt bei aktiver Seitengröße/Suche NUR die gerade sichtbaren
    // Zeilen ins DOM ein — alle anderen Zeilen existieren nur intern (nicht per
    // document.querySelector auffindbar). Deshalb wird hier ausschließlich über die
    // DataTables-Row-API mit einer Funktions-Selektor gesucht (Default-Modifier
    // {page:'all', search:'none'} durchsucht wirklich ALLE Zeilen, unabhängig von
    // aktueller Seite/Filter) — das war der Grund, warum der Puls-Effekt bisher nur
    // "manchmal" auftrat (nämlich nur, wenn die betroffene Zeile zufällig auf der
    // aktuell sichtbaren Seite lag und zum aktiven Suchfilter passte).
    const table = existingTable;
    const changedIds = [];

    function _findTxRowNode(id) {
        const rowApi = table.row((idx, rowData, node) => node && node.getAttribute('data-id') === id);
        return rowApi.any() ? rowApi.node() : null;
    }

    for (const id of _txRowHtmlById.keys()) {
        if (!newHtmlById.has(id)) {
            const node = _findTxRowNode(id);
            if (node) table.row(node).remove();
        }
    }

    for (const [id, html] of newHtmlById) {
        const oldHtml = _txRowHtmlById.get(id);
        if (oldHtml === undefined) {
            // Neue Zeile (Duplikat) — bekommt den Puls, da sie sonst leicht übersehen wird.
            table.row.add($(html)); // Position/Seite regelt draw()
            changedIds.push(id);
        } else if (oldHtml !== html) {
            // Bearbeitete Zeile — nur in-place patchen, bewusst OHNE Puls (siehe changedIds
            // unten). Die Tabelle scrollt/paginiert dabei ohnehin nicht weg, der Nutzer bleibt
            // an der relevanten Stelle, ein zusätzlicher visueller Hinweis ist hier nicht nötig.
            const node = _findTxRowNode(id);
            if (node) {
                _replaceTxRowInPlace(node, html);
                table.row(node).invalidate();
            }
        }
    }

    table.draw(false); // false = aktuelle Seite/Sortierung beibehalten statt auf Seite 1 zu springen
    _markDuplicates();
    _overviewRenderTxCardsIfActive();
    _txRowHtmlById = newHtmlById;
    // Entfernte/neu aufgebaute Zeilen (z.B. nach Bulk-Löschen) haben keine
    // angehakte Checkbox mehr — Toolbar-Count sonst fälschlich stehen (Fund:
    // "2 selected" blieb nach dem Löschen der beiden Transaktionen sichtbar).
    _updateBulkToolbar();

    // Kurzer grüner Fade-Pulse auf geänderten/duplizierten Zeilen (siehe .tx-row-pulse in depot.css).
    // Knoten erst NACH draw() über die DataTables-API erneut auflösen (bei frisch
    // hinzugefügten Zeilen ist der Knoten vor dem Draw ggf. noch nicht zuverlässig
    // verfügbar). Läuft nur sichtbar ab, wenn die Zeile gerade auf der aktuellen Seite
    // angezeigt wird — das ist beabsichtigt, eine Animation auf einer unsichtbaren
    // Zeile wäre ohnehin nicht wahrnehmbar.
    changedIds.forEach(id => {
        const node = _findTxRowNode(id);
        if (!node) return;
        node.classList.add('tx-row-pulse');
        setTimeout(() => node.classList.remove('tx-row-pulse'), 1500);
    });
}

// ── "Alle Transaktionen": Kartenansicht ───────────────────────────────────
// Eigenständige, unabhängige Implementierung (eigener Namensraum, siehe
// depot.css .overview-tx-card). Aktiv, sobald die Kachel sich eine Grid-Row
// mit einer anderen Kachel teilt (siehe updateOverviewRowCols) oder der
// Viewport mobil ist. Die Tabelle (samt DataTables-Suche/Sortierung/Solo-
// Filter) bleibt dabei die ALLEINIGE Wahrheitsquelle — sie wird nur unsicht-
// bar (d-none) geschaltet. Die Karten sind eine reine Anzeige-Ableitung aus
// den aktuell sichtbaren (gefilterten) <tr>-Zeilen in #txTableBody (DataTables
// hängt bei aktivem Filter ohnehin nur die passenden Zeilen ins DOM ein, siehe
// Kommentar in renderTxTable oben). Auswahl-Checkboxen in Karten wirken als
// Fernbedienung auf die zugehörige (unsichtbare) Zeilen-Checkbox — dadurch
// funktionieren Mehrfachauswahl, Bulk-Toolbar und Rechtsklick-Menü unverändert
// in beiden Ansichten, ohne die bestehende Auswahl-Logik zu duplizieren.
const OVERVIEW_TX_MOBILE_BREAKPOINT = 767;
let _overviewTxCardModeActive = false;

function _overviewShouldUseTxCards() {
    if (window.innerWidth <= OVERVIEW_TX_MOBILE_BREAKPOINT) return true;

    const panel = document.getElementById('transactionsPanel');
    const row   = panel ? panel.closest('.overview-grid-row') : null;
    if (!row) return false;
    return row.querySelectorAll('.overview-draggable').length > 1;
}

/** Prüft, ob umgeschaltet werden muss (Layout-Änderung, Resize) und rendert
 *  bei Bedarf die Karten neu. Aufrufstellen: initOverviewLayout, nach jeder
 *  Layout-Änderung (Drag&Drop-Ende, Pfeil-Buttons, Reset) sowie debounced
 *  auf window "resize" (siehe unten). */
function _overviewApplyTxViewMode() {
    const wrap  = document.getElementById('txTableWrap');
    const cards = document.getElementById('txCardsList');
    if (!wrap || !cards) return;

    const useCards = _overviewShouldUseTxCards();
    const changed  = useCards !== _overviewTxCardModeActive;
    _overviewTxCardModeActive = useCards;

    wrap.classList.toggle('d-none', useCards);
    cards.classList.toggle('d-none', !useCards);

    // Bulk-Toolbar: In der Kartenansicht sind die 7 einzelnen Buttons zu breit
    // (die Kachel ist ja gerade deshalb schmal) — dort kompakt als Dropdown +
    // eigene Select-All-Checkbox (Tabellen-Kopfzeile mit #txSelectAll ist in
    // der Kartenansicht unsichtbar). In der Tabellenansicht bleibt es wie
    // gehabt bei den inline Buttons.
    const inline   = document.getElementById('bulkToolbar')?.querySelector('.bulk-actions-inline');
    const dropdown = document.getElementById('bulkToolbar')?.querySelector('.bulk-actions-dropdown');
    if (inline)   inline.classList.toggle('d-none', useCards);
    if (dropdown) dropdown.classList.toggle('d-none', !useCards);

    if (useCards && changed) renderOverviewTxCards();
}

// ── "Wallets & Börsen": Kartenansicht ─────────────────────────────────────
// Eigenständige, unabhängige Implementierung. Anders als bei "Alle Trans-
// aktionen" (jede Row-Teilung genügt) wird hier erst umgeschaltet, wenn die
// Kachel wirklich auf 1 von 3 Slots einer vollen Row gequetscht wird — bei
// nur 2 Kacheln in der Row (halbe Breite) bleibt die schmalere Wallets-
// Tabelle noch als Tabelle lesbar. Da die Positionsliste rein serverseitig
// per Thymeleaf gerendert wird (kein JS-Fetch, Hinzufügen/Bearbeiten/Löschen
// lösen ohnehin einen Seiten-Reload aus) genügt hier ein reines Sichtbar-
// keits-Umschalten zwischen #posTableWrap und #posCardsList — anders als bei
// den Transaktionen ist kein Re-Render/Sync nötig.
function _overviewShouldUsePosCards() {
    if (window.innerWidth <= OVERVIEW_TX_MOBILE_BREAKPOINT) return true;

    const block = document.getElementById('overview-block-wallets');
    const row   = block ? block.closest('.overview-grid-row') : null;
    if (!row) return false;
    // Schwelle bewusst bei 2 (statt OVERVIEW_MAX_COLS) — seit Kennzahlen/Donut auf
    // die Bestandsansicht verschoben wurden, gibt es auf der Übersicht nur noch 2
    // Kacheln insgesamt (Wallets + Transaktionen), eine Row kann also nie mehr 3
    // erreichen. Trigger daher: sobald sich Wallets die Row mit irgendeiner
    // anderen Kachel teilt (nicht mehr erst bei voller 3er-Row).
    return row.querySelectorAll('.overview-draggable').length >= 2;
}

function _overviewApplyPosViewMode() {
    const wrap  = document.getElementById('posTableWrap');
    const cards = document.getElementById('posCardsList');
    if (!wrap || !cards) return;

    const useCards = _overviewShouldUsePosCards();
    wrap.classList.toggle('d-none', useCards);
    cards.classList.toggle('d-none', !useCards);
}

// ── "Import-Historie": Kartenansicht ──────────────────────────────────────
// Gleiches Muster wie bei Wallets/Börsen oben: rein serverseitig gerendert
// (Thymeleaf, kein JS-Fetch), Umschalten genügt daher als reines Sichtbarkeits-
// Toggle zwischen #importHistoryTableWrap und #importHistoryCardsList — Listen-
// ansicht, sobald die Kachel allein in ihrer Row steht, sonst Kartenansicht.
function _overviewShouldUseHistoryCards() {
    if (window.innerWidth <= OVERVIEW_TX_MOBILE_BREAKPOINT) return true;

    const block = document.getElementById('overview-block-import-history');
    const row   = block ? block.closest('.overview-grid-row') : null;
    if (!row) return false;
    return row.querySelectorAll('.overview-draggable').length >= 2;
}

function _overviewApplyHistoryViewMode() {
    const wrap  = document.getElementById('importHistoryTableWrap');
    const cards = document.getElementById('importHistoryCardsList');
    if (!wrap || !cards) return;

    const useCards = _overviewShouldUseHistoryCards();
    wrap.classList.toggle('d-none', useCards);
    cards.classList.toggle('d-none', !useCards);
}

/** Von renderTxTable()/drawCallback nach jedem Tabellen-Redraw aufgerufen,
 *  damit die Kartenansicht (falls gerade aktiv) synchron bleibt. */
function _overviewRenderTxCardsIfActive() {
    if (_overviewTxCardModeActive) renderOverviewTxCards();
}

function renderOverviewTxCards() {
    const container = document.getElementById('txCardsList');
    if (!container) return;

    const rows = document.querySelectorAll('#txTableBody tr[data-id]');
    if (!rows.length) {
        container.innerHTML = `<div class="overview-tx-cards-empty">${esc(t('dt.empty'))}</div>`;
        return;
    }

    const txById = new Map((_lastTxData || []).map(tx => [String(tx.id), tx]));
    container.innerHTML = Array.from(rows)
        .map(row => {
            const tx = txById.get(row.dataset.id);
            return tx ? _buildTxCardHtml(tx, row) : '';
        })
        .join('');

    // Safari/WebKit-Reflow-Fix: Wird dieser Container im selben Zug erst von
    // d-none auf sichtbar geschaltet UND befüllt (genau der Fall beim allerersten
    // Rendern nach dem Laden auf dem Handy), berechnet WebKit das Flex-Layout der
    // neu eingefügten Karten manchmal nicht sofort korrekt — Labels erscheinen,
    // Werte bleiben bis zur nächsten Layout-Änderung (z.B. Klick) leer/unsichtbar.
    // Erzwungenes Reflow durch Lesen von offsetHeight behebt das zuverlässig.
    void container.offsetHeight;
}

const OVERVIEW_TX_BADGE_MAP = {
    BUY:          { cls: 'type-buy',          key: 'flow.legend.buy' },
    SELL:         { cls: 'type-sell',         key: 'flow.legend.sell' },
    TRANSFER_IN:  { cls: 'type-transfer-in',  key: 'flow.panel.transferIn' },
    TRANSFER_OUT: { cls: 'type-transfer-out', key: 'flow.panel.transferOut' }
};

/** Baut eine Karte aus den Roh-Transaktionsdaten (für Inhalt/Formatierung —
 *  eigenständige Berechnung, analog zu _buildTxRowHtml) plus der zugehörigen,
 *  bereits gerenderten <tr> (für Auswahl-/Status-Zustand: checked/selected/
 *  warning/warning-duplicate/solo-transfer/last-import — so bleibt die Karte
 *  immer exakt konsistent mit dem, was die Tabelle aktuell anzeigt). */
function _buildTxCardHtml(tx, row) {
    const date    = tx.date ? tx.date.replace('T', ' ').substring(0, 19) : '–';
    const checked = row.querySelector('.tx-row-check')?.checked ? 'checked' : '';
    const statusClass = ['warning', 'warning-duplicate', 'solo-transfer', 'last-import']
        .filter(c => row.classList.contains(c)).join(' ');
    const selectedClass = row.classList.contains('selected') ? ' selected' : '';

    const badgeInfo = OVERVIEW_TX_BADGE_MAP[tx.type];
    const badge = badgeInfo
        ? `<span class="overview-tx-card-badge ${badgeInfo.cls}">${esc(t(badgeInfo.key))}</span>`
        : '';

    let earning = '–';
    let posNeg  = '';
    if (tx.type === 'BUY') {
        let paid;
        if (tx.currency !== CURRENCY.current()) {
            earning = (CURRENT_PRICE - ((tx.pricePerBtc + tx.fees) * tx.exchangeRate)) * tx.quantity;
            paid = (tx.quantityFiat + tx.fees) * tx.exchangeRate;
        } else {
            earning = (CURRENT_PRICE - ((tx.pricePerBtc + tx.fees))) * tx.quantity;
            paid = (tx.quantityFiat + tx.fees);
        }
        const percentage = (100 / paid * (paid + earning)) - 100;
        earning = formatEur(earning) + ' (' + truncateTwoDecimals(percentage) + '%)';
        posNeg = earning.startsWith('-') ? 'text-neg' : 'text-pos';
    }

    const priceLine = tx.pricePerBtc != null
        ? _overviewTxFieldRow(t('table.col.pricePerBtc'),
            tx.currency !== CURRENCY.current() ? formatEur(tx.pricePerBtc * tx.exchangeRate) : formatEur(tx.pricePerBtc))
        : '';
    const totalLine = tx.quantityFiat != null
        ? _overviewTxFieldRow(t('table.col.total'),
            tx.currency !== CURRENCY.current()
                ? formatEur((tx.quantityFiat + tx.fees) * tx.exchangeRate) + ` <span style="font-size:.62rem">[${esc(tx.currency)} × ${tx.exchangeRate}]</span>`
                : formatEur((tx.quantityFiat + tx.fees)))
        : '';
    const glLine = tx.type === 'BUY' && tx.quantityFiat != null
        ? _overviewTxFieldRow(t('table.col.gl'), `<span class="${posNeg}">${earning}</span>`)
        : '';
    const transferLine = tx.transferId
        ? _overviewTxFieldRow(t('table.col.transferId'), esc(tx.transferId.substring(0, 8)) + '…', tx.transferId)
        : '';
    // Kommentar kann beliebig lang sein — rechtsbündig (wie die übrigen Felder)
    // hat er die Kachel unnötig in die Breite gezogen, da eine lange Zeile ohne
    // Umbruch den Flex-Container aufweitet. Daher: fest auf 20 Zeichen kürzen
    // (mit …, voller Text im title-Tooltip) UND linksbündig statt rechtsbündig
    // darstellen (siehe .overview-tx-field-value.align-left in depot.css).
    const commentText = tx.comment && tx.comment.length > 20 ? tx.comment.substring(0, 20) + '…' : tx.comment;
    const commentLine = tx.comment
        ? _overviewTxFieldRow(t('modal.field.comment'), esc(commentText), tx.comment, true)
        : '';

    const txJson = JSON.stringify(tx).replace(/"/g, '&quot;');

    return `<div class="overview-tx-card ${statusClass}${selectedClass}" data-id="${tx.id}" onclick="_overviewToggleTxCard(this)">
        <div class="overview-tx-card-head">
            <span class="overview-tx-card-head-label">
                <input type="checkbox" class="tx-card-check" data-id="${tx.id}" ${checked}
                       style="accent-color:var(--accent)" onclick="event.stopPropagation()"
                       onchange="_overviewOnTxCardCheckChange(this)"/>
                <span>${esc(tx.positionLabel || '–')}</span>
            </span>
            <span class="overview-tx-card-actions" onclick="event.stopPropagation()">
                ${badge}
                <button type="button" class="btn btn-xs depot-btn-icon" title="Edit"
                        onclick="openEditTx(${txJson})">
                    <i class="bi bi-pencil"></i>
                </button>
                <button type="button" class="btn btn-xs depot-btn-icon" title="Copy"
                        onclick="openAddTx(${txJson})">
                    <i class="bi bi-copy"></i>
                </button>
                <button type="button" class="btn btn-xs depot-btn-icon text-neg" title="Delete"
                        onclick="deleteTx(${tx.id})">
                    <i class="bi bi-trash"></i>
                </button>
            </span>
        </div>
        ${_overviewTxFieldRow(t('table.col.date'), date)}
        ${_overviewTxFieldRow(t('table.col.btc'), fmt8(tx.quantity))}
        ${priceLine}${totalLine}${glLine}${transferLine}${commentLine}
    </div>`;
}

function _overviewTxFieldRow(label, value, title, alignLeft) {
    return `<div class="overview-tx-field">
        <span class="overview-tx-field-label">${esc(label)}</span>
        <span class="overview-tx-field-value${alignLeft ? ' align-left' : ''}"${title ? ` title="${esc(title)}"` : ''}>${value}</span>
    </div>`;
}

/** Fernbedienung: Karten-Checkbox-Änderung auf die tatsächliche (unsichtbare)
 *  Zeilen-Checkbox übertragen und dort ein echtes change-Event feuern — der
 *  bestehende globale change-Listener (siehe unten) übernimmt danach ganz
 *  normal .selected-Klasse + _updateBulkToolbar(), unverändert. */
function _overviewOnTxCardCheckChange(cb) {
    const id = cb.dataset.id;
    const rowCb = document.querySelector(`#txTableBody tr[data-id="${id}"] .tx-row-check`);
    if (rowCb) {
        rowCb.checked = cb.checked;
        rowCb.dispatchEvent(new Event('change', { bubbles: true }));
    }
    cb.closest('.overview-tx-card')?.classList.toggle('selected', cb.checked);
}

function _overviewToggleTxCard(cardEl) {
    const cb = cardEl.querySelector('.tx-card-check');
    if (!cb) return;
    cb.checked = !cb.checked;
    _overviewOnTxCardCheckChange(cb);
}

/** Re-merged Wallets-/Transaktions-Row bei Bedarf nach einem Resize (z.B.
 *  Fenster von Desktop- auf Tablet/Phone-Breite verkleinert, ohne Reload) —
 *  ohne Reload würde sonst ein bereits im DOM getrenntes Layout stehen
 *  bleiben, bis die Seite neu geladen wird. Kein Effekt, wenn schon gemergt
 *  oder auf Desktop-Breite. */
function _overviewReapplyMobileRowConstraint() {
    const grid = document.getElementById('overviewGrid');
    if (!grid || !_isOverviewMobileLayout()) return;

    const rows   = Array.from(grid.querySelectorAll('.overview-grid-row'));
    const layout = rows.map(r => Array.from(r.querySelectorAll('.overview-draggable')).map(el => el.id));
    const merged = _enforceOverviewMobileRowMerge(layout);
    if (JSON.stringify(merged) === JSON.stringify(layout)) return;

    applyOverviewLayout(grid, merged);
    updateOverviewRowCols(grid);
    _overviewTriggerChartResize();
    _saveOverviewLayout(grid);
}

(function initOverviewTxCardMode() {
    let _resizeTimeout = null;
    window.addEventListener('resize', () => {
        clearTimeout(_resizeTimeout);
        _resizeTimeout = setTimeout(() => {
            _overviewApplyTxViewMode();
            _overviewApplyPosViewMode();
            _overviewApplyHistoryViewMode();
            _overviewReapplyMobileRowConstraint();
        }, 150);
    });
})();

// ── Sichtbares Suchfeld in der Bulk-Toolbar (Transaktionstabelle) ──────────
// Das native DataTables-Suchfeld liegt INNERHALB von #txTable_wrapper, das
// selbst scrollt (max-height + overflow-y:auto) — sticky hat dort nicht
// zuverlässig funktioniert. Workaround: natives Feld per CSS ausgeblendet
// (siehe #txTable_wrapper .dt-search in depot.css), stattdessen dieses Feld
// hier oben in der Bulk-Actions-Toolbar, die IMMER sichtbar bleibt, weil sie
// außerhalb von #txTable_wrapper liegt. Steuert die eigentliche DataTables-
// Suche direkt über die API und hält das (verstecktes) native Feld nur der
// Vollständigkeit halber im Wert synchron.
function onTxVisibleSearchInput(value) {
    const hiddenInput = document.querySelector('#txTable_wrapper .dt-search input');
    if (hiddenInput) hiddenInput.value = value;

    document.getElementById('txSearchVisibleClear')?.classList.toggle('d-none', !value);

    if ($.fn.DataTable.isDataTable('#txTable')) {
        $('#txTable').DataTable().search(value).draw();
    }
}

function clearTxVisibleSearch() {
    const input = document.getElementById('txSearchVisible');
    if (input) input.value = '';
    onTxVisibleSearchInput('');
    input?.focus();
}

// Gleicher Workaround wie oben, für die Positions-/Wallets-Tabelle (#posTable).
// Filtert zusätzlich die Kartenansicht mit (siehe _applyPosCardFilters) — die
// Karten sind serverseitig gerendert und wurden von der DataTables-Suche bisher
// gar nicht erfasst, dadurch ging die Suche auf Tablet/Phone (dort aktive
// Kartenansicht) faktisch ins Leere.
function onPosVisibleSearchInput(value) {
    const hiddenInput = document.querySelector('#posTable_wrapper .dt-search input');
    if (hiddenInput) hiddenInput.value = value;

    document.getElementById('posSearchVisibleClear')?.classList.toggle('d-none', !value);

    if ($.fn.DataTable.isDataTable('#posTable')) {
        $('#posTable').DataTable().search(value).draw();
    }

    _posSearchTerm = value || '';
    _applyPosCardFilters();
}

function clearPosVisibleSearch() {
    const input = document.getElementById('posSearchVisible');
    if (input) input.value = '';
    onPosVisibleSearchInput('');
    input?.focus();
}

// updateRelevantFields(), _loadPositionsDropdown(), openAddTx(), openEditTx()
// and saveOrAddTx() now live in tx-form.js (shared with the Flow Diagram page).
// saveOrAddTx() calls onTxSaved() after a successful save — this page's hook:
function onTxSaved() {
    txLoaded = false;
    loadTransactions();
}

async function removeAll() {
    if (!await showConfirm(t('nav.btn.removeAll'), t('confirm.deleteAll'))) return;
    fetch('/api/btc-tracking/', { method: 'DELETE' })
        .then(() => {
            txLoaded = false;
            loadTransactions();
            showToast('✓ ' + t('toast.allDeleted'), 'success');
            setTimeout(() => window.location.reload(), 1800);
        })
        .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

async function deleteTx(id) {
    if (!await showConfirm(t('table.action.delete'), t('confirm.deleteTx'))) return;
    fetch('/api/btc-tracking/transactions/' + id, { method: 'DELETE' })
        .then(() => {
            txLoaded = false;
            loadTransactions();
            showToast('✓ ' + t('toast.txDeleted'), 'success');
        })
        .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

function confirmDeletePosition(id) {
    showConfirm(t('table.action.delete'), t('confirm.deletePosition')).then(ok => {
        if (!ok) return;
        fetch('/api/btc-tracking/positions/' + id, { method: 'DELETE' })
            .then(r => r.json())
            .then(data => {
                if (data.error) { showToast('✗ ' + data.error, 'error'); return; }
                _positionsCache = null;
                showToast('✓ ' + t('toast.deletePosition.success'), 'success');
                setTimeout(() => window.location.reload(), 600);
            })
            .catch(err => showToast('✗ ' + err.message, 'error'));
    });
}

function fmt8(val) {
    if (val == null) return '–';
    return Number(val).toLocaleString('de-DE', {
        minimumFractionDigits: 8,
        maximumFractionDigits: 8
    });
}

// ── CSV Import – PapaParse + Mapping Modal ────────────────

const csvImport = { rawData: [], headers: [] };

// ── CSV Import – neuer 3-Step-Assistent (ersetzt importCsv() unten für
// normale .csv-Dateien; importCsv() bleibt unverändert im Code, wird aber
// nicht mehr aufgerufen — .enc-Importe laufen weiterhin unverändert über
// _importEnc(), siehe unten). Datei-Upload läuft als echtes Formular-POST an
// die Step-1-Route (siehe Absprache: serverseitiges Parsen ersetzt PapaParse
// für den Upload-Schritt) statt per fetch(), damit der Browser direkt auf die
// gerenderte Mapping-Seite navigiert. ──
function onCsvFileSelected(input) {
    const file = input.files[0];
    if (!file) return;
    input.value = '';

    if (file.name.toLowerCase().endsWith('.enc')) {
        _importEnc(file);
        return;
    }

    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '/btc-tracking/import/mapping';
    form.enctype = 'multipart/form-data';
    form.style.display = 'none';

    const fileInput = document.createElement('input');
    fileInput.type = 'file';
    fileInput.name = 'file';
    const dt = new DataTransfer();
    dt.items.add(file);
    fileInput.files = dt.files;

    form.appendChild(fileInput);
    document.body.appendChild(form);
    form.submit();
}

function importCsv(input) {
    const file = input.files[0];
    if (!file) return;
    input.value = '';
 
    // Encrypted export: skip column mapping, ask for password
    if (file.name.endsWith('.enc')) {
        _importEnc(file);
        return;
    }
 
    // Plain CSV: existing PapaParse + column mapping flow
	Papa.parse(file, {
	    header:         false,   // ← wichtig: kein automatisches Header-Parsing
	    skipEmptyLines: true,
	    quoteChar:      '"',
	    complete(results) {
	        if (!results.data || results.data.length < 2) {
	            showToast('✗ ' + t('toast.csvEmpty'), 'error');
	            return;
	        }

	        // Erste Zeile = Header, doppelte Namen mit Suffix versehen
	        const rawHeaders = results.data[0];
	        const seen = {};
	        const headers = rawHeaders.map(h => {
	            const key = h.trim();
	            if (seen[key] === undefined) {
	                seen[key] = 0;
	                return key;
	            } else {
	                seen[key]++;
	                return key + '_' + seen[key];
	            }
	        });

	        // Restliche Zeilen als Objekte mappen
	        const data = results.data.slice(1).map(row => {
	            const obj = {};
	            headers.forEach((h, i) => { obj[h] = row[i] || ''; });
	            return obj;
	        });

	        csvImport.rawData = data;
	        csvImport.headers = headers;
	        openMappingModal();
	    },
	    error(err) {
	        showToast('✗ ' + t('toast.csvError') + ': ' + err.message, 'error');
	    }
	});
}

function openMappingModal() {
    const headers = csvImport.headers;
    const NONE    = `<option value="">${t('modal.csv.field.notMapped')}</option>`;

	
	
    const FIELDS = [
        { id: 'map_typ',          label: I18N.t('table.col.type'),                			required: true  },
        { id: 'map_date',         label: I18N.t('table.col.date'),               			required: true  },
		{ id: 'map_time',         label: I18N.t('csv.import.mapping.time'),      			required: false },
        { id: 'map_exchange',     label: I18N.t('table.wallets'),           				required: true  },
        { id: 'map_buyQty',       label: I18N.t('csv.import.mapping.buy.quantity'),        	required: true },
        { id: 'map_buyCur',       label: I18N.t('csv.import.mapping.buy.currency'),        	required: true },
        { id: 'map_sellQty',      label: I18N.t('csv.import.mapping.sell.quantity'),       	required: true },
        { id: 'map_sellCur',      label: I18N.t('csv.import.mapping.sell.currency'),       	required: true },
        { id: 'map_fee',          label: I18N.t('table.col.fees'),                			required: false },
		{ id: 'map_feeCur',       label: I18N.t('csv.import.mapping.fee.currency'),        	required: false },
        { id: 'map_exchangeRate', label: I18N.t('csv.import.mapping.fee.exchange.rate'), 		required: false },
        { id: 'map_comment',      label: I18N.t('modal.field.comment'),            			required: false },
		{ id: 'map_transactionId',label: I18N.t('modal.field.transaction.id'),            	required: false },
		{ id: 'map_transferId',   label: I18N.t('modal.field.transfer.id'),               	required: false },

		 ]

	 const FIELD_ALIASES = {
	     map_typ:          ['Typ', 'typ', 'type', 'Type'],
	     map_date:         ['Datum', 'datum', 'date', 'Date', 'Datetime'],
		 map_time:         ['Time', 'time', 'Zeit', 'Uhrzeit'],
	     map_exchange:     ['Börse', 'boerse', 'exchange', 'Exchange', 'Börsen'],
	     map_buyQty:       ['Kauf', 'kauf', 'buyQuantity', 'Buy Amount', 'buy_quantity', 'buy', 'buyQty', 'Amount'],
	     map_buyCur:       ['Cur.', 'Cur._1', 'cur._1', 'buyCurrency', 'Buy Currency', 'buyCur', 'Amount unit'],
	     map_sellQty:      ['Verkauf', 'verkauf', 'sellQuantity', 'Sell Amount', 'sell_quantity', 'sell', 'sellQty', 'Amount'],
	     map_sellCur:      ['Cur._1', 'Cur._2', 'cur._2', 'sellCurrency', 'Sell Currency', 'sellCur', 'Amount unit'],
	     map_fee:          ['Gebühr', 'gebuehr', 'fee', 'Fee', 'fees', 'Fees', 'Fee'],
	     map_feeCur:       ['Cur._2', 'Cur._3', 'cur._3', 'feeCurrency', 'Fee Currency', 'feecur.', 'feeCur', 'Fee unit'],
	     map_exchangeRate: ['exchangeRate', 'exchange_rate', 'Wechselkurs'],
	     map_comment:      ['Kommentar', 'kommentar', 'comment', 'Comment'],
	     map_transactionId:['transactionId'],
		 map_transferId:   ['transferId'],

	 };

	 const autoMatch = (fieldId) => {
	     const aliases = FIELD_ALIASES[fieldId] || [];
	     return headers.find(h => aliases.includes(h)) || '';
	 };
	 
    const mappingRows = FIELDS.map(f => {
		const matched = autoMatch(f.id);
	    const opts    = NONE + headers.map(h =>
	        `<option value="${esc(h)}" ${h === matched ? 'selected' : ''}>${esc(h)}</option>`
	    ).join('');

		const extraOnchange =
	        f.id === 'map_typ' ? 'refreshTypRemap();' :
	        (f.id === 'map_date' || f.id === 'map_time') ? 'validateDateTimeMapping();' : '';

	    let row = `
			<tr>
		        <td class="depot-label pt-2" style="width:160px;white-space:nowrap">
		            ${f.label}${f.required ? ' <span style="color:var(--neg)">*</span>' : ''}
		        </td>
		        <td>
		            <select id="${f.id}" class="form-select depot-input form-select-sm"
		                    onchange="${extraOnchange}">
		                ${opts}
		            </select>
		        </td>
		    </tr>`;

	    if (f.id === 'map_exchange') {
	        row += `
	        <tr>
	            <td class="depot-label pt-2" style="width:160px;white-space:nowrap">
	                ${t('csv.import.fixedExchange')}
	            </td>
	            <td>
	                <select id="map_exchangeFixed" class="form-select depot-input form-select-sm mb-1"
	                        onchange="onFixedExchangeChange()">
	                    <option value="">${t('csv.import.fixedExchange.none')}</option>
	                </select>
	                <input type="text" id="map_exchangeFixedNew" class="form-control depot-input d-none"
	                       placeholder="New position name" maxlength="100"/>
	                <div class="form-text text-muted" style="font-size:.7rem">${t('csv.import.fixedExchange.hint')}</div>
	            </td>
	        </tr>`;
	    }
	    return row;
	}).join('');

	const body = `
	    <p style="font-size:.75rem;color:var(--text-muted);margin-bottom:1rem">
	        <strong style="color:var(--text)">${csvImport.rawData.length}</strong>
	        ${t('modal.csv.rowsDetected')}
	    </p>
	    <div id="dateTimeWarning" class="d-none"
	         style="border-left:3px solid var(--neg);padding:.5rem .75rem;margin-bottom:1rem;
	                font-size:.75rem;color:var(--neg);background:rgba(216,90,48,.08)">
	        <i class="bi bi-exclamation-triangle me-1"></i>${t('csv.import.dateTimeWarning')}
	    </div>
	    <table style="width:100%;border-spacing:0 6px">${mappingRows}</table>
	    <hr style="border-color:var(--border);margin:1.25rem 0"/>
	    <div class="depot-card-header mb-2">${t('modal.csv.typMapping')}</div>
	    <p style="font-size:.72rem;color:var(--text-muted);margin-bottom:.75rem">${t('modal.csv.typHint')}</p>
	    <div id="typRemapContainer"></div>`;

	document.getElementById('csvMappingBody').innerHTML = body;
	setTimeout(() => { refreshTypRemap(); _loadFixedExchangeDropdown(); validateDateTimeMapping(); }, 0);

    const el    = document.getElementById('csvMappingModal');
    const modal = bootstrap.Modal.getInstance(el) || new bootstrap.Modal(el);
    modal.show();
}

const INTERNAL_TYPES = ['Trade', 'Einzahlung', 'Auszahlung', 'Selbst'];

const TYP_VALUE_ALIASES = {
    'RECV': 'Einzahlung',
    'SENT': 'Auszahlung',
    'SELF': 'Selbst'
};

function refreshTypRemap() {
    const typColEl  = document.getElementById('map_typ');
    if (!typColEl) return;

    const typCol    = typColEl.value;
    const container = document.getElementById('typRemapContainer');

    if (!typCol) {
        container.innerHTML = `<p style="font-size:.72rem;color:var(--text-muted)">${t('modal.csv.selectTypFirst')}</p>`;
        return;
    }

    const distinctVals = [...new Set(
        csvImport.rawData.map(r => (r[typCol] || '').trim()).filter(Boolean)
    )].sort();

    if (!distinctVals.length) {
        container.innerHTML = `<p style="font-size:.72rem;color:var(--text-muted)">${t('modal.csv.noValues')}</p>`;
        return;
    }

    const rows = distinctVals.map(val => {
        const preselect = TYP_VALUE_ALIASES[val.toUpperCase()] ||
                           (INTERNAL_TYPES.includes(val) ? val : '');
        const opts = `<option value="">${t('modal.csv.field.ignore')}</option>` +
            INTERNAL_TYPES.map(tp =>
                `<option value="${tp}" ${tp === preselect ? 'selected' : ''}>${tp}</option>`
            ).join('');
        return `
        <tr>
            <td style="width:160px;font-size:.78rem;color:var(--text);padding:.3rem 0">
                <code style="background:var(--bg);padding:2px 6px;border-radius:3px">${esc(val)}</code>
            </td>
            <td style="padding:.3rem 0 .3rem .75rem">
                <i class="bi bi-arrow-right" style="color:var(--text-muted);margin-right:.5rem;font-size:.7rem"></i>
                <select class="form-select depot-input form-select-sm d-inline-block"
                        style="width:auto;min-width:140px"
                        data-typ-source="${esc(val)}">
                    ${opts}
                </select>
            </td>
        </tr>`;
    }).join('');

    container.innerHTML = `<table style="width:100%;border-spacing:0 2px">${rows}</table>`;
}

function _loadFixedExchangeDropdown() {
    const sel = document.getElementById('map_exchangeFixed');
    if (!sel) return;
    fetch('/api/btc-tracking/positions')
        .then(r => r.json())
        .then(data => {
            _positionsCache = data;
            sel.innerHTML =
                `<option value="">${t('csv.import.fixedExchange.none')}</option>` +
                data.map(p => `<option value="${esc(p.label)}">${esc(p.label)}</option>`).join('') +
                '<option value="__new__">＋ New position...</option>';
        });
}

function onFixedExchangeChange() {
    const sel         = document.getElementById('map_exchangeFixed');
    const newInput    = document.getElementById('map_exchangeFixedNew');
    const exchangeSel = document.getElementById('map_exchange');
    const isNew       = sel.value === '__new__';

    newInput.classList.toggle('d-none', !isNew);
    if (isNew) newInput.focus();

    exchangeSel.disabled = sel.value !== '';
}

function validateDateTimeMapping() {
    const dateSel = document.getElementById('map_date');
    const timeSel = document.getElementById('map_time');
    const warningEl = document.getElementById('dateTimeWarning');
    const importBtn = document.getElementById('csvImportConfirmBtn');
    if (!dateSel) return;

    const dateCol = dateSel.value;
    const timeCol = timeSel ? timeSel.value : '';

    let needsTime = false;
    if (dateCol) {
        const sampleRow = csvImport.rawData.find(r => (r[dateCol] || '').trim());
        const sample = sampleRow ? (sampleRow[dateCol] || '').trim() : '';
        if (sample && !sample.includes(':')) {
            needsTime = true;
        }
    }

    const blocked = needsTime && !timeCol;

    if (warningEl) warningEl.classList.toggle('d-none', !blocked);
    if (importBtn) importBtn.disabled = blocked;
}

/** Extrahiert nur den Zeit-Anteil (HH:MM oder HH:MM:SS), ignoriert Zeitzonen-Suffixe wie "GMT+1" */
function _extractTimeValue(timeVal) {
    if (!timeVal) return '';
    const match = timeVal.match(/\d{1,2}:\d{2}(:\d{2})?/);
    return match ? match[0] : '';
}

function confirmCsvImport() {
    const mapping = {
        typ:          document.getElementById('map_typ')?.value,
        date:         document.getElementById('map_date')?.value,
		time:         document.getElementById('map_time')?.value,
        exchange:     document.getElementById('map_exchange')?.value,
        buyQty:       document.getElementById('map_buyQty')?.value,
        buyCur:       document.getElementById('map_buyCur')?.value,
        sellQty:      document.getElementById('map_sellQty')?.value,
        sellCur:      document.getElementById('map_sellCur')?.value,
        fee:          document.getElementById('map_fee')?.value,
		feeCur:       document.getElementById('map_feeCur')?.value,
        exchangeRate: document.getElementById('map_exchangeRate')?.value,
        comment:      document.getElementById('map_comment')?.value,
		transactionId:      document.getElementById('map_transactionId')?.value,
		transferId:         document.getElementById('map_transferId')?.value,

    };

    const fixedSel    = document.getElementById('map_exchangeFixed');
    const fixedActive = fixedSel && fixedSel.value !== '';
    const fixedExchange = fixedActive
        ? (fixedSel.value === '__new__'
            ? (document.getElementById('map_exchangeFixedNew').value || '').trim()
            : fixedSel.value)
        : '';

    const missing = [];
    if (!mapping.typ)  missing.push('typ');
    if (!mapping.date) missing.push('date');
    if (fixedActive ? !fixedExchange : !mapping.exchange) missing.push('exchange');
    if (missing.length) {
        showToast('✗ ' + t('toast.csvValidation') + ': ' + missing.join(', '), 'error');
        return;
    }

    const typRemap = {};
    document.querySelectorAll('#typRemapContainer [data-typ-source]').forEach(sel => {
        const src = sel.getAttribute('data-typ-source');
        const dst = sel.value;
        if (dst) typRemap[src] = dst;
    });

    const rows = csvImport.rawData.map(r => {
        const rawTyp    = mapping.typ ? (r[mapping.typ] || '').trim() : '';
        const mappedTyp = typRemap[rawTyp] || null;
        if (!mappedTyp) return null;

		let dateValue = mapping.date ? (r[mapping.date] || '').trim() : null;
        if (mapping.time && dateValue) {
            const rawTime   = (r[mapping.time] || '').trim();
            const cleanTime = _extractTimeValue(rawTime);
            if (cleanTime) dateValue = dateValue + ' ' + cleanTime;
        }
				
        return {
            typ:          mappedTyp,
			date:         dateValue,
            exchange:     fixedActive ? fixedExchange : (mapping.exchange ? (r[mapping.exchange] || '').trim() : null),
            buyQuantity:  mapping.buyQty       ? (r[mapping.buyQty]       || '').trim() : null,
            buyCurrency:  mapping.buyCur       ? (r[mapping.buyCur]       || '').trim() : null,
            sellQuantity: mapping.sellQty      ? (r[mapping.sellQty]      || '').trim() : null,
            sellCurrency: mapping.sellCur      ? (r[mapping.sellCur]      || '').trim() : null,
            fee:          mapping.fee          ? (r[mapping.fee]          || '').trim() : null,
            feeCurrency:  mapping.feeCur       ? (r[mapping.feeCur]       || '').trim() : null,
            exchangeRate: mapping.exchangeRate ? (r[mapping.exchangeRate] || '').trim() : null,
            comment:      mapping.comment      ? (r[mapping.comment]      || '').trim() : null,
            transactionId:mapping.transactionId? (r[mapping.transactionId]|| '').trim() : null,
            transferId:   mapping.transferId   ? (r[mapping.transferId]   || '').trim() : null,
        };
    }).filter(Boolean);

    if (!rows.length) {
        showToast('✗ ' + t('toast.csvNoRows'), 'error');
        return;
    }

    bootstrap.Modal.getInstance(document.getElementById('csvMappingModal'))?.hide();
    showToast('⏳ ' + t('toast.importProgress'), '');

    fetch('/api/btc-tracking/import-mapped', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ rows })
    })
    .then(r => r.json())
    .then(data => {
        if (data.error) {
            showToast('✗ ' + t('toast.importError') + ': ' + data.error, 'error');
        } else {
			sessionStorage.setItem('depot-lastimport-ids', JSON.stringify(data.lastImportIds || []));
			let toastText = '';
			let toastType = 'success';
			if (data.inserted > 0) {
				toastText = '✓ ' + data.inserted + ' ' + t('toast.importSuccess') + '\n';
			}
			if (data.ignoredByTransactionId) {
				toastText += '✗ ' + t('toast.import.transaction.already', { COUNT: data.ignoredByTransactionId }) + '\n';
				toastType = 'warning';
			}
			if(data.duplicateIds.length > 0) {
				toastText += '✗ ' + t('toast.importDuplicates', { COUNT: data.duplicateIds.length });
				toastType = 'warning';
			}
					
	        showToast(toastText, toastType);
			if (data.inserted > 0) {				
	            setTimeout(() => window.location.reload(), 1800);
			}
        }
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function openDbExportModal() {
    document.getElementById('dbExportPassword').value = '';
    document.getElementById('dbExportPasswordConfirm').value = '';
    (bootstrap.Modal.getInstance(document.getElementById('dbExportModal'))
        || new bootstrap.Modal(document.getElementById('dbExportModal'))).show();
}

function doDbExport() {
    const pw  = document.getElementById('dbExportPassword').value;
    const pw2 = document.getElementById('dbExportPasswordConfirm').value;
    if (pw && pw !== pw2) {
        showToast('✗ ' + t('toast.error') + ': passwords do not match', 'error');
        return;
    }
    bootstrap.Modal.getInstance(document.getElementById('dbExportModal'))?.hide();

    fetch('/api/btc-tracking/export-full', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: pw || null })
    })
    .then(async response => {
        if (!response.ok) {
            const err = await response.json().catch(() => ({ error: 'Export failed' }));
            throw new Error(err.error || 'Export failed');
        }
        const disposition = response.headers.get('Content-Disposition') || '';
        const filename = disposition.includes('filename=')
            ? disposition.split('filename=')[1].replace(/"/g, '')
            : (pw ? 'btc-tracking_full_export.json.enc' : 'btc-tracking_full_export.json');
        const blob = await response.blob();
        const url  = URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        URL.revokeObjectURL(url);
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

function onDbImportFileSelected(input) {
    const file = input.files[0];
    if (!file) return;
    window._pendingDbImportFile = file;
    input.value = '';
    document.getElementById('dbImportPassword').value = '';
    (bootstrap.Modal.getInstance(document.getElementById('dbImportModal'))
        || new bootstrap.Modal(document.getElementById('dbImportModal'))).show();
}

function confirmDbImport() {
    const file = window._pendingDbImportFile;
    if (!file) return;
    const password = document.getElementById('dbImportPassword').value;

    bootstrap.Modal.getInstance(document.getElementById('dbImportModal'))?.hide();
    showToast('⏳ ' + t('toast.importProgress'), '');

    const formData = new FormData();
    formData.append('file', file);
    if (password) formData.append('password', password);

    fetch('/api/btc-tracking/import-full', { method: 'POST', body: formData })
        .then(r => r.json())
        .then(data => {
            if (data.error) {
                showToast('✗ ' + t('toast.importError') + ': ' + data.error, 'error');
            } else {
                showToast('✓ ' + t('toast.dbImportSuccess', { POS: data.positions, TX: data.transactions }), 'success');
                setTimeout(() => window.location.reload(), 1800);
            }
        })
        .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

// ── Position Modal ────────────────────────────────────────
let _positionModal = null;

async function openPositionModal(id) {
    document.getElementById('positionId').value    = id || '';
    document.getElementById('positionLabel').value = '';
    document.getElementById('positionType').value  = 'EXCHANGE';

    const titleEl = document.getElementById('positionModalTitle');
    if (id) {
        titleEl.setAttribute('data-i18n', 'form.position.titleEdit');
        titleEl.textContent = t('form.position.titleEdit');
        const data = await fetch('/api/btc-tracking/positions/' + id).then(r => r.json());
        document.getElementById('positionLabel').value = data.label || '';
        document.getElementById('positionType').value  = data.type  || 'EXCHANGE';
    } else {
        titleEl.setAttribute('data-i18n', 'form.position.titleNew');
        titleEl.textContent = t('form.position.titleNew');
    }

    if (!_positionModal) _positionModal = new bootstrap.Modal(document.getElementById('positionModal'));
    _positionModal.show();
}

function savePosition() {
    const id    = document.getElementById('positionId').value;
    const label = document.getElementById('positionLabel').value.trim();
    const type  = document.getElementById('positionType').value;

    if (!label) {
        showToast('✗ ' + t('toast.error') + ': Label required', 'error');
        return;
    }

    const url    = id ? '/api/btc-tracking/positions/' + id : '/api/btc-tracking/positions';
    const method = id ? 'PUT' : 'POST';

    fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ label, type })
    })
    .then(r => r.json())
    .then(data => {
        if (data.error) { showToast('✗ ' + data.error, 'error'); return; }
        _positionModal.hide();
        _positionsCache = null;
        showToast('✓ ' + t(id ? 'toast.txUpdated' : 'toast.txAdded'), 'success');
        setTimeout(() => window.location.reload(), 800);
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

// ── Refresh Prices / BTC Price inline edit: jetzt in navbar.js (siehe dort) ──

// ── Toast ─────────────────────────────────────────────────
// showToast() now lives in tx-form.js (shared with the Flow Diagram page).

// ── Formatters ────────────────────────────────────────────
function formatEur(val) {
    // kept for compatibility – delegates to CURRENCY.format()
    return CURRENCY.format(val);
}

function _importEnc(file) {
    window._pendingEncFile = file;
    const modalEl = document.getElementById('encPasswordModal');
    const modal = bootstrap.Modal.getInstance(modalEl)
        || new bootstrap.Modal(modalEl);
    document.getElementById('encImportPassword').value = '';
    
    modalEl.addEventListener('shown.bs.modal', () => {
        document.getElementById('encImportPassword').focus();
    }, { once: true });
    
    modal.show();
}
 
function confirmEncImport() {
    const password = document.getElementById('encImportPassword').value;
    if (!password) {
        showToast('✗ ' + t('toast.error') + ': password required', 'error');
        return;
    }
 
    const file = window._pendingEncFile;
    if (!file) return;
 
    bootstrap.Modal.getInstance(document.getElementById('encPasswordModal'))?.hide();
    showToast('⏳ ' + t('toast.importProgress'), '');
 
    const formData = new FormData();
    formData.append('file', file);
    formData.append('password', password);
 
    fetch('/api/btc-tracking/import-enc', { method: 'POST', body: formData })
        .then(r => r.json())
        .then(data => {
            if (data.error) {
                showToast('✗ ' + t('toast.importError') + ': ' + data.error, 'error');
            } else {
                showToast('✓ ' + data.inserted + ' ' + t('toast.importSuccess'), 'success');
                setTimeout(() => window.location.reload(), 1800);
            }
        })
        .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

let _exportSelectedOnly = false;

function openExportModal(selectedOnly) {
    _exportSelectedOnly = !!selectedOnly;
    document.getElementById('exportPassword').value = '';
    document.getElementById('exportPasswordConfirm').value = '';
    document.getElementById('exportCoinTracking').checked = false;
    onExportCoinTrackingToggle();

    const info = document.getElementById('exportSelectionInfo');
    if (_exportSelectedOnly) {
        const ids = _getSelectedIds();
        if (!ids.length) {
            showToast('✗ ' + t('toast.error') + ': no rows selected', 'error');
            return;
        }
        info.textContent = ids.length + ' transaction(s) selected for export';
        info.classList.remove('d-none');
    } else {
        info.classList.add('d-none');
    }

    const modal = bootstrap.Modal.getInstance(document.getElementById('exportModal'))
        || new bootstrap.Modal(document.getElementById('exportModal'));
    modal.show();
}

function onExportCoinTrackingToggle() {
    const isCt = document.getElementById('exportCoinTracking').checked;
    document.getElementById('exportPasswordFields').classList.toggle('d-none', isCt);
}

function doExport() {
    const isCt = document.getElementById('exportCoinTracking').checked;
    const pw  = isCt ? '' : document.getElementById('exportPassword').value;
    const pw2 = isCt ? '' : document.getElementById('exportPasswordConfirm').value;

    if (pw && pw !== pw2) {
        showToast('✗ ' + t('toast.error') + ': passwords do not match', 'error');
        return;
    }

    const ids = _exportSelectedOnly ? _getSelectedIds() : null;

    bootstrap.Modal.getInstance(document.getElementById('exportModal'))?.hide();

    fetch('/api/btc-tracking/export', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ password: pw || null, coinTracking: isCt, ids })
    })
    .then(async response => {
        if (!response.ok) {
            const err = await response.json().catch(() => ({ error: 'Export failed' }));
            throw new Error(err.error || 'Export failed');
        }
        const disposition = response.headers.get('Content-Disposition') || '';
        const filename = disposition.includes('filename=')
            ? disposition.split('filename=')[1].replace(/"/g, '')
            : (pw ? 'transactions_export.enc' : (isCt ? 'cointracking_export.csv' : 'transactions_export.csv'));

        const blob = await response.blob();
        const url  = URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href     = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

// ── Multi-Select + Bulk Actions ───────────────────────────

// Kontextmenü-Element einmalig anlegen
const _ctxMenu = (() => {
    const el = document.createElement('div');
    el.id = 'bulkContextMenu';
    el.style.cssText = `
        position:fixed;z-index:9000;display:none;
        background:var(--bg-card);border:1px solid var(--border);
        border-radius:var(--radius);padding:.35rem 0;min-width:180px;
        box-shadow:0 4px 20px rgba(0,0,0,.5);font-size:.78rem`;
    document.body.appendChild(el);
    document.addEventListener('click', () => el.style.display = 'none');
    return el;
})();

function _getSelectedIds() {
    return [...document.querySelectorAll('.tx-row-check:checked')]
        .map(cb => parseInt(cb.dataset.id));
}

function _updateBulkToolbar() {
    const ids    = _getSelectedIds();
    const n      = ids.length;
    const toolbar = document.getElementById('bulkToolbar');
    const count   = document.getElementById('bulkCount');
    if (!toolbar) return;
//    toolbar.classList.toggle('d-none', n === 0);
    if (count) count.textContent = n + ' selected';

    // Sync select-all checkbox state (Tabellen-Kopfzeile UND die Kompakt-
    // Toolbar-Checkbox in der Kartenansicht — beide steuern dieselbe Auswahl).
    const all  = document.querySelectorAll('.tx-row-check');
    [document.getElementById('txSelectAll'), document.getElementById('txCardsSelectAll')]
        .forEach(selAll => {
            if (!selAll) return;
            selAll.checked       = all.length > 0 && n === all.length;
            selAll.indeterminate = n > 0 && n < all.length;
        });
}

function toggleSelectAll(cb) {
	document.querySelectorAll('.tx-row-check')
    .forEach(el => {
        el.checked = cb.checked;
        el.closest('tr').classList.toggle('selected', cb.checked);
    });
    _updateBulkToolbar();
    // Kartenansicht wird aus den (jetzt aktualisierten) <tr>-Zeilen gebaut —
    // ohne diesen Re-Render bleiben die bereits gerenderten Karten optisch auf
    // dem alten Auswahlstatus stehen (Checkbox + Rahmen-Highlight).
    _overviewRenderTxCardsIfActive();
}

// Row-Checkbox click (stopPropagation damit Row-Click nicht feuert)
document.addEventListener('change', e => {
	if (e.target.classList.contains('tx-row-check')) {
        e.target.closest('tr').classList.toggle('selected', e.target.checked);
        _updateBulkToolbar();
    }
});

// Rechtsklick auf txTableBody → Kontextmenü (funktioniert unverändert auch in
// der Kartenansicht: ein Rechtsklick auf eine .overview-tx-card wird auf die
// zugehörige, unsichtbare <tr> aufgelöst — der Rest der Logik bleibt gleich).
document.addEventListener('contextmenu', e => {
    let row = e.target.closest('#txTableBody tr');
    if (!row) {
        const card = e.target.closest('.overview-tx-card');
        if (card) row = document.querySelector(`#txTableBody tr[data-id="${card.dataset.id}"]`);
    }
    if (!row) return;
    e.preventDefault();

    // Wenn die geklickte Row nicht selektiert ist → nur diese selektieren
    const cb = row.querySelector('.tx-row-check');
    if (cb && !cb.checked) {
		document.querySelectorAll('.tx-row-check').forEach(c => {
            c.checked = false;
            c.closest('tr').classList.remove('selected');
        });
        cb.checked = true;
        row.classList.add('selected');
        _updateBulkToolbar();
    }

    const ids = _getSelectedIds();
    if (!ids.length) return;

	// Typen der Selektion ermitteln
    const selectedTypes = [...document.querySelectorAll('.tx-row-check:checked')]
        .map(tr => tr.closest('tr').dataset.type);
    const isAllTransfer = selectedTypes.every(t => t === 'TRANSFER_IN' || t === 'TRANSFER_OUT');
    const isAllTrade    = selectedTypes.every(t => t === 'BUY' || t === 'SELL');
	
	_ctxMenu.innerHTML = `
	        <div style="padding:.2rem .75rem .4rem;font-size:.68rem;color:var(--text-muted);letter-spacing:.08em;text-transform:uppercase">
	            ${ids.length} selected
	        </div>
			<div style="border-top:1px solid var(--border);margin:.3rem 0"></div>
	        ${_ctxItem('bi bi-download me-1',        t("table.action.exportSelected"),    'openExportModal(true)')}
			<div style="border-top:1px solid var(--border);margin:.3rem 0"></div>
	        ${isAllTransfer ? _ctxItem('bi-arrow-down-up', t('table.action.mark.solo'), 'bulkMarkSoloTransfer()') : ''}
	        ${isAllTransfer ? _ctxItem('bi-x-circle', t('table.action.remove.transfer'), 'bulkRemoveTransfer()') : ''}
	        ${isAllTrade ? _ctxItem('bi-percent', t("table.action.exchange.rate"), 'openBulkExRate()') : ''}
			<div style="border-top:1px solid var(--border);margin:.3rem 0"></div>
			${_ctxItem('bi-arrow-right-square',t("table.action.move.position"),     'openBulkMove()')}
			${_ctxItem('bi-check-circle',      t('table.action.clear.duplicate'),   'clearDuplicateMark()')}
	        <div style="border-top:1px solid var(--border);margin:.3rem 0"></div>
	        ${_ctxItem('bi-trash text-neg',    t("table.action.delete"),            'bulkDelete()', true)}`;


    _ctxMenu.style.display = 'block';
    // Position: keep inside viewport
    const vw = window.innerWidth, vh = window.innerHeight;
    let x = e.clientX, y = e.clientY;
    _ctxMenu.style.left = (x + 185 > vw ? vw - 190 : x) + 'px';
    _ctxMenu.style.top  = (y + 160 > vh ? vh - 165 : y) + 'px';
});

function _ctxItem(icon, label, action, danger = false) {
    return `<div onclick="${action};document.getElementById('bulkContextMenu').style.display='none'"
         style="padding:.4rem .9rem;cursor:pointer;color:${danger ? 'var(--neg)' : 'var(--text)'};
                display:flex;align-items:center;gap:.5rem"
         onmouseenter="this.style.background='var(--bg-row)'"
         onmouseleave="this.style.background=''"
    ><i class="bi ${icon}"></i>${label}</div>`;
}

// ── Bulk: Delete ──────────────────────────────────────────
async function bulkDelete() {
    const ids = _getSelectedIds();
    if (!ids.length) return;
    if (!await showConfirm(t('table.action.delete'), t('confirm.deleteTxs', { COUNT: ids.length }))) return;

    fetch('/api/btc-tracking/transactions/bulk', {
        method:  'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(ids)
    })
    .then(r => r.json())
    .then(d => {
        showToast('✓ ' + t('toast.deleteTxs.success', { COUNT: d.deleted }), 'success');
        _positionsCache = null;
        txLoaded = false;
        loadTransactions();
    })
    .catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Pair Transfers ──────────────────────────────────
async function bulkPair() {
    const ids = _getSelectedIds();
    if (ids.length < 2 || ids.length % 2 !== 0) {
        showToast('✗ ' + t('toast.pairing.error.uneven'), 'error');
        return;
    }

    const selectedRows = [...document.querySelectorAll('.tx-row-check:checked')]
        .map(cb => cb.closest('tr'));
    const types    = selectedRows.map(tr => tr.dataset.type);
    const inCount  = types.filter(t => t === 'TRANSFER_IN').length;
    const outCount = types.filter(t => t === 'TRANSFER_OUT').length;
    const invalid  = types.filter(t => t !== 'TRANSFER_IN' && t !== 'TRANSFER_OUT');

    if (invalid.length > 0) {
        showToast('✗ ' + t('toast.pairing.error.type.wrong'), 'error');
        return;
    }
    if (inCount !== outCount) {
        showToast('✗ ' + t('toast.pairing.error.type.unequal', { IN: inCount, OUT: outCount }), 'error');
        return;
    }

    const existingPaired = [...document.querySelectorAll('.tx-row-check:checked')]
        .map(cb => cb.closest('tr'))
        .filter(tr => tr.dataset.transferId);

    if (existingPaired.length > 0) {
        if (!await showConfirm(t('table.action.pair.transfer'),
                t('toast.pairing.info.id', { COUNT: existingPaired.length }))) return;
    }

    fetch('/api/btc-tracking/transactions/bulk-pair', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ ids })
    })
    .then(r => r.json())
    .then(d => {
        if (d.error) { showToast('✗ ' + d.error, 'error'); return; }
        showToast('✓ ' + t('toast.pairing.success', { COUNT: d.paired }), 'success');
        txLoaded = false;
        loadTransactions();
    })
    .catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Move Position ───────────────────────────────────
function openBulkMove() {
    const ids = _getSelectedIds();
    if (!ids.length) return;

    const sel = document.getElementById('bulkMoveSelect');
    sel.innerHTML = '<option value="">— Select —</option>';
    document.getElementById('bulkMoveNew').value = '';
    document.getElementById('bulkMoveNewRow').classList.add('d-none');

    if (_positionsCache) {
        _positionsCache.forEach(p => {
            sel.innerHTML += `<option value="${esc(p.label)}">${esc(p.label)}</option>`;
        });
    } else {
        fetch('/api/btc-tracking/positions').then(r => r.json()).then(data => {
            _positionsCache = data;
            data.forEach(p => {
                sel.innerHTML += `<option value="${esc(p.label)}">${esc(p.label)}</option>`;
            });
        });
    }
    sel.innerHTML += '<option value="__new__">＋ New position...</option>';

    const modal = bootstrap.Modal.getInstance(document.getElementById('bulkMoveModal'))
        || new bootstrap.Modal(document.getElementById('bulkMoveModal'));
    modal.show();
}

/** Blendet das Freitext-Feld für eine neue Position ein/aus, je nachdem ob
 *  "＋ New position..." im Select gewählt ist. */
function onBulkMoveSelectChange(sel) {
    document.getElementById('bulkMoveNewRow').classList.toggle('d-none', sel.value !== '__new__');
}

function confirmBulkMove() {
    const sel    = document.getElementById('bulkMoveSelect');
    const target = sel.value === '__new__'
        ? (document.getElementById('bulkMoveNew').value || '').trim()
        : sel.value;

    if (!target) { showToast("✗ " + t("toast.move.position.missing.target"), 'error'); return; }

    const ids = _getSelectedIds();
    bootstrap.Modal.getInstance(document.getElementById('bulkMoveModal'))?.hide();

    fetch('/api/btc-tracking/transactions/bulk-move', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ ids, targetExchange: target })
    })
    .then(r => r.json())
    .then(d => {
        if (d.error) { showToast('✗ ' + d.error, 'error'); return; }
		showToast("✓ " + t("toast.move.position.success", {COUNT: d.moved, TARGET: target}), 'success');
        _positionsCache = null;
        txLoaded = false;
        loadTransactions();
    })
    .catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Exchange Rate ───────────────────────────────────
function openBulkExRate() {
    const ids = _getSelectedIds();
    if (!ids.length) return;
    document.getElementById('bulkExRateInput').value = '';
    const modal = bootstrap.Modal.getInstance(document.getElementById('bulkExRateModal'))
        || new bootstrap.Modal(document.getElementById('bulkExRateModal'));
    modal.show();
    setTimeout(() => document.getElementById('bulkExRateInput').focus(), 300);
}

function confirmBulkExRate() {
    const rate = parseFloat(document.getElementById('bulkExRateInput').value);
    if (!rate || rate <= 0) { showToast("✗ " + t("toast.exchange.rate.invalid"), 'error'); return; }

    const ids = _getSelectedIds();
    bootstrap.Modal.getInstance(document.getElementById('bulkExRateModal'))?.hide();

    fetch('/api/btc-tracking/transactions/bulk-exrate', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ ids, exchangeRate: rate })
    })
    .then(r => r.json())
    .then(d => {
        if (d.error) { showToast('✗ ' + d.error, 'error'); return; }
		showToast("✓ " + t("toast.exchange.rate.success", {COUNT: d.updated}), 'success');

        txLoaded = false;
        loadTransactions();
    })
    .catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Mark Solo Transfer ──────────────────────────────
async function bulkMarkSoloTransfer() {
    const ids = _getSelectedIds();
    if (!ids.length) return;

    const selectedRows = [...document.querySelectorAll('.tx-row-check:checked')]
        .map(cb => cb.closest('tr'));
    const invalid = selectedRows.some(tr =>
        tr.dataset.type !== 'TRANSFER_IN' && tr.dataset.type !== 'TRANSFER_OUT');

    if (invalid) {
        showToast('✗ ' + t('toast.soloTransfer.error.type.wrong'), 'error');
        return;
    }

    if (!await showConfirm(t('table.action.mark.solo'),
            t('confirm.markSoloTransfer', { COUNT: ids.length }))) return;

    fetch('/api/btc-tracking/transactions/bulk-solo-transfer', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ ids })
    })
    .then(r => r.json())
    .then(d => {
        if (d.error) { showToast('✗ ' + d.error, 'error'); return; }
        showToast('✓ ' + t('toast.soloTransfer.success', { COUNT: d.marked }), 'success');
        txLoaded = false;
        loadTransactions();
    })
    .catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Remove TransferId ───────────────────────────────
async function bulkRemoveTransfer() {
    const ids = _getSelectedIds();
    if (!ids.length) return;

    if (!await showConfirm(t('table.action.remove.transfer'),
            t('confirm.removeTransfer', { COUNT: ids.length }))) return;

    fetch('/api/btc-tracking/transactions/bulk-remove-transfer', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ ids })
    })
    .then(r => r.json())
    .then(d => {
        if (d.error) { showToast('✗ ' + d.error, 'error'); return; }
        showToast('✓ ' + t('toast.removeTransfer.success', { COUNT: d.removed }), 'success');
        txLoaded = false;
        loadTransactions();
    })
    .catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Generic Confirm Dialog ────────────────────────────────
let _confirmModal     = null;
let _confirmResolve   = null;

function showConfirm(title, body) {
    document.getElementById('confirmModalTitle').textContent = title;
    document.getElementById('confirmModalBody').textContent  = body;

    if (!_confirmModal) {
        _confirmModal = new bootstrap.Modal(document.getElementById('confirmModal'));
    }

    return new Promise(resolve => {
        _confirmResolve = resolve;

        const modalEl = document.getElementById('confirmModal');
        const okBtn   = document.getElementById('confirmModalOk');
        // Alten Listener entfernen um Doppel-Trigger zu vermeiden
        const newOk = okBtn.cloneNode(true);
        okBtn.parentNode.replaceChild(newOk, okBtn);
        newOk.addEventListener('click', () => {
            _confirmModal.hide();
            resolve(true);
        });

        // Enter bestätigt, wenn der OK-Button fokussiert (markiert) ist
        const onKeydown = (e) => {
            if (e.key === 'Enter' && document.activeElement === newOk) {
                e.preventDefault();
                newOk.click();
            }
        };
        modalEl.addEventListener('keydown', onKeydown);

        modalEl.addEventListener('shown.bs.modal', () => {
            newOk.focus();
        }, { once: true });

        modalEl.addEventListener('hidden.bs.modal', () => {
            modalEl.removeEventListener('keydown', onKeydown);
            resolve(false);
        }, { once: true });

        _confirmModal.show();
    });
}

// ── Import-Historie: Eintrag löschen ──────────────────────
let _deleteImportHistoryId = null;

function openDeleteImportHistoryModal(id, linkedTransactionCount, filename) {
    _deleteImportHistoryId = id;
    document.getElementById('deleteImportHistoryFilename').textContent = filename;
    document.getElementById('deleteImportHistoryBody').textContent =
        linkedTransactionCount > 0
            ? t('import.history.delete.body', { COUNT: linkedTransactionCount })
            : t('import.history.delete.body.none');

    const withTxBtn = document.getElementById('deleteImportHistoryWithTxBtn');
    withTxBtn.disabled = linkedTransactionCount === 0;

    const modal = bootstrap.Modal.getInstance(document.getElementById('deleteImportHistoryModal'))
        || new bootstrap.Modal(document.getElementById('deleteImportHistoryModal'));
    modal.show();
}

function confirmDeleteImportHistory(deleteTransactions) {
    if (_deleteImportHistoryId == null) return;
    const id = _deleteImportHistoryId;
    bootstrap.Modal.getInstance(document.getElementById('deleteImportHistoryModal'))?.hide();

    fetch(`/api/btc-tracking/import/history/${id}?deleteTransactions=${deleteTransactions}`, { method: 'DELETE' })
        .then(r => {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            window.location.reload();
        })
        .catch(err => showToast('✗ ' + err.message, 'error'));
}

function _markDuplicates() {
	const ids = JSON.parse(sessionStorage.getItem('depot-lastimport-ids') || '[]');
    if (!ids.length) return;
    document.querySelectorAll('.tx-row-check').forEach(cb => {
        const id = parseInt(cb.dataset.id);
        if (ids.includes(id)) {
            cb.closest('tr').classList.add('last-import');
        }
    });
}

function clearDuplicateMark() {
    const ids = _getSelectedIds();
    if (!ids.length) return;

    fetch('/api/btc-tracking/transactions/bulk-clear-duplicate', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ ids })
    })
    .then(r => r.json())
    .then(d => {
        if (d.error) { showToast('✗ ' + d.error, 'error'); return; }

      
        document.querySelectorAll('.tx-row-check').forEach(cb => cb.checked = false);
        _updateBulkToolbar();
        txLoaded = false;
        loadTransactions();
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

document.addEventListener("DOMContentLoaded", function() {
    // Sicherstellen, dass CURRENCY Objekt existiert
    if (typeof CURRENCY !== 'undefined' && CURRENCY.current) {
        const symbol = CURRENCY.symbol(CURRENCY.current());
        
        // Alle Währungssymbol-Platzhalter im Dokument finden und füllen
        document.querySelectorAll('.currency-symbol').forEach(function(el) {
            el.textContent = ' ' + symbol;
        });
    }
});

// ── Device Detection ──────────────────────────────────────
function getDeviceType() {
    const w = window.innerWidth;
    if (w <= 412) return 'PHONE';
    if (w <= 768) return 'TABLET';
    return 'DESKTOP';
}