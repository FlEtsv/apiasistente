const ragOpsArchitectureEl = document.getElementById('ragOpsArchitecture');
const ragOpsIndexLocationEl = document.getElementById('ragOpsIndexLocation');
const ragOpsDocumentsEl = document.getElementById('ragOpsDocuments');
const ragOpsChunksEl = document.getElementById('ragOpsChunks');
const ragOpsVectorsEl = document.getElementById('ragOpsVectors');
const ragOpsFailuresEl = document.getElementById('ragOpsFailures');
const ragOpsMetadataBytesEl = document.getElementById('ragOpsMetadataBytes');
const ragOpsChunkBytesEl = document.getElementById('ragOpsChunkBytes');
const ragOpsEmbeddingBytesEl = document.getElementById('ragOpsEmbeddingBytes');
const ragOpsIndexBytesEl = document.getElementById('ragOpsIndexBytes');
const ragOpsTotalBytesEl = document.getElementById('ragOpsTotalBytes');
const ragOpsTopKEl = document.getElementById('ragOpsTopK');
const ragOpsChunkConfigEl = document.getElementById('ragOpsChunkConfig');
const ragOpsContextConfigEl = document.getElementById('ragOpsContextConfig');
const ragOpsRerankConfigEl = document.getElementById('ragOpsRerankConfig');
const ragOpsEvidenceThresholdEl = document.getElementById('ragOpsEvidenceThreshold');
const ragOpsCorpusUpdatedEl = document.getElementById('ragOpsCorpusUpdated');
const ragOpsIngestSummaryEl = document.getElementById('ragOpsIngestSummary');
const ragOpsRetrievalSummaryEl = document.getElementById('ragOpsRetrievalSummary');
const ragOpsDeleteSummaryEl = document.getElementById('ragOpsDeleteSummary');
const ragOpsIndexSummaryEl = document.getElementById('ragOpsIndexSummary');
const ragOpsIngestCountEl = document.getElementById('ragOpsIngestCount');
const ragOpsRetrievalCountEl = document.getElementById('ragOpsRetrievalCount');
const ragOpsDeletedCountEl = document.getElementById('ragOpsDeletedCount');
const ragOpsPrunedCountEl = document.getElementById('ragOpsPrunedCount');
const ragOpsIndexWriteCountEl = document.getElementById('ragOpsIndexWriteCount');
const ragOpsIndexDeleteCountEl = document.getElementById('ragOpsIndexDeleteCount');
const ragOpsIndexRebuildCountEl = document.getElementById('ragOpsIndexRebuildCount');
const ragOpsUpdatedAtEl = document.getElementById('ragOpsUpdatedAt');
const ragOpsEventsEl = document.getElementById('ragOpsEvents');
const ragOpsRefreshBtn = document.getElementById('btnRefreshRagOps');
const ragOpsRebuildBtn = document.getElementById('btnRebuildRagIndex');
const ragOpsClearBtn = document.getElementById('btnClearRagOpsLog');
const ragOpsPurgeCountInput = document.getElementById('ragOpsPurgeCountInput');
const ragOpsPurgeOldestBtn = document.getElementById('btnPurgeRagOldest');

if (ragOpsArchitectureEl) {
  let ragOpsBusy = false;
  let ragOpsTimer = null;

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

  function formatDecimal(value) {
    const num = Number(value);
    if (!Number.isFinite(num)) return '-';
    return num.toFixed(2);
  }

  function setBusy(nextBusy) {
    ragOpsBusy = nextBusy;
    [ragOpsRefreshBtn, ragOpsRebuildBtn, ragOpsClearBtn, ragOpsPurgeOldestBtn].forEach(btn => {
      if (btn) btn.disabled = nextBusy;
    });
    if (ragOpsPurgeCountInput) ragOpsPurgeCountInput.disabled = nextBusy;
  }

  function eventBadgeClass(level) {
    const normalized = String(level || 'INFO').toUpperCase();
    return normalized === 'ERROR' || normalized === 'WARN' ? 'alert' : 'recover';
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

  function renderEvents(events) {
    if (!ragOpsEventsEl) return;
    if (!Array.isArray(events) || events.length === 0) {
      ragOpsEventsEl.innerHTML = `
        <div class="event-item">
          <div class="event-head">
            <span>Sin eventos</span>
            <span class="event-badge recover">IDLE</span>
          </div>
          <div class="sub">Esperando actividad del core RAG.</div>
        </div>
      `;
      return;
    }

    ragOpsEventsEl.innerHTML = '';
    events.forEach(event => {
      const item = document.createElement('div');
      item.className = 'event-item';
      item.innerHTML = `
        <div class="event-head">
          <span>${escapeHtml(event.summary || event.type || 'Evento')}</span>
          <span class="event-badge ${eventBadgeClass(event.level)}">${escapeHtml(event.level || 'INFO')}</span>
        </div>
        <div class="sub">${escapeHtml(event.detail || '-')}</div>
        <div class="sub">${formatDateTime(event.at)}</div>
      `;
      ragOpsEventsEl.appendChild(item);
    });
  }

  function renderStatus(data) {
    if (ragOpsArchitectureEl) ragOpsArchitectureEl.textContent = data?.architecture || '-';
    if (ragOpsIndexLocationEl) ragOpsIndexLocationEl.textContent = data?.indexLocation || '-';
    if (ragOpsDocumentsEl) ragOpsDocumentsEl.textContent = String(data?.activeDocuments ?? 0);
    if (ragOpsChunksEl) ragOpsChunksEl.textContent = String(data?.activeChunks ?? 0);
    if (ragOpsVectorsEl) ragOpsVectorsEl.textContent = String(data?.activeVectors ?? 0);
    if (ragOpsFailuresEl) ragOpsFailuresEl.textContent = String(data?.failures ?? 0);
    if (ragOpsMetadataBytesEl) ragOpsMetadataBytesEl.textContent = formatBytes(data?.metadataBytes);
    if (ragOpsChunkBytesEl) ragOpsChunkBytesEl.textContent = formatBytes(data?.chunkTextBytes);
    if (ragOpsEmbeddingBytesEl) ragOpsEmbeddingBytesEl.textContent = formatBytes(data?.embeddingBytes);
    if (ragOpsIndexBytesEl) ragOpsIndexBytesEl.textContent = formatBytes(data?.indexBytes);
    if (ragOpsTotalBytesEl) ragOpsTotalBytesEl.textContent = formatBytes(data?.totalBytes);
    if (ragOpsTopKEl) ragOpsTopKEl.textContent = String(data?.topK ?? '-');
    if (ragOpsChunkConfigEl) ragOpsChunkConfigEl.textContent = `${data?.chunkSize ?? '-'} (+${data?.chunkOverlap ?? '-'})`;
    if (ragOpsContextConfigEl) ragOpsContextConfigEl.textContent = String(data?.contextMaxChunks ?? '-');
    if (ragOpsRerankConfigEl) ragOpsRerankConfigEl.textContent = String(data?.rerankCandidates ?? '-');
    if (ragOpsEvidenceThresholdEl) ragOpsEvidenceThresholdEl.textContent = formatDecimal(data?.evidenceThreshold);
    if (ragOpsCorpusUpdatedEl) ragOpsCorpusUpdatedEl.textContent = formatDateTime(data?.lastCorpusUpdateAt);
    if (ragOpsIngestSummaryEl) ragOpsIngestSummaryEl.textContent = data?.lastIngestSummary || '-';
    if (ragOpsRetrievalSummaryEl) ragOpsRetrievalSummaryEl.textContent = data?.lastRetrievalSummary || '-';
    if (ragOpsDeleteSummaryEl) ragOpsDeleteSummaryEl.textContent = data?.lastDeleteSummary || '-';
    if (ragOpsIndexSummaryEl) ragOpsIndexSummaryEl.textContent = data?.lastIndexSummary || '-';
    if (ragOpsIngestCountEl) ragOpsIngestCountEl.textContent = String(data?.ingestOperations ?? 0);
    if (ragOpsRetrievalCountEl) ragOpsRetrievalCountEl.textContent = String(data?.retrievalOperations ?? 0);
    if (ragOpsDeletedCountEl) ragOpsDeletedCountEl.textContent = String(data?.deletedDocuments ?? 0);
    if (ragOpsPrunedCountEl) ragOpsPrunedCountEl.textContent = String(data?.prunedChunks ?? 0);
    if (ragOpsIndexWriteCountEl) ragOpsIndexWriteCountEl.textContent = String(data?.indexWrites ?? 0);
    if (ragOpsIndexDeleteCountEl) ragOpsIndexDeleteCountEl.textContent = String(data?.indexDeletes ?? 0);
    if (ragOpsIndexRebuildCountEl) ragOpsIndexRebuildCountEl.textContent = String(data?.indexRebuilds ?? 0);
    if (ragOpsUpdatedAtEl) ragOpsUpdatedAtEl.textContent = formatDateTime(data?.updatedAt);
    renderEvents(data?.recentEvents);
  }

  async function fetchStatus() {
    try {
      const res = await fetch('/api/rag/ops/status', { credentials: 'same-origin' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      renderStatus(data);
    } catch (error) {
      if (ragOpsUpdatedAtEl) ragOpsUpdatedAtEl.textContent = `Error: ${error.message || 'error'}`;
      renderEvents([]);
    }
  }

  async function postAction(url) {
    setBusy(true);
    try {
      const res = await fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        headers: withCsrf({ 'Content-Type': 'application/json' })
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      renderStatus(data);
    } catch (error) {
      if (ragOpsUpdatedAtEl) ragOpsUpdatedAtEl.textContent = `Error: ${error.message || 'error'}`;
    } finally {
      setBusy(false);
    }
  }

  ragOpsRefreshBtn?.addEventListener('click', () => fetchStatus());
  ragOpsRebuildBtn?.addEventListener('click', () => postAction('/api/rag/ops/index/rebuild'));
  ragOpsClearBtn?.addEventListener('click', () => postAction('/api/rag/ops/logs/clear'));
  ragOpsPurgeOldestBtn?.addEventListener('click', () => {
    const requested = parseInt(ragOpsPurgeCountInput?.value || '25', 10);
    const safeCount = Number.isFinite(requested) ? Math.max(1, Math.min(500, requested)) : 25;
    if (ragOpsPurgeCountInput) ragOpsPurgeCountInput.value = String(safeCount);
    postAction(`/api/rag/ops/documents/purge-oldest?count=${safeCount}`);
  });

  fetchStatus();
  ragOpsTimer = window.setInterval(fetchStatus, 15000);
  window.addEventListener('beforeunload', () => {
    if (ragOpsTimer) window.clearInterval(ragOpsTimer);
  });
}
