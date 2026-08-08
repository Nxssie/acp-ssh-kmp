package com.nxssie.acpssh

import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Sesión SSH autenticada. El transporte ACP (Fase 2) abrirá un canal `exec`
 * por sesión para hablar el protocolo sobre sus streams.
 */
interface SshSession : AutoCloseable {
    /** Ejecuta un comando remoto y expone sus streams como canal bidireccional. */
    suspend fun exec(command: String): ExecChannel

    /**
     * Abre un shell interactivo con PTY (para terminales/TUI).
     *
     * El comando inicial no se pasa aquí a propósito: se escribe en [PtyShell.stdin]
     * una vez conectado, así el resize funciona siempre (a diferencia de `exec`,
     * el canal shell expone `changeWindowDimensions`).
     */
    suspend fun openShell(term: String = "xterm-256color", cols: Int = 80, rows: Int = 24): PtyShell
}

/**
 * Canal de un comando remoto: streams bidireccionales + estado de salida.
 *
 * `stdin`/`stdout` son los que transportará ACP (NDJSON newline-delimited);
 * `stderr` debe drenarse en paralelo para no bloquear el canal.
 */
interface ExecChannel : AutoCloseable {
    /** stdout del proceso remoto. */
    val stdout: Source
    /** stderr del proceso remoto (drenar en coroutine aparte). */
    val stderr: Source
    /** stdin del proceso remoto. */
    val stdin: Sink
    /**
     * Espera a que el proceso remoto termine y devuelve su exit status (-1 si el
     * canal se cerró sin reportarlo). No consume `stdout`/`stderr`: el llamador
     * debe drenarlos en coroutines aparte o el proceso remoto puede bloquearse.
     */
    suspend fun exitStatus(): Int
}

/**
 * Canal de shell interactivo con PTY. `stdout`/`stderr` deben drenarse en
 * coroutines aparte; `stdin` recibe los bytes del teclado/UI.
 */
interface PtyShell : AutoCloseable {
    /** stdout del shell remoto (salida del TUI). */
    val stdout: Source
    /** stderr del shell remoto (drenar en coroutine aparte). */
    val stderr: Source
    /** stdin del shell remoto (envío de teclas). */
    val stdin: Sink
    /** Notifica al remoto el cambio de tamaño del terminal (window-change). */
    suspend fun resize(cols: Int, rows: Int)
}
