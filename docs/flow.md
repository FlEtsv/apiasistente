# Flujo de datos y endpoints (chat + RAG + memoria)

## 1) Flujo de datos del chat (RAG incluido)
1. **Entrada**: tu app web o externa envía `POST /api/chat` o `POST /api/ext/chat`.
2. **ChatService** valida usuario, resuelve sesión y guarda el mensaje del usuario.
3. **RAG**: `RagService.retrieveTopK()` calcula embedding de la pregunta, compara con embeddings en MySQL y devuelve Top‑K.
4. **Prompt final**: se arma con system prompt + historial + bloque “Contexto RAG”.
5. **LLM**: se llama a Ollama (`/api/chat`) y se guarda la respuesta.
6. **Fuentes**: se guarda el log de chunks usados y se retorna `sources` en el response.

## 2) Flujo de datos de ingesta RAG (documentos)
1. **Entrada**: `POST /api/rag/documents` o `POST /api/ext/rag/documents`.
2. **RagService.upsertDocument**:
   - Trocea el texto en chunks.
   - Genera embeddings con Ollama (`/api/embed`).
   - Persiste `KnowledgeDocument` + `KnowledgeChunk` en MySQL.

## 3) Flujo de “memoria” persistente
1. **Entrada**: `POST /api/rag/memory` o `POST /api/ext/rag/memory`.
2. **RagService.storeMemory**:
   - Crea un documento con título automático (`Memoria/<usuario>/<timestamp>`) o con el título indicado.
   - Persiste como documento RAG y queda disponible para retrieval futuro.

## 4) Endpoints disponibles (resumen)
### Chat (web app / sesión con login)
- `POST /api/chat`
- `GET /api/chat/{sessionId}/history`
- `GET /api/chat/active`
- `POST /api/chat/sessions`
- `GET /api/chat/sessions`
- `PUT /api/chat/sessions/{sessionId}/activate`
- `PUT /api/chat/sessions/{sessionId}/title`
- `DELETE /api/chat/sessions/{sessionId}`

### Chat externo (API key, stateless)
- `POST /api/ext/chat`

### RAG (web app / sesión con login)
- `POST /api/rag/documents`
- `POST /api/rag/documents/batch`
- `POST /api/rag/memory`

### RAG externo (API key, stateless)
- `POST /api/ext/rag/documents`
- `POST /api/ext/rag/documents/batch`
- `POST /api/ext/rag/memory`

## 5) Ejemplos rápidos (externo con API key)
```bash
curl -X POST http://localhost:8080/api/ext/chat \
  -H 'Authorization: Bearer ak_<prefix>_<token>' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"","message":"Hola"}'

curl -X POST http://localhost:8080/api/ext/rag/memory \
  -H 'Authorization: Bearer ak_<prefix>_<token>' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Perfil usuario","content":"Mi nombre es Ana y vivo en Madrid."}'
```
