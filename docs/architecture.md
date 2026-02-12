# Arquitectura y Flujos (API Asistente)

Este documento resume la arquitectura por capas y los flujos principales usando diagramas Mermaid.

## Mapa general (capas y dependencias)

```mermaid
flowchart LR
  UI[UI Web (Thymeleaf + JS)] --> REST[Controllers REST]
  UI --> MVC[Controllers MVC]

  REST --> SRV[Servicios de negocio]
  MVC --> SRV

  SRV --> JPA[Repositorios JPA]
  SRV --> OLLAMA[Ollama API]
  SRV --> MON[Monitoreo / Alertas]

  JPA --> DB[(MySQL)]
  MON --> TG[Telegram Bot API]

  subgraph Seguridad
    SEC[Spring Security + ApiKeyAuthFilter]
  end

  SEC --> REST
  SEC --> MVC
```

## Flujo de autenticacion web

```mermaid
sequenceDiagram
  participant Browser
  participant AuthController
  participant AuthService
  participant AppUserRepository
  participant DbUserDetailsService

  Browser->>AuthController: GET /login
  AuthController-->>Browser: login.html

  Browser->>AuthController: POST /register
  AuthController->>AuthService: register(username, password)
  AuthService->>AppUserRepository: save(AppUser)
  AppUserRepository-->>AuthService: ok
  AuthService-->>AuthController: ok
  AuthController-->>Browser: redirect /login?registered=1

  Note over Browser,DbUserDetailsService: En login, Spring Security usa DbUserDetailsService
  DbUserDetailsService->>AppUserRepository: findByUsername
  AppUserRepository-->>DbUserDetailsService: AppUser
```

## Flujo de chat interno (web con sesion)

```mermaid
sequenceDiagram
  participant Browser
  participant ChatApiController
  participant ChatQueueService
  participant ChatService
  participant RagService
  participant OllamaClient
  participant Repos

  Browser->>ChatApiController: POST /api/chat {message, sessionId, model}
  ChatApiController->>ChatQueueService: chatAndWait(username,...)
  ChatQueueService->>ChatService: chat(username,...)

  ChatService->>Repos: validar usuario + resolver sesion
  ChatService->>Repos: guardar mensaje usuario
  ChatService->>RagService: retrieveTopKForOwnerOrGlobal
  RagService->>OllamaClient: embedOne(query)
  RagService->>Repos: buscar chunks + calcular scores
  RagService-->>ChatService: sources

  ChatService->>OllamaClient: chat(messages, model)
  OllamaClient-->>ChatService: respuesta
  ChatService->>Repos: guardar respuesta + fuentes

  ChatService-->>ChatQueueService: ChatResponse
  ChatQueueService-->>ChatApiController: ChatResponse
  ChatApiController-->>Browser: ChatResponse
```

## Flujo de chat externo (API key)

```mermaid
sequenceDiagram
  participant Client
  participant ApiKeyAuthFilter
  participant ApiKeyService
  participant ExternalApiController
  participant ChatQueueService

  Client->>ApiKeyAuthFilter: POST /api/ext/chat (X-API-KEY)
  ApiKeyAuthFilter->>ApiKeyService: authenticateAndGetUsername
  ApiKeyService-->>ApiKeyAuthFilter: username o null
  ApiKeyAuthFilter-->>ExternalApiController: Principal

  ExternalApiController->>ChatQueueService: chatAndWait(username,...)
  ChatQueueService-->>ExternalApiController: ChatResponse
  ExternalApiController-->>Client: ChatResponse
```

## Flujo RAG (ingesta)

```mermaid
flowchart TD
  A[POST /api/rag/documents] --> B[Validar request]
  B --> C[Upsert KnowledgeDocument]
  C --> D[Chunking TextChunker]
  D --> E[Embeddings con Ollama]
  E --> F[Guardar KnowledgeChunk + embedding_json]
  F --> G[Respuesta con id/titulo]
```

## Flujo de monitoreo y alertas

```mermaid
sequenceDiagram
  participant UI
  participant MonitorController
  participant MonitorService
  participant MonitoringAlertService
  participant TelegramNotifier

  UI->>MonitorController: GET /api/monitor/server
  MonitorController->>MonitorService: snapshot()
  MonitorService-->>MonitorController: ServerStatsDto
  MonitorController-->>UI: JSON

  MonitoringAlertService->>MonitorService: snapshot() (cada N segundos)
  MonitoringAlertService->>TelegramNotifier: send(alerta)
  TelegramNotifier-->>MonitoringAlertService: ok
```

## Flujo Ops Status (Grafana/Prometheus)

```mermaid
sequenceDiagram
  participant Browser
  participant MonitoringLinksController
  participant Grafana
  participant Prometheus

  Browser->>MonitoringLinksController: GET /ops/status
  MonitoringLinksController->>Grafana: GET /api/health
  MonitoringLinksController->>Prometheus: GET /-/healthy
  MonitoringLinksController-->>Browser: JSON con estado
```

## Notas operativas

1. Las paginas web requieren login y usan sesiones.
2. Los endpoints `/api/ext/**` requieren API key y retornan 401 si falta o es invalida.
3. Los DTOs evitan exponer entidades JPA al frontend.
4. El monitoreo actual detecta CPU, memoria, disco y caidas de internet.
5. Las alertas por Telegram requieren `TELEGRAM_BOT_TOKEN` y `TELEGRAM_CHAT_ID`.
