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

## Fase C — Modelo de dominio ACP (cliente): COMPLETADA (2026-08-10)

**Contenido:** la capa de protocolo completa, validada contra el spec v1 del
repositorio `agentclientprotocol/agent-client-protocol` (fuente Rust autoritativa)
Y contra el adaptador real **`@agentclientprotocol/claude-agent-acp` 0.66.0**
corrido en este entorno (responde `initialize`/`session/new` sin API key), no solo
contra fixtures sintéticos:

- **Validación empírica previa a escribir tipos:** el adaptador real se ejecutó a
  mano (probe Node sobre stdio) y se capturaron los mensajes crudos — `initialize`
  response, `session/new` response, `session/update` con `available_commands_update`,
  error de `session/prompt` con sesión inexistente, y la llegada **desordenada** de
  respuestas (el adaptador respondió el `session/prompt` antes que el `session/new`).
  El esquema v1 (tags `sessionUpdate` en snake_case, `ContentBlock`, `ToolCall`,
  `ToolCallUpdate` con campos aplanados, `Plan`, `PermissionOption`/`outcome`) se
  contrastó contra el código fuente Rust del spec (descargado de GitHub).
- `commonMain` `acp/AcpProtocol.kt`: instancia `Json` compartida (ignoreUnknownKeys +
  coerceInputValues + explicitNulls=false + **encodeDefaults=true** — sin esto los
  params se serializaban como `{}`), `parseRpc` (detección de tipo por la forma:
  notificación sin id / request entrante con método+id / respuesta con id), `RpcOut`
  (builders de request/notification/response/error), `AcpPrettyJson`.
- `commonMain` `acp/AcpTypes.kt`: DTOs de salida (`InitializeParams` con
  `clientInfo:{name,version}`, `NewSessionParams`, `PromptParams`, `SessionIdParams`,
  `PermissionOutcome.Selected/Cancelled`) y decodificadores lenient de entrada
  (`SessionUpdate` sellado decodificado a mano con tag desconocido → `Unknown(raw)`,
  `ContentChunk`, `AcpToolCall`, `AcpToolCallUpdate`, `ToolCallContent` con `Diff`,
  `AcpPlan`/`PlanEntry`, `PermissionRequest`).
- `commonMain` `acp/AcpClient.kt`: hilo de lectura único (resuelve `CompletableDeferred`
  por id — tolera respuestas desordenadas, confirmado contra el adaptador real),
  notificaciones → `Channel<SessionUpdate>`, requests entrantes →
  `Channel<PermissionRequest>` (el host responde), métodos desconocidos → error
  JSON-RPC -32601 (evita que el agente se cuelgue esperando respuesta), `initialize`/
  `newSession`/`prompt` (el error de prompt se devuelve en el resultado, no lanza),
  `cancel` (notificación), `onEof` para desconexión limpia.
- `commonMain` `acp/AcpExecTransport.kt`: interfaz `exec`-only por plataforma +
  `readAllToString` (lectura hasta EOF para comandos cortos).
- `commonMain` `session/AcpSession.kt`: orquestación compartida — arranque del agente
  remoto (Fase B, idempotente), `pwd` remoto como cwd por defecto, reader/writer
  `exec` → `DuplexRawByteChannel` → `NdjsonFramer` → `AcpClient`, `initialize` +
  `session/new`.
- `commonMain` `session/AcpSessionState.kt`: reducer puro (`AcpSessionStore`) de la
  sesión de chat — burbujas (agente/pensamiento/usuario, agrupadas por messageId),
  tool calls (merge por id con diffs/input/output), plan (reemplazo completo), permiso
  pendiente, busy, error. Sin dependencias de UI ni transporte.
- `commonTest` (nuevas): `AcpProtocolTest` (detección + builders), `AcpClientTest`
  (handshake completo, prompt con updates intercaladas y respuestas desordenadas,
  permiso entrante + respuesta, error de prompt, método desconocido, cancel — con
  canal fake por cola que el test rellena en el momento del "agente"), `SessionUpdateTest`
  (shapes reales capturados + sintéticos, tags snake_case, unknown preservado),
  `AcpSessionStoreTest` (reducer). `MarkdownTest` y `UnifiedDiffTest` en la Fase E.

**Verificación:** compilado completo fuera de gradle (JDK 21 + kotlinc 2.4.10 +
plugins serialization/compose, jars de kotlinx/sshj/compose descargados de Maven
Central) y **76/76 tests en verde**. El request de `initialize` generado por el
cliente se verificó contra el adaptador real (responde protocolVersion 1 + agentInfo).

## Fase D — Host ACP + selector de modo + ChatScreen: COMPLETADA (2026-08-10)

**Contenido:**

- `commonMain` `session/AcpHost.kt`: interfaz hermana de `TerminalHost` (mismos
  estados de conexión + TOFU, `sendPrompt`, `respondPermission`, `cancelTurn`,
  `toggleToolCall`); `HasConnection` común a ambos hosts; `AcpMode` (TERMINAL/CHAT).
- `commonMain` `session/TerminalHost.kt`: `TerminalConfig` gana `acpRunDir` (relativo
  al home remoto; default `.acp-ssh-kmp`) y `acpCwd` (default: `pwd` remoto); el
  `remoteCommand` pasa a ser el comando de arranque del agente en modo chat.
- `android` `AndroidSsh.kt`: conexión SSH + TOFU + keyProvider extraídos de
  `AndroidSshTerminalHost` (el plan marcaba esa duplicación como riesgo de Fase C/D);
  `AndroidSshTerminalHost` lo usa ahora. `AndroidAcpHost`: connect → `AcpSession`
  → clientes updates/permisos → `AcpSessionStore`; `onEof` → desconexión limpia
  (el proceso remoto sobrevive). `MainActivity` construye ambos hosts.
- `desktopMain` `DesktopAcpHost.kt`: mismo patrón sobre `SshjConnect` + `exec`
  kotlinx.io; cancela el **job** de conexión (no el scope) para permitir reconexiones.
  `Main.kt` (desktop) construye ambos hosts.
- `commonMain` `App.kt`: `App(terminalHost, acpHost)` con selector de modo en
  `ConnectionScreen` (FilterChips Terminal/Chat; label del comando según modo);
  `HostKeyDialog` despacha al host activo. `ChatScreen.kt`: burbujas usuario/agente/
  pensamiento, autoscroll, plan, tarjetas de tool call expandibles (input/output/diff),
  modal de permiso (opciones del agente + cancelar → outcome cancelled), input +
  enviar + cancelar turno (■), indicador de streaming.

**Verificación:** compilado completo de common/desktop/android + UI con el plugin de
Compose. Bugs reales detectados y corregidos durante la verificación fuera de gradle
(con `-jvm-target 17` y detección de errores fiable): `onChannelEof` llamaba a una
función `suspend` desde contexto no-suspend en ambos hosts; `App.kt` usaba
`loadLastConfig()` sobre `HasConnection`, que no lo exponía (ahora sí); carrera de
ids en `AcpClient.request()` (se captura el id dentro del lock); declaración
duplicada de `InlineText` en ChatScreen. Falta (máquina con SDK): `:android:assembleDebug`
y el smoke manual.

## Fase E — Diffs, plan, markdown: COMPLETADA (2026-08-10)

**Contenido:**

- `commonMain` `markdown/Markdown.kt`: parser markdown mínimo y determinista —
  párrafos, encabezados #–######, listas ordenadas/desordenadas, citas, bloques de
  código con fence (con language), separador; en línea `code`, **negrita**, *cursiva*
  y [enlaces](url) anidados. Render en `ChatScreen` con `AnnotatedString` por estilos.
- `commonMain` `diff/UnifiedDiff.kt`: diff unificado estilo `diff -u` con **Myers
  (O(ND) en espacio lineal, divide-and-conquer)** sobre líneas, tope de profundidad
  (degradación a todo-borrado/todo-añadido en archivos enormes), hunks con contexto
  y cabeceras `@@ -a,b +c,d @@`; los borrados van antes que los añadidos. Render
  coloreado en `ChatScreen` (verde/rojo/azul/gris sobre fondo oscuro).
- `commonTest`: `MarkdownTest` (9) y `UnifiedDiffTest` (9: identical, new/deleted
  file, sustitución, cabeceras, dos hunks separados, insert grande, render, myers).

**Verificación:** 76/76 tests (los de E incluidos). Bugs reales encontrados y
corregidos durante la verificación fuera de gradle: `encodeDefaults=false` omitía
los campos por defecto de los requests (serializaba `{}`); el render del diff no
añadía los prefijos `+`/`-`; `groupValues[2]` en el regex de listas ordenadas; el
test del cliente tenía una race con el canal fake (sin `\n` el framer emitía solo en
EOF y mataba el reader).

## Verificación real con SDK y bugs encontrados/corregidos (2026-08-10)

Se encontró un JDK 21 (`/tmp/toolchain/jdk21`) en el sandbox y se pudo correr por fin
`:common:desktopTest` y ambos scripts de validación (`validate-acp-persist.sh`,
`validate-acp-client.sh`) contra el sshd de prueba real — lo que las Fases B/C/D
daban por "completado" nunca se había ejecutado de punta a punta. Al correrlos
aparecieron **tres bugs reales**, no solo falta de entorno:

1. **`AcpSession.close()` colgaba ~30s en cada desconexión** (mismo código que usan
   `AndroidAcpHost.disconnect()`/`DesktopAcpHost.disconnect()` en producción).
   Causa: `SshjExecChannel.close()`/`SshjExecRawChannel.close()` llaman al `close()`
   normal de SSHJ, que bloquea esperando el ACK de cierre del canal remoto — y el
   `cat $STDOUT_FIFO` que respalda el canal lector nunca sale por sí solo (por
   diseño, para sobrevivir a la reconexión), así que ese ACK nunca llega y SSHJ
   revienta con su timeout por defecto (30s). **Fix:** `closeChannelWithTimeout()`
   en ambos archivos — el cierre real corre en un hilo daemon con `join(2_000)`;
   si no termina a tiempo se abandona (se libera solo cuando el transporte SSH
   termine de desconectarse) y el llamador no se bloquea.
2. **Carrera de lector huérfano en el FIFO al reconectar.** Al arreglar (1) se
   reveló un problema más de fondo: sin pty, cerrar el canal `exec` del lector no
   le llega como señal al `cat` remoto — el proceso viejo se queda bloqueado
   leyendo el mismo FIFO que el nuevo lector del reconnect, y el kernel entrega
   cada escritura a uno solo de los lectores bloqueados (no a todos), pudiendo
   perder el mensaje si se lo entrega al huérfano. Confirmado con `jstack`: el
   segundo round-trip de `validate-acp-persist.sh` se quedaba esperando datos que
   nunca llegaban. **Fix:** `readerCommand`/`writerCommand` en `RemoteAcpProcess.kt`
   ahora matan por PID (archivo `acp-reader.pid`/`acp-writer.pid`) al lector/escritor
   anterior antes de registrarse ellos mismos, así solo hay un lector vivo por FIFO.
3. **Las lecturas del canal `exec` no eran cancelables.** Al investigar (2) con
   `jstack` se encontró que un `withTimeout(5_000)` alrededor de una lectura NDJSON
   se quedó bloqueado más de una hora sin dispararse: `readChunk()` en
   `RawByteChannels.kt` (desktop) y `SshjExecRawChannel.kt` (Android) llamaban a la
   lectura bloqueante de SSHJ sin `runInterruptible`, así que la cancelación de la
   coroutine nunca interrumpía el hilo bloqueado. Se confirmó en el código fuente de
   SSHJ que `ChannelInputStream.read()` sí convierte `InterruptedException` en
   `InterruptedIOException` correctamente, así que el fix era aplicable: las tres
   operaciones (`readChunk`/`write`/`flush`) ahora corren dentro de
   `runInterruptible(Dispatchers.IO) { ... }` en ambas plataformas.
4. Bug menor adicional (sin impacto en producción): el agente NDJSON fake de
   `validate-acp-client.sh` (`FAKE_ACP_AGENT` en `Main.kt`) tenía llaves mal
   contadas a mano en dos líneas (`plan` con una `}` de menos, `request_permission`
   con una de más) — corrompía el NDJSON y colgaba el test hasta su timeout de 15s.

**Verificación tras los fixes:** `:common:desktopTest` 100/101 (el único que falla,
`TerminalEmulatorTest.wrapsAtLastColumn`, es preexistente y ajeno a ACP — no toca
archivos de este diff). `validate-acp-persist.sh` → `PASS` (round-trip 1, cierre y
reapertura de canales, round-trip 2 tras "reconexión", ~20s). `validate-acp-client.sh`
→ `PASS` (`initialize`, `session/new`, `prompt` con streaming de texto + tool call +
diff + plan, `request_permission` con 2 opciones, `stopReason=end_turn`).

## Verificación aún pendiente (Android SDK / agente real)

1. `:android:assembleDebug` + smoke manual (selector Terminal/Chat, permisos, diffs)
   — este sandbox no tiene Android SDK instalado (`local.properties` apunta a un
   `sdk.dir` que no existe aquí); los fixes de esta sección se revisaron a mano por
   ser el mismo patrón ya verificado en desktop, pero no se compilaron con AGP.
2. Contra el agente real: instalar `claude-code-acp` en el servidor y apuntar el
   `remoteCommand` del modo chat a su ruta; verificar streaming, tool calls, diffs,
   `request_permission` con claude en modo manual.

## Retomar ACP — perfiles guardados, claves gestionadas y tabs paralelos (2026-08-10, borrador)

> Estado: planificado, sin código. Pedido del usuario: no fijar un comando por
> defecto y permitir varios comandos guardados; la clave privada no debe mostrarse
> siempre en pantalla (select en vez de textarea permanente); varias configuraciones
> de conexión guardadas; varios tabs de chat en paralelo, lo que implica que el host
> soporte más de una conexión/sesión ACP a la vez. DeepSeek ejecuta esto después.

### Por qué no es un cambio de UI aislado

Hoy `SecureStore` (`android/.../SecureStore.kt:29-52`) guarda **una sola**
configuración con claves fijas (`host`, `port`, `user`, `pem`, `command`,
`public_key`) — cada `saveConfig()` pisa la anterior, no hay lista. Desktop ni
siquiera tiene esto: `DesktopAcpHost`/`DesktopSshTerminalHost` guardan `lastConfig`
en una variable en memoria, se pierde al cerrar la app. `ConnectionScreen.kt:48,109-126`
pinta la clave PEM en un `OutlinedTextField` multilinea siempre visible, precargado
con el secreto en claro cada vez que se abre la pantalla; `command` (`ConnectionScreen.kt:49-51`)
es un único campo de texto libre con un default hardcodeado por modo
(`"tmux new -As claude-terminal"` / `"claude-code-acp"`). Y tanto `AcpHost` como
`TerminalHost` son **de una sola sesión**: `AndroidAcpHost`/`DesktopAcpHost` tienen
variables singulares (`transport`, `acpSession`, `acpClient`, `sessionStore`) y
`connect()` llama a `disconnect()` primero — conectar de nuevo mata la sesión
anterior. `App.kt:52-53` tiene un único `mode` y un único `active: HasConnection`,
sin concepto de "varias sesiones vivas" en absoluto. Cuatro pedidos → cuatro capas
distintas a tocar (storage, UI de conexión, capa de host/sesión, UI de chat).

### Qué se reutiliza tal cual

- `PemFileIo.kt` (`rememberPemExporter`/`rememberPemImporter`, SAF en Android /
  `JFileChooser` en desktop): la mecánica de importar/exportar `.pem` ya existe,
  solo hay que conectarla a una lista de claves guardadas en vez de a un único
  campo de texto.
- TOFU (`TofuHostKeyVerifier`), generación Ed25519 (`generateEd25519SshKey`),
  `AcpSession`/`AcpClient`/`RemoteAcpProcess`: nada de esto cambia — el fix de
  Fase B/D de evitar el lector huérfano por PID (`RemoteAcpProcess.kt`) es
  precisamente lo que hace seguro tener varios `runDir` concurrentes (ver Fase H).

### Fase F — Almacenamiento multi-perfil (storage puro, sin UI)

**1. Modelo de datos nuevo** (`commonMain`, `kotlinx.serialization`):

```kotlin
data class SavedKey(val id: String, val label: String, val privateKeyPem: String, val publicKeyLine: String? = null)
data class SavedCommand(val id: String, val label: String, val command: String, val mode: AcpMode? = null)
data class ConnectionProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val keyId: String,        // referencia a SavedKey.id
    val commandId: String?,   // referencia a SavedCommand.id; null = comando vacío (shell por defecto en modo Terminal)
    val acpRunDir: String? = null,
    val acpCwd: String? = null,
)
```

`mode: AcpMode? = null` en `SavedCommand` permite comandos reutilizables entre
Terminal y Chat (p. ej. nada impide guardar `tmux new -As claude-terminal` y
`claude-code-acp` en la misma lista) o marcados para un modo si el usuario quiere
separarlos; el selector de comandos (Fase G) filtra por modo pero muestra "ver
todos" para reutilizar uno de otro modo.

**2. `ProfileStore` — interfaz común** (`commonMain`, sin dependencias de
plataforma): `listProfiles()/saveProfile()/deleteProfile()`,
`listKeys()/saveKey()/deleteKey()`, `listCommands()/saveCommand()/deleteCommand()`,
`loadLastProfileId()/setLastProfileId()` (qué perfil abrir por defecto al entrar).
Todo por `Flow`/`StateFlow` si la UI necesita reactividad, o simples funciones si
la UI relee al entrar a la pantalla (más simple, evaluar en Fase G).

**3. Implementación Android** (`android/.../SecureStoreProfileStore.kt`, reemplaza
gradualmente a `SecureStore`): mismo `EncryptedSharedPreferences`, pero las listas
se serializan como JSON (`Json.encodeToString(List<ConnectionProfile>)`) bajo una
sola key por tipo (`profiles`, `keys`, `commands`) en vez de campos sueltos —
evita migrar el schema de prefs cada vez que se añade un campo. **Migración**:
al primer arranque tras esta versión, si existen las keys viejas de `SecureStore`
(`host`/`user`/`pem`/`command`) y no existe ya ningún perfil, crear un
`ConnectionProfile`+`SavedKey`+`SavedCommand` a partir de ellas (así nadie pierde
su conexión guardada al actualizar) y borrar las keys viejas.

**4. Implementación desktop** (`common/src/desktopMain/.../DesktopProfileStore.kt`):
hoy no hay ninguna persistencor — usar un archivo JSON en
`~/.config/acp-ssh-kmp/profiles.json` (permisos `600`, plano: desktop no tiene
AndroidX Security ni es el target con amenaza de "otra app en el mismo dispositivo
lee tus prefs" que sí aplica en Android). Documentar la asimetría de seguridad
Android (cifrado) vs desktop (archivo plano con permisos de usuario) igual que ya
se documenta la asimetría TOFU vs `known_hosts`.

**5. Tests** (`commonTest` para el modelo de datos + reglas de referencia
`keyId`/`commandId` inválidos; tests de la implementación Android/desktop quedan
fuera de `commonTest` igual que hoy `SecureStore` no tiene test — evaluar si vale
un test JVM para `DesktopProfileStore` dado que sí es código nuevo en desktopMain).

### Fase G — UI: perfiles, claves y comandos gestionados (reemplaza `ConnectionScreen`)

**Pantalla de perfiles** (nueva, antes de lo que hoy es `ConnectionScreen`): lista
de `ConnectionProfile` guardados (label + host) con "Conectar" / "Editar" / "Duplicar"
/ "Borrar", botón "Nueva conexión". Si no hay perfiles, ir directo al formulario
(no forzar una pantalla vacía intermedia).

**Formulario de conexión** (`ConnectionScreen` reescrita): host/puerto/usuario en
texto libre como hoy; **clave**: `ExposedDropdownMenuBox` (Material3) con las
`SavedKey` guardadas por label — la clave NUNCA se pinta en pantalla al elegirla
del select, solo su label; opción "Gestionar claves" abre un diálogo con
generar/importar/exportar/renombrar/borrar sobre la lista (reutiliza
`rememberPemExporter`/`rememberPemImporter` de Fase F); un botón explícito
"Mostrar clave" (no automático) permite ver el PEM en claro de la clave
seleccionada, para el caso de que el usuario necesite copiarla — fricción
deliberada, no lo oculta del todo. **Comando**: mismo patrón de dropdown sobre
`SavedCommand` (filtrado por `mode` actual, con opción "ver todos"), opción
"Nuevo comando" que abre un campo de texto + label para guardarlo, y opción
"Sin comando guardado (usar shell / claude-code-acp por defecto)" en vez de un
default siempre precargado — cubre el pedido de "que la app pueda no incluir
comando por defecto".

**`TerminalConfig`** pierde su rol de "única fuente de verdad guardada": pasa a
construirse en el momento de conectar a partir de `ConnectionProfile` +
`SavedKey` + `SavedCommand` resueltos (`profile.toTerminalConfig(key, command)`),
en vez de ser lo que persiste `SecureStore` directamente.

### Fase H — Multi-sesión ACP: una conexión SSH, varios agentes remotos

**Decisión de diseño — un proceso de agente por tab, no multiplexado por
`sessionId` sobre un único agente.** El protocolo ya viaja con `sessionId` en cada
`session/update` (`AcpClient.kt:171` lee `message.params` pero `route()` solo
pasa `update`, sin `sessionId`, al `Channel<SessionUpdate>` único — así que hoy
NO hay demux) y en teoría un solo `claude-code-acp` podría atender varias
`session/new` a la vez. Pero eso exige (a) verificar que el agente real soporta
bien sesiones concurrentes — sin binario real a mano no se puede confirmar, ver
la brecha ya documentada en "Verificación aún pendiente"/punto 2 — y (b) reescribir
`AcpClient`/`AcpSessionStore` para rutear por `sessionId`. La alternativa —
**un `AcpSession` (con su propio `runDir`, ergo sus propios FIFOs/proceso remoto)
por tab, todos sobre la misma conexión SSH ya autenticada** — no depende de esa
suposición no verificada, aísla fallos (un agente que crashea no afecta a los
demás tabs) y reutiliza sin cambios el fix de Fase B/D del lector huérfano por
PID (cada `runDir` es independiente, no hay colisión de FIFOs). Costo: un proceso
`claude-code-acp` por tab en el servidor en vez de uno compartido — aceptable
salvo que el usuario abra decenas de tabs a la vez.

**`AcpSessionManager`** (`commonMain`, nueva clase — hoy esta responsabilidad vive
mezclada dentro de `AndroidAcpHost`/`DesktopAcpHost`): mantiene la conexión SSH
(un `AcpExecTransport`/cliente compartido) y un mapa `tabId -> AcpSessionEntry`
donde cada entrada tiene su propio `AcpSession`, `AcpClient`, `AcpSessionStore`
(cada `runDir` se autogenera como `${config.acpRunDir}/tab-<uuid>` si no se fija
uno explícito, para que dos tabs del mismo perfil no compartan FIFOs por accidente).
Expone `StateFlow<Map<String, AcpSessionState>>` o una lista de
`StateFlow<AcpSessionState>` por tab, `openTab(profile)`, `closeTab(tabId)`
(cierra ese `AcpSession` sin tocar la conexión SSH ni los demás tabs — el
agente remoto de ESE tab sigue vivo tras cerrar el tab, igual que ya sobrevive a
un disconnect; "cerrar tab" en la UI no debería significar "matar el proceso
remoto" salvo que el usuario lo pida explícito, ver Fase I).

**`AcpHost` cambia de forma**: en vez de una interfaz "una sesión", pasa a ser
(o a envolver) el `AcpSessionManager`. Revisar si conviene mantener `AcpHost`
como fachada de UN tab (para no romper `App.kt`/`ChatScreen` de golpe) mientras
`AcpSessionManager` vive por debajo — decisión de implementación a tomar en el
momento, no bloqueante para el resto del plan.

**Reutilización de la conexión SSH**: hoy `AndroidAcpHost.connect()` abre un
`SSHClient` nuevo cada vez; para varios tabs sobre el mismo perfil, extraer esa
conexión a algo que viva mientras el `AcpSessionManager` viva, no por tab —
`AndroidSsh.connect()` (ya extraído en Fase D, `android/.../AndroidSsh.kt`) ya
devuelve la conexión desnuda, así que esta capa es la que hoy falta, no algo que
haya que rehacer desde cero.

### Fase I — UI: tabs de chat en paralelo

**`TabRow` (Material3)** sobre `ChatScreen`: un tab por sesión abierta en el
`AcpSessionManager`, "+" para abrir uno nuevo (siempre del mismo perfil que el
tab activo, decisión cerrada — ver "Decisiones cerradas con el usuario"),
long-press o botón "x" para cerrar (deja el proceso remoto corriendo, con una
acción separada "cerrar y terminar agente" para matarlo explícitamente),
tope configurable de tabs simultáneos (default 5, con aviso al llegar al
límite), indicador visual (punto/badge) si un tab en background tiene
streaming activo o un permiso pendiente (para no perderlo si el usuario está
mirando otro tab). `ChatScreen` pasa a recibir el `AcpSessionState` del tab
activo en vez de leerlo directo del host.

**Alcance deliberadamente fuera de V1**: tabs de **Terminal** en paralelo (el
usuario solo pidió tabs para chat; Terminal ya tiene tmux para multiplexar, ver
la nota de diseño de Fase B/pivote a terminal) y tabs contra **servidores
distintos** en simultáneo (cada tab de este plan comparte la conexión SSH del
perfil activo — abrir tabs contra otro perfil implicaría otra conexión SSH en
paralelo, descartado para V1, ver "Decisiones cerradas con el usuario").

### Decisiones cerradas con el usuario (2026-08-10)

Las cuatro preguntas abiertas de este plan ya se resolvieron con el usuario;
quedan fijadas para que DeepSeek ejecute sin tener que volver a decidirlas:

1. **Perfil por tab: siempre el mismo que el tab activo.** No se puede elegir
   perfil al abrir un tab nuevo en V1 — todos los tabs comparten la única
   conexión SSH del perfil activo, `AcpSessionManager` no gestiona más de una
   conexión a la vez. (El diseño no lo bloquea a futuro si se pidiera después.)
2. **Cerrar un tab deja el proceso remoto corriendo.** Igual que un disconnect
   normal: reconectable después, consistente con el resto del diseño de Fase B.
   Se añade una acción separada y explícita "cerrar y terminar el agente
   remoto" para quien sí quiera matar el proceso.
3. **Límite de tabs simultáneos: tope configurable, default 5.** Avisa al
   llegar al tope en vez de bloquear en silencio o permitir un número
   ilimitado de procesos `claude-code-acp` en el servidor.
4. **Copiar la clave desde "Mostrar clave" está permitido.** La fricción
   deliberada vive en el paso de "Mostrar" (no automático); una vez visible,
   copiarla al portapapeles es un flujo normal, sin bloqueo adicional.

### Siguiente paso concreto

Fase F no depende de nada pendiente — arranca por el storage antes de tocar
ninguna UI, igual que el resto de fases de este plan ya siguieron ese orden.
