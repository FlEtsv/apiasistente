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

function formatPermissions(list) {
  const values = Array.isArray(list) ? list : [];
  if (!values.length) return "-";
  const labels = {
    CHAT: "Chat",
    RAG: "RAG",
    MONITOR: "Monitor",
    API_KEYS: "API Keys"
  };
  return values.map(v => labels[v] || v).join(", ");
}

function showRegCode(value, expiresAt, permissions) {
  const box = document.getElementById("regCodeCreatedBox");
  const pre = document.getElementById("regCodeCreatedValue");
  const exp = document.getElementById("regCodeCreatedExpiry");
  const perms = document.getElementById("regCodeCreatedPermissions");
  pre.textContent = value;
  exp.textContent = expiresAt ? fmtDate(expiresAt) : "-";
  if (perms) perms.textContent = formatPermissions(permissions);
  box.classList.remove("hidden");
}

function hideRegCode() {
  const box = document.getElementById("regCodeCreatedBox");
  const pre = document.getElementById("regCodeCreatedValue");
  const exp = document.getElementById("regCodeCreatedExpiry");
  const perms = document.getElementById("regCodeCreatedPermissions");
  pre.textContent = "";
  exp.textContent = "";
  if (perms) perms.textContent = "";
  box.classList.add("hidden");
}

function showRegError(msg) {
  const el = document.getElementById("regCodesError");
  el.textContent = msg;
  el.classList.remove("hidden");
}

function clearRegError() {
  const el = document.getElementById("regCodesError");
  el.textContent = "";
  el.classList.add("hidden");
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
        <td class="py-2 pr-2 text-xs">${k.specialModeEnabled ? "Especial" : "Generica"}</td>
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

async function loadRegCodes() {
  clearRegError();
  const tbody = document.getElementById("regCodesTbody");
  const empty = document.getElementById("regCodesEmpty");
  tbody.innerHTML = "";
  empty.classList.add("hidden");

  try {
    const codes = await apiFetch("/api/registration-codes", { method: "GET" });

    if (!codes || codes.length === 0) {
      empty.classList.remove("hidden");
      return;
    }

    for (const c of codes) {
      const tr = document.createElement("tr");
      tr.className = "border-b border-slate-700/20";

      const used = c.usedAt ? `${fmtDate(c.usedAt)}${c.usedBy ? " · " + c.usedBy : ""}` : "-";
      const revoked = c.revokedAt ? "✓" : "-";
      const canRevoke = !c.revokedAt && !c.usedAt;
      const permissions = formatPermissions(c.permissions);

      tr.innerHTML = `
        <td class="py-2 pr-2">${escapeHtml(c.label || "-")}</td>
        <td class="py-2 pr-2 font-mono text-xs text-blue-300/80">${escapeHtml(c.codePrefix || "-")}</td>
        <td class="py-2 pr-2 text-xs">${escapeHtml(permissions)}</td>
        <td class="py-2 pr-2 text-xs text-slate-400">${escapeHtml(fmtDate(c.expiresAt))}</td>
        <td class="py-2 pr-2 text-xs text-slate-400">${escapeHtml(used)}</td>
        <td class="py-2 pr-2">${revoked}</td>
        <td class="py-2 text-right">
          ${canRevoke ? `<button data-id="${c.id}" class="revokeReg px-3 py-1.5 rounded-xl text-xs bg-red-600/80 hover:bg-red-500/80 border border-red-400/20">Revocar</button>` : ""}
        </td>
      `;

      tbody.appendChild(tr);
    }

    document.querySelectorAll(".revokeReg").forEach(btn => {
      btn.addEventListener("click", async () => {
        const id = btn.getAttribute("data-id");
        if (!confirm("¿Revocar este codigo?")) return;

        try {
          await apiFetch(`/api/registration-codes/${id}`, { method: "DELETE" });
          await loadRegCodes();
        } catch (e) {
          showRegError(e.message);
        }
      });
    });
  } catch (e) {
    showRegError(e.message);
  }
}

async function createApiKey() {
  clearError();
  hideCreatedKey();

  const label = document.getElementById("apiKeyLabel")?.value?.trim();
  const specialModeEnabled = !!document.getElementById("apiKeySpecialMode")?.checked;
  if (!label) {
    showError("Falta la etiqueta (label).");
    return;
  }

  try {
    const out = await apiFetch("/api/api-keys", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ label, specialModeEnabled })
    });

    // out = { id, label, keyPrefix, apiKey, sessionId }
    showCreatedKey(out.apiKey);
    document.getElementById("apiKeyLabel").value = "";
    const specialCheckbox = document.getElementById("apiKeySpecialMode");
    if (specialCheckbox) specialCheckbox.checked = false;
    await loadApiKeys();
    // Notifica al chat para abrir una sesión separada para esta integración externa.
    window.dispatchEvent(new CustomEvent("api-key-created", { detail: { sessionId: out.sessionId } }));

  } catch (e) {
    showError(e.message);
  }
}

function getRegPermissions() {
  const map = [
    ["regPermChat", "CHAT"],
    ["regPermRag", "RAG"],
    ["regPermMonitor", "MONITOR"],
    ["regPermApiKeys", "API_KEYS"]
  ];
  const values = [];
  map.forEach(([id, code]) => {
    const input = document.getElementById(id);
    if (input && input.checked) {
      values.push(code);
    }
  });
  return values;
}

function resetRegPermissions() {
  const ids = ["regPermChat", "regPermRag", "regPermMonitor", "regPermApiKeys"];
  ids.forEach(id => {
    const input = document.getElementById(id);
    if (!input) return;
    input.checked = id === "regPermChat";
  });
}

async function createRegCode() {
  clearRegError();
  hideRegCode();

  const label = document.getElementById("regCodeLabel")?.value?.trim();
  const ttlRaw = document.getElementById("regCodeTtl")?.value?.trim();
  const ttlMinutes = ttlRaw ? parseInt(ttlRaw, 10) : null;
  const permissions = getRegPermissions();

  if (!permissions.length) {
    showRegError("Selecciona al menos un permiso para el nuevo usuario.");
    return;
  }

  try {
    const out = await apiFetch("/api/registration-codes", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ label, ttlMinutes, permissions })
    });

    showRegCode(out.code, out.expiresAt, out.permissions);
    document.getElementById("regCodeLabel").value = "";
    document.getElementById("regCodeTtl").value = "";
    resetRegPermissions();
    await loadRegCodes();
  } catch (e) {
    showRegError(e.message);
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
  resetRegPermissions();
  await loadApiKeys();
  await loadRegCodes();
});

document.getElementById("closeApiKeys")?.addEventListener("click", () => closeModal());
document.getElementById("apiKeysBackdrop")?.addEventListener("click", () => closeModal());
document.getElementById("btnRefreshApiKeys")?.addEventListener("click", async () => loadApiKeys());
document.getElementById("btnCreateApiKey")?.addEventListener("click", async () => createApiKey());

document.getElementById("btnCreateRegCode")?.addEventListener("click", async () => createRegCode());
document.getElementById("btnRefreshRegCodes")?.addEventListener("click", async () => loadRegCodes());

document.getElementById("btnCopyApiKey")?.addEventListener("click", async () => {
  const v = document.getElementById("apiKeyCreatedValue")?.textContent || "";
  if (!v) return;
  await navigator.clipboard.writeText(v);
});

document.getElementById("btnHideApiKey")?.addEventListener("click", () => hideCreatedKey());

document.getElementById("btnCopyRegCode")?.addEventListener("click", async () => {
  const v = document.getElementById("regCodeCreatedValue")?.textContent || "";
  if (!v) return;
  await navigator.clipboard.writeText(v);
});

document.getElementById("btnHideRegCode")?.addEventListener("click", () => hideRegCode());
