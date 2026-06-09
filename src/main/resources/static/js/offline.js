/**
 * offline.js – Internet toggle for the depot application
 *
 * When offline mode is active:
 *  - OFFLINE.isOnline() returns true
 *  - CoinGecko refresh is blocked (caller must check before fetch)
 *  - A visual indicator is shown in the navbar
 *
 * Persisted in localStorage under 'depot-offline'.
 */

'use strict';

const OFFLINE = (() => {

    const STORAGE_KEY = 'depot-online';
    let _online = localStorage.getItem(STORAGE_KEY) === 'true';

    // ── Public API ────────────────────────────────────────

    function isOnline() {
        return _online;
    }

    function toggle() {
        _online = !_online;
        localStorage.setItem(STORAGE_KEY, _online);
        _applyUI();
        return _online;
    }

    function init() {
        _applyUI();
    }

    // ── Private ───────────────────────────────────────────

    function _applyUI() {
        const btn  = document.getElementById('btnOffline');
        const icon = document.getElementById('offlineIcon');
        if (!btn || !icon) return;

        if (_online) {
            btn.classList.remove('is-offline');
            icon.className = 'bi bi-wifi';
            btn.title = 'Online mode – click to disable internet';
        } else {
            btn.classList.add('is-offline');
            icon.className = 'bi bi-wifi-off';
            btn.title = 'Offline mode ON – click to enable internet';
        }
    }

    return { isOnline, toggle, init };
})();
