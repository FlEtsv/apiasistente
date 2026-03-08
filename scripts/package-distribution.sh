#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="dist"
BUNDLE_NAME="apiasistente-installer"
SKIP_TESTS=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    --bundle-name) BUNDLE_NAME="${2:-}"; shift 2 ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    *)
      echo "Parametro no soportado: $1" >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

resolve_bootjar() {
  local libs_dir="$REPO_ROOT/build/libs"
  if [[ ! -d "$libs_dir" ]]; then
    echo "No existe build/libs. Ejecuta bootJar antes." >&2
    exit 1
  fi
  local jar
  jar="$(find "$libs_dir" -maxdepth 1 -type f -name "*.jar" ! -name "*-plain.jar" | xargs ls -S 2>/dev/null | head -n 1 || true)"
  if [[ -z "${jar:-}" ]]; then
    echo "No se encontro jar ejecutable en build/libs." >&2
    exit 1
  fi
  printf '%s\n' "$jar"
}

pushd "$REPO_ROOT" >/dev/null
trap 'popd >/dev/null' EXIT

if [[ $SKIP_TESTS -eq 0 ]]; then
  echo "Ejecutando tests..."
  ./gradlew test
fi

echo "Generando bootJar..."
./gradlew bootJar

JAR_PATH="$(resolve_bootjar)"
OUT_ROOT="$REPO_ROOT/$OUTPUT_DIR"
BUNDLE_DIR="$OUT_ROOT/$BUNDLE_NAME"
APP_DIR="$BUNDLE_DIR/app"
SCRIPTS_DIR="$BUNDLE_DIR/scripts"
DOCS_DIR="$BUNDLE_DIR/docs"

rm -rf "$BUNDLE_DIR"
mkdir -p "$APP_DIR" "$SCRIPTS_DIR" "$DOCS_DIR"

cp "$JAR_PATH" "$APP_DIR/apiasistente.jar"
cp "$REPO_ROOT/scripts/check-prerequisites.sh" "$SCRIPTS_DIR/"
cp "$REPO_ROOT/scripts/run-mysql-container.sh" "$SCRIPTS_DIR/"
cp "$REPO_ROOT/scripts/run-app-container.sh" "$SCRIPTS_DIR/"
cp "$REPO_ROOT/scripts/install-guided.sh" "$SCRIPTS_DIR/"
cp "$REPO_ROOT/scripts/install-guided.command" "$SCRIPTS_DIR/"
cp "$REPO_ROOT/scripts/activate-observability-stack.sh" "$SCRIPTS_DIR/"
cp "$REPO_ROOT/docs/installation-architecture.md" "$DOCS_DIR/"
cp "$REPO_ROOT/README.md" "$BUNDLE_DIR/README.md"

chmod +x "$SCRIPTS_DIR"/*.sh "$SCRIPTS_DIR"/*.command

cat > "$BUNDLE_DIR/QUICKSTART.txt" <<EOF
ApiAsistente - Instalacion rapida (macOS/Linux)

1) Instala Docker Desktop y Ollama.
2) En terminal ejecuta:
   chmod +x ./scripts/*.sh ./scripts/*.command
   ./scripts/install-guided.sh

Despues entra en http://localhost:8082/login y completa /setup.
EOF

mkdir -p "$OUT_ROOT"
ZIP_PATH="$OUT_ROOT/$BUNDLE_NAME.zip"
rm -f "$ZIP_PATH"
(cd "$BUNDLE_DIR" && zip -qr "$ZIP_PATH" .)

echo "Bundle generado:"
echo " - Carpeta: $BUNDLE_DIR"
echo " - ZIP: $ZIP_PATH"
