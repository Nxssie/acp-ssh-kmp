package com.nxssie.acpssh.acp

/**
 * Abstracción mínima que cada plataforma implementa sobre su capa SSH: abrir
 * canales `exec` crudos (sin PTY) y cerrar la conexión. Lo que comparten los
 * dos hosts ACP (arranque del agente remoto, canales reader/writer, framing,
 * protocolo) vive en [com.nxssie.acpssh.session.AcpSession] sobre esto.
 */
interface AcpExecTransport : AutoCloseable {
    /** Abre un canal `exec` sin PTY sobre la conexión ya autenticada. */
    suspend fun exec(command: String): RawByteChannel

    override fun close()
}

/** Lee el canal hasta EOF (para comandos de corta vida como el arranque del agente). */
suspend fun RawByteChannel.readAllToString(): String {
    val out = StringBuilder()
    val buffer = ByteArray(8192)
    while (true) {
        val n = readChunk(buffer)
        if (n == -1) break
        out.append(buffer.decodeToString(0, n))
    }
    return out.toString()
}
