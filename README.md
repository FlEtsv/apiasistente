# API Asistente (Chat + RAG + Memoria)

API Asistente es un servicio Spring Boot que expone endpoints de chat con recuperación de contexto (RAG) y almacenamiento de “memoria” persistente. Integra Ollama para generación de texto y embeddings, y usa MySQL para guardar sesiones, documentos y chunks vectorizados.

## Funcionalidad principal
- **Chat con RAG**: combina prompt del sistema, historial y contexto relevante recuperado desde MySQL.
- **Ingesta RAG**: permite cargar documentos para generar embeddings y persistirlos como conocimiento.
- **Memoria persistente**: guarda hechos o perfiles de usuario como documentos RAG reutilizables.
- **Acceso web y externo**: endpoints con sesión (web app) y endpoints externos con API key.

## Arquitectura (alto nivel)
1. **Entrada**: el cliente envía mensajes o documentos mediante endpoints REST.
2. **Servicios**: el backend valida, crea sesiones y orquesta RAG.
3. **Embeddings**: se llama a Ollama para generar embeddings (modelo configurable).
4. **Persistencia**: se guardan documentos, chunks y sesiones en MySQL.
5. **Respuesta**: se devuelve el texto generado y fuentes utilizadas.

Para un flujo detallado consulta `docs/flow.md`.

## Requisitos
- **Java 21** (toolchain configurada en Gradle).
- **MySQL** accesible para persistencia.
- **Ollama** con modelos configurados para chat y embeddings.

## Configuración
Las variables principales se encuentran en `src/main/resources/application.yml`:

| Variable | Descripción | Ejemplo |
| --- | --- | --- |
| `MYSQL_HOST` | Host de MySQL | `apiasistente_mysql` |
| `MYSQL_PORT` | Puerto de MySQL | `3306` |
| `MYSQL_DB` | Base de datos | `apiasistente_db` |
| `MYSQL_USER` | Usuario MySQL | `apiuser` |
| `MYSQL_PASSWORD` | Password MySQL | `apipassword` |
| `OLLAMA_BASE_URL` | URL base de Ollama | `http://ollama:11434/api` |

Otros parámetros relevantes:
- `ollama.chat-model`: modelo para chat.
- `ollama.embed-model`: modelo para embeddings.
- `rag.top-k`: número de chunks devueltos por consulta.
- `rag.chunk.size` / `rag.chunk.overlap`: tamaño y solapamiento de chunks.

## Ejecución

### Con Docker Compose
> El `docker-compose.yml` usa la imagen publicada y asume un contenedor de MySQL y Ollama.

```bash
docker compose up -d
```

### Local con Gradle
```bash
./gradlew bootRun
```

## Endpoints
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

## Ejemplos rápidos (API externa)
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

## Notas de seguridad
- Los endpoints `/api/ext/*` requieren API key en el header `Authorization`.
- Ajusta el nivel de logging en `application.yml` para producción.

## Tests
Para ejecutar pruebas unitarias:
```bash
./gradlew test
```

Si no hay Java 21 instalado localmente, Gradle fallará al resolver la toolchain.
