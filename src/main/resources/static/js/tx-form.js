'use strict';

// tx-form.js: shared Add/Edit Transaction modal logic, used by overview.html
// and flow.html. Requires flatpickr, bootstrap.bundle.min.js, i18n.js,
// currency.js. Calls the page-specific hook onTxSaved() after a save, if defined.

// ── Bootstrap Modal instances ─────────────────────────────
let txModal    = null;
let txModalAdd = null;

// ── Flatpickr Date Pickers ────────────────────────────────
const FLATPICKR_LOCALES = { de: 'de', th: 'th', es: 'es', fr: 'fr', it: 'it' };

let _fpAdd = null;
let _fpEdit = null;
let _fpTransferIn = null;

function _fpLocale() {
    const lang = I18N.currentLang();
    return FLATPICKR_LOCALES[lang] || 'default';
}

function initFlatpickr() {
    const locale = _fpLocale();
    const isEn   = locale === 'default';
    const cfg = {
        enableTime:     true,
        enableSeconds:  true,
        time_24hr:      !isEn,
        dateFormat:     'Y-m-d H:i:S',
        altInput:       true,
        altFormat:      isEn ? 'm/d/Y h:i:S K' : 'd.m.Y H:i:S',
        locale:         locale,
        allowInput:     true,
        minuteIncrement: 1,
    };

    if (_fpAdd)        { _fpAdd.destroy();        _fpAdd = null; }
    if (_fpEdit)       { _fpEdit.destroy();       _fpEdit = null; }
    if (_fpTransferIn) { _fpTransferIn.destroy(); _fpTransferIn = null; }

    if (document.getElementById('addTxDate'))      _fpAdd        = flatpickr('#addTxDate',      cfg);
    if (document.getElementById('editTxDate'))     _fpEdit       = flatpickr('#editTxDate',     cfg);
    if (document.getElementById('transferInDate')) _fpTransferIn = flatpickr('#transferInDate', cfg);
}

// ── Exchange Dropdown ─────────────────────────────────────
let _positionsCache = null;

async function _ensurePositionsLoaded(prefix) {
    const sel = document.getElementById(prefix + 'TxExchangeSelect');
    if (_positionsCache) {
        _fillExchangeDropdown(sel, _positionsCache);
        return;
    }
    const data = await fetch('/api/btc-tracking/positions').then(r => r.json());
    _positionsCache = data;
    _fillExchangeDropdown(sel, data);
}

function _fillExchangeDropdown(sel, data) {
    const current = sel.dataset.current || '';
    sel.innerHTML =
        '<option value="">— Select position —</option>' +
        data.map(p => `<option value="${esc(p.label)}" ${p.label === current ? 'selected' : ''}>${esc(p.label)}</option>`).join('') +
        '<option value="__new__">＋ New position...</option>';
    // if current isn't in the list, preselect "__new__" and show the text field
    const known = data.some(p => p.label === current);
    if (current && !known) {
        sel.value = '__new__';
        _showExchangeNewInput(sel.id.replace('ExchangeSelect', ''));
    }
}

function onExchangeSelectChange(prefix) {
    const sel = document.getElementById(prefix + 'TxExchangeSelect');
    const isNew = sel.value === '__new__';
    const input = document.getElementById(prefix + 'TxExchange');
    input.classList.toggle('d-none', !isNew);
    if (isNew) input.focus();
}

// ── Live conversion preview below the exchange-rate field, same formula as DepotService#getAllPositions ──
function _updateTxExchangeRatePreview(prefix) {
    const previewEl = document.getElementById(prefix + 'TxExchangeRatePreview');
    if (!previewEl) return;

    const qtyFiat = parseFloat(document.getElementById(prefix + 'TxQuantityFiat').value);
    const rate    = parseFloat(document.getElementById(prefix + 'TxExchangeRate').value);

    if (!qtyFiat || !rate) {
        previewEl.textContent = '';
        return;
    }
    const converted = qtyFiat * rate;
    previewEl.textContent = '= ' + converted.toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' ' + CURRENCY.current();
}

function _showExchangeNewInput(prefix) {
    document.getElementById(prefix + 'TxExchange').classList.remove('d-none');
}

function _getExchangeValue(prefix) {
    const sel = document.getElementById(prefix + 'TxExchangeSelect');
    if (sel.value === '__new__') {
        return (document.getElementById(prefix + 'TxExchange').value || '').trim();
    }
    return sel.value;
}

function _setExchangeValue(prefix, label) {
    const sel = document.getElementById(prefix + 'TxExchangeSelect');
    sel.dataset.current = label;
    // re-populate the dropdown with the preselected value
    if (_positionsCache) {
        _fillExchangeDropdown(sel, _positionsCache);
    }
    document.getElementById(prefix + 'TxExchange').value = label;
}

// ── Add-modal specific fields (type-dependent visibility, transfer pairing) ─

function updateRelevantFields() {
    const selected = document.getElementById('addTxType').value;
    const isTrade  = selected === 'BUY' || selected === 'SELL';
    const isOut    = selected === 'TRANSFER_OUT';
    const isIn     = selected === 'TRANSFER_IN';

    document.querySelectorAll('.fiat-field').forEach(el => el.classList.toggle('d-none', !isTrade));
    document.querySelectorAll('.fee-field').forEach(el => el.classList.toggle('d-none', isIn));
    const transferSection = document.getElementById('transferPairSection');
    if (transferSection) transferSection.classList.toggle('d-none', !isOut);

    if (isOut) {
        _loadPositionsDropdown();
        // prefill date + quantity from the OUT fields
        const date = document.getElementById('addTxDate').value;
        const qty  = document.getElementById('addTxQty').value;
        const tDate = document.getElementById('transferInDate');
        const tQty  = document.getElementById('transferInQty');
        if (_fpTransferIn && !_fpTransferIn.selectedDates.length && date) {
            _fpTransferIn.setDate(date, false);
        }
        if (!tQty.value  && qty)  tQty.value  = qty;
    }
}

function _loadPositionsDropdown() {
    const sel = document.getElementById('transferTargetSelect');
    if (!sel || sel.dataset.loaded) return;
    fetch('/api/btc-tracking/positions')
        .then(r => r.json())
        .then(data => {
            sel.innerHTML =
                '<option value="">— Select position —</option>' +
                data.map(p => `<option value="${esc(p.label)}">${esc(p.label)}</option>`).join('') +
                '<option value="__new__">＋ New position...</option>';
            sel.dataset.loaded = '1';
        });
}

// ── Open modals ────────────────────────────────────────────

async function openAddTx(tx) {
    await _ensurePositionsLoaded('add');
    // Reset new-position input
    document.getElementById('addTxExchange').classList.add('d-none');
    document.getElementById('addTxExchange').value = '';

    if (tx !== undefined) {
       document.getElementById('addTxId').value           = '';
       if (_fpAdd) _fpAdd.setDate(tx.date ? tx.date.substring(0, 19) : '', false);
       document.getElementById('addTxType').value         = tx.type;
       document.getElementById('addTxQty').value          = tx.quantity;
       document.getElementById('addTxQuantityFiat').value = tx.quantityFiat || '';
       _setExchangeValue('add', tx.positionLabel || '');
       document.getElementById('addTxFees').value         = tx.fees || '';
       document.getElementById('addTxFeesCurrency').value = tx.feesCurrency || 'EUR';
       document.getElementById('addTxCurrency').value     = tx.currency || 'EUR';
       document.getElementById('addTxExchangeRate').value = tx.exchangeRate || '1';
       document.getElementById('addTxComment').value      = tx.comment || '';
       const isTrade = tx.type === 'BUY' || tx.type === 'SELL';
       document.querySelectorAll('.fiat-field').forEach(el => el.classList.toggle('d-none', !isTrade));
   } else {
        document.getElementById('addTxId').value           = '';
        var now = new Date();
        now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
        if (_fpAdd) _fpAdd.setDate(now, false);
        document.getElementById('addTxType').value         = 'BUY';
        document.getElementById('addTxQty').value          = '';
        document.getElementById('addTxQuantityFiat').value = '';
        document.getElementById('addTxExchange').value     = '';
        document.getElementById('addTxFees').value         = '';
        document.getElementById('addTxFeesCurrency').value = CURRENCY.current();
        document.getElementById('addTxCurrency').value     = CURRENCY.current();
        document.getElementById('addTxExchangeRate').value = '1';
        document.getElementById('addTxComment').value      = '';
        document.querySelectorAll('.fiat-field').forEach(el => el.classList.toggle('d-none', false));
        _setExchangeValue('add', '');
    }
    updateRelevantFields();
    _updateTxExchangeRatePreview('add');
    if (!txModalAdd) txModalAdd = new bootstrap.Modal(document.getElementById('txModalAdd'));
    txModalAdd.show();
}

async function openEditTx(tx) {
    await _ensurePositionsLoaded('edit');
    // Reset new-position input
    document.getElementById('editTxExchange').classList.add('d-none');
    document.getElementById('editTxExchange').value = '';
    document.getElementById('editTxId').value           = tx.id;
    if (_fpEdit) _fpEdit.setDate(tx.date ? tx.date.substring(0, 19) : '', false);

    document.getElementById('editTxType').value         = tx.type;
    document.getElementById('editTxQty').value          = tx.quantity;
    document.getElementById('editTxQuantityFiat').value = tx.quantityFiat || '';
    _setExchangeValue('edit', tx.positionLabel || '');
    document.getElementById('editTxFees').value         = tx.fees || '';
    document.getElementById('editTxFeesCurrency').value = tx.feesCurrency || 'EUR';
    document.getElementById('editTxCurrency').value     = tx.currency || 'EUR';
    document.getElementById('editTxExchangeRate').value = tx.exchangeRate || '1';
    document.getElementById('editTxComment').value      = tx.comment || '';

    document.getElementById('editTxType').disabled = true;
    const isTrade = tx.type === 'BUY' || tx.type === 'SELL';
    document.querySelectorAll('.fiat-field').forEach(el => el.classList.toggle('d-none', !isTrade));
    document.querySelectorAll('.fee-field').forEach(el => el.classList.toggle('d-none', tx.type === 'TRANSFER_IN'));
    _updateTxExchangeRatePreview('edit');

    if (!txModal) txModal = new bootstrap.Modal(document.getElementById('txModal'));
    txModal.show();
}

// ── Save (Add or Edit) ─────────────────────────────────────

function saveOrAddTx(isAdd) {
    const pref    = isAdd ? 'add' : 'edit';
    const id      = document.getElementById(pref + 'TxId').value;
    const dateVal = document.getElementById(pref + 'TxDate').value;
    const txType  = document.getElementById(pref + 'TxType').value;
    const isTrade = txType === 'BUY' || txType === 'SELL';

    const payload = {
        date:         dateVal ? dateVal : null,
        type:         txType,
        quantity:     parseFloat(document.getElementById(pref + 'TxQty').value) || 0,
        quantityFiat: parseFloat(document.getElementById(pref + 'TxQuantityFiat').value) || 0,
        fees:         txType === 'TRANSFER_IN' ? 0 : (parseFloat(document.getElementById(pref + 'TxFees').value) || 0),
        feesCurrency: isTrade ? (document.getElementById(pref + 'TxFeesCurrency').value || CURRENCY.current()) : null,
        currency:     isTrade ? (document.getElementById(pref + 'TxCurrency').value || CURRENCY.current()) : null,
        exchangeRate: isTrade ? (parseFloat(document.getElementById(pref + 'TxExchangeRate').value) || 1) : null,
        comment:      document.getElementById(pref + 'TxComment').value,
        exchange:     _getExchangeValue(pref)
    };

    // TRANSFER_OUT pairing (Add-modal only)
    if (isAdd && txType === 'TRANSFER_OUT') {
        const selEl  = document.getElementById('transferTargetSelect');
        let target   = selEl.value === '__new__'
            ? (document.getElementById('transferTargetNew').value || '').trim()
            : selEl.value;
        if (target) {
            payload.transferTarget    = target;
            const tDate = document.getElementById('transferInDate').value;
            const tQty  = document.getElementById('transferInQty').value;
            payload.transferInDate     = tDate ? tDate : null;
            payload.transferInQuantity = parseFloat(tQty) || null;
        }
    }

    const url    = isAdd ? '/api/btc-tracking/transactions' : '/api/btc-tracking/transactions/' + id;
    const method = isAdd ? 'POST' : 'PUT';
    fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(payload)
    })
    .then(r => r.json())
    .then(() => {
        (isAdd ? txModalAdd : txModal).hide();
        // Reset dropdown loaded-flag so next open refreshes positions
        const sel = document.getElementById('transferTargetSelect');
        if (sel) delete sel.dataset.loaded;
        _positionsCache = null;
        if (typeof onTxSaved === 'function') onTxSaved();
        showToast('✓ ' + t(isAdd ? 'toast.txAdded' : 'toast.txUpdated'), 'success');
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

// ── Toast ─────────────────────────────────────────────────
function showToast(msg, type) {
    const toast = document.getElementById('statusToast');
    if (!toast) return;
    toast.innerHTML = msg.replace(/\n/g, '<br>');
    toast.className = 'depot-toast ' + (type || '');
    toast.classList.remove('d-none');
    setTimeout(() => toast.classList.add('d-none'), 5000);
}
