/**
 * chat.js - sesiones (lista + activar + renombrar + eliminar) + historial + CSRF
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
const toggleOpsBtn = document.getElementById('toggleOps');
const closeOpsBtn = document.getElementById('closeOps');
const opsBackdropEl = document.getElementById('opsBackdrop');
const opsPanelEl = document.querySelector('.ops-panel');
const opsPanelDetailsEl = document.getElementById('opsPanelDetails');
const opsSummaryEl = document.querySelector('#opsPanelDetails > summary');
const rootEl = document.documentElement;
const mobileViewportQuery = window.matchMedia('(max-width: 980px)');
const reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');

const newChatBtn = document.getElementById('newChat');

const monitorIntervalEl = document.getElementById('monitorInterval');
const monitorEventsEl = document.getElementById('monitorEvents');
const monitorLastUpdateEl = document.getElementById('monitorLastUpdate');
const monitorApplyBtn = document.getElementById('btnApplyMonitorInterval');
const monitorStackProvisionBtn = document.getElementById('btnProvisionMonitorStack');
const monitorStackRefreshBtn = document.getElementById('btnRefreshMonitorStack');
const monitorStackActionStatusEl = document.getElementById('monitorStackActionStatus');
const triggerMonitorProvisionSidebarBtn = document.getElementById('triggerMonitorProvisionSidebar');
const triggerMonitorProvisionMobileBtn = document.getElementById('triggerMonitorProvisionMobile');
const triggerMonitorProvisionTopBtn = document.getElementById('triggerMonitorProvisionTop');
const monitorProvisionQuickStatusEl = document.getElementById('monitorProvisionQuickStatus');
const ragContextPillEl = document.getElementById('ragContextPill');
const ragTotalContextEl = document.getElementById('ragTotalContext');
const ragGlobalContextEl = document.getElementById('ragGlobalContext');
const ragOwnerContextEl = document.getElementById('ragOwnerContext');
const ragTopKEl = document.getElementById('ragTopK');
const ragChunkConfigEl = document.getElementById('ragChunkConfig');
const ragLastUpdatedEl = document.getElementById('ragLastUpdated');
const ragRefreshBtn = document.getElementById('btnRefreshRagContext');
const ragDecisionUsedRateEl = document.getElementById('ragDecisionUsedRate');
const ragDecisionAvoidedRateEl = document.getElementById('ragDecisionAvoidedRate');
const ragDecisionTotalTurnsEl = document.getElementById('ragDecisionTotalTurns');
const ragDecisionRetryRateEl = document.getElementById('ragDecisionRetryRate');
const ragDecisionGateSummaryEl = document.getElementById('ragDecisionGateSummary');
const ragDecisionPostCheckSummaryEl = document.getElementById('ragDecisionPostCheckSummary');
const ragDecisionLatencyEl = document.getElementById('ragDecisionLatency');
const ragDecisionConfidenceEl = document.getElementById('ragDecisionConfidence');
const ragDecisionUpdatedEl = document.getElementById('ragDecisionUpdated');
const ragDecisionEventsEl = document.getElementById('ragDecisionEvents');
const ragDecisionRefreshBtn = document.getElementById('btnRefreshRagDecision');

const openApiKeysInline = document.getElementById('openApiKeysInline');
const mobileNavMenuEl = document.getElementById('mobileNavMenu');
const mobileOpenApiKeysEl = document.getElementById('mobileOpenApiKeys');
const composerMenuEl = document.getElementById('composerMenu');
const composerMenuToggleEl = document.getElementById('composerMenuToggle');
const mediaInputEl = document.getElementById('mediaInput');
const cameraInputEl = document.getElementById('cameraInput');
const attachBtnEl = document.getElementById('attachBtn');
const cameraBtnEl = document.getElementById('cameraBtn');
const clearMediaBtnEl = document.getElementById('clearMediaBtn');
const mediaPreviewEl = document.getElementById('mediaPreview');

let sessionId = null;
let sessionsCache = [];
let isLoadingHistory = false;
const MODEL_STORAGE_KEY = 'chat.model';
const MONITOR_INTERVAL_KEY = 'monitor.intervalMs';
const MONITOR_MIN_SEC = 3;
const MONITOR_MAX_SEC = 3600;
const MAX_MEDIA_ITEMS = 4;
const MAX_MEDIA_BYTES = 6 * 1024 * 1024;
const MAX_MEDIA_TEXT_CHARS = 18000;
const MAX_IMAGE_EDGE_PX = 1536;
const IMAGE_UPLOAD_QUALITY = 0.9;
let monitorTimer = null;
let pendingMedia = [];

function syncViewportHeight() {
  const viewportHeight = window.visualViewport?.height || window.innerHeight || 0;
  if (viewportHeight > 0) {
    rootEl.style.setProperty('--app-height', `${Math.round(viewportHeight)}px`);
  }
  syncKeyboardState(viewportHeight);
}

// Mantiene el textarea compacto y evita que el teclado tape el composer en movil.
function syncComposerHeight() {
  if (!inputEl) return;
  inputEl.style.height = 'auto';
  const next = Math.max(54, Math.min(inputEl.scrollHeight || 54, 180));
  inputEl.style.height = `${next}px`;
}

function closeComposerMenu() {
  if (composerMenuEl?.open) {
    composerMenuEl.open = false;
  }
}

function closeMobileNavMenu() {
  if (mobileNavMenuEl?.open) {
    mobileNavMenuEl.open = false;
  }
}

function syncKeyboardState(viewportHeight = window.visualViewport?.height || window.innerHeight || 0) {
  const layoutHeight = window.innerHeight || viewportHeight || 0;
  const ratio = layoutHeight > 0 ? viewportHeight / layoutHeight : 1;
  const keyboardLikelyOpen = window.innerWidth <= 760 && ratio < 0.8;
  document.body.classList.toggle('keyboard-open', keyboardLikelyOpen);
  if (keyboardLikelyOpen) {
    hideOps();
  }
}

function getSelectedModelLabel() {
  const label = modelSelectEl?.selectedOptions?.[0]?.textContent || 'Auto';
  return String(label).trim().replace(/\s+/g, ' ');
}

function refreshComposerMenuLabel() {
  if (!composerMenuToggleEl) return;
  const modelLabel = getSelectedModelLabel();
  const compactModel = modelLabel.length > 28 ? `${modelLabel.slice(0, 28).trim()}...` : modelLabel;
  const attachmentLabel = pendingMedia.length > 0 ? ` + ${pendingMedia.length} adjunto(s)` : '';
  composerMenuToggleEl.textContent = `Modelo: ${compactModel}${attachmentLabel}`;
}

function escapeHtml(value) {
  const text = value == null ? '' : String(value);
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function reasoningLabel(level) {
  const normalized = String(level || '').toUpperCase();
  if (normalized === 'HIGH') return 'ALTO';
  if (normalized === 'LOW') return 'BAJO';
  return 'MEDIO';
}

function renderAssistantMarkdown(text) {
  const raw = text || '';
  const fallback = escapeHtml(raw).replace(/\n/g, '<br>');

  if (!window.marked || typeof window.marked.parse !== 'function') {
    return fallback;
  }

  try {
    const html = window.marked.parse(raw, {
      gfm: true,
      breaks: true
    });

    if (window.DOMPurify && typeof window.DOMPurify.sanitize === 'function') {
      return window.DOMPurify.sanitize(html, {
        USE_PROFILES: { html: true },
        ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class', 'src', 'alt', 'width', 'height']
      });
    }

    return html;
  } catch (e) {
    return fallback;
  }
}

function sanitizeBase64(dataUrlOrBase64) {
  if (!dataUrlOrBase64) return '';
  const raw = String(dataUrlOrBase64).trim();
  const idx = raw.indexOf(',');
  const clean = (idx >= 0 ? raw.slice(idx + 1) : raw).replace(/\s+/g, '');
  return clean;
}

function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('No se pudo leer archivo.'));
    reader.readAsDataURL(file);
  });
}

function fileToText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('No se pudo leer documento de texto.'));
    reader.readAsText(file);
  });
}

function loadImageElement(dataUrl) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('No se pudo decodificar la imagen.'));
    image.src = dataUrl;
  });
}

async function normalizeImageDataUrl(dataUrl, maxEdgePx = MAX_IMAGE_EDGE_PX, quality = IMAGE_UPLOAD_QUALITY) {
  const image = await loadImageElement(dataUrl);
  const width = image.naturalWidth || image.width || 0;
  const height = image.naturalHeight || image.height || 0;
  if (width <= 0 || height <= 0) return dataUrl;

  const scale = Math.min(1, maxEdgePx / Math.max(width, height));
  const targetWidth = Math.max(1, Math.round(width * scale));
  const targetHeight = Math.max(1, Math.round(height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = targetWidth;
  canvas.height = targetHeight;
  const ctx = canvas.getContext('2d');
  if (!ctx) return dataUrl;
  ctx.drawImage(image, 0, 0, targetWidth, targetHeight);
  return canvas.toDataURL('image/jpeg', quality);
}

function isTextLikeFile(file) {
  const type = (file?.type || '').toLowerCase();
  if (type.startsWith('text/')) return true;
  return type.includes('json') || type.includes('xml') || type.includes('csv') || type.includes('javascript');
}

function inferMediaLabel(item) {
  if ((item.mimeType || '').startsWith('image/')) return 'Imagen';
  if ((item.mimeType || '').includes('pdf')) return 'PDF';
  if (item.text) return 'Documento';
  return 'Archivo';
}

function renderMediaPreview() {
  if (!mediaPreviewEl) {
    refreshComposerMenuLabel();
    return;
  }
  mediaPreviewEl.innerHTML = '';
  if (!pendingMedia.length) {
    refreshComposerMenuLabel();
    return;
  }

  pendingMedia.forEach((item, idx) => {
    const chip = document.createElement('div');
    chip.className = 'media-chip';

    if ((item.mimeType || '').startsWith('image/') && item.base64) {
      const thumb = document.createElement('img');
      thumb.className = 'thumb';
      thumb.alt = item.name || 'imagen';
      thumb.src = `data:${item.mimeType || 'image/png'};base64,${item.base64}`;
      chip.appendChild(thumb);
    }

    const meta = document.createElement('span');
    meta.className = 'meta';
    meta.textContent = `${inferMediaLabel(item)}: ${item.name || 'archivo'}`;

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'remove';
    removeBtn.textContent = 'x';
    removeBtn.addEventListener('click', () => {
      pendingMedia.splice(idx, 1);
      renderMediaPreview();
    });

    chip.appendChild(meta);
    chip.appendChild(removeBtn);
    mediaPreviewEl.appendChild(chip);
  });
  refreshComposerMenuLabel();
}

function clearMediaSelection() {
  pendingMedia = [];
  if (mediaInputEl) mediaInputEl.value = '';
  if (cameraInputEl) cameraInputEl.value = '';
  renderMediaPreview();
}

async function pushMediaFile(file) {
  if (!file) return;
  if (pendingMedia.length >= MAX_MEDIA_ITEMS) {
    throw new Error(`Maximo ${MAX_MEDIA_ITEMS} adjuntos por mensaje.`);
  }
  if (file.size > MAX_MEDIA_BYTES) {
    throw new Error(`Archivo demasiado grande (${file.name}). Limite: ${Math.round(MAX_MEDIA_BYTES / (1024 * 1024))} MB.`);
  }

  const mimeType = (file.type || 'application/octet-stream').toLowerCase();
  const media = {
    name: file.name || 'archivo',
    mimeType
  };

  if (mimeType.startsWith('image/')) {
    if (mimeType.includes('heic') || mimeType.includes('heif')) {
      throw new Error('La foto en formato HEIC/HEIF no es compatible. Usa JPG/PNG en la camara del movil.');
    }
    const dataUrl = await fileToDataUrl(file);
    try {
      const normalizedDataUrl = await normalizeImageDataUrl(dataUrl);
      media.base64 = sanitizeBase64(normalizedDataUrl);
      media.mimeType = 'image/jpeg';
    } catch (e) {
      media.base64 = sanitizeBase64(dataUrl);
    }
  } else if (isTextLikeFile(file)) {
    const text = await fileToText(file);
    media.text = (text || '').slice(0, MAX_MEDIA_TEXT_CHARS);
  } else {
    const dataUrl = await fileToDataUrl(file);
    media.base64 = sanitizeBase64(dataUrl);
  }

  if (!media.base64 && !media.text) {
    throw new Error(`No se pudo leer adjunto: ${file.name}`);
  }

  pendingMedia.push(media);
}

async function handlePickedFiles(fileList) {
  if (!fileList || !fileList.length) return;
  try {
    for (const file of Array.from(fileList)) {
      await pushMediaFile(file);
    }
    renderMediaPreview();
  } catch (e) {
    alert(e.message || 'No se pudo adjuntar archivo.');
  }
}

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

async function readResponsePayload(res) {
  const contentType = res.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    return await res.json().catch(() => null);
  }
  return await res.text().catch(() => '');
}

function extractErrorMessage(payload, res) {
  const status = res?.status || 0;
  const requestId = res?.headers?.get('X-Request-Id') || '';
  if (payload == null) {
    return requestId ? `HTTP ${status} (requestId ${requestId})` : `HTTP ${status}`;
  }
  if (typeof payload === 'string') {
    const clean = payload.trim();
    return requestId
      ? `${clean || `HTTP ${status}`} (requestId ${requestId})`
      : (clean || `HTTP ${status}`);
  }
  const details = Array.isArray(payload.details) && payload.details.length
    ? ` Detalle: ${payload.details.join(' | ')}`
    : '';
  const errorId = payload.errorId || requestId;
  const base = payload.message || payload.error || `HTTP ${status}`;
  return `${base}${errorId ? ` [${errorId}]` : ''}${details}`;
}

function scrollDown() {
  requestAnimationFrame(() => {
    chatEl.scrollTo({
      top: chatEl.scrollHeight,
      behavior: reducedMotionQuery.matches ? 'auto' : 'smooth'
    });
  });
}

function clearWelcomeIfNeeded() {
  if (chatEl.querySelector('.opacity-50')) chatEl.innerHTML = '';
}

function renderInlineMedia(media = []) {
  const list = Array.isArray(media) ? media : [];
  if (!list.length) return null;

  const container = document.createElement('div');
  container.className = 'inline-media';

  list.forEach((item) => {
    if (!item || (!item.base64 && !item.text)) return;
    const mime = String(item.mimeType || '').toLowerCase();
    const chip = document.createElement('div');
    chip.className = 'inline-media-chip';

    if (mime.startsWith('image/') && item.base64) {
      const img = document.createElement('img');
      img.className = 'inline-media-thumb';
      img.alt = item.name || 'adjunto';
      img.src = `data:${mime || 'image/png'};base64,${item.base64}`;
      chip.appendChild(img);
    }

    const label = document.createElement('span');
    label.className = 'inline-media-name';
    label.textContent = item.name || inferMediaLabel(item);
    chip.appendChild(label);
    container.appendChild(chip);
  });

  return container.childElementCount ? container : null;
}

function msgTimestamp() {
  const now = new Date();
  return now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function addCopyButton(div, text) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'msg-copy';
  btn.textContent = 'Copiar';
  btn.addEventListener('click', () => {
    navigator.clipboard?.writeText(text || '').then(() => {
      btn.textContent = 'Copiado';
      btn.classList.add('copied');
      setTimeout(() => {
        btn.textContent = 'Copiar';
        btn.classList.remove('copied');
      }, 1800);
    }).catch(() => {});
  });
  return btn;
}

function addMsg(who, text, sources = [], meta = null, media = []) {
  clearWelcomeIfNeeded();

  const wrapper = document.createElement('div');
  wrapper.className = `msg-wrapper flex ${who === 'user' ? 'justify-end' : 'justify-start'} animate-msg mb-5`;

  const div = document.createElement('div');
  const isUser = who === 'user';
  const ragUsed = !isUser && meta && meta.ragUsed === true;

  const baseClasses = "max-w-[92%] sm:max-w-[85%] lg:max-w-[78%] px-5 py-3 rounded-2xl shadow-xl text-sm leading-relaxed";
  const userClasses = "bg-blue-600 text-white rounded-tr-none ml-3 sm:ml-10";
  const aiClasses = "glass text-slate-100 rounded-tl-none mr-3 sm:mr-10 border border-slate-700/50";
  div.className = `${baseClasses} ${isUser ? userClasses : aiClasses}`;

  const content = document.createElement('div');
  if (isUser) {
    content.className = 'whitespace-pre-wrap break-words';
    content.textContent = text || '';
  } else {
    content.className = 'msg-markdown';
    content.innerHTML = renderAssistantMarkdown(text);
    content.querySelectorAll('a').forEach(a => {
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
    });
  }
  div.appendChild(content);

  if (isUser) {
    const inlineMedia = renderInlineMedia(media);
    if (inlineMedia) div.appendChild(inlineMedia);
  }

  // RAG sources
  if (!isUser && ragUsed && sources && sources.length > 0) {
    const sourcesDiv = document.createElement('div');
    sourcesDiv.className = 'rag-sources';

    const title = document.createElement('div');
    title.className = 'rag-sources-title';
    title.textContent = 'Fuentes consultadas';
    sourcesDiv.appendChild(title);

    sources.forEach((s, idx) => {
      const card = document.createElement('div');
      card.className = 'rag-source-card';
      const scoreVal = ((s.score || 0) * 100).toFixed(0);
      const titleText = escapeHtml(s.documentTitle || `Documento ${idx + 1}`);
      const snippetText = escapeHtml(s.snippet || '');
      card.innerHTML = `
        <div class="rag-source-head">
          <span class="rag-source-title">${titleText}</span>
          <span class="rag-source-score">${scoreVal}%</span>
        </div>
        <div class="rag-source-snippet">"${snippetText}"</div>
      `;
      sourcesDiv.appendChild(card);
    });
    div.appendChild(sourcesDiv);
  }

  // Meta badge
  if (!isUser && meta && typeof meta === 'object') {
    if (ragUsed) {
      const safe = meta.safe !== false;
      const confidence = Number.isFinite(meta.confidence) ? Math.max(0, Math.min(1, meta.confidence)) : 0;
      const groundedSources = Number.isFinite(meta.groundedSources) ? Math.max(0, meta.groundedSources) : 0;
      const badge = document.createElement('div');
      badge.className = `msg-rag-badge ${safe ? 'rag-ok' : 'rag-warn'}`;
      badge.innerHTML = `<span>${safe ? '✓' : '!'}</span> ${safe ? 'RAG ok' : 'No seguro'} · ${Math.round(confidence * 100)}% · ${groundedSources} fuente${groundedSources !== 1 ? 's' : ''}`;
      div.appendChild(badge);
    } else {
      const reasoning = reasoningLabel(meta.reasoningLevel);
      const badge = document.createElement('div');
      badge.className = 'msg-rag-badge rag-plain';
      badge.textContent = `${reasoning === 'ALTO' ? '◈' : reasoning === 'MEDIO' ? '◇' : '·'} ${reasoning}`;
      div.appendChild(badge);
    }
  }

  // Footer: timestamp + copy
  const footer = document.createElement('div');
  footer.className = `msg-footer ${isUser ? 'justify-end' : 'justify-start'}`;
  const ts = document.createElement('span');
  ts.className = 'msg-ts';
  ts.textContent = msgTimestamp();
  footer.appendChild(ts);
  if (!isUser) {
    footer.appendChild(addCopyButton(div, text));
  }
  div.appendChild(footer);

  wrapper.appendChild(div);
  chatEl.appendChild(wrapper);
  scrollDown();
}
function showTyping() {
  const id = 'typing-' + Date.now();
  const html = `
    <div id="${id}" class="msg-wrapper flex justify-start animate-msg mb-5">
      <div class="glass px-5 py-4 rounded-2xl rounded-tl-none mr-3 sm:mr-10 border border-slate-700/50">
        <div class="thinking-row">
          <div class="thinking-dots"><span></span><span></span><span></span></div>
          <span>Procesando...</span>
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
  const options = Array.from(modelSelectEl.options).map(o => o.value);
  let saved = localStorage.getItem(MODEL_STORAGE_KEY);
  if (saved === 'default') {
    saved = 'auto';
  }
  if (saved && options.includes(saved)) {
    modelSelectEl.value = saved;
  }
  refreshComposerMenuLabel();
  modelSelectEl.addEventListener('change', () => {
    localStorage.setItem(MODEL_STORAGE_KEY, modelSelectEl.value);
    refreshComposerMenuLabel();
  });
}

function getMonitorIntervalMs() {
  const raw = localStorage.getItem(MONITOR_INTERVAL_KEY);
  const fallback = 10000;
  if (!raw) return fallback;
  const ms = parseInt(raw, 10);
  if (!Number.isFinite(ms) || ms < MONITOR_MIN_SEC * 1000) return fallback;
  return ms;
}

function setMonitorIntervalMs(ms) {
  localStorage.setItem(MONITOR_INTERVAL_KEY, String(ms));
}

function syncMonitorIntervalUI() {
  if (!monitorIntervalEl) return;
  const ms = getMonitorIntervalMs();
  monitorIntervalEl.value = String(Math.round(ms / 1000));
}

function formatDateTime(value) {
  if (!value) return '-';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleString();
}

function formatPercent(value) {
  const num = Number(value);
  if (!Number.isFinite(num)) return '-';
  return `${Math.round(Math.max(0, Math.min(1, num)) * 100)}%`;
}

function formatMs(value) {
  const num = Number(value);
  if (!Number.isFinite(num)) return '-';
  return `${num.toFixed(num >= 100 ? 0 : 1)} ms`;
}

function endpointHostPort(url) {
  if (!url) return '';
  try {
    const parsed = new URL(url);
    const host = parsed.hostname || '';
    if (!host) return '';
    const port = parsed.port || (parsed.protocol === 'https:' ? '443' : '80');
    return `${host}:${port}`;
  } catch (e) {
    return String(url).replace(/^https?:\/\//i, '').replace(/\/+$/, '');
  }
}

function metaMonitoringUrl(metaName) {
  return document.querySelector(`meta[name="${metaName}"]`)?.getAttribute('content') || '';
}

function defaultServiceBaseUrl(link) {
  if (!link) return '';
  return link.getAttribute('data-default-url') || '';
}

function applyServiceLink(linkId, baseUrl, verboseText = false) {
  const link = document.getElementById(linkId);
  if (!link) return;
  const resolvedBaseUrl = baseUrl || defaultServiceBaseUrl(link) || '';
  if (resolvedBaseUrl) link.href = resolvedBaseUrl;
  const label = link.getAttribute('data-label') || link.textContent || 'Abrir';
  const hostPort = endpointHostPort(resolvedBaseUrl || link.href || '');
  const forceHostPort = link.classList.contains('endpoint-link');
  link.textContent = (verboseText || forceHostPort) && hostPort ? `${label} (${hostPort})` : label;
  if (hostPort) {
    link.title = `${label} - ${hostPort}`;
  }
}

function stackStateLabel(status) {
  if (!status || typeof status !== 'object') return 'Stack: sin datos';
  const docker = status.dockerReachable ? 'Docker OK' : (status.dockerInstalled ? 'Docker daemon OFF' : 'Docker CLI OFF');
  const compose = status.composeAvailable ? 'Compose OK' : 'Compose OFF';
  const api = status.apiContainerRunning ? 'API ON' : 'API OFF';
  const prom = status.prometheusContainerRunning ? 'Prom ON' : 'Prom OFF';
  const grafana = status.grafanaContainerRunning ? 'Grafana ON' : 'Grafana OFF';
  return `${docker} | ${compose} | ${api} | ${prom} | ${grafana}`;
}

function setMonitorStackStatus(status, fallbackText = '') {
  if (!monitorStackActionStatusEl) return;
  const summary = stackStateLabel(status);
  const message = status?.message ? String(status.message) : fallbackText;
  monitorStackActionStatusEl.textContent = message ? `${summary} - ${message}` : summary;
}

function setMonitorProvisionQuickStatus(message, isError = false) {
  if (!monitorProvisionQuickStatusEl) return;
  monitorProvisionQuickStatusEl.textContent = message || '';
  monitorProvisionQuickStatusEl.className = isError ? 'sub status-down' : 'sub status-up';
}

function setProvisionTriggersDisabled(disabled) {
  [monitorStackProvisionBtn, triggerMonitorProvisionSidebarBtn, triggerMonitorProvisionMobileBtn, triggerMonitorProvisionTopBtn]
    .forEach((btn) => {
      if (btn) btn.disabled = disabled;
    });
}

async function loadMonitorStackStatus() {
  if (!monitorStackActionStatusEl) return;
  try {
    const res = await fetch('/api/monitor/stack/status', { headers: withCsrf() });
    const payload = await readResponsePayload(res);
    if (!res.ok) {
      throw new Error(extractErrorMessage(payload, res));
    }
    setMonitorStackStatus(payload || {});
  } catch (e) {
    setMonitorStackStatus(null, `Error stack: ${e.message || 'no disponible'}`);
  }
}

async function activateMonitorStack() {
  setProvisionTriggersDisabled(true);
  setMonitorStackStatus(null, 'Activando stack...');
  setMonitorProvisionQuickStatus('Activando stack...', false);
  try {
    const res = await fetch('/api/monitor/stack/up', { method: 'POST', headers: withCsrf() });
    const payload = await readResponsePayload(res);
    if (!res.ok) {
      throw new Error(extractErrorMessage(payload, res));
    }
    setMonitorStackStatus(payload || {});
    await loadOpsStatus();
    await loadMonitorEvents();
    const ok = payload?.success === true;
    setMonitorProvisionQuickStatus(
      ok ? 'Stack de observabilidad activo' : 'Stack parcial, revisar estado',
      !ok
    );
    return ok;
  } catch (e) {
    setMonitorStackStatus(null, `No se pudo activar: ${e.message || 'error'}`);
    setMonitorProvisionQuickStatus(`Error activando stack: ${e.message || 'error'}`, true);
    return false;
  } finally {
    setProvisionTriggersDisabled(false);
  }
}

async function triggerMonitorProvisionShortcut() {
  closeMobileNavMenu();
  closeComposerMenu();
  hideDrawer();
  await activateMonitorStack();
}

const aiStatusOverlayEl = document.getElementById('aiStatusOverlay');
const aiStatusTextEl = document.getElementById('aiStatusText');

function updateAiStatusOverlay(docs, chunks, hasContent) {
  if (!aiStatusOverlayEl) return;
  const hasRag = hasContent && (docs > 0 || chunks > 0);
  aiStatusOverlayEl.classList.toggle('has-rag', hasRag);
  if (aiStatusTextEl) {
    aiStatusTextEl.textContent = hasRag
      ? `RAG · ${docs}d / ${chunks}c`
      : 'RAG sin datos';
  }
}

function setRagContextLoading() {
  if (ragContextPillEl) ragContextPillEl.textContent = 'RAG: cargando...';
  if (ragTotalContextEl) ragTotalContextEl.textContent = '-';
  if (ragGlobalContextEl) ragGlobalContextEl.textContent = '-';
  if (ragOwnerContextEl) ragOwnerContextEl.textContent = '-';
  if (ragTopKEl) ragTopKEl.textContent = '-';
  if (ragChunkConfigEl) ragChunkConfigEl.textContent = '-';
  if (ragLastUpdatedEl) ragLastUpdatedEl.textContent = '-';
}

function renderRagContextStats(data) {
  const total = `${data.totalDocuments || 0} docs / ${data.totalChunks || 0} chunks`;
  const global = `${data.globalDocuments || 0} docs / ${data.globalChunks || 0} chunks`;
  const owner = `${data.ownerDocuments || 0} docs / ${data.ownerChunks || 0} chunks`;
  const chunkCfg = `${data.chunkSize || '-'} (+${data.chunkOverlap || '-'})`;
  const docs = data.totalDocuments || 0;
  const chunks = data.totalChunks || 0;

  if (ragContextPillEl) ragContextPillEl.textContent = `RAG: ${docs}d / ${chunks}c`;
  if (ragTotalContextEl) ragTotalContextEl.textContent = total;
  if (ragGlobalContextEl) ragGlobalContextEl.textContent = global;
  if (ragOwnerContextEl) ragOwnerContextEl.textContent = owner;
  if (ragTopKEl) ragTopKEl.textContent = String(data.topK ?? '-');
  if (ragChunkConfigEl) ragChunkConfigEl.textContent = chunkCfg;
  if (ragLastUpdatedEl) ragLastUpdatedEl.textContent = formatDateTime(data.lastUpdatedAt);
  updateAiStatusOverlay(docs, chunks, true);
}

function renderRagContextError(message) {
  const text = message || 'No disponible';
  if (ragContextPillEl) ragContextPillEl.textContent = `RAG: ${text}`;
  if (ragTotalContextEl) ragTotalContextEl.textContent = text;
  if (ragGlobalContextEl) ragGlobalContextEl.textContent = text;
  if (ragOwnerContextEl) ragOwnerContextEl.textContent = text;
  if (ragTopKEl) ragTopKEl.textContent = text;
  if (ragChunkConfigEl) ragChunkConfigEl.textContent = text;
  if (ragLastUpdatedEl) ragLastUpdatedEl.textContent = text;
  updateAiStatusOverlay(0, 0, false);
}

function renderRagDecisionLoading() {
  if (ragDecisionUsedRateEl) ragDecisionUsedRateEl.textContent = '-';
  if (ragDecisionAvoidedRateEl) ragDecisionAvoidedRateEl.textContent = '-';
  if (ragDecisionTotalTurnsEl) ragDecisionTotalTurnsEl.textContent = '-';
  if (ragDecisionRetryRateEl) ragDecisionRetryRateEl.textContent = '-';
  if (ragDecisionGateSummaryEl) ragDecisionGateSummaryEl.textContent = '-';
  if (ragDecisionPostCheckSummaryEl) ragDecisionPostCheckSummaryEl.textContent = '-';
  if (ragDecisionLatencyEl) ragDecisionLatencyEl.textContent = '-';
  if (ragDecisionConfidenceEl) ragDecisionConfidenceEl.textContent = '-';
  if (ragDecisionUpdatedEl) ragDecisionUpdatedEl.textContent = '-';
}

function renderRagDecisionEvents(events) {
  if (!ragDecisionEventsEl) return;
  if (!Array.isArray(events) || events.length === 0) {
    ragDecisionEventsEl.innerHTML = `
      <div class="event-item">
        <div class="event-head">
          <span>Sin telemetria</span>
          <span class="event-badge recover">IDLE</span>
        </div>
        <div class="sub">Esperando decisiones del motor RAG.</div>
      </div>
    `;
    return;
  }

  ragDecisionEventsEl.innerHTML = '';
  events.slice(0, 8).forEach(evt => {
    const outcome = String(evt?.outcome || 'info').toLowerCase();
    const badgeClass = outcome.includes('skip') || outcome.includes('avoided') || outcome.includes('ok')
      ? 'recover'
      : 'alert';
    const title = evt?.type || 'evento';
    const reason = evt?.reason || '-';
    const detail = evt?.detail || '-';
    const ts = evt?.at ? new Date(evt.at).toLocaleTimeString() : '-';

    const div = document.createElement('div');
    div.className = 'event-item';
    div.innerHTML = `
      <div class="event-head">
        <span>${title}</span>
        <span class="event-badge ${badgeClass}">${escapeHtml(evt?.outcome || 'INFO')}</span>
      </div>
      <div class="sub">${escapeHtml(reason)}</div>
      <div class="sub">${escapeHtml(detail)}</div>
      <div class="sub">${ts}</div>
    `;
    ragDecisionEventsEl.appendChild(div);
  });
}

function renderRagDecisionMetrics(data) {
  if (ragDecisionUsedRateEl) ragDecisionUsedRateEl.textContent = formatPercent(data.ragUsedRate);
  if (ragDecisionAvoidedRateEl) ragDecisionAvoidedRateEl.textContent = formatPercent(data.ragAvoidedRate);
  if (ragDecisionTotalTurnsEl) ragDecisionTotalTurnsEl.textContent = String(data.totalTurns ?? 0);
  if (ragDecisionRetryRateEl) ragDecisionRetryRateEl.textContent = formatPercent(data.postCheckRetryRate);
  if (ragDecisionGateSummaryEl) {
    ragDecisionGateSummaryEl.textContent =
      `${data.gateAttemptedTurns || 0} intento(s), ${data.gateSkippedTurns || 0} evitado(s), ${data.forcedNoEvidenceTurns || 0} sin evidencia`;
  }
  if (ragDecisionPostCheckSummaryEl) {
    ragDecisionPostCheckSummaryEl.textContent =
      `${data.postChecksReviewed || 0} revisado(s), ${data.postCheckRetries || 0} relanzado(s), cache hit ${formatPercent(data.cacheHitRate)}`;
  }
  if (ragDecisionLatencyEl) {
    ragDecisionLatencyEl.textContent =
      `retrieval ${formatMs(data.avgRetrievalPhaseMs)} / embedding ${formatMs(data.avgEmbeddingTimeMs)}`;
  }
  if (ragDecisionConfidenceEl) {
    ragDecisionConfidenceEl.textContent =
      `decision ${formatPercent(data.avgDecisionConfidence)} / heuristica ${formatPercent(data.avgHeuristicConfidence)} / turno ${formatPercent(data.avgTurnConfidence)}`;
  }
  if (ragDecisionUpdatedEl) ragDecisionUpdatedEl.textContent = formatDateTime(data.updatedAt);
  renderRagDecisionEvents(data.recentEvents);
}

async function loadRagDecisionMetrics() {
  if (!ragDecisionUsedRateEl && !ragDecisionEventsEl) return;
  try {
    const res = await fetch('/api/chat/rag/metrics', { headers: withCsrf() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    renderRagDecisionMetrics(data || {});
  } catch (e) {
    renderRagDecisionLoading();
    if (ragDecisionUpdatedEl) ragDecisionUpdatedEl.textContent = 'error';
    renderRagDecisionEvents([]);
  }
}

function renderMonitorEvents(events) {
  if (!monitorEventsEl) return;
  if (!Array.isArray(events) || events.length === 0) {
    monitorEventsEl.innerHTML = `
      <div class="event-item">
        <div class="event-head">
          <span>Sin eventos</span>
          <span class="event-badge recover">OK</span>
        </div>
        <div class="sub">No hay alertas recientes.</div>
      </div>
    `;
    return;
  }

  monitorEventsEl.innerHTML = '';
  events.forEach(evt => {
    const level = (evt.level || '').toUpperCase();
    const badgeClass = level === 'ALERT' ? 'alert' : 'recover';
    const title = evt.title || evt.key || 'Evento';
    const ts = evt.timestamp ? new Date(evt.timestamp).toLocaleTimeString() : '-';
    const msg = evt.message || '';

    const div = document.createElement('div');
    div.className = 'event-item';
    div.innerHTML = `
      <div class="event-head">
        <span>${title}</span>
        <span class="event-badge ${badgeClass}">${level || 'INFO'}</span>
      </div>
      <div class="sub">${msg}</div>
      <div class="sub">${ts}</div>
    `;
    monitorEventsEl.appendChild(div);
  });
}

async function loadMonitorEvents() {
  if (!monitorEventsEl) return;
  try {
    const res = await fetch('/api/monitor/alerts?limit=8', { headers: withCsrf() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    renderMonitorEvents(data);
    if (monitorLastUpdateEl) {
      monitorLastUpdateEl.textContent = new Date().toLocaleTimeString();
    }
  } catch (e) {
    monitorEventsEl.innerHTML = `
      <div class="event-item">
        <div class="event-head">
          <span>Error</span>
          <span class="event-badge alert">ALERT</span>
        </div>
        <div class="sub">No se pudo cargar eventos: ${e.message || 'error'}</div>
      </div>
    `;
  }
}

async function loadOpsStatus() {
  const hasOpsTargets =
    document.getElementById('grafana-dot') ||
    document.getElementById('prometheus-dot') ||
    document.getElementById('grafana-status') ||
    document.getElementById('prometheus-status');
  if (!hasOpsTargets) return;

  const fallbackGrafanaBaseUrl = metaMonitoringUrl('monitoring_grafana_url');
  const fallbackPrometheusBaseUrl = metaMonitoringUrl('monitoring_prometheus_url');

  try {
    const res = await fetch('/ops/status', { credentials: 'include' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();

    const grafanaOk = data.grafana && data.grafana.up;
    const promOk = data.prometheus && data.prometheus.up;

    const grafanaDot = document.getElementById('grafana-dot');
    const promDot = document.getElementById('prometheus-dot');
    if (grafanaDot) grafanaDot.style.background = grafanaOk ? '#39c07d' : '#e07b7b';
    if (promDot) promDot.style.background = promOk ? '#39c07d' : '#e07b7b';

    const gStatus = document.getElementById('grafana-status');
    const pStatus = document.getElementById('prometheus-status');
    const gUrl = document.getElementById('grafana-url');
    const pUrl = document.getElementById('prometheus-url');
    if (gStatus) {
      gStatus.textContent = grafanaOk ? 'OK' : 'CAIDO';
      gStatus.className = grafanaOk ? 'status-up' : 'status-down';
    }
    if (pStatus) {
      pStatus.textContent = promOk ? 'OK' : 'CAIDO';
      pStatus.className = promOk ? 'status-up' : 'status-down';
    }
    const grafanaBaseUrl = data.grafana?.baseUrl || fallbackGrafanaBaseUrl;
    const prometheusBaseUrl = data.prometheus?.baseUrl || fallbackPrometheusBaseUrl;
    if (gUrl) gUrl.textContent = grafanaBaseUrl || '-';
    if (pUrl) pUrl.textContent = prometheusBaseUrl || '-';

    applyServiceLink('sidebarGrafanaLink', grafanaBaseUrl, false);
    applyServiceLink('sidebarPrometheusLink', prometheusBaseUrl, false);
    applyServiceLink('mobileGrafanaLink', grafanaBaseUrl, false);
    applyServiceLink('mobilePrometheusLink', prometheusBaseUrl, false);
    applyServiceLink('opsGrafanaLink', grafanaBaseUrl, true);
    applyServiceLink('opsPrometheusLink', prometheusBaseUrl, true);
  } catch (e) {
    const grafanaDot = document.getElementById('grafana-dot');
    const promDot = document.getElementById('prometheus-dot');
    if (grafanaDot) grafanaDot.style.background = '#e07b7b';
    if (promDot) promDot.style.background = '#e07b7b';

    const gStatus = document.getElementById('grafana-status');
    const pStatus = document.getElementById('prometheus-status');
    const gUrl = document.getElementById('grafana-url');
    const pUrl = document.getElementById('prometheus-url');
    if (gStatus) {
      gStatus.textContent = 'NO DISPONIBLE';
      gStatus.className = 'status-down';
    }
    if (pStatus) {
      pStatus.textContent = 'NO DISPONIBLE';
      pStatus.className = 'status-down';
    }
    if (gUrl) gUrl.textContent = fallbackGrafanaBaseUrl || '-';
    if (pUrl) pUrl.textContent = fallbackPrometheusBaseUrl || '-';

    applyServiceLink('sidebarGrafanaLink', fallbackGrafanaBaseUrl, false);
    applyServiceLink('sidebarPrometheusLink', fallbackPrometheusBaseUrl, false);
    applyServiceLink('mobileGrafanaLink', fallbackGrafanaBaseUrl, false);
    applyServiceLink('mobilePrometheusLink', fallbackPrometheusBaseUrl, false);
    applyServiceLink('opsGrafanaLink', fallbackGrafanaBaseUrl, true);
    applyServiceLink('opsPrometheusLink', fallbackPrometheusBaseUrl, true);
  }
}

async function loadRagContextStats() {
  if (!ragContextPillEl && !ragTotalContextEl) return;
  try {
    const res = await fetch('/api/rag/stats', { headers: withCsrf() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    renderRagContextStats(data || {});
  } catch (e) {
    renderRagContextError('error');
  }
}

function scheduleMonitorPolling() {
  const ms = getMonitorIntervalMs();
  if (monitorTimer) clearInterval(monitorTimer);
  monitorTimer = setInterval(() => {
    loadMonitorEvents();
    loadOpsStatus();
    loadMonitorStackStatus();
    loadRagContextStats();
    loadRagDecisionMetrics();
  }, ms);
}

function applyMonitorInterval() {
  if (!monitorIntervalEl) return;
  const sec = parseInt(monitorIntervalEl.value, 10);
  if (!Number.isFinite(sec)) return;
  const clamped = Math.max(MONITOR_MIN_SEC, Math.min(MONITOR_MAX_SEC, sec));
  setMonitorIntervalMs(clamped * 1000);
  syncMonitorIntervalUI();
  scheduleMonitorPolling();
  loadMonitorEvents();
  loadOpsStatus();
  loadMonitorStackStatus();
  loadRagContextStats();
  loadRagDecisionMetrics();
}

async function ensureActiveSession() {
  // El backend decide cual es la sesion activa real del usuario
  const res = await fetch('/api/chat/active', { headers: withCsrf() });
  if (!res.ok) throw new Error(`No se pudo obtener sesion activa: ${res.status}`);
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
        <p>Inicia una conversacion...</p>
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

      const title = s.title || 'Chat sin titulo';
      const subtitle = (s.lastActivityAt || s.createdAt || '').toString();

      item.innerHTML = `
        <div class="flex items-center justify-between gap-2">
          <div class="min-w-0">
            <div class="text-sm font-semibold truncate">${title}</div>
            <div class="text-[10px] text-slate-500 truncate">${s.id.substring(0, 8)} - ${subtitle}</div>
          </div>
          <div class="flex items-center gap-1 shrink-0">
            <button class="rename px-2 py-1 text-[10px] rounded-lg border border-slate-700/50 hover:border-blue-500/40">Renombrar</button>
            <button class="delete px-2 py-1 text-[10px] rounded-lg border border-red-700/50 text-red-300 hover:border-red-500/60">Eliminar</button>
          </div>
        </div>
      `;

      // activar chat
      item.addEventListener('click', async () => {
        if (s.id === sessionId) return;
        await activateSession(s.id);
      });

      // renombrar: boton + doble click
      item.querySelector('.rename').addEventListener('click', async (e) => {
        e.stopPropagation();
        await renameSessionPrompt(s.id, title);
      });

      item.querySelector('.delete').addEventListener('click', async (e) => {
        e.stopPropagation();
        try {
          await deleteSessionPrompt(s.id, title);
        } catch (err) {
          alert(err.message || 'No se pudo eliminar el chat');
        }
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

  // cerrar drawer movil
  hideDrawer();
}

async function createNewChat() {
  // Si tu backend usa otra ruta, cambiala. Ej: POST /api/chat/sessions
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
      <p>Inicia una conversacion...</p>
    </div>
  `;

  if (inputEl) {
    inputEl.value = '';
    syncComposerHeight();
  }

  await loadSessions();
  hideDrawer();
}

async function handleExternalApiKeySession(newSessionId) {
  // Aisla el chat cuando se genera una API key externa.
  if (newSessionId) {
    await activateSession(newSessionId);
    return;
  }

  await createNewChat();
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

async function ensureSessionAfterDelete(deletedId) {
  const deletedWasActive = deletedId === sessionId;

  await loadSessions();
  if (!deletedWasActive) return;

  if (sessionsCache.length > 0) {
    await activateSession(sessionsCache[0].id);
    return;
  }

  await ensureActiveSession();
  await loadSessions();
  await loadHistory(sessionId);
}

async function deleteSessionPrompt(id, currentTitle) {
  const label = (currentTitle || '').trim() || 'este chat';
  const ok = confirm(`Eliminar "${label}"? Esta accion no se puede deshacer.`);
  if (!ok) return;

  const res = await fetch(`/api/chat/sessions/${id}`, {
    method: 'DELETE',
    headers: withCsrf({ 'Content-Type': 'application/json' })
  });
  if (!res.ok) throw new Error(await res.text());

  await ensureSessionAfterDelete(id);
  hideDrawer();
}

async function send() {
  let text = inputEl.value.trim();
  const selectedModel = modelSelectEl?.value || null;
  if (!text && pendingMedia.length > 0) {
    text = selectedModel && (selectedModel === 'image' || selectedModel === 'image-hq' || selectedModel.endsWith('.safetensors'))
      ? 'Genera una nueva version basada en esta imagen.'
      : 'Analiza el adjunto y responde a mi consulta.';
  }
  if (!text) return;
  closeComposerMenu();
  closeMobileNavMenu();
  hideOps();

  const mediaPayload = pendingMedia.map(m => ({
    name: m.name,
    mimeType: m.mimeType,
    base64: m.base64 || null,
    text: m.text || null
  }));

  inputEl.value = '';
  syncComposerHeight();
  addMsg('user', text, [], null, mediaPayload);

  const typingId = showTyping();
  sendBtn.disabled = true;

  try {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: withCsrf({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        sessionId,
        message: text,
        model: selectedModel,
        media: mediaPayload
      })
    });

    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();

    const payload = await readResponsePayload(res);
    if (!res.ok) throw new Error(extractErrorMessage(payload, res));

    const data = payload;
    sessionId = data.sessionId;
    setSidLabel(sessionId);

    const reply = (data?.reply || '').toString();
    const responseMeta = {
      safe: data?.safe !== false,
      confidence: Number(data?.confidence || 0),
      groundedSources: Number(data?.groundedSources || 0),
      ragUsed: data?.ragUsed === true,
      ragNeeded: data?.ragNeeded === true,
      reasoningLevel: String(data?.reasoningLevel || 'MEDIUM')
    };

    addMsg('assistant', reply, data.sources, responseMeta);
    clearMediaSelection();

    // refresca lista para ordenar por ultima actividad + contador
    await loadSessions();
  } catch (e) {
    const typingEl = document.getElementById(typingId);
    if (typingEl) typingEl.remove();
    addMsg('assistant', 'Error: ' + (e.message || 'desconocido'));
  } finally {
    sendBtn.disabled = false;
    syncComposerHeight();
    inputEl.focus();
  }
}

// Drawer movil: bloquea el fondo y permite cerrar al volver a escritorio.
function setDrawerOpen(open) {
  if (!sessionDrawer) return;
  const isOpen = !sessionDrawer.classList.contains('hidden');
  if (isOpen === open) return;

  const updateState = () => {
    sessionDrawer.classList.toggle('hidden', !open);
    sessionDrawer.setAttribute('aria-hidden', open ? 'false' : 'true');
    document.body.classList.toggle('drawer-open', open);
  };

  if (typeof document.startViewTransition === 'function') {
    document.startViewTransition(updateState);
    return;
  }

  updateState();
}

/* Drawer mobile */
function showDrawer() {
  setDrawerOpen(true);
}
function hideDrawer() {
  setDrawerOpen(false);
}

function setOpsOpen(open) {
  if (!opsPanelDetailsEl) return;
  if (!mobileViewportQuery.matches) {
    document.body.classList.remove('ops-open');
    opsPanelEl?.classList.remove('is-open');
    opsPanelEl?.setAttribute('aria-hidden', 'false');
    opsPanelDetailsEl.open = true;
    return;
  }
  document.body.classList.toggle('ops-open', open);
  opsPanelEl?.classList.toggle('is-open', open);
  opsPanelEl?.setAttribute('aria-hidden', open ? 'false' : 'true');
  opsPanelDetailsEl.open = open;
}

function showOps() {
  setOpsOpen(true);
}

function hideOps() {
  setOpsOpen(false);
}

/* Listeners */
sendBtn?.addEventListener('click', send);
inputEl?.addEventListener('input', () => syncComposerHeight());
inputEl?.addEventListener('focus', () => {
  closeComposerMenu();
  closeMobileNavMenu();
  hideOps();
  syncComposerHeight();
  if (window.innerWidth <= 760) {
    setTimeout(() => inputEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' }), 180);
  }
});
inputEl?.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    send();
  }
});
attachBtnEl?.addEventListener('click', () => {
  closeComposerMenu();
  mediaInputEl?.click();
});
cameraBtnEl?.addEventListener('click', () => {
  closeComposerMenu();
  cameraInputEl?.click();
});
clearMediaBtnEl?.addEventListener('click', () => {
  clearMediaSelection();
  closeComposerMenu();
});
mediaInputEl?.addEventListener('change', async (e) => {
  await handlePickedFiles(e.target.files);
  e.target.value = '';
});
cameraInputEl?.addEventListener('change', async (e) => {
  await handlePickedFiles(e.target.files);
  e.target.value = '';
});

newChatBtn?.addEventListener('click', async () => {
  hideOps();
  try { await createNewChat(); } catch (e) { alert(e.message); }
});

openApiKeysInline?.addEventListener('click', () => {
  const btn = document.getElementById('openApiKeys');
  if (btn) btn.click();
});
mobileOpenApiKeysEl?.addEventListener('click', () => {
  closeMobileNavMenu();
  const btn = document.getElementById('openApiKeys');
  if (btn) btn.click();
});
mobileNavMenuEl?.querySelectorAll('a').forEach((link) => {
  link.addEventListener('click', () => closeMobileNavMenu());
});
toggleOpsBtn?.addEventListener('click', () => {
  hideDrawer();
  closeMobileNavMenu();
  closeComposerMenu();
  showOps();
});
closeOpsBtn?.addEventListener('click', (event) => {
  event.preventDefault();
  event.stopPropagation();
  hideOps();
});
opsBackdropEl?.addEventListener('click', () => hideOps());
opsSummaryEl?.addEventListener('click', (event) => {
  if (!mobileViewportQuery.matches) return;
  if (event.target instanceof Element && event.target.closest('#closeOps')) {
    return;
  }
  event.preventDefault();
});

monitorApplyBtn?.addEventListener('click', () => applyMonitorInterval());
monitorStackProvisionBtn?.addEventListener('click', () => activateMonitorStack());
monitorStackRefreshBtn?.addEventListener('click', () => loadMonitorStackStatus());
triggerMonitorProvisionSidebarBtn?.addEventListener('click', () => triggerMonitorProvisionShortcut());
triggerMonitorProvisionMobileBtn?.addEventListener('click', () => triggerMonitorProvisionShortcut());
triggerMonitorProvisionTopBtn?.addEventListener('click', () => triggerMonitorProvisionShortcut());
ragRefreshBtn?.addEventListener('click', () => loadRagContextStats());
ragDecisionRefreshBtn?.addEventListener('click', () => loadRagDecisionMetrics());
monitorIntervalEl?.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') applyMonitorInterval();
});

window.addEventListener('api-key-created', async (event) => {
  try {
    await handleExternalApiKeySession(event?.detail?.sessionId);
  } catch (e) {
    console.error('No se pudo crear el chat separado para API key:', e);
  }
});

window.addEventListener('storage', (e) => {
  if (e.key === MONITOR_INTERVAL_KEY) {
    syncMonitorIntervalUI();
    scheduleMonitorPolling();
  }
});

window.addEventListener('resize', () => {
  syncViewportHeight();
  syncComposerHeight();
  closeComposerMenu();
  closeMobileNavMenu();
  if (!mobileViewportQuery.matches) {
    hideDrawer();
    setOpsOpen(false);
  }
});

window.addEventListener('orientationchange', () => {
  syncViewportHeight();
  syncKeyboardState();
  syncComposerHeight();
  closeMobileNavMenu();
  hideOps();
});

window.visualViewport?.addEventListener('resize', () => {
  syncViewportHeight();
  syncKeyboardState();
});
mobileViewportQuery.addEventListener?.('change', (event) => {
  if (!event.matches) {
    hideDrawer();
  }
  closeComposerMenu();
  closeMobileNavMenu();
  hideOps();
  syncKeyboardState();
  setOpsOpen(false);
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    closeComposerMenu();
    closeMobileNavMenu();
    hideOps();
    hideDrawer();
  }
});

document.addEventListener('click', (event) => {
  if (!(event.target instanceof Element)) return;
  if (composerMenuEl?.open && !composerMenuEl.contains(event.target)) {
    closeComposerMenu();
  }
  if (mobileNavMenuEl?.open && !mobileNavMenuEl.contains(event.target)) {
    closeMobileNavMenu();
  }
});

toggleSessionsBtn?.addEventListener('click', () => {
  closeMobileNavMenu();
  hideOps();
  showDrawer();
});
drawerBackdrop?.addEventListener('click', () => {
  hideDrawer();
  closeMobileNavMenu();
  hideOps();
});
closeDrawerBtn?.addEventListener('click', () => {
  hideDrawer();
  closeMobileNavMenu();
  hideOps();
});

sessionSearchEl?.addEventListener('input', () => renderSessions(sessionSearchEl.value));
sessionSearchMobileEl?.addEventListener('input', () => renderSessions(sessionSearchMobileEl.value));

(async function init() {
  try {
    syncViewportHeight();
    syncComposerHeight();
    setOpsOpen(false);
    loadModelSelection();
    syncMonitorIntervalUI();
    loadOpsStatus();
    loadMonitorEvents();
    loadMonitorStackStatus();
    setRagContextLoading();
    loadRagContextStats();
    renderRagDecisionLoading();
    loadRagDecisionMetrics();
    renderMediaPreview();
    scheduleMonitorPolling();
    await ensureActiveSession();
    await loadSessions();
    await loadHistory(sessionId);
  } catch (e) {
    console.error(e);
    addMsg('assistant', 'Error: No se pudo inicializar el chat: ' + (e.message || 'error'));
  }
})();



