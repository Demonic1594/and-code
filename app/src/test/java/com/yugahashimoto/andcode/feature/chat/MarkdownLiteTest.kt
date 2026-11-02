package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
    private fun paragraph(text: String) = MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain(text)))

    @Test
    fun `lists nest, keep several paragraphs and code per item, and start where written`() {
        val blocks =
            MarkdownLite.parse(
                """
                3. three
                4. four

                   more about four

                   ```sh
                   make
                   ```
                   - nested
                     - deeper
                """.trimIndent(),
            )

        val list = blocks.single() as MarkdownBlock.OrderedList
        assertEquals(3, list.start)
        val four = list.items[1].blocks
        assertEquals(paragraph("four"), four[0])
        assertEquals(paragraph("more about four"), four[1])
        assertEquals(MarkdownBlock.CodeBlock("make", "sh"), four[2])
        val nested = four[3] as MarkdownBlock.BulletList
        assertEquals(paragraph("nested"), nested.items.single().blocks[0])
        assertTrue(nested.items.single().blocks[1] is MarkdownBlock.BulletList)
    }

    @Test
    fun `task list items carry their box`() {
        val list = MarkdownLite.parse("- [x] done\n- [ ] todo\n- plain").single() as MarkdownBlock.BulletList

        assertEquals(listOf(true, false, null), list.items.map { it.checked })
        assertEquals(paragraph("done"), list.items[0].blocks.single())
    }

    @Test
    fun `a quote holds whole blocks`() {
        val quote = MarkdownLite.parse("> # Note\n> - one\n>\n> said **this**").single() as MarkdownBlock.Blockquote

        assertTrue(quote.blocks[0] is MarkdownBlock.Heading)
        assertTrue(quote.blocks[1] is MarkdownBlock.BulletList)
        assertEquals(
            MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain("said "), MarkdownInline.Bold(listOf(MarkdownInline.Plain("this"))))),
            quote.blocks[2],
        )
    }

    @Test
    fun `table cells are formatted and columns aligned`() {
        val table =
            MarkdownLite.parse("| Name | Size |\n|:-----|-----:|\n| `a.kt` | **2** KB |").single() as MarkdownBlock.Table

        assertEquals(listOf(MarkdownAlignment.START, MarkdownAlignment.END), table.alignments)
        assertEquals(listOf(MarkdownInline.Code("a.kt")), table.rows.single()[0])
        assertEquals(listOf(MarkdownInline.Bold(listOf(MarkdownInline.Plain("2"))), MarkdownInline.Plain(" KB")), table.rows.single()[1])
    }

    @Test
    fun `underscores, escapes, line breaks and lone tildes read as written`() {
        val inlines = (MarkdownLite.parse("__bold__ _it_ \\*not\\* about ~5 min\nnext line").single() as MarkdownBlock.Paragraph).inlines

        assertEquals(
            listOf(
                MarkdownInline.Bold(listOf(MarkdownInline.Plain("bold"))),
                MarkdownInline.Plain(" "),
                MarkdownInline.Italic(listOf(MarkdownInline.Plain("it"))),
                MarkdownInline.Plain(" *not* about ~5 min\nnext line"),
            ),
            inlines,
        )
    }

    @Test
    fun `indented code, setext headings, rules and raw html are understood`() {
        val blocks = MarkdownLite.parse("Title\n=====\n\n    indented()\n\n***\n\n<div>raw</div>")

        assertEquals(MarkdownBlock.Heading(1, listOf(MarkdownInline.Plain("Title"))), blocks[0])
        assertEquals(MarkdownBlock.CodeBlock("indented()"), blocks[1])
        assertEquals(MarkdownBlock.HorizontalRule, blocks[2])
        assertEquals(paragraph("<div>raw</div>"), blocks[3])
    }

    @Test
    fun `plain paragraph becomes a single paragraph block`() {
        val blocks = MarkdownLite.parse("Just plain text.")
        assertEquals(1, blocks.size)
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MarkdownInline.Plain("Just plain text.")), paragraph.inlines)
    }

    @Test
    fun `fenced code block is parsed with language`() {
        val blocks =
            MarkdownLite.parse(
                """
                ```kotlin
                val x = 1
                println(x)
                ```
                """.trimIndent(),
            )
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
    }

    @Test
    fun `fenced code block without language`() {
        val blocks = MarkdownLite.parse("```\necho hi\n```")
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals(null, code.language)
        assertEquals("echo hi", code.code)
    }

    @Test
    fun `inline code is extracted`() {
        val blocks = MarkdownLite.parse("Run `ls -la` to list files")
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Run "),
                MarkdownInline.Code("ls -la"),
                MarkdownInline.Plain(" to list files"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `bare https url becomes a link`() {
        val paragraph = MarkdownLite.parse("Open https://example.com/docs?q=1.").single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Open "),
                MarkdownInline.Link("https://example.com/docs?q=1", "https://example.com/docs?q=1"),
                MarkdownInline.Plain("."),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `bold text is extracted`() {
        val blocks = MarkdownLite.parse("This is **important** text")
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("This is "),
                MarkdownInline.Bold(listOf(MarkdownInline.Plain("important"))),
                MarkdownInline.Plain(" text"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `a bare url inside bold stays a link`() {
        val paragraph =
            MarkdownLite.parse("Open **http://localhost:4200** now")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Open "),
                MarkdownInline.Bold(
                    listOf(MarkdownInline.Link("http://localhost:4200", "http://localhost:4200")),
                ),
                MarkdownInline.Plain(" now"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `a url inside italic stays a link`() {
        val paragraph =
            MarkdownLite.parse("See *https://example.com* please")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("See "),
                MarkdownInline.Italic(
                    listOf(MarkdownInline.Link("https://example.com", "https://example.com")),
                ),
                MarkdownInline.Plain(" please"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `a url inside strikethrough stays a link`() {
        val paragraph =
            MarkdownLite.parse("~~https://example.com~~")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Strikethrough(
                    listOf(MarkdownInline.Link("https://example.com", "https://example.com")),
                ),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `emphasis keeps surrounding text while linking the url inside it`() {
        val paragraph =
            MarkdownLite.parse("**see https://example.com now**")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Bold(
                    listOf(
                        MarkdownInline.Plain("see "),
                        MarkdownInline.Link("https://example.com", "https://example.com"),
                        MarkdownInline.Plain(" now"),
                    ),
                ),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `headings at different levels are parsed`() {
        val blocks = MarkdownLite.parse("# Title\n## Subtitle\n### Detail")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals(2, (blocks[1] as MarkdownBlock.Heading).level)
        assertEquals(3, (blocks[2] as MarkdownBlock.Heading).level)
        assertEquals(
            listOf(MarkdownInline.Plain("Title")),
            (blocks[0] as MarkdownBlock.Heading).inlines,
        )
    }

    @Test
    fun `bullet list with dash and star markers`() {
        // As in CommonMark, a different bullet character starts a new list.
        val blocks = MarkdownLite.parse("- first\n- second\n* third")
        assertEquals(listOf(2, 1), blocks.map { (it as MarkdownBlock.BulletList).items.size })
        val first = blocks[0] as MarkdownBlock.BulletList
        assertEquals(listOf(paragraph("first")), first.items[0].blocks)
        assertEquals(listOf(paragraph("second")), first.items[1].blocks)
        assertEquals(listOf(paragraph("third")), (blocks[1] as MarkdownBlock.BulletList).items[0].blocks)
    }

    @Test
    fun `mixed content keeps block order`() {
        val blocks =
            MarkdownLite.parse(
                """
                # Heading
                Some paragraph text.
                - item one
                - item two
                ```
                code here
                ```
                Trailing paragraph.
                """.trimIndent(),
            )
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertTrue(blocks[2] is MarkdownBlock.BulletList)
        assertTrue(blocks[3] is MarkdownBlock.CodeBlock)
        assertTrue(blocks[4] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `bare url with balanced parentheses is preserved`() {
        val paragraph =
            MarkdownLite.parse("See https://en.wikipedia.org/wiki/Foo_(bar) for details")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("See "),
                MarkdownInline.Link(
                    "https://en.wikipedia.org/wiki/Foo_(bar)",
                    "https://en.wikipedia.org/wiki/Foo_(bar)",
                ),
                MarkdownInline.Plain(" for details"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `bare url wrapped in parentheses trims closing paren`() {
        val paragraph =
            MarkdownLite.parse("(https://example.com)")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("("),
                MarkdownInline.Link("https://example.com", "https://example.com"),
                MarkdownInline.Plain(")"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `bare url with trailing comma is trimmed`() {
        val paragraph =
            MarkdownLite.parse("Visit https://example.com, then continue")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Visit "),
                MarkdownInline.Link("https://example.com", "https://example.com"),
                MarkdownInline.Plain(", then continue"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `markdown link is parsed`() {
        val paragraph =
            MarkdownLite.parse("See [docs](https://example.com/docs) for info")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("See "),
                MarkdownInline.Link("docs", "https://example.com/docs"),
                MarkdownInline.Plain(" for info"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `multiple bare urls are all linked`() {
        val paragraph =
            MarkdownLite.parse("Go to https://a.com and https://b.com today")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Go to "),
                MarkdownInline.Link("https://a.com", "https://a.com"),
                MarkdownInline.Plain(" and "),
                MarkdownInline.Link("https://b.com", "https://b.com"),
                MarkdownInline.Plain(" today"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `dotted numbers stay plain text`() {
        val paragraph =
            MarkdownLite.parse("versionName 1.2.2, 19.5 MB, v1.2.2")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(MarkdownInline.Plain("versionName 1.2.2, 19.5 MB, v1.2.2")),
            paragraph.inlines,
        )
    }

    @Test
    fun `markdown image is parsed`() {
        val paragraph =
            MarkdownLite.parse("Look at ![rabbit](/path/to/rabbit.png) image")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Look at "),
                MarkdownInline.Image("rabbit", "/path/to/rabbit.png"),
                MarkdownInline.Plain(" image"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `long blocks are split for lazy rendering, keeping numbering and table headers`() {
        val code = MarkdownBlock.CodeBlock((1..400).joinToString("\n") { "line $it" }, "kt")
        val list = MarkdownBlock.OrderedList((1..90).map { MarkdownListItem(listOf(paragraph("item $it"))) })
        val headers = listOf(listOf(MarkdownInline.Plain("a")), listOf(MarkdownInline.Plain("b")))
        val table =
            MarkdownBlock.Table(
                headers,
                (1..130).map { listOf(listOf(MarkdownInline.Plain("$it")), listOf(MarkdownInline.Plain("x"))) },
                listOf(MarkdownAlignment.END, null),
            )
        val short = MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain("hi")))

        val split = lazyMarkdownBlocks(listOf(short, code, list, table))

        val codes = split.filterIsInstance<MarkdownBlock.CodeBlock>()
        assertEquals(3, codes.size)
        assertEquals(code.code, codes.joinToString("\n") { it.code })
        assertTrue(codes.all { it.language == "kt" })
        assertEquals(listOf(1, 41, 81), split.filterIsInstance<MarkdownBlock.OrderedList>().map { it.start })
        val tables = split.filterIsInstance<MarkdownBlock.Table>()
        assertEquals(listOf(60, 60, 10), tables.map { it.rows.size })
        assertTrue(tables.all { it.headers == headers && it.alignments == listOf(MarkdownAlignment.END, null) })
        assertEquals(short, split.first())
    }
}
