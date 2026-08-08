#!/usr/bin/env bash
# Validación de Fase 1: conecta por SSH con SSHJ desde el CLI de desktop y
# confirma que stdout se lee por streams (sin ACP todavía).
#
# Uso: scripts/validate-ssh.sh [COMMAND]
# Requiere: un sshd de prueba con key auth (ver scripts/setup-sshd.sh).
set -euo pipefail

cd "$(dirname "$0")/.."

SSHD_DIR="${SSHD_DIR:-$HOME/sshd-test}"
HOST="127.0.0.1"
PORT="${PORT:-2223}"
USER="${SSH_USER:-$(id -un)}"
COMMAND="${1:-echo hola}"

if [[ ! -f "$SSHD_DIR/client_key" ]]; then
    echo "No se encontró la clave de prueba en $SSHD_DIR. Ejecuta primero: scripts/setup-sshd.sh" >&2
    exit 1
fi

# Gradle 9.7 corre sobre Java 25 (mise); exporta JAVA_HOME si no hay java en PATH.
if ! command -v java > /dev/null && [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$HOME/.local/share/mise/installs/java/25.0.2"
fi
./gradlew :desktop:run \
    --args="--test-ssh --host $HOST --port $PORT --user $USER --key $SSHD_DIR/client_key --known-hosts $SSHD_DIR/known_hosts --command '$COMMAND'" \
    --no-daemon
