package com.nxssie.acpssh.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownTest {

    @Test
    fun paragraphsAndBlankLines() {
        val blocks = Markdown.parse("una línea\n\nsegundo párrafo")
        assertEquals(2, blocks.size)
        assertIs<Markdown.Block.Paragraph>(blocks[0])
        assertIs<Markdown.Block.Paragraph>(blocks[1])
    }

    @Test
    fun headings() {
        val blocks = Markdown.parse("# Título\n## Subtítulo\n### H3")
        assertEquals(1, blocks[0].let { assertIs<Markdown.Block.Heading>(it); it.level })
        assertEquals(2, blocks[1].let { assertIs<Markdown.Block.Heading>(it); it.level })
        assertEquals(3, blocks[2].let { assertIs<Markdown.Block.Heading>(it); it.level })
    }

    @Test
    fun unorderedAndOrderedLists() {
        val blocks = Markdown.parse("- uno\n- dos\n\n1. primero\n2. segundo")
        assertIs<Markdown.Block.ListBlock>(blocks[0])
        val ordered = assertIs<Markdown.Block.ListBlock>(blocks[1])
        assertEquals(true, ordered.ordered)
        assertEquals(2, ordered.items.size)
    }

    @Test
    fun fencedCodeBlock() {
        val blocks = Markdown.parse("```kotlin\nval x = 1\n```")
        val code = assertIs<Markdown.Block.CodeBlock>(blocks[0])
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun blockquote() {
        val blocks = Markdown.parse("> cita\n> segunda línea")
        val quote = assertIs<Markdown.Block.Quote>(blocks[0])
        assertEquals("cita segunda línea", Markdown.inlineText(quote.inline))
    }

    @Test
    fun inlineBoldItalicCodeAndLink() {
        val inline = Markdown.parseInline("**negrita** y *cursiva* con `code` y [link](https://x.dev)")
        assertEquals(7, inline.size)
        assertIs<Markdown.Inline.Bold>(inline[0])
        assertIs<Markdown.Inline.Text>(inline[1])
        assertIs<Markdown.Inline.Italic>(inline[2])
        assertIs<Markdown.Inline.Text>(inline[3])
        assertIs<Markdown.Inline.Code>(inline[4])
        assertIs<Markdown.Inline.Text>(inline[5])
        val link = assertIs<Markdown.Inline.Link>(inline[6])
        assertEquals("link", link.text)
        assertEquals("https://x.dev", link.url)
    }

    @Test
    fun multilineParagraphJoinsLines() {
        val blocks = Markdown.parse("primera línea\ncontinúa aquí")
        assertEquals(1, blocks.size)
        val p = assertIs<Markdown.Block.Paragraph>(blocks[0])
        assertEquals("primera línea continúa aquí", Markdown.inlineText(p.inline))
    }

    @Test
    fun plainTextOfCodeBlock() {
        val blocks = Markdown.parse("```\ncode\n```")
        assertEquals("code", Markdown.plainText(blocks[0]))
    }
}
