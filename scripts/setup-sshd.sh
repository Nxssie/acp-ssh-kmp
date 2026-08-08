#!/usr/bin/env bash
# Levanta un sshd de prueba local (puerto 2223) con un par de claves efímero,
# para validar la capa SSH sin depender de un host externo.
set -euo pipefail

SSHD_DIR="${SSHD_DIR:-$HOME/sshd-test}"
PORT="${PORT:-2223}"

mkdir -p "$SSHD_DIR"
cd "$SSHD_DIR"

if [[ ! -f hostkey ]]; then
    ssh-keygen -t ed25519 -f hostkey -N '' -q
fi
if [[ ! -f client_key ]]; then
    ssh-keygen -t ed25519 -f client_key -N '' -q
fi

touch authorized_keys
if ! grep -q "$(cat client_key.pub)" authorized_keys 2>/dev/null; then
    cat client_key.pub >> authorized_keys
fi
printf '[127.0.0.1]:%s %s\n' "$PORT" "$(cat hostkey.pub)" > known_hosts

cat > sshd_config <<EOF
Port $PORT
ListenAddress 127.0.0.1
HostKey $SSHD_DIR/hostkey
UsePAM no
PasswordAuthentication yes
PermitRootLogin no
AllowUsers $USER
PidFile $SSHD_DIR/sshd.pid
LogLevel VERBOSE
AuthorizedKeysFile $SSHD_DIR/authorized_keys
StrictModes no
EOF

# En foreground (-D) para que funcione dentro de contenedores sin root.
if pgrep -f "sshd.*sshd_config" > /dev/null; then
    echo "sshd de prueba ya en marcha (revisa $SSHD_DIR/sshd.out)."
else
    nohup /usr/sbin/sshd -D -f "$SSHD_DIR/sshd_config" -e > "$SSHD_DIR/sshd.out" 2>&1 &
    sleep 1
    echo "sshd de prueba escuchando en 127.0.0.1:$PORT"
fi
