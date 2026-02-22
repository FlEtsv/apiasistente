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
- `POST /api/rag/memory`
- `GET /api/monitor/server`
- `GET /api/monitor/alerts`
- `GET /api/monitor/alerts/state`

### Externa (API key)
- `POST /api/ext/chat`
- `POST /api/ext/rag/documents`
- `POST /api/ext/rag/documents/batch`
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
