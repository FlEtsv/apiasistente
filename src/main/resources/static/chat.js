/**
 * Chat UI + Sesiones (con CSRF)
 */

const chatEl = document.getElementById('chat');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('send');
const sidEl = document.getElementById('sid');

const newChatBtn = document.getElementById('newChat');
const sessionListEl = document.getElementById('sessionList');
const sessionSearchEl = document.getElementById('sessionSearch');

const toggleSessionsBtn = document.getElementById('toggleSessions');
const sessionDrawer = document.getElementById('sessionDrawer');
const drawerBackdrop = document.getElementById('drawerBackdrop');
const closeDrawerBtn = document.getElementById('closeDrawer');
const sessionListMobileEl = document.getElementById('sessionListMobile');
const sessionSearchMobileEl = document.getElementById('sessionSearchMobile');

// CSRF (FIX CLAVE)
const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

let sessionId = localStorage.getItem('apiasistente.sessionId');

function scrollDown() {
  requestAnimationFrame(() => {
    chatEl.scrollTo({ top: chatEl.scrollHeight, behavior: 'smooth' });
  });
}

function safeHtml(text) {
  // escape mínimo (evita inyectar HTML por accidente)
  return (text ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function addMsg(who, text, sources = []) {
  // limpiar placeholder
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
  content.innerHTML = safeHtml(text).replace(/\n/g, '<br>');
  div.appendChild(content);

  if (sources && sources.length > 0) {
    const sourcesDiv = document.createElement('div');
    sourcesDiv.className = "mt-4 pt-3 border-t border-slate-700/50 space-y-2";

    const title = document.createElement('p');
    title.className = "text-[10px] uppercase tracking-widest text-slate-400 font-bold mb-2";
    title.textContent = "Fuentes Consultadas";
    sourcesDiv.appendChild(title);

    sources.forEach((s, idx) => {
      const score = (typeof s.score === 'number') ? (s.score * 100).toFixed(0) : '—';
      const sourceCard = document.createElement('div');
      sourceCard.className = "text-[11px] bg-slate-800/50 p-2 rounded-lg border border-slate-700/30 hover:border-blue-500/30 transition-colors";
      sourceCard.innerHTML = `
        <div class="flex justify-between font-bold text-blue-400 mb-1">
          <span>${safeHtml(s.documentTitle || ('Documento ' + (idx + 1)))}</span>
          <span class="opacity-60 text-[9px]">Relevancia: ${score}%</span>
        </div>
        <div class="text-slate-400 italic font-light">"${safeHtml(s.snippet || '')}"</div>
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

async function apiFetch(url, options = {}) {
  const opts = { credentials: 'same-origin', ...options };
  opts.headers = { ...(opts.headers || {}) };

  const method = (opts.method || 'GET').toUpperCase();
  const isWrite = method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS';

  // Añadir CSRF en escrituras
  if (isWrite && csrfToken && csrfHeader) {
    opts.headers[csrfHeader] = csrfToken;
  }

  return fetch(url, opts);
}

async function readError(res) {
  const ct = res.headers.get('content-type') || '';
  try {
    if (ct.includes('application/json')) {
      const j = await res.json();
      return JSON.stringify(j);
    }
    return await res.text();
  } catch {
    return `HTTP ${res.status}`;
  }
}

function setSessionUI(id) {
  sessionId = id;
  localStorage.setItem('apiasistente.sessionId', sessionId);
  sidEl.textContent = sessionId ? sessionId.substring(0, 8) : '-';
}

async function ensureActiveSession() {
  // Si tu backend ya tiene /api/chat/active (lo tienes, sale en logs)
  const res = await apiFetch('/api/chat/active');
  if (!res.ok) throw new Error(await readError(res));
  const data = await res.json(); // { sessionId: "..." } o similar
  if (data.sessionId) setSessionUI(data.sessionId);
}

async function loadHistory() {
  // Si sessionId apunta a una sesión antigua inexistente (por recrear DB), te fallará.
  if (!sessionId) {
    await ensureActiveSession();
  }

  sidEl.textContent = sessionId.substring(0, 8);

  const res = await apiFetch(`/api/chat/${sessionId}/history`);
  if (!res.ok) {
    // Si la sesión no existe/ya no es tuya, reseteamos y creamos una nueva
    localStorage.removeItem('apiasistente.sessionId');
    sessionId = null;
    await ensureActiveSession();

    const res2 = await apiFetch(`/api/chat/${sessionId}/history`);
    if (!res2.ok) return;
    const msgs2 = await res2.json();
    if (msgs2.length > 0) chatEl.innerHTML = '';
    msgs2.forEach(m => addMsg(m.role === 'USER' ? 'user' : 'assistant', m.content));
    return;
  }

  const msgs = await res.json();
  if (msgs.length > 0) chatEl.innerHTML = '';
  msgs.forEach(m => addMsg(m.role === 'USER' ? 'user' : 'assistant', m.content));
}

function renderSessionItem(s, targetEl) {
  const item = document.createElement('div');
  item.className =
    "p-3 rounded-xl border border-slate-700/40 bg-slate-900/30 hover:bg-slate-800/30 cursor-pointer transition flex items-center justify-between gap-3";

  const left = document.createElement('div');
  left.className = "min-w-0";
  const title = document.createElement('div');
  title.className = "text-sm font-semibold truncate";
  title.textContent = s.title || (s.id ? s.id.substring(0, 8) : 'Chat');
  const meta = document.createElement('div');
  meta.className = "text-[10px] text-slate-500 mt-0.5";
  meta.textContent = `${s.messageCount ?? 0} msgs`;
  left.appendChild(title);
  left.appendChild(meta);

  const right = document.createElement('div');
  right.className = "flex items-center gap-2 shrink-0";

  const openBtn = document.createElement('button');
  openBtn.className = "px-2 py-1 text-[10px] rounded-lg bg-blue-600/80 hover:bg-blue-500/80";
  openBtn.textContent = "Abrir";
  openBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    await activateSession(s.id);
  });

  const delBtn = document.createElement('button');
  delBtn.className = "px-2 py-1 text-[10px] rounded-lg bg-red-600/70 hover:bg-red-500/70";
  delBtn.textContent = "Borrar";
  delBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    await deleteSession(s.id);
  });

  right.appendChild(openBtn);
  right.appendChild(delBtn);

  item.appendChild(left);
  item.appendChild(right);

  item.addEventListener('click', async () => activateSession(s.id));

  targetEl.appendChild(item);
}

async function loadSessionsList() {
  // Si no existe en tu backend, no rompe: simplemente no mostrará lista.
  const res = await apiFetch('/api/chat/sessions');
  if (!res.ok) return;

  const data = await res.json(); // lista
  const list = Array.isArray(data) ? data : (data.items || []);

  const q = (sessionSearchEl?.value || '').toLowerCase().trim();
  const filtered = q
    ? list.filter(s => (s.title || '').toLowerCase().includes(q) || (s.id || '').includes(q))
    : list;

  if (sessionListEl) {
    sessionListEl.innerHTML = '';
    filtered.forEach(s => renderSessionItem(s, sessionListEl));
  }
  if (sessionListMobileEl) {
    sessionListMobileEl.innerHTML = '';
    filtered.forEach(s => renderSessionItem(s, sessionListMobileEl));
  }
}

async function activateSession(id) {
  // Endpoint que ya aparece en tus logs
  const res = await apiFetch(`/api/chat/sessions/${id}/activate`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' }
  });

  if (!res.ok) {
    const msg = await readError(res);
    addMsg('assistant', `⚠️ No pude activar la sesión.\n${msg}`);
    return;
  }

  setSessionUI(id);
  chatEl.innerHTML = '';
  await loadHistory();

  // cerrar drawer móvil si está abierto
  if (sessionDrawer && !sessionDrawer.classList.contains('hidden')) {
    sessionDrawer.classList.add('hidden');
  }
}

async function deleteSession(id) {
  const res = await apiFetch(`/api/chat/sessions/${id}`, { method: 'DELETE' });
  if (!res.ok) {
    const msg = await readError(res);
    addMsg('assistant', `⚠️ No pude borrar la sesión.\n${msg}`);
    return;
  }

  // si borras la activa, crea otra
  if (sessionId === id) {
    localStorage.removeItem('apiasistente.sessionId');
    sessionId = null;
    chatEl.innerHTML = '';
    await ensureActiveSession();
    await loadHistory();
  }

  await loadSessionsList();
}

async function createNewChat() {
  // Intento 1: si tienes POST /api/chat/sessions (si existe)
  let res = await apiFetch('/api/chat/sessions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: null })
  });

  // Fallback: usar /api/chat/active si tu backend no tiene POST /sessions
  if (res.status === 404 || res.status === 405) {
    res = await apiFetch('/api/chat/active', { method: 'GET' });
  }

  if (!res.ok) {
    const msg = await readError(res);
    addMsg('assistant', `⚠️ No pude crear un chat nuevo.\n${msg}`);
    return;
  }

  const data = await res.json();
  if (data.sessionId) {
    setSessionUI(data.sessionId);
    chatEl.innerHTML = '';
    await loadHistory();
    await loadSessionsList();
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
    // si la sesión no existe (por DB recreada), fuerza una nueva
    if (!sessionId) await ensureActiveSession();

    const res = await apiFetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, message: text })
    });

    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();

    if (!res.ok) {
      const msg = await readError(res);

      if (res.status === 403) {
        addMsg('assistant', '⚠️ 403 Forbidden: CSRF/permisos.\nRecarga la página.\n\nDetalles:\n' + msg);
      } else {
        addMsg('assistant', '⚠️ Error:\n' + msg);
      }
      return;
    }

    const data = await res.json();
    if (data.sessionId && data.sessionId !== sessionId) setSessionUI(data.sessionId);

    addMsg('assistant', data.reply, data.sources || []);
    await loadSessionsList();
  } catch (e) {
    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();
    addMsg('assistant', '⚠️ Error JS: ' + (e?.message || e));
  } finally {
    sendBtn.disabled = false;
    inputEl.focus();
  }
}

function openDrawer() {
  if (!sessionDrawer) return;
  sessionDrawer.classList.remove('hidden');
}
function closeDrawer() {
  if (!sessionDrawer) return;
  sessionDrawer.classList.add('hidden');
}

function bindEvents() {
  // Evita que un null rompa TODO el script
  sendBtn?.addEventListener('click', send);
  inputEl?.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });

  newChatBtn?.addEventListener('click', createNewChat);

  sessionSearchEl?.addEventListener('input', loadSessionsList);
  sessionSearchMobileEl?.addEventListener('input', loadSessionsList);

  toggleSessionsBtn?.addEventListener('click', () => { openDrawer(); });
  closeDrawerBtn?.addEventListener('click', () => { closeDrawer(); });
  drawerBackdrop?.addEventListener('click', () => { closeDrawer(); });
}

async function init() {
  bindEvents();

  try {
    // Si había una sessionId antigua en localStorage, puede estar muerta. loadHistory lo corrige.
    await loadHistory();
  } catch (e) {
    console.error(e);
  }

  // cargar lista de chats si existe el endpoint
  try { await loadSessionsList(); } catch {}

  // si no hay sessionId, crea/obtén una
  if (!sessionId) {
    try {
      await ensureActiveSession();
      await loadHistory();
    } catch {}
  }
}

init();
