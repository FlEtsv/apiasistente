# Arquitectura de instalacion guiada

## Objetivo
Garantizar que la web solo se use cuando la plataforma base ya esta lista:
- Docker operativo
- Ollama accesible
- MySQL provisionado
- API arrancada y saludable

## Capas del sistema

### 1) Preflight (host)
Script: `scripts/check-prerequisites.ps1`

Responsabilidad:
- Verificar `docker` en PATH.
- Verificar daemon Docker activo.
- Verificar plugin `docker compose`.
- Verificar Ollama (`/api/tags`) salvo que se omita explicitamente.

Si falla preflight, se corta la instalacion.

### 2) Provisioning de base de datos
Script: `scripts/run-mysql-container.ps1`

Responsabilidad:
- Crear red Docker dedicada (`apiasistente_net`) si no existe.
- Reutilizar contenedor MySQL/MariaDB existente cuando se detecta.
- Solo si no existe uno util, crear volumen persistente y contenedor nuevo.
- Esperar estado `healthy`.
- Asegurar DB y usuario de aplicacion.

Notas:
- Las tablas funcionales se crean automaticamente al arrancar la API
  (JPA/Hibernate `ddl-auto=update`).

### 3) Provisioning de API web
Script: `scripts/run-app-container.ps1`

Responsabilidad:
- Construir imagen local de la app.
- Crear/recrear contenedor de API.
- Inyectar variables de entorno (MySQL, Ollama, bootstrap admin).
- Montar `data/` para persistencia local.
- Esperar `GET /actuator/health` en estado `UP`.

### 4) Orquestador de instalacion
Script: `scripts/install-guided.ps1`

Responsabilidad:
- Guiar parametros (interactivo o no interactivo).
- Detectar contenedores MySQL existentes y preguntar cual reutilizar cuando hay varios.
- Guardar `.install.env` con la configuracion usada.
- Ejecutar preflight + MySQL + API en orden.
- Entregar URLs finales de login y setup.

## Flujo de arranque recomendado
1. `pwsh ./scripts/install-guided.ps1`
2. Login en `/login` con usuario bootstrap.
3. Configuracion inicial en `/setup` (Ollama, modelos, scraper).
4. Uso normal en `/chat`.

## Empaquetado recomendado
Punto de partida recomendado: **bundle ZIP con JAR + scripts**.

Script:
- `pwsh ./scripts/package-distribution.ps1`

Salida:
- `dist/apiasistente-installer.zip`
- Incluye `app/apiasistente.jar` y `scripts/install-guided.cmd` para instalacion asistida en Windows.
- El cliente final no instala Java: el script ejecuta el JAR dentro de Docker (`eclipse-temurin:21-jre`).

## Integracion del scraper (opcional)
La app funciona sin scraper.

Opciones de integracion:
- **Interno**: activar scraper web en `/setup`.
- **Externo (servicio aparte)**:
  - usar misma DB MySQL de la plataforma, o
  - preferible: inyectar conocimiento via API (`/api/ext/rag/**`) para desacoplar esquema.

## Coherencia operativa
- Sin Docker/Ollama: no hay web funcional.
- Con scripts: la instalacion se valida y prepara antes de abrir la web.
- Con `/setup`: el runtime queda parametrizable sin tocar codigo.
