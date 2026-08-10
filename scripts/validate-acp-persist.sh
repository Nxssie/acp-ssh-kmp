#!/usr/bin/env bash
# Validación de Fase B: arranque persistente del proceso ACP (FIFOs + `setsid`)
# sobre el sshd de prueba. Round-trip NDJSON, desconexión simulada (cierre de
# los canales exec) y reconexión confirmando que el proceso remoto sobrevivió
# sin perder mensajes.
#
# Uso: scripts/validate-acp-persist.sh [RUN_DIR]
# Requiere: un sshd de prueba con key auth (ver scripts/setup-sshd.sh).
set -euo pipefail

cd "$(dirname "$0")/.."

SSHD_DIR="${SSHD_DIR:-$HOME/sshd-test}"
HOST="127.0.0.1"
PORT="${PORT:-2223}"
USER="${SSH_USER:-$(id -un)}"
RUN_DIR="${1:-/tmp/acp-ssh-kmp-validate}"

if [[ ! -f "$SSHD_DIR/client_key" ]]; then
    echo "No se encontró la clave de prueba en $SSHD_DIR. Ejecuta primero: scripts/setup-sshd.sh" >&2
    exit 1
fi

if ! command -v java > /dev/null && [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$HOME/.local/share/mise/installs/java/25.0.2"
fi
./gradlew :desktop:run \
    --args="--test-acp-persist --host $HOST --port $PORT --user $USER --key $SSHD_DIR/client_key --known-hosts $SSHD_DIR/known_hosts --run-dir $RUN_DIR" \
    --no-daemon
