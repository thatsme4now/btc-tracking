/**
 * i18n.js – lightweight translation module
 *
 * Usage:
 *   t('key')                  → translated string
 *   t('key', {n: 5})          → replaces {n} in string
 *   applyI18n()               → updates all [data-i18n] elements in DOM
 *   setLanguage('de')         → load language, persist, re-apply DOM
 *
 * JSON files expected at: /i18n/{lang}.json
 * Supported: en, de, th  (add more by dropping a JSON file)
 */

'use strict';

const I18N = (() => {

    const STORAGE_KEY = 'depot-lang';
    const DEFAULT_LANG = 'en';
	const SUPPORTED = { en: 'English', de: 'Deutsch', th: 'ภาษาไทย', es: 'Español', it: 'Italiano', fr: 'Français' };
    let _strings = {};
    let _lang    = localStorage.getItem(STORAGE_KEY) || DEFAULT_LANG;

    // ── Public API ────────────────────────────────────────

    // Translate a key, interpolating {placeholder} tokens; falls back to the key itself.
    function t(key, vars) {
        let str = _strings[key] ?? key;
        if (vars) {
            Object.entries(vars).forEach(([k, v]) => {
                str = str.replaceAll('{' + k + '}', v);
            });
        }
        return str;
    }

    function currentLang() {
        return _lang;
    }

    function supported() {
        return SUPPORTED;
    }

    // Load language JSON, persist choice, update DOM.
    function setLanguage(lang) {
        if (!SUPPORTED[lang]) lang = DEFAULT_LANG;
        _lang = lang;
        localStorage.setItem(STORAGE_KEY, lang);
        return _load(lang).then(() => applyI18n());
    }

    // Replace text of all [data-i18n*] elements in the DOM.
	function applyI18n() {
	    document.querySelectorAll('[data-i18n]').forEach(el => {
	        const key = el.getAttribute('data-i18n');
	        el.textContent = t(key);
	    });
	    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
	        el.placeholder = t(el.getAttribute('data-i18n-placeholder'));
	    });
	    document.querySelectorAll('[data-i18n-title]').forEach(el => {
	        el.title = t(el.getAttribute('data-i18n-title'));
	    });
	    document.querySelectorAll('[data-i18n-html]').forEach(el => {
	        el.innerHTML = t(el.getAttribute('data-i18n-html'));
	    });
	    document.querySelectorAll('[data-i18n-label]').forEach(el => {
	        el.setAttribute('data-cell-label', t(el.getAttribute('data-i18n-label')));
	    });
	}

    // ── Private ───────────────────────────────────────────

    function _load(lang) {
        return fetch('/i18n/' + lang + '.json?v=' + Date.now())
            .then(r => {
                if (!r.ok) throw new Error('i18n file not found: ' + lang);
                return r.json();
            })
            .then(data => { _strings = data; })
            .catch(err => {
                console.warn('i18n load error:', err.message);
            });
    }

    // Loaded on module parse; ready is a promise callers can await
    const ready = _load(_lang);

    return { t, currentLang, supported, setLanguage, applyI18n, ready };
})();

// Convenience shorthand used throughout depot.js
const t = (key, vars) => I18N.t(key, vars);