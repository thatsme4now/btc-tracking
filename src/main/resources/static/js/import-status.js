'use strict';
// Step 3 des Import-Assistenten: Ergebnis-Anzeige. Liest primär das Ergebnis
// des soeben abgeschlossenen Imports aus sessionStorage (vom Confirm-Aufruf
// in import-review.js) — bei direktem Aufruf/Reload ohne diese Daten wird
// stattdessen der letzte Historien-Eintrag als Fallback geladen (nur
// Kennzahlen, keine Zeilen-Details mehr verfügbar).

function metricCard(labelKey, value, colorVar) {
    return `
        <div class="col-6 col-md-3">
            <div class="depot-card-metric" style="border:1px solid var(--border);border-radius:var(--radius)">
                <div class="depot-label" data-i18n="${labelKey}"></div>
                <div style="font-size:var(--density-metric,1.4rem);font-weight:600;${colorVar ? 'color:' + colorVar : ''}">${value}</div>
            </div>
        </div>`;
}

function renderFromResult(result) {
    document.getElementById('statusHeadline').innerHTML =
        `<i class="bi bi-check-circle me-2" style="color:var(--pos)"></i><span data-i18n="import.status.headline">Import abgeschlossen</span>`;

    document.getElementById('statusMetrics').innerHTML =
        metricCard('import.status.metric.total', result.totalRows ?? '–') +
        metricCard('import.status.metric.imported', result.importedRows ?? '–', 'var(--pos)') +
        metricCard('import.status.metric.duplicates', result.duplicateRows ?? '–', 'var(--warn-duplicate)') +
        metricCard('import.status.metric.errors', result.errorRows ?? '–', result.errorRows ? 'var(--neg)' : null);

    if (result.errors && result.errors.length) {
        document.getElementById('statusErrorsCard').classList.remove('d-none');
        document.getElementById('statusErrorsBody').innerHTML = result.errors.map(e => `
            <tr>
                <td>${esc(e.date || '–')}</td>
                <td>${esc(e.positionLabel || '–')}</td>
                <td>${esc(e.type || '–')}</td>
                <td style="color:var(--neg)">${esc(errorReasonText(e.reason))}</td>
            </tr>`).join('');
    }
    I18N.applyI18n();
}

function renderFromHistoryFallback(entry) {
    document.getElementById('statusFallbackNote').classList.remove('d-none');
    if (!entry) {
        document.getElementById('statusHeadline').innerHTML =
            `<i class="bi bi-info-circle me-2"></i><span data-i18n="import.status.noData">Keine Import-Daten verfügbar.</span>`;
        I18N.applyI18n();
        return;
    }
    document.getElementById('statusHeadline').innerHTML =
        `<i class="bi bi-check-circle me-2" style="color:var(--pos)"></i><span>${esc(entry.filename)}</span>`;
    document.getElementById('statusMetrics').innerHTML =
        metricCard('import.status.metric.total', entry.totalRows) +
        metricCard('import.status.metric.imported', entry.importedRows, 'var(--pos)') +
        metricCard('import.status.metric.duplicates', entry.duplicateRows, 'var(--warn-duplicate)') +
        metricCard('import.status.metric.errors', entry.errorRows, entry.errorRows ? 'var(--neg)' : null);
    I18N.applyI18n();
}

I18N.ready.then(() => {
    I18N.applyI18n();
    const raw = sessionStorage.getItem('depot-import-result');
    if (raw) {
        sessionStorage.removeItem('depot-import-result');
        try {
            renderFromResult(JSON.parse(raw));
            return;
        } catch (e) { /* fall through to fallback */ }
    }
    fetchJSON('/api/btc-tracking/import/history?limit=1')
        .then(list => renderFromHistoryFallback(list && list.length ? list[0] : null))
        .catch(() => renderFromHistoryFallback(null));
});
