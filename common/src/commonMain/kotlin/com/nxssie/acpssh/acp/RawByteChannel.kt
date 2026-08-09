package com.nxssie.acpssh.acp

/**
 * Canal de bytes bidireccional para el transporte ACP (NDJSON sobre SSH `exec`),
 * común a Android (java.io crudo) y desktop (kotlinx.io vía [com.nxssie.acpssh.ExecChannel]).
 */
interface RawByteChannel : AutoCloseable {
    /** Lee hasta [buffer.size] bytes; -1 si el remoto cerró (EOF). */
    suspend fun readChunk(buffer: ByteArray): Int
    suspend fun write(bytes: ByteArray)
    suspend fun flush()
}
