function getXsrf() {
  const c = document.cookie.split("; ").find(x => x.startsWith("XSRF-TOKEN="));
  if (!c) return null;
  return decodeURIComponent(c.split("=")[1]);
}

async function postJson(url, body) {
  const xsrf = getXsrf();
  const res = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(xsrf ? {"X-XSRF-TOKEN": xsrf} : {})
    },
    body: JSON.stringify(body)
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
      docs.push({ title: `${title} (part ${part++})`, content: chunk.trim() });
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

document.getElementById("btnUpload")?.addEventListener("click", async () => {
  const title = document.getElementById("title").value.trim();
  const content = document.getElementById("content").value;
  if (!title || !content.trim()) return log("❌ Falta título o contenido.");

  try {
    const out = await postJson("/api/rag/documents", {title, content});
    log("✅ Subido: " + JSON.stringify(out));
  } catch (e) {
    log("❌ Error: " + e.message);
  }
});

document.getElementById("btnUploadSplit")?.addEventListener("click", async () => {
  const title = document.getElementById("title").value.trim();
  const content = document.getElementById("content").value;
  if (!title || !content.trim()) return log("❌ Falta título o contenido.");

  const docs = splitIntoDocs(title, content, 6000);
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
