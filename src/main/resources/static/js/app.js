'use strict';

const state = {
    accessToken: localStorage.getItem('accessToken'),
    refreshToken: localStorage.getItem('refreshToken'),
    user: JSON.parse(localStorage.getItem('user') || 'null'),
    view: 'dashboard',
};

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

function uuid() {
    return (crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'
        .replace(/[xy]/g, c => { const r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16); }));
}

// ---------- API ----------

async function api(path, { method = 'GET', body, idempotencyKey } = {}) {
    const headers = { 'Content-Type': 'application/json' };
    if (state.accessToken) headers['Authorization'] = 'Bearer ' + state.accessToken;
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    let res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });

    if (res.status === 401 && state.refreshToken && !path.startsWith('/api/auth')) {
        const ok = await tryRefresh();
        if (ok) return api(path, { method, body, idempotencyKey });
    }

    const data = await res.json().catch(() => null);
    if (!res.ok) throw new Error((data && data.message) || 'Request failed (' + res.status + ')');
    return data;
}

async function tryRefresh() {
    try {
        const data = await fetch('/api/auth/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken: state.refreshToken }),
        }).then(r => r.json());
        if (!data.accessToken) return false;
        setSession(data);
        return true;
    } catch (e) {
        return false;
    }
}

function setSession(data) {
    state.accessToken = data.accessToken;
    state.refreshToken = data.refreshToken;
    state.user = data.user;
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('refreshToken', data.refreshToken);
    localStorage.setItem('user', JSON.stringify(data.user));
}

function clearSession() {
    state.accessToken = null;
    state.refreshToken = null;
    state.user = null;
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('user');
}

// ---------- Views ----------

function showView(view) {
    state.view = view;
    $$('.view').forEach(v => v.classList.add('hidden'));
    $('#view-' + view).classList.remove('hidden');
    $$('nav a').forEach(a => a.classList.toggle('active', a.dataset.view === view));
    if (view === 'dashboard') loadDashboard();
    if (view === 'wallets') loadWallets();
    if (view === 'transfers') loadTransfers();
    if (view === 'rates') loadRates();
}

function render() {
    const authed = !!state.accessToken;
    $('#nav').classList.toggle('hidden', !authed);
    $('#view-auth').classList.toggle('hidden', authed);
    if (!authed) {
        $$('.view').forEach(v => { if (v.id !== 'view-auth') v.classList.add('hidden'); });
        return;
    }
    showView(state.view);
}

// ---------- Auth handlers ----------

$('#login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        const data = await api('/api/auth/login', {
            method: 'POST',
            body: { email: $('#login-email').value, password: $('#login-password').value },
        });
        setSession(data);
        render();
    } catch (err) { showAuthError(err.message); }
});

$('#register-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        const data = await api('/api/auth/register', {
            method: 'POST',
            body: {
                email: $('#register-email').value,
                password: $('#register-password').value,
                firstName: $('#register-first').value,
                lastName: $('#register-last').value,
            },
        });
        setSession(data);
        render();
    } catch (err) { showAuthError(err.message); }
});

$('#logout-btn').addEventListener('click', async () => {
    try { await api('/api/auth/logout', { method: 'POST', body: { refreshToken: state.refreshToken } }); } catch (e) { /* ignore */ }
    clearSession();
    render();
});

function showAuthError(msg) {
    const el = $('#auth-error');
    el.textContent = msg;
    el.classList.remove('hidden');
}

$$('.tab').forEach(tab => tab.addEventListener('click', () => {
    $$('.tab').forEach(t => t.classList.toggle('active', t === tab));
    $('#login-form').classList.toggle('hidden', tab.dataset.tab !== 'login');
    $('#register-form').classList.toggle('hidden', tab.dataset.tab !== 'register');
}));

$$('nav a').forEach(a => a.addEventListener('click', (e) => { e.preventDefault(); showView(a.dataset.view); }));

// ---------- Dashboard ----------

async function loadDashboard() {
    try {
        const wallets = await api('/api/wallets');
        const transfers = await api('/api/transfers');
        $('#dashboard-wallets').innerHTML = wallets.length
            ? wallets.map(w => `<div class="row"><b>${w.currency}</b> ${fmt(w.balance)} <span class="badge ${w.status}">${w.status}</span></div>`).join('')
            : '<div class="empty">No wallets yet — create one in the Wallets tab.</div>';
        $('#dashboard-activity').innerHTML = transfers.length
            ? transfers.slice(0, 5).map(t => `<div class="row">#${t.id} ${t.amount} ${t.currency} ${t.fromWalletId}→${t.toWalletId} <span class="badge ${t.status}">${t.status}</span></div>`).join('')
            : '<div class="empty">No transfers yet.</div>';
    } catch (e) {
        $('#dashboard-wallets').textContent = e.message;
    }
}

// ---------- Wallets ----------

async function loadWallets() {
    try {
        const wallets = await api('/api/wallets');
        const rows = wallets.map(w => `
            <tr>
                <td>${w.id}</td>
                <td>${w.currency}</td>
                <td><b>${fmt(w.balance)} ${w.currency}</b></td>
                <td><span class="badge ${w.status}">${w.status}</span></td>
                <td><button class="btn-link" onclick="window.location='/api/wallets/${w.id}/ledger?page=0&size=20'" title="ledger endpoint">ledger →</button></td>
            </tr>`).join('');
        $('#wallets-table').innerHTML = rows || '<tr><td colspan="5" class="empty">No wallets yet.</td></tr>';

        const currency = walletCurrencyOptions(wallets);
        $('#transfer-from').innerHTML = currency;
        $('#topup-wallet').innerHTML = currency;
        $('#withdraw-wallet').innerHTML = currency;
    } catch (e) {
        $('#wallets-table').innerHTML = `<tr><td colspan="5" class="empty">${e.message}</td></tr>`;
    }
}

function walletCurrencyOptions(wallets) {
    return wallets.map(w => `<option value="${w.id}">#${w.id} ${w.currency} (${fmt(w.balance)})</option>`).join('');
}

$('#create-wallet-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        await api('/api/wallets', { method: 'POST', body: { currency: $('#wallet-currency').value }, idempotencyKey: uuid() });
        loadWallets();
    } catch (err) { alert(err.message); }
});

// ---------- Transfers ----------

async function loadTransfers() {
    try {
        const transfers = await api('/api/transfers');
        $('#transfers-table').innerHTML = transfers.map(t => `
            <tr>
                <td>${t.id}</td><td>${t.fromWalletId}</td><td>${t.toWalletId}</td>
                <td>${fmt(t.amount)} ${t.currency}</td>
                <td>${t.convertedAmount ? fmt(t.convertedAmount) + ' ' + (t.currency) : '—'}</td>
                <td>${fmt(t.fee)}</td>
                <td><span class="badge ${t.status}">${t.status}</span></td>
                <td>${new Date(t.createdAt).toLocaleString()}</td>
            </tr>`).join('') || '<tr><td colspan="8" class="empty">No transfers yet.</td></tr>';

        const payments = await api('/api/payments');
        $('#payments-table').innerHTML = payments.map(p => `
            <tr>
                <td>${p.id}</td><td>${p.type}</td><td>${p.walletId}</td>
                <td>${fmt(p.amount)} ${p.currency}</td>
                <td><span class="badge ${p.status}">${p.status}</span></td>
                <td>${p.externalPaymentId || '—'}</td>
                <td>${p.failureReason || '—'}</td>
                <td>${new Date(p.createdAt).toLocaleString()}</td>
            </tr>`).join('') || '<tr><td colspan="8" class="empty">No payments yet.</td></tr>';
    } catch (e) {
        $('#transfers-table').innerHTML = `<tr><td colspan="8" class="empty">${e.message}</td></tr>`;
    }
}

$('#transfer-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        const body = {
            fromWalletId: Number($('#transfer-from').value),
            toWalletId: Number($('#transfer-to').value),
            amount: Number($('#transfer-amount').value),
        };
        await api('/api/transfers', { method: 'POST', body, idempotencyKey: uuid() });
        loadTransfers();
    } catch (err) { alert(err.message); }
});

$('#topup-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        const body = { amount: Number($('#topup-amount').value), cardToken: 'tok_' + uuid().slice(0, 12) };
        await api(`/api/payments/wallets/${$('#topup-wallet').value}/topup`, { method: 'POST', body, idempotencyKey: uuid() });
        loadTransfers();
    } catch (err) { alert(err.message); }
});

$('#withdraw-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        const body = { amount: Number($('#withdraw-amount').value), bankAccount: 'DE00 1001 1001 0000' };
        await api(`/api/payments/wallets/${$('#withdraw-wallet').value}/withdraw`, { method: 'POST', body, idempotencyKey: uuid() });
        loadTransfers();
    } catch (err) { alert(err.message); }
});

// ---------- Rates ----------

async function loadRates() {
    try {
        const rates = await api('/api/exchange-rates?base=EUR');
        $('#rates-table').innerHTML = rates.map(r => `
            <tr><td><b>${r.quoteCurrency}</b></td><td>${fmt(r.rate, 6)} EUR</td></tr>`).join('');
    } catch (e) {
        $('#rates-table').innerHTML = `<tr><td colspan="2" class="empty">${e.message}</td></tr>`;
    }
}

// ---------- utils ----------

function fmt(v, decimals = 2) {
    if (v === null || v === undefined) return '—';
    return new Intl.NumberFormat('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals }).format(Number(v));
}

render();
