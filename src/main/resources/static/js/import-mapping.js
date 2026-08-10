'use strict';
// Import wizard step 1: column mapping + live preview, runs client-side against IMPORT_HEADERS/IMPORT_ROWS embedded by the server on upload.

let _positionsCache = null;

const FIELDS = [
    { id: 'map_typ',          labelKey: 'table.col.type',                     required: true  },
    { id: 'map_date',         labelKey: 'table.col.date',                     required: true  },
    { id: 'map_time',         labelKey: 'csv.import.mapping.time',            required: false },
    { id: 'map_exchange',     labelKey: 'table.wallets',                      required: true  },
    { id: 'map_buyQty',       labelKey: 'csv.import.mapping.buy.quantity',    required: true  },
    { id: 'map_buyCur',       labelKey: 'csv.import.mapping.buy.currency',    required: true  },
    { id: 'map_sellQty',      labelKey: 'csv.import.mapping.sell.quantity',   required: true  },
    { id: 'map_sellCur',      labelKey: 'csv.import.mapping.sell.currency',   required: true  },
    { id: 'map_fee',          labelKey: 'table.col.fees',                     required: false },
    { id: 'map_feeCur',       labelKey: 'csv.import.mapping.fee.currency',    required: false },
    { id: 'map_exchangeRate', labelKey: 'csv.import.mapping.fee.exchange.rate', required: false },
    { id: 'map_comment',      labelKey: 'modal.field.comment',                required: false },
    { id: 'map_transactionId',labelKey: 'modal.field.transaction.id',         required: false },
    { id: 'map_transferId',   labelKey: 'modal.field.transfer.id',            required: false },
];

const FIELD_ALIASES = {
    map_typ:          ['Typ', 'typ', 'type', 'Type'],
    // 'Time' also matches as a date alias for single ISO-datetime column exports, see renderMappingTable()
    map_date:         ['Datum', 'datum', 'date', 'Date', 'Datetime', 'Time'],
    map_time:         ['Time', 'time', 'Zeit', 'Uhrzeit'],
    map_exchange:     ['Börse', 'boerse', 'exchange', 'Exchange', 'Börsen'],
    map_buyQty:       ['Kauf', 'kauf', 'buyQuantity', 'Buy Amount', 'buy_quantity', 'buy', 'buyQty', 'Amount'],
    map_buyCur:       ['Cur.', 'Cur._1', 'cur._1', 'buyCurrency', 'Buy Currency', 'buyCur', 'Amount unit', 'Unit'],
    map_sellQty:      ['Verkauf', 'verkauf', 'sellQuantity', 'Sell Amount', 'sell_quantity', 'sell', 'sellQty', 'Amount'],
    map_sellCur:      ['Cur._1', 'Cur._2', 'cur._2', 'sellCurrency', 'Sell Currency', 'sellCur', 'Amount unit', 'Unit'],
    map_fee:          ['Gebühr', 'gebuehr', 'fee', 'Fee', 'fees', 'Fees'],
    map_feeCur:       ['Cur._2', 'Cur._3', 'cur._3', 'feeCurrency', 'Fee Currency', 'feecur.', 'feeCur', 'Fee unit', 'Fee Unit'],
    map_exchangeRate: ['exchangeRate', 'exchange_rate', 'Wechselkurs'],
    map_comment:      ['Kommentar', 'kommentar', 'comment', 'Comment', 'Note'],
    map_transactionId:['transactionId', 'Transaction ID'],
    map_transferId:   ['transferId'],
};

const INTERNAL_TYPES = ['Trade', 'Einzahlung', 'Auszahlung', 'Selbst'];
const TYP_VALUE_ALIASES = {
    RECV: 'Einzahlung', RECEIVED: 'Einzahlung',
    SENT: 'Auszahlung',
    SELF: 'Selbst', SENT_TO_YOURSELF: 'Selbst',
};

function autoMatch(fieldId) {
    const aliases = FIELD_ALIASES[fieldId] || [];
    return IMPORT_HEADERS.find(h => aliases.includes(h)) || '';
}

function renderMappingTable() {
    const NONE = `<option value="">${t('modal.csv.field.notMapped')}</option>`;

    // if date and time match the same column, don't also prefill time (avoids double-appending it in computeMappedRows())
    const autoMatches = {};
    FIELDS.forEach(f => { autoMatches[f.id] = autoMatch(f.id); });
    if (autoMatches.map_time && autoMatches.map_time === autoMatches.map_date) {
        autoMatches.map_time = '';
    }

    const rowsHtml = FIELDS.map(f => {
        const matched = autoMatches[f.id];
        const opts = NONE + IMPORT_HEADERS.map(h =>
            `<option value="${esc(h)}" ${h === matched ? 'selected' : ''}>${esc(h)}</option>`
        ).join('');
        const extraOnchange =
            f.id === 'map_typ' ? 'refreshTypRemap(); recomputePreview();' :
            (f.id === 'map_date' || f.id === 'map_time') ? 'validateDateTimeMapping(); recomputePreview();' :
            f.id === 'map_exchange' ? 'updateExchangeWarning(); recomputePreview();' :
            'recomputePreview();';

        let row = `
            <tr id="mappingRow_${f.id}">
                <td class="depot-label pt-2" style="width:180px;white-space:nowrap">
                    ${t(f.labelKey)}${f.required ? ' <span style="color:var(--neg)">*</span>' : ''}
                </td>
                <td>
                    <select id="${f.id}" class="form-select depot-input form-select-sm" onchange="${extraOnchange}">
                        ${opts}
                    </select>
                </td>
            </tr>`;

        if (f.id === 'map_exchange') {
            row += `
            <tr id="mappingRow_map_exchangeFixed">
                <td class="depot-label pt-2" style="width:180px;white-space:nowrap">${t('csv.import.fixedExchange')}</td>
                <td>
                    <select id="map_exchangeFixed" class="form-select depot-input form-select-sm mb-1" onchange="onFixedExchangeChange()">
                        <option value="">${t('csv.import.fixedExchange.none')}</option>
                    </select>
                    <input type="text" id="map_exchangeFixedNew" class="form-control depot-input d-none"
                           placeholder="New position name" maxlength="100" oninput="updateExchangeWarning(); recomputePreview();"/>
                    <div class="form-text text-muted" style="font-size:.7rem">${t('csv.import.fixedExchange.hint')}</div>
                </td>
            </tr>`;
        }
        return row;
    }).join('');

    document.getElementById('mappingTable').innerHTML = rowsHtml;
    _loadFixedExchangeDropdown();
    updateExchangeWarning();
}

// Highlights the exchange mapping rows while neither option is set, see goToReview()'s missing.push('exchange')
function updateExchangeWarning() {
    const exchangeSel = document.getElementById('map_exchange');
    const fixedSel     = document.getElementById('map_exchangeFixed');
    const newInput     = document.getElementById('map_exchangeFixedNew');

    const mapped = !!(exchangeSel && exchangeSel.value !== '');
    let fixedOk = false;
    if (fixedSel && fixedSel.value !== '') {
        fixedOk = fixedSel.value === '__new__' ? !!(newInput && newInput.value.trim()) : true;
    }

    const warn = !mapped && !fixedOk;
    document.getElementById('mappingRow_map_exchange')?.classList.toggle('mapping-row-warning', warn);
    document.getElementById('mappingRow_map_exchangeFixed')?.classList.toggle('mapping-row-warning', warn);
}

function refreshTypRemap() {
    const typColEl = document.getElementById('map_typ');
    const container = document.getElementById('typRemapContainer');
    if (!typColEl) return;

    const typCol = typColEl.value;
    if (!typCol) {
        container.innerHTML = `<p style="font-size:.72rem;color:var(--text-muted)">${t('modal.csv.selectTypFirst')}</p>`;
        return;
    }

    const distinctVals = [...new Set(IMPORT_ROWS.map(r => (r[typCol] || '').trim()).filter(Boolean))].sort();
    if (!distinctVals.length) {
        container.innerHTML = `<p style="font-size:.72rem;color:var(--text-muted)">${t('modal.csv.noValues')}</p>`;
        return;
    }

    const rows = distinctVals.map(val => {
        const preselect = TYP_VALUE_ALIASES[val.toUpperCase()] || (INTERNAL_TYPES.includes(val) ? val : '');
        const opts = `<option value="">${t('modal.csv.field.ignore')}</option>` +
            INTERNAL_TYPES.map(tp => `<option value="${tp}" ${tp === preselect ? 'selected' : ''}>${tp}</option>`).join('');
        return `
        <tr>
            <td style="width:160px;font-size:.78rem;color:var(--text);padding:.3rem 0">
                <code style="background:var(--bg);padding:2px 6px;border-radius:3px">${esc(val)}</code>
            </td>
            <td style="padding:.3rem 0 .3rem .75rem">
                <i class="bi bi-arrow-right" style="color:var(--text-muted);margin-right:.5rem;font-size:.7rem"></i>
                <select class="form-select depot-input form-select-sm d-inline-block" style="width:auto;min-width:140px"
                        data-typ-source="${esc(val)}" onchange="recomputePreview()">
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
    const sel = document.getElementById('map_exchangeFixed');
    const newInput = document.getElementById('map_exchangeFixedNew');
    const exchangeSel = document.getElementById('map_exchange');
    const isNew = sel.value === '__new__';
    newInput.classList.toggle('d-none', !isNew);
    if (isNew) newInput.focus();
    exchangeSel.disabled = sel.value !== '';
    updateExchangeWarning();
    recomputePreview();
}

function _extractTimeValue(timeVal) {
    if (!timeVal) return '';
    // time token must stand alone (not surrounded by digits), avoids silently truncating malformed values like "1720:21"
    const match = timeVal.match(/(?<!\d)\d{1,2}:\d{2}(:\d{2})?(?!\d)/);
    return match ? match[0] : '';
}

function validateDateTimeMapping() {
    const dateSel = document.getElementById('map_date');
    const timeSel = document.getElementById('map_time');
    const warningEl = document.getElementById('dateTimeWarning');
    const nextBtn = document.getElementById('mappingNextBtn');
    if (!dateSel) return;

    const dateCol = dateSel.value;
    const timeCol = timeSel ? timeSel.value : '';

    let needsTime = false;
    if (dateCol) {
        const sampleRow = IMPORT_ROWS.find(r => (r[dateCol] || '').trim());
        const sample = sampleRow ? (sampleRow[dateCol] || '').trim() : '';
        if (sample && !sample.includes(':')) needsTime = true;
    }

    const blocked = needsTime && !timeCol;
    if (warningEl) warningEl.classList.toggle('d-none', !blocked);
    if (nextBtn) nextBtn.disabled = blocked;
}

// Builds the row list in the server's MappedRow format from the current mapping
function computeMappedRows() {
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
        transactionId:document.getElementById('map_transactionId')?.value,
        transferId:   document.getElementById('map_transferId')?.value,
    };

    const fixedSel = document.getElementById('map_exchangeFixed');
    const fixedActive = fixedSel && fixedSel.value !== '';
    const fixedExchange = fixedActive
        ? (fixedSel.value === '__new__' ? (document.getElementById('map_exchangeFixedNew').value || '').trim() : fixedSel.value)
        : '';

    const typRemap = {};
    document.querySelectorAll('#typRemapContainer [data-typ-source]').forEach(sel => {
        const src = sel.getAttribute('data-typ-source');
        const dst = sel.value;
        if (dst) typRemap[src] = dst;
    });

    return IMPORT_ROWS.map(r => {
        const rawTyp = mapping.typ ? (r[mapping.typ] || '').trim() : '';
        const mappedTyp = typRemap[rawTyp] || null;
        if (!mappedTyp) return null;

        let dateValue = mapping.date ? (r[mapping.date] || '').trim() : null;
        if (mapping.time && dateValue) {
            const rawTime = (r[mapping.time] || '').trim();
            if (rawTime) {
                const cleanTime = _extractTimeValue(rawTime);
                // append the cleaned time if recognized, otherwise fall back to the raw value so the row still shows a useful error
                dateValue = dateValue + ' ' + (cleanTime || rawTime);
            }
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
}

function renderPreviewTable(rows) {
    const body = document.getElementById('previewTableBody');
    document.getElementById('mappingRowCount').textContent = IMPORT_ROWS.length;

    if (!rows.length) {
        body.innerHTML = `<tr><td colspan="13" class="text-center text-muted py-3" data-i18n="import.preview.empty">Keine zuordenbaren Zeilen — Zuordnung prüfen.</td></tr>`;
        I18N.applyI18n();
        return;
    }

    body.innerHTML = rows.slice(0, 500).map(r => `
        <tr>
            <td>${esc(r.typ)}</td>
            <td>${esc(r.date)}</td>
            <td>${esc(r.exchange)}</td>
            <td class="text-end">${esc(r.buyQuantity)}</td>
            <td>${esc(r.buyCurrency)}</td>
            <td class="text-end">${esc(r.sellQuantity)}</td>
            <td>${esc(r.sellCurrency)}</td>
            <td class="text-end">${esc(r.fee)}</td>
            <td>${esc(r.feeCurrency)}</td>
            <td class="text-end">${esc(r.exchangeRate)}</td>
            <td>${esc(r.comment)}</td>
            <td>${esc(r.transactionId)}</td>
            <td>${esc(r.transferId)}</td>
        </tr>`).join('') +
        (rows.length > 500
            ? `<tr><td colspan="13" class="text-center text-muted py-2" style="font-size:.72rem">… +${rows.length - 500}</td></tr>`
            : '');
}

function recomputePreview() {
    const rows = computeMappedRows();
    renderPreviewTable(rows);
}

function goToReview() {
    const mapping = {
        typ: document.getElementById('map_typ')?.value,
        date: document.getElementById('map_date')?.value,
        exchange: document.getElementById('map_exchange')?.value,
    };
    const fixedSel = document.getElementById('map_exchangeFixed');
    const fixedActive = fixedSel && fixedSel.value !== '';
    const fixedExchange = fixedActive
        ? (fixedSel.value === '__new__' ? (document.getElementById('map_exchangeFixedNew').value || '').trim() : fixedSel.value)
        : '';

    const missing = [];
    if (!mapping.typ) missing.push('typ');
    if (!mapping.date) missing.push('date');
    if (fixedActive ? !fixedExchange : !mapping.exchange) missing.push('exchange');
    if (missing.length) {
        showToast('✗ ' + t('toast.csvValidation') + ': ' + missing.join(', '), 'error');
        return;
    }

    const rows = computeMappedRows();
    if (!rows.length) {
        showToast('✗ ' + t('toast.csvNoRows'), 'error');
        return;
    }

    const btn = document.getElementById('mappingNextBtn');
    btn.disabled = true;
    fetchJSON('/api/btc-tracking/import/stage', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rows })
    }).then(result => {
        sessionStorage.setItem('depot-import-filename', IMPORT_FILENAME || 'import.csv');
        sessionStorage.setItem('depot-import-total-rows', String(IMPORT_ROWS.length));
        sessionStorage.setItem('depot-import-skipped-no-btc', String((result && result.skippedNoBtc) || 0));
        window.location.href = '/btc-tracking/import/review';
    }).catch(err => {
        btn.disabled = false;
        showToast('✗ ' + t('toast.importError') + ': ' + err.message, 'error');
    });
}

I18N.ready.then(() => {
    I18N.applyI18n();
    renderMappingTable();
    refreshTypRemap();
    validateDateTimeMapping();
    recomputePreview();
});
