package com.nxssie.acpssh.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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

    // runInterruptible: sin esto, un `withTimeout`/cancel no puede interrumpir
    // esta llamada — es una lectura bloqueante de Java por debajo (ChannelInputStream
    // de SSHJ), no un punto de suspensión cooperativo. Confirmado que SSHJ
    // convierte la interrupción del hilo en InterruptedIOException correctamente.
    override suspend fun readChunk(buffer: ByteArray): Int = runInterruptible(Dispatchers.IO) {
        command.inputStream.read(buffer)
    }

    override suspend fun write(bytes: ByteArray) = runInterruptible(Dispatchers.IO) {
        command.outputStream.write(bytes)
    }

    override suspend fun flush() = runInterruptible(Dispatchers.IO) {
        command.outputStream.flush()
    }

    override fun close() {
        stderrScope.cancel()
        closeChannelWithTimeout({ command.close() }, { session.close() })
    }
}

/**
 * Cierra un canal SSHJ acotando la espera: `Channel.close()` bloquea hasta
 * recibir el ACK de cierre del remoto, y para el canal `exec` que lee el FIFO
 * persistente de ACP ([RemoteAcpProcess]) ese ACK nunca llega — el proceso
 * remoto que respalda el canal sigue vivo a propósito para sobrevivir a la
 * desconexión, así que sin este límite el cierre se cuelga con el timeout por
 * defecto de SSHJ (30s). El cierre real sigue en un hilo daemon: si termina
 * antes del límite no hay diferencia; si no, lo abandona (se libera cuando el
 * transporte SSH se desconecte del todo) y el llamador no se bloquea.
 */
private fun closeChannelWithTimeout(vararg actions: () -> Unit, timeoutMs: Long = 2_000) {
    val thread = Thread { actions.forEach { runCatching(it) } }
    thread.isDaemon = true
    thread.start()
    thread.join(timeoutMs)
}
