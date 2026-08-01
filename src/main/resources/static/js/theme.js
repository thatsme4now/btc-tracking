// ── Theme (Dark/Light) ─────────────────────────────────────────────────────
// Gemeinsam genutzt von overview.html (mit Toggle-Button), sowie flow.html und
// holdings.html (übernehmen das gespeicherte Theme nur passiv, ohne eigenen
// Toggle-Button — der Button existiert nur auf der Hauptseite).
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

// ── Font + Text-Size (Density) ──────────────────────────────────────────────
// Waren früher Teil von depot.js und liefen dadurch NUR auf der Hauptseite —
// flow.html/holdings.html/yearly.html luden depot.js nie, daher griff die
// gespeicherte Schriftart/Textgröße dort nie, obwohl beides in localStorage
// global (seitenübergreifend) abgelegt wird. Jetzt hier in theme.js (auf allen
// 4 Seiten geladen), damit beide Einstellungen überall wirken, nicht nur dort,
// wo gerade der Button dafür sitzt.
const DENSITY_CYCLE  = ['default', 'comfortable'];
const DENSITY_ICONS  = { default: 'bi-type', comfortable: 'bi-type-bold' };
const DENSITY_LABELS = { default: 'A', comfortable: 'A+' };
const FONTS = [
    { key: 'ibm',    label: 'IBM Plex Mono', family: "'IBM Plex Mono', 'Courier New', monospace" },
    { key: 'inter',  label: 'Inter',         family: "'Inter', sans-serif" },
    { key: 'roboto', label: 'Roboto',        family: "'Roboto', sans-serif" },
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
