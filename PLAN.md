# Plan — Cliente Android (KMP) para ACP sobre SSH

> Estado: borrador inicial para que GLM lo profundice. DeepSeek ejecuta vía `pi`.
> No hay código todavía — solo arquitectura, fases y decisiones abiertas.

## Objetivo

App Android (Kotlin Multiplatform, target Android primero) que:
1. Abre una sesión SSH contra un host remoto.
2. Lanza ahí un agente que hable **ACP (Agent Client Protocol)** — p. ej. `claude-code-acp`,
   el adaptador de Gemini CLI, o cualquier binario que exponga ACP por stdio.
3. Habla el protocolo ACP (JSON-RPC 2.0, newline-delimited) sobre los streams stdin/stdout
   del canal `exec` de SSH.
4. Renderiza la sesión en UI (Compose Multiplatform): chat, tool calls, diffs, permission
   requests.

## Por qué KMP y no Android puro

Con target único Android, KMP añade la capa `expect`/`actual` sin beneficio real —el target
Android ya corre sobre JVM y usa las mismas librerías. **Solo vale la pena si el plan real es
compartir este cliente con iOS/desktop más adelante.** Si no, considerar bajar a Android nativo
y ahorrar la capa de abstracción. → Decisión pendiente, ver "Preguntas abiertas".

## Arquitectura en capas

```
UI (Compose Multiplatform)
   │  observa StateFlow<SessionState>
   ▼
ACP Client (dominio)
   │  initialize / session.new / session.prompt / session.update (streaming)
   │  serializa mensajes JSON-RPC, deserializa notificaciones
   ▼
Transport (interfaz) ──actual──► SSH Channel (exec) stdin/stdout como streams
```

Puntos clave:
- El **Transport** debe ser una interfaz (`interface AcpTransport { input: Source; output: Sink }`)
  para poder testear la capa ACP sin SSH real (mockeando con pipes locales).
- El **framing** de ACP es NDJSON (una línea = un mensaje JSON-RPC). Cuidado con partial reads
  del stream SSH: hay que bufferizar hasta encontrar `\n`.
- La sesión SSH debe mantenerse viva mientras dure la sesión ACP (no es request/response, es
  streaming bidireccional largo).

## Stack propuesto

- **SSH**: SSHJ (`hierynomus/sshj`) — JVM puro, mantenido, soporta auth por key/agente/password.
  Alternativa: Apache Mina SSHD (más pesado, más control). JSch está descartado (sin
  mantenimiento activo).
- **Serialización**: `kotlinx.serialization` con un `JsonRpcMessage` sellado (`Request`,
  `Response`, `Notification`) que mapea al esquema de ACP.
- **Concurrencia/streams**: `kotlinx.coroutines` + `kotlinx.io` (o `okio`) para leer/escribir
  sobre los streams del canal SSH sin bloquear el hilo principal.
- **UI**: Compose Multiplatform (Android primero).
- **Runtime/tooling**: mise para JDK; Bun no aplica aquí (proyecto JVM/Kotlin puro).

## Fases

### Fase 0 — Esqueleto
- Proyecto KMP con target `androidTarget()` (dejar `commonMain` listo para sumar targets luego).
- Config de mise (`.mise.toml`) con Java 25 LTS.
- Gradle con SSHJ + kotlinx.serialization + kotlinx.coroutines.

### Fase 1 — Validar SSH puro
- Conectar a un host de prueba, autenticar (key), `exec` de un comando remoto simple
  (`echo hola` o similar) y confirmar que se puede leer stdout por streams.
- Sin ACP todavía. Objetivo: descartar problemas de conectividad/auth antes de meter protocolo.

### Fase 2 — Framing NDJSON + eco
- Sobre el canal `exec` (ahora apuntando al binario ACP real, ej. `claude-code-acp`), implementar
  el lector de líneas NDJSON y el escritor de mensajes JSON-RPC.
- Enviar `initialize` a mano, loguear la respuesta cruda. Validar que el framing no se rompe con
  mensajes grandes (diffs largos, etc.) partidos en varios reads del socket.

### Fase 3 — Capa ACP de dominio
- Modelar el ciclo de vida: `initialize` → `session/new` → `session/prompt` → stream de
  `session/update` (tool_call, tool_call_update, agent_message_chunk, plan, etc.) →
  `session/request_permission` (requiere respuesta del usuario, es bidireccional).
- Exponer esto como un `Flow<SessionEvent>` consumible desde UI.

### Fase 4 — UI mínima (chat)
- Pantalla de conexión (host, usuario, key/agente SSH, comando remoto del agente).
- Chat simple: prompt del usuario → stream de respuesta en texto plano (sin renderizar diffs
  todavía, solo placeholders "[tool_call: ...]").

### Fase 5 — UI rica
- Renderizado de diffs (side-by-side o unificado).
- UI para `request_permission` (aceptar/rechazar/always-allow).
- Indicadores de plan/steps si el agente los emite.

## Riesgos / puntos de fricción esperados

- **Backpressure y cierre de canal**: si el proceso remoto muere o el SSH se cae a mitad de
  sesión, el `Flow` de eventos debe cerrarse limpio y la UI debe poder reconectar sin duplicar
  estado.
- **Auth SSH en Android**: si se usa key privada, dónde se guarda (Keystore, no en claro) y cómo
  se importa desde el usuario.
- **Tamaño de mensajes**: diffs de archivos grandes pueden generar líneas JSON enormes; el buffer
  de lectura NDJSON no debe asumir tamaño máximo fijo.
- **Multiplicidad de agentes ACP**: `claude-code-acp`, Gemini CLI, etc. pueden diferir en
  capabilities reportadas en `initialize` — el cliente debe negociar capabilities, no asumir
  todas presentes.

## Preguntas abiertas (para que GLM las resuelva/profundice)

1. ¿Target final es solo Android, o de verdad se planea Desktop/iOS? Define si vale la pena KMP.
2. ¿Qué agente ACP concreto se va a ejecutar en el remoto? (`claude-code-acp` vs otro) — afecta
   el comando `exec` exacto y qué capabilities esperar.
3. ¿Auth SSH por key, por ssh-agent forwarding, o password? Afecta el diseño de la pantalla de
   conexión y el almacenamiento de credenciales.
4. ¿La sesión ACP debe persistir si la app va a background (Android puede matar el proceso), o
   se acepta reconectar cada vez?

## Estado de ejecución (2026-08-07, vía pi)

**Fase 0 — Esqueleto: COMPLETADA**

- Proyecto KMP de 3 módulos: `common` (androidTarget + jvm("desktop")), `android` (app), `desktop` (CLI + UI).
- Versiones probadas juntas: Kotlin 2.3.20, AGP 8.9.0, Compose Multiplatform 1.10.1, Gradle 8.13, kotlinx-io 0.9.1, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0, SSHJ 0.40.0.
- `.mise.toml` con Java 25.0.2 + Gradle 9.7.0. ⚠️ Gradle 8.13 (AGP 8.9) no corre sobre Java 25 (el compilador Kotlin embebido falla al parsear la versión); se ejecuta con JDK 21 (`JAVA_HOME`).
- Capa de dominio en `commonMain`: `SshConnectionConfig`, `SshSession`/`ExecChannel` (interfaz con `Source`/`Sink` de kotlinx-io, listas para el framing NDJSON de Fase 2).
- Implementación SSHJ en `desktopMain` (jvm): `SshjConnect` + `SshjSession`/`SshjExecChannel`. Host key verificada contra archivo `known_hosts` (secure default: sin verifier la conexión falla).
- App Android (`:android:assembleDebug`) y UI placeholder Compose compilando (APK debug 9.1 MB).

**Fase 1 — Validar SSH puro: COMPLETADA**

- `scripts/setup-sshd.sh` levanta un sshd de prueba local (127.0.0.1:2223) con par de claves ed25519 efímero y `known_hosts`.
- `scripts/validate-ssh.sh` ejecuta el CLI de desktop (`:desktop:run --test-ssh`) contra él. Resultados:
  - `echo hola` → `exit=0`, `stdout=hola` (key auth ed25519 + known_hosts OK).
  - `seq 1 200000 | tail -1` → `stdout=200000` (stream grande leído por chunks sin perder datos).
  - `echo oops >&2 && exit 3` → `exit=3`, `stderr=oops` (exit status propagado, stderr drenado en paralelo).
- Nota SSHJ: las claves modernas `-----BEGIN OPENSSH PRIVATE KEY-----` (ed25519) requieren `OpenSSHKeyV1KeyFile`; el clásico `OpenSSHKeyFile` cae a PKCS8 y falla. `SshjConnect.keyProvider()` elige por header.

## Siguiente paso

GLM: profundizar Fase 2 y 3 (el framing NDJSON exacto y el modelo de datos del protocolo ACP,
incluyendo los tipos de `session/update` que hay que soportar en v1).
DeepSeek (vía `pi`): una vez profundizado, ejecutar Fase 2 (framing NDJSON + `initialize` a mano
contra un binario ACP real). La Fase 2 ya puede montarse sobre `ExecChannel` sin tocar la capa SSH.

## Giro de producto (2026-08-08): terminal SSH interactivo

El objetivo real del usuario es **gestionar una terminal como el setup `claude-terminal`** (SSH +
`claude`/tmux), no un chat ACP. Se pivota a un **cliente SSH interactivo**:

- **Shell con PTY** (`xterm-256color`) vía SSHJ `allocatePTY` + `startShell`, con `window-change`
  en resize (a diferencia de `exec`, que no expone `changeWindowDimensions`).
- **Emulador VT100/xterm propio** en `commonMain` (Kotlin puro, sin deps JVM): subconjunto
  suficiente para TUIs (tmux, claude, vim-lite): alt screen, cursor, SGR 16/256/truecolor,
  scroll region, save/restore cursor, wrap, DECCKM/DECAWM/DECOM, DSR/DA/CPR/window-size,
  OSC title, UTF-8.
- **TOFU** para host keys en Android (`EncryptedSharedPreferences`); desktop usa `known_hosts`
  o verifier promiscuo (dev).
- **Clave privada** pegada como PEM y guardada cifrada (AndroidX Security). Sin ficheros.

### Arquitectura resultante

```
commonMain (UI + dominio, sin deps JVM)
  App.kt ── App(host: TerminalHost) ── estados: form / conectando / TOFU dialog / terminal
  ui/ConnectionScreen.kt   ui/TerminalScreen.kt
  session/TerminalHost.kt  (interfaz: connection, screen, terminal, send/resize/…)
  terminal/                TerminalEmulator + TerminalState + TerminalColors + AnsiSequences
  SshSession.kt            + PtyShell (openShell con PTY)  · SshConnectionConfig.Auth.KeyData

:android (app, SSHJ + streams crudos de Java)
  MainActivity  → AndroidSshTerminalHost(context)
  AndroidSshTerminalHost: SSHClient + allocatePTY + startShell; reader coroutine → emulator
  TofuHostKeyVerifier:   huella SHA-256; primera vez → espera decisión del usuario
  SecureStore:          EncryptedSharedPreferences (PEM, config, hostkeys)

common/desktopMain (JVM)
  jvm/SshjSession.kt     + openShell()/SshjPtyShell · KeyData · connect(knownHosts = null)
  DesktopSshTerminalHost (App desktop reutiliza la misma UI)
```

### Desviaciones del plan original (documentadas)

1. `PtyShell` **no** recibe `command`: el comando inicial se escribe en `stdin` tras conectar
   (así `resize` funciona siempre; un canal `exec` no expone window-change).
2. Android host **no** usa kotlinx-io `Source`/`Sink` ni la interfaz `SshSession` común: lee el
   `InputStream` de Java directamente (evita dudas de variante KMP de kotlinx-io en android).
3. La barra de teclas usa **Ctrl+letra fijos** (C/D/Z/L/U/W) en vez de un "modo Ctrl" toggle:
   más usable en móvil; las flechas/Home/End sí respetan DECCKM en tiempo de pulsación.
4. Sin scrollback en la UI (el emulador lo acumula en `TerminalBuffer.scrollback`, listo para
   futuro); sin soporte de ratón ni DEC Special Graphics (tmux usa UTF-8 box-drawing).
5. Clave con passphrase no soportada (mismo límite que desktop).

### Smoke manual (móvil)

1. `:android:assembleDebug` (fuera de este flujo, una vez) → instalar `android-debug.apk`.
2. Host/usuario de tu servidor SSH, pegar la clave ed25519, comando `tmux new -As claude-terminal`.
3. TOFU: comparar la huella mostrada con `ssh-keyscan -t ed25519 host | ssh-keygen -lf -`.
4. Verificar TUI de `claude` (colores, alt-screen, cursor) y flechas/Ctrl+C; rotar → tmux reflow.
5. Desconectar/reconectar → `tmux attach` reanuda la sesión.

### Deuda técnica pendiente

- ACP (Fases 2–5 del plan original) queda como fase futura sobre el mismo `TerminalHost`/SSHJ.
- Import de clave por SAF (selector de archivos) en vez de pegar PEM.
- Scrollback, ratón, bracketed paste (el emulador ya expone `bracketedPaste`), parpadeo de cursor.
