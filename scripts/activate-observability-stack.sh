#!/usr/bin/env bash
set -euo pipefail

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

"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" up -d "${SERVICES[@]}"

echo "Estado actual de contenedores relevantes:"
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'apiasistente|prometheus|grafana|mysql' || true
echo "OK: stack de observabilidad activado."
