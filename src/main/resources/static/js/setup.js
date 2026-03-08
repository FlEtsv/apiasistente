(function () {
  const statusEl = document.getElementById('setupStatus');
  const statusTextEl = document.getElementById('setupStatusText');
  const resultEl = document.getElementById('setupResult');
  const saveBtn = document.getElementById('saveSetupBtn');
  const runScraperBtn = document.getElementById('runScraperBtn');
  const loadDefaultsBtn = document.getElementById('loadDefaultsBtn');
  const ragRobotStateEl = document.getElementById('ragRobotState');
  const ragRobotDetailEl = document.getElementById('ragRobotDetail');
  const toggleRagRobotBtn = document.getElementById('toggleRagRobotBtn');

  let ragRobotPoweredOn = false;

  const fields = {
    ollamaBaseUrl: document.getElementById('ollamaBaseUrl'),
    chatModel: document.getElementById('chatModel'),
    fastChatModel: document.getElementById('fastChatModel'),
    visualModel: document.getElementById('visualModel'),
    imageModel: document.getElementById('imageModel'),
    embedModel: document.getElementById('embedModel'),
    responseGuardModel: document.getElementById('responseGuardModel'),
    scraperEnabled: document.getElementById('scraperEnabled'),
    scraperTickSec: document.getElementById('scraperTickSec'),
    scraperOwner: document.getElementById('scraperOwner'),
    scraperSource: document.getElementById('scraperSource'),
    scraperTags: document.getElementById('scraperTags'),
    scraperUrls: document.getElementById('scraperUrls')
  };

  function getCsrf() {
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    return { token, header };
  }

  function withCsrf(headers = {}) {
    const { token, header } = getCsrf();
    if (token && header) {
      headers[header] = token;
    }
    return headers;
  }

  async function jsonRequest(url, options = {}) {
    const response = await fetch(url, {
      ...options,
      headers: withCsrf({
        'Content-Type': 'application/json',
        ...(options.headers || {})
      })
    });
    const isJson = (response.headers.get('content-type') || '').includes('application/json');
    const payload = isJson ? await response.json().catch(() => null) : await response.text().catch(() => '');
    if (!response.ok) {
      const message = typeof payload === 'string'
        ? payload
        : (payload?.message || payload?.error || `HTTP ${response.status}`);
      throw new Error(message);
    }
    return payload;
  }

  function setStatus(configured) {
    if (!statusEl || !statusTextEl) return;
    statusEl.classList.remove('ok', 'warn');
    statusEl.classList.add(configured ? 'ok' : 'warn');
    statusTextEl.textContent = configured ? 'Instalacion configurada' : 'Configuracion pendiente';
  }

  function showResult(message, ok) {
    if (!resultEl) return;
    resultEl.textContent = message || '';
    resultEl.classList.remove('ok', 'error');
    if (message) {
      resultEl.classList.add(ok ? 'ok' : 'error');
    }
  }

  function renderRagRobotStatus(data) {
    if (!data || typeof data !== 'object') return;
    ragRobotPoweredOn = data.poweredOn === true;

    if (ragRobotStateEl) {
      ragRobotStateEl.classList.remove('on', 'off');
      ragRobotStateEl.classList.add(ragRobotPoweredOn ? 'on' : 'off');
      ragRobotStateEl.textContent = ragRobotPoweredOn ? 'Robot RAG encendido' : 'Robot RAG apagado';
    }
    if (ragRobotDetailEl) {
      ragRobotDetailEl.textContent = data.detail || '';
    }
    if (toggleRagRobotBtn) {
      toggleRagRobotBtn.textContent = ragRobotPoweredOn ? 'Apagar robot RAG' : 'Encender robot RAG';
      if (data.configuredEnabled === false && !ragRobotPoweredOn) {
        toggleRagRobotBtn.disabled = true;
        if (ragRobotDetailEl) {
          ragRobotDetailEl.textContent = 'No se puede encender desde setup porque rag.maintenance.enabled=false en application.yml.';
        }
      } else {
        toggleRagRobotBtn.disabled = false;
      }
    }
  }

  function fillForm(data) {
    if (!data || typeof data !== 'object') return;
    fields.ollamaBaseUrl.value = data.ollamaBaseUrl || '';
    fields.chatModel.value = data.chatModel || '';
    fields.fastChatModel.value = data.fastChatModel || '';
    fields.visualModel.value = data.visualModel || '';
    fields.imageModel.value = data.imageModel || '';
    fields.embedModel.value = data.embedModel || '';
    fields.responseGuardModel.value = data.responseGuardModel || '';
    fields.scraperEnabled.checked = data.scraperEnabled === true;
    const tickSec = Math.max(10, Math.round((Number(data.scraperTickMs) || 300000) / 1000));
    fields.scraperTickSec.value = String(tickSec);
    fields.scraperOwner.value = data.scraperOwner || 'global';
    fields.scraperSource.value = data.scraperSource || 'web-scraper';
    fields.scraperTags.value = data.scraperTags || 'scraper,web,knowledge';
    fields.scraperUrls.value = Array.isArray(data.scraperUrls) ? data.scraperUrls.join('\n') : '';
    setStatus(data.configured === true);
  }

  function buildPayload() {
    const tickSec = Number.parseInt(fields.scraperTickSec.value || '300', 10);
    return {
      ollamaBaseUrl: (fields.ollamaBaseUrl.value || '').trim(),
      chatModel: (fields.chatModel.value || '').trim(),
      fastChatModel: (fields.fastChatModel.value || '').trim(),
      visualModel: (fields.visualModel.value || '').trim(),
      imageModel: (fields.imageModel.value || '').trim(),
      embedModel: (fields.embedModel.value || '').trim(),
      responseGuardModel: (fields.responseGuardModel.value || '').trim(),
      scraperEnabled: !!fields.scraperEnabled.checked,
      scraperTickMs: Math.max(10000, (Number.isFinite(tickSec) ? tickSec : 300) * 1000),
      scraperOwner: (fields.scraperOwner.value || '').trim(),
      scraperSource: (fields.scraperSource.value || '').trim(),
      scraperTags: (fields.scraperTags.value || '').trim(),
      scraperUrls: (fields.scraperUrls.value || '').trim()
    };
  }

  async function loadConfig() {
    const data = await jsonRequest('/api/setup/config', { method: 'GET' });
    fillForm(data);
  }

  async function loadDefaults() {
    showResult('', true);
    if (loadDefaultsBtn) loadDefaultsBtn.disabled = true;
    try {
      const data = await jsonRequest('/api/setup/defaults', { method: 'GET' });
      fillForm(data);
      showResult('Preset recomendado cargado. Revisa y pulsa "Guardar configuracion".', true);
    } catch (error) {
      showResult(`No se pudo cargar el preset: ${error.message || 'error'}`, false);
    } finally {
      if (loadDefaultsBtn) loadDefaultsBtn.disabled = false;
    }
  }

  async function loadRagRobotStatus() {
    const data = await jsonRequest('/api/setup/rag-robot/status', { method: 'GET' });
    renderRagRobotStatus(data);
  }

  async function saveConfig() {
    showResult('', true);
    saveBtn.disabled = true;
    try {
      const payload = buildPayload();
      const data = await jsonRequest('/api/setup/config', {
        method: 'PUT',
        body: JSON.stringify(payload)
      });
      fillForm(data);
      showResult('Configuracion guardada correctamente. Ya puedes usar /chat.', true);
    } catch (error) {
      showResult(`No se pudo guardar: ${error.message || 'error'}`, false);
    } finally {
      saveBtn.disabled = false;
    }
  }

  async function runScraper() {
    showResult('', true);
    runScraperBtn.disabled = true;
    try {
      const data = await jsonRequest('/api/setup/scraper/run', { method: 'POST' });
      if (!data.executed) {
        showResult(data.message || 'No se ejecuto el scraper.', false);
        return;
      }
      showResult(
        `Scraper ejecutado.\nProcesadas: ${data.processed}\nActualizadas: ${data.updated}\nOmitidas: ${data.skipped}\nFallos: ${data.failed}`,
        true
      );
    } catch (error) {
      showResult(`Error lanzando scraper: ${error.message || 'error'}`, false);
    } finally {
      runScraperBtn.disabled = false;
    }
  }

  async function toggleRagRobot() {
    if (!toggleRagRobotBtn) return;
    toggleRagRobotBtn.disabled = true;
    try {
      const targetEnabled = !ragRobotPoweredOn;
      const data = await jsonRequest(`/api/setup/rag-robot/power?enabled=${targetEnabled}`, { method: 'POST' });
      renderRagRobotStatus(data);
      showResult(
        targetEnabled
          ? 'Robot RAG encendido. Volvera a revisar y sanear el corpus automaticamente.'
          : 'Robot RAG apagado. El corpus no se sanea automaticamente hasta volver a encenderlo.',
        true
      );
    } catch (error) {
      showResult(`No se pudo cambiar el robot RAG: ${error.message || 'error'}`, false);
      await loadRagRobotStatus().catch(() => null);
    }
  }

  saveBtn?.addEventListener('click', saveConfig);
  runScraperBtn?.addEventListener('click', runScraper);
  loadDefaultsBtn?.addEventListener('click', loadDefaults);
  toggleRagRobotBtn?.addEventListener('click', toggleRagRobot);

  loadConfig().catch((error) => {
    showResult(`No se pudo cargar configuracion: ${error.message || 'error'}`, false);
  });
  loadRagRobotStatus().catch((error) => {
    showResult(`No se pudo cargar estado del robot RAG: ${error.message || 'error'}`, false);
  });
})();
