// ── Theme Toggle ──────────────────────────────────────────
(function initTheme() {
    const saved = localStorage.getItem('depot-theme') || 'dark';
    applyTheme(saved);
})();

function toggleTheme() {
    const current = document.body.classList.contains('light') ? 'light' : 'dark';
    const next    = current === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    localStorage.setItem('depot-theme', next);
}

function applyTheme(theme) {
    document.body.classList.toggle('light', theme === 'light');
    const icon = document.getElementById('themeIcon');
    if (icon) {
        icon.className = theme === 'light' ? 'bi bi-sun-fill' : 'bi bi-moon-fill';
    }
}

'use strict';

const CHART_COLORS = [
    '#F7931A','#1D9E75','#378ADD','#534AB7','#D85A30',
    '#BA7517','#185FA5','#0F6E56','#3C3489','#993C1D'
];

const APEX_DEFAULTS = {
    chart:   { background: 'transparent', fontFamily: "'IBM Plex Mono', monospace" },
    theme:   { mode: 'dark' },
    tooltip: {
        theme: 'dark',
        style: { fontFamily: "'IBM Plex Mono', monospace", fontSize: '12px' }
    },
    grid: { borderColor: '#252830' }
};
// ── Density Toggle ────────────────────────────────────────
//const DENSITY_CYCLE = ['default', 'comfortable', 'spacious'];
//const DENSITY_ICONS = { default: 'bi-type', comfortable: 'bi-type-bold', spacious: 'bi-type-h1' };
//const DENSITY_LABELS = { default: 'A', comfortable: 'A+', spacious: 'A++' };

const DENSITY_CYCLE = ['default', 'comfortable'];
const DENSITY_ICONS = { default: 'bi-type', comfortable: 'bi-type-bold'};
const DENSITY_LABELS = { default: 'A', comfortable: 'A+' };
const FONTS = [
    { key: 'ibm',   label: 'IBM Plex Mono', family: "'IBM Plex Mono', 'Courier New', monospace" },
    { key: 'inter', label: 'Inter',          family: "'Inter', sans-serif" },
    { key: 'roboto',label: 'Roboto',         family: "'Roboto', sans-serif" },
];

(function initFont() {
    const saved = localStorage.getItem('depot-font') || 'ibm';
    applyFont(saved);
})();

function applyFont(key) {
    const font = FONTS.find(f => f.key === key) || FONTS[0];
    document.documentElement.style.setProperty('--font', font.family);
    localStorage.setItem('depot-font', key);
}

(function initDensity() {
    const saved = localStorage.getItem('depot-density') || 'default';
    applyDensity(saved);
})();


function cycleDensity() {
    const current = localStorage.getItem('depot-density') || 'default';
    const next    = DENSITY_CYCLE[(DENSITY_CYCLE.indexOf(current) + 1) % DENSITY_CYCLE.length];
    applyDensity(next);
    localStorage.setItem('depot-density', next);
}

function applyDensity(density) {
    document.body.classList.remove('density-comfortable', 'density-spacious');
    if (density !== 'default') {
        document.body.classList.add('density-' + density);
    }
    const icon = document.getElementById('densityIcon');
    if (icon) icon.className = DENSITY_ICONS[density] || 'bi-type';
    const btn = document.getElementById('btnDensity');
    if (btn) btn.title = 'Size: ' + (DENSITY_LABELS[density] || 'A');
}

// ── Flatpickr Date Pickers ────────────────────────────────
const FLATPICKR_LOCALES = { de: 'de', th: 'th', es: 'es', fr: 'fr', it: 'it' };

let _fpAdd  = null;
let _fpEdit = null;
let _fpTransferIn = null;

function _fpLocale() {
    const lang = I18N.currentLang();
    return FLATPICKR_LOCALES[lang] || 'default';
}

function _fpConfig(inputEl) {
    return {
        enableTime:  true,
        time_24hr:   true,
        dateFormat:  'Y-m-d H:i',     // internes Format — bleibt immer ISO für JS-Logik
        altInput:    true,
        altFormat:   _fpLocale() === 'default' ? 'm/d/Y h:i K' : 'd.m.Y H:i',
        locale:      _fpLocale(),
        allowInput:  true,
        minuteIncrement: 1,
    };
}

function initFlatpickr() {
    const locale = _fpLocale();
    const isEn   = locale === 'default';
    const cfg = {
        enableTime:     true,
        time_24hr:      !isEn,
        dateFormat:     'Y-m-d H:i',
        altInput:       true,
        altFormat:      isEn ? 'm/d/Y h:i K' : 'd.m.Y H:i',
        locale:         locale,
        allowInput:     true,
        minuteIncrement: 1,
    };

    if (_fpAdd)        { _fpAdd.destroy();        _fpAdd = null; }
    if (_fpEdit)       { _fpEdit.destroy();       _fpEdit = null; }
    if (_fpTransferIn) { _fpTransferIn.destroy(); _fpTransferIn = null; }

    _fpAdd        = flatpickr('#addTxDate',       cfg);
    _fpEdit       = flatpickr('#editTxDate',      cfg);
    _fpTransferIn = flatpickr('#transferInDate',  cfg);
}

// ── Exchange Dropdown ─────────────────────────────────────
let _positionsCache = null;

async function _ensurePositionsLoaded(prefix) {
    const sel = document.getElementById(prefix + 'TxExchangeSelect');
    if (_positionsCache) {
        _fillExchangeDropdown(sel, _positionsCache);
        return;
    }
    const data = await fetch('/api/depot/positions').then(r => r.json());
    _positionsCache = data;
    _fillExchangeDropdown(sel, data);
}

function _fillExchangeDropdown(sel, data) {
    const current = sel.dataset.current || '';
    sel.innerHTML =
        '<option value="">— Select position —</option>' +
        data.map(p => `<option value="${esc(p.label)}" ${p.label === current ? 'selected' : ''}>${esc(p.label)}</option>`).join('') +
        '<option value="__new__">＋ New position...</option>';
    // Wenn current nicht in Liste → "__new__" vorwählen + Textfeld zeigen
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
    // Dropdown neu befüllen mit vorselektiertem Wert
    if (_positionsCache) {
        _fillExchangeDropdown(sel, _positionsCache);
    }
    document.getElementById(prefix + 'TxExchange').value = label;
}

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

    initDonut();
	initFlatpickr();
	
	// sorting for exchange/wallet table
	if ($.fn.DataTable.isDataTable('#posTable')) {
        $('#posTable').DataTable().destroy();
    }

    $('#posTable').DataTable({
        order:      [[2, 'desc']],
        pageLength: 100,
		paging:     false,
		searching:  false, 
        language: {
            search:     t('dt.search'),
            lengthMenu: t('dt.lengthMenu'),
            info:       t('dt.info'),
            paginate:   { previous: t('dt.previous'), next: t('dt.next') }
        },
        columnDefs: [{ orderable: false, targets: [-1] }]
    });	

});

// ── Settings Modal ────────────────────────────────────────
let settingsModal = null;

function openSettings() {
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

function saveSettings() {
    const selLang = document.querySelector('input[name="langChoice"]:checked');
    const selCur  = document.querySelector('input[name="curChoice"]:checked');
    const selFont = document.querySelector('input[name="fontChoice"]:checked');

    const langChanged = selLang && selLang.value !== I18N.currentLang();
    const curChanged  = selCur  && selCur.value  !== CURRENCY.current();

    if (selFont) applyFont(selFont.value);

    const applyLang = selLang
        ? I18N.setLanguage(selLang.value)
        : Promise.resolve();

    applyLang.then(() => {
		initFlatpickr();
		if (curChanged) {
            CURRENCY.setCurrency(selCur.value);
            settingsModal.hide();
            showToast('✓ ' + t('toast.currencyChanged', { currency: selCur.value }), 'success');
            setTimeout(() => window.location.reload(), 1000);
        } else {
            settingsModal.hide();
            if (langChanged && txLoaded) loadTransactions();
        }
    });
}

// ── Allocation Donut ──────────────────────────────────────
let donutInstance = null;

function initDonut() {
    const labels = typeof ALLOCATION_LABELS !== 'undefined' ? ALLOCATION_LABELS : [];
    const values = typeof ALLOCATION_VALUES !== 'undefined'
        ? ALLOCATION_VALUES.map(Number) : [];

    if (!labels.length) return;
    if (donutInstance) { donutInstance.destroy(); donutInstance = null; }

	
    const options = {
        ...APEX_DEFAULTS,
        series: values,
        labels: labels,
        chart: {
            ...APEX_DEFAULTS.chart,
            type:   'donut',
            height: window.innerHeight / 3
        },
        colors: CHART_COLORS,
        plotOptions: {
            pie: {
                donut: {
                    size: '80%',
                    labels: {
                        show: true,
                        total: {
                            show:      true,
                            label:     t('chart.total'),
                            color:     '#6b6f7a',
                            fontSize:  '30px',
                            formatter: (w) => {
                                const total = w.globals.seriesTotals.reduce((a, b) => a + b, 0);
                                return formatEur(total);
                            }
                        },
                        value: {
                            color:     '#ddd9d0',
                            fontSize:  '30px',
                            formatter: (val) => formatEur(Number(val))
                        }
                    }
                }
            }
        },
        legend: {
            position:   'bottom',
            fontSize:   '11px',
            fontFamily: "'IBM Plex Mono', monospace",
            labels:     { colors: '#6b6f7a' },
            markers:    { width: 10, height: 10, radius: 2 }
        },
        dataLabels: { enabled: false },
        stroke:     { width: 0 }
    };

    donutInstance = new ApexCharts(document.getElementById('donutChart'), options);
    donutInstance.render();
}

// ── BTC Price History Chart ───────────────────────────────
let historyChart = null;

function filterExchangeTransaction(exchange) {
    const panel = document.getElementById('transactionsPanel');
    panel.classList.remove('d-none');

    const doSearch = () => {
        $('#txTable').DataTable().search('"' + exchange + '"').draw();
    };

    if (!txLoaded) {
        loadTransactions().then(doSearch);
    } else {
        doSearch();
    }
}

function showHistory() {
    const panel = document.getElementById('historyPanel');
    panel.classList.remove('d-none');

    if (historyChart) return;

    document.getElementById('historyChart').innerHTML =
        `<div style="color:#6b6f7a;padding:1rem;font-size:.8rem">${t('chart.history.loading')}</div>`;

    fetch('/api/depot/history')
        .then(r => r.json())
        .then(data => {
            if (!data.length) {
                document.getElementById('historyChart').innerHTML =
                    `<div style="color:#6b6f7a;padding:1rem;font-size:.8rem">${t('chart.history.empty')}</div>`;
                return;
            }

            const series    = data.map(d => [new Date(d.date).getTime(), Number(d.close)]);
            const first     = series[0][1];
            const last      = series[series.length - 1][1];
            const lineColor = last >= first ? '#1D9E75' : '#D85A30';

            const options = {
                ...APEX_DEFAULTS,
                series: [{ name: 'BTC/EUR', data: series }],
                chart: {
                    ...APEX_DEFAULTS.chart,
                    type:    'area',
                    height:  240,
                    zoom:    { enabled: true },
                    toolbar: { show: true, tools: { download: false } }
                },
                colors: [lineColor],
                fill: {
                    type:     'gradient',
                    gradient: {
                        shadeIntensity: 1,
                        opacityFrom:    0.25,
                        opacityTo:      0.02,
                        stops:          [0, 100]
                    }
                },
                stroke:   { curve: 'smooth', width: 2 },
                xaxis: {
                    type:   'datetime',
                    labels: { style: { colors: '#6b6f7a', fontFamily: "'IBM Plex Mono', monospace" } },
                    axisBorder: { color: '#252830' },
                    axisTicks:  { color: '#252830' }
                },
                yaxis: {
                    labels: {
                        style:     { colors: '#6b6f7a', fontFamily: "'IBM Plex Mono', monospace" },
                        formatter: (v) => formatEur(v)
                    }
                },
                tooltip: {
                    ...APEX_DEFAULTS.tooltip,
                    x: { format: 'dd.MM.yyyy' },
                    y: { formatter: (v) => formatEur(v) }
                },
                dataLabels: { enabled: false },
                markers:    { size: 0 }
            };

            historyChart = new ApexCharts(document.getElementById('historyChart'), options);
            historyChart.render();
        })
        .catch(err => {
            document.getElementById('historyChart').innerHTML =
                `<div style="color:#d85a30;padding:1rem;font-size:.8rem">${t('toast.error')}: ${err.message}</div>`;
        });
}

function closeHistory() {
    document.getElementById('historyPanel').classList.add('d-none');
    if (historyChart) { historyChart.destroy(); historyChart = null; }
}

// ── Transactions Panel ────────────────────────────────────
let txLoaded = false;
let txModal  = null;
let txModalAdd  = null;

function toggleTransactions() {
    const panel   = document.getElementById('transactionsPanel');
    const chevron = document.getElementById('txChevron');
    const hidden  = panel.classList.contains('d-none');

    panel.classList.toggle('d-none', !hidden);
    chevron.className = hidden ? 'bi bi-chevron-up' : 'bi bi-chevron-down';
    chevron.style.fontSize = '.6rem';

    if (hidden && !txLoaded) loadTransactions();
}

async function loadTransactions() {
	
    return fetch('/api/depot/transactions')
        .then(r => r.json())
        .then(data => {
            txLoaded = true;
            renderTxTable(data);
        })
        .catch(err => {select
            document.getElementById('txTableBody').innerHTML =
                `<tr><td></td><td></td><td></td><td></td><td></td><td class="text-neg py-3 text-center">${t('toast.error')}: ${err.message}</td><td></td><td></td><td></td><td></td></tr>`;
        });
}

function renderTxTable(data) {
    const TYPE_COLORS = {
        BUY:          'text-pos',
        SELL:         'text-neg',
        TRANSFER_IN:  'text-pos',
        TRANSFER_OUT: 'text-neg'
    };

    const rows = data.map(tx => {
        const color   = TYPE_COLORS[tx.type] || '';
        const date    = tx.date ? tx.date.replace('T', ' ').substring(0, 16) : '–';
        const shortId = tx.transferId ? tx.transferId.substring(0, 8) + '…' : '–';
		
		let earning;
		let posNeg = "";
		if (tx.type == "BUY") {		
			if(tx.currency !== CURRENCY.current()) {
				earning = formatEur((CURRENT_PRICE - ((tx.pricePerBtc + tx.fees) * tx.exchangeRate)) * tx.quantity);
			} else {
				earning = formatEur((CURRENT_PRICE - ((tx.pricePerBtc + tx.fees))) * tx.quantity);
			}

			if (earning.startsWith("-")) {
				posNeg = "text-neg";
			} else {
				debugger;
				posNeg = "text-pos";
			}
		} else {
			earning="–";
		}
		
        return `<tr class="depot-row ${tx.currency !== CURRENCY.current() && tx.exchangeRate == 1 ?  'warning'  : ''}" data-type="${tx.type}" data-transfer-id="${tx.transferId || ''}" onclick="const cb=this.querySelector('.tx-row-check');cb.checked=!cb.checked;_updateBulkToolbar()">
			<td onclick="event.stopPropagation()">
		        <input type="checkbox" class="tx-row-check" data-id="${tx.id}"
		               style="accent-color:var(--accent)"/>
		    </td>
            <td style="white-space:nowrap">${date}</td>
            <td>${tx.positionLabel || '–'}</td>
            <td><span class="${color}">${tx.type}</span></td>
            <td class="text-end">${fmt8(tx.quantity)}</td>
            <td class="text-end">${tx.pricePerBtc != null ? tx.currency !== CURRENCY.current() ?  formatEur(tx.pricePerBtc * tx.exchangeRate) : formatEur(tx.pricePerBtc) : '–'}</td>
            
            <td class="text-end">${tx.quantityFiat != null ? tx.currency !== CURRENCY.current() ? formatEur((tx.quantityFiat + tx.fees) * tx.exchangeRate) + ' <span class="text-end" style="font-size:.7rem">[' + tx.currency + ' × ' + tx.exchangeRate + ']</span>' : formatEur((tx.quantityFiat + tx.fees)) : '–'}</td>
			
			
			<td class="text-end"><span class="${posNeg}">${tx.quantityFiat != null ? earning : '–'}</span></td>
	
			
			 <td class="text-end text-muted" style="font-size:.7rem" title="${tx.transferId || ''}">${shortId}</td>
            <td class="text-end depot-actions" style="white-space:nowrap">
                <button class="btn btn-xs depot-btn-icon" onclick="event.stopPropagation(); openEditTx(${JSON.stringify(tx).replace(/"/g,'&quot;')})" title="Edit">
                    <i class="bi bi-pencil"></i>
                </button>
                <button class="btn btn-xs depot-btn-icon" onclick="event.stopPropagation(); openAddTx(${JSON.stringify(tx).replace(/"/g,'&quot;')})" title="Copy">
                    <i class="bi bi-copy"></i>
                </button>
                <button class="btn btn-xs depot-btn-icon text-neg" onclick="event.stopPropagation(); deleteTx(${tx.id})" title="Delete">
                    <i class="bi bi-trash"></i>
                </button>
            </td>
        </tr>`;
    });

    if ($.fn.DataTable.isDataTable('#txTable')) {
        $('#txTable').DataTable().destroy();
    }

    document.getElementById('txTableBody').innerHTML =
        rows.length ? rows.join('') :
        `<tr><td></td><td></td><td></td><td></td><td></td><td class="text-center text-muted py-3">${t('dt.empty')}</td><td></td><td></td><td></td><td></td></tr>`;

    $('#txTable').DataTable({
        order:      [[1, 'desc']],
        pageLength: 500,
		lengthMenu: [25, 50, 100, 250, 500],
        language: {
            search:     t('dt.search'),
            lengthMenu: t('dt.lengthMenu'),
            info:       t('dt.info'),
            paginate:   { previous: t('dt.previous'), next: t('dt.next') }
        },
        columnDefs: [{ orderable: false, targets: [0, -1] }]
    });
}

function updateRelevantFields() {
    const selected = document.getElementById('addTxType').value;
    const isTrade  = selected === 'BUY' || selected === 'SELL';
    const isOut    = selected === 'TRANSFER_OUT';

    document.querySelectorAll('.fiat-field').forEach(el => el.classList.toggle('d-none', !isTrade));
    document.getElementById('transferPairSection').classList.toggle('d-none', !isOut);

    if (isOut) {
        _loadPositionsDropdown();
        // Datum + Quantity aus OUT-Feldern vorausfüllen
        const date = document.getElementById('addTxDate').value;
        const qty  = document.getElementById('addTxQty').value;
        const tDate = document.getElementById('transferInDate');
        const tQty  = document.getElementById('transferInQty');
        //if (!tDate.value && date) tDate.value = date;
		if (_fpTransferIn && !_fpTransferIn.selectedDates.length && date) {
		    _fpTransferIn.setDate(date, false);
		}
        if (!tQty.value  && qty)  tQty.value  = qty;
    }
}

function _loadPositionsDropdown() {
    const sel = document.getElementById('transferTargetSelect');
    if (sel.dataset.loaded) return;
    fetch('/api/depot/positions')
        .then(r => r.json())
        .then(data => {
            sel.innerHTML =
                '<option value="">— Select position —</option>' +
                data.map(p => `<option value="${esc(p.label)}">${esc(p.label)}</option>`).join('') +
                '<option value="__new__">＋ New position...</option>';
            sel.dataset.loaded = '1';
        });
}

async function openAddTx(tx) {
	await _ensurePositionsLoaded('add');
    // Reset new-position input
    document.getElementById('addTxExchange').classList.add('d-none');
    document.getElementById('addTxExchange').value = '';

    if (tx !== undefined) {
       document.getElementById('addTxId').value           = '';
       document.getElementById('addTxDate').value         = tx.date ? tx.date.substring(0, 16) : '';
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
		//document.getElementById('addTxDate').value = now.toISOString().slice(0,16);
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
	if (!txModalAdd) txModalAdd = new bootstrap.Modal(document.getElementById('txModalAdd'));
	txModalAdd.show();
}

async function openEditTx(tx) {
    await _ensurePositionsLoaded('edit');
    // Reset new-position input
    document.getElementById('editTxExchange').classList.add('d-none');
    document.getElementById('editTxExchange').value = '';
    document.getElementById('editTxId').value           = tx.id;
    //document.getElementById('editTxDate').value         = tx.date ? tx.date.substring(0, 16) : '';
	if (_fpEdit) _fpEdit.setDate(tx.date ? tx.date.substring(0, 16) : '', false);

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

    if (!txModal) txModal = new bootstrap.Modal(document.getElementById('txModal'));
    txModal.show();
}

function saveOrAddTx(isAdd) {
    const pref    = isAdd ? 'add' : 'edit';
    const id      = document.getElementById(pref + 'TxId').value;
    const dateVal = document.getElementById(pref + 'TxDate').value;
    const txType  = document.getElementById(pref + 'TxType').value;
    const isTrade = txType === 'BUY' || txType === 'SELL';

    const payload = {
        date:         dateVal ? dateVal + ':00' : null,
        type:         txType,
        quantity:     parseFloat(document.getElementById(pref + 'TxQty').value) || 0,
        quantityFiat: parseFloat(document.getElementById(pref + 'TxQuantityFiat').value) || 0,
        fees:         parseFloat(document.getElementById(pref + 'TxFees').value) || 0,
        feesCurrency: isTrade ? (document.getElementById(pref + 'TxFeesCurrency').value || CURRENCY.current()) : null,
        currency:     isTrade ? (document.getElementById(pref + 'TxCurrency').value || CURRENCY.current()) : null,
        exchangeRate: isTrade ? (parseFloat(document.getElementById(pref + 'TxExchangeRate').value) || 1) : null,
        comment:      document.getElementById(pref + 'TxComment').value,
        exchange:     _getExchangeValue(pref)
    };

    // TRANSFER_OUT pairing
    if (isAdd && txType === 'TRANSFER_OUT') {
        const selEl  = document.getElementById('transferTargetSelect');
        let target   = selEl.value === '__new__'
            ? (document.getElementById('transferTargetNew').value || '').trim()
            : selEl.value;
        if (target) {
            payload.transferTarget    = target;
            const tDate = document.getElementById('transferInDate').value;
            const tQty  = document.getElementById('transferInQty').value;
            payload.transferInDate     = tDate ? tDate + ':00' : null;
            payload.transferInQuantity = parseFloat(tQty) || null;
        }
    }

    const url    = isAdd ? '/api/depot/transactions' : '/api/depot/transactions/' + id;
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
        txLoaded = false;
		_positionsCache = null;
        loadTransactions();
        showToast('✓ ' + t(isAdd ? 'toast.txAdded' : 'toast.txUpdated'), 'success');
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

async function removeAll() {
    if (!await showConfirm(t('nav.btn.removeAll'), t('confirm.deleteAll'))) return;
    fetch('/api/depot/', { method: 'DELETE' })
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
    fetch('/api/depot/transactions/' + id, { method: 'DELETE' })
        .then(() => {
            txLoaded = false;
            loadTransactions();
            showToast('✓ ' + t('toast.txDeleted'), 'success');
        })
        .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

function confirmDeletePosition(id) {
    showConfirm(t('table.action.delete'), t('confirm.deletePosition')).then(ok => {
        if (ok) window.location.href = '/depot/delete/' + id;
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
        header:         true,
        skipEmptyLines: true,
        complete(results) {
            if (!results.data || results.data.length === 0) {
                showToast('✗ ' + t('toast.csvEmpty'), 'error');
                return;
            }
            csvImport.rawData = results.data;
            csvImport.headers = results.meta.fields || [];
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
        { id: 'map_exchange',     label: I18N.t('table.wallets'),           				required: true  },
        { id: 'map_buyQty',       label: I18N.t('csv.import.mapping.buy.quantity'),        	required: false },
        { id: 'map_buyCur',       label: I18N.t('csv.import.mapping.buy.currency'),        	required: false },
        { id: 'map_sellQty',      label: I18N.t('csv.import.mapping.sell.quantity'),       	required: false },
        { id: 'map_sellCur',      label: I18N.t('csv.import.mapping.sell.currency'),       	required: false },
        { id: 'map_fee',          label: I18N.t('table.col.fees'),                			required: false },
		{ id: 'map_feeCur',       label: I18N.t('csv.import.mapping.fee.currency'),        	required: false },
        { id: 'map_exchangeRate', label: I18N.t('csv.import.mapping.fee.exchange.rate'), 	required: false },
        { id: 'map_comment',      label: I18N.t('modal.field.comment'),            			required: false },
    ];

    const autoMatch = (fieldLabel) => {
        const candidates = [fieldLabel, fieldLabel.toLowerCase(), fieldLabel.toUpperCase()];
        return headers.find(h => candidates.includes(h)) || '';
    };

    const mappingRows = FIELDS.map(f => {
        const matched = autoMatch(f.id.replace('map_', ''));
        const opts    = NONE + headers.map(h =>
            `<option value="${esc(h)}" ${h === matched ? 'selected' : ''}>${esc(h)}</option>`
        ).join('');
        return `
        <tr>
            <td class="depot-label pt-2" style="width:160px;white-space:nowrap">
                ${f.label}${f.required ? ' <span style="color:var(--neg)">*</span>' : ''}
            </td>
            <td>
                <select id="${f.id}" class="form-select depot-input form-select-sm"
                        onchange="${f.id === 'map_typ' ? 'refreshTypRemap()' : ''}">
                    ${opts}
                </select>
            </td>
        </tr>`;
    }).join('');

    const body = `
        <p style="font-size:.75rem;color:var(--text-muted);margin-bottom:1rem">
            <strong style="color:var(--text)">${csvImport.rawData.length}</strong>
            ${t('modal.csv.rowsDetected')}
        </p>
        <table style="width:100%;border-spacing:0 6px">${mappingRows}</table>
        <hr style="border-color:var(--border);margin:1.25rem 0"/>
        <div class="depot-card-header mb-2">${t('modal.csv.typMapping')}</div>
        <p style="font-size:.72rem;color:var(--text-muted);margin-bottom:.75rem">${t('modal.csv.typHint')}</p>
        <div id="typRemapContainer"></div>`;

    document.getElementById('csvMappingBody').innerHTML = body;
    setTimeout(refreshTypRemap, 0);

    const el    = document.getElementById('csvMappingModal');
    const modal = bootstrap.Modal.getInstance(el) || new bootstrap.Modal(el);
    modal.show();
}

const INTERNAL_TYPES = ['Trade', 'Einzahlung', 'Auszahlung'];

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
        const preselect = INTERNAL_TYPES.includes(val) ? val : '';
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

function confirmCsvImport() {
    const mapping = {
        typ:          document.getElementById('map_typ')?.value,
        date:         document.getElementById('map_date')?.value,
        exchange:     document.getElementById('map_exchange')?.value,
        buyQty:       document.getElementById('map_buyQty')?.value,
        buyCur:       document.getElementById('map_buyCur')?.value,
        sellQty:      document.getElementById('map_sellQty')?.value,
        sellCur:      document.getElementById('map_sellCur')?.value,
        fee:          document.getElementById('map_fee')?.value,
		feeCur:       document.getElementById('map_feeCur')?.value,
        exchangeRate: document.getElementById('map_exchangeRate')?.value,
        comment:      document.getElementById('map_comment')?.value,
    };

    const missing = [];
    if (!mapping.typ)      missing.push('typ');
    if (!mapping.date)     missing.push('date');
    if (!mapping.exchange) missing.push('exchange');
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

        return {
            typ:          mappedTyp,
            date:         mapping.date         ? (r[mapping.date]         || '').trim() : null,
            exchange:     mapping.exchange     ? (r[mapping.exchange]     || '').trim() : null,
            buyQuantity:  mapping.buyQty       ? (r[mapping.buyQty]       || '').trim() : null,
            buyCurrency:  mapping.buyCur       ? (r[mapping.buyCur]       || '').trim() : null,
            sellQuantity: mapping.sellQty      ? (r[mapping.sellQty]      || '').trim() : null,
            sellCurrency: mapping.sellCur      ? (r[mapping.sellCur]      || '').trim() : null,
            fee:          mapping.fee          ? (r[mapping.fee]          || '').trim() : null,
			feeCur:       mapping.feeCur  ? (r[mapping.feeCur]  || '').trim() : null,
            exchangeRate: mapping.exchangeRate ? (r[mapping.exchangeRate] || '').trim() : null,
            comment:      mapping.comment      ? (r[mapping.comment]      || '').trim() : null,
        };
    }).filter(Boolean);

    if (!rows.length) {
        showToast('✗ ' + t('toast.csvNoRows'), 'error');
        return;
    }

    bootstrap.Modal.getInstance(document.getElementById('csvMappingModal'))?.hide();
    showToast('⏳ ' + t('toast.importProgress'), '');

    fetch('/api/depot/import-mapped', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ rows })
    })
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

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
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
        const data = await fetch('/api/depot/positions/' + id).then(r => r.json());
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

    const url    = id ? '/api/depot/positions/' + id : '/api/depot/positions';
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

// ── Refresh Prices ────────────────────────────────────────
function refreshPrices() {
    if (!OFFLINE.isOnline()) {
        const modal = bootstrap.Modal.getInstance(document.getElementById('offlineConfirmModal'))
            || new bootstrap.Modal(document.getElementById('offlineConfirmModal'));
        modal.show();
        return;
    }
    _doRefreshPrices();
}

function confirmOfflineRefresh() {
    bootstrap.Modal.getInstance(document.getElementById('offlineConfirmModal'))?.hide();
    _doRefreshPrices();
}

function _doRefreshPrices() {
    const btn = document.getElementById('btnRefresh');
    btn.disabled = true;
    btn.innerHTML = `<span class="depot-spinner"></span>${t('toast.refreshLoading')}`;

    fetch('/api/depot/refresh?currency=' + CURRENCY.current(), { method: 'POST' })
        .then(r => r.json())
        .then(data => {
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

// ── Toast ─────────────────────────────────────────────────
function showToast(msg, type) {
    const toast       = document.getElementById('statusToast');
    toast.textContent = msg;
    toast.className   = 'depot-toast ' + (type || '');
    toast.classList.remove('d-none');
    setTimeout(() => toast.classList.add('d-none'), 5000);
}

// ── Formatters ────────────────────────────────────────────
function formatEur(val) {
    // kept for compatibility – delegates to CURRENCY.format()
    return CURRENCY.format(val);
}

function formatSats(btc) {
    if (btc == null || isNaN(btc)) return '–';
    return Math.round(Number(btc) * 1e8).toLocaleString('de-DE') + ' sats';
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
	
    fetch('/api/depot/current-price', {
        method:  'PUT',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ price, currency: CURRENCY.current() })
    })
    .then(r => r.json())
    .then(data => {
        if (data.error) { showToast('✗ ' + data.error, 'error'); return; }
        // Update badge display without full reload
        const fmt = Number(data.price).toLocaleString('de-DE', {
            minimumFractionDigits: 2, maximumFractionDigits: 2
        }) + ' €';
        document.getElementById('btcPriceDisplay').textContent = fmt;
        closePriceEdit();
        showToast('✓ BTC price updated', 'success');
    })
    .catch(err => showToast('✗ ' + t('toast.error') + ': ' + err.message, 'error'));
}

function _importEnc(file) {
    // Show password modal, store file reference
    window._pendingEncFile = file;
    const modal = bootstrap.Modal.getInstance(document.getElementById('encPasswordModal'))
        || new bootstrap.Modal(document.getElementById('encPasswordModal'));
    document.getElementById('encImportPassword').value = '';
    modal.show();
    setTimeout(() => document.getElementById('encImportPassword').focus(), 400);
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
 
    fetch('/api/depot/import-enc', { method: 'POST', body: formData })
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
 
function openExportModal() {
    document.getElementById('exportPassword').value = '';
    document.getElementById('exportPasswordConfirm').value = '';
    const modal = bootstrap.Modal.getInstance(document.getElementById('exportModal'))
        || new bootstrap.Modal(document.getElementById('exportModal'));
    modal.show();
}
 
function doExport() {
    const pw  = document.getElementById('exportPassword').value;
    const pw2 = document.getElementById('exportPasswordConfirm').value;
 
    if (pw && pw !== pw2) {
        showToast('✗ ' + t('toast.error') + ': passwords do not match', 'error');
        return;
    }
 
    bootstrap.Modal.getInstance(document.getElementById('exportModal'))?.hide();
 
    fetch('/api/depot/export', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ password: pw || null })
    })
    .then(async response => {
        if (!response.ok) {
            const err = await response.json().catch(() => ({ error: 'Export failed' }));
            throw new Error(err.error || 'Export failed');
        }
        const disposition = response.headers.get('Content-Disposition') || '';
        const filename = disposition.includes('filename=')
            ? disposition.split('filename=')[1].replace(/"/g, '')
            : (pw ? 'transactions_export.enc' : 'transactions_export.csv');
 
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

    // Sync select-all checkbox state
    const all  = document.querySelectorAll('.tx-row-check');
    const selAll = document.getElementById('txSelectAll');
    if (selAll) {
        selAll.checked       = all.length > 0 && n === all.length;
        selAll.indeterminate = n > 0 && n < all.length;
    }
}

function toggleSelectAll(cb) {
    document.querySelectorAll('.tx-row-check')
        .forEach(el => { el.checked = cb.checked; });
    _updateBulkToolbar();
}

// Row-Checkbox click (stopPropagation damit Row-Click nicht feuert)
document.addEventListener('change', e => {
    if (e.target.classList.contains('tx-row-check')) _updateBulkToolbar();
});

// Rechtsklick auf txTableBody → Kontextmenü
document.addEventListener('contextmenu', e => {
    const row = e.target.closest('#txTableBody tr');
    if (!row) return;
    e.preventDefault();

    // Wenn die geklickte Row nicht selektiert ist → nur diese selektieren
    const cb = row.querySelector('.tx-row-check');
    if (cb && !cb.checked) {
        document.querySelectorAll('.tx-row-check').forEach(c => c.checked = false);
        cb.checked = true;
        _updateBulkToolbar();
    }

    const ids = _getSelectedIds();
    if (!ids.length) return;

    _ctxMenu.innerHTML = `
        <div style="padding:.2rem .75rem .4rem;font-size:.68rem;color:var(--text-muted);letter-spacing:.08em;text-transform:uppercase">
            ${ids.length} selected
        </div>
        ${_ctxItem('bi-link-45deg',        t("table.action.pair.transfer"),    'bulkPair()')}
        ${_ctxItem('bi-arrow-right-square',t("table.action.move.position"),     'openBulkMove()')}
        ${_ctxItem('bi-percent',           t("table.action.exchange.rate"), 'openBulkExRate()')}
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

    fetch('/api/depot/transactions/bulk', {
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

    fetch('/api/depot/transactions/bulk-pair', {
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
        fetch('/api/depot/positions').then(r => r.json()).then(data => {
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

function confirmBulkMove() {
    const sel    = document.getElementById('bulkMoveSelect');
    const target = sel.value === '__new__'
        ? (document.getElementById('bulkMoveNew').value || '').trim()
        : sel.value;

    if (!target) { showToast("✗ " + t("toast.move.position.missing.target"), 'error'); return; }

    const ids = _getSelectedIds();
    bootstrap.Modal.getInstance(document.getElementById('bulkMoveModal'))?.hide();

    fetch('/api/depot/transactions/bulk-move', {
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

    fetch('/api/depot/transactions/bulk-exrate', {
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

        const okBtn = document.getElementById('confirmModalOk');
        // Alten Listener entfernen um Doppel-Trigger zu vermeiden
        const newOk = okBtn.cloneNode(true);
        okBtn.parentNode.replaceChild(newOk, okBtn);
        newOk.addEventListener('click', () => {
            _confirmModal.hide();
            resolve(true);
        });

        document.getElementById('confirmModal')
            .addEventListener('hidden.bs.modal', () => resolve(false), { once: true });

        _confirmModal.show();
    });
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