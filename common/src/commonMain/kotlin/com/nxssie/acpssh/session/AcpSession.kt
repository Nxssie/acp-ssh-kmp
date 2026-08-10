package com.nxssie.acpssh.session

import com.nxssie.acpssh.acp.AcpClient
import com.nxssie.acpssh.acp.AcpExecTransport
import com.nxssie.acpssh.acp.DuplexRawByteChannel
import com.nxssie.acpssh.acp.NdjsonFramer
import com.nxssie.acpssh.acp.RawByteChannel
import com.nxssie.acpssh.acp.RemoteAcpProcess
import com.nxssie.acpssh.acp.readAllToString
import kotlinx.coroutines.CoroutineScope

/** Resultado de arrancar una sesión ACP completa. */
data class AcpSessionResult(
    val client: AcpClient,
    val initialize: com.nxssie.acpssh.acp.InitializeResult,
    val newSession: com.nxssie.acpssh.acp.NewSessionResult,
)

/**
 * Orquestación del arranque ACP, común a Android y desktop: lanza el agente
 * remoto persistente (Fase B, FIFOs + `setsid`, idempotente), abre los canales
 * reader/writer, monta el [NdjsonFramer] y negocia `initialize` + `session/new`.
 *
 * El proceso remoto vive desacoplado de la sesión SSH: si esta se cae, el
 * agente sigue corriendo y un reconexión reabre los pipes sin perder mensajes.
 */
class AcpSession(
    private val transport: AcpExecTransport,
    private val config: TerminalConfig,
    private val scope: CoroutineScope,
) {
    /** Directorio remoto del agente; relativo al home del usuario por defecto. */
    val runDir: String = config.acpRunDir?.takeIf { it.isNotBlank() } ?: DEFAULT_RUN_DIR

    /** Comando de arranque del agente ACP (el `remoteCommand` de la config). */
    val agentCommand: String = config.remoteCommand?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT

    private var duplex: RawByteChannel? = null
    private var client: AcpClient? = null

    /** Arranca el agente remoto y negocia la sesión ACP. */
    suspend fun start(): AcpSessionResult {
        val launchOut = execCapture(RemoteAcpProcess.launchScript(runDir, agentCommand))
        check(launchOut == "STARTED" || launchOut == "ALREADY_RUNNING") {
            "respuesta de arranque del agente inesperada: '$launchOut'"
        }

        val reader = transport.exec(RemoteAcpProcess.readerCommand(runDir))
        val writer = transport.exec(RemoteAcpProcess.writerCommand(runDir))
        val duplex = DuplexRawByteChannel(reader, writer)
        this.duplex = duplex

        val client = AcpClient(NdjsonFramer(duplex), scope)
        client.start()
        val initialize = client.initialize()
        val cwd = config.acpCwd?.takeIf { it.isNotBlank() } ?: execCapture("pwd")
        val newSession = client.newSession(cwd)
        this.client = client
        return AcpSessionResult(client, initialize, newSession)
    }

    fun close() {
        runCatching { client?.close() }
        runCatching { duplex?.close() }
        client = null
        duplex = null
    }

    /** Ejecuta un comando corto y captura su stdout hasta EOF. */
    private suspend fun execCapture(command: String): String {
        val channel = transport.exec(command)
        try {
            return channel.readAllToString().trim()
        } finally {
            channel.close()
        }
    }

    companion object {
        const val DEFAULT_RUN_DIR = ".acp-ssh-kmp"
        const val DEFAULT_AGENT = "claude-code-acp"
    }
}
