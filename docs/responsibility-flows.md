# Flujos y separacion de responsabilidades

## Objetivo
Definir fronteras claras entre capas para que Windows/macOS compartan el mismo comportamiento funcional sin duplicar responsabilidades internas.

## Mapa de responsabilidades

### Setup (wizard inicial)
- `SetupApiController`: expone endpoints REST de setup.
- `SetupPageController`: renderiza la pantalla `/setup`.
- `SetupConfigService`: orquesta persistencia y resolucion de configuracion efectiva.
- `SetupConfigNormalizer`: sanea y normaliza payloads/valores (sin acceso a DB).
- `AppSetupConfigRepository`: acceso a persistencia.

Regla: controller no normaliza ni persiste directamente; service no parsea HTTP; normalizer no accede a repositorios.

### Monitoring (lectura y observabilidad)
- `MonitoringReadService`: fachada unica para snapshot, alertas y estado de alertas.
- `MonitorApiController`: superficie web interna.
- `ExternalMonitoringController`: superficie externa por API key.
- `IntegrationMonitoringController`: superficie de integracion entre apps.
- `MonitoringStackService`: diagnostico y arranque del stack Docker.
- `PrometheusMonitoringMetricsPublisher` y `AppMetricsService`: publicacion de metricas.

Regla: las tres superficies de monitor consumen la misma fachada de lectura para garantizar consistencia.

### Chat y RAG (orquestacion conversacional)
- `ChatService`: fachada de alto nivel para controladores/cola.
- `ChatTurnService`: orquestador transaccional de cada turno.
- `ChatRagFlowService`: fase de retrieval y decision de grounding.
- `RagService`: core de almacenamiento/retrieval vectorial.
- `RagIngestionService`: adaptador de ingesta desde DTOs HTTP al core RAG.
- `RagApiController` y `ExternalRagController`: reglas HTTP/autorizacion y delegacion.

Regla: la logica de compatibilidad de payload de ingesta vive en `RagIngestionService`, no en controladores.

### Scripts instalables (Windows/macOS)
- `check-prerequisites.*`: solo verifica prerequisitos.
- `run-mysql-container.*`: solo gestiona infraestructura de MySQL.
- `run-app-container.*`: solo gestiona contenedor API.
- `install-guided.*`: orquestador secuencial de instalacion.
- `activate-observability-stack.*`: arranque del stack de observabilidad.
- `package-distribution.*`: empaquetado de artefactos para testers/clientes.

Regla: cada script hace una sola fase; el orquestador decide el orden.

## Flujos verificados

### Flujo de instalacion guiada
1. `install-guided` ejecuta `check-prerequisites`.
2. Si preflight OK, ejecuta `run-mysql-container`.
3. Con DB lista, ejecuta `run-app-container`.
4. Guarda `.install.env` para trazabilidad de parametros.
5. Usuario entra en `/login` y completa `/setup`.

### Flujo de monitor
1. Cliente llama endpoint (`/api/monitor`, `/api/ext/monitor` o `/api/integration/monitor`).
2. Controller delega en `MonitoringReadService`.
3. `MonitoringReadService` consulta `MonitorService`, `MonitoringAlertStore` y `MonitoringAlertService`.
4. Respuesta consistente en cualquier superficie.

### Flujo de stack Docker
1. UI llama `/api/monitor/stack/status` para inspeccion.
2. UI llama `/api/monitor/stack/up` para activacion.
3. `MonitoringStackService` decide script vs compose, ejecuta y re-inspecciona estado.

## Criterios de separacion aplicados
- Normalizacion de setup separada en componente dedicado.
- Lectura de monitor centralizada en una sola fachada.
- Scripts particionados por fase de lifecycle (preflight/provision/orquestacion/empaquetado).
- Endpoints y servicios con contratos documentados via Javadoc.
