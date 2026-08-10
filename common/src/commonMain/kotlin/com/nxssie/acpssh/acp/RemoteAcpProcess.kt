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

    /** Comando que expone el pipe de salida como stdout de un `exec` SSH. */
    fun readerCommand(runDir: String): String =
        "cd ${shellQuote(runDir)} && exec cat $STDOUT_FIFO"

    /** Comando que vuelca el stdin de un `exec` SSH al pipe de entrada. */
    fun writerCommand(runDir: String): String =
        "cd ${shellQuote(runDir)} && exec cat >> $STDIN_FIFO"

    /** Quoting POSIX sh de un valor arbitrario como literal de una sola palabra. */
    fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
