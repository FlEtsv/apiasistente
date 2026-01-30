function csrfHeader() {
  const token = document.querySelector('meta[name="_csrf"]')?.getAttribute("content");
  const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content");
  if (!token || !header) return {};
  return { [header]: token };
}

async function apiFetch(url, options = {}) {
  const res = await fetch(url, {
    credentials: "same-origin",
    ...options,
    headers: {
      ...(options.headers || {}),
      ...csrfHeader()
    }
  });

  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${JSON.stringify(data)}`);
  }
  return data;
}

function fmtDate(iso) {
  if (!iso) return "-";
  try {
    const d = new Date(iso);
    return d.toLocaleString();
  } catch {
    return iso;
  }
}

function openModal() {
  document.getElementById("apiKeysModal")?.classList.remove("hidden");
}

function closeModal() {
  document.getElementById("apiKeysModal")?.classList.add("hidden");
}

function showError(msg) {
  const el = document.getElementById("apiKeysError");
  el.textContent = msg;
  el.classList.remove("hidden");
}

function clearError() {
  const el = document.getElementById("apiKeysError");
  el.textContent = "";
  el.classList.add("hidden");
}

function showCreatedKey(value) {
  const box = document.getElementById("apiKeyCreatedBox");
  const pre = document.getElementById("apiKeyCreatedValue");
  pre.textContent = value;
  box.classList.remove("hidden");
}

function hideCreatedKey() {
  const box = document.getElementById("apiKeyCreatedBox");
  const pre = document.getElementById("apiKeyCreatedValue");
  pre.textContent = "";
  box.classList.add("hidden");
}

async function loadApiKeys() {
  clearError();
  const tbody = document.getElementById("apiKeysTbody");
  const empty = document.getElementById("apiKeysEmpty");
  tbody.innerHTML = "";
  empty.classList.add("hidden");

  try {
    const keys = await apiFetch("/api/api-keys", { method: "GET" });

    if (!keys || keys.length === 0) {
      empty.classList.remove("hidden");
      return;
    }

    for (const k of keys) {
      const tr = document.createElement("tr");
      tr.className = "border-b border-slate-700/20";

      const revoked = k.revokedAt ? "✅" : "-";

      tr.innerHTML = `
        <td class="py-2 pr-2">${escapeHtml(k.label)}</td>
        <td class="py-2 pr-2 font-mono text-xs text-blue-300/80">${escapeHtml(k.keyPrefix)}</td>
        <td class="py-2 pr-2 text-xs text-slate-400">${escapeHtml(fmtDate(k.createdAt))}</td>
        <td class="py-2 pr-2 text-xs text-slate-400">${escapeHtml(fmtDate(k.lastUsedAt))}</td>
        <td class="py-2 pr-2">${revoked}</td>
        <td class="py-2 text-right">
          ${k.revokedAt ? "" : `<button data-id="${k.id}" class="revokeKey px-3 py-1.5 rounded-xl text-xs bg-red-600/80 hover:bg-red-500/80 border border-red-400/20">Revocar</button>`}
        </td>
      `;

      tbody.appendChild(tr);
    }

    document.querySelectorAll(".revokeKey").forEach(btn => {
      btn.addEventListener("click", async () => {
        const id = btn.getAttribute("data-id");
        if (!confirm("¿Revocar esta clave? Dejará de funcionar en /api/ext/**")) return;

        try {
          await apiFetch(`/api/api-keys/${id}`, { method: "DELETE" });
          await loadApiKeys();
        } catch (e) {
          showError(e.message);
        }
      });
    });

  } catch (e) {
    showError(e.message);
  }
}

async function createApiKey() {
  clearError();
  hideCreatedKey();

  const label = document.getElementById("apiKeyLabel")?.value?.trim();
  if (!label) {
    showError("Falta la etiqueta (label).");
    return;
  }

  try {
    const out = await apiFetch("/api/api-keys", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ label })
    });

    // out = { id, label, keyPrefix, apiKey, sessionId }
    showCreatedKey(out.apiKey);
    document.getElementById("apiKeyLabel").value = "";
    await loadApiKeys();
    // Notifica al chat para abrir una sesión separada para esta integración externa.
    window.dispatchEvent(new CustomEvent("api-key-created", { detail: { sessionId: out.sessionId } }));

  } catch (e) {
    showError(e.message);
  }
}

function escapeHtml(s) {
  return String(s ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

// Wire UI
document.getElementById("openApiKeys")?.addEventListener("click", async () => {
  openModal();
  await loadApiKeys();
});

document.getElementById("closeApiKeys")?.addEventListener("click", () => closeModal());
document.getElementById("apiKeysBackdrop")?.addEventListener("click", () => closeModal());
document.getElementById("btnRefreshApiKeys")?.addEventListener("click", async () => loadApiKeys());
document.getElementById("btnCreateApiKey")?.addEventListener("click", async () => createApiKey());

document.getElementById("btnCopyApiKey")?.addEventListener("click", async () => {
  const v = document.getElementById("apiKeyCreatedValue")?.textContent || "";
  if (!v) return;
  await navigator.clipboard.writeText(v);
});

document.getElementById("btnHideApiKey")?.addEventListener("click", () => hideCreatedKey());
