# acp-ssh-kmp

An Android and desktop client for driving a persistent, TUI-friendly SSH session — the kind of
setup where you SSH into a box and `tmux attach` to keep `claude`, `vim`, or any full-screen
terminal app alive across reconnects. Built with Kotlin Multiplatform around a from-scratch
VT100/xterm emulator, so the terminal renderer and UI are shared between platforms instead of
reimplemented per target.

> **Project history**: this started as an **ACP (Agent Client Protocol)** client over SSH — a
> JSON-RPC/NDJSON transport for talking to agents such as `claude-code-acp`. That goal is still on
> the roadmap, layered on the same `TerminalHost` abstraction, but the current focus is the
> interactive terminal itself. See [`PLAN.md`](PLAN.md) for the original design, the pivot
> rationale and the full execution log.

## Features

- Interactive SSH shell over a real PTY (`xterm-256color`), not a one-shot `exec`.
- Custom VT100/xterm emulator in pure Kotlin (`commonMain`, no JVM dependencies): alt screen,
  16/256-color and truecolor SGR, scroll regions, cursor save/restore, wrap, DECCKM/DECAWM/DECOM,
  DSR/DA/CPR, OSC window title, UTF-8.
- Live resize forwarded as a `window-change`, so `tmux`/`vim` reflow immediately.
- TOFU host-key verification on Android, with a SHA-256 fingerprint that matches
  `ssh-keygen -lf` / `ssh-keyscan` output.
- Private key encrypted at rest via AndroidX Security (`EncryptedSharedPreferences`) — pasted
  once, never written to disk unencrypted.
- One UI and one terminal renderer (Compose Multiplatform), shared by the Android app and the
  desktop build.

## Architecture

```text
commonMain (Compose UI + domain, no JVM dependencies)
├─ App.kt                     connection form / TOFU dialog / terminal, driven by TerminalHost state
├─ ui/ConnectionScreen.kt      ui/TerminalScreen.kt
├─ session/TerminalHost.kt     platform-agnostic contract: connect, send, resize, host-key decisions
└─ terminal/                   TerminalEmulator + TerminalState + TerminalColors + AnsiSequences

:android (SSHJ, raw java.io streams)
├─ MainActivity → AndroidSshTerminalHost
├─ AndroidSshTerminalHost      SSHClient + allocatePTY + startShell; reader coroutine feeds the emulator
├─ TofuHostKeyVerifier         SHA-256 fingerprint over the SSH wire-format key blob
└─ SecureStore                 EncryptedSharedPreferences: PEM key, connection config, accepted host keys

common/desktopMain (JVM)
├─ jvm/SshjSession.kt          SshSession/PtyShell over SSHJ, shared connect() used by :desktop
└─ DesktopSshTerminalHost      reuses the commonMain UI for a desktop build
```

`TerminalHost` is the seam between UI and transport: `commonMain` never depends on SSHJ directly,
only on this interface. Each platform module supplies its own implementation and wires its own
key storage / host-key policy.

## Modules

| Module    | Description |
|-----------|-------------|
| `common`  | KMP target (`androidTarget()` + `jvm("desktop")`): shared Compose UI, the terminal emulator, and the `TerminalHost` contract. |
| `android` | Android app — SSHJ, PTY allocation, TOFU host-key verification, encrypted key storage. |
| `desktop` | SSH validation CLI (`--test-ssh`) plus a desktop app reusing the `common` UI. |

## Requirements

- **JDK 25 (LTS)**, managed via `mise` — see [`.mise.toml`](.mise.toml).
- **Android SDK** with `platforms;android-36` (path via `local.properties` or `ANDROID_HOME`).
  `minSdk` 26, `targetSdk`/`compileSdk` 36.
- On the remote host: nothing special — any POSIX shell, plus `tmux` if you want the
  `claude-terminal` workflow.

## Getting started

### Android

```bash
./gradlew :android:assembleDebug
```

1. Install `android/build/outputs/apk/debug/android-debug.apk`.
2. Enter host, port, username, and paste your **private key in PEM format** (no passphrase
   support yet).
3. Default remote command: `tmux new -As claude-terminal` — leave empty for a plain shell.
4. On first connection you'll be asked to confirm the host's fingerprint (TOFU). Verify it
   out-of-band before accepting:

   ```bash
   ssh-keyscan -t ed25519 your-host | ssh-keygen -lf -
   ```

   The SHA-256 fingerprint shown in the app must match this exactly.
5. Bottom toolbar: input line, special keys (`Esc`, `Tab`, `Ctrl+C/D/Z/L/U/W`), arrows,
   `Home`/`End`. Rotating the phone triggers a `window-change`, so `tmux` reflows on the spot.

### Desktop

```bash
./gradlew :desktop:run
```

Reuses `~/.ssh/known_hosts` if present, otherwise falls back to a promiscuous verifier for local
development — there is no TOFU prompt on desktop yet.

## SSH validation harness

A standalone path for exercising the SSH layer without the UI:

```bash
scripts/setup-sshd.sh        # local test sshd on :2223, ephemeral ed25519 keys
scripts/validate-ssh.sh      # connects, runs `echo hello`, checks streams + exit status
scripts/validate-ssh.sh 'seq 1 200000 | tail -1'   # large-stream regression check
```

Or invoke the CLI directly:

```bash
./gradlew :desktop:run --args="--test-ssh --host H --port 22 --user U \
  --key /path/to/key --known-hosts /path/to/known_hosts --command 'echo hello'"
```

## Stack

| Component                          | Version        |
|-------------------------------------|----------------|
| Kotlin                              | 2.4.10         |
| Android Gradle Plugin               | 9.3.1          |
| Compose Multiplatform               | 1.11.1         |
| Gradle (wrapper)                    | 9.7.0          |
| SSHJ                                | 0.40.0         |
| kotlinx-coroutines                  | 1.11.0         |
| kotlinx-serialization               | 1.11.0         |
| kotlinx-io                          | 0.9.1          |
| androidx.security:security-crypto   | 1.1.0-alpha06  |

## Known limitations

- No passphrase-protected private keys.
- No scrollback UI (the emulator already buffers it internally, ready to wire up).
- No mouse support or DEC Special Graphics — sufficient for `tmux`/`vim`/UTF-8 box-drawing, not
  for legacy line-drawing apps.
- No TOFU prompt on desktop; it trusts `~/.ssh/known_hosts` or falls back to a promiscuous
  verifier, by design (dev-only path).

See [`PLAN.md`](PLAN.md) for the original ACP design, the pivot to an interactive terminal, and
the full execution log.
