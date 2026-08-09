package com.nxssie.acpssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalEmulatorTest {

    private fun emulator(cols: Int = 10, rows: Int = 4) = TerminalEmulator(cols, rows)

    private fun TerminalEmulator.feed(s: String) = feed(s.encodeToByteArray())

    private fun TerminalEmulator.rowText(r: Int): String =
        state.value.screen[r].joinToString("") { it.ch.toString() }.trimEnd()

    // --- escritura básica ----------------------------------------------------

    @Test
    fun writesPlainText() {
        val e = emulator()
        e.feed("hola")
        assertEquals("hola", e.rowText(0))
        assertEquals(4, e.state.value.cursorCol)
    }

    @Test
    fun crlfMovesToNextLine() {
        val e = emulator()
        e.feed("ab\r\ncd")
        assertEquals("ab", e.rowText(0))
        assertEquals("cd", e.rowText(1))
    }

    @Test
    fun wrapsAtLastColumn() {
        val e = emulator(cols = 4, rows = 3)
        e.feed("abcdef")
        assertEquals("abcd", e.rowText(0))
        assertEquals("ef", e.rowText(1))
    }

    @Test
    fun scrollsWhenWritingPastBottom() {
        val e = emulator(cols = 5, rows = 2)
        e.feed("a\r\nb\r\nc")
        assertEquals("b", e.rowText(0))
        assertEquals("c", e.rowText(1))
    }

    @Test
    fun decodesUtf8AcrossChunks() {
        val e = emulator()
        val bytes = "ñ".encodeToByteArray()
        e.feed(byteArrayOf(bytes[0]))
        e.feed(byteArrayOf(bytes[1]))
        assertEquals("ñ", e.rowText(0))
    }

    // --- movimiento de cursor ------------------------------------------------

    @Test
    fun cupMovesCursor() {
        val e = emulator()
        e.feed("\u001b[2;3H")
        assertEquals(1, e.state.value.cursorRow)
        assertEquals(2, e.state.value.cursorCol)
    }

    @Test
    fun cupClampsOutOfRange() {
        val e = emulator(cols = 10, rows = 4)
        e.feed("\u001b[99;99H")
        assertEquals(3, e.state.value.cursorRow)
        assertEquals(9, e.state.value.cursorCol)
    }

    @Test
    fun saveAndRestoreCursor() {
        val e = emulator()
        e.feed("\u001b[2;5H\u001b7\u001b[1;1H\u001b8")
        assertEquals(1, e.state.value.cursorRow)
        assertEquals(4, e.state.value.cursorCol)
    }

    // --- borrado ---------------------------------------------------------------

    @Test
    fun eraseDisplayClearsScreen() {
        val e = emulator()
        e.feed("hola\u001b[2J")
        assertEquals("", e.rowText(0))
    }

    @Test
    fun eraseLineToEnd() {
        val e = emulator()
        e.feed("abcdef\u001b[1;3H\u001b[K")
        assertEquals("ab", e.rowText(0))
    }

    // --- región de scroll / IL-DL ---------------------------------------------

    @Test
    fun insertLineOutsideScrollRegionIsIgnored() {
        val e = emulator(cols = 5, rows = 6)
        // Región de scroll filas 1..2, cursor a fila 5 (fuera), CSI L no debe lanzar.
        e.feed("\u001b[1;2r\u001b[5;1Hx\u001b[5;1H\u001b[L")
        assertEquals("x", e.rowText(4)) // sin efecto
    }

    @Test
    fun deleteLineOutsideScrollRegionIsIgnored() {
        val e = emulator(cols = 5, rows = 6)
        e.feed("\u001b[1;2r\u001b[5;1Hx\u001b[5;1H\u001b[M")
        assertEquals("x", e.rowText(4))
    }

    @Test
    fun scrollRegionScrollsOnlyInsideRegion() {
        val e = emulator(cols = 5, rows = 4)
        e.feed("a\r\nb\r\nc\r\nd")           // filas: a b c d
        e.feed("\u001b[1;2r")                 // región 1..2
        e.feed("\u001b[2;1H\n")               // LF en el fondo de la región → scroll interno
        assertEquals("b", e.rowText(0))
        assertEquals("", e.rowText(1))
        assertEquals("c", e.rowText(2))       // fuera de la región: intacto
        assertEquals("d", e.rowText(3))
    }

    // --- alt screen -------------------------------------------------------------

    @Test
    fun altScreenSavesAndRestoresMain() {
        val e = emulator()
        e.feed("main")
        e.feed("\u001b[?1049h")   // enter alt
        e.feed("alt")
        assertEquals("alt", e.rowText(0))
        e.feed("\u001b[?1049l")   // exit alt
        assertEquals("main", e.rowText(0))
    }

    // --- SGR / colores -----------------------------------------------------------

    @Test
    fun sgrAppliesAndResets() {
        val e = emulator()
        e.feed("\u001b[1;31mrx\u001b[0mn")
        val row = e.state.value.screen[0]
        assertTrue(row[0].style.bold)
        assertEquals(ColorRef.Palette(1), row[0].style.fg)
        assertFalse(row[2].style.bold)
        assertEquals(ColorRef.Default, row[2].style.fg)
    }

    @Test
    fun sgr256AndTruecolor() {
        val e = emulator()
        e.feed("\u001b[38;5;200ma\u001b[38;2;10;20;30mb")
        val row = e.state.value.screen[0]
        assertEquals(ColorRef.Palette(200), row[0].style.fg)
        assertEquals(ColorRef.Rgb(TerminalColors.rgb(10, 20, 30)), row[1].style.fg)
    }

    // --- modos / respuestas --------------------------------------------------------

    @Test
    fun decckmTogglesApplicationCursorMode() {
        val e = emulator()
        e.feed("\u001b[?1h")
        assertTrue(e.cursorKeyApplicationMode)
        e.feed("\u001b[?1l")
        assertFalse(e.cursorKeyApplicationMode)
    }

    @Test
    fun dsrRespondsWithCursorPosition() {
        val e = emulator()
        var response = ""
        e.onResponse = { response = it.decodeToString() }
        e.feed("\u001b[3;7H\u001b[6n")
        assertEquals("\u001b[3;7R", response)
    }

    @Test
    fun oscSetsWindowTitle() {
        val e = emulator()
        e.feed("\u001b]0;mi titulo\u0007")
        assertEquals("mi titulo", e.state.value.title)
    }

    // --- resize ------------------------------------------------------------------

    @Test
    fun resizePreservesContentAndClampsCursor() {
        val e = emulator(cols = 10, rows = 4)
        e.feed("hola\u001b[4;10H")
        e.resize(6, 2)
        assertEquals("hola", e.rowText(0))
        assertTrue(e.state.value.cursorRow < 2)
        assertTrue(e.state.value.cursorCol < 6)
    }

    @Test
    fun malformedInputDoesNotThrow() {
        val e = emulator()
        // CSI truncado, bytes inválidos UTF-8, C1 sueltos, params enormes.
        e.feed(byteArrayOf(0x1b, '['.code.toByte()))
        e.feed(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x80.toByte()))
        e.feed("\u001b[99999999;99999999H\u001b[99999999L\u001b[99999999M")
        e.feed("\u001b[;;;m\u001b[38;5m\u001b[38;2;1m")
        // Si llega aquí sin lanzar, el test pasa.
        assertTrue(e.state.value.rows > 0)
    }
}
