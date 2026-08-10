'use strict';

// ── Shared navbar (all 4 pages): price badge, settings modal, sats modal, help link. Requires i18n.js, currency.js, theme.js, bootstrap.bundle.min.js. ──

// ── BTC price badge, loaded client-side via /api/btc-tracking/current-price so it works on all 4 pages ──
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
        .catch(() => { /* badge stays hidden, no toast needed for a display-only fetch */ });
}

function _navbarFormatPrice(price, currency) {
    return Number(price).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' ' + currency;
}

// ── BTC price inline edit ─────────────────────────────────
function openPriceEdit() {
    const badge  = document.getElementById('btcPriceBadge');
    const editor = document.getElementById('btcPriceEditor');
    // strip formatting from the current display value
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
        document.getElementById('btcPriceDisplay').textContent = _navbarFormatPrice(data.price, data.currency || currency);
        closePriceEdit();
        showToast('✓ BTC price updated', 'success');
        setTimeout(() => window.location.reload(), 1000);
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

// ── Settings modal (language / currency / font / tax cutoff date) ──
let settingsModal = null;
let _taxCutoffOriginal = ''; // last value loaded from server, used to detect changes on save

function openSettings() {
    // load tax cutoff date async so opening the modal doesn't wait on the request
    fetch('/api/btc-tracking/settings')
        .then(r => r.json())
        .then(data => {
            _taxCutoffOriginal = data.taxHoldingPeriodCutoffDate || '';
            document.getElementById('taxCutoffDateInput').value = _taxCutoffOriginal;
        })
        .catch(() => { /* field stays empty if the fetch fails */ });
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
    // font selection is disabled in 21-mode, see theme.js toggleMode21()
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

    if (selFont && !document.body.classList.contains('mode-21')) applyFont(selFont.value);

    // Language/currency/tax cutoff affect server-rendered values across every
    // page, so any change triggers a full reload instead of partial refresh.
    if (langChanged || curChanged || taxCutoffChanged) {
        if (selLang) I18N.setLanguage(selLang.value); // persists to localStorage immediately
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

// ── Help: docs served statically from this app under /docs/** ──
function openHelp() {
    const lang = I18N.currentLang();
    const supported = ["de", "it", "fr", "es", "th"];
    const path = supported.includes(lang) ? `/docs/${lang}/index.html` : '/docs/index.html';
    window.open(path, '_blank');
}

// ── Bootstrap: self-initializing, like applock.js ──
I18N.ready.then(() => {
    initNavbarPriceBadge();
});
