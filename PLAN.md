# Plan — Android (KMP) client for ACP over SSH

> Status: initial draft for GLM to flesh out. DeepSeek executes via `pi`.
> No code yet — only architecture, phases, and open decisions.

## Goal

Android app (Kotlin Multiplatform, Android-first target) that:
1. Opens an SSH session against a remote host.
2. Launches there an agent that speaks **ACP (Agent Client Protocol)** — e.g. `claude-code-acp`,
   the Gemini CLI adapter, or any binary that exposes ACP over stdio.
3. Speaks the ACP protocol (JSON-RPC 2.0, newline-delimited) over the stdin/stdout
   streams of the SSH `exec` channel.
4. Renders the session in UI (Compose Multiplatform): chat, tool calls, diffs, permission
   requests.

## Why KMP and not pure Android

With a single Android target, KMP adds the `expect`/`actual` layer with no real benefit — the
Android target already runs on the JVM and uses the same libraries. **It's only worth it if the
real plan is to share this client with iOS/desktop later.** If not, consider dropping down to
native Android and saving the abstraction layer. → Decision pending, see "Open questions".

## Layered architecture

```
UI (Compose Multiplatform)
   │  observes StateFlow<SessionState>
   ▼
ACP Client (domain)
   │  initialize / session.new / session.prompt / session.update (streaming)
   │  serializes JSON-RPC messages, deserializes notifications
   ▼
Transport (interface) ──actual──► SSH Channel (exec) stdin/stdout as streams
```

Key points:
- The **Transport** must be an interface (`interface AcpTransport { input: Source; output: Sink }`)
  so the ACP layer can be tested without a real SSH connection (mocking with local pipes).
- ACP's **framing** is NDJSON (one line = one JSON-RPC message). Watch out for partial reads
  of the SSH stream: you need to buffer until you find `\n`.
- The SSH session must stay alive for as long as the ACP session lasts (it's not
  request/response, it's long-lived bidirectional streaming).

## Proposed stack

- **SSH**: SSHJ (`hierynomus/sshj`) — pure JVM, maintained, supports key/agent/password auth.
  Alternative: Apache Mina SSHD (heavier, more control). JSch is ruled out (no
  active maintenance).
- **Serialization**: `kotlinx.serialization` with a sealed `JsonRpcMessage` (`Request`,
  `Response`, `Notification`) that maps to the ACP schema.
- **Concurrency/streams**: `kotlinx.coroutines` + `kotlinx.io` (or `okio`) to read/write
  over the SSH channel's streams without blocking the main thread.
- **UI**: Compose Multiplatform (Android-first).
- **Runtime/tooling**: mise for the JDK; Bun doesn't apply here (pure JVM/Kotlin project).

## Phases

### Phase 0 — Skeleton
- KMP project with `androidTarget()` target (keep `commonMain` ready to add more targets later).
- mise config (`.mise.toml`) with Java 25 LTS.
- Gradle with SSHJ + kotlinx.serialization + kotlinx.coroutines.

### Phase 1 — Validate plain SSH
- Connect to a test host, authenticate (key), `exec` a simple remote command
  (`echo hola` or similar) and confirm stdout can be read via streams.
- No ACP yet. Goal: rule out connectivity/auth issues before adding the protocol.

### Phase 2 — NDJSON framing + echo
- On the `exec` channel (now pointing at the real ACP binary, e.g. `claude-code-acp`), implement
  the NDJSON line reader and the JSON-RPC message writer.
- Send `initialize` by hand, log the raw response. Validate that framing doesn't break with
  large messages (long diffs, etc.) split across several socket reads.

### Phase 3 — ACP domain layer
- Model the lifecycle: `initialize` → `session/new` → `session/prompt` → stream of
  `session/update` (tool_call, tool_call_update, agent_message_chunk, plan, etc.) →
  `session/request_permission` (requires a user response, it's bidirectional).
- Expose this as a `Flow<SessionEvent>` consumable from the UI.

### Phase 4 — Minimal UI (chat)
- Connection screen (host, user, SSH key/agent, agent's remote command).
- Simple chat: user prompt → plain-text response stream (not rendering diffs
  yet, just placeholders "[tool_call: ...]").

### Phase 5 — Rich UI
- Diff rendering (side-by-side or unified).
- UI for `request_permission` (accept/reject/always-allow).
- Plan/step indicators if the agent emits them.

## Expected risks / friction points

- **Backpressure and channel closure**: if the remote process dies or SSH drops mid-session,
  the event `Flow` must close cleanly and the UI must be able to reconnect without duplicating
  state.
- **SSH auth on Android**: if a private key is used, where it's stored (Keystore, not in the
  clear) and how the user imports it.
- **Message size**: diffs of large files can produce huge JSON lines; the NDJSON read
  buffer must not assume a fixed max size.
- **Multiplicity of ACP agents**: `claude-code-acp`, Gemini CLI, etc. may differ in
  capabilities reported in `initialize` — the client must negotiate capabilities, not assume
  all are present.

## Open questions (for GLM to resolve/flesh out)

1. Is the final target Android only, or is Desktop/iOS really planned? This determines whether
   KMP is worth it.
2. Which concrete ACP agent will run on the remote? (`claude-code-acp` vs another) — affects
   the exact `exec` command and which capabilities to expect.
3. SSH auth by key, ssh-agent forwarding, or password? Affects the design of the
   connection screen and how credentials are stored.
4. Must the ACP session persist if the app goes to background (Android can kill the process),
   or is reconnecting each time acceptable?

## Execution status (2026-08-07, via pi)

**Phase 0 — Skeleton: COMPLETE**

- 3-module KMP project: `common` (androidTarget + jvm("desktop")), `android` (app), `desktop` (CLI + UI).
- Versions tested together: Kotlin 2.3.20, AGP 8.9.0, Compose Multiplatform 1.10.1, Gradle 8.13, kotlinx-io 0.9.1, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0, SSHJ 0.40.0.
- `.mise.toml` with Java 25.0.2 + Gradle 9.7.0. ⚠️ Gradle 8.13 (AGP 8.9) doesn't run on Java 25 (the embedded Kotlin compiler fails to parse the version); it runs on JDK 21 (`JAVA_HOME`).
- Domain layer in `commonMain`: `SshConnectionConfig`, `SshSession`/`ExecChannel` (interface with kotlinx-io `Source`/`Sink`, ready for the Phase 2 NDJSON framing).
- SSHJ implementation in `desktopMain` (jvm): `SshjConnect` + `SshjSession`/`SshjExecChannel`. Host key verified against a `known_hosts` file (secure default: without a verifier the connection fails).
- Android app (`:android:assembleDebug`) and placeholder Compose UI compiling (debug APK 9.1 MB).

**Phase 1 — Validate plain SSH: COMPLETE**

- `scripts/setup-sshd.sh` spins up a local test sshd (127.0.0.1:2223) with an ephemeral ed25519 key pair and `known_hosts`.
- `scripts/validate-ssh.sh` runs the desktop CLI (`:desktop:run --test-ssh`) against it. Results:
  - `echo hola` → `exit=0`, `stdout=hola` (ed25519 key auth + known_hosts OK).
  - `seq 1 200000 | tail -1` → `stdout=200000` (large stream read in chunks without losing data).
  - `echo oops >&2 && exit 3` → `exit=3`, `stderr=oops` (exit status propagated, stderr drained in parallel).
- SSHJ note: modern `-----BEGIN OPENSSH PRIVATE KEY-----` keys (ed25519) require `OpenSSHKeyV1KeyFile`; the classic `OpenSSHKeyFile` falls back to PKCS8 and fails. `SshjConnect.keyProvider()` picks based on the header.

## Next step

GLM: flesh out Phase 2 and 3 (the exact NDJSON framing and the ACP protocol's data model,
including the `session/update` variants that must be supported in v1).
DeepSeek (via `pi`): once fleshed out, execute Phase 2 (NDJSON framing + `initialize` by hand
against a real ACP binary). Phase 2 can already be built on top of `ExecChannel` without touching the SSH layer.

## Product pivot (2026-08-08): interactive SSH terminal

The user's actual goal is to **manage a terminal like the `claude-terminal` setup** (SSH +
`claude`/tmux), not an ACP chat. Pivoting to an **interactive SSH client**:

- **PTY shell** (`xterm-256color`) via SSHJ `allocatePTY` + `startShell`, with `window-change`
  on resize (unlike `exec`, which doesn't expose `changeWindowDimensions`).
- **Custom VT100/xterm emulator** in `commonMain` (pure Kotlin, no JVM deps): a subset
  sufficient for TUIs (tmux, claude, vim-lite): alt screen, cursor, SGR 16/256/truecolor,
  scroll region, save/restore cursor, wrap, DECCKM/DECAWM/DECOM, DSR/DA/CPR/window-size,
  OSC title, UTF-8.
- **TOFU** for host keys on Android (`EncryptedSharedPreferences`); desktop uses `known_hosts`
  or a promiscuous verifier (dev).
- **Private key** pasted as PEM and stored encrypted (AndroidX Security). No files.

### Resulting architecture

```
commonMain (UI + domain, no JVM deps)
  App.kt ── App(host: TerminalHost) ── states: form / connecting / TOFU dialog / terminal
  ui/ConnectionScreen.kt   ui/TerminalScreen.kt
  session/TerminalHost.kt  (interface: connection, screen, terminal, send/resize/…)
  terminal/                TerminalEmulator + TerminalState + TerminalColors + AnsiSequences
  SshSession.kt            + PtyShell (openShell with PTY)  · SshConnectionConfig.Auth.KeyData

:android (app, SSHJ + raw Java streams)
  MainActivity  → AndroidSshTerminalHost(context)
  AndroidSshTerminalHost: SSHClient + allocatePTY + startShell; reader coroutine → emulator
  TofuHostKeyVerifier:   SHA-256 fingerprint; first time → waits for user decision
  SecureStore:          EncryptedSharedPreferences (PEM, config, hostkeys)

common/desktopMain (JVM)
  jvm/SshjSession.kt     + openShell()/SshjPtyShell · KeyData · connect(knownHosts = null)
  DesktopSshTerminalHost (desktop App reuses the same UI)
```

### Deviations from the original plan (documented)

1. `PtyShell` does **not** take a `command`: the initial command is written to `stdin` after
   connecting (this way `resize` always works; an `exec` channel doesn't expose window-change).
2. The Android host does **not** use kotlinx-io `Source`/`Sink` nor the common `SshSession`
   interface: it reads Java's `InputStream` directly (avoids doubts about the KMP variant of
   kotlinx-io on Android).
3. The key bar uses **fixed Ctrl+letter** shortcuts (C/D/Z/L/U/W) instead of a toggling "Ctrl
   mode": more usable on mobile; the arrow keys/Home/End do respect DECCKM at keypress time.
4. No scrollback in the UI (the emulator accumulates it in `TerminalBuffer.scrollback`, ready for
   the future); no mouse support or DEC Special Graphics (tmux uses UTF-8 box-drawing).
5. Passphrase-protected keys not supported (same limitation as desktop).

### Manual smoke test (mobile)

1. `:android:assembleDebug` (outside this flow, once) → install `android-debug.apk`.
2. Your SSH server's host/user, paste the ed25519 key, command `tmux new -As claude-terminal`.
3. TOFU: compare the shown fingerprint against `ssh-keyscan -t ed25519 host | ssh-keygen -lf -`.
4. Verify `claude`'s TUI (colors, alt-screen, cursor) and arrows/Ctrl+C; rotate → tmux reflow.
5. Disconnect/reconnect → `tmux attach` resumes the session.

### Pending technical debt

- ACP (Phases 2–5 of the original plan) remains a future phase on top of the same `TerminalHost`/SSHJ.
- ~~Import a key via SAF (file picker) instead of pasting PEM.~~ Done (2026-08-09):
  export/import of `.pem` via SAF (Android) / `JFileChooser` (desktop), see `io/PemFileIo.kt`.
- Scrollback, mouse, bracketed paste (the emulator already exposes `bracketedPaste`), cursor blinking.

## Resuming ACP — chat mode plan (2026-08-09, draft)

> Status: planned, no code. Decisions closed with the user before writing this:
> agent = `claude-code-acp`; UI = current terminal **and** a new chat, selectable (it doesn't
> replace anything); ACP session persistence from v1 (survives Android killing the process in
> background / network reconnection).

### Why this isn't a styling tweak

What's shown today upon connecting (`tmux new -As claude-terminal`) is the interactive `claude`
CLI rendered character by character by the custom VT100 emulator. A real "chat-style UI" needs
the remote to speak **ACP (JSON-RPC 2.0 over NDJSON)** instead of printing a TUI — these are two
different transport protocols, not a matter of `TerminalScreen` styles. This means reactivating
Phases 2–5 of the original plan, now on top of the code that exists after the pivot to terminal.

### What's reused as-is

- SSH connection, TOFU (Android) / `known_hosts` (desktop), `SecureStore`, key generation and
  import/export: all of this lives in the auth/config layer, independent of the channel opened
  afterward.
- `TerminalConfig` (host/port/user/key) is reused; the `remoteCommand` field becomes the
  startup command for the ACP agent instead of a shell.

### What's missing / needs to be built

**1. Real `exec` channel on Android** — today `AndroidSshTerminalHost` only does
`allocatePTY`+`startShell` (see `AndroidSshTerminalHost.kt:78-80`); it never opens a PTY-less
`exec`. ACP needs raw pipes, no pty (a pty can change the buffering/output of the ACP binary,
which expects plain stdio). This capability needs to be added alongside the shell one.

**2. `RawByteChannel` in `commonMain`** — minimal abstraction common to Android (which uses
raw `java.io.InputStream`/`OutputStream`) and desktop (which uses `kotlinx.io Source/Sink` via
`SshSession.exec()`, already defined in `SshSession.kt:12` but not used yet):

```kotlin
interface RawByteChannel {
    suspend fun readChunk(buffer: ByteArray): Int  // -1 = EOF, like InputStream.read
    suspend fun write(bytes: ByteArray)
    fun close()
}
```

**3. `NdjsonFramer` in `commonMain`** (pure Kotlin, no JVM deps) — buffers on top of a
`RawByteChannel` and exposes a `Flow<String>` of complete lines + `writeMessage(json: String)`.
Testable in `commonTest` with a fake `RawByteChannel` and reads split mid-line (the same edge
case already flagged in the original plan's Phase 2).

**4. Persistence of the remote process — recommendation: do NOT use classic tmux/dtach.**
Both create a pty, and a pty can make `claude-code-acp` switch its output mode
(many CLIs detect a TTY and stop emitting clean NDJSON). A safer alternative that equally
satisfies the "survives reconnection" requirement:

```bash
mkfifo /tmp/acp-in /tmp/acp-out /tmp/acp-err
setsid nohup claude-code-acp <\ /tmp/acp-in >\ /tmp/acp-out 2>\ /tmp/acp-err &
```

The client opens two `exec` channels (same host, same multiplexed SSH connection): one runs
`cat >> /tmp/acp-in` to write, another `cat /tmp/acp-out` to read. If the client disconnects,
the remote process stays alive (decoupled from the SSH session by `setsid`); if the FIFO fills up
because no one is reading, the agent simply blocks until someone reconnects and reads again — no
messages are lost, they're just paused. If the user prefers tmux for consistency with
`claude-terminal`, it's a valid alternative but **it must first be validated** that
`claude-code-acp` doesn't change behavior under a pty before committing to that route.

**5. ACP domain model** (`commonMain`, `kotlinx.serialization`):
- Generic JSON-RPC 2.0 types (`Request`/`Response`/`Notification`), detecting the incoming
  message type by the presence of `id`/`method` (a notification has no `id`; a response has no
  `method`; an incoming request from the agent — e.g. `session/request_permission` — has both).
- `AcpClient`: `initialize()`, `session/new`, `session/prompt`, dispatch of `session/update`
  (stream) and handling of incoming requests that require a client response.
- ⚠️ The exact schema (`SessionUpdate` and its variants, the shape of `initialize`/capabilities)
  **is not verified against the real `claude-code-acp`** — before locking down types, run the
  binary by hand against the Phase A framer and log the raw messages, just like the original
  plan did in its Phase 2 ("send `initialize` by hand, log the raw response").

**6. `AcpHost` — new interface, sibling of `TerminalHost`** (doesn't replace it):

```kotlin
interface AcpHost {
    val connection: StateFlow<ConnectionState>   // reuses the same states (TOFU, error…)
    val session: StateFlow<AcpSessionState>      // messages, tool calls, plan, pending permission
    fun connect(config: TerminalConfig)
    fun sendPrompt(text: String)
    fun respondPermission(requestId: String, outcome: PermissionOutcome)
    fun disconnect()
}
```

`App.kt` needs a mode selector (Terminal | Chat) in `ConnectionScreen` that decides which host
to build and which screen to mount upon connecting (`TerminalScreen` vs the new `ChatScreen`).

**7. `ChatScreen.kt`** (`commonMain`, v1): user/agent bubbles, streaming text (appending to the
last chunk), simple tool-call cards (name + status: pending/in-progress/done/error, no diff yet),
input + send, Allow/Reject/Always-allow modal for `session/request_permission`.
Diffs, detailed plan/steps, and rich markdown are left for a later phase (Phase E).

### Execution phases

| Phase | Content | Depends on pending decisions |
|------|-----------|-----------------------------------|
| A | Real `exec` on Android + `RawByteChannel` + `NdjsonFramer` (+ commonTest tests) | No — can start now |
| B | Persistent remote startup (FIFOs+`setsid`, or tmux/dtach if validated first) | No |
| C | ACP domain model (`AcpClient`) against the real `claude-code-acp` | Yes — requires the binary installed on the test server |
| D | `AcpHost` + mode selector + `ChatScreen` v1 | Depends on C |
| E | Diffs, rich plan, expandable tool-calls, markdown | Depends on D |

### Risks

- Unverified ACP schema (see item 5) — don't commit to data types until the real binary is run.
- Pty vs pipes for persistence (see item 4) — validate before assuming tmux works the same as
  with a normal interactive CLI.
- Duplication of connection/TOFU logic between `AndroidSshTerminalHost` (terminal) and the future
  ACP host for Android — consider extracting the common parts (connect + TOFU + SecureStore) in
  Phase C/D instead of copying the class.
- Future multiplicity of ACP agents (Gemini CLI, etc.) — `initialize` capabilities may differ;
  don't assume all are present if more than one is ever supported.

### Concrete next step

Phase A depends on nothing pending — it's the natural starting point when this is resumed.

## Phase A — NDJSON transport: COMPLETE (2026-08-09)

**Content:** PTY-less `exec` channel + NDJSON framing, common to Android and desktop. No touching
UI, mode selector, `AcpHost`, or ACP types (Phases B–E remain blocked by the real binary).

- `commonMain` `acp/RawByteChannel.kt`: bidirectional byte interface (`readChunk` with -1=EOF,
  `write`, `flush`), pure common with no kotlinx.io or `expect/actual`.
- `commonMain` `acp/NdjsonFramer.kt`: `lines(): Flow<String>` buffering up to `\n`, buffer
  growing by doubling (no max line size), UTF-8 decoding only at line boundaries (a char split
  across reads stays intact), `\r\n` normalized, partial line emitted on EOF; `writeLine()`
  writes line+`\n` and flushes.
- `commonTest` `acp/NdjsonFramerTest.kt`: 12 tests with `FakeRawChannel` (split chunks, byte-by-
  byte, newline mid-chunk, a 300KB line, `\r\n`, UTF-8 split across reads, EOF with no data,
  writes). Via `:common:desktopTest`.
- `desktopMain` `acp/RawByteChannels.kt`: `ExecChannel.asRawByteChannel()` — bridge for the
  existing kotlinx.io `exec` (`SshSession.exec()`, without touching the SSH plumbing). Drains
  `stderr` in a separate coroutine (`SupervisorJob` cancelled on `close()`) so as not to block the
  channel if the remote writes there.
- `:android` `acp/SshjExecRawChannel.kt`: PTY-less `exec` over SSHJ's raw streams
  (`Session.Command` + `Dispatchers.IO`), the plain pipes ACP expects. Same background `stderr`
  draining as the desktop bridge.
- `AndroidSshTerminalHost.openExec(command)`: exposes the channel over the already-authenticated
  client (stopgap: today requires a shell `connect()`; Phase D will refactor into an exec-only
  host); closes the session if `exec()` fails.

**Verification:** compiled + tests running outside gradle (sandbox without JDK/SDK): Kotlin 2.4.10,
coroutines 1.11.0, kotlinx-io 0.9.1, sshj 0.40.0 — 12/12 tests OK; the Android adapter and the
desktop bridge compile against the same versions. Still need to run `:common:desktopTest` +
`:android:assembleDebug` on a machine with the SDK (see Phase A of the execution plan).

## Phase B — Persistent remote startup: COMPLETE (2026-08-10)

**Content:** the FIFOs+`setsid` snippet proposed by the original plan (§4) had a design bug that
was detected and fixed **before** writing Kotlin, reproducing it by hand in shell (with no SSH
involved, just to isolate the FIFO mechanics):

- **Bug found:** opening a FIFO for reading or writing blocks `open()` until the opposite end
  appears; and if the only external reader/writer disconnects, the other side gets EOF (reads)
  or EPIPE/SIGPIPE (writes) — the original snippet killed the agent on the very first reconnect,
  the exact opposite of the "survives reconnection" goal.
- **Validated fix:** keep an own `<>` (read+write) fd on each FIFO for the entire life of the
  agent, inherited through the final `exec` into the binary (it's not closed unless the agent
  explicitly closes all inherited fds, uncommon in a foreground CLI). This way the FIFO's
  reader/writer count never reaches zero: a disconnecting client only causes blocking
  (backpressure), never data loss or process death. Confirmed in plain shell: disconnection with
  no message loss, 3000 lines queued with no reader (backpressure, no crash), idempotent
  relaunch (`ALREADY_RUNNING` if the pid is alive) and recovery after killing the process (stale
  pidfile detected, FIFOs recreated, new pid).
- `stderr` goes to a normal file (`acp-err.log`), not a third FIFO: an error FIFO with no reader
  would block the redirection `open()` before the agent could even run — a deliberate deviation
  from the original plan's snippet.

**Code:**
- `commonMain` `acp/RemoteAcpProcess.kt`: `launchScript(runDir, agentCommand)` (idempotent via
  pidfile + `kill -0`), `readerCommand`/`writerCommand` (`cat acp-out` / `cat >> acp-in`),
  `shellQuote` (POSIX, single quotes). Dynamic values (`runDir`, `agentCommand`) are only
  interpolated into the header's shell variable assignments; the `setsid sh -c '...'` block is
  static text, so no quote character the user might supply can break that block (verified in
  `commonTest`).
- `commonMain` `acp/DuplexRawByteChannel.kt`: combines a read-only `RawByteChannel` and a write-
  only one (two independent SSH `exec` channels) into a bidirectional `RawByteChannel` for the
  Phase A `NdjsonFramer`.
- `commonTest`: `RemoteAcpProcessTest.kt` (quoting, script structure, isolation of the inner
  block) + `DuplexRawByteChannelTest.kt` (read/write/flush/close routing). Via `:common:desktopTest`.
- `desktop` `Main.kt --test-acp-persist`: reproduces the full scenario against the test sshd —
  starts the agent (a shell loop that echoes, since `claude-code-acp` is not installed on the
  test sshd), NDJSON round-trip, closes both `exec` channels (disconnection), reopens fresh
  channels and confirms the second round-trip with no loss. `scripts/validate-acp-persist.sh` is
  the wrapper, just like `validate-ssh.sh` in Phase 1.
- No changes to `AndroidSshTerminalHost`/`DesktopSshTerminalHost`: Phase A's `openExec`/
  `session.exec()` already suffice to open the reader/writer channels, no new per-host plumbing
  needed.

**Verification:** the FIFOs+`setsid` mechanics were run and confirmed in real shell (see above).
The Kotlin code (`RemoteAcpProcess`, `DuplexRawByteChannel`, `Main.kt`) was carefully reviewed by
hand (including the `$` escaping cases in Kotlin string templates, which caused two real
compilation bugs that were detected and fixed before continuing) but **it was not compiled or run
with `--test-acp-persist` against the real sshd**: this sandbox has no JDK/SDK at all (not even
the standalone `kotlinc` that allowed verifying Phase A outside gradle). Still pending as the next
step, on the machine with the SDK: `./gradlew :common:desktopTest` and
`scripts/validate-acp-persist.sh` against the test sshd (`scripts/setup-sshd.sh`).

## Phase C — ACP domain model (client): COMPLETE (2026-08-10)

**Content:** the full protocol layer, validated against the v1 spec of the
`agentclientprotocol/agent-client-protocol` repository (authoritative Rust source)
AND against the real **`@agentclientprotocol/claude-agent-acp` 0.66.0** adapter
run in this environment (it responds to `initialize`/`session/new` without an API key), not just
against synthetic fixtures:

- **Empirical validation before writing types:** the real adapter was run by hand
  (a Node probe over stdio) and the raw messages were captured — `initialize`
  response, `session/new` response, `session/update` with `available_commands_update`,
  a `session/prompt` error with a nonexistent session, and the arrival of responses
  **out of order** (the adapter responded to `session/prompt` before `session/new`).
  The v1 schema (snake_case `sessionUpdate` tags, `ContentBlock`, `ToolCall`,
  `ToolCallUpdate` with flattened fields, `Plan`, `PermissionOption`/`outcome`) was
  cross-checked against the spec's Rust source code (downloaded from GitHub).
- `commonMain` `acp/AcpProtocol.kt`: shared `Json` instance (ignoreUnknownKeys +
  coerceInputValues + explicitNulls=false + **encodeDefaults=true** — without this the
  params were serialized as `{}`), `parseRpc` (type detection by shape:
  notification with no id / incoming request with method+id / response with id), `RpcOut`
  (request/notification/response/error builders), `AcpPrettyJson`.
- `commonMain` `acp/AcpTypes.kt`: outgoing DTOs (`InitializeParams` with
  `clientInfo:{name,version}`, `NewSessionParams`, `PromptParams`, `SessionIdParams`,
  `PermissionOutcome.Selected/Cancelled`) and lenient incoming decoders
  (`SessionUpdate` sealed, decoded by hand with an unknown tag → `Unknown(raw)`,
  `ContentChunk`, `AcpToolCall`, `AcpToolCallUpdate`, `ToolCallContent` with `Diff`,
  `AcpPlan`/`PlanEntry`, `PermissionRequest`).
- `commonMain` `acp/AcpClient.kt`: single reader thread (resolves `CompletableDeferred`
  by id — tolerates out-of-order responses, confirmed against the real adapter),
  notifications → `Channel<SessionUpdate>`, incoming requests →
  `Channel<PermissionRequest>` (the host responds), unknown methods → JSON-RPC
  error -32601 (prevents the agent from hanging while waiting for a response), `initialize`/
  `newSession`/`prompt` (a prompt error is returned in the result, not thrown),
  `cancel` (notification), `onEof` for clean disconnection.
- `commonMain` `acp/AcpExecTransport.kt`: per-platform `exec`-only interface +
  `readAllToString` (read to EOF for short commands).
- `commonMain` `session/AcpSession.kt`: shared orchestration — starting the remote
  agent (Phase B, idempotent), remote `pwd` as the default cwd, reader/writer
  `exec` → `DuplexRawByteChannel` → `NdjsonFramer` → `AcpClient`, `initialize` +
  `session/new`.
- `commonMain` `session/AcpSessionState.kt`: pure reducer (`AcpSessionStore`) for the
  chat session — bubbles (agent/thinking/user, grouped by messageId),
  tool calls (merged by id with diffs/input/output), plan (full replacement), pending
  permission, busy, error. No UI or transport dependencies.
- `commonTest` (new): `AcpProtocolTest` (detection + builders), `AcpClientTest`
  (full handshake, prompt with interleaved updates and out-of-order responses,
  incoming permission + response, prompt error, unknown method, cancel — with a
  queue-based fake channel that the test fills in place of the "agent"), `SessionUpdateTest`
  (real captured shapes + synthetic ones, snake_case tags, unknown preserved),
  `AcpSessionStoreTest` (reducer). `MarkdownTest` and `UnifiedDiffTest` are in Phase E.

**Verification:** fully compiled outside gradle (JDK 21 + kotlinc 2.4.10 +
serialization/compose plugins, kotlinx/sshj/compose jars downloaded from Maven
Central) and **76/76 tests green**. The `initialize` request generated by the
client was verified against the real adapter (it responds with protocolVersion 1 + agentInfo).

## Phase D — ACP Host + mode selector + ChatScreen: COMPLETE (2026-08-10)

**Content:**

- `commonMain` `session/AcpHost.kt`: interface sibling to `TerminalHost` (same
  connection + TOFU states, `sendPrompt`, `respondPermission`, `cancelTurn`,
  `toggleToolCall`); `HasConnection` common to both hosts; `AcpMode` (TERMINAL/CHAT).
- `commonMain` `session/TerminalHost.kt`: `TerminalConfig` gains `acpRunDir` (relative
  to the remote home; default `.acp-ssh-kmp`) and `acpCwd` (default: remote `pwd`); the
  `remoteCommand` becomes the agent's startup command in chat mode.
- `android` `AndroidSsh.kt`: SSH connection + TOFU + keyProvider extracted from
  `AndroidSshTerminalHost` (the plan flagged that duplication as a Phase C/D risk);
  `AndroidSshTerminalHost` now uses it. `AndroidAcpHost`: connect → `AcpSession`
  → update/permission clients → `AcpSessionStore`; `onEof` → clean disconnection
  (the remote process survives). `MainActivity` builds both hosts.
- `desktopMain` `DesktopAcpHost.kt`: same pattern over `SshjConnect` + kotlinx.io
  `exec`; cancels the connection **job** (not the scope) to allow reconnections.
  `Main.kt` (desktop) builds both hosts.
- `commonMain` `App.kt`: `App(terminalHost, acpHost)` with a mode selector in
  `ConnectionScreen` (Terminal/Chat FilterChips; command label depends on mode);
  `HostKeyDialog` dispatches to the active host. `ChatScreen.kt`: user/agent/
  thinking bubbles, autoscroll, plan, expandable tool-call cards (input/output/diff),
  permission modal (agent options + cancel → cancelled outcome), input +
  send + cancel turn (■), streaming indicator.

**Verification:** full compile of common/desktop/android + UI with the Compose
plugin. Real bugs detected and fixed during verification outside gradle
(with `-jvm-target 17` and reliable error detection): `onChannelEof` called a
`suspend` function from non-suspend context in both hosts; `App.kt` called
`loadLastConfig()` on `HasConnection`, which didn't expose it (now it does); an
id race in `AcpClient.request()` (the id is now captured inside the lock);
duplicate `InlineText` declaration in ChatScreen. Still pending (machine with SDK):
`:android:assembleDebug` and the manual smoke test.

## Phase E — Diffs, plan, markdown: COMPLETE (2026-08-10)

**Content:**

- `commonMain` `markdown/Markdown.kt`: minimal, deterministic markdown parser —
  paragraphs, headings #–######, ordered/unordered lists, quotes, fenced code
  blocks (with language), separator; inline `code`, **bold**, *italics*
  and nested [links](url). Rendered in `ChatScreen` with `AnnotatedString` styles.
- `commonMain` `diff/UnifiedDiff.kt`: `diff -u`-style unified diff with **Myers
  (O(ND) in linear space, divide-and-conquer)** over lines, a depth cap
  (degrading to all-deleted/all-added on huge files), hunks with context
  and `@@ -a,b +c,d @@` headers; deletions come before additions. Colored
  rendering in `ChatScreen` (green/red/blue/gray on a dark background).
- `commonTest`: `MarkdownTest` (9) and `UnifiedDiffTest` (9: identical, new/deleted
  file, substitution, headers, two separate hunks, large insert, render, myers).

**Verification:** 76/76 tests (E's included). Real bugs found and
fixed during verification outside gradle: `encodeDefaults=false` was omitting
requests' default fields (serialized as `{}`); the diff renderer wasn't
adding the `+`/`-` prefixes; `groupValues[2]` in the ordered-list regex; the
client test had a race with the fake channel (without `\n` the framer only emitted on
EOF and killed the reader).

## Real verification with SDK and bugs found/fixed (2026-08-10)

A JDK 21 (`/tmp/toolchain/jdk21`) was found in the sandbox and it was finally possible to run
`:common:desktopTest` and both validation scripts (`validate-acp-persist.sh`,
`validate-acp-client.sh`) against the real test sshd — what Phases B/C/D
had marked as "complete" had never been run end-to-end. Running them revealed
**three real bugs**, not just an environment gap:

1. **`AcpSession.close()` hung for ~30s on every disconnection** (the same code used
   by `AndroidAcpHost.disconnect()`/`DesktopAcpHost.disconnect()` in production).
   Cause: `SshjExecChannel.close()`/`SshjExecRawChannel.close()` call SSHJ's normal
   `close()`, which blocks waiting for the remote channel's close ACK — and the
   `cat $STDOUT_FIFO` backing the reader channel never exits on its own (by
   design, to survive reconnection), so that ACK never arrives and SSHJ
   blows up with its default 30s timeout. **Fix:** `closeChannelWithTimeout()`
   in both files — the actual close runs on a daemon thread with `join(2_000)`;
   if it doesn't finish in time it's abandoned (it will only be freed once the
   SSH transport finishes disconnecting) and the caller doesn't block.
2. **Orphaned-reader race on the FIFO during reconnect.** Fixing (1) revealed
   a deeper issue: without a pty, closing the reader's `exec` channel doesn't
   reach the remote `cat` as a signal — the old process stays blocked
   reading the same FIFO as the new reader from the reconnect, and the kernel
   delivers each write to only one of the blocked readers (not to all), possibly
   losing the message if it's delivered to the orphan. Confirmed with `jstack`:
   the second round-trip of `validate-acp-persist.sh` hung waiting for data that
   never arrived. **Fix:** `readerCommand`/`writerCommand` in `RemoteAcpProcess.kt`
   now kill the previous reader/writer by PID (`acp-reader.pid`/`acp-writer.pid`
   file) before registering themselves, so there's only ever one live reader per FIFO.
3. **Reads on the `exec` channel were not cancellable.** While investigating (2) with
   `jstack`, it was found that a `withTimeout(5_000)` around an NDJSON read
   stayed blocked for over an hour without firing: `readChunk()` in
   `RawByteChannels.kt` (desktop) and `SshjExecRawChannel.kt` (Android) called
   SSHJ's blocking read without `runInterruptible`, so coroutine cancellation
   never interrupted the blocked thread. It was confirmed in SSHJ's source code
   that `ChannelInputStream.read()` does correctly convert `InterruptedException`
   into `InterruptedIOException`, so the fix was applicable: all three
   operations (`readChunk`/`write`/`flush`) now run inside
   `runInterruptible(Dispatchers.IO) { ... }` on both platforms.
4. Additional minor bug (no production impact): the fake NDJSON agent in
   `validate-acp-client.sh` (`FAKE_ACP_AGENT` in `Main.kt`) had miscounted
   braces by hand in two lines (`plan` missing a `}`, `request_permission`
   with one extra) — this corrupted the NDJSON and hung the test until its
   15s timeout.

**Verification after the fixes:** `:common:desktopTest` 100/101 (the only failure,
`TerminalEmulatorTest.wrapsAtLastColumn`, is pre-existing and unrelated to ACP — it doesn't touch
any files in this diff). `validate-acp-persist.sh` → `PASS` (round-trip 1, closing and
reopening channels, round-trip 2 after "reconnection", ~20s). `validate-acp-client.sh`
→ `PASS` (`initialize`, `session/new`, `prompt` with text streaming + tool call +
diff + plan, `request_permission` with 2 options, `stopReason=end_turn`).

## Verification still pending (Android SDK / real agent)

1. `:android:assembleDebug` + manual smoke test (Terminal/Chat selector, permissions, diffs)
   — this sandbox has no Android SDK installed (`local.properties` points to an
   `sdk.dir` that doesn't exist here); this section's fixes were reviewed by hand,
   since they're the same pattern already verified on desktop, but not compiled with AGP.
2. Against the real agent: install `claude-code-acp` on the server and point chat mode's
   `remoteCommand` at its path; verify streaming, tool calls, diffs,
   `request_permission` with claude in manual mode.

## Resuming ACP — saved profiles, managed keys and parallel tabs (2026-08-10, draft)

> Status: planned, no code. User request: don't hardcode a default command
> and allow several saved commands; the private key should not always be
> shown on screen (a select instead of a permanently visible textarea); several
> saved connection configurations; several chat tabs in parallel, which implies the host
> must support more than one connection/ACP session at a time. DeepSeek executes this afterward.

### Why this isn't an isolated UI change

Today `SecureStore` (`android/.../SecureStore.kt:29-52`) stores **a single**
configuration with fixed keys (`host`, `port`, `user`, `pem`, `command`,
`public_key`) — every `saveConfig()` overwrites the previous one, there's no list. Desktop
doesn't even have this: `DesktopAcpHost`/`DesktopSshTerminalHost` keep `lastConfig`
in an in-memory variable, lost on app close. `ConnectionScreen.kt:48,109-126`
renders the PEM key in an always-visible multiline `OutlinedTextField`, preloaded
with the secret in the clear every time the screen opens; `command` (`ConnectionScreen.kt:49-51`)
is a single free-text field with a hardcoded default per mode
(`"tmux new -As claude-terminal"` / `"claude-code-acp"`). And both `AcpHost` and
`TerminalHost` are **single-session**: `AndroidAcpHost`/`DesktopAcpHost` have
singular variables (`transport`, `acpSession`, `acpClient`, `sessionStore`) and
`connect()` calls `disconnect()` first — connecting again kills the previous
session. `App.kt:52-53` has a single `mode` and a single `active: HasConnection`,
with no concept of "several live sessions" at all. Four requests → four different
layers to touch (storage, connection UI, host/session layer, chat UI).

### What's reused as-is

- `PemFileIo.kt` (`rememberPemExporter`/`rememberPemImporter`, SAF on Android /
  `JFileChooser` on desktop): the `.pem` import/export mechanics already exist,
  they just need to be wired to a list of saved keys instead of a single
  text field.
- TOFU (`TofuHostKeyVerifier`), Ed25519 generation (`generateEd25519SshKey`),
  `AcpSession`/`AcpClient`/`RemoteAcpProcess`: none of this changes — the Phase B/D
  fix that avoids the orphaned reader via PID (`RemoteAcpProcess.kt`) is
  precisely what makes it safe to have several concurrent `runDir`s (see Phase H).

### Phase F — Multi-profile storage (pure storage, no UI)

**1. New data model** (`commonMain`, `kotlinx.serialization`):

```kotlin
data class SavedKey(val id: String, val label: String, val privateKeyPem: String, val publicKeyLine: String? = null)
data class SavedCommand(val id: String, val label: String, val command: String, val mode: AcpMode? = null)
data class ConnectionProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val keyId: String,        // reference to SavedKey.id
    val commandId: String?,   // reference to SavedCommand.id; null = empty command (default shell in Terminal mode)
    val acpRunDir: String? = null,
    val acpCwd: String? = null,
)
```

`mode: AcpMode? = null` on `SavedCommand` allows commands to be reused between
Terminal and Chat (e.g. nothing prevents saving `tmux new -As claude-terminal` and
`claude-code-acp` in the same list) or tagged for a single mode if the user wants to
keep them separate; the command selector (Phase G) filters by mode but shows a "show
all" option to reuse one from another mode.

**2. `ProfileStore` — common interface** (`commonMain`, no platform
dependencies): `listProfiles()/saveProfile()/deleteProfile()`,
`listKeys()/saveKey()/deleteKey()`, `listCommands()/saveCommand()/deleteCommand()`,
`loadLastProfileId()/setLastProfileId()` (which profile to open by default on entry).
All via `Flow`/`StateFlow` if the UI needs reactivity, or plain functions if
the UI re-reads on entering the screen (simpler, to be evaluated in Phase G).

**3. Android implementation** (`android/.../SecureStoreProfileStore.kt`, gradually
replaces `SecureStore`): same `EncryptedSharedPreferences`, but lists are
serialized as JSON (`Json.encodeToString(List<ConnectionProfile>)`) under a
single key per type (`profiles`, `keys`, `commands`) instead of loose fields —
avoids migrating the prefs schema every time a field is added. **Migration**:
on the first launch after this version, if the old `SecureStore` keys
(`host`/`user`/`pem`/`command`) exist and no profile exists yet, create a
`ConnectionProfile`+`SavedKey`+`SavedCommand` out of them (so nobody loses
their saved connection on update) and delete the old keys.

**4. Desktop implementation** (`common/src/desktopMain/.../DesktopProfileStore.kt`):
there's currently no persistence at all — use a JSON file at
`~/.config/acp-ssh-kmp/profiles.json` (permissions `600`, plain: desktop has no
AndroidX Security and isn't a target with the "another app on the same device
reads your prefs" threat that does apply on Android). Document the security
asymmetry between Android (encrypted) and desktop (plain file with user
permissions) the same way the TOFU vs `known_hosts` asymmetry is already documented.

**5. Tests** (`commonTest` for the data model + invalid `keyId`/`commandId`
reference rules; tests for the Android/desktop implementation stay
outside `commonTest`, same as `SecureStore` has no test today — evaluate whether
a JVM test for `DesktopProfileStore` is worthwhile since it is new code in desktopMain).

### Phase G — UI: managed profiles, keys and commands (replaces `ConnectionScreen`)

**Profiles screen** (new, before what's today the `ConnectionScreen`): list
of saved `ConnectionProfile`s (label + host) with "Connect" / "Edit" / "Duplicate"
/ "Delete", "New connection" button. If there are no profiles, go straight to the form
(don't force an intermediate empty screen).

**Connection form** (rewritten `ConnectionScreen`): host/port/user as free
text as today; **key**: an `ExposedDropdownMenuBox` (Material3) with saved
`SavedKey`s by label — the key is NEVER rendered on screen when choosing it
from the select, only its label; a "Manage keys" option opens a dialog with
generate/import/export/rename/delete on the list (reuses
`rememberPemExporter`/`rememberPemImporter` from Phase F); an explicit
"Show key" button (not automatic) lets the user view the selected key's
plain PEM, for cases where the user needs to copy it — deliberate
friction, not fully hidden. **Command**: same dropdown pattern over
`SavedCommand` (filtered by the current `mode`, with a "show all" option),
a "New command" option that opens a text field + label to save it, and an
**explicit** default option — "shell (default)" in Terminal mode,
"claude-code-acp (default)" in Chat mode — instead of an always
preloaded default. The default is visible in the UI and matches the fallback
of `AcpSession.DEFAULT_AGENT`; never a hidden fallback behind an option that says
"no command" (closed decision, see "Closed decisions" #5). This covers
the request for "the app not always including a default command" in the sense
that nothing is preloaded in the form: the user picks a saved command, an
explicit default, or types a new one.

**`TerminalConfig`** loses its role as the "single saved source of truth": it now gets
built at connection time from the resolved `ConnectionProfile` +
`SavedKey` + `SavedCommand` (`profile.toTerminalConfig(key, command)`),
instead of being what `SecureStore` persists directly.

### Phase H — Multi-session ACP: one SSH connection, several remote agents

**Design decision — one agent process per tab, not multiplexed by
`sessionId` over a single agent.** The protocol already carries a `sessionId` in every
`session/update` (`AcpClient.kt:171` reads `message.params` but `route()` only
passes `update`, without `sessionId`, to the single `Channel<SessionUpdate>` — so today
there's NO demux) and in theory a single `claude-code-acp` could serve several
`session/new`s at once. But that requires (a) verifying that the real agent handles
concurrent sessions well — without a real binary at hand this can't be confirmed, see
the gap already documented in "Verification still pending"/item 2 — and (b) rewriting
`AcpClient`/`AcpSessionStore` to route by `sessionId`. The alternative —
**one `AcpSession` (with its own `runDir`, hence its own FIFOs/remote process)
per tab, all over the same already-authenticated SSH connection** — doesn't depend
on that unverified assumption, isolates failures (a crashing agent doesn't affect
other tabs) and reuses unchanged the Phase B/D fix for the orphaned reader via
PID (each `runDir` is independent, no FIFO collision). Cost: one
`claude-code-acp` process per tab on the server instead of a shared one — acceptable
unless the user opens dozens of tabs at once.

**`AcpSessionManager`** (`commonMain`, new class — today this responsibility lives
mixed into `AndroidAcpHost`/`DesktopAcpHost`): keeps the SSH connection
(a shared `AcpExecTransport`/client) and a `tabId -> AcpSessionEntry` map
where each entry has its own `AcpSession`, `AcpClient`, `AcpSessionStore`
(each `runDir` is auto-generated as `${config.acpRunDir}/tab-<uuid>` unless
one is explicitly fixed, so two tabs of the same profile don't accidentally share FIFOs).
Exposes `StateFlow<Map<String, AcpSessionState>>` or a list of
`StateFlow<AcpSessionState>` per tab, `openTab(profile)`, `closeTab(tabId)`
(closes that `AcpSession` without touching the SSH connection or the other
tabs — the remote agent for THAT tab stays alive after closing the tab, the same
way it already survives a disconnect; "closing a tab" in the UI shouldn't mean
"kill the remote process" unless the user explicitly asks for it, see Phase I).

**`AcpHost` changes shape**: instead of a "single session" interface, it becomes
(or wraps) the `AcpSessionManager`. Consider whether it's worth keeping `AcpHost`
as a facade for ONE tab (to avoid breaking `App.kt`/`ChatScreen` all at once)
while `AcpSessionManager` lives underneath — an implementation decision to be
made at the time, not blocking for the rest of the plan.

**SSH connection reuse**: today `AndroidAcpHost.connect()` opens a new
`SSHClient` every time; for several tabs on the same profile, extract that
connection into something that lives as long as the `AcpSessionManager` does, not per
tab — `AndroidSsh.connect()` (already extracted in Phase D, `android/.../AndroidSsh.kt`)
already returns the bare connection, so this layer is what's missing today, not
something that needs to be rebuilt from scratch.

### Phase I — UI: parallel chat tabs

**`TabRow` (Material3)** over `ChatScreen`: one tab per session open in the
`AcpSessionManager`, "+" to open a new one (always from the same profile as the
active tab, closed decision — see "Decisions closed with the user"),
long-press or "x" button to close (leaves the remote process running, with a
separate "close and terminate agent" action to explicitly kill it),
configurable cap on simultaneous tabs (default 5, with a warning when the
limit is reached), visual indicator (dot/badge) if a background tab has
active streaming or a pending permission (so it isn't missed if the user is
looking at another tab). `ChatScreen` now receives the active tab's
`AcpSessionState` instead of reading it directly from the host.

**Deliberately out of scope for V1**: parallel **Terminal** tabs (the
user only asked for chat tabs; Terminal already has tmux for multiplexing, see
the design note from the Phase B/terminal pivot) and tabs against **different
servers** simultaneously (each tab in this plan shares the active profile's SSH
connection — opening tabs against another profile would mean another SSH connection
in parallel, ruled out for V1, see "Decisions closed with the user").

### Decisions closed with the user (2026-08-10)

The open questions from this plan were already resolved with the user; they're
fixed here so DeepSeek can execute without having to decide them again:

1. **Profile per tab: always the same as the active tab.** You cannot choose a
   profile when opening a new tab in V1 — all tabs share the single
   SSH connection of the active profile, `AcpSessionManager` doesn't manage more than one
   connection at a time. (The design doesn't block this in the future if requested later.)
2. **Closing a tab leaves the remote process running.** Same as a normal
   disconnect: reconnectable afterward, consistent with the rest of the Phase B design.
   A separate, explicit "close and terminate the remote agent" action is added
   for those who do want to kill the process.
3. **Simultaneous tab limit: configurable cap, default 5.** Warns when the
   limit is reached instead of silently blocking or allowing an
   unlimited number of `claude-code-acp` processes on the server.
4. **Copying the key from "Show key" is allowed.** The deliberate friction
   lives in the "Show" step (not automatic); once visible, copying it to
   the clipboard is a normal flow, with no additional block.
5. **The default command exists and is explicit, not hidden.** The request for
   "don't hardcode a default command" is interpreted as: nothing is preloaded in
   the form (no "always preloaded" default as today), but the dropdown's
   empty option shows the default with its name — "shell
   (default)" in Terminal, "claude-code-acp (default)" in Chat — and the
   fallback of `AcpSession.agentCommand` (`DEFAULT_AGENT = "claude-code-acp"`)
   matches what the UI announces. The error/validation option in Chat mode was
   dropped: "no command" + explicit default satisfies the request without
   breaking the "connect and go" flow.

### Concrete next step

Phase F depends on nothing pending — it starts with storage before touching
any UI, the same order the rest of this plan's phases already followed.

## Phases F–I — profiles, keys, commands and tabs: COMPLETE (2026-08-10)

**Content:** the four pending phases (multi-profile storage,
profiles/keys/commands UI, multi-tab `AcpSessionManager`, `TabRow` in
`ChatScreen`) arrived already written by DeepSeek; this closeout was the
verification outside gradle of that delivery, not a new implementation.

- `profile/Profiles.kt`/`ProfileStore.kt`: `SavedKey`/`SavedCommand`/
  `ConnectionProfile` with the fields from the design (`mode`, `keyId`,
  `commandId`, `acpRunDir`, `acpCwd`); `SecureStoreProfileStore` (Android,
  JSON per collection + migration from `SecureStore`'s loose keys) and
  `DesktopProfileStore` (JSON at `~/.config/acp-ssh-kmp/profiles.json`,
  permissions 600).
- `ui/ProfilesScreen.kt`, `ui/KeyManagerDialog.kt`, `ui/CommandManagerDialog.kt`,
  rewritten `ConnectionScreen`: key dropdown that never renders the PEM
  except via "Show", command dropdown filtered by mode with an explicit
  default matching `AcpSession.DEFAULT_AGENT`.
- `session/AcpSessionManager.kt`: one `AcpSession`/process per tab over a
  single shared SSH connection, auto-generated runDir `tab-<id>`,
  `openTab`/`closeTab`/`killTabAgent`, reconnection reopening the same
  tabIds. `AndroidAcpHost`/`DesktopAcpHost` delegate to the manager instead of
  keeping a single session.
- `ChatScreen.kt`: `ScrollableTabRow` over the manager's tabs, configurable
  limit with warning, streaming/pending-permission indicator on background
  tabs, separate action to kill the remote agent.

**Real bugs found and fixed during verification:**

1. `KeyManagerDialog.kt` didn't compile: it used `Row(...)` without importing
   `androidx.compose.foundation.layout.Row`.
2. `AcpSessionManagerTest.kt` didn't compile: four references to `tabs`/
   `connection` inside the tests were missing the `manager.` receiver
   (`manager.tabs`/`manager.connection`), and one test
   (`unexpectedEofDisconnectsEverything`) had, as the last expression of its `try`
   block, a `withTimeout {}` returning a non-`Unit` value, which JUnit4 rejects
   as "should be void".
3. **Real production bug, not just tests:** `AcpClient.close()`
   cancelled `readerJob` without silencing `onEof` first — cancelling that job
   triggers the same `finally` block as a real EOF (`framer.lines().collect`
   followed by `onEof?.invoke()`), so **any intentional close**
   (closing a tab, reconnecting) would still fire the "connection
   lost" notice regardless. With tabIds reused on reconnect (Phase H's design),
   the late `onEof` from the OLD `tab-1` session arrived after
   `entries["tab-1"]` already pointed at the NEW session, and `handleTabEof`
   disconnected the freshly opened connection — reproduced deterministically
   with `AcpSessionManagerTest.reconnectReopensSameTabsOnSameRunDirs`
   (hung waiting for 2 tabs to have a session after reconnecting; instrumented with
   a temporary watcher that confirmed `_tabs` was emptied right after
   `tab-1`'s handshake). **Fix:** `close()` sets `onEof = null` before
   cancelling the reader.
4. Minor test bug (affects only CI reliability, not production):
   `AcpSessionManagerTest.FakeAgent.reader.readChunk` blocked the real thread
   with `LinkedBlockingQueue.poll(30, SECONDS)` inside a `suspend fun`
   without `runInterruptible` — on a 2-core sandbox, with `Dispatchers.Default`
   exhausted by several tests leaving threads blocked without releasing until
   the 30s timeout, the rest of the suite (including unrelated classes
   like `AcpClientTest`) ran out of threads and everything started failing on
   timeout. Fix: wrap the `poll()` in `runInterruptible(Dispatchers.IO) { ... }`,
   the same as the production code (Android/desktop) has done since the
   "Real verification with SDK" section above.
5. `closeTabLeavesRemoteAgentAlive` checked `execLog.none { "cat acp.pid" in it }`
   to verify that closing a tab doesn't trigger a kill — but that same
   substring already appears in the idempotent startup script
   (`launchScript`, `ALREADY_RUNNING` check) of ANY tab that gets opened,
   so the assertion always failed, unrelated to `closeTab`'s actual
   behavior. Fix: compare against the exact `RemoteAcpProcess.killCommand(runDir)`
   instead of an ambiguous substring.

**Verification:** `:common:desktopTest` 123/124 across two consecutive runs
(the only failure, `TerminalEmulatorTest.wrapsAtLastColumn`, is pre-existing
and unrelated to this diff, as already documented in the "Real verification
with SDK and bugs found/fixed" section); `:desktop:compileKotlinJvm` green.
No Android SDK in this environment: `:android:assembleDebug` and the manual smoke test
(profile selector, key/command manager, several chat tabs) remain
pending on a machine with the SDK installed, same as in previous phases.

## Replanning 2026-08-11 — toward a complete app (V1 scope override)

> Status: planned with the user. **Explicit override of the closed decision #1
> from 2026-08-10** ("profile per tab: always the same") and of the
> "deliberately out of V1 scope" note from Phase I: the goal now IS
> to be able to have, in parallel tabs, ACP agents from different
> profiles (e.g. `claude-code-acp` in one, `pi-acp` in another) and also
> terminal tabs, mixed together. Decisions #2–#5 remain in effect.

### Actual state of the app (2026-08-11 audit)

Working: PTY terminal with a custom emulator; multi-tab ACP chat (same profile)
with streaming, tool calls, diffs, plan, permissions, markdown; saved profiles/keys/
commands; persistence and `session/load` for sessions; "Server sessions"
(resume/kill orphans); model/config-options selector;
auto-reconnect on startup. Crash bugs fixed today: `ScrollableTabRow`
with an empty list (closing the last tab / killing the agent) and SSHJ close of the terminal
on the main thread.

Gaps found:
- **Tab architecture**: Terminal/Chat mode is global (`App.kt`), one
  SSH connection per host (`AcpSessionManager.transport` singular), a single
  emulator per `TerminalHost` — none of this supports heterogeneous tabs.
- **Android in background**: without a foreground service, the process dies and
  SSH with it (auto-reconnect mitigates it, doesn't solve it). No notifications
  for pending permissions.
- **Chat**: `available_commands_update` is parsed but discarded (no slash-command
  UI); no `session/set_mode` (`claude-code-acp`'s permission modes);
  only plain text in prompts (no attachments/images or @-mentions).
- **Terminal**: no scrollback UI, no passphrase-protected keys, no mouse.
- **Desktop**: no TOFU (promiscuous verifier), no packaging.
- **Housekeeping**: `TerminalEmulatorTest.wrapsAtLastColumn` has been red for
  several phases; README describes ACP chat as "roadmap" when it already exists;
  no CI.

### Phase J — Heterogeneous tab workspace (the big piece)

A single tab model above the mode:

```kotlin
sealed interface WorkspaceTab {
    val tabId: String
    val profileId: String
    data class Chat(...) : WorkspaceTab      // wraps the current AcpTabState
    data class Terminal(...) : WorkspaceTab  // its own TerminalEmulator + shell
}
```

- **SSH connection pool** (`SshConnectionPool`, commonMain + per-platform factory):
  a `profileId → shared connection` map with refcount; tabs on the
  same profile share a connection (as today), tabs on different profiles open
  another. Closing a profile's last tab releases its connection. TOFU per
  connection (a queue of pending ones, one dialog at a time).
- **`WorkspaceManager`** (evolution of `AcpSessionManager`): no longer has
  global `transport`/`connectedConfig`; each tab resolves its connection against
  the pool. The current `AcpSessionManager` is kept almost intact as the Chat
  branch (runDirs, resume, epoch, remote sessions become per-profile).
- **Multi-instance `TerminalHost`**: extract a `TerminalTabSession` (emulator +
  shell + write loop per tab) out of `AndroidSshTerminalHost` /
  `DesktopSshTerminalHost`, over the pool's connection. The current host remains
  as the single-tab case until the UI migrates.
- **UI**: the tab bar moves up to `App.kt` (above the `when(mode)`);
  "+" opens a "new tab" dialog with a profile selector (default: the active
  tab's) and Chat/Terminal type based on the profile's command mode.
  `ChatScreen`/`TerminalScreen` become tab content.
- **Persistence**: `SavedTabSession` gains `profileId` and `type`; terminal
  tabs don't persist a session (tmux already covers that), only the tab.
- **Step-by-step migration** (each step compiles and works on its own): 1) connection
  pool behind `AcpSessionManager` without changing the UI; 2) per-tab
  profile in chat; 3) terminal tabs; 4) extended persistence.
- Global tab cap (still the default 5, decision #3) + cap on simultaneous
  connections (suggested: 3) with a warning.

### Phase K — Android robustness

- **Connection foreground service** (`connectedDeviceType`/`dataSync`):
  keeps SSH connections alive with a persistent notification
  (connected profiles, number of tabs); stops when everything disconnects.
- **Pending-permission notification** when the app is in background
  (tap → opens the app on that tab). Badge already exists in foreground.
- **SSH keepalive** (SSHJ `withHeartbeatInterval`) + dropped-network detection
  → a "reconnecting" state with exponential retry, instead of waiting for EOF.
- **Passphrase-protected keys** (SSHJ already supports it; missing: asking for
  the passphrase on connect and not persisting it) — a limitation carried over from the pivot.

### Phase L — Chat: completing the protocol

- **Slash commands**: type `AvailableCommand` (currently raw `JsonObject`),
  autocomplete when typing `/` in the input.
- **Permission modes** (`session/set_mode` + `current_mode_update`, already
  parsed): a chip next to the model selector (default/acceptEdits/bypass…).
- **Context in prompts**: @-mentions of remote paths (`ContentBlock`
  resource_link) and attaching images if the agent reports the capability.
- **`session/load` history**: verify against a real agent that the replay
  renders fully (including user bubbles); today only covered by the reducer.
- **Robust cancellation**: review `stopReason=cancelled` and orphaned busy
  states if the agent dies mid-turn.

### Phase M — Terminal: quality of life

- Scrollback UI (the emulator already accumulates it in `TerminalBuffer.scrollback`).
- Bracketed paste (the emulator already exposes `bracketedPaste`) and cursor blinking.
- Mouse (tmux `mouse on`) — optional, last.

### Phase N — Desktop and housekeeping

- TOFU on desktop (same dialog; store at `~/.config/acp-ssh-kmp/`).
- Desktop packaging (Compose `packageDistributionForCurrentOS`).
- Fix `TerminalEmulatorTest.wrapsAtLastColumn`.
- README up to date (ACP chat is no longer "roadmap") + CI (GitHub Actions:
  `desktopTest` + `assembleDebug`).

### Recommended order

J (step by step, it's what unblocks the real usage the user asked for) → K
(without the foreground service, J's multi-connection falls apart in background) →
L → M/N in any order. N's housekeeping can be interleaved whenever each area is touched.
</content>
</invoke>
