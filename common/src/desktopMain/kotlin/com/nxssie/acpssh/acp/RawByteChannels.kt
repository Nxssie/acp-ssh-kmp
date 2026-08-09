package com.nxssie.acpssh.acp

import com.nxssie.acpssh.ExecChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bridge del canal `exec` de desktop (kotlinx.io Source/Sink) al [RawByteChannel]
 * común, para que el [NdjsonFramer] funcione sobre el `SshSession.exec()` ya
 * implementado en desktopMain sin plomería SSH nueva.
 */
fun ExecChannel.asRawByteChannel(): RawByteChannel {
    // stderr no lo consume nadie más: si no se drena, la ventana del canal SSH
    // se llena y el remoto se bloquea al escribir ahí (colgando también stdout).
    val stderrScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    stderrScope.launch {
        val buf = ByteArray(4096)
        runCatching { while (stderr.readAtMostTo(buf) >= 0) { /* descartado */ } }
    }

    return object : RawByteChannel {
        override suspend fun readChunk(buffer: ByteArray): Int {
            val n = stdout.readAtMostTo(buffer)
            // kotlinx.io devuelve 0 o -1 al agotar el source; -1 es el EOF del contrato.
            return if (n <= 0) -1 else n
        }

        override suspend fun write(bytes: ByteArray) {
            stdin.write(bytes)
        }

        override suspend fun flush() {
            stdin.flush()
        }

        override fun close() {
            stderrScope.cancel()
            this@asRawByteChannel.close()
        }
    }
}
