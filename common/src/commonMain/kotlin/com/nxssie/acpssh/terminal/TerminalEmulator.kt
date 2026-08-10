package com.nxssie.acpssh.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Emulador de terminal VT100/xterm (subconjunto para TUIs) en Kotlin puro.
 *
 * - [feed] consume bytes del stream remoto y actualiza [state].
 * - [onResponse] permite responder consultas del remoto (DSR/DA/CPR/window-size).
 * - La UI consulta [cursorKeyApplicationMode] para codificar flechas/Home/End.
 */
class TerminalEmulator(initialCols: Int = 80, initialRows: Int = 24) {

    private val _state = MutableStateFlow(initialSnapshot(initialCols, initialRows))
    val state: StateFlow<TerminalState> = _state

    /** Lo asigna el host: escribe bytes de respuesta en el stdin del remoto. */
    var onResponse: ((ByteArray) -> Unit)? = null

    private var main = TerminalBuffer(initialCols, initialRows)
    private var alt: TerminalBuffer? = null
    private val buffer: TerminalBuffer get() = alt ?: main

    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var wrapPending = false

    var cursorKeyApplicationMode = false
        private set
    var cursorVisible = true
        private set
    var bracketedPaste = false
        private set

    private var autowrap = true
    private var originMode = false
    private var title: String? = null
    private var currentStyle = CellStyle.NONE

    private enum class ParserState { GROUND, ESCAPE, CSI, OSC, DCS, CHARSET }

    private var parser = ParserState.GROUND
    private val csi = CsiParams()
    private val osc = StringBuilder()
    private var oscSt = false
    private var dcsSt = false
    private val utf8 = Utf8Decoder()
    private var dirty = false

    val cols: Int get() = buffer.cols
    val rows: Int get() = buffer.rows

    fun resize(cols: Int, rows: Int) {
        val c = cols.coerceAtLeast(1)
        val r = rows.coerceAtLeast(1)
        if (c == buffer.cols && r == buffer.rows) return
        main.resize(c, r)
        alt?.resize(c, r)
        cursorRow = cursorRow.coerceIn(0, r - 1)
        cursorCol = cursorCol.coerceIn(0, c - 1)
        wrapPending = false
        publish()
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val n = length.coerceIn(0, bytes.size)
        var i = 0
        while (i < n) {
            val b = bytes[i].toInt() and 0xff
            when (parser) {
                ParserState.GROUND -> feedGround(b)
                ParserState.ESCAPE -> feedEscape(b)
                ParserState.CSI -> feedCsi(b)
                ParserState.OSC -> feedOsc(b)
                ParserState.DCS -> feedDcs(b)
                ParserState.CHARSET -> parser = ParserState.GROUND
            }
            i++
        }
        if (dirty) {
            publish()
            dirty = false
        }
    }

    // --- parser: GROUND ------------------------------------------------------

    private fun feedGround(b: Int) {
        val cp = utf8.push(b)
        when {
            cp == -1 -> Unit // secuencia UTF-8 incompleta: esperar más bytes
            cp == -2 -> { writeCodepoint(0xFFFD); mark() }
            cp in 0x80..0x9F -> handleC1(cp)
            cp in 0x00..0x1F || cp == 0x7F -> handleControl(cp)
            else -> writeCodepoint(cp)
        }
    }

    private fun handleControl(c: Int) {
        when (c) {
            0x08 -> { cursorCol = maxOf(0, cursorCol - 1); wrapPending = false; mark() }
            0x09 -> {
                cursorCol = ((cursorCol / 8) + 1) * 8
                if (cursorCol >= cols) cursorCol = cols - 1
                wrapPending = false
                mark()
            }
            0x0A, 0x0B, 0x0C -> { linefeed(); mark() }
            0x0D -> { cursorCol = 0; wrapPending = false; mark() }
            0x07 -> Unit // BEL: ignorar
            0x1B -> { parser = ParserState.ESCAPE; utf8.reset() }
            0x18, 0x1A -> parser = ParserState.GROUND
            else -> Unit
        }
    }

    private fun handleC1(c: Int) {
        when (c) {
            0x90, 0x9E, 0x9F -> { dcsSt = false; parser = ParserState.DCS } // DCS/PM/APC
            0x9B -> { csi.reset(); parser = ParserState.CSI }
            0x9D -> { osc.clear(); oscSt = false; parser = ParserState.OSC }
            0x9C -> parser = ParserState.GROUND // ST
        }
    }

    private fun writeCodepoint(cp: Int) {
        // MVP: fuera del BMP → carácter de reemplazo (sin pares sustitutos).
        writeChar(if (cp <= 0xFFFF) cp.toChar() else '\uFFFD')
    }

    private fun writeChar(ch: Char) {
        if (wrapPending) {
            // Sin esto, wrapPending queda en true y CADA carácter siguiente
            // vuelve a disparar un salto de línea de más — el texto se
            // desparrama filas abajo en vez de escribirse en la fila que sigue.
            wrapPending = false
            cursorCol = 0
            linefeed()
        }
        if (cursorCol >= cols) cursorCol = cols - 1
        buffer.set(cursorRow, cursorCol, ch, currentStyle)
        if (cursorCol == cols - 1) wrapPending = true else cursorCol++
        mark()
    }

    private fun linefeed() {
        if (cursorRow == buffer.scrollBottom) {
            buffer.scrollUp(1, eraseStyle(), buffer === main)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == buffer.scrollTop) {
            buffer.scrollDown(1, eraseStyle())
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    // --- parser: ESCAPE ------------------------------------------------------

    private fun feedEscape(b: Int) {
        when (b.toChar()) {
            '[' -> { csi.reset(); parser = ParserState.CSI }
            ']' -> { osc.clear(); oscSt = false; parser = ParserState.OSC }
            'P', '^', '_' -> { dcsSt = false; parser = ParserState.DCS }
            '7' -> { savedRow = cursorRow; savedCol = cursorCol; parser = ParserState.GROUND }
            '8' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
                wrapPending = false
                mark()
                parser = ParserState.GROUND
            }
            'D' -> { linefeed(); mark(); parser = ParserState.GROUND }
            'E' -> { linefeed(); cursorCol = 0; wrapPending = false; mark(); parser = ParserState.GROUND }
            'M' -> { reverseIndex(); mark(); parser = ParserState.GROUND }
            'H' -> parser = ParserState.GROUND // HTS: tab stops fijos cada 8, ignorar
            'c' -> { reset(); parser = ParserState.GROUND }
            '(', ')', '*', '+' -> parser = ParserState.CHARSET
            else -> parser = ParserState.GROUND
        }
    }

    private fun reset() {
        main = TerminalBuffer(cols, rows)
        alt = null
        cursorRow = 0
        cursorCol = 0
        savedRow = 0
        savedCol = 0
        wrapPending = false
        cursorKeyApplicationMode = false
        autowrap = true
        originMode = false
        cursorVisible = true
        bracketedPaste = false
        title = null
        currentStyle = CellStyle.NONE
        mark()
        publish()
    }

    // --- parser: CSI ---------------------------------------------------------

    private fun feedCsi(b: Int) {
        when {
            b in 0x30..0x39 -> csi.appendDigit(b - 0x30)
            b == ';'.code || b == ':'.code -> csi.nextParam()
            b == '?'.code -> csi.privateMode = true
            b in 0x20..0x2F -> Unit // intermedios: ignorar
            b in 0x40..0x7E -> { dispatchCsi(b.toChar()); parser = ParserState.GROUND }
            else -> parser = ParserState.GROUND
        }
    }

    private fun dispatchCsi(f: Char) {
        val p = csi.parsed()
        val private = csi.privateMode
        fun param(i: Int): Int = if (i < p.size) p[i] else 0
        fun unit(i: Int): Int = param(i).let { if (it == 0) 1 else it }
        when (f) {
            'A' -> cursorUp(unit(0))
            'B' -> cursorDown(unit(0))
            'C' -> cursorRight(unit(0))
            'D' -> cursorLeft(unit(0))
            'E' -> { cursorDown(unit(0)); cursorCol = 0; wrapPending = false }
            'F' -> { cursorUp(unit(0)); cursorCol = 0; wrapPending = false }
            'G' -> { cursorCol = (unit(0) - 1).coerceIn(0, cols - 1); wrapPending = false; mark() }
            'd' -> { cursorRow = (unit(0) - 1).coerceIn(0, rows - 1); wrapPending = false; mark() }
            'H', 'f' -> cup(unit(0), unit(1))
            'J' -> eraseDisplay(param(0))
            'K' -> eraseLine(param(0))
            'L' -> { buffer.insertLines(cursorRow, unit(0), eraseStyle()); mark() }
            'M' -> { buffer.deleteLines(cursorRow, unit(0), eraseStyle()); mark() }
            'P' -> { buffer.deleteChars(cursorRow, cursorCol, unit(0)); mark() }
            'X' -> { buffer.eraseCells(cursorRow, cursorCol, cursorCol + unit(0) - 1, eraseStyle()); mark() }
            'S' -> { buffer.scrollUp(unit(0), eraseStyle(), buffer === main); mark() }
            'T' -> { buffer.scrollDown(unit(0), eraseStyle()); mark() }
            '@' -> { buffer.insertChars(cursorRow, cursorCol, unit(0)); mark() }
            'Z' -> backTab(unit(0))
            'm' -> sgr(p)
            'r' -> setScrollRegion(param(0), param(1))
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
                wrapPending = false
                mark()
            }
            'h' -> if (private) setPrivateModes(p, true)
            'l' -> if (private) setPrivateModes(p, false)
            'n' -> dsr(p)
            'c' -> if (!private && (p.isEmpty() || p.all { it == 0 })) deviceAttributes()
            'q' -> if (private && p.isNotEmpty()) { cursorVisible = true; mark() }
            't' -> windowOp(p)
            else -> Unit
        }
        csi.reset()
    }

    private fun cursorUp(n: Int) { cursorRow = maxOf(0, cursorRow - n); wrapPending = false; mark() }
    private fun cursorDown(n: Int) { cursorRow = minOf(rows - 1, cursorRow + n); wrapPending = false; mark() }
    private fun cursorRight(n: Int) { cursorCol = minOf(cols - 1, cursorCol + n); wrapPending = false; mark() }
    private fun cursorLeft(n: Int) { cursorCol = maxOf(0, cursorCol - n); wrapPending = false; mark() }

    private fun cup(row1: Int, col1: Int) {
        cursorRow = if (originMode) {
            (buffer.scrollTop + (row1 - 1)).coerceIn(buffer.scrollTop, buffer.scrollBottom)
        } else {
            (row1 - 1).coerceIn(0, rows - 1)
        }
        cursorCol = (col1 - 1).coerceIn(0, cols - 1)
        wrapPending = false
        mark()
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> buffer.eraseToEnd(cursorRow, cursorCol, eraseStyle())
            1 -> buffer.eraseFromStart(cursorRow, cursorCol, eraseStyle())
            2 -> buffer.eraseAll(eraseStyle())
            3 -> { buffer.eraseAll(eraseStyle()); buffer.scrollback.clear() }
        }
        mark()
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> buffer.eraseCells(cursorRow, cursorCol, cols - 1, eraseStyle())
            1 -> buffer.eraseCells(cursorRow, 0, cursorCol, eraseStyle())
            2 -> buffer.eraseCells(cursorRow, 0, cols - 1, eraseStyle())
        }
        mark()
    }

    private fun backTab(n: Int) {
        repeat(n) {
            val stop = (cursorCol / 8) * 8
            cursorCol = if (cursorCol == stop && stop > 0) stop - 8 else stop
        }
        wrapPending = false
        mark()
    }

    private fun setScrollRegion(top1: Int, bottom1: Int) {
        // Sin parámetros (o 0;0) → región completa.
        val full = top1 == 0 && bottom1 == 0
        val top = if (full) 0 else (top1 - 1).coerceIn(0, rows - 1)
        val bottom = if (full) rows - 1 else (bottom1 - 1).coerceIn(top + 1, rows - 1)
        buffer.scrollTop = top
        buffer.scrollBottom = bottom
        if (originMode) cursorRow = top
        wrapPending = false
        mark()
    }

    private fun setPrivateModes(p: List<Int>, enable: Boolean) {
        for (m in p) {
            when (m) {
                1 -> cursorKeyApplicationMode = enable
                6 -> {
                    originMode = enable
                    cursorRow = if (enable) buffer.scrollTop else 0
                    cursorCol = 0
                }
                7 -> autowrap = enable
                25 -> cursorVisible = enable
                47, 1047, 1049 -> if (enable) enterAltScreen(m == 1049) else exitAltScreen(m == 1049)
                2004 -> bracketedPaste = enable
            }
        }
        mark()
    }

    private fun enterAltScreen(saveCursor: Boolean) {
        if (saveCursor) { savedRow = cursorRow; savedCol = cursorCol }
        if (alt == null) alt = TerminalBuffer(cols, rows) else alt!!.clear()
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
    }

    private fun exitAltScreen(restoreCursor: Boolean) {
        alt = null
        if (restoreCursor) {
            cursorRow = savedRow.coerceIn(0, rows - 1)
            cursorCol = savedCol.coerceIn(0, cols - 1)
        }
        wrapPending = false
    }

    private fun dsr(p: List<Int>) {
        when (p.firstOrNull() ?: 0) {
            5 -> respond("\u001b[0n")
            6 -> respond("\u001b[${cursorRow + 1};${cursorCol + 1}R")
        }
    }

    private fun deviceAttributes() {
        // VT100 con video avanzado: suficiente para la mayoría de TUIs.
        respond("\u001b[?1;2c")
    }

    private fun windowOp(p: List<Int>) {
        when (p.firstOrNull() ?: 0) {
            18, 19 -> respond("\u001b[8;${rows};${cols}t")
        }
    }

    private fun respond(s: String) {
        onResponse?.invoke(s.encodeToByteArray())
    }

    private fun sgr(p: List<Int>) {
        var i = 0
        while (i < p.size) {
            when (val code = p[i]) {
                0 -> currentStyle = CellStyle.NONE
                1 -> currentStyle = currentStyle.copy(bold = true)
                2 -> currentStyle = currentStyle.copy(faint = true)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4, 21 -> currentStyle = currentStyle.copy(underline = true)
                5, 6 -> Unit // parpadeo: ignorar
                7 -> currentStyle = currentStyle.copy(reverse = true)
                8, 9 -> Unit // conceal/strike: ignorar
                22 -> currentStyle = currentStyle.copy(bold = false, faint = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                25 -> Unit
                27 -> currentStyle = currentStyle.copy(reverse = false)
                28, 29 -> Unit
                in 30..37 -> currentStyle = currentStyle.copy(fg = ColorRef.Palette(code - 30))
                38 -> {
                    val (next, ref) = extendedColor(p, i)
                    if (ref != null) currentStyle = currentStyle.copy(fg = ref)
                    i = next
                    continue
                }
                39 -> currentStyle = currentStyle.copy(fg = ColorRef.Default)
                in 40..47 -> currentStyle = currentStyle.copy(bg = ColorRef.Palette(code - 40))
                48 -> {
                    val (next, ref) = extendedColor(p, i)
                    if (ref != null) currentStyle = currentStyle.copy(bg = ref)
                    i = next
                    continue
                }
                49 -> currentStyle = currentStyle.copy(bg = ColorRef.Default)
                in 90..97 -> currentStyle = currentStyle.copy(fg = ColorRef.Palette(code - 90 + 8))
                in 100..107 -> currentStyle = currentStyle.copy(bg = ColorRef.Palette(code - 100 + 8))
            }
            i++
        }
        mark()
    }

    private fun extendedColor(p: List<Int>, start: Int): Pair<Int, ColorRef?> {
        if (start + 1 >= p.size) return start + 2 to null
        return when (p[start + 1]) {
            5 -> if (start + 2 < p.size) {
                (start + 3) to ColorRef.Palette(p[start + 2].coerceIn(0, 255))
            } else {
                start + 3 to null
            }
            2 -> if (start + 4 < p.size) {
                val r = p[start + 2].coerceIn(0, 255)
                val g = p[start + 3].coerceIn(0, 255)
                val b = p[start + 4].coerceIn(0, 255)
                (start + 5) to ColorRef.Rgb(TerminalColors.rgb(r, g, b))
            } else {
                start + 5 to null
            }
            else -> start + 2 to null
        }
    }

    // --- parser: OSC / DCS ---------------------------------------------------

    private fun feedOsc(b: Int) {
        when {
            b == 0x07 -> { applyOsc(); parser = ParserState.GROUND }
            oscSt -> {
                if (b == '\\'.code) { applyOsc(); parser = ParserState.GROUND } else oscSt = false
            }
            b == 0x1B -> oscSt = true
            b >= 0x20 -> osc.append(b.toChar())
            else -> Unit
        }
    }

    private fun applyOsc() {
        val s = osc.toString()
        val idx = s.indexOf(';')
        if (idx >= 0) {
            val kind = s.substring(0, idx)
            val data = s.substring(idx + 1)
            if ((kind == "0" || kind == "2") && data.isNotEmpty()) title = data
        }
        osc.clear()
        oscSt = false
        mark()
    }

    private fun feedDcs(b: Int) {
        if (b == 0x1B) dcsSt = true
        else if (dcsSt && b == '\\'.code) parser = ParserState.GROUND
        else dcsSt = false
    }

    // --- utilidades ----------------------------------------------------------

    private fun eraseStyle(): CellStyle = CellStyle(bg = currentStyle.bg)

    private fun mark() {
        dirty = true
    }

    private fun publish() {
        val b = buffer
        val screen = ArrayList<List<Cell>>(b.rows)
        for (r in 0 until b.rows) {
            val rowChars = b.charAt(r)
            val rowStyles = b.styleAt(r)
            val row = ArrayList<Cell>(b.cols)
            for (c in 0 until b.cols) row.add(Cell(rowChars[c], rowStyles[c]))
            screen.add(row)
        }
        _state.value = TerminalState(b.cols, b.rows, screen, cursorRow, cursorCol, cursorVisible, title)
    }

    private fun initialSnapshot(cols: Int, rows: Int) = TerminalState(
        cols, rows,
        List(rows) { List(cols) { Cell() } },
        0, 0, true, null,
    )

    private class CsiParams {
        var privateMode = false
        private val raw = StringBuilder()

        fun reset() {
            raw.clear()
            privateMode = false
        }

        fun appendDigit(d: Int) {
            raw.append(d)
        }

        fun nextParam() {
            raw.append(';')
        }

        fun parsed(): List<Int> = raw.split(';').map { it.toIntOrNull() ?: 0 }
    }

    private class Utf8Decoder {
        private var need = 0
        private var cp = 0

        fun reset() {
            need = 0
            cp = 0
        }

        /**
         * Devuelve el codepoint completo, -1 si necesita más bytes,
         * -2 si el byte es inválido (no consumible).
         */
        fun push(b: Int): Int {
            if (need > 0) {
                if (b !in 0x80..0xBF) {
                    reset()
                    return push(b)
                }
                cp = (cp shl 6) or (b and 0x3F)
                if (--need == 0) {
                    val r = cp
                    reset()
                    return r
                }
                return -1
            }
            if (b < 0x80) return b
            if (b in 0x80..0x9F) return b // C1 crudo (byte suelto)
            if (b in 0xC2..0xDF) { need = 1; cp = b and 0x1F; return -1 }
            if (b in 0xE0..0xEF) { need = 2; cp = b and 0x0F; return -1 }
            if (b in 0xF0..0xF4) { need = 3; cp = b and 0x07; return -1 }
            return -2 // 0xA0..0xBF sueltos, 0xC0/0xC1, 0xF5..0xFF
        }
    }
}
