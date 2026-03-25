#!/usr/bin/env bash
set -euo pipefail
# Activa el stack de observabilidad (API + Prometheus + Grafana) con Docker Compose.
# Script operativo idempotente para diagnosticos desde backend/UI.

PROJECT_DIR="${1:-.}"
COMPOSE_FILE="${2:-docker-compose.yml}"
SERVICES_CSV="${3:-api,prometheus,grafana}"

cd "$PROJECT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI no disponible en este host." >&2
  exit 1
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1 && docker-compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker-compose)
else
  echo "Docker Compose no disponible." >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "No existe el compose file: $COMPOSE_FILE" >&2
  exit 1
fi

IFS=',' read -r -a RAW_SERVICES <<< "$SERVICES_CSV"
SERVICES=()
for service in "${RAW_SERVICES[@]}"; do
  service="$(echo "$service" | xargs)"
  if [[ -n "$service" ]]; then
    SERVICES+=("$service")
  fi
done

if [[ ${#SERVICES[@]} -eq 0 ]]; then
  SERVICES=(api prometheus grafana)
fi

NO_DEPS=()
includes_api=0
for service in "${SERVICES[@]}"; do
  if [[ "${service,,}" == "api" ]]; then
    includes_api=1
    break
  fi
done
if [[ $includes_api -eq 0 ]]; then
  NO_DEPS=(--no-deps)
fi

"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" up -d "${NO_DEPS[@]}" "${SERVICES[@]}"

echo "Estado actual de contenedores relevantes:"
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'apiasistente|prometheus|grafana|mysql' || true
echo "OK: stack de observabilidad activado."
