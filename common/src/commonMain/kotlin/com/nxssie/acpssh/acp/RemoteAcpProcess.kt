package com.nxssie.acpssh.acp

/**
 * Comandos remotos para la Fase B: arranque persistente del proceso ACP vía
 * FIFOs + `setsid`, desacoplado de la sesión SSH que lo lanza (sobrevive a que
 * el cliente se desconecte/reconecte, a diferencia de un `exec` simple).
 *
 * Por qué no basta el snippet ingenuo `mkfifo ...; setsid cmd <in >out 2>err &`:
 * abrir un FIFO en modo lectura/escritura bloquea hasta que aparece el extremo
 * contrario, y si el único lector/escritor externo se desconecta, el otro lado
 * recibe EOF (lecturas) o EPIPE/SIGPIPE (escrituras) — mata al agente en el
 * primer reconnect. La solución (validada en shell antes de escribir esto) es
 * mantener un fd propio `<>` (lectura+escritura) sobre cada FIFO durante toda
 * la vida del agente: como ese fd nunca se cierra, el conteo de lectores/
 * escritores del FIFO nunca llega a cero, así que un cliente que se desconecta
 * solo causa bloqueo (backpressure), nunca EOF/EPIPE. Se logra heredando esos
 * fds a través del `exec` final hacia el binario del agente (no se cierran
 * salvo que el propio agente cierre explícitamente todos los fds heredados,
 * poco común en una CLI en foreground).
 *
 * `stderr` va a un archivo normal (no un FIFO): un FIFO de error sin lector
 * bloquearía el `open()` de redirección antes de poder ejecutar el agente.
 */
object RemoteAcpProcess {

    /** Nombre del pipe de entrada (stdin del agente) dentro del run dir. */
    const val STDIN_FIFO = "acp-in"

    /** Nombre del pipe de salida (stdout del agente) dentro del run dir. */
    const val STDOUT_FIFO = "acp-out"

    /** Log de stderr del agente (archivo normal, no bloqueante). */
    const val STDERR_LOG = "acp-err.log"

    /** Archivo con el PID del agente, usado para detectar si sigue vivo. */
    const val PID_FILE = "acp.pid"

    /** Archivo con el PID del último lector/escritor del FIFO, ver [readerCommand]/[writerCommand]. */
    const val READER_PID_FILE = "acp-reader.pid"
    const val WRITER_PID_FILE = "acp-writer.pid"

    /** Marcador con sessionId+cwd de la sesión ACP activa, ver [writeSessionCommand]. */
    const val SESSION_FILE = "acp-session"

    /**
     * Sentinel que cierra la salida de [listCommand]: [AcpExecTransport] no
     * expone exit status, así que sin esto "sin sesiones" y "el exec se cortó
     * a medias" son indistinguibles para [parseListOutput].
     */
    const val LIST_END = "ACP_LIST_END"

    /**
     * Script POSIX sh que arranca el agente si no está corriendo ya (idempotente:
     * relanzar mientras vive no hace nada, solo informa). Imprime `STARTED` o
     * `ALREADY_RUNNING` por stdout y devuelve.
     */
    fun launchScript(runDir: String, agentCommand: String): String = """
        RUN_DIR=${shellQuote(runDir)}
        AGENT_CMD=${shellQuote(agentCommand)}
        mkdir -p "${'$'}RUN_DIR" && cd "${'$'}RUN_DIR" || exit 1
        if [ -f $PID_FILE ] && kill -0 "$(cat $PID_FILE 2>/dev/null)" 2>/dev/null; then
          echo ALREADY_RUNNING
        else
          rm -f $STDIN_FIFO $STDOUT_FIFO
          mkfifo $STDIN_FIFO $STDOUT_FIFO
          setsid sh -c '
            exec 3<>$STDIN_FIFO
            exec 4<>$STDOUT_FIFO
            exec <$STDIN_FIFO >$STDOUT_FIFO 2>>$STDERR_LOG
            echo ${'$'}${'$'} > $PID_FILE
            exec sh -c "${'$'}0"
          ' "${'$'}AGENT_CMD" >/dev/null 2>&1 </dev/null &
          echo STARTED
        fi
    """.trimIndent()

    /**
     * Comando que expone el pipe de salida como stdout de un `exec` SSH.
     *
     * Cierra primero el `cat` anterior (si quedó uno huérfano de una conexión
     * previa): sin pty, cerrar el canal `exec` desde el cliente no le llega
     * como señal al remoto — el `cat` viejo se queda bloqueado leyendo el FIFO
     * y puede competir con el nuevo por el próximo mensaje que llegue (el
     * kernel entrega cada escritura del FIFO a un solo lector de los que estén
     * bloqueados, no a todos), perdiéndolo si se lo entrega al huérfano.
     */
    fun readerCommand(runDir: String): String =
        "cd ${shellQuote(runDir)} && " +
            "{ [ -f $READER_PID_FILE ] && kill \"\$(cat $READER_PID_FILE)\" 2>/dev/null; :; }; " +
            "echo \$\$ > $READER_PID_FILE; exec cat $STDOUT_FIFO"

    /** Comando que vuelca el stdin de un `exec` SSH al pipe de entrada (misma lógica de relevo que [readerCommand]). */
    fun writerCommand(runDir: String): String =
        "cd ${shellQuote(runDir)} && " +
            "{ [ -f $WRITER_PID_FILE ] && kill \"\$(cat $WRITER_PID_FILE)\" 2>/dev/null; :; }; " +
            "echo \$\$ > $WRITER_PID_FILE; exec cat >> $STDIN_FIFO"

    /**
     * Mata el agente remoto de un run dir y borra el directorio (Fase H: acción
     * explícita "cerrar y terminar agente"; también el camino de "Terminar" en
     * el listado de sesiones remotas para runDirs huérfanos). Distinto de cerrar
     * un tab, que deja el proceso vivo para reconectar después.
     *
     * Verifica que el pid del fichero sigue siendo el agente antes de matar
     * (mirando a qué apuntan sus fd 0/1) porque, una vez el listado puede apuntar
     * a runDirs que este dispositivo no lanzó, un pid reciclado tras un reinicio
     * del host podría pertenecer a un proceso ajeno del mismo usuario. Manda al
     * grupo (el pid es el líder de sesión de `setsid`, ver [launchScript]) para
     * arrastrar hijos que el agente haya lanzado, con escalada TERM→KILL. Si el
     * kill no aplica o no hace falta, igual se limpia el directorio.
     */
    fun killCommand(runDir: String): String = "sh -c " + shellQuote(killScript(runDir))

    internal fun killScript(runDir: String): String = """
        D=${shellQuote(runDir)}
        if [ -d "${'$'}D" ]; then
          pid=
          [ -r "${'$'}D/$PID_FILE" ] && { IFS= read -r pid < "${'$'}D/$PID_FILE" || : ; }
          case ${'$'}pid in ''|*[!0-9]*) pid= ;; esac
          if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
            ok=1
            if [ -d "/proc/${'$'}pid/fd" ]; then
              fd0=${'$'}(readlink "/proc/${'$'}pid/fd/0" 2>/dev/null)
              fd1=${'$'}(readlink "/proc/${'$'}pid/fd/1" 2>/dev/null)
              if [ -n "${'$'}fd0" ] || [ -n "${'$'}fd1" ]; then
                case "${'$'}fd0 ${'$'}fd1" in
                  *"${'$'}D/$STDIN_FIFO"*|*"${'$'}D/$STDOUT_FIFO"*) ok=1 ;;
                  *) ok=0 ;;
                esac
              fi
            fi
            if [ "${'$'}ok" = 1 ]; then
              kill -TERM -"${'$'}pid" 2>/dev/null || kill -TERM "${'$'}pid" 2>/dev/null
              n=0
              while [ ${'$'}n -lt 3 ] && kill -0 "${'$'}pid" 2>/dev/null; do
                sleep 1
                n=${'$'}((n + 1))
              done
              if kill -0 "${'$'}pid" 2>/dev/null; then
                kill -KILL -"${'$'}pid" 2>/dev/null || kill -KILL "${'$'}pid" 2>/dev/null
              fi
            fi
          fi
          rpid=
          [ -r "${'$'}D/$READER_PID_FILE" ] && { IFS= read -r rpid < "${'$'}D/$READER_PID_FILE" || : ; }
          case ${'$'}rpid in ''|*[!0-9]*) rpid= ;; esac
          [ -n "${'$'}rpid" ] && kill "${'$'}rpid" 2>/dev/null
          wpid=
          [ -r "${'$'}D/$WRITER_PID_FILE" ] && { IFS= read -r wpid < "${'$'}D/$WRITER_PID_FILE" || : ; }
          case ${'$'}wpid in ''|*[!0-9]*) wpid= ;; esac
          [ -n "${'$'}wpid" ] && kill "${'$'}wpid" 2>/dev/null
          rm -rf "${'$'}D"
        fi
    """.trimIndent()

    /**
     * Escribe sessionId+cwd de la sesión ACP activa junto al proceso al que
     * pertenecen (Fase de gestión de sesiones remotas): hace posible que
     * [listCommand] recupere lo necesario para `session/load` sin depender del
     * registro local del dispositivo que abrió el tab (otro dispositivo, un
     * "Cerrar tab" que lo olvidó, o una reinstalación). Escritura atómica vía
     * archivo temporal + `mv`, para que un lector concurrente de [listCommand]
     * nunca vea el archivo a medio escribir.
     */
    fun writeSessionCommand(runDir: String, sessionId: String, cwd: String): String {
        require('\n' !in sessionId && '\t' !in sessionId) { "sessionId no puede contener salto de línea ni tab" }
        require('\n' !in cwd) { "cwd no puede contener salto de línea" }
        val dir = shellQuote(runDir)
        val tmp = shellQuote("$runDir/$SESSION_FILE.tmp")
        val dst = shellQuote("$runDir/$SESSION_FILE")
        return "mkdir -p $dir && printf '%s\\n%s\\n' ${shellQuote(sessionId)} ${shellQuote(cwd)} > $tmp && mv $tmp $dst"
    }

    /**
     * Barre los subdirectorios directos de `baseDir` reportando qué runDirs hay
     * y su estado real, para el listado "Sesiones del servidor…". Independiente
     * de cualquier registro local: encuentra también runDirs que este
     * dispositivo nunca abrió.
     */
    fun listCommand(baseDir: String): String = "sh -c " + shellQuote(listScript(baseDir))

    internal fun listScript(baseDir: String): String = """
        BASE=${shellQuote(baseDir)}
        TAB=${'$'}(printf '\t')
        NOW=${'$'}(date +%s 2>/dev/null)
        case ${'$'}NOW in ''|*[!0-9]*) NOW=0 ;; esac
        mtimeof() {
          _m=${'$'}(stat -c %Y "${'$'}1" 2>/dev/null)
          case ${'$'}_m in ''|*[!0-9]*) _m=${'$'}(stat -f %m "${'$'}1" 2>/dev/null) ;; esac
          case ${'$'}_m in ''|*[!0-9]*) _m= ;; esac
          printf %s "${'$'}_m"
        }
        if [ -d "${'$'}BASE" ] && cd "${'$'}BASE" 2>/dev/null; then
          for d in */; do
            d=${'$'}{d%/}
            [ -d "${'$'}d" ] || continue
            case ${'$'}d in *"${'$'}TAB"*) continue ;; esac
            pid=
            [ -r "${'$'}d/$PID_FILE" ] && { IFS= read -r pid < "${'$'}d/$PID_FILE" || : ; }
            case ${'$'}pid in ''|*[!0-9]*) pid= ;; esac
            state=STALE
            if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
              state=LIVE
              if [ -d "/proc/${'$'}pid/fd" ]; then
                fd0=${'$'}(readlink "/proc/${'$'}pid/fd/0" 2>/dev/null)
                fd1=${'$'}(readlink "/proc/${'$'}pid/fd/1" 2>/dev/null)
                if [ -n "${'$'}fd0" ] || [ -n "${'$'}fd1" ]; then
                  case "${'$'}fd0 ${'$'}fd1" in
                    *"/${'$'}d/$STDIN_FIFO"*|*"/${'$'}d/$STDOUT_FIFO"*) state=LIVE ;;
                    *) state=STALE ;;
                  esac
                fi
              fi
            fi
            att=-
            rpid=
            [ -r "${'$'}d/$READER_PID_FILE" ] && { IFS= read -r rpid < "${'$'}d/$READER_PID_FILE" || : ; }
            case ${'$'}rpid in ''|*[!0-9]*) rpid= ;; esac
            if [ -n "${'$'}rpid" ]; then
              if kill -0 "${'$'}rpid" 2>/dev/null; then att=ATTACHED; else att=FREE; fi
            fi
            o=${'$'}(mtimeof "${'$'}d/$STDOUT_FIFO")
            i=${'$'}(mtimeof "${'$'}d/$STDIN_FIFO")
            last=${'$'}o
            if [ -n "${'$'}i" ] && { [ -z "${'$'}last" ] || [ "${'$'}i" -gt "${'$'}last" ] ; }; then last=${'$'}i; fi
            idle=-
            if [ -n "${'$'}last" ] && [ "${'$'}NOW" -gt 0 ]; then
              idle=${'$'}((NOW - last))
              [ "${'$'}idle" -ge 0 ] || idle=0
            fi
            sid=
            cwd=
            if [ -r "${'$'}d/$SESSION_FILE" ]; then
              { IFS= read -r sid; IFS= read -r cwd; } < "${'$'}d/$SESSION_FILE" 2>/dev/null || :
            fi
            case ${'$'}sid in *"${'$'}TAB"*) sid= ;; esac
            [ -n "${'$'}pid" ] || pid=-
            [ -n "${'$'}sid" ] || sid=-
            [ -n "${'$'}cwd" ] || cwd=-
            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}d" "${'$'}state" "${'$'}pid" "${'$'}idle" "${'$'}att" "${'$'}sid" "${'$'}cwd"
          done
        fi
        printf '%s\n' $LIST_END
    """.trimIndent()

    /** Una fila del listado de [listCommand], ya interpretada. */
    data class RemoteAcpEntry(
        val dirName: String,
        val alive: Boolean,
        val pid: String?,
        val idleSeconds: Long?,
        val attached: Boolean?,
        val sessionId: String?,
        val cwd: String?,
    )

    /**
     * Parsea la salida de [listCommand]. Devuelve `null` si no llegó [LIST_END]:
     * como [AcpExecTransport] no expone exit status, es la única forma de
     * distinguir "sin sesiones" de "el exec se cortó a medias" (conexión caída,
     * timeout). Ignora cualquier línea que no tenga exactamente 7 campos o un
     * estado reconocido, así que ruido de shell (motd, un `.bashrc` con `echo`)
     * antes/después del listado no lo rompe.
     */
    fun parseListOutput(raw: String): List<RemoteAcpEntry>? {
        val lines = raw.lines()
        if (lines.none { it.trim() == LIST_END }) return null
        val entries = mutableListOf<RemoteAcpEntry>()
        for (line in lines) {
            val fields = line.split("\t", limit = 7)
            if (fields.size != 7) continue
            val dirName = fields[0]
            val state = fields[1]
            if (state != "LIVE" && state != "STALE") continue
            if (!isValidDirName(dirName)) continue
            entries += RemoteAcpEntry(
                dirName = dirName,
                alive = state == "LIVE",
                pid = fields[2].takeIf { it != "-" },
                idleSeconds = fields[3].takeIf { it != "-" }?.toLongOrNull(),
                attached = when (fields[4]) {
                    "ATTACHED" -> true
                    "FREE" -> false
                    else -> null
                },
                sessionId = fields[5].takeIf { it != "-" },
                cwd = fields[6].takeIf { it != "-" },
            )
        }
        return entries
    }

    private fun isValidDirName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name

    /** Quoting POSIX sh de un valor arbitrario como literal de una sola palabra. */
    fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
