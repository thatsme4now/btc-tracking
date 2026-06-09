/**
 * currency.js – display currency configuration
 *
 * To add a new currency, add an entry to CURRENCIES:
 *   { code: 'GBP', symbol: '£', locale: 'en-GB' }
 *
 * The cookie 'depot-currency' is read by the Spring controller
 * to load the correct current_price row from DB.
 */

'use strict';

const CURRENCY = (() => {

    const STORAGE_KEY = 'depot-currency';
    const DEFAULT     = 'EUR';

    // ── Currency registry – extend here ──────────────────
    const CURRENCIES = [
        { code: 'EUR', symbol: '€', locale: 'de-DE' },
        { code: 'USD', symbol: '$', locale: 'en-US' },
        { code: 'THB', symbol: '฿', locale: 'th-TH' },
    ];

    let _current = _readCookie() || DEFAULT;

    // ── Public API ────────────────────────────────────────

    function current() {
        return _current;
    }

    function all() {
        return CURRENCIES;
    }

    function get(code) {
        return CURRENCIES.find(c => c.code === (code || _current).toUpperCase())
            || CURRENCIES[0];
    }

    function symbol(code) {
        return get(code).symbol;
    }

    /**
     * Format a numeric value with the currency symbol.
     * Uses the locale defined in CURRENCIES for number formatting.
     */
    function format(val, code) {
        if (val == null || isNaN(val)) return '–';
        const cur = get(code);
        return Number(val).toLocaleString(cur.locale, {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2
        }) + ' ' + cur.symbol;
    }

    /**
     * Change the active currency, write cookie, reload page
     * so Thymeleaf fetches the correct current_price from DB.
     */
    function setCurrency(code) {
        if (!CURRENCIES.find(c => c.code === code)) return;
        _current = code;
        _writeCookie(code);
    }

    // ── Cookie helpers ────────────────────────────────────
    // Cookie is read server-side by DepotViewController

    function _readCookie() {
        const match = document.cookie.match(/(?:^|;\s*)depot-currency=([^;]+)/);
        return match ? decodeURIComponent(match[1]) : null;
    }

    function _writeCookie(value) {
        const maxAge = 60 * 60 * 24 * 365; // 1 year
        document.cookie = `depot-currency=${encodeURIComponent(value)};path=/;max-age=${maxAge};SameSite=Lax`;
    }

    return { current, all, get, symbol, format, setCurrency };
})();