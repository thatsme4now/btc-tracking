'use strict';
// Shared helpers for the 3 import wizard pages; kept standalone since depot.js/tx-form.js depend on overview-only DOM elements.

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function showToast(msg, type) {
    const toast = document.getElementById('statusToast');
    if (!toast) return;
    toast.innerHTML = String(msg).replace(/\n/g, '<br>');
    toast.className = 'depot-toast ' + (type || '');
    toast.classList.remove('d-none');
    setTimeout(() => toast.classList.add('d-none'), 5000);
}

function formatEur(val) {
    return CURRENCY.format(val);
}

// fetch() wrapper: throws on !ok, otherwise parses JSON (or null for an empty body)
function fetchJSON(url, options) {
    return fetch(url, options).then(async r => {
        const text = await r.text();
        const data = text ? JSON.parse(text) : null;
        if (!r.ok) {
            const message = (data && data.error) ? data.error : ('HTTP ' + r.status);
            throw new Error(message);
        }
        return data;
    });
}

const ERROR_REASON_KEYS = {
    invalid_date: 'import.error.invalid_date',
    missing_exchange: 'import.error.missing_exchange',
    unknown_currency: 'import.error.unknown_currency',
    unknown_type: 'import.error.unknown_type',
    invalid_quantity: 'import.error.invalid_quantity',
    transaction_id_exists: 'import.error.transaction_id_exists',
    save_failed: 'import.error.save_failed',
    invalid_data: 'import.error.invalid_data',
};

function errorReasonText(reason) {
    if (!reason) return '';
    return reason.split(',').map(r => t(ERROR_REASON_KEYS[r.trim()] || r)).join(', ');
}

// Cancels the running import (clears the staging table) and returns to the overview
function cancelImportWizard() {
    fetchJSON('/api/btc-tracking/import/cancel', { method: 'POST' })
        .catch(() => {})
        .finally(() => {
            sessionStorage.removeItem('depot-import-filename');
            sessionStorage.removeItem('depot-import-total-rows');
            window.location.href = '/btc-tracking';
        });
}

I18N.ready.then(() => I18N.applyI18n());
