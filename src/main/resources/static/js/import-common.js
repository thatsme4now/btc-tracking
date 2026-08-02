'use strict';
// Gemeinsame Helfer für die 3 Import-Assistent-Seiten (import-mapping.js,
// import-review.js, import-status.js). Bewusst eigenständig statt depot.js/
// tx-form.js mitzuladen — diese enthalten überwiegende Logik, die auf
// Übersicht-spezifische DOM-Elemente (#overviewGrid, #txTable, ...) angewiesen
// ist, die auf den Import-Seiten nicht existieren.

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

/** fetch()-Wrapper: wirft bei !ok, parst sonst JSON (oder null bei leerem Body). */
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

/** Bricht den laufenden Import ab (leert die Staging-Tabelle) und kehrt zur Übersicht zurück. */
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
