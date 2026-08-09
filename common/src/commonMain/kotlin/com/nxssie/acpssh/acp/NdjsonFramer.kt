package com.nxssie.acpssh.acp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Framing NDJSON (newline-delimited JSON-RPC) sobre un [RawByteChannel]:
 * bufferiza bytes hasta encontrar `\n` y emite la línea completa como String.
 *
 * Sin tamaño máximo de línea: el buffer crece por duplicación (diffs grandes).
 * El charset se decodifica solo en límites de línea, así un char UTF-8
 * multicbyte partido entre reads queda íntegro en el buffer hasta el `\n`.
 */
class NdjsonFramer(private val channel: RawByteChannel) {

    /** Flujo de líneas completas (sin el `\n`; `\r\n` se normaliza a `\n`). */
    fun lines(): Flow<String> = flow {
        var buffer = ByteArray(256)
        var length = 0
        val chunk = ByteArray(8192)
        while (true) {
            val n = channel.readChunk(chunk)
            if (n == -1) {
                // Línea parcial al cerrar el remoto: se emite igual (el parser
                // JSON del llamador decide si la acepta o la descarta).
                if (length > 0) emit(decode(buffer, length))
                return@flow
            }
            for (i in 0 until n) {
                val b = chunk[i]
                if (b == NEWLINE) {
                    emit(decode(buffer, length))
                    length = 0
                } else {
                    if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                    buffer[length++] = b
                }
            }
        }
    }

    /** Escribe un mensaje NDJSON (línea + `\n`) y hace flush. */
    suspend fun writeLine(line: String) {
        channel.write((line + "\n").encodeToByteArray())
        channel.flush()
    }

    private fun decode(bytes: ByteArray, length: Int): String =
        String(bytes, 0, length, Charsets.UTF_8).trimEnd('\r')

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
