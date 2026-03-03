const ragRobotStateEl = document.getElementById('ragRobotState');
const ragRobotDotEl = document.getElementById('ragRobotDot');
const ragRobotStateLabelEl = document.getElementById('ragRobotStateLabel');
const ragRobotModeEl = document.getElementById('ragRobotMode');
const ragRobotIntervalEl = document.getElementById('ragRobotInterval');
const ragRobotNextRunEl = document.getElementById('ragRobotNextRun');
const ragRobotCurrentEl = document.getElementById('ragRobotCurrent');
const ragRobotCorpusEl = document.getElementById('ragRobotCorpus');
const ragRobotStorageEl = document.getElementById('ragRobotStorage');
const ragRobotLastRunEl = document.getElementById('ragRobotLastRun');
const ragRobotSummaryEl = document.getElementById('ragRobotSummary');
const ragRobotFreedEl = document.getElementById('ragRobotFreed');
const ragRobotEventsEl = document.getElementById('ragRobotEvents');
const ragRobotDryRunEl = document.getElementById('ragRobotDryRun');
const ragRobotIntervalInputEl = document.getElementById('ragRobotIntervalInput');
const ragRobotRunBtn = document.getElementById('btnRagRobotRun');
const ragRobotPauseBtn = document.getElementById('btnRagRobotPause');
const ragRobotResumeBtn = document.getElementById('btnRagRobotResume');
const ragRobotApplyBtn = document.getElementById('btnRagRobotApply');
const ragCaseListEl = document.getElementById('ragCaseList');
const ragCasesRefreshBtn = document.getElementById('btnRagCasesRefresh');

if (ragRobotStateEl) {
  let ragRobotBusy = false;
  let ragCaseBusyId = null;

  function getCsrfToken() {
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    return { token, header };
  }

  function withCsrf(headers = {}) {
    const { token, header } = getCsrfToken();
    if (token && header) headers[header] = token;
    return headers;
  }

  function formatDateTime(value) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    return date.toLocaleString();
  }

  function formatBytes(bytes) {
    const value = Number(bytes || 0);
    if (!Number.isFinite(value) || value <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    let current = value;
    let unitIdx = 0;
    while (current >= 1024 && unitIdx < units.length - 1) {
      current /= 1024;
      unitIdx += 1;
    }
    const digits = current >= 10 || unitIdx === 0 ? 0 : 1;
    return `${current.toFixed(digits)} ${units[unitIdx]}`;
  }

  function setBusy(nextBusy) {
    ragRobotBusy = nextBusy;
    [ragRobotRunBtn, ragRobotPauseBtn, ragRobotResumeBtn, ragRobotApplyBtn, ragCasesRefreshBtn].forEach(btn => {
      if (btn) btn.disabled = nextBusy;
    });
  }

  function stateConfig(data) {
    if (!data?.schedulerEnabled) return { label: 'Desactivado', dot: '#94a3b8' };
    if (data.running) return { label: 'En ejecucion', dot: '#38bdf8' };
    if (data.paused) return { label: 'Pausado', dot: '#f59e0b' };
    if (data.dryRun) return { label: 'Solo analisis', dot: '#60a5fa' };
    return { label: 'Activo', dot: '#39c07d' };
  }

  function eventBadgeClass(level) {
    const normalized = String(level || 'INFO').toUpperCase();
    return normalized === 'ERROR' || normalized === 'WARN' ? 'alert' : 'recover';
  }

  function renderEvents(events) {
    if (!ragRobotEventsEl) return;
    if (!Array.isArray(events) || events.length === 0) {
      ragRobotEventsEl.innerHTML = `
        <div class="event-item">
          <div class="event-head">
            <span>Sin actividad</span>
            <span class="event-badge recover">IDLE</span>
          </div>
          <div class="sub">Esperando eventos del robot.</div>
        </div>
      `;
      return;
    }

    ragRobotEventsEl.innerHTML = '';
    events.forEach(event => {
      const level = String(event.level || 'INFO').toUpperCase();
      const item = document.createElement('div');
      item.className = 'event-item';
      item.innerHTML = `
        <div class="event-head">
          <span>${event.title || event.type || 'Evento'}</span>
          <span class="event-badge ${eventBadgeClass(level)}">${level}</span>
        </div>
        <div class="sub">${event.message || '-'}</div>
        <div class="sub">${formatDateTime(event.timestamp)}</div>
      `;
      ragRobotEventsEl.appendChild(item);
    });
  }

  function renderStatus(data) {
    const state = stateConfig(data || {});
    if (ragRobotStateLabelEl) ragRobotStateLabelEl.textContent = state.label;
    if (ragRobotDotEl) ragRobotDotEl.style.background = state.dot;
    if (ragRobotModeEl) {
      ragRobotModeEl.textContent = data?.dryRun ? 'Solo analisis' : 'Limpieza real';
      if (data?.paused) ragRobotModeEl.textContent += ' (pausado)';
    }
    if (ragRobotIntervalEl) ragRobotIntervalEl.textContent = `${Math.round((data?.intervalMs || 0) / 1000)} s`;
    if (ragRobotNextRunEl) ragRobotNextRunEl.textContent = data?.running ? 'En curso' : formatDateTime(data?.nextRunAt);
    if (ragRobotCurrentEl) {
      const current = data?.currentDocumentTitle
        ? `${data.currentStep || 'Analizando'}: ${data.currentDocumentTitle}`
        : (data?.currentStep || 'Idle');
      ragRobotCurrentEl.textContent = current;
    }
    if (ragRobotCorpusEl) {
      const corpus = data?.corpus || {};
      ragRobotCorpusEl.textContent = `${corpus.totalDocuments || 0} docs / ${corpus.totalChunks || 0} chunks`;
    }
    if (ragRobotStorageEl) ragRobotStorageEl.textContent = formatBytes(data?.corpus?.totalBytes || 0);
    if (ragRobotLastRunEl) ragRobotLastRunEl.textContent = formatDateTime(data?.lastRun?.completedAt);
    if (ragRobotSummaryEl) ragRobotSummaryEl.textContent = data?.lastRun?.summary || 'Sin barridos todavia.';
    if (ragRobotFreedEl) ragRobotFreedEl.textContent = formatBytes(data?.lastRun?.estimatedBytesFreed || 0);
    if (ragRobotDryRunEl) ragRobotDryRunEl.checked = data?.dryRun === true;
    if (ragRobotIntervalInputEl && document.activeElement !== ragRobotIntervalInputEl) {
      ragRobotIntervalInputEl.value = String(Math.round((data?.intervalMs || 0) / 1000));
    }
    if (ragRobotPauseBtn) ragRobotPauseBtn.disabled = ragRobotBusy || data?.paused === true || data?.schedulerEnabled === false;
    if (ragRobotResumeBtn) ragRobotResumeBtn.disabled = ragRobotBusy || data?.paused !== true || data?.schedulerEnabled === false;
    if (ragRobotRunBtn) ragRobotRunBtn.disabled = ragRobotBusy || data?.running === true;
    renderEvents(data?.recentEvents);
  }

  function caseSeverityColor(severity) {
    return String(severity || '').toUpperCase() === 'CRITICAL' ? 'alert' : 'recover';
  }

  function decisionButtons(item) {
    const status = String(item.status || '').toUpperCase();
    if (status === 'EXECUTED' || status === 'RESOLVED') return '';

    const disable = ragCaseBusyId === item.id ? 'disabled' : '';
    return `
      <div style="display:flex; gap:6px; flex-wrap:wrap; margin-top:8px;">
        <button class="btn rag-case-action" data-case-id="${item.id}" data-action="KEEP" ${disable}>Mantener</button>
        <button class="btn rag-case-action" data-case-id="${item.id}" data-action="RESTRUCTURE" ${disable}>Reestructurar</button>
        <button class="btn rag-case-action" data-case-id="${item.id}" data-action="DELETE" ${disable}>Eliminar</button>
      </div>
    `;
  }

  function renderCases(cases) {
    if (!ragCaseListEl) return;
    if (!Array.isArray(cases) || cases.length === 0) {
      ragCaseListEl.innerHTML = `
        <div class="event-item">
          <div class="event-head">
            <span>Sin casos</span>
            <span class="event-badge recover">OK</span>
          </div>
          <div class="sub">Todavia no hay decisiones pendientes.</div>
        </div>
      `;
      return;
    }

    ragCaseListEl.innerHTML = '';
    cases.forEach(item => {
      const event = document.createElement('div');
      event.className = 'event-item';
      event.innerHTML = `
        <div class="event-head">
          <span>${item.documentTitle || 'Documento'}</span>
          <span class="event-badge ${caseSeverityColor(item.severity)}">${item.severity || 'CASE'}</span>
        </div>
        <div class="sub"><strong>${item.issueType || '-'}</strong> · estado ${item.status || '-'}</div>
        <div class="sub">${item.summary || '-'}</div>
        <div class="sub">Uso: ${item.usageCount || 0} · Ultimo uso: ${formatDateTime(item.lastUsedAt)}</div>
        <div class="sub">Admin hasta: ${formatDateTime(item.adminDueAt)} · Auto: ${formatDateTime(item.autoApplyAt)}</div>
        <div class="sub">IA: ${(item.aiSuggestedAction || '-')}${item.aiReason ? ` · ${item.aiReason}` : ''}</div>
        ${item.proposedContent ? `<details style="margin-top:8px;"><summary class="sub" style="cursor:pointer;">Ver propuesta</summary><pre class="sub" style="white-space:pre-wrap; margin-top:6px;">${escapeHtml(item.proposedContent)}</pre></details>` : ''}
        ${decisionButtons(item)}
      `;
      ragCaseListEl.appendChild(event);
    });

    ragCaseListEl.querySelectorAll('.rag-case-action').forEach(btn => {
      btn.addEventListener('click', async () => {
        const caseId = btn.getAttribute('data-case-id');
        const action = btn.getAttribute('data-action');
        await postCaseDecision(caseId, action);
      });
    });
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

  async function fetchStatus() {
    try {
      const res = await fetch('/api/rag/maintenance/status', { credentials: 'same-origin' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      renderStatus(data);
    } catch (error) {
      if (ragRobotSummaryEl) ragRobotSummaryEl.textContent = `Error cargando robot: ${error.message || 'error'}`;
    }
  }

  async function fetchCases() {
    if (!ragCaseListEl) return;
    try {
      const res = await fetch('/api/rag/maintenance/cases', { credentials: 'same-origin' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      renderCases(data);
    } catch (error) {
      ragCaseListEl.innerHTML = `
        <div class="event-item">
          <div class="event-head">
            <span>Error</span>
            <span class="event-badge alert">ALERT</span>
          </div>
          <div class="sub">No se pudo cargar la cola de decisiones: ${error.message || 'error'}</div>
        </div>
      `;
    }
  }

  async function postAction(url, body) {
    setBusy(true);
    try {
      const res = await fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        headers: withCsrf({ 'Content-Type': 'application/json' }),
        body: body == null ? null : JSON.stringify(body)
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      renderStatus(data);
      await fetchCases();
    } catch (error) {
      if (ragRobotSummaryEl) ragRobotSummaryEl.textContent = `Error accionando robot: ${error.message || 'error'}`;
    } finally {
      setBusy(false);
    }
  }

  async function postCaseDecision(caseId, action) {
    ragCaseBusyId = Number(caseId);
    try {
      const res = await fetch(`/api/rag/maintenance/cases/${caseId}/decision`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: withCsrf({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ action })
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      await fetchStatus();
      await fetchCases();
    } catch (error) {
      if (ragRobotSummaryEl) ragRobotSummaryEl.textContent = `Error aplicando decision: ${error.message || 'error'}`;
    } finally {
      ragCaseBusyId = null;
    }
  }

  ragRobotRunBtn?.addEventListener('click', () => postAction('/api/rag/maintenance/run', {}));
  ragRobotPauseBtn?.addEventListener('click', () => postAction('/api/rag/maintenance/pause', {}));
  ragRobotResumeBtn?.addEventListener('click', () => postAction('/api/rag/maintenance/resume', {}));
  ragRobotApplyBtn?.addEventListener('click', () => {
    const intervalSeconds = parseInt(ragRobotIntervalInputEl?.value || '0', 10);
    postAction('/api/rag/maintenance/config', {
      dryRun: ragRobotDryRunEl?.checked === true,
      intervalSeconds: Number.isFinite(intervalSeconds) ? intervalSeconds : null
    });
  });
  ragRobotIntervalInputEl?.addEventListener('keydown', event => {
    if (event.key === 'Enter') ragRobotApplyBtn?.click();
  });
  ragCasesRefreshBtn?.addEventListener('click', () => fetchCases());

  fetchStatus();
  fetchCases();
  setInterval(() => {
    fetchStatus();
    fetchCases();
  }, 10000);
}
