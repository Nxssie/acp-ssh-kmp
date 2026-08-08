package com.nxssie.acpssh.terminal

/** Paleta xterm de 256 colores en ARGB. */
object TerminalColors {

    /** Color de la paleta (0..255): 16 básicos, cubo 6×6×6, escala de grises. */
    fun rgbFor(index: Int): Int {
        val i = index.coerceIn(0, 255)
        return when {
            i < 16 -> BASIC[i]
            i < 232 -> {
                val n = i - 16
                val r = n / 36
                val g = (n % 36) / 6
                val b = n % 6
                rgb(level(r), level(g), level(b))
            }
            else -> {
                val v = 8 + (i - 232) * 10
                rgb(v, v, v)
            }
        }
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)

    private fun level(n: Int): Int = 55 + n * 40

    // Paleta básica (Tango), índice = valor ANSI 0..15.
    private val BASIC = intArrayOf(
        0x000000, 0xcc0000, 0x4e9a06, 0xc4a000,
        0x3465a4, 0x75507b, 0x06989a, 0xd3d7cf,
        0x555753, 0xef2929, 0x8ae234, 0xfce94f,
        0x729fcf, 0xad7fa8, 0x34e2e2, 0xeeeeec,
    )
}
