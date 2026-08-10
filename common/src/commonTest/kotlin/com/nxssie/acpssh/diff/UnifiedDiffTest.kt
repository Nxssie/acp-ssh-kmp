package com.nxssie.acpssh.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedDiffTest {

    @Test
    fun identicalTextProducesNoHunks() {
        val lines = UnifiedDiff.diff("hola\nmundo", "hola\nmundo", "/tmp/a.txt")
        assertEquals(0, lines.size)
    }

    @Test
    fun newFileIsAllAdds() {
        val lines = UnifiedDiff.diff(null, "l1\nl2", "/tmp/nuevo.txt")
        assertTrue(lines.any { it.kind == UnifiedDiff.Kind.HEADER && it.text.startsWith("--- ") })
        val adds = lines.filter { it.kind == UnifiedDiff.Kind.ADD }
        assertEquals(listOf("+l1", "+l2"), adds.map { it.text })
    }

    @Test
    fun deletedFileIsAllDeletes() {
        val lines = UnifiedDiff.diff("l1\nl2", "", "/tmp/borrado.txt")
        val dels = lines.filter { it.kind == UnifiedDiff.Kind.DELETE }
        assertEquals(listOf("-l1", "-l2"), dels.map { it.text })
    }

    @Test
    fun replacementProducesDeleteAndAdd() {
        // context=0: solo el cambio, sin líneas de contexto alrededor.
        val lines = UnifiedDiff.diff("a\nb\nc", "a\nx\nc", "/tmp/f.txt", context = 0)
        val body = lines.filter { it.kind != UnifiedDiff.Kind.HEADER }
        assertEquals(listOf("-b"), body.filter { it.kind == UnifiedDiff.Kind.DELETE }.map { it.text })
        assertEquals(listOf("+x"), body.filter { it.kind == UnifiedDiff.Kind.ADD }.map { it.text })
        // En el hunk, los borrados van antes que los añadidos (estilo diff).
        assertEquals(listOf("-b", "+x"), body.map { it.text })
    }

    @Test
    fun hunkHeaderHasCorrectLineNumbers() {
        // cambio en la 5ª línea (0-based 4); con context=3 el hunk cubre 1..4.
        val lines = UnifiedDiff.diff("l1\nl2\nl3\nl4\nl5", "l1\nl2\nl3\nl4\nX", "/tmp/f.txt")
        val header = lines.first { it.kind == UnifiedDiff.Kind.HEADER && it.text.startsWith("@@") }
        assertEquals("@@ -2,4 +2,4 @@", header.text)
    }

    @Test
    fun twoSeparateChangesSplitIntoTwoHunks() {
        // cambio en la línea 1 y en la línea 12: con context=1 quedan separados
        val old = (1..15).joinToString("\n") { "l$it" }
        val new = (1..15).joinToString("\n") { if (it == 2 || it == 13) "X$it" else "l$it" }
        val lines = UnifiedDiff.diff(old, new, "/tmp/f.txt")
        val hunks = lines.filter { it.kind == UnifiedDiff.Kind.HEADER && it.text.startsWith("@@") }
        assertEquals(2, hunks.size)
    }

    @Test
    fun bigInsertKeepsAllLines() {
        val old = "inicio"
        val new = (0 until 200).joinToString("\n") { "linea$it" }
        val lines = UnifiedDiff.diff(old, new, "/tmp/big.txt")
        val adds = lines.filter { it.kind == UnifiedDiff.Kind.ADD }
        assertEquals(200, adds.size)
        assertEquals("+linea0", adds.first().text)
        assertEquals("+linea199", adds.last().text)
    }

    @Test
    fun renderRoundTripsToText() {
        val lines = UnifiedDiff.diff("a\nb", "a\nc", "/tmp/f.txt")
        val text = UnifiedDiff.render(lines)
        assertTrue(text.contains("@@"))
        assertTrue(text.contains("+c"))
        assertTrue(text.contains("-b"))
    }

    /** Myers básico: una sustitución produce dos equals (a y c) + delete + insert. */
    @Test
    fun myersSimpleSubstitution() {
        val ops = UnifiedDiff.myers(listOf("a", "b", "c"), listOf("a", "x", "c"))
        val equals = ops.filter { it.kind == UnifiedDiff.OpKind.EQUAL }
        assertEquals(listOf(0, 2), equals.map { it.oldIndex })
        assertEquals(1, ops.count { it.kind == UnifiedDiff.OpKind.DELETE })
        assertEquals(1, ops.count { it.kind == UnifiedDiff.OpKind.INSERT })
    }
}
