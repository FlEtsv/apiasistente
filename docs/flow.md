# Flujo de datos y endpoints

Este documento resume como circulan datos y requests en el sistema.

## 1) Chat con RAG

1. El cliente envia `POST /api/chat` (web) o `POST /api/ext/chat` (externo).
2. El backend identifica usuario y sesion.
3. Si hay adjuntos visuales/documentales, `ChatService` ejecuta una etapa intermedia con `ollama.visual-model` (Qwen-VL).
4. `RagService` calcula embedding de la consulta y recupera Top-K chunks.
5. `ChatService` construye prompt final con historial + contexto RAG + contexto visual intermedio.
6. `OllamaClient` genera respuesta final con el modelo grande.
7. Si esta activo `chat.response-guard`, un mini-modelo depura la respuesta para quitar relleno (sin perder citas/codigo).
8. Se persisten mensajes y fuentes usadas.
9. Se retorna `ChatResponse` al cliente.

Notas:
- Si `chat.response-guard.strict-mode=true`, la depuracion es mas agresiva contra texto innecesario.
- El acceso a vistas/APIs web depende de permisos del usuario (`CHAT`, `RAG`, `MONITOR`, `API_KEYS`).

## 2) Ingesta de conocimiento RAG

1. El cliente envia `POST /api/rag/documents` o `POST /api/ext/rag/documents` para contexto global,
   o bien `POST /api/rag/users/{username}/documents` / `POST /api/ext/rag/users/{externalUserId}/documents`
   para contexto individual.
2. Se valida payload y ownership del namespace de destino.
3. El contenido se divide en chunks.
4. Cada chunk genera embedding via Ollama.
5. Se persiste documento y chunks vectorizados.
6. Se retorna metadata de documento.

## 3) Memoria persistente

1. El cliente envia `POST /api/rag/memory` o `POST /api/ext/rag/memory`.
2. El sistema genera un documento de memoria con titulo y contenido.
3. Se procesa igual que documento RAG (chunking + embedding + persistencia).
4. Queda disponible para retrieval en chats posteriores.

## 4) Monitoreo y alertas

1. Un scheduler ejecuta chequeos de salud (`MonitoringAlertService`).
2. Se evalua CPU, memoria, disco, swap e internet.
3. Si hay cambio de estado, se registra evento `ALERT` o `RECOVER`.
4. Los eventos se publican en:
   - `GET /api/monitor/alerts` (web)
   - `GET /api/ext/monitor/alerts` (externo)

## 5) Endpoints por superficie

### Web (sesion)
- Chat: `/api/chat`, `/api/chat/sessions`, `/api/chat/active`
- RAG: `/api/rag/documents`, `/api/rag/documents/batch`, `/api/rag/memory`
  y rutas por usuario `/api/rag/users/{username}/documents` (+ `/batch`)
- Monitor: `/api/monitor/server`, `/api/monitor/alerts`, `/api/monitor/alerts/state`

### Externa (API key)
- Chat: `/api/ext/chat`
- RAG: `/api/ext/rag/documents`, `/api/ext/rag/documents/batch`, `/api/ext/rag/memory`
  y rutas por usuario externo `/api/ext/rag/users/{externalUserId}/documents` (+ `/batch`)
- Monitor: `/api/ext/monitor/server`, `/api/ext/monitor/alerts`, `/api/ext/monitor/alerts/state`

## 6) Ejemplos curl (externo)

```bash
API_KEY="ak_xxx"
BASE_URL="http://localhost:8080"

curl -s -X POST "$BASE_URL/api/ext/chat" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"","message":"Hola"}'

curl -sG "$BASE_URL/api/ext/monitor/alerts" \
  -H "X-API-KEY: $API_KEY" \
  --data-urlencode "limit=20"
```
