# CLAUDE.md — AI Assistant Guide for API Asistente

This file describes the codebase structure, development conventions, and workflows for AI assistants working on this project.

---

## Project Overview

**API Asistente** is a Spring Boot application (Java 21) that provides:
- Chat with RAG (Retrieval Augmented Generation) using a local Ollama LLM
- Persistent knowledge base management (vector search via Apache Lucene HNSW)
- Operational monitoring with health alerts (CPU, memory, disk, swap, internet)
- Dual API surfaces: web session-based UI and stateless external API with API keys

The project is written primarily in Spanish (entity names, UI, comments). All code comments and internal naming follow Spanish conventions.

---

## Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 3.5.9 |
| Build | Gradle (wrapper) | 8.x |
| Database (prod) | MySQL | 8.4 |
| Database (test) | H2 (in-memory) | — |
| ORM | Spring Data JPA / Hibernate | 3.5.x |
| Templates | Thymeleaf | — |
| Security | Spring Security 6 | 3.5.x |
| Metrics | Micrometer + Prometheus | — |
| Dashboards | Grafana | 11.5.1 |
| LLM | Ollama (local) | any |
| Vector Search | Apache Lucene HNSW | 9.12.0 |
| PDF Processing | Apache PDFBox | 3.0.4 |
| Web Scraping | JSoup | 1.18.1 |
| Testing | JUnit 5 + Spring Test | — |
| CI | GitHub Actions | — |
| Containers | Docker / Docker Compose | — |

---

## Project Structure

```
apiasistente/
├── src/main/java/com/example/apiasistente/
│   ├── apikey/          # API key lifecycle, authentication filter
│   ├── auth/            # Users, login/logout, password, permissions
│   ├── chat/            # Core conversation engine (53 files)
│   ├── monitoring/      # Health alerts, metrics, container orchestration (16 files)
│   ├── prompt/          # System prompt management
│   ├── rag/             # RAG ingestion, retrieval, vector index, maintenance (47 files)
│   ├── registration/    # Registration codes, permission bootstrap
│   ├── setup/           # Configuration wizard (Ollama endpoints, model selection)
│   └── shared/          # Security config, OllamaClient, error handling, home controller
├── src/main/resources/
│   ├── templates/       # Thymeleaf HTML views (chat, setup, login, monitor, rag_admin…)
│   ├── static/          # JavaScript & CSS (chat.js, rag-maintenance.js, style.css…)
│   ├── application.yml  # Main Spring Boot configuration
│   └── application.properties
├── src/test/java/       # 49 test files, integration + unit, uses H2
├── .github/
│   ├── workflows/ci.yml # GitHub Actions CI (test + build on push/PR to main)
│   ├── ISSUE_TEMPLATE/  # Bug report & feature request templates
│   └── dependabot.yml
├── monitoring/
│   ├── prometheus.yml
│   └── grafana/         # Dashboards & provisioning
├── reusable-components/ # Exportable modules (runtime-adaptation, RAG-learning, monitoring, prompt-governance)
├── scripts/             # PowerShell install/setup scripts + shell observability script
├── docs/
│   ├── architecture.md
│   ├── flow.md
│   └── installation-architecture.md
├── data/
│   ├── rag-hnsw/        # Local HNSW vector index (Lucene, gitignored)
│   └── bootstrap-admin.txt
├── build.gradle
├── Dockerfile           # Multi-stage: eclipse-temurin:21-jdk → eclipse-temurin:21-jre
├── docker-compose.yml   # 4 services: mysql, api, prometheus, grafana
└── README.md
```

---

## Build & Run Commands

```bash
# Run all tests
./gradlew test

# Build JAR (skip tests)
./gradlew build -x test

# Run locally (requires MySQL + Ollama running)
./gradlew bootRun

# Full stack via Docker Compose
docker compose up -d --build

# Windows interactive installer
pwsh ./scripts/install-guided.ps1
```

### Service Ports
| Service | Port |
|---------|------|
| Application | 8082 |
| Prometheus | 9090 |
| Grafana | 3000 (admin/admin) |
| MySQL | 3306 |
| Ollama | 11434 |

---

## First-Time Setup
1. Start the stack: `docker compose up -d --build`
2. Open `http://localhost:8082/login`
3. Use bootstrap credentials from `data/bootstrap-admin.txt`
4. You will be redirected to `/setup` — configure Ollama base URL and models
5. Start chatting at `/chat`

---

## Code Conventions

### Package / Naming
- Each domain has its own sub-package: `com.example.apiasistente.<feature>`
- Services: `*Service.java`
- Controllers: `*Controller.java`
- DTOs: `*DTO.java` (used for HTTP contracts; never expose JPA entities directly)
- Entities: Plain entity names (`ChatSession`, `KnowledgeDocument`)
- Repositories: `*Repository extends JpaRepository`

### Architecture Patterns
- **Feature-driven modules**: Each feature owns controllers, services, DTOs, entities, repositories
- **Service-layer delegation**: Controllers only delegate; business logic lives in services
- **Transactional**: Service methods use `@Transactional`
- **Security**: Session-based (web UI) + `X-API-KEY` / `Authorization: Bearer` (external API)
- **Schema management**: Hibernate `ddl-auto: update` — no Flyway/Liquibase migrations

### API Surfaces
| Prefix | Auth | Usage |
|--------|------|-------|
| `/api/**` | Spring session + CSRF | Web UI |
| `/api/ext/**` | API key (stateless) | External integrations |
| `/api/integration/monitor/**` | API key | Third-party status polling |

### Frontend
- Server-side rendering with Thymeleaf
- JavaScript in `src/main/resources/static/` for dynamic behavior (chat, RAG ops, API keys)
- No build tool for frontend (vanilla JS + CSS)

---

## Testing

- Test database: H2 in-memory (profile `test`)
- Configuration: `src/test/resources/application-test.yml`
- Run with: `./gradlew test`
- 49 test files covering chat turns, RAG retrieval, monitoring, auth, and API keys
- Integration tests use `@SpringBootTest` with H2

**Do not use MySQL-specific SQL in entity queries** — tests run against H2.

---

## Key Domain Details

### Chat (`chat/`)
Core services:
- `ChatService` — main facade
- `ChatTurnService` — turn orchestration
- `ChatRagFlowService` — RAG integration decision
- `ChatAssistantService` — LLM invocation (with retry/fallback)
- `ChatTurnPlanner` — dynamic RAG routing heuristics
- `ChatPromptBuilder` — prompt construction

Entities: `ChatSession`, `ChatMessage`, `ChatMessageSource`

### RAG (`rag/`)
- Hybrid ranking: semantic (85%) + lexical (15%) + exact-match boost
- Per-owner scoping: global documents + user-private documents
- HNSW vector index stored in `data/rag-hnsw/` (Lucene)
- Maintenance: automated auditing, unused detection, quality checks
- Log learning: runtime events ingested into the corpus automatically
- Web scraping: `RagWebScraperService` ingests URLs as knowledge

RAG tuning defaults (from `application.yml`):
| Parameter | Default |
|-----------|---------|
| Chunk size | 700 chars |
| Overlap | 120 chars |
| Top-k | 10 |
| Min score | 0.22 |
| Evidence threshold | 0.45 |
| MMR lambda | 0.70 |
| Max context history | 16 messages |

### Monitoring (`monitoring/`)
- `MonitoringAlertService` checks CPU, memory, disk, swap, internet
- Alert check interval: 15 seconds (configurable)
- Max in-memory events: 200 (configurable)
- Metrics exposed at `/actuator/prometheus` for Prometheus scraping

### Ollama Models (defaults)
| Role | Model |
|------|-------|
| Chat | `qwen3:14b` |
| Fast | `qwen2.5:7b` |
| Visual | `qwen2.5vl:7b` |
| Embedding | `nomic-embed-text:latest` |
| Guard | `mistral:7b` |

---

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`):
- Triggers: push to `main`, PR against `main`
- Java 21 (Temurin distribution)
- Steps: checkout → setup JDK → validate Gradle wrapper → cache → `./gradlew test` → `./gradlew build -x test`
- Permissions: read-only

---

## Commit Conventions

From `CONTRIBUTING.md`:

```
feat:     new feature
fix:      bug fix
docs:     documentation only
refactor: code change without new feature or bug fix
test:     adding or updating tests
chore:    build, config, dependency updates
```

---

## Environment Variables

Key variables (see `README.md` and `docker-compose.yml` for the full list):

| Variable | Description |
|----------|-------------|
| `SPRING_DATASOURCE_URL` | JDBC URL for MySQL |
| `SPRING_DATASOURCE_USERNAME` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `OLLAMA_BASE_URL` | Ollama HTTP endpoint (e.g. `http://host.docker.internal:11434`) |
| `OLLAMA_MODEL` | Chat model name |
| `OLLAMA_FAST_MODEL` | Fast/cheap model name |
| `OLLAMA_EMBEDDING_MODEL` | Embedding model name |

---

## Important Notes for AI Assistants

1. **Language**: Internal code, entity names, and UI copy are in Spanish. Match this when adding new code.
2. **DTOs vs. entities**: Always use DTOs for HTTP responses. Never expose JPA entities from controllers.
3. **No SQL migrations**: Schema evolves via Hibernate `ddl-auto: update`. For breaking changes, coordinate with the team.
4. **H2 compatibility**: Test queries must be compatible with H2 (avoid MySQL-specific functions).
5. **Vector index**: `data/rag-hnsw/` is a local file-based index. Do not delete or modify it outside `RagVectorIndexService`.
6. **Bootstrap admin**: `data/bootstrap-admin.txt` is generated on first run. Do not hard-code credentials.
7. **Port**: Application runs on **8082**, not the Spring default 8080.
8. **Feature structure**: New features should follow the existing pattern — add a new sub-package with controller, service, DTO, entity, and repository files.
9. **Tests**: Add integration tests for new features using H2 and the `test` profile.
10. **Secrets**: Never commit credentials, API keys, or sensitive configuration. Use environment variables.
