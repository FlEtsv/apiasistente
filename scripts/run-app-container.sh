#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME="apiasistente"
IMAGE_NAME="apiasistente:local"
NETWORK_NAME="apiasistente_net"
HOST_PORT="8082"
OLLAMA_BASE_URL="http://host.docker.internal:11434/api"
MYSQL_HOST="apiasistente_mysql"
MYSQL_PORT="3306"
DB_NAME="apiasistente_db"
DB_USER="apiuser"
DB_PASSWORD="apipassword"
BOOTSTRAP_ADMIN_ENABLED="true"
BOOTSTRAP_ADMIN_USERNAME="admin"
BOOTSTRAP_ADMIN_PASSWORD=""
BOOTSTRAP_ADMIN_OUTPUT_FILE="data/bootstrap-admin.txt"
BUNDLED_JAR_RELATIVE_PATH="app/apiasistente.jar"
WAIT_TIMEOUT_SEC="240"
SKIP_BUILD=0
FORCE_BUILD_IMAGE=0
RECREATE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --container-name) CONTAINER_NAME="${2:-}"; shift 2 ;;
    --image-name) IMAGE_NAME="${2:-}"; shift 2 ;;
    --network-name) NETWORK_NAME="${2:-}"; shift 2 ;;
    --host-port) HOST_PORT="${2:-}"; shift 2 ;;
    --ollama-base-url) OLLAMA_BASE_URL="${2:-}"; shift 2 ;;
    --mysql-host) MYSQL_HOST="${2:-}"; shift 2 ;;
    --mysql-port) MYSQL_PORT="${2:-}"; shift 2 ;;
    --db-name) DB_NAME="${2:-}"; shift 2 ;;
    --db-user) DB_USER="${2:-}"; shift 2 ;;
    --db-password) DB_PASSWORD="${2:-}"; shift 2 ;;
    --bootstrap-admin-enabled) BOOTSTRAP_ADMIN_ENABLED="${2:-}"; shift 2 ;;
    --bootstrap-admin-username) BOOTSTRAP_ADMIN_USERNAME="${2:-}"; shift 2 ;;
    --bootstrap-admin-password) BOOTSTRAP_ADMIN_PASSWORD="${2:-}"; shift 2 ;;
    --bootstrap-admin-output-file) BOOTSTRAP_ADMIN_OUTPUT_FILE="${2:-}"; shift 2 ;;
    --bundled-jar-relative-path) BUNDLED_JAR_RELATIVE_PATH="${2:-}"; shift 2 ;;
    --wait-timeout-sec) WAIT_TIMEOUT_SEC="${2:-}"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --force-build-image) FORCE_BUILD_IMAGE=1; shift ;;
    --recreate) RECREATE=1; shift ;;
    *)
      echo "Parametro no soportado: $1" >&2
      exit 1
      ;;
  esac
done

docker_cmd() {
  docker "$@"
}

container_exists() {
  docker container inspect "$1" >/dev/null 2>&1
}

ensure_network() {
  local name="$1"
  if ! docker network inspect "$name" >/dev/null 2>&1; then
    echo "Creando red Docker '$name'..."
    docker_cmd network create "$name" >/dev/null
  else
    echo "Red Docker '$name' ya existe."
  fi
}

wait_api_health() {
  local port="$1"
  local timeout="$2"
  local url="http://localhost:${port}/actuator/health"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    local payload=""
    payload="$(curl -fsS --max-time 4 "$url" 2>/dev/null || true)"
    if [[ "$payload" == *"\"status\""* && "$payload" == *"\"UP\""* ]]; then
      return 0
    fi
    sleep 3
  done
  return 1
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DATA_DIR="$REPO_ROOT/data"
mkdir -p "$DATA_DIR"
DATA_DIR_RESOLVED="$(cd "$DATA_DIR" && pwd)"

BUNDLED_JAR_PATH="$REPO_ROOT/$BUNDLED_JAR_RELATIVE_PATH"
USE_BUNDLED_JAR=0
if [[ -f "$BUNDLED_JAR_PATH" && $FORCE_BUILD_IMAGE -eq 0 ]]; then
  USE_BUNDLED_JAR=1
  BUNDLED_JAR_PATH="$(cd "$(dirname "$BUNDLED_JAR_PATH")" && pwd)/$(basename "$BUNDLED_JAR_PATH")"
fi

echo "== Provisioning API container =="
docker_cmd info >/dev/null
ensure_network "$NETWORK_NAME"

if [[ $USE_BUNDLED_JAR -eq 1 ]]; then
  echo "Modo bundle detectado: se usara JAR local en contenedor Java."
else
  if [[ $SKIP_BUILD -eq 0 ]]; then
    echo "Construyendo imagen local '$IMAGE_NAME'..."
    docker_cmd build -t "$IMAGE_NAME" "$REPO_ROOT" >/dev/null
  else
    echo "Build omitido por parametro."
  fi
fi

if container_exists "$CONTAINER_NAME"; then
  if [[ $RECREATE -eq 1 ]]; then
    echo "Eliminando contenedor existente '$CONTAINER_NAME'..."
    docker_cmd rm -f "$CONTAINER_NAME" >/dev/null
  else
    state="$(docker inspect -f '{{.State.Status}}' "$CONTAINER_NAME" 2>/dev/null || true)"
    if [[ "$state" != "running" ]]; then
      echo "Iniciando contenedor existente '$CONTAINER_NAME'..."
      docker_cmd start "$CONTAINER_NAME" >/dev/null
    else
      echo "Contenedor '$CONTAINER_NAME' ya estaba en ejecucion."
    fi
  fi
fi

if ! container_exists "$CONTAINER_NAME"; then
  echo "Creando contenedor API '$CONTAINER_NAME'..."
  if [[ $USE_BUNDLED_JAR -eq 1 ]]; then
    docker_cmd run -d \
      --name "$CONTAINER_NAME" \
      --network "$NETWORK_NAME" \
      -p "${HOST_PORT}:8082" \
      -e "SERVER_PORT=8082" \
      -e "MYSQL_HOST=$MYSQL_HOST" \
      -e "MYSQL_PORT=$MYSQL_PORT" \
      -e "MYSQL_DB=$DB_NAME" \
      -e "MYSQL_USER=$DB_USER" \
      -e "MYSQL_PASSWORD=$DB_PASSWORD" \
      -e "OLLAMA_BASE_URL=$OLLAMA_BASE_URL" \
      -e "BOOTSTRAP_ADMIN_ENABLED=$BOOTSTRAP_ADMIN_ENABLED" \
      -e "BOOTSTRAP_ADMIN_USERNAME=$BOOTSTRAP_ADMIN_USERNAME" \
      -e "BOOTSTRAP_ADMIN_PASSWORD=$BOOTSTRAP_ADMIN_PASSWORD" \
      -e "BOOTSTRAP_ADMIN_OUTPUT_FILE=$BOOTSTRAP_ADMIN_OUTPUT_FILE" \
      -v "${DATA_DIR_RESOLVED}:/app/data" \
      -v "${BUNDLED_JAR_PATH}:/opt/apiasistente/apiasistente.jar:ro" \
      eclipse-temurin:21-jre \
      java -jar /opt/apiasistente/apiasistente.jar >/dev/null
  else
    docker_cmd run -d \
      --name "$CONTAINER_NAME" \
      --network "$NETWORK_NAME" \
      -p "${HOST_PORT}:8082" \
      -e "SERVER_PORT=8082" \
      -e "MYSQL_HOST=$MYSQL_HOST" \
      -e "MYSQL_PORT=$MYSQL_PORT" \
      -e "MYSQL_DB=$DB_NAME" \
      -e "MYSQL_USER=$DB_USER" \
      -e "MYSQL_PASSWORD=$DB_PASSWORD" \
      -e "OLLAMA_BASE_URL=$OLLAMA_BASE_URL" \
      -e "BOOTSTRAP_ADMIN_ENABLED=$BOOTSTRAP_ADMIN_ENABLED" \
      -e "BOOTSTRAP_ADMIN_USERNAME=$BOOTSTRAP_ADMIN_USERNAME" \
      -e "BOOTSTRAP_ADMIN_PASSWORD=$BOOTSTRAP_ADMIN_PASSWORD" \
      -e "BOOTSTRAP_ADMIN_OUTPUT_FILE=$BOOTSTRAP_ADMIN_OUTPUT_FILE" \
      -v "${DATA_DIR_RESOLVED}:/app/data" \
      "$IMAGE_NAME" >/dev/null
  fi
fi

echo "Esperando a que la API responda..."
if ! wait_api_health "$HOST_PORT" "$WAIT_TIMEOUT_SEC"; then
  echo "La API no quedo lista dentro de ${WAIT_TIMEOUT_SEC} segundos." >&2
  exit 1
fi

if [[ "$BOOTSTRAP_ADMIN_OUTPUT_FILE" = /* ]]; then
  credential_path="$BOOTSTRAP_ADMIN_OUTPUT_FILE"
else
  credential_path="$REPO_ROOT/$BOOTSTRAP_ADMIN_OUTPUT_FILE"
fi

echo "API lista en http://localhost:${HOST_PORT}"
echo "Login: http://localhost:${HOST_PORT}/login"
echo "Setup: http://localhost:${HOST_PORT}/setup"
echo "Credenciales bootstrap: $credential_path"
