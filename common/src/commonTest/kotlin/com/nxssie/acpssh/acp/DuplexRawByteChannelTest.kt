package com.nxssie.acpssh.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DuplexRawByteChannelTest {

    private class RecordingChannel(private val reads: List<ByteArray> = emptyList()) : RawByteChannel {
        private var index = 0
        val written = StringBuilder()
        var flushed = false
        var closed = false

        override suspend fun readChunk(buffer: ByteArray): Int {
            if (index >= reads.size) return -1
            val src = reads[index++]
            src.copyInto(buffer)
            return src.size
        }

        override suspend fun write(bytes: ByteArray) {
            written.append(bytes.decodeToString())
        }

        override suspend fun flush() {
            flushed = true
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun readsComeFromReaderChannel() = runBlocking {
        val reader = RecordingChannel(listOf("hello".encodeToByteArray()))
        val writer = RecordingChannel()
        val duplex = DuplexRawByteChannel(reader, writer)

        val buffer = ByteArray(16)
        val n = duplex.readChunk(buffer)

        assertEquals("hello", buffer.decodeToString(0, n))
    }

    @Test
    fun writesAndFlushGoToWriterChannel() = runBlocking {
        val reader = RecordingChannel()
        val writer = RecordingChannel()
        val duplex = DuplexRawByteChannel(reader, writer)

        duplex.write("payload".encodeToByteArray())
        duplex.flush()

        assertEquals("payload", writer.written.toString())
        assertTrue(writer.flushed)
    }

    @Test
    fun closeClosesBothChannels() {
        val reader = RecordingChannel()
        val writer = RecordingChannel()
        val duplex = DuplexRawByteChannel(reader, writer)

        duplex.close()

        assertTrue(reader.closed)
        assertTrue(writer.closed)
    }
}
