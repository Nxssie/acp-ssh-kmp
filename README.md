# acp-ssh-kmp

Cliente Android (Kotlin Multiplatform) que **gestiona una terminal SSH interactiva**: conecta a
tu servidor, abre un shell con PTY (`xterm-256color`) y renderiza el TUI (tmux, `claude`, …) en
Compose. Pensado para manejar desde el móvil un setup tipo `tmux new -As claude-terminal`.

> Originalmente el proyecto apuntaba a **ACP (Agent Client Protocol)** sobre SSH (chat con agente
> por `exec` + NDJSON). Ese objetivo queda como fase futura sobre el mismo `TerminalHost`; el
> producto actual es el terminal interactivo. Detalle y desviaciones en `PLAN.md`.

## Módulos

- `common` — KMP: `androidTarget()` + `jvm("desktop")`. UI Compose compartida + **emulador de
  terminal** puro (`terminal/`) + contrato `session/TerminalHost.kt`.
- `android` — App Android (SSHJ + PTY + TOFU host keys + clave cifrada con AndroidX Security).
  APK debug: `:android:assembleDebug`.
- `desktop` — CLI de validación SSH (`--test-ssh`) + app desktop con la misma UI.

## Uso (Android)

1. Compila e instala el APK debug.
2. Introduce host, puerto, usuario y **pega tu clave privada PEM** (sin passphrase por ahora).
3. Comando remoto por defecto: `tmux new -As claude-terminal` (vacío = shell normal).
4. Primera conexión → confirma la huella del host (TOFU); se guarda cifrada.
5. Barra inferior: línea de entrada, teclas especiales (Esc, Tab, Ctrl+C/D/Z/L/U/W, flechas,
   Home/End). Rotar el móvil → window-change al remoto (tmux hace reflow).

## Stack

Kotlin 2.3.20 · AGP 8.9.0 · Compose Multiplatform 1.10.1 · Gradle 8.13 · kotlinx-io 0.9.1 ·
kotlinx-coroutines 1.11.0 · kotlinx-serialization 1.11.0 · SSHJ 0.40.0 ·
androidx.security:security-crypto 1.1.0-alpha06.

## Requisitos

- JDK 21 para ejecutar Gradle (Gradle 8.13 no soporta Java 25 como JVM de ejecución).
  `mise` con `java@21` y `JAVA_HOME` apuntando ahí.
- Android SDK con `platforms;android-35` (ruta en `local.properties` o `ANDROID_HOME`).
- En el host remoto: nada especial — cualquier shell + `tmux` si quieres el setup `claude-terminal`.

## Validación SSH (Fase 1, CLI desktop)

```bash
scripts/setup-sshd.sh        # levanta sshd de prueba local en :2223 (claves efímeras)
scripts/validate-ssh.sh      # conector SSHJ: echo hola por streams + exit status
scripts/validate-ssh.sh 'seq 1 200000 | tail -1'   # stream grande
```

Alternativa directa:

```bash
./gradlew :desktop:run --args="--test-ssh --host H --port 22 --user U \
  --key /ruta/clave --known-hosts /ruta/known_hosts --command 'echo hola'"
```
