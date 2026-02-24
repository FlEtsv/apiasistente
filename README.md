# API Asistente

API Asistente es una aplicacion Spring Boot para chat con RAG (Retrieval Augmented Generation), memoria persistente y monitoreo operativo.

El proyecto expone dos superficies de API:
- API web con login y sesion (`/api/**`)
- API externa stateless con API key (`/api/ext/**`)

## Tabla de contenido
- [Arquitectura](#arquitectura)
- [Stack tecnologico](#stack-tecnologico)
- [Requisitos](#requisitos)
- [Configuracion](#configuracion)
- [Ejecucion](#ejecucion)
- [Resumen de endpoints](#resumen-de-endpoints)
- [Monitoreo y alertas](#monitoreo-y-alertas)
- [Calidad y CI](#calidad-y-ci)
- [Contribuir](#contribuir)

## Arquitectura
Documentacion tecnica:
- `docs/architecture.md`: componentes, decisiones y limites de arquitectura.
- `docs/flow.md`: flujos de datos por endpoint.

Resumen:
1. Controllers reciben requests web o externas.
2. Services aplican reglas de negocio (chat, RAG, monitoreo, seguridad).
3. Repositories persisten entidades en MySQL.
4. Ollama provee chat y embeddings.
5. MonitoringAlertService genera eventos de salud y los publica via endpoints.

## Stack tecnologico
- Java 21
- Spring Boot 3.5.x
- Spring Security (sesion web + API key para `/api/ext/**`)
- Spring Data JPA + MySQL
- Thymeleaf + JS para la UI
- Micrometer + Prometheus
- Ollama para LLM y embeddings

## Requisitos
- Java 21
- MySQL accesible
- Ollama accesible con modelos de chat y embeddings disponibles

## Configuracion
Variables principales (definidas en `src/main/resources/application.yml`):

| Variable | Descripcion | Ejemplo |
| --- | --- | --- |
| `MYSQL_HOST` | Host de MySQL | `localhost` |
| `MYSQL_PORT` | Puerto MySQL | `3306` |
| `MYSQL_DB` | Base de datos | `apiasistente_db` |
| `MYSQL_USER` | Usuario MySQL | `apiuser` |
| `MYSQL_PASSWORD` | Password MySQL | `apipassword` |
| `OLLAMA_BASE_URL` | URL base de Ollama | `http://localhost:11434/api` |
| `OLLAMA_RESPONSE_GUARD_MODEL` | Mini-modelo para depurar respuestas | `qwen2.5:3b` |
| `MONITOR_ALERTS_ENABLED` | Habilita alertas | `true` |
| `MONITOR_ALERTS_INTERVAL_MS` | Frecuencia de chequeo | `15000` |
| `MONITOR_ALERTS_MAX_EVENTS` | Maximo de eventos en memoria | `200` |

## Ejecucion
### Docker Compose
```bash
docker compose up -d
```

### Local (Gradle)
```bash
./gradlew bootRun
```

## Resumen de endpoints
### Web (sesion)
- `POST /api/chat`
- `GET /api/chat/{sessionId}/history`
- `GET /api/chat/active`
- `POST /api/chat/sessions`
- `GET /api/chat/sessions`
- `PUT /api/chat/sessions/{sessionId}/activate`
- `PUT /api/chat/sessions/{sessionId}/title`
- `DELETE /api/chat/sessions/{sessionId}`
- `DELETE /api/chat/sessions`
- `POST /api/rag/documents`
- `POST /api/rag/documents/batch`
- `POST /api/rag/users/{username}/documents`
- `POST /api/rag/users/{username}/documents/batch`
- `POST /api/rag/memory`
- `GET /api/monitor/server`
- `GET /api/monitor/alerts`
- `GET /api/monitor/alerts/state`

### Externa (API key)
- `POST /api/ext/chat`
- `POST /api/ext/rag/documents`
- `POST /api/ext/rag/documents/batch`
- `POST /api/ext/rag/users/{externalUserId}/documents`
- `POST /api/ext/rag/users/{externalUserId}/documents/batch`
- `POST /api/ext/rag/memory`
- `GET /api/ext/monitor/server`
- `GET /api/ext/monitor/alerts`
- `GET /api/ext/monitor/alerts/state`

## Monitoreo y alertas
- Actuator expuesto: `health`, `info`, `prometheus`.
- Alertas de CPU, memoria, disco, swap e internet.
- Eventos recientes en memoria (maximo configurable).
- Endpoint externo para polling de alertas:

```bash
curl -sG "http://localhost:8080/api/ext/monitor/alerts" \
  -H "X-API-KEY: <tu_api_key>" \
  --data-urlencode "limit=20"
```

## Chat visual (Qwen-VL)
- Configura `ollama.visual-model` (por defecto `qwen-vl:latest`).
- En la UI (`/chat`) ahora puedes adjuntar imagen/camara/documento.
- El flujo es:
  1. Modelo visual analiza adjuntos y genera contexto intermedio.
  2. Ese contexto se combina con RAG.
  3. Modelo grande de chat responde al usuario.
- En requests JSON puedes enviar `media` (lista) con:
  - `name`
  - `mimeType`
  - `base64` (imagen/pdf/binario)
  - `text` (documento de texto)

## Depurador de respuestas (mini-modelo)
- Configura `ollama.response-guard-model` (env: `OLLAMA_RESPONSE_GUARD_MODEL`).
- `chat.response-guard.enabled=true` activa una segunda pasada corta para quitar relleno.
- `chat.response-guard.strict-mode=true` aplica depuracion mas agresiva (modo estricto).
- Si la respuesta original trae citas `[S#]` o codigo, se preservan; si la depuracion degrada, se mantiene la salida original.

## Permisos por codigo de registro
- Cada codigo de registro puede llevar permisos por checklist (`CHAT`, `RAG`, `MONITOR`, `API_KEYS`).
- El usuario nuevo hereda solo esos permisos al registrarse.
- Los usuarios existentes sin permisos persistidos mantienen acceso completo por compatibilidad.

## Calidad y CI
Este repositorio incluye CI en GitHub Actions para:
- Compilacion
- Tests
- Verificacion de wrapper Gradle

Comandos locales recomendados antes de abrir PR:
```bash
./gradlew clean test
./gradlew build
```

## Contribuir
Consulta:
- `CONTRIBUTING.md`
- `CODE_OF_CONDUCT.md`
- `SECURITY.md`

## Licencia
Este proyecto usa licencia MIT. Ver `LICENSE`.
