#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME="apiasistente_mysql"
USE_EXISTING_CONTAINER=""
NETWORK_NAME="apiasistente_net"
VOLUME_NAME="apiasistente_mysql_data"
MYSQL_VERSION="8.4"
HOST_PORT="3306"
DB_NAME="apiasistente_db"
DB_USER="apiuser"
DB_PASSWORD="apipassword"
ROOT_PASSWORD="rootpassword"
WAIT_TIMEOUT_SEC="180"
RECREATE=0
SKIP_DB_INIT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --container-name) CONTAINER_NAME="${2:-}"; shift 2 ;;
    --use-existing-container) USE_EXISTING_CONTAINER="${2:-}"; shift 2 ;;
    --network-name) NETWORK_NAME="${2:-}"; shift 2 ;;
    --volume-name) VOLUME_NAME="${2:-}"; shift 2 ;;
    --mysql-version) MYSQL_VERSION="${2:-}"; shift 2 ;;
    --host-port) HOST_PORT="${2:-}"; shift 2 ;;
    --db-name) DB_NAME="${2:-}"; shift 2 ;;
    --db-user) DB_USER="${2:-}"; shift 2 ;;
    --db-password) DB_PASSWORD="${2:-}"; shift 2 ;;
    --root-password) ROOT_PASSWORD="${2:-}"; shift 2 ;;
    --wait-timeout-sec) WAIT_TIMEOUT_SEC="${2:-}"; shift 2 ;;
    --recreate) RECREATE=1; shift ;;
    --skip-db-init) SKIP_DB_INIT=1; shift ;;
    *)
      echo "Parametro no soportado: $1" >&2
      exit 1
      ;;
  esac
done

warn_line() {
  printf '[WARN] %s\n' "$1"
}

docker_cmd() {
  docker "$@"
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

ensure_volume() {
  local name="$1"
  if ! docker volume inspect "$name" >/dev/null 2>&1; then
    echo "Creando volumen Docker '$name'..."
    docker_cmd volume create "$name" >/dev/null
  else
    echo "Volumen Docker '$name' ya existe."
  fi
}

container_exists() {
  local name="$1"
  docker container inspect "$name" >/dev/null 2>&1
}

ensure_container_running() {
  local name="$1"
  if ! container_exists "$name"; then
    echo "No existe el contenedor '$name'." >&2
    exit 1
  fi
  local state
  state="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || true)"
  if [[ "$state" != "running" ]]; then
    echo "Iniciando contenedor existente '$name'..."
    docker_cmd start "$name" >/dev/null
  fi
}

connect_to_network() {
  local container="$1"
  local network="$2"
  local output
  if output="$(docker network connect "$network" "$container" 2>&1)"; then
    return
  fi
  if [[ "$output" == *"already exists"* ]]; then
    return
  fi
  echo "No se pudo conectar '$container' a la red '$network': $output" >&2
  exit 1
}

get_container_health_or_state() {
  local name="$1"
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null | tr '[:upper:]' '[:lower:]'
}

wait_container_ready() {
  local name="$1"
  local timeout="$2"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    local status
    status="$(get_container_health_or_state "$name")"
    if [[ "$status" == "healthy" || "$status" == "running" ]]; then
      return 0
    fi
    sleep 3
  done
  echo "Contenedor '$name' no alcanzo estado listo dentro de $timeout segundos." >&2
  exit 1
}

ensure_database_access() {
  local name="$1"
  local ignore_errors="$2"
  local esc_db
  esc_db="$(printf '%s' "$DB_NAME" | tr -cd '[:alnum:]_')"
  if [[ -z "$esc_db" ]]; then
    echo "Nombre de base de datos invalido: '$DB_NAME'" >&2
    exit 1
  fi
  local esc_user esc_pass sql
  esc_user="${DB_USER//\'/\'\'}"
  esc_pass="${DB_PASSWORD//\'/\'\'}"
  sql="CREATE DATABASE IF NOT EXISTS \`$esc_db\`; CREATE USER IF NOT EXISTS '$esc_user'@'%' IDENTIFIED BY '$esc_pass'; GRANT ALL PRIVILEGES ON \`$esc_db\`.* TO '$esc_user'@'%'; FLUSH PRIVILEGES;"
  if ! docker_cmd exec "$name" mysql -uroot "-p${ROOT_PASSWORD}" -e "$sql" >/dev/null 2>&1; then
    if [[ "$ignore_errors" == "1" ]]; then
      warn_line "No se pudo ejecutar SQL de inicializacion en '$name'. Se asume DB/usuario ya existen."
      return 0
    fi
    echo "No se pudo inicializar DB/usuario en '$name'." >&2
    exit 1
  fi
}

echo "== Provisioning MySQL container =="
docker_cmd info >/dev/null
ensure_network "$NETWORK_NAME"

selected_container="$CONTAINER_NAME"
reuse_mode=0
if [[ -n "${USE_EXISTING_CONTAINER// }" ]]; then
  selected_container="$USE_EXISTING_CONTAINER"
  reuse_mode=1
fi

if [[ $reuse_mode -eq 1 ]]; then
  echo "Reutilizando contenedor MySQL existente '$selected_container'."
  ensure_container_running "$selected_container"
  connect_to_network "$selected_container" "$NETWORK_NAME"
  wait_container_ready "$selected_container" "$WAIT_TIMEOUT_SEC"
  if [[ $SKIP_DB_INIT -eq 0 ]]; then
    ensure_database_access "$selected_container" "1"
  fi
  echo "MySQL reutilizado en '$selected_container' (DB=$DB_NAME, USER=$DB_USER)."
  echo "MYSQL_CONTAINER=$selected_container"
  exit 0
fi

ensure_volume "$VOLUME_NAME"

if container_exists "$selected_container"; then
  if [[ $RECREATE -eq 1 ]]; then
    echo "Eliminando contenedor existente '$selected_container'..."
    docker_cmd rm -f "$selected_container" >/dev/null
  else
    ensure_container_running "$selected_container"
  fi
fi

if ! container_exists "$selected_container"; then
  echo "Creando contenedor MySQL '$selected_container'..."
  docker_cmd run -d \
    --name "$selected_container" \
    --network "$NETWORK_NAME" \
    -p "${HOST_PORT}:3306" \
    -e "MYSQL_DATABASE=$DB_NAME" \
    -e "MYSQL_USER=$DB_USER" \
    -e "MYSQL_PASSWORD=$DB_PASSWORD" \
    -e "MYSQL_ROOT_PASSWORD=$ROOT_PASSWORD" \
    --health-cmd='mysqladmin ping -h localhost -uroot -p$MYSQL_ROOT_PASSWORD --silent' \
    --health-interval "10s" \
    --health-timeout "5s" \
    --health-retries "20" \
    -v "${VOLUME_NAME}:/var/lib/mysql" \
    "mysql:${MYSQL_VERSION}" >/dev/null
fi

echo "Esperando a que MySQL este listo..."
wait_container_ready "$selected_container" "$WAIT_TIMEOUT_SEC"

if [[ $SKIP_DB_INIT -eq 0 ]]; then
  ensure_database_access "$selected_container" "0"
fi

echo "MySQL listo en localhost:${HOST_PORT} (DB=$DB_NAME, USER=$DB_USER)."
echo "Las tablas de la app se crean automaticamente al arrancar el backend Spring."
echo "MYSQL_CONTAINER=$selected_container"
