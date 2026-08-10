package com.nxssie.acpssh.markdown

/**
 * Parser Markdown mínimo y determinista para el chat (Fase E): párrafos,
 * encabezados, listas, citas, bloques de código con fence, y en línea `code`,
 * **negrita**, *cursiva* y [enlaces](url) renderizados como texto con estilo.
 *
 * Subconjunto deliberado: sin tablas, sin HTML, sin imágenes. El objetivo es
 * que la salida de `claude-code-acp` (markdown corriente de agentes) se lea
 * bien, no un parser completo de CommonMark.
 */
object Markdown {

    sealed interface Inline {
        data class Text(val text: String) : Inline
        data class Code(val text: String) : Inline
        data class Bold(val children: List<Inline>) : Inline
        data class Italic(val children: List<Inline>) : Inline
        data class Link(val text: String, val url: String) : Inline
    }

    sealed interface Block {
        data class Heading(val level: Int, val inline: List<Inline>) : Block
        data class Paragraph(val inline: List<Inline>) : Block
        data class CodeBlock(val language: String?, val code: String) : Block
        data class ListBlock(val ordered: Boolean, val items: List<List<Inline>>) : Block
        data class Quote(val inline: List<Inline>) : Block
        data object ThematicBreak : Block
    }

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                // Bloque de código con fence
                FENCE_REGEX.matchEntire(trimmed) != null -> {
                    val language = FENCE_REGEX.matchEntire(trimmed)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && FENCE_REGEX.matchEntire(lines[i].trim()) == null) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // fence de cierre (o fin de entrada)
                    blocks.add(Block.CodeBlock(language, code.toString().trimEnd('\n')))
                }
                // Cita
                trimmed.startsWith(">") -> {
                    val quote = StringBuilder(trimmed.removePrefix(">").trim())
                    i++
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote.append(' ').append(lines[i].trimStart().removePrefix(">").trim())
                        i++
                    }
                    blocks.add(Block.Quote(parseInline(quote.toString())))
                }
                // Encabezado
                HEADING_REGEX.matchEntire(trimmed) != null -> {
                    val m = HEADING_REGEX.matchEntire(trimmed)!!
                    blocks.add(Block.Heading(m.groupValues[1].length, parseInline(m.groupValues[2].trim())))
                    i++
                }
                // Lista
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || ORDERED_REGEX.matchEntire(trimmed) != null -> {
                    val ordered = ORDERED_REGEX.matchEntire(trimmed) != null
                    val items = mutableListOf<List<Inline>>()
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        val content = when {
                            t.startsWith("- ") -> t.removePrefix("- ").trim()
                            t.startsWith("* ") -> t.removePrefix("* ").trim()
                            ORDERED_REGEX.matchEntire(t) != null -> ORDERED_REGEX.matchEntire(t)!!.groupValues[1].trim()
                            else -> null
                        }
                        if (content == null) break
                        items.add(parseInline(content))
                        i++
                    }
                    blocks.add(Block.ListBlock(ordered, items))
                }
                // Separador
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    blocks.add(Block.ThematicBreak)
                    i++
                }
                // Línea vacía: separador entre bloques
                trimmed.isEmpty() -> i++
                // Párrafo (acumula líneas hasta una vacía u otro bloque)
                else -> {
                    val paragraph = StringBuilder(line)
                    i++
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        if (t.isEmpty() || t.startsWith("#") || t.startsWith("- ") || t.startsWith("* ") ||
                            ORDERED_REGEX.matchEntire(t) != null || FENCE_REGEX.matchEntire(t) != null ||
                            t.startsWith(">")
                        ) break
                        paragraph.append(' ').append(lines[i].trim())
                        i++
                    }
                    blocks.add(Block.Paragraph(parseInline(paragraph.toString())))
                }
            }
        }
        return blocks
    }

    /** Parsea en línea `code`, **negrita**, *cursiva* y [links](url) de forma anidada. */
    fun parseInline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        var pos = 0
        while (pos < text.length) {
            val rest = text.substring(pos)
            val next = when {
                rest.startsWith("```") -> {
                    val end = rest.indexOf("```", 3)
                    if (end == -1) null
                    else {
                        out.add(Inline.Code(rest.substring(3, end)))
                        pos += end + 3
                        continue
                    }
                }
                else -> INLINE_TOKEN.find(rest)
            }
            if (next == null) {
                out.add(Inline.Text(rest))
                pos = text.length
                continue
            }
            val token = next.value
            val tokenStart = next.range.first
            if (tokenStart > 0) out.add(Inline.Text(rest.substring(0, tokenStart)))
            when {
                token.startsWith("[") -> {
                    val content = next.groupValues[4]
                    val url = next.groupValues[5].ifEmpty { content }
                    out.add(Inline.Link(content, url))
                }
                token.startsWith("**") -> out.add(Inline.Bold(parseInline(next.groupValues[2])))
                token.startsWith("*") -> out.add(Inline.Italic(parseInline(next.groupValues[3])))
                else -> out.add(Inline.Code(next.groupValues[1]))
            }
            pos += tokenStart + token.length
        }
        return out
    }

    /** Texto plano de un bloque (para copy o accesibilidad). */
    fun plainText(block: Block): String = when (block) {
        is Block.Heading -> inlineText(block.inline)
        is Block.Paragraph -> inlineText(block.inline)
        is Block.CodeBlock -> block.code
        is Block.ListBlock -> block.items.joinToString("\n") { inlineText(it) }
        is Block.Quote -> inlineText(block.inline)
        Block.ThematicBreak -> "---"
    }

    fun inlineText(inline: List<Inline>): String = inline.joinToString("") { i ->
        when (i) {
            is Inline.Text -> i.text
            is Inline.Code -> i.text
            is Inline.Bold -> inlineText(i.children)
            is Inline.Italic -> inlineText(i.children)
            is Inline.Link -> i.text
        }
    }

    private val FENCE_REGEX = Regex("^```(\\w*)\\s*$")
    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
    private val ORDERED_REGEX = Regex("^\\d+\\.\\s+(.+)$")
    private val INLINE_TOKEN = Regex("(?:`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|\\[([^\\]]+)\\]\\(([^)]+)\\))")
}
