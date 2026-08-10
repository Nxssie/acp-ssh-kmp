package com.nxssie.acpssh.diff

/**
 * Diff unificado (estilo `diff -u`) para los `ToolCallContent.Diff` del
 * agente ACP. Implementa Myers (O(ND) en espacio lineal vía divide-and-conquer,
 * Myers 1986) sobre líneas, con un tope de profundidad: si el grafo de edición
 * explota (archivos enormes), se degrada a "todo borrado + todo añadido" en
 * vez de colgar o reventar la pila.
 */
object UnifiedDiff {

    enum class Kind { HEADER, CONTEXT, ADD, DELETE }

    data class Line(val kind: Kind, val text: String)

    /**
     * Calcula el diff unificado de [oldText] → [newText] con [context] líneas
     * de contexto. Incluye cabeceras `@@ -a,b +c,d @@` y, si hay cambios,
     * `--- path` / `+++ path` (mismo path en ambos lados, como hace `diff -u`
     * al comparar un fichero consigo mismo).
     */
    fun diff(oldText: String?, newText: String, path: String, context: Int = 3): List<Line> {
        val old = splitLines(oldText)
        val new = splitLines(newText)
        val ops = myers(old, new)
        val out = mutableListOf<Line>()
        if (ops.any { it.kind != OpKind.EQUAL }) {
            out.add(Line(Kind.HEADER, "--- $path"))
            out.add(Line(Kind.HEADER, "+++ $path"))
        }
        buildHunks(old, new, ops, context).forEach { hunk ->
            out.add(Line(Kind.HEADER, "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@"))
            hunk.lines.forEach { out.add(it) }
        }
        return out
    }

    /** Render del diff a texto (para copiar o logs). */
    fun render(lines: List<Line>): String = lines.joinToString("\n") { it.text }

    private fun splitLines(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (lines.size == 1 && lines[0].isEmpty()) emptyList() else lines
    }

    // --- Myers (espacio lineal, divide-and-conquer) ----------------------------

    internal enum class OpKind { EQUAL, DELETE, INSERT }

    /** Una operación de edición sobre índices de a/b (-1 = no aplica). */
    internal data class Op(val kind: OpKind, val oldIndex: Int, val newIndex: Int)

    private const val MAX_DEPTH = 4000

    /** Devuelve las operaciones que transforman a en b, en orden de lectura. */
    internal fun myers(a: List<String>, b: List<String>): List<Op> {
        val ops = mutableListOf<Op>()
        diffRec(a, 0, a.size, b, 0, b.size, ops, 0)
        return ops
    }

    private fun diffRec(
        a: List<String>, aLo0: Int, aHi0: Int,
        b: List<String>, bLo0: Int, bHi0: Int,
        out: MutableList<Op>,
        depth: Int,
    ) {
        var aLo = aLo0
        var aHi = aHi0
        var bLo = bLo0
        var bHi = bHi0
        if (depth > MAX_DEPTH) {
            for (i in aLo until aHi) out.add(Op(OpKind.DELETE, i, -1))
            for (j in bLo until bHi) out.add(Op(OpKind.INSERT, -1, j))
            return
        }
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
            out.add(Op(OpKind.EQUAL, aLo, bLo))
            aLo++; bLo++
        }
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
            out.add(Op(OpKind.EQUAL, aHi - 1, bHi - 1))
            aHi--; bHi--
        }
        if (aLo == aHi) {
            for (j in bLo until bHi) out.add(Op(OpKind.INSERT, -1, j))
            return
        }
        if (bLo == bHi) {
            for (i in aLo until aHi) out.add(Op(OpKind.DELETE, i, -1))
            return
        }
        val (midA, midB) = split(a, aLo, aHi, b, bLo, bHi)
        diffRec(a, aLo, midA, b, bLo, midB, out, depth + 1)
        diffRec(a, midA, aHi, b, midB, bHi, out, depth + 1)
    }

    /**
     * Punto de la serpiente media del grafo de edición, en coordenadas
     * absolutas. Implementación canónica de Myers 1986 (la de los port de
     * "diff" lineales): vf/vb con índices offset por max, condiciones de cruce
     * con paridad de delta.
     */
    private fun split(
        a: List<String>, aLo: Int, aHi: Int,
        b: List<String>, bLo: Int, bHi: Int,
    ): Pair<Int, Int> {
        val n = aHi - aLo
        val m = bHi - bLo
        val delta = n - m
        val odd = delta % 2 != 0
        val max = (n + m + 1) / 2
        val size = 2 * max + 2
        val vf = IntArray(size)
        val vb = IntArray(size)
        val off = max + 1
        vf[off] = 0
        vb[off] = 0

        for (d in 0..max) {
            // Pasada forward: busca la serpiente desde (0,0).
            for (k in -d..d step 2) {
                val kf = off + k
                val x = if (k == -d || (k != d && vf[kf - 1] < vf[kf + 1])) vf[kf + 1] else vf[kf - 1] + 1
                var xi = x
                var y = xi - k
                while (xi < n && y < m && a[aLo + xi] == b[bLo + y]) { xi++; y++ }
                vf[kf] = xi
                if (odd && (k - delta) in -(d - 1)..(d - 1) && vf[kf] + vb[off + (delta - k)] >= n) {
                    return aLo + xi to bLo + y
                }
            }
            // Pasada backward: busca la serpiente desde (n,m).
            for (k in -d..d step 2) {
                val kb = off + k
                val x = if (k == -d || (k != d && vb[kb - 1] < vb[kb + 1])) vb[kb + 1] else vb[kb - 1] + 1
                var xi = x
                var y = xi - k
                while (xi < n && y < m && a[aLo + n - xi - 1] == b[bLo + m - y - 1]) { xi++; y++ }
                vb[kb] = xi
                if (!odd && (k - delta) in -d..d && vb[kb] + vf[off + (delta - k)] >= n) {
                    return aLo + n - xi to bLo + m - y
                }
            }
        }
        // Inalcanzable en la práctica; corta por el medio como último recurso.
        return aLo + n / 2 to bLo + m / 2
    }

    // --- hunks -----------------------------------------------------------------

    private data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<Line>,
    )

    /**
     * Agrupa las ops en hunks con [context] líneas de contexto. Dos cambios se
     * fusionan en un hunk si la distancia entre ellos es <= 2*context (así los
     * bloques de contexto no se solapan entre hunks adyacentes).
     */
    private fun buildHunks(old: List<String>, new: List<String>, ops: List<Op>, context: Int): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        var i = 0
        while (i < ops.size) {
            if (ops[i].kind == OpKind.EQUAL) { i++; continue }
            val firstChange = i
            var lastChange = i
            var j = i + 1
            while (j < ops.size) {
                if (ops[j].kind != OpKind.EQUAL) {
                    lastChange = j
                    j++
                    continue
                }
                var e = j
                while (e < ops.size && ops[e].kind == OpKind.EQUAL) e++
                if (e - j > 2 * context) break
                val hasChangeAhead = (e until (e + context).coerceAtMost(ops.size)).any { ops[it].kind != OpKind.EQUAL }
                if (!hasChangeAhead) break
                j = e
            }

            val hunkStart = (firstChange - context).coerceAtLeast(0)
            val hunkEnd = (lastChange + context + 1).coerceAtMost(ops.size)

            var oldLine = 0
            var newLine = 0
            for (p in 0 until hunkStart) {
                when (ops[p].kind) {
                    OpKind.EQUAL -> { oldLine++; newLine++ }
                    OpKind.DELETE -> oldLine++
                    OpKind.INSERT -> newLine++
                }
            }
            var oldCount = 0
            var newCount = 0
            val lines = mutableListOf<Line>()
            for (p in hunkStart until hunkEnd) {
                when (ops[p].kind) {
                    OpKind.EQUAL -> {
                        lines.add(Line(Kind.CONTEXT, " " + old[ops[p].oldIndex]))
                        oldCount++; newCount++
                    }
                    OpKind.DELETE -> {
                        lines.add(Line(Kind.DELETE, "-" + old[ops[p].oldIndex]))
                        oldCount++
                    }
                    OpKind.INSERT -> {
                        lines.add(Line(Kind.ADD, "+" + new[ops[p].newIndex]))
                        newCount++
                    }
                }
            }
            hunks.add(Hunk(oldLine + 1, oldCount, newLine + 1, newCount, lines))
            i = hunkEnd
        }
        return hunks
    }
}
