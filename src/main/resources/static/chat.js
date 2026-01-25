// src/main/resources/static/chat.js
/**
 * AI Assistant JS - Con Sidebar de sesiones + CSRF robusto
 */

const chatEl = document.getElementById('chat');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('send');
const sidEl = document.getElementById('sid');

// Sidebar / Drawer
const sessionListEl = document.getElementById('sessionList');
const sessionListMobileEl = document.getElementById('sessionListMobile');
const sessionSearchEl = document.getElementById('sessionSearch');
const sessionSearchMobileEl = document.getElementById('sessionSearchMobile');

const toggleSessionsBtn = document.getElementById('toggleSessions');
const sessionDrawer = document.getElementById('sessionDrawer');
const closeDrawerBtn = document.getElementById('closeDrawer');
const drawerBackdrop = document.getElementById('drawerBackdrop');

const newChatBtn = document.getElementById('newChat');

let sessionId = localStorage.getItem('apiasistente.sessionId') || null;

/* =========================
   CSRF helpers
========================= */

function csrfFromMeta() {
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    return {
        token: tokenMeta ? tokenMeta.getAttribute('content') : null,
        header: headerMeta ? headerMeta.getAttribute('content') : 'X-XSRF-TOKEN'
    };
}

function getCookie(name) {
    const m = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
    return m ? decodeURIComponent(m.pop()) : null;
}

function csrfToken() {
    // 1) Meta (lo más fiable si Thymeleaf renderiza)
    const meta = csrfFromMeta();
    if (meta.token) return meta;

    // 2) Cookie fallback
    const cookieToken = getCookie('XSRF-TOKEN');
    if (cookieToken) return { token: cookieToken, header: 'X-XSRF-TOKEN' };

    return { token: null, header: 'X-XSRF-TOKEN' };
}

async function apiFetch(url, options = {}) {
    const method = (options.method || 'GET').toUpperCase();

    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');

    if (options.body && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
    }

    // CSRF en métodos mutadores
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
        const csrf = csrfToken();
        if (csrf.token) headers.set(csrf.header, csrf.token);
    }

    const res = await fetch(url, {
        ...options,
        method,
        headers,
        credentials: 'same-origin'
    });

    if (res.status === 401) {
        window.location.href = '/login';
        return res;
    }
    if (res.status === 403) {
        throw new Error('403 Forbidden (CSRF / permisos). Recarga /chat y reintenta.');
    }
    return res;
}

/* =========================
   UI helpers
========================= */

function scrollDown() {
    requestAnimationFrame(() => {
        chatEl.scrollTo({ top: chatEl.scrollHeight, behavior: 'smooth' });
    });
}

function addMsg(who, text, sources = []) {
    // eliminar placeholder inicial
    if (chatEl.querySelector('.opacity-50')) chatEl.innerHTML = '';

    const wrapper = document.createElement('div');
    wrapper.className = `flex ${who === 'user' ? 'justify-end' : 'justify-start'} animate-msg mb-6`;

    const div = document.createElement('div');
    const isUser = who === 'user';

    const baseClasses = "max-w-[85%] md:max-w-[75%] px-5 py-3 rounded-2xl shadow-xl text-sm leading-relaxed transition-all";
    const userClasses = "bg-blue-600 text-white rounded-tr-none ml-12";
    const aiClasses = "glass text-slate-100 rounded-tl-none mr-12 border border-slate-700/50";

    div.className = `${baseClasses} ${isUser ? userClasses : aiClasses}`;

    const content = document.createElement('div');
    content.innerHTML = (text || '').toString().replace(/\n/g, '<br>');
    div.appendChild(content);

    if (sources && sources.length > 0 && !isUser) {
        const sourcesDiv = document.createElement('div');
        sourcesDiv.className = "mt-4 pt-3 border-t border-slate-700/50 space-y-2";

        const title = document.createElement('p');
        title.className = "text-[10px] uppercase tracking-widest text-slate-400 font-bold mb-2";
        title.textContent = "Fuentes Consultadas";
        sourcesDiv.appendChild(title);

        sources.forEach((s, idx) => {
            const sourceCard = document.createElement('div');
            sourceCard.className = "text-[11px] bg-slate-800/50 p-2 rounded-lg border border-slate-700/30 hover:border-blue-500/30 transition-colors";
            sourceCard.innerHTML = `
                <div class="flex justify-between font-bold text-blue-400 mb-1">
                    <span>${escapeHtml(s.documentTitle || ('Documento ' + (idx + 1)))}</span>
                    <span class="opacity-60 text-[9px]">Relevancia: ${((s.score || 0) * 100).toFixed(0)}%</span>
                </div>
                <div class="text-slate-400 italic font-light">"${escapeHtml(s.snippet || '')}"</div>
            `;
            sourcesDiv.appendChild(sourceCard);
        });

        div.appendChild(sourcesDiv);
    }

    wrapper.appendChild(div);
    chatEl.appendChild(wrapper);
    scrollDown();
}

function showTyping() {
    const id = 'typing-' + Date.now();
    const html = `
        <div id="${id}" class="flex justify-start animate-msg mb-6 animate-pulse-slow">
            <div class="glass px-5 py-4 rounded-2xl rounded-tl-none mr-12 border border-slate-700/50">
                <div class="flex gap-1.5">
                    <div class="w-2 h-2 bg-blue-500 rounded-full animate-bounce"></div>
                    <div class="w-2 h-2 bg-blue-500 rounded-full animate-bounce [animation-delay:-0.3s]"></div>
                    <div class="w-2 h-2 bg-blue-500 rounded-full animate-bounce [animation-delay:-0.5s]"></div>
                </div>
            </div>
        </div>
    `;
    chatEl.insertAdjacentHTML('beforeend', html);
    scrollDown();
    return id;
}

function escapeHtml(str) {
    return (str ?? '').toString()
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

/* =========================
   API calls (asumidos)
   - GET  /api/chat/sessions
   - PUT  /api/chat/sessions/{id}/activate
   - POST /api/chat (sessionId + message)
   - GET  /api/chat/{sessionId}/history
========================= */

async function fetchSessions() {
    const res = await apiFetch('/api/chat/sessions');
    if (!res.ok) return [];
    return await res.json();
}

async function activateSession(id) {
    const res = await apiFetch(`/api/chat/sessions/${id}/activate`, { method: 'PUT' });
    if (!res.ok) throw new Error(await res.text());
}

async function loadHistory() {
    if (!sessionId) return;
    sidEl.textContent = sessionId.substring(0, 8);

    try {
        const res = await apiFetch(`/api/chat/${sessionId}/history`);
        if (!res.ok) return;

        const msgs = await res.json();
        if (msgs.length > 0) chatEl.innerHTML = '';

        msgs.forEach(m => {
            addMsg(m.role === 'USER' ? 'user' : 'assistant', m.content);
        });
    } catch (e) {
        console.error("Error cargando historial", e);
    }
}

async function send() {
    const text = inputEl.value.trim();
    if (!text) return;

    inputEl.value = '';
    addMsg('user', text);

    const typingId = showTyping();
    sendBtn.disabled = true;

    try {
        const res = await apiFetch('/api/chat', {
            method: 'POST',
            body: JSON.stringify({ sessionId, message: text })
        });

        const typingEl = document.getElementById(typingId);
        if (typingEl) typingEl.remove();

        if (!res.ok) throw new Error(await res.text());

        const data = await res.json();
        sessionId = data.sessionId;
        localStorage.setItem('apiasistente.sessionId', sessionId);
        sidEl.textContent = sessionId.substring(0, 8);

        addMsg('assistant', data.reply, data.sources);
        await refreshSessionsUI(); // para que suba arriba por actividad
    } catch (e) {
        const typingEl = document.getElementById(typingId);
        if (typingEl) typingEl.remove();
        addMsg('assistant', '⚠️ Error: ' + e.message);
    } finally {
        sendBtn.disabled = false;
        inputEl.focus();
    }
}

/* =========================
   Sessions UI
========================= */

function openDrawer() {
    if (!sessionDrawer) return;
    sessionDrawer.classList.remove('hidden');
}

function closeDrawer() {
    if (!sessionDrawer) return;
    sessionDrawer.classList.add('hidden');
}

function formatSessionTitle(s) {
    return s.title && s.title.trim().length > 0 ? s.title : ('Chat ' + s.id.substring(0, 8));
}

function sessionItemHtml(s, isActive) {
    const title = formatSessionTitle(s);
    const subtitle = `${(s.messageCount ?? 0)} msgs`;
    return `
      <div class="group flex items-center justify-between gap-2 px-3 py-2 rounded-xl border ${isActive ? 'border-blue-500/50 bg-blue-600/10' : 'border-slate-700/40 bg-slate-900/30'} hover:border-blue-500/30 transition cursor-pointer" data-id="${s.id}">
        <div class="min-w-0">
          <div class="text-sm font-semibold text-slate-100 truncate">${escapeHtml(title)}</div>
          <div class="text-[11px] text-slate-500 truncate">${escapeHtml(subtitle)}</div>
        </div>
        <div class="flex items-center gap-2 opacity-0 group-hover:opacity-100 transition">
          <button class="deleteSession text-[10px] px-2 py-1 rounded-lg bg-red-600/60 hover:bg-red-500/70 border border-red-400/20" title="Borrar">🗑</button>
        </div>
      </div>
    `;
}

function attachSessionHandlers(container) {
    if (!container) return;

    // click: activar chat
    container.querySelectorAll('[data-id]').forEach(el => {
        el.addEventListener('click', async () => {
            const id = el.getAttribute('data-id');
            if (!id) return;

            try {
                await activateSession(id);
                sessionId = id;
                localStorage.setItem('apiasistente.sessionId', sessionId);
                sidEl.textContent = sessionId.substring(0, 8);

                chatEl.innerHTML = '';
                await loadHistory();

                closeDrawer();
                await refreshSessionsUI();
            } catch (e) {
                addMsg('assistant', '⚠️ Error activando sesión: ' + e.message);
