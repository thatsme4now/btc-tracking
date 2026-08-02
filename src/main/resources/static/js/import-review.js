'use strict';
// Step 2 des Import-Assistenten: Review der Staging-Tabelle (Duplikate/FX-
// Warnungen/Fehler, Editieren, Löschen, Transfer-Pairing) + finaler Commit.

let _stagingRows = [];
let _stagingPositionsLoaded = false;

const TYPE_COLOR = {
    BUY: 'var(--pos)', SELL: 'var(--neg)',
    TRANSFER_IN: 'var(--pos)', TRANSFER_OUT: 'var(--neg)'
};

function formatDate(iso) {
    if (!iso) return '–';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '–';
    return d.toLocaleString(I18N.currentLang() === 'de' ? 'de-DE' : 'en-GB', {
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
    });
}

function loadPositionsDatalist() {
    if (_stagingPositionsLoaded) return;
    fetch('/api/btc-tracking/positions').then(r => r.json()).then(data => {
        _stagingPositionsLoaded = true;
        const list = document.getElementById('stagingPositionsList');
        if (list) list.innerHTML = data.map(p => `<option value="${esc(p.label)}"></option>`).join('');
    }).catch(() => {});
}

function loadStaging() {
    return fetchJSON('/api/btc-tracking/import/staging').then(rows => {
        _stagingRows = rows || [];
        renderStagingTable();
        updateSummary();
    }).catch(err => showToast('✗ ' + err.message, 'error'));
}

let _stagingFilterMode = 'all';

function toggleStagingErrorFilter(mode) {
    _stagingFilterMode = mode;
    document.querySelectorAll('#stagingFilterGroup .pill-filter-btn').forEach(btn => {
        btn.classList.toggle('is-active', btn.dataset.mode === mode);
    });
    renderStagingTable();
}

function renderStagingTable() {
    const body = document.getElementById('stagingTableBody');
    const rows = _stagingFilterMode === 'errors' ? _stagingRows.filter(r => r.hasError) : _stagingRows;

    if (!_stagingRows.length) {
        body.innerHTML = `<tr><td colspan="8" class="text-center text-muted py-4" data-i18n="import.review.empty">Keine Zeilen (mehr) vorhanden.</td></tr>`;
        I18N.applyI18n();
        return;
    }
    if (!rows.length) {
        body.innerHTML = `<tr><td colspan="8" class="text-center text-muted py-4" data-i18n="import.review.filter.noErrors">Keine fehlerhaften Zeilen (mehr).</td></tr>`;
        I18N.applyI18n();
        return;
    }

    body.innerHTML = rows.map(row => {
        const rowClass = row.hasError ? 'error-row' : (row.duplicate ? 'warning-duplicate' : (row.fxWarning ? 'warning' : ''));
        const typeColor = TYPE_COLOR[row.type] || 'var(--text)';
        const amount = row.type === 'BUY' || row.type === 'SELL'
            ? (row.quantityFiat != null ? Number(row.quantityFiat).toLocaleString('de-DE', {minimumFractionDigits:2, maximumFractionDigits:2}) + ' ' + (row.currency || '') : '–')
            : (row.quantity != null ? Number(row.quantity).toFixed(8) + ' BTC' : '–');

        const badges = [];
        if (row.hasError) badges.push(`<span class="depot-badge" style="color:var(--neg)" title="${esc(errorReasonText(row.errorReason))}"><i class="bi bi-exclamation-octagon"></i></span>`);
        if (row.duplicate) badges.push(`<span class="depot-badge" style="color:var(--warn-duplicate)" title="${esc(t('legend.warn.duplicate'))}"><i class="bi bi-files"></i></span>`);
        if (row.fxWarning) badges.push(`<span class="depot-badge" style="color:var(--warn)" title="${esc(t('legend.warn.currency'))}"><i class="bi bi-currency-exchange"></i></span>`);

        return `
        <tr class="depot-row ${rowClass}" data-id="${row.id}" data-type="${row.type || ''}"
            onclick="const cb=this.querySelector('.staging-row-check');cb.checked=!cb.checked;this.classList.toggle('selected',cb.checked);_updateStagingBulkCount()">
            <td onclick="event.stopPropagation()"><input type="checkbox" class="staging-row-check" value="${row.id}" style="accent-color:var(--accent)" onchange="_updateStagingBulkCount()"/></td>
            <td style="white-space:nowrap;padding-left:.4rem;padding-right:.4rem">
                ${badges.join(' ')}
                <span class="depot-actions">
                    <button class="btn btn-xs depot-btn-icon" onclick="event.stopPropagation();openStagingEdit(${row.id})" title="Edit"><i class="bi bi-pencil"></i></button>
                    <a href="#" class="btn btn-xs depot-btn-icon text-neg" title="Delete" onclick="event.stopPropagation();event.preventDefault();deleteStagingRow(${row.id})"><i class="bi bi-trash"></i></a>
                </span>
            </td>
            <td>${row.dateParsed ? esc(formatDate(row.dateParsed)) : `<span style="color:var(--neg)" title="${esc(row.dateRaw || '')}">${esc(row.dateRaw || '–')}</span>`}</td>
            <td>${esc(row.positionLabel || '–')}</td>
            <td><span style="color:${typeColor}">${esc(row.type || '–')}</span></td>
            <td class="text-end">${row.quantity != null ? Number(row.quantity).toFixed(8) : '–'}</td>
            <td class="text-end">${amount}</td>
            <td style="font-size:.7rem;color:var(--text-muted)">${esc(row.transferId ? row.transferId.slice(0, 8) + '…' : '–')}</td>
        </tr>`;
    }).join('');
    I18N.applyI18n();
}

function updateSummary() {
    const total = _stagingRows.length;
    const duplicates = _stagingRows.filter(r => r.duplicate).length;
    const fx = _stagingRows.filter(r => r.fxWarning).length;
    const errors = _stagingRows.filter(r => r.hasError).length;

    document.getElementById('reviewSummary').textContent =
        t('import.review.summary', { TOTAL: total, DUP: duplicates, FX: fx, ERR: errors });

    document.getElementById('errorHint').classList.toggle('d-none', errors === 0);
    document.getElementById('confirmImportBtn').disabled = errors > 0 || total === 0;
}

function toggleSelectAllStaging(cb) {
    document.querySelectorAll('.staging-row-check').forEach(el => {
        el.checked = cb.checked;
        el.closest('tr').classList.toggle('selected', cb.checked);
    });
    _updateStagingBulkCount();
}

// Checkbox-Klick soll den Zeilen-Klick (Toggle) nicht doppelt auslösen und
// den 'selected'-Rahmen synchron halten (analog Übersichtsseite).
document.addEventListener('change', e => {
    if (e.target.classList.contains('staging-row-check')) {
        e.target.closest('tr').classList.toggle('selected', e.target.checked);
    }
});

function _updateStagingBulkCount() {
    const n = document.querySelectorAll('.staging-row-check:checked').length;
    document.getElementById('stagingBulkCount').textContent = n + ' selected';

    const all = document.querySelectorAll('.staging-row-check');
    const selAll = document.getElementById('stagingSelectAll');
    if (selAll) {
        selAll.checked = all.length > 0 && n === all.length;
        selAll.indeterminate = n > 0 && n < all.length;
    }
}

function _selectedStagingIds() {
    return Array.from(document.querySelectorAll('.staging-row-check:checked')).map(el => Number(el.value));
}

function bulkPairStaging() {
    const ids = _selectedStagingIds();
    if (ids.length < 2 || ids.length % 2 !== 0) {
        showToast('✗ ' + t('import.review.pairEvenHint'), 'error');
        return;
    }
    fetchJSON('/api/btc-tracking/import/staging/bulk-pair', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids })
    }).then(() => loadStaging()).catch(err => showToast('✗ ' + err.message, 'error'));
}

function bulkRemoveTransferStaging() {
    const ids = _selectedStagingIds();
    if (!ids.length) return;
    fetchJSON('/api/btc-tracking/import/staging/bulk-remove-transfer', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids })
    }).then(() => loadStaging()).catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Wechselkurs anpassen ────────────────────────────
function openStagingBulkExRate() {
    const ids = _selectedStagingIds();
    if (!ids.length) return;
    document.getElementById('stagingBulkExRateInput').value = '';
    const modal = bootstrap.Modal.getInstance(document.getElementById('stagingBulkExRateModal'))
        || new bootstrap.Modal(document.getElementById('stagingBulkExRateModal'));
    modal.show();
    setTimeout(() => document.getElementById('stagingBulkExRateInput').focus(), 300);
}

function confirmStagingBulkExRate() {
    const rate = parseFloat(document.getElementById('stagingBulkExRateInput').value);
    if (!rate || rate <= 0) { showToast('✗ ' + t('toast.exchange.rate.invalid'), 'error'); return; }

    const ids = _selectedStagingIds();
    bootstrap.Modal.getInstance(document.getElementById('stagingBulkExRateModal'))?.hide();

    fetchJSON('/api/btc-tracking/import/staging/bulk-exrate', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids, exchangeRate: rate })
    }).then(d => {
        showToast('✓ ' + t('toast.exchange.rate.success', { COUNT: d.updated }), 'success');
        loadStaging();
    }).catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Wallet/Börse anpassen ────────────────────────────
function openStagingBulkMove() {
    const ids = _selectedStagingIds();
    if (!ids.length) return;

    const sel = document.getElementById('stagingBulkMoveSelect');
    sel.innerHTML = '<option value="">— Select —</option>';
    document.getElementById('stagingBulkMoveNew').value = '';
    document.getElementById('stagingBulkMoveNewRow').classList.add('d-none');

    fetch('/api/btc-tracking/positions').then(r => r.json()).then(data => {
        data.forEach(p => { sel.innerHTML += `<option value="${esc(p.label)}">${esc(p.label)}</option>`; });
        sel.innerHTML += '<option value="__new__">＋ New position...</option>';
    });

    const modal = bootstrap.Modal.getInstance(document.getElementById('stagingBulkMoveModal'))
        || new bootstrap.Modal(document.getElementById('stagingBulkMoveModal'));
    modal.show();
}

function onStagingBulkMoveSelectChange(sel) {
    document.getElementById('stagingBulkMoveNewRow').classList.toggle('d-none', sel.value !== '__new__');
}

function confirmStagingBulkMove() {
    const sel = document.getElementById('stagingBulkMoveSelect');
    const target = sel.value === '__new__'
        ? (document.getElementById('stagingBulkMoveNew').value || '').trim()
        : sel.value;
    if (!target) { showToast('✗ ' + t('toast.move.position.missing.target'), 'error'); return; }

    const ids = _selectedStagingIds();
    bootstrap.Modal.getInstance(document.getElementById('stagingBulkMoveModal'))?.hide();

    fetchJSON('/api/btc-tracking/import/staging/bulk-move', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids, targetExchange: target })
    }).then(d => {
        showToast('✓ ' + t('toast.move.position.success', { COUNT: d.moved, TARGET: target }), 'success');
        loadStaging();
    }).catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Als Solo-Transfer markieren ─────────────────────
function bulkSoloTransferStaging() {
    const ids = _selectedStagingIds();
    if (!ids.length) return;

    const selectedTypes = [...document.querySelectorAll('.staging-row-check:checked')]
        .map(cb => cb.closest('tr').dataset.type);
    const invalid = selectedTypes.some(tp => tp !== 'TRANSFER_IN' && tp !== 'TRANSFER_OUT');
    if (invalid) { showToast('✗ ' + t('toast.soloTransfer.error.type.wrong'), 'error'); return; }

    fetchJSON('/api/btc-tracking/import/staging/bulk-solo-transfer', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids })
    }).then(d => {
        showToast('✓ ' + t('toast.soloTransfer.success', { COUNT: d.marked }), 'success');
        loadStaging();
    }).catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Bulk: Löschen ──────────────────────────────────────────
function bulkDeleteStaging() {
    const ids = _selectedStagingIds();
    if (!ids.length) return;
    if (!window.confirm(t('confirm.deleteTxs', { COUNT: ids.length }))) return;

    fetchJSON('/api/btc-tracking/import/staging/bulk', {
        method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids })
    }).then(d => {
        showToast('✓ ' + t('toast.deleteTxs.success', { COUNT: d.deleted }), 'success');
        loadStaging();
    }).catch(err => showToast('✗ ' + err.message, 'error'));
}

// ── Rechtsklick-Kontextmenü (analog Übersichtsseite) ──────
const _stagingCtxMenu = (() => {
    const el = document.createElement('div');
    el.id = 'stagingBulkContextMenu';
    el.style.cssText = `
        position:fixed;z-index:9000;display:none;
        background:var(--bg-card);border:1px solid var(--border);
        border-radius:var(--radius);padding:.35rem 0;min-width:200px;
        box-shadow:0 4px 20px rgba(0,0,0,.5);font-size:.78rem`;
    document.body.appendChild(el);
    document.addEventListener('click', () => el.style.display = 'none');
    return el;
})();

function _stagingCtxItem(icon, label, action, danger = false) {
    return `<div onclick="${action};document.getElementById('stagingBulkContextMenu').style.display='none'"
         style="padding:.4rem .9rem;cursor:pointer;color:${danger ? 'var(--neg)' : 'var(--text)'};
                display:flex;align-items:center;gap:.5rem"
         onmouseenter="this.style.background='var(--bg-row)'"
         onmouseleave="this.style.background=''"
    ><i class="bi ${icon}"></i>${label}</div>`;
}

document.addEventListener('contextmenu', e => {
    const row = e.target.closest('#stagingTableBody tr[data-id]');
    if (!row) return;
    e.preventDefault();

    const cb = row.querySelector('.staging-row-check');
    if (cb && !cb.checked) {
        document.querySelectorAll('.staging-row-check').forEach(c => {
            c.checked = false;
            c.closest('tr').classList.remove('selected');
        });
        cb.checked = true;
        row.classList.add('selected');
        _updateStagingBulkCount();
    }

    const ids = _selectedStagingIds();
    if (!ids.length) return;

    _stagingCtxMenu.innerHTML = `
        <div style="padding:.2rem .75rem .4rem;font-size:.68rem;color:var(--text-muted);letter-spacing:.08em;text-transform:uppercase">
            ${ids.length} selected
        </div>
        <div style="border-top:1px solid var(--border);margin:.3rem 0"></div>
        ${_stagingCtxItem('bi-link-45deg', t('table.action.pair.transfer'), 'bulkPairStaging()')}
        ${_stagingCtxItem('bi-x-circle', t('table.action.remove.transfer'), 'bulkRemoveTransferStaging()')}
        ${_stagingCtxItem('bi-arrow-down-up', t('table.action.mark.solo'), 'bulkSoloTransferStaging()')}
        <div style="border-top:1px solid var(--border);margin:.3rem 0"></div>
        ${_stagingCtxItem('bi-arrow-right-square', t('table.action.move.position'), 'openStagingBulkMove()')}
        ${_stagingCtxItem('bi-percent', t('table.action.exchange.rate'), 'openStagingBulkExRate()')}
        <div style="border-top:1px solid var(--border);margin:.3rem 0"></div>
        ${_stagingCtxItem('bi-trash text-neg', t('table.action.delete'), 'bulkDeleteStaging()', true)}`;

    _stagingCtxMenu.style.display = 'block';
    const vw = window.innerWidth, vh = window.innerHeight;
    let x = e.clientX, y = e.clientY;
    _stagingCtxMenu.style.left = (x + 205 > vw ? vw - 210 : x) + 'px';
    _stagingCtxMenu.style.top  = (y + 190 > vh ? vh - 195 : y) + 'px';
});

function openStagingEdit(id) {
    const row = _stagingRows.find(r => r.id === id);
    if (!row) return;
    loadPositionsDatalist();

    document.getElementById('stagingEditId').value = id;
    document.getElementById('stagingEditDate').value = row.dateParsed ? row.dateParsed.substring(0, 16) : '';
    document.getElementById('stagingEditType').value = row.type || 'BUY';
    document.getElementById('stagingEditExchange').value = row.positionLabel || '';
    document.getElementById('stagingEditQty').value = row.quantity != null ? row.quantity : '';
    document.getElementById('stagingEditQtyFiat').value = row.quantityFiat != null ? row.quantityFiat : '';
    document.getElementById('stagingEditCurrency').value = row.currency || '';
    document.getElementById('stagingEditExchangeRate').value = row.exchangeRate != null ? row.exchangeRate : '';
    document.getElementById('stagingEditFees').value = row.fees != null ? row.fees : '';
    document.getElementById('stagingEditFeesCurrency').value = row.feesCurrency || '';
    document.getElementById('stagingEditTransferId').value = row.transferId || '';
    document.getElementById('stagingEditComment').value = row.comment || '';

    _updateStagingEditExchangeRatePreview();

    const el = document.getElementById('stagingEditModal');
    (bootstrap.Modal.getInstance(el) || new bootstrap.Modal(el)).show();
}

// Fiat-Betrag × Wechselkurs = Wert in der aktuell gewählten Anzeigewährung
// (gleiche Formel wie DepotService#getAllPositions) — zeigt sofort, ob ein
// angepasster Kurs plausibel ist, ohne erst zu speichern.
function _updateStagingEditExchangeRatePreview() {
    const previewEl = document.getElementById('stagingEditExchangeRatePreview');
    if (!previewEl) return;

    const qtyFiat = parseFloat(document.getElementById('stagingEditQtyFiat').value);
    const rate    = parseFloat(document.getElementById('stagingEditExchangeRate').value);

    if (!qtyFiat || !rate) {
        previewEl.textContent = '';
        return;
    }
    const converted = qtyFiat * rate;
    previewEl.textContent = '= ' + converted.toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' ' + CURRENCY.current();
}

function saveStagingEdit() {
    const id = document.getElementById('stagingEditId').value;
    const num = v => (v === '' || v == null) ? null : Number(v);

    const req = {
        date: document.getElementById('stagingEditDate').value || null,
        type: document.getElementById('stagingEditType').value || null,
        exchange: document.getElementById('stagingEditExchange').value.trim(),
        quantity: num(document.getElementById('stagingEditQty').value),
        quantityFiat: num(document.getElementById('stagingEditQtyFiat').value),
        currency: document.getElementById('stagingEditCurrency').value.trim(),
        exchangeRate: num(document.getElementById('stagingEditExchangeRate').value),
        fees: num(document.getElementById('stagingEditFees').value),
        feesCurrency: document.getElementById('stagingEditFeesCurrency').value.trim(),
        transferId: document.getElementById('stagingEditTransferId').value.trim(),
        comment: document.getElementById('stagingEditComment').value.trim(),
    };

    fetchJSON('/api/btc-tracking/import/staging/' + id, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(req)
    }).then(() => {
        bootstrap.Modal.getInstance(document.getElementById('stagingEditModal'))?.hide();
        showToast('✓ ' + t('toast.txUpdated'), 'success');
        loadStaging();
    }).catch(err => showToast('✗ ' + err.message, 'error'));
}

function deleteStagingRow(id) {
    if (!window.confirm(t('import.review.confirmDelete'))) return;
    fetchJSON('/api/btc-tracking/import/staging/' + id, { method: 'DELETE' })
        .then(() => loadStaging())
        .catch(err => showToast('✗ ' + err.message, 'error'));
}

function confirmImport() {
    const btn = document.getElementById('confirmImportBtn');
    btn.disabled = true;

    const filename = sessionStorage.getItem('depot-import-filename') || 'import.csv';
    const totalRowsRaw = sessionStorage.getItem('depot-import-total-rows');
    const totalRows = totalRowsRaw ? Number(totalRowsRaw) : null;

    fetchJSON('/api/btc-tracking/import/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ filename, totalRows })
    }).then(result => {
        sessionStorage.setItem('depot-import-result', JSON.stringify(result));
        sessionStorage.removeItem('depot-import-filename');
        sessionStorage.removeItem('depot-import-total-rows');
        sessionStorage.removeItem('depot-import-skipped-no-btc');
        window.location.href = '/btc-tracking/import/status';
    }).catch(err => {
        btn.disabled = false;
        showToast('✗ ' + t('toast.importError') + ': ' + err.message, 'error');
    });
}

I18N.ready.then(() => {
    I18N.applyI18n();
    document.getElementById('reviewFilename').textContent =
        sessionStorage.getItem('depot-import-filename') || t('import.review.title');

    const skippedNoBtc = Number(sessionStorage.getItem('depot-import-skipped-no-btc') || 0);
    if (skippedNoBtc > 0) {
        document.getElementById('skippedNoBtcText').textContent =
            t('import.review.skippedNoBtc', { COUNT: skippedNoBtc });
        document.getElementById('skippedNoBtcHint').classList.remove('d-none');
    }

    loadStaging();
});
