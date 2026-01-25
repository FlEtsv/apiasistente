/**
 * Chat UI - Sesiones por usuario + selector de chats + stats (messageCount/lastMessageAt)
 * Requiere Spring Security con cookie (form-login).
 */

const chatEl = document.getElementById('chat');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('send');
const sidEl = document.getElementById('sid');
const newChatBtn = document.getElementById('newChat');

// Sidebar (desktop)
const sessionList = document.getElementById('sessionList');
const sessionSearch = document.getElementById('sessionSearch');

// Drawer (mobile)
const toggleSessionsBtn = document.getElementById('toggleSessions');
const drawer = document.getElementById('sessionDrawer');
const drawerBackdrop = document.getElementById('drawerBackdrop');
const closeDrawerBtn = document.getElementById('closeDrawer');
const sessionListMobile = document.getElementById('sessionListMobile');
const sessionSearchMobile = document.getElementById('sessionSearchMobile');

let sessionId = localStorage.getItem('apiasistente.sessionId') || '';
let sessionsCache = [];

// ---------- UI helpers ----------

function scrollDown() {
  requestAnimationFrame(() => chatEl.scrollTo({ top: chatEl.scrollHeight, behavior: 'smooth' }));
}

function escapeHtml(str) {
  return (str ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function setSid(id) {
  sessionId = id || '';
  if (sessionId) localStorage.setItem('apiasistente.sessionId', sessionId);
  sidEl.textContent = sessionId ? sessionId.substring(0, 8) : '-';
}

function showWelcomeIfEmpty() {
  if (chatEl.querySelector('.opacity-50')) return;
  if (chatEl.children.length === 0) {
    chatEl.innerHTML = `
      <div class="flex flex-col items-center justify-center h-full text-slate-500 opacity-50 space-y-2">
        <svg class="w-12 h-12" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"></path>
        </svg>
        <p>Inicia una conversación...</p>
      </div>
    `;
  }
}

/**
 * Mensajes (mantiene tu estilo)
 */
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
  content.innerHTML = escapeHtml(text).replace(/\n/g, '<br>');
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

      const docTitle = escapeHtml(s.documentTitle || ('Documento ' + (idx + 1)));
      const snippet = escapeHtml(s.snippet || '');
      const pct = (Number(s.score || 0) * 100).toFixed(0);

      sourceCard.innerHTML = `
        <div class="flex justify-between font-bold text-blue-400 mb-1">
          <span>${docTitle}</span>
          <span class="opacity-60 text-[9px]">Relevancia: ${pct}%</span>
        </div>
        <div class="text-slate-400 italic font-light">"${snippet}"</div>
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

// ---------- Auth / API helpers ----------

function looksLikeLoginHtml(res) {
  const ct = (res.headers.get('content-type') || '').toLowerCase();
  return ct.includes('text/html');
}

function redirectToLogin() {
  window.location.href = '/login';
}

/**
 * Fetch protegido: si Spring te devuelve HTML de /login o 401/403, redirige.
 */
async function apiFetch(url, options = {}) {
  const res = await fetch(url, options);
  if (looksLikeLoginHtml(res) || res.status === 401 || res.status === 403) {
    redirectToLogin();
    throw new Error('Not authenticated');
  }
  if (!res.ok) throw new Error(await res.text());
  return res;
}

// ---------- Drawer mobile ----------

function openDrawer() { if (drawer) drawer.classList.remove('hidden'); }
function closeDrawer() { if (drawer) drawer.classList.add('hidden'); }

// ---------- Sessions / Sidebar ----------

function fmtDate(d) {
  if (!d) return 'sin mensajes';
  try {
    return d.toLocaleString(undefined, {
      year: '2-digit', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit'
    });
  } catch {
    return 'sin mensajes';
  }
}

function getSearchQuery() {
  const q1 = (sessionSearch?.value || '').trim();
  const q2 = (sessionSearchMobile?.value || '').trim();
  return (q2.length > 0 ? q2 : q1).toLowerCase();
}

function renderSessions() {
  const q = getSearchQuery();

  const filtered = sessionsCache.filter(s => {
    const title = (s.title || '').toLowerCase();
    return q === '' || title.includes(q);
  });

  const renderInto = (container) => {
    if (!container) return;
    container.innerHTML = '';

    if (filtered.length === 0) {
      container.innerHTML = `<div class="text-xs text-slate-500 p-3">No hay chats.</div>`;
      return;
    }

    filtered.forEach(s => {
      const active = (s.id === sessionId);
      const item = document.createElement('div');
      item.className = `
        group p-3 rounded-xl border transition cursor-pointer
        ${active ? 'border-blue-500/50 bg-blue-500/10' : 'border-slate-700/40 hover:border-slate-600/60 hover:bg-slate-800/20'}
      `.trim();

      const title = escapeHtml(s.title || 'Sin título');
      const msgCount = Number(s.messageCount || 0);
      const lastMsg = s.lastMessageAt ? new Date(s.lastMessageAt) : null;

      item.innerHTML = `
        <div class="flex items-center justify-between gap-2">
          <div class="min-w-0">
            <div class="text-sm font-semibold truncate">${title}</div>
            <div class="text-[10px] text-slate-500 mt-1 truncate">
              ${escapeHtml(s.id.substring(0, 8))} · ${msgCount} msgs · ${fmtDate(lastMsg)}
            </div>
          </div>
          <button class="deleteSession opacity-0 group-hover:opacity-100 transition text-[11px] px-2 py-1 rounded-lg border border-slate-700/50 hover:border-red-400/40 hover:bg-red-500/10">
            🗑
          </button>
        </div>
      `;

      // click => abrir (si no has pulsado borrar)
      item.addEventListener('click', async (e) => {
        if (e.target && e.target.classList.contains('deleteSession')) return;
        await selectSession(s.id);
        closeDrawer();
      });

      // double click => renombrar
      item.addEventListener('dblclick', async () => {
        const next = prompt('Nuevo título del chat:', s.title || '');
        if (next === null) return;
        await renameSession(s.id, next);
        await loadSessions();
      });

      // borrar
      item.querySelector('.deleteSession').addEventListener('click', async (e) => {
        e.stopPropagation();
        const ok = confirm('¿Borrar este chat? Esta acción no se puede deshacer.');
        if (!ok) return;

        await deleteSession(s.id);

        // Si borraste el activo, pedimos una sesión activa nueva
        if (s.id === sessionId) {
          await ensureActiveSession();
          await loadHistory(true);
        }

        await loadSessions();
      });

      container.appendChild(item);
    });
  };

  renderInto(sessionList);
  renderInto(sessionListMobile);
}

async function loadSessions() {
  const res = await apiFetch('/api/chat/sessions');
  sessionsCache = await res.json();
  renderSessions();
}

async function selectSession(id) {
  await apiFetch(`/api/chat/sessions/${id}/activate`, { method: 'PUT' });
  setSid(id);
  await loadHistory(true);
  await loadSessions(); // refresca orden + activo
}

async function renameSession(id, title) {
  await apiFetch(`/api/chat/sessions/${id}/title`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title })
  });
}

async function deleteSession(id) {
  await apiFetch(`/api/chat/sessions/${id}`, { method: 'DELETE' });
}

// ---------- Session active + history ----------

async function ensureActiveSession() {
  // 1) Si hay sessionId guardada, probamos si es válida (history)
  if (sessionId) {
    try {
      const probe = await fetch(`/api/chat/${sessionId}/history`, { method: 'GET' });
      if (!looksLikeLoginHtml(probe) && probe.ok) {
        setSid(sessionId);
        return;
      }
    } catch (_) { /* caemos al active */ }
  }

  // 2) Pedimos al backend la sesión activa del usuario
  const res = await apiFetch('/api/chat/active');
  const data = await res.json();
  setSid(data.sessionId);
}

async function loadHistory(clear = false) {
  if (!sessionId) return;

  try {
    const res = await apiFetch(`/api/chat/${sessionId}/history`);
    const msgs = await res.json();

    if (clear) chatEl.innerHTML = '';
    if (msgs.length > 0) chatEl.innerHTML = '';

    msgs.forEach(m => addMsg(m.role === 'USER' ? 'user' : 'assistant', m.content));
    if (msgs.length === 0) showWelcomeIfEmpty();
  } catch (e) {
    console.error("Error cargando historial", e);
  }
}

// ---------- New chat ----------

async function newChat() {
  chatEl.innerHTML = '';
  addMsg('assistant', '✅ Nuevo chat creado. Escribe tu primera pregunta.');

  const res = await apiFetch('/api/chat/sessions', { method: 'POST' });
  const data = await res.json();
  setSid(data.sessionId);

  await loadSessions();
  closeDrawer();
}

// ---------- Send message ----------

async function send() {
  const text = inputEl.value.trim();
  if (!text) return;

  inputEl.value = '';
  addMsg('user', text);

  const typingId = showTyping();
  sendBtn.disabled = true;
  inputEl.disabled = true;

  try {
    const res = await apiFetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, message: text })
    });

    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();

    const data = await res.json();
    setSid(data.sessionId);
    addMsg('assistant', data.reply, data.sources);

    // refresca sidebar (título automático / lastActivity / stats)
    await loadSessions();

  } catch (e) {
    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();
    addMsg('assistant', '⚠️ Error: ' + e.message);
  } finally {
    sendBtn.disabled = false;
    inputEl.disabled = false;
    inputEl.focus();
  }
}

// ---------- Listeners ----------

sendBtn?.addEventListener('click', send);

inputEl?.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') send();
});

newChatBtn?.addEventListener('click', () => newChat().catch(err => addMsg('assistant', '⚠️ ' + err.message)));

sessionSearch?.addEventListener('input', renderSessions);
sessionSearchMobile?.addEventListener('input', renderSessions);

toggleSessionsBtn?.addEventListener('click', openDrawer);
drawerBackdrop?.addEventListener('click', closeDrawer);
closeDrawerBtn?.addEventListener('click', closeDrawer);

// ---------- Init ----------

(async function init() {
  try {
    await ensureActiveSession();
    await loadHistory(false);
    await loadSessions();
  } catch (e) {
    console.error(e);
    addMsg('assistant', '⚠️ No se pudo inicializar: ' + e.message);
  }
})();
