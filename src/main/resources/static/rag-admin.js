function getCsrfFromMeta() {
  const token = document.querySelector('meta[name="_csrf"]')?.getAttribute("content");
  const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content");
  if (token && header) return { token, header };
  return null;
}

function getXsrfFromCookie() {
  const c = document.cookie.split("; ").find(x => x.startsWith("XSRF-TOKEN="));
  if (!c) return null;
  return decodeURIComponent(c.split("=")[1]);
}

async function postJson(url, body) {
  const meta = getCsrfFromMeta();
  const xsrf = meta ? null : getXsrfFromCookie();

  const headers = { "Content-Type": "application/json" };
  if (meta) headers[meta.header] = meta.token;
  else if (xsrf) headers["X-XSRF-TOKEN"] = xsrf;

  const res = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined
  });

  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${JSON.stringify(data)}`);
  return data;
}

function splitIntoDocs(title, content, maxChars = 6000) {
  // troceo simple por tamaño, priorizando cortes por líneas
  const lines = content.split("\n");
  const docs = [];
  let chunk = "";
  let part = 1;

  for (const line of lines) {
    if ((chunk + "\n" + line).length > maxChars) {
      if (chunk.trim()) docs.push({ title: `${title} (part ${part++})`, content: chunk.trim() });
      chunk = line;
    } else {
      chunk += (chunk ? "\n" : "") + line;
    }
  }
  if (chunk.trim()) docs.push({ title: `${title} (part ${part})`, content: chunk.trim() });
  return docs;
}

function log(msg) {
  const el = document.getElementById("log");
  el.textContent += msg + "\n";
}

function buildDocPayload(title, content) {
  const source = document.getElementById("source")?.value.trim() || undefined;
  const tags   = document.getElementById("tags")?.value.trim()   || undefined;
  const payload = { title, content };
  if (source) payload.source = source;
  if (tags)   payload.tags   = tags;
  return payload;
}

document.getElementById("btnUpload")?.addEventListener("click", async () => {
  const title = document.getElementById("title").value.trim();
  const content = document.getElementById("content").value;
  if (!title || !content.trim()) return log("❌ Falta título o contenido.");

  try {
    const out = await postJson("/api/rag/documents", buildDocPayload(title, content));
    log("✅ Subido: " + JSON.stringify(out));
  } catch (e) {
    log("❌ Error: " + e.message);
  }
});

document.getElementById("btnUploadSplit")?.addEventListener("click", async () => {
  const title = document.getElementById("title").value.trim();
  const content = document.getElementById("content").value;
  if (!title || !content.trim()) return log("❌ Falta título o contenido.");

  const source = document.getElementById("source")?.value.trim() || undefined;
  const tags   = document.getElementById("tags")?.value.trim()   || undefined;
  const docs = splitIntoDocs(title, content, 6000).map(d => {
    if (source) d.source = source;
    if (tags)   d.tags   = tags;
    return d;
  });
  log(`ℹ️ Troceado en ${docs.length} docs...`);

  try {
    const out = await postJson("/api/rag/documents/batch", docs);
    log("✅ Subidos: " + JSON.stringify(out));
  } catch (e) {
    log("❌ Error: " + e.message);
  }
});

document.getElementById("btnUploadFiles")?.addEventListener("click", async () => {
  const files = document.getElementById("files").files;
  if (!files || files.length === 0) return log("❌ No hay archivos.");

  const docs = [];
  for (const f of files) {
    const text = await f.text();
    docs.push({ title: f.name, content: text });
  }

  try {
    const out = await postJson("/api/rag/documents/batch", docs);
    log("✅ Subidos: " + JSON.stringify(out));
  } catch (e) {
    log("❌ Error: " + e.message);
  }
});

function logReset(msg) {
  const el = document.getElementById("logReset");
  if (el) el.textContent += msg + "\n";
}

document.getElementById("btnResetRag")?.addEventListener("click", async () => {
  const first = confirm("¿Seguro que quieres eliminar TODO el corpus RAG?\n\nEsta acción borra todos los documentos, chunks, vectores e índice HNSW.\n\nNo se puede deshacer.");
  if (!first) return;
  const second = confirm("Confirmación final: el corpus quedará completamente vacío.\n\n¿Continuar?");
  if (!second) return;

  const btn = document.getElementById("btnResetRag");
  btn.disabled = true;
  btn.textContent = "Reiniciando...";
  logReset("⏳ Enviando reset...");

  try {
    const out = await postJson("/api/rag/ops/reset");
    logReset("✅ Reset completado. Docs activos: " + (out.activeDocuments ?? 0) + ", Chunks: " + (out.activeChunks ?? 0) + ", Vectores: " + (out.activeVectors ?? 0));
  } catch (e) {
    logReset("❌ Error en reset: " + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "Reiniciar RAG desde cero";
  }
});
