'use strict';

// ── Shared Navbar (alle 4 Seiten) ───────────────────────────────────────────
// Preis-Badge (manuell editierbar), Settings-Modal (Sprache/Währung/Schriftart),
// Sats-Modal und Hilfe-Link — vorher Teil von depot.js und dadurch nur auf der
// Hauptseite verfügbar. Jetzt hier, zusammen mit dem gemeinsamen
// fragments/navbar.html + fragments/navbar-modals.html, auf allen 4 Seiten
// geladen. Benötigt: i18n.js, currency.js, theme.js (FONTS/applyFont),
// bootstrap.bundle.min.js.

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
    const currentFont   = localStorage.getItem('depot-font') || 'inter';
    // Im Einundzwanzig-Modus ist Dark-Theme + Inconsolata fix — Font-Auswahl
    // wird gesperrt (disabled), siehe toggleMode21()/toggleTheme() in theme.js.
    const mode21Active  = document.body.classList.contains('mode-21');

    fontContainer.innerHTML = FONTS.map(f => `
        <label class="d-flex align-items-center gap-2" style="cursor:${mode21Active ? 'not-allowed' : 'pointer'};opacity:${mode21Active ? '.5' : '1'}">
            <input type="radio" name="fontChoice" value="${f.key}"
                   ${f.key === currentFont ? 'checked' : ''}
                   ${mode21Active ? 'disabled' : ''}
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

    // Font bleibt im Einundzwanzig-Modus fix auf Inconsolata — Radios sind
    // in openSettings() bereits disabled, hier zusätzlich defensiv geprüft.
    if (selFont && !document.body.classList.contains('mode-21')) applyFont(selFont.value);

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
    const lang = I18N.currentLang();
    const supported = ["de", "it", "fr", "es", "th"];
    const path = supported.includes(lang)
        ? `https://thatsme4now.github.io/btc-tracking/${lang}`
        : 'https://thatsme4now.github.io/btc-tracking/';
    window.open(path, '_blank');
}

// ── Bootstrap ────────────────────────────────────────────
// Self-initialisierend (wie applock.js) — läuft automatisch auf jeder Seite,
// die navbar.js einbindet, ohne dass die jeweilige Seite es explizit aus
// ihrem eigenen Bootstrap-Skript aufrufen muss.
I18N.ready.then(() => {
    initNavbarPriceBadge();
});
