/**
 * chat.js - sesiones (lista + activar + renombrar) + historial + CSRF
 */

const chatEl = document.getElementById('chat');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('send');
const sidEl = document.getElementById('sid');
const modelSelectEl = document.getElementById('modelSelect');

const sessionListEl = document.getElementById('sessionList');
const sessionListMobileEl = document.getElementById('sessionListMobile');
const sessionSearchEl = document.getElementById('sessionSearch');
const sessionSearchMobileEl = document.getElementById('sessionSearchMobile');

const toggleSessionsBtn = document.getElementById('toggleSessions');
const sessionDrawer = document.getElementById('sessionDrawer');
const drawerBackdrop = document.getElementById('drawerBackdrop');
const closeDrawerBtn = document.getElementById('closeDrawer');

const newChatBtn = document.getElementById('newChat');

let sessionId = null;
let sessionsCache = [];
let isLoadingHistory = false;
const MODEL_STORAGE_KEY = 'chat.model';

function getCsrf() {
  const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
  const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
  return { token, header };
}

function withCsrf(headers = {}) {
  const { token, header } = getCsrf();
  if (token && header) headers[header] = token;
  return headers;
}

function scrollDown() {
  requestAnimationFrame(() => {
    chatEl.scrollTo({ top: chatEl.scrollHeight, behavior: 'smooth' });
  });
}

function clearWelcomeIfNeeded() {
  if (chatEl.querySelector('.opacity-50')) chatEl.innerHTML = '';
}

function addMsg(who, text, sources = []) {
  clearWelcomeIfNeeded();

  const wrapper = document.createElement('div');
  wrapper.className = `flex ${who === 'user' ? 'justify-end' : 'justify-start'} animate-msg mb-6`;

  const div = document.createElement('div');
  const isUser = who === 'user';

  const baseClasses = "max-w-[85%] md:max-w-[75%] px-5 py-3 rounded-2xl shadow-xl text-sm leading-relaxed transition-all";
  const userClasses = "bg-blue-600 text-white rounded-tr-none ml-12";
  const aiClasses = "glass text-slate-100 rounded-tl-none mr-12 border border-slate-700/50";
  div.className = `${baseClasses} ${isUser ? userClasses : aiClasses}`;

  const content = document.createElement('div');
  content.innerHTML = (text || '').replace(/\n/g, '<br>');
  div.appendChild(content);

  if (!isUser && sources && sources.length > 0) {
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
          <span>${s.documentTitle || ('Documento ' + (idx + 1))}</span>
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

function setSidLabel(id) {
  sidEl.textContent = id ? id.substring(0, 8) : '-';
}

function loadModelSelection() {
  if (!modelSelectEl) return;
  const saved = localStorage.getItem(MODEL_STORAGE_KEY);
  if (saved) {
    modelSelectEl.value = saved;
  }
  modelSelectEl.addEventListener('change', () => {
    localStorage.setItem(MODEL_STORAGE_KEY, modelSelectEl.value);
  });
}

async function ensureActiveSession() {
  // El backend decide cuál es la sesión activa real del usuario
  const res = await fetch('/api/chat/active', { headers: withCsrf() });
  if (!res.ok) throw new Error(`No se pudo obtener sesión activa: ${res.status}`);
  const data = await res.json();
  sessionId = data.sessionId || data.id; // por si tu DTO usa otro nombre
  setSidLabel(sessionId);
}

async function loadHistory(id) {
  if (!id) return;
  if (isLoadingHistory) return;
  isLoadingHistory = true;

  try {
    const res = await fetch(`/api/chat/${id}/history`, { headers: withCsrf() });
    if (!res.ok) throw new Error(await res.text());

    const msgs = await res.json();
    chatEl.innerHTML = `
      <div class="flex flex-col items-center justify-center h-full text-slate-500 opacity-50 space-y-2">
        <svg class="w-12 h-12" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"></path>
        </svg>
        <p>Inicia una conversación...</p>
      </div>
    `;

    if (Array.isArray(msgs) && msgs.length > 0) {
      chatEl.innerHTML = '';
      msgs.forEach(m => addMsg(m.role === 'USER' ? 'user' : 'assistant', m.content || ''));
    }
  } finally {
    isLoadingHistory = false;
  }
}

function renderSessions(filter = '') {
  const q = (filter || '').toLowerCase().trim();
  const filtered = sessionsCache.filter(s => {
    const t = (s.title || '').toLowerCase();
    const id = (s.id || '').toLowerCase();
    return !q || t.includes(q) || id.includes(q);
  });

  const renderTo = (el) => {
    if (!el) return;
    el.innerHTML = '';
    filtered.forEach(s => {
      const item = document.createElement('div');
      const isActive = s.id === sessionId;

      item.className = `
        p-3 rounded-xl border cursor-pointer transition
        ${isActive ? 'border-blue-500/60 bg-blue-600/10' : 'border-slate-700/40 bg-slate-900/20 hover:bg-slate-800/30'}
      `;

      const title = s.title || 'Chat sin título';
      const subtitle = (s.lastActivityAt || s.createdAt || '').toString();

      item.innerHTML = `
        <div class="flex items-center justify-between gap-2">
          <div class="min-w-0">
            <div class="text-sm font-semibold truncate">${title}</div>
            <div class="text-[10px] text-slate-500 truncate">${s.id.substring(0, 8)} · ${subtitle}</div>
          </div>
          <button class="rename px-2 py-1 text-[10px] rounded-lg border border-slate-700/50 hover:border-blue-500/40">Renombrar</button>
        </div>
      `;

      // activar chat
      item.addEventListener('click', async () => {
        if (s.id === sessionId) return;
        await activateSession(s.id);
      });

      // renombrar: botón + doble click
      item.querySelector('.rename').addEventListener('click', async (e) => {
        e.stopPropagation();
        await renameSessionPrompt(s.id, title);
      });

      item.addEventListener('dblclick', async (e) => {
        e.stopPropagation();
        await renameSessionPrompt(s.id, title);
      });

      el.appendChild(item);
    });
  };

  renderTo(sessionListEl);
  renderTo(sessionListMobileEl);
}

async function loadSessions() {
  const res = await fetch('/api/chat/sessions', { headers: withCsrf() });
  if (!res.ok) throw new Error(await res.text());
  sessionsCache = await res.json();
  renderSessions(sessionSearchEl?.value || '');
}

async function activateSession(id) {
  const res = await fetch(`/api/chat/sessions/${id}/activate`, {
    method: 'PUT',
    headers: withCsrf({ 'Content-Type': 'application/json' }),
  });
  if (!res.ok) throw new Error(await res.text());

  sessionId = id;
  setSidLabel(sessionId);

  await loadHistory(sessionId);
  await loadSessions(); // para remarcar activo

  // cerrar drawer móvil
  hideDrawer();
}

async function createNewChat() {
  // Si tu backend usa otra ruta, cámbiala. Ej: POST /api/chat/sessions
  const res = await fetch('/api/chat/sessions', {
    method: 'POST',
    headers: withCsrf({ 'Content-Type': 'application/json' }),
  });
  if (!res.ok) throw new Error(await res.text());

  const data = await res.json();
  sessionId = data.id || data.sessionId;
  setSidLabel(sessionId);

  chatEl.innerHTML = `
    <div class="flex flex-col items-center justify-center h-full text-slate-500 opacity-50 space-y-2">
      <svg class="w-12 h-12" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
              d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"></path>
      </svg>
      <p>Inicia una conversación...</p>
    </div>
  `;

  await loadSessions();
  hideDrawer();
}

async function renameSessionPrompt(id, currentTitle) {
  const next = prompt('Nuevo nombre del chat:', currentTitle || '');
  if (next == null) return;

  const title = next.trim();
  if (!title) return;

  const res = await fetch(`/api/chat/sessions/${id}/title`, {
    method: 'PUT',
    headers: withCsrf({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ title })
  });
  if (!res.ok) throw new Error(await res.text());

  await loadSessions();
}

async function send() {
  const text = inputEl.value.trim();
  if (!text) return;

  inputEl.value = '';
  addMsg('user', text);

  const typingId = showTyping();
  sendBtn.disabled = true;

  try {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: withCsrf({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        sessionId,
        message: text,
        model: modelSelectEl?.value || null
      })
    });

    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();

    if (!res.ok) throw new Error(await res.text());

    const data = await res.json();
    sessionId = data.sessionId;
    setSidLabel(sessionId);

    addMsg('assistant', data.reply, data.sources);

    // refresca lista para ordenar por última actividad + contador
    await loadSessions();
  } catch (e) {
    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();
    addMsg('assistant', '⚠️ Error: ' + (e.message || 'desconocido'));
  } finally {
    sendBtn.disabled = false;
    inputEl.focus();
  }
}

/* Drawer mobile */
function showDrawer() {
  if (!sessionDrawer) return;
  sessionDrawer.classList.remove('hidden');
}
function hideDrawer() {
  if (!sessionDrawer) return;
  sessionDrawer.classList.add('hidden');
}

/* Listeners */
sendBtn?.addEventListener('click', send);
inputEl?.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });

newChatBtn?.addEventListener('click', async () => {
  try { await createNewChat(); } catch (e) { alert(e.message); }
});

toggleSessionsBtn?.addEventListener('click', showDrawer);
drawerBackdrop?.addEventListener('click', hideDrawer);
closeDrawerBtn?.addEventListener('click', hideDrawer);

sessionSearchEl?.addEventListener('input', () => renderSessions(sessionSearchEl.value));
sessionSearchMobileEl?.addEventListener('input', () => renderSessions(sessionSearchMobileEl.value));

(async function init() {
  try {
    loadModelSelection();
    await ensureActiveSession();
    await loadSessions();
    await loadHistory(sessionId);
  } catch (e) {
    console.error(e);
    addMsg('assistant', '⚠️ No se pudo inicializar el chat: ' + (e.message || 'error'));
  }
})();
