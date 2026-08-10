// ── Theme (dark/light), shared across all pages; toggle button only exists on the main page ──
(function initTheme() {
    const saved = localStorage.getItem('depot-theme') || 'dark';
    applyTheme(saved);
})();

function toggleTheme() {
    // Theme locked to dark in 21-mode (button also disabled via CSS)
    if (document.body.classList.contains('mode-21')) return;
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

// ── Font + text size (density), loaded on all 4 pages so settings apply everywhere ──
const DENSITY_CYCLE  = ['default', 'comfortable'];
const DENSITY_ICONS  = { default: 'bi-type', comfortable: 'bi-type-bold' };
const DENSITY_LABELS = { default: 'A', comfortable: 'A+' };
// IBM Plex Mono is no longer user-selectable but stays hardcoded for chart/badge fonts.
const FONTS = [
    { key: 'inter',       label: 'Inter',       family: "'Inter', sans-serif" },
    { key: 'roboto',      label: 'Roboto',      family: "'Roboto', sans-serif" },
    { key: 'inconsolata', label: 'Inconsolata', family: "'Inconsolata', 'Courier New', monospace" },
];

(function initFont() {
    const saved = localStorage.getItem('depot-font') || 'inter';
    applyFont(saved);
})();

function applyFont(key) {
    const font = FONTS.find(f => f.key === key) || FONTS[0];
    document.documentElement.style.setProperty('--font', font.family);
    localStorage.setItem('depot-font', key);
}

// ── 21-mode: sets dark theme + Inconsolata once, remembering the previous values to restore on deactivation ──
(function initMode21() {
    const saved = localStorage.getItem('depot-mode21') || 'off';
    if (saved === 'on') document.body.classList.add('mode-21');
    _updateMode21Icon();
})();

function toggleMode21() {
    const active = document.body.classList.contains('mode-21');
    if (!active) {
        localStorage.setItem('depot-mode21-prev-theme', localStorage.getItem('depot-theme') || 'dark');
        localStorage.setItem('depot-mode21-prev-font',  localStorage.getItem('depot-font')  || 'inter');
        document.body.classList.add('mode-21');
        applyTheme('dark');
        applyFont('inconsolata');
        localStorage.setItem('depot-mode21', 'on');
    } else {
        const prevTheme = localStorage.getItem('depot-mode21-prev-theme') || 'dark';
        const prevFont  = localStorage.getItem('depot-mode21-prev-font')  || 'inter';
        document.body.classList.remove('mode-21');
        applyTheme(prevTheme);
        applyFont(prevFont);
        localStorage.setItem('depot-mode21', 'off');
        localStorage.removeItem('depot-mode21-prev-theme');
        localStorage.removeItem('depot-mode21-prev-font');
    }
    _updateMode21Icon();
}

function _updateMode21Icon() {
    const btn = document.getElementById('btnMode21');
    if (btn) btn.classList.toggle('mode21-active', document.body.classList.contains('mode-21'));
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
