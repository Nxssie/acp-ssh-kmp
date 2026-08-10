package com.nxssie.acpssh.acp

/**
 * Combina dos [RawByteChannel] unidireccionales (uno para leer, otro para
 * escribir) en un único canal bidireccional. Hace falta porque la Fase B abre
 * el pipe de entrada y el de salida del agente remoto como dos `exec` SSH
 * independientes (`cat acp-out` / `cat >> acp-in`), no un solo canal como en
 * la Fase A.
 */
class DuplexRawByteChannel(
    private val reader: RawByteChannel,
    private val writer: RawByteChannel,
) : RawByteChannel {

    override suspend fun readChunk(buffer: ByteArray): Int = reader.readChunk(buffer)

    override suspend fun write(bytes: ByteArray) = writer.write(bytes)

    override suspend fun flush() = writer.flush()

    override fun close() {
        reader.close()
        writer.close()
    }
}
