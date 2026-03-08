# Reusable Components Kit

Este directorio agrupa componentes clave del proyecto listos para reutilizar en otros repositorios.

## Estructura

- `themes/runtime-adaptation/`
  - Autotuning de modelo por carga local/latencias.
- `themes/rag-learning/`
  - Aprendizaje de logs y codigo en RAG.
- `themes/monitoring/`
  - Monitorizacion de servidor con soporte GPU.
- `themes/prompt-governance/`
  - Prompt base de conducta y limites.

## Flujo recomendado

1. Actualiza el codigo fuente en `src/main/...`.
2. Ejecuta el script:
   - `powershell -ExecutionPolicy Bypass -File scripts/export-reusable-components.ps1`
3. Revisa cambios en `reusable-components/themes/*`.
4. Copia las piezas necesarias al nuevo proyecto.

## Nota

Los archivos Java se copian tal cual (incluyendo `package` e imports actuales).  
Al moverlos a otro proyecto, ajusta:

- paquete base (`com.example...`)
- dependencias de Spring
- propiedades en `application.yml`
- wiring de controladores/servicios/repositorios
