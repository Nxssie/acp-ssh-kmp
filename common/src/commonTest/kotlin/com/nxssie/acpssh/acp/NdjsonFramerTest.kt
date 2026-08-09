package com.nxssie.acpssh.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class NdjsonFramerTest {

    /**
     * Canal fake: sirve [reads] como chunks (para simular reads partidos y
     * chunks mayores que el buffer interno) y captura los writes.
     */
    private class FakeRawChannel(private val reads: List<ByteArray>) : RawByteChannel {
        private var chunkIndex = 0
        private var offset = 0
        val written = StringBuilder()
        var closed = false

        override suspend fun readChunk(buffer: ByteArray): Int {
            if (chunkIndex >= reads.size) return -1
            val src = reads[chunkIndex]
            val n = minOf(src.size - offset, buffer.size)
            src.copyInto(buffer, 0, offset, offset + n)
            offset += n
            if (offset >= src.size) {
                chunkIndex++
                offset = 0
            }
            return n
        }

        override suspend fun write(bytes: ByteArray) {
            written.append(bytes.decodeToString())
        }

        override suspend fun flush() {}

        override fun close() {
            closed = true
        }
    }

    private fun framer(chunks: List<String>): NdjsonFramer {
        val channel = FakeRawChannel(chunks.map { it.encodeToByteArray() })
        return NdjsonFramer(channel)
    }

    private fun lines(framer: NdjsonFramer): List<String> = runBlocking { framer.lines().toList() }

    private val channel = FakeRawChannel(emptyList())

    // --- lectura ---------------------------------------------------------------

    @Test
    fun singleLineThenEof() {
        val f = framer(listOf("""{"a":1}""" + "\n"))
        assertEquals(listOf("""{"a":1}"""), lines(f))
    }

    @Test
    fun multipleLinesInOneChunk() {
        val f = framer(listOf("1\n2\n3\n"))
        assertEquals(listOf("1", "2", "3"), lines(f))
    }

    @Test
    fun lineSplitByteByByte() {
        val f = framer("hello\n".chunked(1))
        assertEquals(listOf("hello"), lines(f))
    }

    @Test
    fun newlineMidChunkAndLineSpanningChunks() {
        val f = framer(listOf("he", "llo\nwor", "ld\n"))
        assertEquals(listOf("hello", "world"), lines(f))
    }

    @Test
    fun emptyLineBetween() {
        val f = framer(listOf("a\n\nb\n"))
        assertEquals(listOf("a", "", "b"), lines(f))
    }

    @Test
    fun largeLineNotTruncated() {
        // Línea de 300KB: fuerza el crecimiento del buffer interno por duplicación.
        val big = "x".repeat(300_000)
        val f = framer(listOf(big + "\n"))
        val result = lines(f)
        assertEquals(1, result.size)
        assertEquals(300_000, result[0].length)
        assertEquals(big, result[0])
    }

    @Test
    fun crlfIsStripped() {
        val f = framer(listOf("a\r\n"))
        assertEquals(listOf("a"), lines(f))
    }

    @Test
    fun partialLineEmittedAtEof() {
        val f = framer(listOf("partial"))
        assertEquals(listOf("partial"), lines(f))
    }

    @Test
    fun eofWithoutDataYieldsNothing() {
        val f = framer(emptyList())
        assertEquals(emptyList(), lines(f))
    }

    @Test
    fun utf8AcrossReadsIsDecodedAtLineBoundary() {
        // "ñ" = 2 bytes; se reparte entre chunks de 1 byte y se decodifica en el `\n`.
        val f = framer("ñ\n".chunked(1))
        assertEquals(listOf("ñ"), lines(f))
    }

    // --- escritura ---------------------------------------------------------------

    @Test
    fun writeLineAppendsNewline() {
        val channel = FakeRawChannel(emptyList())
        val f = NdjsonFramer(channel)
        runBlocking { f.writeLine("""{"x":1}""") }
        assertEquals("""{"x":1}""" + "\n", channel.written.toString())
    }

    @Test
    fun multipleWriteLines() {
        val channel = FakeRawChannel(emptyList())
        val f = NdjsonFramer(channel)
        runBlocking {
            f.writeLine("a")
            f.writeLine("b")
        }
        assertEquals("a\nb\n", channel.written.toString())
    }
}
