package com.nxssie.acpssh.terminal

/**
 * Secuencias de teclado que envía la UI al remoto.
 *
 * Las flechas/Home/End cambian de forma según el modo cursor de aplicación
 * (DECCKM, `CSI ? 1 h`): el emulador expone `cursorKeyApplicationMode` y la UI
 * consulta el valor en el momento de pulsar (no en composición, que no
 * reacciona al cambio).
 */
object Ansi {
    const val ESC: Int = 0x1b

    /** Ctrl + letra → byte 1..26 (`Ctrl(C)` = 0x03). */
    fun ctrl(letter: Char): Byte = (letter.lowercaseChar() - 'a' + 1).toByte()

    enum class Arrow { UP, DOWN, LEFT, RIGHT }

    fun arrow(key: Arrow, applicationCursorMode: Boolean): ByteArray {
        val prefix = if (applicationCursorMode) {
            byteArrayOf(ESC.toByte(), 'O'.code.toByte())
        } else {
            byteArrayOf(ESC.toByte(), '['.code.toByte())
        }
        val code = when (key) {
            Arrow.UP -> 'A'.code
            Arrow.DOWN -> 'B'.code
            Arrow.RIGHT -> 'C'.code
            Arrow.LEFT -> 'D'.code
        }.toByte()
        return byteArrayOf(prefix[0], prefix[1], code)
    }

    fun home(applicationCursorMode: Boolean): ByteArray =
        if (applicationCursorMode) {
            byteArrayOf(ESC.toByte(), 'O'.code.toByte(), 'H'.code.toByte())
        } else {
            byteArrayOf(ESC.toByte(), '['.code.toByte(), '1'.code.toByte(), '~'.code.toByte())
        }

    fun end(applicationCursorMode: Boolean): ByteArray =
        if (applicationCursorMode) {
            byteArrayOf(ESC.toByte(), 'O'.code.toByte(), 'F'.code.toByte())
        } else {
            byteArrayOf(ESC.toByte(), '['.code.toByte(), '4'.code.toByte(), '~'.code.toByte())
        }

    fun pgUp(): ByteArray = byteArrayOf(ESC.toByte(), '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())

    fun pgDn(): ByteArray = byteArrayOf(ESC.toByte(), '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())
}
