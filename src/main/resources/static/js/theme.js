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
