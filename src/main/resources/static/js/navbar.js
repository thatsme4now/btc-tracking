'use strict';

// ── Shared Navbar (alle 4 Seiten) ───────────────────────────────────────────
// Preis-Badge, Kurs-Refresh, Settings-Modal (Sprache/Währung/Schriftart),
// Sats-Modal und Hilfe-Link — vorher Teil von depot.js und dadurch nur auf der
// Hauptseite verfügbar. Jetzt hier, zusammen mit dem gemeinsamen
// fragments/navbar.html + fragments/navbar-modals.html, auf allen 4 Seiten
// geladen. Benötigt: i18n.js, currency.js, theme.js (FONTS/applyFont),
// offline.js (OFFLINE), bootstrap.bundle.min.js.

// ── BTC-Preis-Badge ──────────────────────────────────────────────────────
// Lädt den aktuellen Kurs clientseitig über den bestehenden
// /api/btc-tracking/current-price Endpoint (schon von yearly.js genutzt),
// statt wie früher nur serverseitig ins Model der Hauptseite gepackt zu
// werden — damit die Badge auf allen 4 Seiten gleich funktioniert, ohne dass
// jede Controller-Methode (flow()/holdings()/yearly()) eigene Model-Attribute
// braucht.
function initNavbarPriceBadge() {
    const badge = document.getElementById('btcPriceBadge');
    if (!badge) return;

    const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';
    fetch('/api/btc-tracking/current-price?currency=' + encodeURIComponent(currency))
        .then(r => r.json())
        .then(data => {
            const display = document.getElementById('btcPriceDisplay');
            if (display) display.textContent = _navbarFormatPrice(data.price, data.currency || currency);
            badge.classList.remove('d-none');
        })
        .catch(() => { /* Badge bleibt ausgeblendet, kein Toast nötig für einen reinen Anzeige-Fetch */ });
}

function _navbarFormatPrice(price, currency) {
    return Number(price).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' ' + currency;
}

// ── BTC Price inline edit ─────────────────────────────────
function openPriceEdit() {
    const badge  = document.getElementById('btcPriceBadge');
    const editor = document.getElementById('btcPriceEditor');
    // Read current display value → strip formatting
    const raw = document.getElementById('btcPriceDisplay')
        .textContent.slice(0, -4).replace(/[^\d,]/g, '').replace(',', '.');

    document.getElementById('btcPriceInput').value = parseFloat(raw) || '';
    badge.classList.add('d-none');
    editor.classList.remove('d-none');
    editor.classList.add('d-flex');
    document.getElementById('btcPriceInput').focus();
}

function closePriceEdit() {
    document.getElementById('btcPriceBadge').classList.remove('d-none');
    const editor = document.getElementById('btcPriceEditor');
    editor.classList.add('d-none');
    editor.classList.remove('d-flex');
}

function savePriceEdit() {
    const price = parseFloat(document.getElementById('btcPriceInput').value);
    if (!price || price <= 0) {
        showToast('✗ ' + t('toast.error') + ': invalid price', 'error');
        return;
    }
    const currency = (typeof CURRENCY !== 'undefined') ? CURRENCY.current() : 'EUR';

    fetch('/api/btc-tracking/current-price', {
        method:  'PUT',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ price, currency })
    })
    .then(r => r.json())
    .then(data => {
        if (data.error) { showToast('✗ ' + data.error, 'error'); return; }
        // Update badge display without full reload
        document.getElementById('btcPriceDisplay').textContent = _navbarFormatPrice(data.price, data.currency || currency);
        closePriceEdit();
        showToast('✓ BTC price updated', 'success');
        setTimeout(() => window.location.reload(), 1000);
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

// ── Refresh Prices ────────────────────────────────────────
// Lädt ausschließlich den aktuellen Kurs — das Nachladen fehlender
// Monatspreise (Backfill) passiert bewusst nicht hier, sondern ausschließlich
// über den eigenen "Preise laden"-Button auf der Jahresansicht-Seite
// (yearlyLoadPrices() in yearly.js).
function refreshPrices() {
    if (!OFFLINE.isOnline()) {
        const modal = bootstrap.Modal.getInstance(document.getElementById('offlineConfirmModal'))
            || new bootstrap.Modal(document.getElementById('offlineConfirmModal'));
        modal.show();
        return;
    }
    _doRefresh();
}

function confirmOfflineRefresh() {
    bootstrap.Modal.getInstance(document.getElementById('offlineConfirmModal'))?.hide();
    _doRefresh();
}

function _doRefresh() {
    const btn = document.getElementById('btnRefresh');
    btn.disabled = true;
    btn.innerHTML = `<span class="depot-spinner"></span>${t('toast.refreshLoading')}`;

    fetch('/api/btc-tracking/refresh?currency=' + CURRENCY.current(), { method: 'POST' })
        .then(r => r.json())
        .then(data => {
            if (data.error) throw new Error(data.error);
            const n = data.totalNew || 0;
            showToast('✓ ' + n + ' ' + t('toast.refreshSuccess'), 'success');
            setTimeout(() => window.location.reload(), 1800);
        })
        .catch(err => {
            showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error');
            btn.disabled = false;
            btn.innerHTML = `<i class="bi bi-arrow-clockwise me-1"></i>${t('nav.btn.refresh')}`;
        });
}

// ── Settings Modal (Sprache / Währung / Schriftart / Steuer-Stichtag) ──
let settingsModal = null;
let _taxCutoffOriginal = ''; // zuletzt vom Server geladener Wert, zum Änderungs-Check beim Speichern

function openSettings() {
    // Steuer-Stichtag (Haltefrist-Wegfall) — asynchron nachladen, damit das
    // Öffnen des Modals nicht auf den Request wartet; Wert kommt i.d.R. quasi
    // sofort aus dem lokalen Backend.
    fetch('/api/btc-tracking/settings')
        .then(r => r.json())
        .then(data => {
            _taxCutoffOriginal = data.taxHoldingPeriodCutoffDate || '';
            document.getElementById('taxCutoffDateInput').value = _taxCutoffOriginal;
        })
        .catch(() => { /* Feld bleibt leer, falls Abruf fehlschlägt */ });
    const langContainer = document.getElementById('langOptions');
    const supported     = I18N.supported();
    const currentLang   = I18N.currentLang();

    langContainer.innerHTML = Object.entries(supported).map(([code, label]) => `
        <label class="d-flex align-items-center gap-2" style="cursor:pointer">
            <input type="radio" name="langChoice" value="${code}"
                   ${code === currentLang ? 'checked' : ''}
                   style="accent-color:var(--accent)"/>
            <span style="font-size:.82rem;color:var(--text)">${label}</span>
        </label>
    `).join('');

    const curContainer = document.getElementById('currencyOptions');
    const currentCur   = CURRENCY.current();

    curContainer.innerHTML = CURRENCY.all().map(c => `
        <label class="d-flex align-items-center gap-2" style="cursor:pointer">
            <input type="radio" name="curChoice" value="${c.code}"
                   ${c.code === currentCur ? 'checked' : ''}
                   style="accent-color:var(--accent)"/>
            <span style="font-size:.82rem;color:var(--text)">
                ${c.code} <span style="color:var(--text-muted)">${c.symbol}</span>
            </span>
        </label>
    `).join('');

    const fontContainer = document.getElementById('fontOptions');
    const currentFont   = localStorage.getItem('depot-font') || 'ibm';

    fontContainer.innerHTML = FONTS.map(f => `
        <label class="d-flex align-items-center gap-2" style="cursor:pointer">
            <input type="radio" name="fontChoice" value="${f.key}"
                   ${f.key === currentFont ? 'checked' : ''}
                   style="accent-color:var(--accent)"/>
            <span style="font-size:.82rem;font-family:${f.family};color:var(--text)">${f.label}</span>
        </label>
    `).join('');

    if (!settingsModal) settingsModal = new bootstrap.Modal(document.getElementById('settingsModal'));
    settingsModal.show();
}

function saveSettings() {
    const selLang = document.querySelector('input[name="langChoice"]:checked');
    const selCur  = document.querySelector('input[name="curChoice"]:checked');
    const selFont = document.querySelector('input[name="fontChoice"]:checked');
    const taxCutoffValue = document.getElementById('taxCutoffDateInput').value || '';

    const langChanged      = selLang && selLang.value !== I18N.currentLang();
    const curChanged       = selCur  && selCur.value  !== CURRENCY.current();
    const taxCutoffChanged = taxCutoffValue !== _taxCutoffOriginal;

    if (selFont) applyFont(selFont.value);

    // Sprache, Währung UND der Steuer-Stichtag beeinflussen serverseitig
    // berechnete/gerenderte Werte (Zahl-/Datumsformate, positionsbezogene
    // Beträge in der gewählten Anzeigewährung, Steuerfrei/-pflichtig-Badges
    // auf Yearly-/Flow-Seite usw.) auf jeder Seite — punktuelles Nachladen
    // einzelner Tabellen lässt an anderer Stelle veraltete/falsche Werte
    // stehen. Deshalb bei jeder Änderung ein vollständiger Reload statt
    // clientseitigem Nachziehen.
    if (langChanged || curChanged || taxCutoffChanged) {
        if (selLang) I18N.setLanguage(selLang.value); // persistiert sofort in localStorage
        if (curChanged) CURRENCY.setCurrency(selCur.value);

        const savePromise = taxCutoffChanged
            ? fetch('/api/btc-tracking/settings', {
                  method: 'PUT',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ taxHoldingPeriodCutoffDate: taxCutoffValue || null })
              })
            : Promise.resolve();

        savePromise
            .catch(() => showToast('✗ ' + t('toast.error'), 'error'))
            .then(() => {
                settingsModal.hide();
                showToast('✓ ' + t('toast.settingsReload'), 'success');
                setTimeout(() => window.location.reload(), 600);
            });
        return;
    }

    settingsModal.hide();
}

// ── Send some Sats ──────────────────────────────────────────
function sendSomeSats() {
    const modal = bootstrap.Modal.getInstance(document.getElementById('sendsomesatsModal'))
        || new bootstrap.Modal(document.getElementById('sendsomesatsModal'));
    modal.show();
}

function copySatsAddress() {
    const addr = document.getElementById('satsAddress').textContent;
    navigator.clipboard.writeText(addr).then(() => {
        const btn = document.getElementById('btnCopySats');
        btn.innerHTML = '<i class="bi bi-check-lg"></i>';
        setTimeout(() => btn.innerHTML = '<i class="bi bi-copy"></i>', 1500);
    });
}

// ── Hilfe ────────────────────────────────────────────────
function openHelp() {
    if (I18N.currentLang() == "de") {
        window.open('https://thatsme4now.github.io/btc-tracking/de', '_blank');
    } else {
        window.open('https://thatsme4now.github.io/btc-tracking/', '_blank');
    }
}

// ── Bootstrap ────────────────────────────────────────────
// Self-initialisierend (wie applock.js) — läuft automatisch auf jeder Seite,
// die navbar.js einbindet, ohne dass die jeweilige Seite es explizit aus
// ihrem eigenen Bootstrap-Skript aufrufen muss.
I18N.ready.then(() => {
    initNavbarPriceBadge();
    if (typeof OFFLINE !== 'undefined') OFFLINE.init();
});
