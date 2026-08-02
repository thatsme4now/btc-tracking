'use strict';

const AppLock = (() => {

    let lockedModal = null;
    let setModal     = null;
    let _locked       = false;

    function init() {
        lockedModal = new bootstrap.Modal(document.getElementById('appLockedModal'), { backdrop: 'static', keyboard: false });
        setModal    = new bootstrap.Modal(document.getElementById('appLockSetModal'));

        fetch('/api/btc-tracking/lock/status')
            .then(r => r.json())
            .then(data => {
                _locked = !!data.locked;
                _applyIcon();
                if (_locked) {
                    document.getElementById('unlockPassword').value = '';
                    document.getElementById('unlockError').classList.add('d-none');
                    _resetCollapse();
                    lockedModal.show();
                }
            })
            .catch(() => {});
    }

    function _resetCollapse() {
        document.getElementById('appResetSection').classList.add('d-none');
        document.getElementById('appResetLink').classList.remove('d-none');
        document.getElementById('resetConfirmInput').value = '';
        document.getElementById('resetConfirmBtn').disabled = true;
        document.getElementById('resetError').classList.add('d-none');
    }

    function toggleReset() {
        document.getElementById('appResetSection').classList.remove('d-none');
        document.getElementById('appResetLink').classList.add('d-none');
        document.getElementById('resetConfirmInput').focus();
    }

    function onResetConfirmInput() {
        const val = document.getElementById('resetConfirmInput').value;
        document.getElementById('resetConfirmBtn').disabled = (val !== 'delete');
    }

    function doReset() {
        const confirm = document.getElementById('resetConfirmInput').value;
        const errorEl = document.getElementById('resetError');
        errorEl.classList.add('d-none');

        if (confirm !== 'delete') return;

        fetch('/api/btc-tracking/lock/reset', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ confirm })
        })
        .then(r => r.json())
        .then(data => {
            if (data.error) {
                errorEl.textContent = data.error;
                errorEl.classList.remove('d-none');
                return;
            }
            showToast('✓ ' + t('applock.toast.reset'), 'success');
            setTimeout(() => window.location.reload(), 800);
        })
        .catch(err => {
            errorEl.textContent = err.message;
            errorEl.classList.remove('d-none');
        });
    }

    function _applyIcon() {
        const icon = document.getElementById('appLockIcon');
        const btn  = document.getElementById('btnAppLock');
        if (!icon || !btn) return;
        icon.className = _locked ? 'bi bi-lock-fill' : 'bi bi-unlock-fill';
        btn.title = _locked ? t('applock.icon.unlock.title') : t('applock.icon.lock.title');
    }

    function onIconClick() {
        if (_locked) {
            lockedModal.show();
            return;
        }
        document.getElementById('lockPassword').value = '';
        document.getElementById('lockPasswordConfirm').value = '';
        setModal.show();
    }

    function doLock() {
        const pw  = document.getElementById('lockPassword').value;
        const pw2 = document.getElementById('lockPasswordConfirm').value;

        if (!pw)      { showToast('✗ ' + t('applock.error.passwordRequired'), 'error'); return; }
        if (pw !== pw2) { showToast('✗ ' + t('applock.error.passwordMismatch'), 'error'); return; }

        fetch('/api/btc-tracking/lock', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ password: pw, passwordConfirm: pw2 })
        })
        .then(r => r.json())
        .then(data => {
            if (data.error) { showToast('✗ ' + data.error, 'error'); return; }
            setModal.hide();
            showToast('✓ ' + t('applock.toast.locked'), 'success');
            setTimeout(() => window.location.reload(), 800);
        })
        .catch(err => showToast('✗ ' + err.message, 'error'));
    }

    function doUnlock() {
        const pw = document.getElementById('unlockPassword').value;
        const errorEl = document.getElementById('unlockError');
        errorEl.classList.add('d-none');

        if (!pw) {
            errorEl.textContent = t('applock.error.passwordRequired');
            errorEl.classList.remove('d-none');
            return;
        }

        fetch('/api/btc-tracking/unlock', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ password: pw })
        })
        .then(r => r.json().then(data => ({ ok: r.ok, data })))
        .then(({ data }) => {
            if (data.error) {
                errorEl.textContent = data.remainingSeconds
                    ? t('applock.error.rateLimited', { SECONDS: data.remainingSeconds })
                    : t('applock.error.wrongPassword');
                errorEl.classList.remove('d-none');
                return;
            }
            lockedModal.hide();
            _locked = false;
            _applyIcon();
            showToast('✓ ' + t('applock.toast.unlocked', { POS: data.positions, TX: data.transactions }), 'success');
            setTimeout(() => window.location.reload(), 800);
        })
        .catch(err => {
            errorEl.textContent = err.message;
            errorEl.classList.remove('d-none');
        });
    }

    return { init, onIconClick, doLock, doUnlock, toggleReset, onResetConfirmInput, doReset };
})();

document.addEventListener('DOMContentLoaded', () => {
    I18N.ready.then(() => AppLock.init());
});