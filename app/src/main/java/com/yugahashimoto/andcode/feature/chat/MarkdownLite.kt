package com.yugahashimoto.andcode.feature.chat

import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser

sealed interface MarkdownInline {
    val text: String

    data class Plain(override val text: String) : MarkdownInline

    /**
     * Emphasis carries its own parsed children rather than a flat string so a link (or code) nested
     * inside it - e.g. `**http://localhost:4200**` - is still recognized, and rendered clickable,
     * instead of being swallowed as opaque bold text.
     */
    data class Bold(val inlines: List<MarkdownInline>) : MarkdownInline {
        override val text: String get() = inlines.joinToString("") { it.text }
    }

    data class Italic(val inlines: List<MarkdownInline>) : MarkdownInline {
        override val text: String get() = inlines.joinToString("") { it.text }
    }

    data class Strikethrough(val inlines: List<MarkdownInline>) : MarkdownInline {
        override val text: String get() = inlines.joinToString("") { it.text }
    }

    data class Code(override val text: String) : MarkdownInline

    data class Link(override val text: String, val url: String) : MarkdownInline

    data class Image(override val text: String, val url: String) : MarkdownInline
}

/** A list item: its first paragraph, a task box when it has one, and any blocks nested under it. */
data class MarkdownListItem(
    val inlines: List<MarkdownInline>,
    val checked: Boolean? = null,
    val children: List<MarkdownBlock> = emptyList(),
)

enum class MarkdownAlign { LEFT, CENTER, RIGHT }

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock

    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock

    data class CodeBlock(val code: String, val language: String? = null) : MarkdownBlock

    data class BulletList(val items: List<MarkdownListItem>) : MarkdownBlock

    /** [start] is the first item's number: what the source gave, or later for a long list split into pieces. */
    data class OrderedList(val items: List<MarkdownListItem>, val start: Int = 1) : MarkdownBlock

    data class Blockquote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** [alignments] has one entry per column; null where the separator row set none. */
    data class Table(
        val headers: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>,
        val alignments: List<MarkdownAlign?> = emptyList(),
    ) : MarkdownBlock

    /** A footnote's text, shown where the source defines it (usually the end). */
    data class Footnote(val label: String, val blocks: List<MarkdownBlock>) : MarkdownBlock

    data object HorizontalRule : MarkdownBlock
}

object MarkdownLite {
    private val inlineRegex =
        Regex(
            "`([^`]+)`" +
                "|\\*\\*([^*]+)\\*\\*" +
                "|~~([^~]+)~~" +
                "|!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)" +
                "|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)" +
                "|\\*([^*]+)\\*" +
                "|(?<![\\w@])(https?://[A-Za-z0-9][A-Za-z0-9._~:/?#\\[\\]@!&'()*+,;=%-]*)",
        )

    /**
     * The whole-document parser, for what an agent writes and for Markdown files: CommonMark with
     * GitHub's tables, strikethrough and task lists, and footnotes. Built once; a commonmark-java
     * parser is safe to share between threads. Bare URLs are linked by [linkBareUrls], not by the
     * parser's autolink extension, which reads on to the next space and so swallows the Japanese or
     * Chinese text that follows a URL with no space between.
     */
    private val documentParser: Parser =
        Parser
            .builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    // Two tildes only, as before: "PROGRA~1 and PROGRA~2" is not struck through.
                    StrikethroughExtension.builder().requireTwoTildes(true).build(),
                    TaskListItemsExtension.create(),
                    FootnotesExtension.create(),
                ),
            ).build()

    private val bareUrlRegex = Regex("(?<![\\w@])(https?://[A-Za-z0-9][A-Za-z0-9._~:/?#\\[\\]@!&'()*+,;=%-]*)")

    /**
     * [text] with each bare http(s) URL in it as a link, trimmed of trailing punctuation the way
     * [parseInline] trims it. Only ASCII URL characters count, so a URL ends where text in another
     * script starts.
     */
    internal fun linkBareUrls(text: String): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        var lastIndex = 0
        for (match in bareUrlRegex.findAll(text)) {
            val url = trimUrlTail(match.value)
            if (url.isEmpty()) continue
            if (match.range.first > lastIndex) result += MarkdownInline.Plain(text.substring(lastIndex, match.range.first))
            result += MarkdownInline.Link(url, url)
            lastIndex = match.range.first + url.length
        }
        if (lastIndex < text.length) result += MarkdownInline.Plain(text.substring(lastIndex))
        return result
    }

    fun parse(source: String): List<MarkdownBlock> =
        try {
            CommonMarkMapper.blocks(documentParser.parse(source))
        } catch (e: IllegalArgumentException) {
            // The parser refuses some extreme input (a table of over a million cells); show it as text
            // rather than let the exception escape composition, in pieces a lazy list can lay out.
            source.lines().chunked(LAZY_CODE_LINES).map { MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain(it.joinToString("\n")))) }
        }

    /**
     * Inline Markdown in a single run of text, without block syntax. The user's own messages go
     * through this, not [parse], so their `-`, `#`, `>` and blank lines show exactly as typed; only
     * links (and emphasis around them) are picked out.
     */
    internal fun parseInline(text: String): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        var lastIndex = 0
        for (match in inlineRegex.findAll(text)) {
            if (match.range.first > lastIndex) {
                result += MarkdownInline.Plain(text.substring(lastIndex, match.range.first))
            }
            val code = match.groups[1]?.value
            val bold = match.groups[2]?.value
            val strike = match.groups[3]?.value
            val imageAlt = match.groups[4]?.value
            val imageUrl = match.groups[5]?.value
            val linkText = match.groups[6]?.value
            val linkUrl = match.groups[7]?.value
            val italic = match.groups[8]?.value
            val bareUrl = match.groups[9]?.value
            when {
                code != null -> result += MarkdownInline.Code(code)
                bold != null -> result += MarkdownInline.Bold(parseInline(bold))
                strike != null -> result += MarkdownInline.Strikethrough(parseInline(strike))
                imageAlt != null && imageUrl != null -> result += MarkdownInline.Image(imageAlt, imageUrl)
                linkText != null && linkUrl != null -> result += MarkdownInline.Link(linkText, linkUrl)
                italic != null -> result += MarkdownInline.Italic(parseInline(italic))
                bareUrl != null -> {
                    val url = trimUrlTail(bareUrl)
                    result += MarkdownInline.Link(url, url)
                    if (url.length < bareUrl.length) {
                        result += MarkdownInline.Plain(bareUrl.substring(url.length))
                    }
                }
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            result += MarkdownInline.Plain(text.substring(lastIndex))
        }
        if (result.isEmpty()) {
            result += MarkdownInline.Plain(text)
        }
        return result
    }

    private fun trimUrlTail(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            when {
                s.last() in ".,!?;:]}" -> s = s.dropLast(1)
                s.last() == ')' && s.count { it == '(' } < s.count { it == ')' } -> s = s.dropLast(1)
                else -> break
            }
        }
        return s
    }
}

private const val LAZY_CODE_LINES = 150
private const val LAZY_LIST_ITEMS = 40
private const val LAZY_TABLE_ROWS = 60

/**
 * [blocks] with any block too long to lay out as one piece split into consecutive pieces, for a lazy
 * list: one layout taller than Compose can measure is cut off, and a single fence, list or table
 * can be that long on its own (an unclosed fence swallows the rest of the document). Code splits on
 * line boundaries, lists keep their numbering, and every table piece repeats the header row.
 */
fun lazyMarkdownBlocks(blocks: List<MarkdownBlock>): List<MarkdownBlock> =
    blocks.flatMap { block ->
        when (block) {
            is MarkdownBlock.CodeBlock -> {
                val lines = block.code.lines()
                if (lines.size <= LAZY_CODE_LINES) {
                    listOf(block)
                } else {
                    lines.chunked(LAZY_CODE_LINES).map { MarkdownBlock.CodeBlock(it.joinToString("\n"), block.language) }
                }
            }
            is MarkdownBlock.BulletList -> block.items.chunked(LAZY_LIST_ITEMS).map { MarkdownBlock.BulletList(it) }
            is MarkdownBlock.OrderedList ->
                block.items.chunked(LAZY_LIST_ITEMS).mapIndexed { index, items ->
                    MarkdownBlock.OrderedList(items, block.start + index * LAZY_LIST_ITEMS)
                }
            is MarkdownBlock.Table ->
                if (block.rows.size <= LAZY_TABLE_ROWS) {
                    listOf(block)
                } else {
                    block.rows.chunked(LAZY_TABLE_ROWS).map { block.copy(rows = it) }
                }
            else -> listOf(block)
        }
    }
