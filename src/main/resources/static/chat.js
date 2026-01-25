/**
 * AI Assistant JS - Versión con CSRF + sesiones
 */

const chatEl = document.getElementById('chat');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('send');
const sidEl = document.getElementById('sid');

const sessionListEl = document.getElementById('sessionList');
const sessionListMobileEl = document.getElementById('sessionListMobile');
const sessionSearchEl = document.getElementById('sessionSearch');
const sessionSearchMobileEl = document.getElementById('sessionSearchMobile');
const newChatBtn = document.getElementById('newChat');

const toggleSessionsBtn = document.getElementById('toggleSessions');
const sessionDrawer = document.getElementById('sessionDrawer');
const drawerBackdrop = document.getElementById('drawerBackdrop');
const closeDrawerBtn = document.getElementById('closeDrawer');

let sessionId = localStorage.getItem('apiasistente.sessionId') || null;

/** ---------------- CSRF helpers ---------------- **/

function getCookie(name) {
  const m = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
  return m ? decodeURIComponent(m.pop()) : null;
}

function csrfToken() {
  // CookieCsrfTokenRepository por defecto -> cookie "XSRF-TOKEN"
  return getCookie('XSRF-TOKEN');
}

async function apiFetch(url, options = {}) {
  const method = (options.method || 'GET').toUpperCase();

  const headers = new Headers(options.headers || {});
  headers.set('Accept', 'application/json');

  // Para JSON bodies
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  // CSRF para métodos mutadores
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
    const token = csrfToken();
    if (token) headers.set('X-XSRF-TOKEN', token);
  }

  const res = await fetch(url, {
    ...options,
    method,
    headers,
    credentials: 'same-origin'
  });

  // Si Spring te echa por auth/CSRF, lo tratamos claro
  if (res.status === 401) {
    window.location.href = '/login';
    return res;
  }
  if (res.status === 403) {
    // CSRF inválido o permisos. En tu caso era CSRF.
    throw new Error('403 Forbidden (CSRF / permisos). Recarga la página y reintenta.');
  }

  return res;
}

/** ---------------- UI helpers ---------------- **/

function scrollDown() {
  requestAnimationFrame(() => {
    chatEl.scrollTo({ top: chatEl.scrollHeight, behavior: 'smooth' });
  });
}

function addMsg(who, text, sources = []) {
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

  if (sources && sources.length > 0) {
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
            <span>${s.documentTitle || ('Documento ' + (idx+1))}</span>
            <span class="opacity-60 text-[9px]">Relevancia: ${((s.score || 0) * 100).toFixed(0)}%</span>
        </div>
        <div class="text-slate-400 italic font-light">"${s.snippet || ''}"</div>
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

/** ---------------- Sessions ---------------- **/

async function loadSessions() {
  try {
    const res = await apiFetch('/api/chat/sessions');
    if (!res.ok) return;
    const sessions = await res.json();
    renderSessions(sessions);
  } catch (e) {
    console.error('Error cargando sesiones', e);
  }
}

function renderSessions(sessions) {
  const q1 = (sessionSearchEl?.value || '').toLowerCase().trim();
  const q2 = (sessionSearchMobileEl?.value || '').toLowerCase().trim();
  const q = q1 || q2;

  const filtered = (sessions || []).filter(s => {
    const title = (s.title || '').toLowerCase();
    const id = (s.id || '').toLowerCase();
    return !q || title.includes(q) || id.includes(q);
  });

  const makeItem = (s) => {
    const isActive = sessionId && s.id === sessionId;
    const el = document.createElement('div');
    el.className =
      "p-3 rounded-xl border transition cursor-pointer " +
      (isActive
        ? "bg-blue-600/20 border-blue-500/30"
        : "bg-slate-900/30 border-slate-700/40 hover:border-blue-500/20 hover:bg-slate-900/40");

    el.innerHTML = `
      <div class="flex items-start justify-between gap-2">
        <div class="min-w-0">
          <p class="text-sm font-semibold truncate">${s.title || 'Chat sin título'}</p>
          <p class="text-[10px] text-slate-500 mt-1 font-mono truncate">${(s.id || '').slice(0, 8)} · msgs: ${s.messageCount ?? 0}</p>
        </div>
        <button class="deleteSession text-[10px] px-2 py-1 rounded-lg bg-red-600/20 border border-red-500/20 hover:bg-red-600/30">
          Borrar
        </button>
      </div>
    `;

    // Activar al click (no si pulsas borrar)
    el.addEventListener('click', async (ev) => {
      if (ev.target && ev.target.classList.contains('deleteSession')) return;
      await activateSession(s.id);
      if (sessionDrawer) sessionDrawer.classList.add('hidden');
    });

    // Borrar
    el.querySelector('.deleteSession').addEventListener('click', async (ev) => {
      ev.stopPropagation();
      if (!confirm('¿Borrar este chat?')) return;
      await deleteSession(s.id);
    });

    return el;
  };

  if (sessionListEl) {
    sessionListEl.innerHTML = '';
    filtered.forEach(s => sessionListEl.appendChild(makeItem(s)));
  }
  if (sessionListMobileEl) {
    sessionListMobileEl.innerHTML = '';
    filtered.forEach(s => sessionListMobileEl.appendChild(makeItem(s)));
  }
}

async function activateSession(id) {
  try {
    // PUT requiere CSRF -> apiFetch ya lo mete
    const res = await apiFetch(`/api/chat/sessions/${id}/activate`, { method: 'PUT' });
    if (!res.ok) throw new Error(await res.text());

    sessionId = id;
    localStorage.setItem('apiasistente.sessionId', sessionId);
    sidEl.textContent = sessionId.substring(0, 8);

    chatEl.innerHTML = '';
    await loadHistory();
    await loadSessions();
  } catch (e) {
    console.error(e);
    addMsg('assistant', '⚠️ No pude activar esa sesión: ' + e.message);
  }
}

async function deleteSession(id) {
  try {
    const res = await apiFetch(`/api/chat/sessions/${id}`, { method: 'DELETE' });
    if (!res.ok) throw new Error(await res.text());

    if (sessionId === id) {
      sessionId = null;
      localStorage.removeItem('apiasistente.sessionId');
      sidEl.textContent = '-';
      chatEl.innerHTML = '';
    }
    await loadSessions();
  } catch (e) {
    console.error(e);
    addMsg('assistant', '⚠️ No pude borrar la sesión: ' + e.message);
  }
}

async function ensureActiveSession() {
  // Opcional: si no hay sessionId local, pide al backend una activa o crea una nueva
  try {
    const res = await apiFetch('/api/chat/active');
    if (!res.ok) return;

    const data = await res.json(); // { sessionId: "..." }
    if (data?.sessionId) {
      sessionId = data.sessionId;
      localStorage.setItem('apiasistente.sessionId', sessionId);
      sidEl.textContent = sessionId.substring(0, 8);
    }
  } catch (e) {
    console.error('Error ensureActiveSession', e);
  }
}

/** ---------------- Chat history & send ---------------- **/

async function loadHistory() {
  if (!sessionId) return;

  sidEl.textContent = sessionId.substring(0, 8);

  try {
    const res = await apiFetch(`/api/chat/${sessionId}/history`);
    if (!res.ok) return;

    const msgs = await res.json();
    if (msgs.length > 0) chatEl.innerHTML = '';

    msgs.forEach(m => addMsg(m.role === 'USER' ? 'user' : 'assistant', m.content));
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
    await loadSessions();
  } catch (e) {
    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();
    addMsg('assistant', '⚠️ Error: ' + e.message);
  } finally {
    sendBtn.disabled = false;
    inputEl.focus();
  }
}

/** ---------------- Mobile drawer ---------------- **/

function openDrawer() {
  if (sessionDrawer) sessionDrawer.classList.remove('hidden');
}
function closeDrawer() {
  if (sessionDrawer) sessionDrawer.classList.add('hidden');
}

/** ---------------- Wire up ---------------- **/

sendBtn?.addEventListener('click', send);
inputEl?.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });

sessionSearchEl?.addEventListener('input', loadSessions);
sessionSearchMobileEl?.addEventListener('input', loadSessions);

newChatBtn?.addEventListener('click', async () => {
  // Simple: reset local sessionId -> backend creará una nueva al primer POST /api/chat
  sessionId = null;
  localStorage.removeItem('apiasistente.sessionId');
  sidEl.textContent = '-';
  chatEl.innerHTML = '';
  addMsg('assistant', 'Listo. Escribe tu primer mensaje para crear un chat nuevo.');
  await loadSessions();
});

toggleSessionsBtn?.addEventListener('click', openDrawer);
drawerBackdrop?.addEventListener('click', closeDrawer);
closeDrawerBtn?.addEventListener('click', closeDrawer);

// Init
(async function init() {
  // Importante: asegura que exista cookie XSRF-TOKEN.
  // Con CookieCsrfTokenRepository normalmente se setea al cargar cualquier página.
  await ensureActiveSession();
  await loadSessions();
  await loadHistory();
})();
