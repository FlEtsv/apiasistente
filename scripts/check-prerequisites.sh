#!/usr/bin/env bash
set -euo pipefail

OLLAMA_BASE_URL="http://localhost:11434/api"
SKIP_OLLAMA_CHECK=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ollama-base-url)
      OLLAMA_BASE_URL="${2:-}"
      shift 2
      ;;
    --skip-ollama-check)
      SKIP_OLLAMA_CHECK=1
      shift
      ;;
    *)
      echo "Parametro no soportado: $1" >&2
      echo "Uso: $0 [--ollama-base-url URL] [--skip-ollama-check]" >&2
      exit 1
      ;;
  esac
done

ok() {
  printf '[OK] %s\n' "$1"
}

warn() {
  printf '[WARN] %s\n' "$1"
}

fail() {
  printf '[FAIL] %s\n' "$1"
}

normalize_ollama_api_base() {
  local raw="${1:-}"
  local value
  if [[ -z "${raw// }" ]]; then
    value="http://localhost:11434/api"
  else
    value="${raw%/}"
  fi
  if [[ "$value" != http://* && "$value" != https://* ]]; then
    value="http://$value"
  fi
  if [[ "$value" != */api ]]; then
    value="$value/api"
  fi
  printf '%s\n' "$value"
}

has_errors=0
printf '== Preflight: Docker + Ollama ==\n'

if ! command -v docker >/dev/null 2>&1; then
  fail "Docker CLI no esta instalado o no esta en PATH."
  has_errors=1
else
  ok "Docker CLI detectado."
fi

if [[ $has_errors -eq 0 ]]; then
  if ! docker info >/dev/null 2>&1; then
    fail "Docker daemon no responde. Abre Docker Desktop y prueba de nuevo."
    has_errors=1
  else
    ok "Docker daemon activo."
  fi
fi

if [[ $has_errors -eq 0 ]]; then
  if ! docker compose version >/dev/null 2>&1; then
    fail "Falta docker compose."
    has_errors=1
  else
    ok "docker compose disponible."
  fi
fi

if [[ $SKIP_OLLAMA_CHECK -eq 0 ]]; then
  ollama_api_base="$(normalize_ollama_api_base "$OLLAMA_BASE_URL")"
  ollama_tags_url="${ollama_api_base}/tags"
  if curl -fsS --max-time 5 "$ollama_tags_url" >/dev/null 2>&1; then
    ok "Ollama API accesible en $ollama_api_base"
  else
    fallback_url=""
    if [[ "$ollama_api_base" == *"host.docker.internal"* ]]; then
      fallback_url="${ollama_api_base/host.docker.internal/localhost}"
    fi
    if [[ -n "$fallback_url" ]] && curl -fsS --max-time 5 "${fallback_url}/tags" >/dev/null 2>&1; then
      warn "Ollama responde en ${fallback_url}. Se mantendra $ollama_api_base para uso desde contenedor."
    elif ! command -v ollama >/dev/null 2>&1; then
      fail "No se pudo conectar a Ollama ($ollama_api_base) y el comando 'ollama' no esta instalado."
      warn "Instala Ollama o usa --ollama-base-url con un servidor Ollama operativo."
      has_errors=1
    else
      fail "Comando 'ollama' detectado pero la API no responde en $ollama_api_base."
      warn "Ejecuta 'ollama serve' o ajusta --ollama-base-url."
      has_errors=1
    fi
  fi
else
  warn "Chequeo Ollama omitido por parametro."
fi

if [[ $has_errors -ne 0 ]]; then
  printf 'Preflight finalizado con errores.\n' >&2
  exit 1
fi

printf 'Preflight OK. Puedes continuar con la instalacion.\n'
