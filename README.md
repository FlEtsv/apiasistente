# API Asistente

API Asistente es una aplicacion Spring Boot para chat con RAG (Retrieval Augmented Generation), memoria persistente y monitoreo operativo.

El proyecto expone dos superficies de API:
- API web con login y sesion (`/api/**`)
- API externa stateless con API key (`/api/ext/**`)

## Tabla de contenido
- [Arquitectura](#arquitectura)
- [Arquitectura de instalacion](#arquitectura-de-instalacion)
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
- `docs/responsibility-flows.md`: separacion de responsabilidades por capa y flujo.

Resumen:
1. El codigo esta organizado por feature (`apikey`, `auth`, `chat`, `monitoring`, `prompt`, `rag`, `registration`, `shared`).
2. Cada feature concentra sus controllers, services, DTOs, entities y repositories cuando aplica.
3. `shared` contiene solo infraestructura transversal (config, filtros, seguridad comun, cliente Ollama).
4. Ollama provee chat y embeddings.
5. `MonitoringAlertService` genera eventos de salud y los publica via endpoints.

Layout principal:
- `src/main/java/com/example/apiasistente/chat`: flujo completo de chat y orquestacion por turno.
- `src/main/java/com/example/apiasistente/rag`: ingesta, retrieval, utilidades vectoriales y endpoints RAG.
- `src/main/java/com/example/apiasistente/monitoring`: endpoints y servicios de monitoreo.
- `src/main/java/com/example/apiasistente/shared`: infraestructura transversal.

## Arquitectura de instalacion
- `docs/installation-architecture.md`: flujo de preflight + provisioning antes de abrir la web.
- Scripts:
  - `scripts/check-prerequisites.ps1`
  - `scripts/run-mysql-container.ps1`
  - `scripts/run-app-container.ps1`
  - `scripts/install-guided.ps1`
  - `scripts/install-guided.cmd`
  - `scripts/package-distribution.ps1`

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
| `BOOTSTRAP_ADMIN_ENABLED` | Crea admin inicial si no hay usuarios | `true` |
| `BOOTSTRAP_ADMIN_USERNAME` | Usuario admin inicial | `admin` |
| `BOOTSTRAP_ADMIN_PASSWORD` | Password admin inicial (opcional) | `MiPasswordSegura123!` |
| `BOOTSTRAP_ADMIN_OUTPUT_FILE` | Archivo donde se guarda el admin inicial | `data/bootstrap-admin.txt` |
| `OLLAMA_RESPONSE_GUARD_MODEL` | Mini-modelo para depurar respuestas | `qwen2.5:3b` |
| `OLLAMA_IMAGE_MODEL` | Modelo de generación de imágenes en chat | `flux` |
| `CHAT_IMAGE_BASE_URL` | URL base de ComfyUI API para imagen | `http://localhost:8083` |
| `CHAT_IMAGE_CHECKPOINT` | Checkpoint usado por FLUX en ComfyUI | `flux1-schnell-fp8.safetensors` |
| `MONITOR_ALERTS_ENABLED` | Habilita alertas | `true` |
| `MONITOR_ALERTS_INTERVAL_MS` | Frecuencia de chequeo | `15000` |
| `MONITOR_ALERTS_MAX_EVENTS` | Maximo de eventos en memoria | `200` |

## Ejecucion
### Docker Compose
```bash
docker compose up -d --build
```

### Local (Gradle)
```bash
./gradlew bootRun
```

### Instalacion guiada por scripts (recomendada en Windows)
```powershell
pwsh ./scripts/install-guided.ps1
```

Modo no interactivo:
```powershell
pwsh ./scripts/install-guided.ps1 -NonInteractive -OllamaBaseUrl "http://host.docker.internal:11434/api" -RecreateAppContainer
```

Scripts individuales:
```powershell
pwsh ./scripts/check-prerequisites.ps1
pwsh ./scripts/run-mysql-container.ps1
pwsh ./scripts/run-mysql-container.ps1 -UseExistingContainer "mysql-prod" -SkipDbInit
pwsh ./scripts/run-app-container.ps1 -Recreate
```

Empaquetado para distribuir:
```powershell
pwsh ./scripts/package-distribution.ps1
```
Genera `dist/apiasistente-installer.zip` con:
- `app/apiasistente.jar`
- instalador guiado (`scripts/install-guided.cmd`)
- scripts de provisioning
- docs de arquitectura de instalacion
- no requiere Java instalado en cliente final (el JAR corre dentro de contenedor `eclipse-temurin:21-jre`)

## Instalacion guiada (wizard inicial)
Flujo recomendado para instalar en una maquina nueva sin tocar codigo:

1. Arranca la app (compose o gradle).
2. Abre `http://localhost:8082/login`.
3. Si es la primera ejecucion:
   - Se crea un admin bootstrap automaticamente.
   - Credenciales en `data/bootstrap-admin.txt` (o las que hayas pasado por `BOOTSTRAP_ADMIN_USERNAME/BOOTSTRAP_ADMIN_PASSWORD`).
4. Inicia sesion y se redirige a `http://localhost:8082/setup` mientras falte configuracion.
5. En `/setup` define:
   - URL de Ollama (`http://localhost:11434/api` o remota).
   - Modelos chat/fast/visual/image/embed/guard.
   - Activacion del scraper y URLs web a indexar en RAG.
   - Encendido/apagado del robot RAG de mantenimiento (pausa/reanuda barridos automaticos).
6. Guarda y usa `/chat`. Desde el wizard puedes lanzar `Ejecutar scraper ahora`.

## Politica de reutilizacion de contenedores
- El instalador busca contenedores MySQL existentes (MySQL/MariaDB) y los reutiliza por defecto.
- Solo crea `apiasistente_mysql` si no detecta uno reutilizable.
- El contenedor de la app (`apiasistente`) se puede recrear para aplicar nuevas versiones sin perder datos de DB.
- Los datos de negocio se mantienen en la base de datos existente.

## Resumen de endpoints
### Web (sesion)
- `GET /setup`
- `GET /api/setup/status`
- `GET /api/setup/config`
- `PUT /api/setup/config`
- `POST /api/setup/scraper/run`
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
- `GET /api/monitor/stack/status`
- `POST /api/monitor/stack/up`

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

### Integracion monitor (API key, endpoint dedicado para otra app)
- `GET /api/integration/monitor/server`
- `GET /api/integration/monitor/alerts`
- `GET /api/integration/monitor/alerts/state`

## Monitoreo y alertas
- Actuator expuesto: `health`, `info`, `prometheus`.
- Alertas de CPU, memoria, disco, swap e internet.
- Eventos recientes en memoria (maximo configurable).
- Arranque remoto del stack de observabilidad desde web:
  - Boton en `/chat` (panel tecnico)
  - Botones en `/ops/status/ui`
- Script reutilizable de arranque:
  - `scripts/activate-observability-stack.ps1`
  - `scripts/activate-observability-stack.sh`
- Endpoint externo para polling de alertas:

```bash
curl -sG "http://localhost:8080/api/ext/monitor/alerts" \
  -H "X-API-KEY: <tu_api_key>" \
  --data-urlencode "limit=20"
```

```bash
curl -sG "http://localhost:8080/api/integration/monitor/alerts" \
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

## Generación de imágenes en chat
- En la UI (`/chat`) selecciona `Generar imagen (flux schnell)` o `Generar imagen PRO 3090 (flux dev)`.
- El backend usa ComfyUI API (`POST /prompt`) con workflow FLUX `txt2img` o `img2img` (si adjuntas una imagen).
- Si eliges un checkpoint no instalado (por ejemplo modo PRO sin modelo cargado), el backend reintenta automaticamente con el checkpoint base configurado.
- La respuesta ahora trae enlace de descarga directa.
- Endpoint de entrega de imagen generada: `GET /api/chat/sessions/{sessionId}/images/{imageId}`.
- Descarga forzada: `GET /api/chat/sessions/{sessionId}/images/{imageId}?download=1`.
- Configuración:
  - `ollama.image-model` (alias del modelo de imagen mostrado en chat).
  - `chat.image-generation.base-url`, `prompt-path`, `checkpoint`.
  - `chat.image-generation.width`, `height`, `steps`, `cfg-scale`, `sampler-name`, `scheduler`, `denoise`, `img2img-denoise`, `seed`.
  - `chat.image-generation.convert-format` y `convert-quality` (opcional para WebP/JPEG).
  - `chat.image-generation.timeout-ms`, `max-prompt-chars`, `storage-dir`.

Ejemplo rapido para levantar ComfyUI API FLUX local:
```bash
docker run -d --name flux-api --gpus all -p 3000:3000 --restart unless-stopped \
  saladtechnologies/comfyui:comfy0.3.61-api1.9.2-torch2.8.0-cuda12.8-flux1-schnell-fp8
```

## Depurador de respuestas (mini-modelo)
- Configura `ollama.response-guard-model` (env: `OLLAMA_RESPONSE_GUARD_MODEL`).
- `chat.response-guard.enabled=true` activa una segunda pasada corta para quitar relleno.
- `chat.response-guard.strict-mode=true` aplica depuracion mas agresiva (modo estricto).
- Si la respuesta original trae citas `[S#]` o codigo, se preservan; si la depuracion degrada, se mantiene la salida original.

## Planificador de turno (mini-modelo de enrutado)
- `ChatTurnPlanner` decide por turno si conviene `ragNeeded=true/false`.
- Tambien clasifica el `reasoningLevel` (`LOW`, `MEDIUM`, `HIGH`).
- El backend devuelve ambos campos en `ChatResponse` junto con `ragUsed`, para distinguir plan vs ejecucion real.

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
