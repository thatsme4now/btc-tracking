'use strict';

// Automatically attaches Spring Security's CSRF token (read from the readable XSRF-TOKEN cookie,
// see SecurityConfig) to every POST/PUT/PATCH/DELETE fetch() call, app-wide — a single central
// place instead of touching every individual fetch() call across the app's JS files. Must be the
// FIRST <script> tag loaded on every page, before anything that might call fetch() (bootstrap,
// i18n.js, applock.js, navbar.js, ...).
//
// No-op when login is disabled (the default): Spring Security's CsrfFilter still issues the token
// either way, but if it didn't, this simply wouldn't find a cookie and would send nothing extra —
// harmless.
(function () {
    function readCookie(name) {
        const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
        return match ? decodeURIComponent(match[1]) : null;
    }

    const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
    const originalFetch = window.fetch.bind(window);

    window.fetch = function (input, init) {
        init = init || {};
        const method = (init.method || 'GET').toUpperCase();

        if (MUTATING_METHODS.has(method)) {
            const token = readCookie('XSRF-TOKEN');
            if (token) {
                const headers = new Headers(init.headers || {});
                headers.set('X-XSRF-TOKEN', token);
                init = Object.assign({}, init, { headers });
            }
        }

        return originalFetch(input, init);
    };
})();
