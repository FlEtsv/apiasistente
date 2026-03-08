#!/usr/bin/env bash
set -euo pipefail

NON_INTERACTIVE=0
OLLAMA_BASE_URL="http://host.docker.internal:11434/api"
APP_PORT="8082"
MYSQL_PORT="3306"
DB_NAME="apiasistente_db"
DB_USER="apiuser"
DB_PASSWORD="apipassword"
ROOT_PASSWORD="rootpassword"
BOOTSTRAP_ADMIN_USERNAME="admin"
BOOTSTRAP_ADMIN_PASSWORD=""
EXISTING_MYSQL_CONTAINER=""
SKIP_OLLAMA_CHECK=0
RECREATE_APP_CONTAINER=0
RECREATE_MYSQL_CONTAINER=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --non-interactive) NON_INTERACTIVE=1; shift ;;
    --ollama-base-url) OLLAMA_BASE_URL="${2:-}"; shift 2 ;;
    --app-port) APP_PORT="${2:-}"; shift 2 ;;
    --mysql-port) MYSQL_PORT="${2:-}"; shift 2 ;;
    --db-name) DB_NAME="${2:-}"; shift 2 ;;
    --db-user) DB_USER="${2:-}"; shift 2 ;;
    --db-password) DB_PASSWORD="${2:-}"; shift 2 ;;
    --root-password) ROOT_PASSWORD="${2:-}"; shift 2 ;;
    --bootstrap-admin-username) BOOTSTRAP_ADMIN_USERNAME="${2:-}"; shift 2 ;;
    --bootstrap-admin-password) BOOTSTRAP_ADMIN_PASSWORD="${2:-}"; shift 2 ;;
    --existing-mysql-container) EXISTING_MYSQL_CONTAINER="${2:-}"; shift 2 ;;
    --skip-ollama-check) SKIP_OLLAMA_CHECK=1; shift ;;
    --recreate-app-container) RECREATE_APP_CONTAINER=1; shift ;;
    --recreate-mysql-container) RECREATE_MYSQL_CONTAINER=1; shift ;;
    *)
      echo "Parametro no soportado: $1" >&2
      exit 1
      ;;
  esac
done

read_with_default() {
  local label="$1"
  local default_value="$2"
  local value=""
  read -r -p "$label [$default_value]: " value
  if [[ -z "${value// }" ]]; then
    printf '%s\n' "$default_value"
  else
    printf '%s\n' "$value"
  fi
}

to_int_or_default() {
  local raw="$1"
  local default_value="$2"
  if [[ "$raw" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$raw"
  else
    printf '%s\n' "$default_value"
  fi
}

save_install_env() {
  local path="$1"
  cat > "$path" <<EOF
OLLAMA_BASE_URL=$OLLAMA_BASE_URL
MYSQL_PORT=$MYSQL_PORT
MYSQL_DB=$DB_NAME
MYSQL_USER=$DB_USER
MYSQL_PASSWORD=$DB_PASSWORD
MYSQL_CONTAINER=$RESOLVED_MYSQL_CONTAINER
BOOTSTRAP_ADMIN_USERNAME=$BOOTSTRAP_ADMIN_USERNAME
BOOTSTRAP_ADMIN_PASSWORD=$BOOTSTRAP_ADMIN_PASSWORD
APP_PORT=$APP_PORT
EOF
}

mapfile_candidates() {
  docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}' 2>/dev/null || true
}

select_mysql_container() {
  local preferred="$1"
  if [[ -n "${preferred// }" ]]; then
    printf '%s\n' "$preferred"
    return 0
  fi

  local rows
  rows="$(mapfile_candidates)"
  local -a names=()
  while IFS='|' read -r name image status; do
    [[ -z "${name:-}" ]] && continue
    if [[ "${image,,}" == *mysql* || "${image,,}" == *mariadb* ]]; then
      names+=("$name")
    fi
  done <<< "$rows"

  if [[ ${#names[@]} -eq 0 ]]; then
    printf '\n'
    return 0
  fi

  for n in "${names[@]}"; do
    if [[ "$n" == "apiasistente_mysql" ]]; then
      printf '%s\n' "$n"
      return 0
    fi
  done

  if [[ ${#names[@]} -eq 1 ]]; then
    printf '%s\n' "${names[0]}"
    return 0
  fi

  if [[ $NON_INTERACTIVE -eq 1 ]]; then
    echo "Se detectaron varios contenedores MySQL. Usa --existing-mysql-container para elegir uno." >&2
    exit 1
  fi

  echo "Se detectaron varios contenedores MySQL/MariaDB:"
  for i in "${!names[@]}"; do
    echo "  [$i] ${names[$i]}"
  done
  local answer=""
  read -r -p "Elige indice para reutilizar o Enter para crear apiasistente_mysql: " answer
  if [[ -z "${answer// }" ]]; then
    printf '\n'
    return 0
  fi
  if ! [[ "$answer" =~ ^[0-9]+$ ]] || (( answer < 0 || answer >= ${#names[@]} )); then
    echo "Indice invalido: $answer" >&2
    exit 1
  fi
  printf '%s\n' "${names[$answer]}"
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CHECK_SCRIPT="$SCRIPT_DIR/check-prerequisites.sh"
MYSQL_SCRIPT="$SCRIPT_DIR/run-mysql-container.sh"
APP_SCRIPT="$SCRIPT_DIR/run-app-container.sh"

if [[ $NON_INTERACTIVE -eq 0 ]]; then
  echo "== Instalacion guiada ApiAsistente (macOS/Linux) =="
  echo "Pulsa Enter para aceptar cada valor por defecto."
  OLLAMA_BASE_URL="$(read_with_default "Ollama base URL" "$OLLAMA_BASE_URL")"
  APP_PORT="$(to_int_or_default "$(read_with_default "Puerto web de la app" "$APP_PORT")" "$APP_PORT")"
  MYSQL_PORT="$(to_int_or_default "$(read_with_default "Puerto host de MySQL" "$MYSQL_PORT")" "$MYSQL_PORT")"
  DB_NAME="$(read_with_default "Nombre de base de datos" "$DB_NAME")"
  DB_USER="$(read_with_default "Usuario MySQL de la app" "$DB_USER")"
  DB_PASSWORD="$(read_with_default "Password MySQL de la app" "$DB_PASSWORD")"
  ROOT_PASSWORD="$(read_with_default "Password root MySQL (si aplica)" "$ROOT_PASSWORD")"
  BOOTSTRAP_ADMIN_USERNAME="$(read_with_default "Usuario admin bootstrap" "$BOOTSTRAP_ADMIN_USERNAME")"
  BOOTSTRAP_ADMIN_PASSWORD="$(read_with_default "Password admin bootstrap (vacio = auto)" "$BOOTSTRAP_ADMIN_PASSWORD")"
fi

echo "1) Ejecutando preflight..."
check_args=(--ollama-base-url "$OLLAMA_BASE_URL")
if [[ $SKIP_OLLAMA_CHECK -eq 1 ]]; then
  check_args+=(--skip-ollama-check)
fi
"$CHECK_SCRIPT" "${check_args[@]}"

RESOLVED_MYSQL_CONTAINER="$(select_mysql_container "$EXISTING_MYSQL_CONTAINER")"
reuse_existing_mysql=0
if [[ -n "${RESOLVED_MYSQL_CONTAINER// }" ]]; then
  reuse_existing_mysql=1
fi

if [[ $reuse_existing_mysql -eq 1 ]]; then
  echo "Se reutilizara el contenedor MySQL existente: $RESOLVED_MYSQL_CONTAINER"
else
  RESOLVED_MYSQL_CONTAINER="apiasistente_mysql"
  echo "No se detecto contenedor MySQL reutilizable; se creara '$RESOLVED_MYSQL_CONTAINER'."
fi

echo "2) Provisionando MySQL..."
if [[ $reuse_existing_mysql -eq 1 ]]; then
  "$MYSQL_SCRIPT" \
    --use-existing-container "$RESOLVED_MYSQL_CONTAINER" \
    --db-name "$DB_NAME" \
    --db-user "$DB_USER" \
    --db-password "$DB_PASSWORD" \
    --root-password "$ROOT_PASSWORD" \
    --skip-db-init
else
  mysql_args=(
    --container-name "$RESOLVED_MYSQL_CONTAINER"
    --host-port "$MYSQL_PORT"
    --db-name "$DB_NAME"
    --db-user "$DB_USER"
    --db-password "$DB_PASSWORD"
    --root-password "$ROOT_PASSWORD"
  )
  if [[ $RECREATE_MYSQL_CONTAINER -eq 1 ]]; then
    mysql_args+=(--recreate)
  fi
  "$MYSQL_SCRIPT" "${mysql_args[@]}"
fi

ENV_PATH="$REPO_ROOT/.install.env"
save_install_env "$ENV_PATH"
echo "Configuracion guardada en $ENV_PATH"

echo "3) Provisionando API..."
app_args=(
  --host-port "$APP_PORT"
  --ollama-base-url "$OLLAMA_BASE_URL"
  --mysql-host "$RESOLVED_MYSQL_CONTAINER"
  --mysql-port "3306"
  --db-name "$DB_NAME"
  --db-user "$DB_USER"
  --db-password "$DB_PASSWORD"
  --bootstrap-admin-username "$BOOTSTRAP_ADMIN_USERNAME"
  --bootstrap-admin-password "$BOOTSTRAP_ADMIN_PASSWORD"
)
if [[ $RECREATE_APP_CONTAINER -eq 1 || $NON_INTERACTIVE -eq 0 ]]; then
  app_args+=(--recreate)
fi
"$APP_SCRIPT" "${app_args[@]}"

echo
echo "Instalacion completada."
echo "Siguiente paso: entrar en http://localhost:${APP_PORT}/login y despues completar /setup."
echo "Nota: el scraper es opcional. Puedes dejarlo desactivado y activar/integrar luego."
