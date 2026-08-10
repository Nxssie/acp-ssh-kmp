#!/usr/bin/env bash
# Validación de Fases C/D: flujo completo del cliente ACP (arranque persistente,
# initialize, session/new, prompt con streaming y request_permission) contra el
# sshd de prueba usando un agente fake que habla NDJSON — no requiere el binario
# real instalado en el servidor.
#
# Uso: scripts/validate-acp-client.sh [RUN_DIR]
# Requiere: un sshd de prueba con key auth (ver scripts/setup-sshd.sh).
set -euo pipefail

cd "$(dirname "$0")/.."

SSHD_DIR="${SSHD_DIR:-$HOME/sshd-test}"
HOST="127.0.0.1"
PORT="${PORT:-2223}"
USER="${SSH_USER:-$(id -un)}"
RUN_DIR="${1:-/tmp/acp-ssh-kmp-client-validate}"

if [[ ! -f "$SSHD_DIR/client_key" ]]; then
    echo "No se encontró la clave de prueba en $SSHD_DIR. Ejecuta primero: scripts/setup-sshd.sh" >&2
    exit 1
fi

if ! command -v java > /dev/null && [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$HOME/.local/share/mise/installs/java/25.0.2"
fi
./gradlew :desktop:run \
    --args="--test-acp-client --host $HOST --port $PORT --user $USER --key $SSHD_DIR/client_key --known-hosts $SSHD_DIR/known_hosts --run-dir $RUN_DIR" \
    --no-daemon
