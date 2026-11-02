package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
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
        val blocks = MarkdownLite.parse("- first\n- second\n* third")
        val list = blocks.single() as MarkdownBlock.BulletList
        assertEquals(3, list.items.size)
        assertEquals(listOf(MarkdownInline.Plain("first")), list.items[0].inlines)
        assertEquals(listOf(MarkdownInline.Plain("second")), list.items[1].inlines)
        assertEquals(listOf(MarkdownInline.Plain("third")), list.items[2].inlines)
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
        val list = MarkdownBlock.OrderedList((1..90).map { MarkdownListItem(listOf(MarkdownInline.Plain("item $it"))) })
        val header = listOf(listOf(MarkdownInline.Plain("a")), listOf(MarkdownInline.Plain("b")))
        val rows = (1..130).map { listOf(listOf(MarkdownInline.Plain("$it")), listOf(MarkdownInline.Plain("x"))) }
        val table = MarkdownBlock.Table(header, rows)
        val short = MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain("hi")))

        val split = lazyMarkdownBlocks(listOf(short, code, list, table))

        val codes = split.filterIsInstance<MarkdownBlock.CodeBlock>()
        assertEquals(3, codes.size)
        assertEquals(code.code, codes.joinToString("\n") { it.code })
        assertTrue(codes.all { it.language == "kt" })
        assertEquals(listOf(1, 41, 81), split.filterIsInstance<MarkdownBlock.OrderedList>().map { it.start })
        val tables = split.filterIsInstance<MarkdownBlock.Table>()
        assertEquals(listOf(60, 60, 10), tables.map { it.rows.size })
        assertTrue(tables.all { it.headers == header })
        assertEquals(short, split.first())
    }

    @Test
    fun `nested lists stay nested under their item`() {
        val list = MarkdownLite.parse("- a\n  - nested\n    - deeper\n- b").single() as MarkdownBlock.BulletList

        assertEquals(2, list.items.size)
        val nested = list.items[0].children.single() as MarkdownBlock.BulletList
        assertEquals(listOf(MarkdownInline.Plain("nested")), nested.items.single().inlines)
        val deeper = nested.items.single().children.single() as MarkdownBlock.BulletList
        assertEquals(listOf(MarkdownInline.Plain("deeper")), deeper.items.single().inlines)
    }

    @Test
    fun `task list items carry their box`() {
        val list = MarkdownLite.parse("- [ ] todo\n- [x] done\n- plain").single() as MarkdownBlock.BulletList

        assertEquals(listOf(false, true, null), list.items.map { it.checked })
        assertEquals(listOf(MarkdownInline.Plain("todo")), list.items[0].inlines)
    }

    @Test
    fun `an ordered list keeps the number it starts at, and plus bullets are a list`() {
        val blocks = MarkdownLite.parse("+ plus\n\n3) three\n4) four")

        assertTrue(blocks[0] is MarkdownBlock.BulletList)
        val ordered = blocks[1] as MarkdownBlock.OrderedList
        assertEquals(3, ordered.start)
        assertEquals(2, ordered.items.size)
    }

    @Test
    fun `underscore and triple emphasis follow the spec`() {
        val paragraph = MarkdownLite.parse("__bold__ _it_ ***both*** **a*b**").single() as MarkdownBlock.Paragraph

        assertEquals(
            listOf(
                MarkdownInline.Bold(listOf(MarkdownInline.Plain("bold"))),
                MarkdownInline.Plain(" "),
                MarkdownInline.Italic(listOf(MarkdownInline.Plain("it"))),
                MarkdownInline.Plain(" "),
                MarkdownInline.Italic(listOf(MarkdownInline.Bold(listOf(MarkdownInline.Plain("both"))))),
                MarkdownInline.Plain(" "),
                MarkdownInline.Bold(listOf(MarkdownInline.Plain("a*b"))),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `a quote holds whole blocks, including a list and a nested quote`() {
        val quote = MarkdownLite.parse("> quote\n> - item\n>> nested").single() as MarkdownBlock.Blockquote

        assertTrue(quote.blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(quote.blocks[1] is MarkdownBlock.BulletList)
        assertTrue(quote.blocks[2] is MarkdownBlock.Blockquote)
    }

    @Test
    fun `a hashtag is not a heading, and an underlined title is`() {
        val blocks = MarkdownLite.parse("#hashtag\n\nTitle\n=====")

        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain("#hashtag"))), blocks[0])
        assertEquals(MarkdownBlock.Heading(1, listOf(MarkdownInline.Plain("Title"))), blocks[1])
    }

    @Test
    fun `tilde fences and indented code are code`() {
        val blocks = MarkdownLite.parse("~~~kotlin\nval x = 1\n~~~\n\n    indented")

        assertEquals(MarkdownBlock.CodeBlock("val x = 1", "kotlin"), blocks[0])
        assertEquals(MarkdownBlock.CodeBlock("indented", null), blocks[1])
    }

    @Test
    fun `reference links, angle links and footnotes resolve`() {
        val blocks = MarkdownLite.parse("[ref][1] <https://a.b> ok[^n]\n\n[1]: https://r.e\n[^n]: the note")

        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Link("ref", "https://r.e"),
                MarkdownInline.Plain(" "),
                MarkdownInline.Link("https://a.b", "https://a.b"),
                MarkdownInline.Plain(" ok[n]"),
            ),
            paragraph.inlines,
        )
        val note = blocks[1] as MarkdownBlock.Footnote
        assertEquals("n", note.label)
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain("the note"))), note.blocks.single())
    }

    @Test
    fun `table cells keep their formatting and columns their alignment`() {
        val source = "| a | **b** | c |\n|:-|-:|:-:|\n| `c` | [d](https://e) | x<br>y |"
        val table = MarkdownLite.parse(source).single() as MarkdownBlock.Table

        assertEquals(listOf(MarkdownAlign.LEFT, MarkdownAlign.RIGHT, MarkdownAlign.CENTER), table.alignments)
        assertEquals(listOf(MarkdownInline.Bold(listOf(MarkdownInline.Plain("b")))), table.headers[1])
        val row = table.rows.single()
        assertEquals(listOf(MarkdownInline.Code("c")), row[0])
        assertEquals(listOf(MarkdownInline.Link("d", "https://e")), row[1])
        assertEquals(listOf(MarkdownInline.Plain("x\ny")), row[2])
    }

    @Test
    fun `raw html is shown as text, and emails and www stay plain`() {
        val blocks = MarkdownLite.parse("<div>hi</div>\n\nmail a@b.co or www.x.org, see <b>x</b>")

        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain("<div>hi</div>"))), blocks[0])
        assertEquals(
            MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain("mail a@b.co or www.x.org, see <b>x</b>"))),
            blocks[1],
        )
    }

    @Test
    fun `single newlines inside a paragraph stay line breaks`() {
        val paragraph = MarkdownLite.parse("line one\nline two").single() as MarkdownBlock.Paragraph

        assertEquals(listOf(MarkdownInline.Plain("line one\nline two")), paragraph.inlines)
    }

    @Test
    fun `the user's own text keeps its block markers, with only links picked out`() {
        val inlines = MarkdownLite.parseInline("- not a list\n# not a heading http://localhost:3000")

        assertEquals(
            listOf(
                MarkdownInline.Plain("- not a list\n# not a heading "),
                MarkdownInline.Link("http://localhost:3000", "http://localhost:3000"),
            ),
            inlines,
        )
    }

    @Test
    fun `a long table split for lazy rendering keeps its column alignment`() {
        val source = "| a | b |\n|:-:|-:|\n" + (1..130).joinToString("\n") { "| $it | x |" }
        val table = MarkdownLite.parse(source).single() as MarkdownBlock.Table

        val pieces = lazyMarkdownBlocks(listOf(table)).filterIsInstance<MarkdownBlock.Table>()

        assertEquals(3, pieces.size)
        assertTrue(pieces.all { it.alignments == listOf(MarkdownAlign.CENTER, MarkdownAlign.RIGHT) })
    }

    @Test
    fun `a bare url ends where Japanese or Chinese text starts`() {
        val joined = MarkdownLite.parse("サーバーを http://localhost:3000で起動しました").single() as MarkdownBlock.Paragraph
        val bracketed = MarkdownLite.parse("（http://localhost:3000）を開いて").single() as MarkdownBlock.Paragraph
        val chinese = MarkdownLite.parse("打开http://localhost:5173查看").single() as MarkdownBlock.Paragraph

        assertEquals(
            listOf(
                MarkdownInline.Plain("サーバーを "),
                MarkdownInline.Link("http://localhost:3000", "http://localhost:3000"),
                MarkdownInline.Plain("で起動しました"),
            ),
            joined.inlines,
        )
        assertEquals(
            listOf(
                MarkdownInline.Plain("（"),
                MarkdownInline.Link("http://localhost:3000", "http://localhost:3000"),
                MarkdownInline.Plain("）を開いて"),
            ),
            bracketed.inlines,
        )
        assertEquals(MarkdownInline.Link("http://localhost:5173", "http://localhost:5173"), chinese.inlines[1])
    }

    @Test
    fun `only http and https are linked bare, and code spans are left alone`() {
        val paragraph = MarkdownLite.parse("file:///etc/passwd and `http://a.b` and ftp://x.y").single() as MarkdownBlock.Paragraph

        assertEquals(
            listOf(
                MarkdownInline.Plain("file:///etc/passwd and "),
                MarkdownInline.Code("http://a.b"),
                MarkdownInline.Plain(" and ftp://x.y"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `deeply nested quotes stop nesting and keep their text`() {
        var depth = 0
        var block: MarkdownBlock = MarkdownLite.parse(">".repeat(200) + " deep").single()
        while (block is MarkdownBlock.Blockquote) {
            depth++
            block = block.blocks.single()
        }

        // Bounded, whether the parser's own limit or the mapper's is hit first, and the text survives.
        assertTrue("nesting reached $depth", depth <= 26)
        val text = (block as MarkdownBlock.Paragraph).inlines.joinToString("") { it.text }
        assertTrue(text, text.endsWith("deep"))
    }

    @Test
    fun `deeply nested lists stop nesting and keep their text`() {
        val source = (0 until 120).joinToString("\n") { "  ".repeat(it) + "- item $it" }
        var depth = 0
        var block: MarkdownBlock = MarkdownLite.parse(source).single()
        while (block is MarkdownBlock.BulletList) {
            depth++
            block = block.items.single().children.singleOrNull() ?: break
        }

        assertTrue("nesting reached $depth", depth <= 26)
        val all = MarkdownLite.parse(source).toString()
        assertTrue(all.contains("item 119"))
    }

    @Test
    fun `single tildes are not strikethrough`() {
        val paragraph = MarkdownLite.parse("PROGRA~1 and PROGRA~2, ~~gone~~").single() as MarkdownBlock.Paragraph

        assertEquals(
            listOf(
                MarkdownInline.Plain("PROGRA~1 and PROGRA~2, "),
                MarkdownInline.Strikethrough(listOf(MarkdownInline.Plain("gone"))),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `text past the nesting limit keeps code, html and footnote labels, and runs formatted words on`() {
        val deep = "> ".repeat(30)
        val source = "$deep **bold** and `code` here[^n] <b>\n$deep\n$deep ```\n$deep fenced\n$deep ```\n\n[^n]: note"
        val all = MarkdownLite.parse(source).toString()

        assertTrue(all, all.contains("bold and code here[n] <b>"))
        assertTrue(all, all.contains("fenced"))
        val heading = MarkdownLite.parse("> ".repeat(30) + "# **hello** world").toString()
        assertTrue(heading, heading.contains("hello world"))
    }
}
