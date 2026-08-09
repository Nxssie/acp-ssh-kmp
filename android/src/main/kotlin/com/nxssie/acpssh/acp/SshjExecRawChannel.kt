package com.nxssie.acpssh.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * Canal `exec` sin PTY para Android: pipes planos sobre los streams crudos de
 * SSHJ, tal como los necesita ACP (el binario remoto espera stdio plano, no un
 * pty que pueda cambiar su buffering/salida).
 */
class SshjExecRawChannel(
    private val command: Session.Command,
    private val session: Session,
) : RawByteChannel {

    // stderr no lo consume nadie más: si no se drena, la ventana del canal SSH
    // se llena y el remoto se bloquea al escribir ahí (colgando también stdout).
    private val stderrScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        stderrScope.launch {
            val buffer = ByteArray(4096)
            runCatching { while (command.errorStream.read(buffer) >= 0) { /* descartado */ } }
        }
    }

    override suspend fun readChunk(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        command.inputStream.read(buffer)
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        command.outputStream.write(bytes)
    }

    override suspend fun flush() = withContext(Dispatchers.IO) {
        command.outputStream.flush()
    }

    override fun close() {
        stderrScope.cancel()
        runCatching { command.close() }
        runCatching { session.close() }
    }
}
