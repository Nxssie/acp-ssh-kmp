package com.nxssie.acpssh.terminal

/** Referencia de color: paleta xterm (0..255) o RGB verdadero. */
sealed interface ColorRef {
    data class Palette(val index: Int) : ColorRef
    data class Rgb(val value: Int) : ColorRef
    data object Default : ColorRef
}

data class CellStyle(
    val fg: ColorRef = ColorRef.Default,
    val bg: ColorRef = ColorRef.Default,
    val bold: Boolean = false,
    val faint: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
) {
    companion object {
        val NONE = CellStyle()
    }
}

data class Cell(val ch: Char = ' ', val style: CellStyle = CellStyle.NONE)

/** Snapshot inmutable de la pantalla para la UI. */
data class TerminalState(
    val cols: Int,
    val rows: Int,
    val screen: List<List<Cell>>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val title: String? = null,
)

private const val MAX_SCROLLBACK = 2000

/**
 * Búfer de pantalla mutable (principal o alt). Interno al emulador: la UI solo
 * consume [TerminalState], que es inmutable.
 */
internal class TerminalBuffer(var cols: Int, var rows: Int) {

    private var chars = Array(rows) { CharArray(cols) { ' ' } }
    private var styles = Array(rows) { Array(cols) { CellStyle.NONE } }

    var scrollTop = 0
    var scrollBottom = rows - 1

    /** Últimas líneas que salieron por arriba de la pantalla principal. */
    val scrollback = ArrayDeque<String>()

    fun clear() {
        for (r in 0 until rows) {
            chars[r].fill(' ')
            styles[r].fill(CellStyle.NONE)
        }
    }

    fun set(row: Int, col: Int, ch: Char, style: CellStyle) {
        chars[row][col] = ch
        styles[row][col] = style
    }

    fun charAt(row: Int): CharArray = chars[row]
    fun styleAt(row: Int): Array<CellStyle> = styles[row]

    fun eraseCells(row: Int, from: Int, toInclusive: Int, style: CellStyle) {
        if (from > toInclusive) return
        val end = toInclusive.coerceAtMost(cols - 1)
        for (c in from.coerceAtLeast(0)..end) {
            chars[row][c] = ' '
            styles[row][c] = style
        }
    }

    fun eraseToEnd(row: Int, col: Int, style: CellStyle) {
        eraseCells(row, col, cols - 1, style)
        for (r in row + 1 until rows) {
            chars[r].fill(' ')
            styles[r].fill(style)
        }
    }

    fun eraseFromStart(row: Int, col: Int, style: CellStyle) {
        for (r in 0 until row) {
            chars[r].fill(' ')
            styles[r].fill(style)
        }
        eraseCells(row, 0, col, style)
    }

    fun eraseAll(style: CellStyle) {
        for (r in 0 until rows) {
            chars[r].fill(' ')
            styles[r].fill(style)
        }
    }

    fun scrollUp(n: Int, blank: CellStyle, recordScrollback: Boolean) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        if (recordScrollback) {
            for (r in scrollTop until scrollTop + count) pushScrollback(r)
        }
        for (r in scrollTop..scrollBottom - count) {
            chars[r + count].copyInto(chars[r])
            styles[r + count].copyInto(styles[r])
        }
        for (r in scrollBottom - count + 1..scrollBottom) {
            chars[r].fill(' ')
            styles[r].fill(blank)
        }
    }

    fun scrollDown(n: Int, blank: CellStyle) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        for (r in scrollBottom downTo scrollTop + count) {
            chars[r - count].copyInto(chars[r])
            styles[r - count].copyInto(styles[r])
        }
        for (r in scrollTop until scrollTop + count) {
            chars[r].fill(' ')
            styles[r].fill(blank)
        }
    }

    fun insertLines(row: Int, n: Int, blank: CellStyle) {
        // Fuera de la región de scroll: sin efecto (mismo comportamiento que xterm).
        if (row !in scrollTop..scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - row + 1)
        for (r in scrollBottom downTo row + count) {
            chars[r - count].copyInto(chars[r])
            styles[r - count].copyInto(styles[r])
        }
        for (r in row until row + count) {
            chars[r].fill(' ')
            styles[r].fill(blank)
        }
    }

    fun deleteLines(row: Int, n: Int, blank: CellStyle) {
        // Fuera de la región de scroll: sin efecto (mismo comportamiento que xterm).
        if (row !in scrollTop..scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - row + 1)
        for (r in row..scrollBottom - count) {
            chars[r + count].copyInto(chars[r])
            styles[r + count].copyInto(styles[r])
        }
        for (r in scrollBottom - count + 1..scrollBottom) {
            chars[r].fill(' ')
            styles[r].fill(blank)
        }
    }

    fun insertChars(row: Int, col: Int, n: Int) {
        val count = n.coerceIn(1, cols - col)
        for (c in cols - 1 downTo col + count) {
            chars[row][c] = chars[row][c - count]
            styles[row][c] = styles[row][c - count]
        }
        for (c in col until col + count) {
            chars[row][c] = ' '
            styles[row][c] = CellStyle.NONE
        }
    }

    fun deleteChars(row: Int, col: Int, n: Int) {
        val count = n.coerceIn(1, cols - col)
        for (c in col..cols - 1 - count) {
            chars[row][c] = chars[row][c + count]
            styles[row][c] = styles[row][c + count]
        }
        for (c in cols - count until cols) {
            chars[row][c] = ' '
            styles[row][c] = CellStyle.NONE
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val newChars = Array(newRows) { r ->
            if (r < rows) {
                val row = CharArray(newCols) { ' ' }
                chars[r].copyInto(row, endIndex = minOf(cols, newCols))
                row
            } else {
                CharArray(newCols) { ' ' }
            }
        }
        val newStyles = Array(newRows) { r ->
            if (r < rows) {
                val row = Array(newCols) { CellStyle.NONE }
                styles[r].copyInto(row, endIndex = minOf(cols, newCols))
                row
            } else {
                Array(newCols) { CellStyle.NONE }
            }
        }
        chars = newChars
        styles = newStyles
        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun pushScrollback(row: Int) {
        if (row < 0 || row >= rows) return
        val line = chars[row].joinToString("").trimEnd(' ')
        scrollback.addLast(line)
        while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
    }
}
