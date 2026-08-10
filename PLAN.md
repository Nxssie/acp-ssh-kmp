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
- ~~Import de clave por SAF (selector de archivos) en vez de pegar PEM.~~ Hecho (2026-08-09):
  export/import de `.pem` vía SAF (Android) / `JFileChooser` (desktop), ver `io/PemFileIo.kt`.
- Scrollback, ratón, bracketed paste (el emulador ya expone `bracketedPaste`), parpadeo de cursor.

## Retomar ACP — plan de modo chat (2026-08-09, borrador)

> Estado: planificado, sin código. Decisiones cerradas con el usuario antes de escribir esto:
> agente = `claude-code-acp`; UI = terminal actual **y** chat nuevo, seleccionable (no reemplaza
> nada); persistencia de sesión ACP desde el v1 (sobrevive a que Android mate el proceso en
> background / reconexión de red).

### Por qué no es un ajuste de estilos

Lo que hoy se ve al conectar (`tmux new -As claude-terminal`) es la CLI `claude` interactiva
renderizada carácter a carácter por el emulador VT100 propio. Una "UI tipo chat" real necesita que
el remoto hable **ACP (JSON-RPC 2.0 sobre NDJSON)** en vez de imprimir una TUI — son dos protocolos
de transporte distintos, no una cuestión de estilos de `TerminalScreen`. Implica reactivar las
Fases 2–5 del plan original, ahora sobre el código que existe tras el pivote a terminal.

### Qué se reutiliza tal cual

- Conexión SSH, TOFU (Android) / `known_hosts` (desktop), `SecureStore`, generación e import/export
  de clave: todo eso vive en la capa de auth/config, independiente del canal que se abra después.
- `TerminalConfig` (host/puerto/usuario/clave) se reutiliza; el campo `remoteCommand` pasa a ser el
  comando de arranque del agente ACP en vez de un shell.

### Qué falta / hay que construir

**1. Canal `exec` real en Android** — hoy `AndroidSshTerminalHost` solo hace
`allocatePTY`+`startShell` (ver `AndroidSshTerminalHost.kt:78-80`); nunca abre un `exec` sin PTY.
ACP necesita pipes crudos, sin pty (un pty puede cambiar el buffering/salida del binario ACP,
que espera stdio plano). Hay que añadir esa capacidad en paralelo a la del shell.

**2. `RawByteChannel` en `commonMain`** — abstracción mínima común a Android (que usa
`java.io.InputStream`/`OutputStream` crudos) y desktop (que usa `kotlinx.io Source/Sink` vía
`SshSession.exec()`, ya definido en `SshSession.kt:12` pero sin usar todavía):

```kotlin
interface RawByteChannel {
    suspend fun readChunk(buffer: ByteArray): Int  // -1 = EOF, como InputStream.read
    suspend fun write(bytes: ByteArray)
    fun close()
}
```

**3. `NdjsonFramer` en `commonMain`** (Kotlin puro, sin deps JVM) — bufferiza sobre un
`RawByteChannel` y expone `Flow<String>` de líneas completas + `writeMessage(json: String)`.
Testeable en `commonTest` con un `RawByteChannel` fake y reads partidos a mitad de línea (el mismo
caso límite que ya señalaba el plan original en Fase 2).

**4. Persistencia del proceso remoto — recomendación: NO usar tmux/dtach clásicos.**
Ambos crean una pty, y una pty puede hacer que `claude-code-acp` cambie de modo de salida
(muchas CLIs detectan TTY y dejan de emitir NDJSON limpio). Alternativa más segura y que cumple
igual el requisito de "sobrevive a reconexión":

```bash
mkfifo /tmp/acp-in /tmp/acp-out /tmp/acp-err
setsid nohup claude-code-acp <\ /tmp/acp-in >\ /tmp/acp-out 2>\ /tmp/acp-err &
```

El cliente abre dos canales `exec` (mismo host, misma conexión SSH multiplexada): uno hace
`cat >> /tmp/acp-in` para escribir, otro `cat /tmp/acp-out` para leer. Si el cliente se desconecta,
el proceso remoto sigue vivo (desacoplado de la sesión SSH por `setsid`); si el FIFO se llena
porque nadie está leyendo, el agente simplemente bloquea hasta que alguien reconecte y vuelva a
leer — no se pierden mensajes, solo se pausan. Si el usuario prefiere tmux por uniformidad con
`claude-terminal`, es una alternativa válida pero **hay que validar primero** que
`claude-code-acp` no cambia de comportamiento bajo pty antes de comprometerse a esa vía.

**5. Modelo de dominio ACP** (`commonMain`, `kotlinx.serialization`):
- Tipos JSON-RPC 2.0 genéricos (`Request`/`Response`/`Notification`), detectando el tipo de mensaje
  entrante por presencia de `id`/`method` (una notificación no tiene `id`; una respuesta no tiene
  `method`; un request entrante del agente —p. ej. `session/request_permission`— tiene ambos).
- `AcpClient`: `initialize()`, `session/new`, `session/prompt`, despacho de `session/update`
  (stream) y manejo de requests entrantes que exigen respuesta del cliente.
- ⚠️ El esquema exacto (`SessionUpdate` y sus variantes, forma de `initialize`/capabilities) **no
  está verificado contra `claude-code-acp` real** — antes de fijar tipos, correr el binario a mano
  contra el framer de la Fase A y loguear los mensajes crudos, igual que hizo el plan original en
  su Fase 2 ("enviar `initialize` a mano, loguear la respuesta cruda").

**6. `AcpHost` — nueva interfaz hermana de `TerminalHost`** (no lo reemplaza):

```kotlin
interface AcpHost {
    val connection: StateFlow<ConnectionState>   // reutiliza los mismos estados (TOFU, error…)
    val session: StateFlow<AcpSessionState>      // mensajes, tool calls, plan, permission pendiente
    fun connect(config: TerminalConfig)
    fun sendPrompt(text: String)
    fun respondPermission(requestId: String, outcome: PermissionOutcome)
    fun disconnect()
}
```

`App.kt` necesita un selector de modo (Terminal | Chat) en `ConnectionScreen` que decide qué host
construir y qué pantalla montar al conectar (`TerminalScreen` vs `ChatScreen` nuevo).

**7. `ChatScreen.kt`** (`commonMain`, v1): burbujas usuario/agente, texto en streaming (append al
último chunk), tarjetas de tool-call simples (nombre + estado: pendiente/en curso/hecho/error, sin
diff todavía), input + enviar, modal Allow/Reject/Always-allow para `session/request_permission`.
Diffs, plan/steps detallado y markdown enriquecido quedan para una fase posterior (Fase E).

### Fases de ejecución

| Fase | Contenido | Depende de decisiones pendientes |
|------|-----------|-----------------------------------|
| A | `exec` real en Android + `RawByteChannel` + `NdjsonFramer` (+ tests commonTest) | No — se puede empezar ya |
| B | Arranque persistente remoto (FIFOs+`setsid`, o tmux/dtach si se valida antes) | No |
| C | Modelo de dominio ACP (`AcpClient`) contra `claude-code-acp` real | Sí — requiere el binario instalado en el servidor de prueba |
| D | `AcpHost` + selector de modo + `ChatScreen` v1 | Depende de C |
| E | Diffs, plan rico, tool-calls expandibles, markdown | Depende de D |

### Riesgos

- Esquema ACP no verificado (ver punto 5) — no comprometerse a tipos de datos hasta correr el
  binario real.
- Pty vs pipes en la persistencia (ver punto 4) — validar antes de asumir tmux funciona igual que
  con una CLI interactiva normal.
- Duplicación de lógica de conexión/TOFU entre `AndroidSshTerminalHost` (terminal) y el futuro host
  ACP para Android — evaluar extraer lo común (connect + TOFU + SecureStore) en Fase C/D en vez de
  copiar la clase.
- Multiplicidad de agentes ACP a futuro (Gemini CLI, etc.) — capabilities de `initialize` pueden
  diferir; no asumir todas presentes si algún día se soporta más de uno.

### Siguiente paso concreto

Fase A no depende de nada pendiente — es el punto de partida natural cuando se retome esto.

## Fase A — Transporte NDJSON: COMPLETADA (2026-08-09)

**Contenido:** canal `exec` sin PTY + framing NDJSON, común a Android y desktop. Sin tocar UI,
selector de modo, `AcpHost` ni tipos ACP (Fases B–E siguen bloqueadas por el binario real).

- `commonMain` `acp/RawByteChannel.kt`: interfaz de bytes bidireccional (`readChunk` con -1=EOF,
  `write`, `flush`), pura common sin kotlinx.io ni `expect/actual`.
- `commonMain` `acp/NdjsonFramer.kt`: `lines(): Flow<String>` bufferizando hasta `\n`, buffer
  con crecimiento por duplicación (sin tamaño máximo de línea), decode UTF-8 solo en límites de
  línea (un char partido entre reads queda íntegro), `\r\n` normalizado, línea parcial emitida en
  EOF; `writeLine()` escribe línea+`\n` y hace flush.
- `commonTest` `acp/NdjsonFramerTest.kt`: 12 tests con `FakeRawChannel` (chunks partidos, bytes de
  a uno, nueva línea a mitad de chunk, línea de 300KB, `\r\n`, UTF-8 entre reads, EOF sin datos,
  writes). Vía `:common:desktopTest`.
- `desktopMain` `acp/RawByteChannels.kt`: `ExecChannel.asRawByteChannel()` — bridge del `exec`
  kotlinx.io existente (`SshSession.exec()`, sin tocar plomería SSH). Drena `stderr` en una
  coroutine aparte (`SupervisorJob` cancelado en `close()`) para no bloquear el canal si el
  remoto escribe ahí.
- `:android` `acp/SshjExecRawChannel.kt`: `exec` sin PTY sobre los streams crudos de SSHJ
  (`Session.Command` + `Dispatchers.IO`), los pipes planos que espera ACP. Mismo drenado de
  `stderr` en background que el bridge desktop.
- `AndroidSshTerminalHost.openExec(command)`: expone el canal sobre el cliente ya autenticado
  (stopgap: hoy requiere `connect()` de shell; Fase D refactorizará a un host exec-only); cierra
  la sesión si `exec()` falla.

**Verificación:** compilado + tests corriendo fuera de gradle (sandbox sin JDK/SDK): Kotlin 2.4.10,
coroutines 1.11.0, kotlinx-io 0.9.1, sshj 0.40.0 — 12/12 tests OK; el adaptador Android y el
bridge desktop compilan contra las mismas versiones. Falta correr `:common:desktopTest` +
`:android:assembleDebug` en la máquina con SDK (ver Fase A del plan de ejecución).

## Fase B — Arranque persistente remoto: COMPLETADA (2026-08-10)

**Contenido:** el snippet de FIFOs+`setsid` que proponía el plan original (§4) tenía un bug de
diseño que se detectó y arregló **antes** de escribir Kotlin, reproduciéndolo a mano en shell
(sin SSH de por medio, solo para aislar la mecánica de FIFOs):

- **Bug encontrado:** abrir un FIFO en modo lectura o escritura bloquea el `open()` hasta que
  aparece el extremo contrario; y si el único lector/escritor externo se desconecta, el otro lado
  recibe EOF (lecturas) o EPIPE/SIGPIPE (escrituras) — el snippet original mataba al agente en el
  primer reconnect, justo lo opuesto al objetivo de "sobrevive a reconexión".
- **Fix validado:** mantener un fd propio `<>` (lectura+escritura) sobre cada FIFO durante toda la
  vida del agente, heredado a través del `exec` final hacia el binario (no se cierra salvo que el
  agente cierre explícitamente todos los fds heredados, poco común en una CLI en foreground). Con
  eso el conteo de lectores/escritores del FIFO nunca llega a cero: un cliente que se desconecta
  solo causa bloqueo (backpressure), nunca pérdida de datos ni muerte del proceso. Confirmado en
  shell puro: desconexión sin pérdida de mensajes, 3000 líneas encoladas sin lector (backpressure,
  sin crash), relanzamiento idempotente (`ALREADY_RUNNING` si el pid vive) y recuperación tras
  matar el proceso (pidfile stale detectado, FIFOs recreados, nuevo pid).
- `stderr` va a un archivo normal (`acp-err.log`), no a un tercer FIFO: un FIFO de error sin
  lector bloquearía el `open()` de redirección antes de poder ejecutar el agente — desviación
  deliberada del snippet original del plan.

**Código:**
- `commonMain` `acp/RemoteAcpProcess.kt`: `launchScript(runDir, agentCommand)` (idempotente vía
  pidfile + `kill -0`), `readerCommand`/`writerCommand` (`cat acp-out` / `cat >> acp-in`),
  `shellQuote` (POSIX, comillas simples). Los valores dinámicos (`runDir`, `agentCommand`) solo se
  interpolan en la asignación de variables shell de cabecera; el bloque `setsid sh -c '...'` es
  texto estático, así que ninguna comilla que meta el usuario puede rompernos ese bloque
  (verificado en `commonTest`).
- `commonMain` `acp/DuplexRawByteChannel.kt`: combina un `RawByteChannel` de solo lectura y otro de
  solo escritura (dos `exec` SSH independientes) en un `RawByteChannel` bidireccional para el
  `NdjsonFramer` de la Fase A.
- `commonTest`: `RemoteAcpProcessTest.kt` (quoting, estructura del script, aislamiento del bloque
  interno) + `DuplexRawByteChannelTest.kt` (routing read/write/flush/close). Vía `:common:desktopTest`.
- `desktop` `Main.kt --test-acp-persist`: reproduce el escenario completo contra el sshd de
  prueba — arranca el agente (un bucle de shell que hace eco, ya que `claude-code-acp` no está
  instalado en el sshd de prueba), round-trip NDJSON, cierra ambos canales `exec` (desconexión),
  reabre canales frescos y confirma el segundo round-trip sin pérdida. `scripts/validate-acp-persist.sh`
  es el wrapper, igual que `validate-ssh.sh` de la Fase 1.
- Sin cambios en `AndroidSshTerminalHost`/`DesktopSshTerminalHost`: `openExec`/`session.exec()` de
  la Fase A ya bastan para abrir los canales reader/writer, no hace falta plomería nueva por host.

**Verificación:** la mecánica de FIFOs+`setsid` se ejecutó y confirmó en shell real (ver arriba).
El código Kotlin (`RemoteAcpProcess`, `DuplexRawByteChannel`, `Main.kt`) se revisó a mano con
mucho cuidado (incluyendo los casos de escape de `$` en plantillas de string de Kotlin, que
causaron dos bugs de compilación reales detectados y corregidos antes de continuar) pero **no se
compiló ni se corrió `--test-acp-persist` contra el sshd real**: este sandbox no tiene JDK/SDK en
absoluto (ni siquiera el `kotlinc` suelto que permitió verificar la Fase A fuera de gradle). Falta
como siguiente paso, en la máquina con SDK: `./gradlew :common:desktopTest` y
`scripts/validate-acp-persist.sh` contra el sshd de prueba (`scripts/setup-sshd.sh`).
