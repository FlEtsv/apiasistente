# Contributing Guide

Gracias por contribuir a API Asistente.

## Requisitos de desarrollo
- Java 21
- Docker (opcional para entorno completo)
- Acceso a MySQL y Ollama para pruebas end-to-end

## Flujo recomendado
1. Crea una rama desde `main`.
2. Implementa cambios pequenos y cohesivos.
3. Ejecuta pruebas localmente.
4. Abre Pull Request con contexto tecnico claro.

## Estandares de codigo
- Evita cambios no relacionados en el mismo PR.
- Mantiene nombres expresivos y metodos pequenos.
- Prefiere DTOs para contratos HTTP, no exponer entidades JPA.
- Conserva compatibilidad de endpoints existentes cuando no se acuerde breaking change.

## Validacion local
```bash
./gradlew clean test
./gradlew build
```

## Convencion de commits (sugerida)
Formato:
- `feat: ...`
- `fix: ...`
- `docs: ...`
- `refactor: ...`
- `test: ...`
- `chore: ...`

## Pull Request checklist
- [ ] El cambio compila
- [ ] Los tests relevantes pasan
- [ ] Documentacion actualizada (`README`/`docs`)
- [ ] No incluye secretos ni credenciales
- [ ] Describe impacto y rollback
