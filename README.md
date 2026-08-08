# acp-ssh-kmp

Android client (Kotlin Multiplatform) that **manages an interactive SSH terminal**: connects to
your server, opens a shell with a PTY (`xterm-256color`) and renders the TUI (tmux, `claude`, …) in
Compose. Built to drive a `tmux new -As claude-terminal`-style setup from your phone.

> The project originally targeted **ACP (Agent Client Protocol)** over SSH (agent chat via
> `exec` + NDJSON). That goal remains a future phase on top of the same `TerminalHost`; the
> current product is the interactive terminal. Details and deviations in `PLAN.md`.

## Modules

- `common` — KMP: `androidTarget()` + `jvm("desktop")`. Shared Compose UI + a pure **terminal
  emulator** (`terminal/`) + the `session/TerminalHost.kt` contract.
- `android` — Android app (SSHJ + PTY + TOFU host keys + private key encrypted with AndroidX
  Security). Debug APK: `:android:assembleDebug`.
- `desktop` — SSH validation CLI (`--test-ssh`) + desktop app sharing the same UI.

## Usage (Android)

1. Build and install the debug APK.
2. Enter host, port, username and **paste your private key in PEM format** (no passphrase support
   yet).
3. Default remote command: `tmux new -As claude-terminal` (empty = plain shell).
4. First connection → confirm the host fingerprint (TOFU); it gets saved encrypted.
5. Bottom bar: input line, special keys (Esc, Tab, Ctrl+C/D/Z/L/U/W, arrows, Home/End). Rotating
   the phone sends a window-change to the remote end (tmux reflows).

## Stack

Kotlin 2.3.20 · AGP 8.9.0 · Compose Multiplatform 1.10.1 · Gradle 8.13 · kotlinx-io 0.9.1 ·
kotlinx-coroutines 1.11.0 · kotlinx-serialization 1.11.0 · SSHJ 0.40.0 ·
androidx.security:security-crypto 1.1.0-alpha06.

## Requirements

- JDK 21 to run Gradle (Gradle 8.13 doesn't support Java 25 as the execution JVM).
  `mise` with `java@21` and `JAVA_HOME` pointing to it.
- Android SDK with `platforms;android-35` (path in `local.properties` or `ANDROID_HOME`).
- On the remote host: nothing special — any shell + `tmux` if you want the `claude-terminal`
  setup.

## SSH validation (Phase 1, desktop CLI)

```bash
scripts/setup-sshd.sh        # starts a local test sshd on :2223 (ephemeral keys)
scripts/validate-ssh.sh      # SSHJ connector: echo hello over streams + exit status
scripts/validate-ssh.sh 'seq 1 200000 | tail -1'   # large stream
```

Direct alternative:

```bash
./gradlew :desktop:run --args="--test-ssh --host H --port 22 --user U \
  --key /path/to/key --known-hosts /path/to/known_hosts --command 'echo hello'"
```
