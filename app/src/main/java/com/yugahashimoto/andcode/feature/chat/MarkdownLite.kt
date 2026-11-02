package com.yugahashimoto.andcode.feature.chat

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.strikethrough.Strikethrough as GfmStrikethrough

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

/** One item of a list: any blocks (paragraphs, nested lists, code), and its box when it is a task. */
data class MarkdownListItem(
    val blocks: List<MarkdownBlock>,
    /** True or false for a `- [x]` / `- [ ]` task item, null for an ordinary one. */
    val checked: Boolean? = null,
)

enum class MarkdownAlignment { START, CENTER, END }

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock

    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock

    data class CodeBlock(val code: String, val language: String? = null) : MarkdownBlock

    data class BulletList(val items: List<MarkdownListItem>) : MarkdownBlock

    /** [start] is the first item's number: as written, or later for a long list split into pieces. */
    data class OrderedList(val items: List<MarkdownListItem>, val start: Int = 1) : MarkdownBlock

    data class Blockquote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    data class Table(
        val headers: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>,
        /** One per column; shorter than [headers] when not given. */
        val alignments: List<MarkdownAlignment?> = emptyList(),
    ) : MarkdownBlock

    data object HorizontalRule : MarkdownBlock
}

/**
 * CommonMark with GitHub's tables, strikethrough and task lists, read into [MarkdownBlock]s for
 * Compose to draw. Bare URLs are linked here rather than by the autolink extension, so the rules
 * already relied on (trailing punctuation, balanced parentheses) stay as they were.
 */
object MarkdownLite {
    private val bareUrl = Regex("(?<![\\w@])(https?://[A-Za-z0-9][A-Za-z0-9._~:/?#\\[\\]@!&'()*+,;=%-]*)")

    private val parser: Parser =
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    // A single tilde ("~5 minutes") is not a strikethrough.
                    StrikethroughExtension.builder().requireTwoTildes(true).build(),
                    TaskListItemsExtension.create(),
                ),
            ).build()

    fun parse(source: String): List<MarkdownBlock> = blocksOf(parser.parse(source))

    /** The inline content of [text], paragraphs joined by a line break. */
    internal fun parseInline(text: String): List<MarkdownInline> {
        val inlines =
            parse(text).flatMapIndexed { index, block ->
                val content =
                    when (block) {
                        is MarkdownBlock.Paragraph -> block.inlines
                        is MarkdownBlock.Heading -> block.inlines
                        else -> listOf(MarkdownInline.Plain(text))
                    }
                if (index == 0) content else listOf(MarkdownInline.Plain("\n")) + content
            }
        return merged(inlines).ifEmpty { listOf(MarkdownInline.Plain(text)) }
    }

    private fun children(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

    private fun blocksOf(parent: Node): List<MarkdownBlock> = children(parent).mapNotNull(::blockOf).toList()

    private fun blockOf(node: Node): MarkdownBlock? =
        when (node) {
            is Heading -> MarkdownBlock.Heading(node.level.coerceIn(1, 6), inlinesOf(node))
            is Paragraph -> MarkdownBlock.Paragraph(inlinesOf(node))
            is FencedCodeBlock ->
                MarkdownBlock.CodeBlock(
                    node.literal.removeSuffix("\n"),
                    node.info?.trim()?.substringBefore(' ')?.ifBlank { null },
                )
            is IndentedCodeBlock -> MarkdownBlock.CodeBlock(node.literal.removeSuffix("\n"))
            is BulletList -> MarkdownBlock.BulletList(itemsOf(node))
            is OrderedList -> MarkdownBlock.OrderedList(itemsOf(node), node.markerStartNumber ?: 1)
            is BlockQuote -> MarkdownBlock.Blockquote(blocksOf(node))
            is ThematicBreak -> MarkdownBlock.HorizontalRule
            is TaskListItemMarker -> null
            is TableBlock -> tableOf(node)
            // Raw HTML is shown as the text it is, never interpreted.
            is HtmlBlock -> MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain(node.literal.trimEnd())))
            else -> null
        }

    private fun itemsOf(list: Node): List<MarkdownListItem> =
        children(list).filterIsInstance<ListItem>().map { item ->
            // The box is the item's first child (or, in some versions, its paragraph's).
            val marker =
                children(item).firstNotNullOfOrNull { child ->
                    child as? TaskListItemMarker ?: children(child).firstOrNull() as? TaskListItemMarker
                }
            MarkdownListItem(blocksOf(item), marker?.isChecked)
        }.toList()

    private fun tableOf(table: TableBlock): MarkdownBlock.Table {
        val rows = mutableListOf<List<TableCell>>()

        fun collect(node: Node) {
            children(node).forEach { child ->
                if (child is TableRow) rows += children(child).filterIsInstance<TableCell>().toList() else collect(child)
            }
        }
        collect(table)
        val header = rows.firstOrNull().orEmpty()
        return MarkdownBlock.Table(
            headers = header.map(::inlinesOf),
            rows = rows.drop(1).map { row -> row.map(::inlinesOf) },
            alignments =
                header.map { cell ->
                    when (cell.alignment) {
                        TableCell.Alignment.LEFT -> MarkdownAlignment.START
                        TableCell.Alignment.CENTER -> MarkdownAlignment.CENTER
                        TableCell.Alignment.RIGHT -> MarkdownAlignment.END
                        else -> null
                    }
                },
        )
    }

    private fun inlinesOf(parent: Node): List<MarkdownInline> = merged(children(parent).flatMap(::inlineOf).toList())

    private fun inlineOf(node: Node): List<MarkdownInline> =
        when (node) {
            is Text -> linkified(node.literal)
            is Code -> listOf(MarkdownInline.Code(node.literal))
            is StrongEmphasis -> listOf(MarkdownInline.Bold(inlinesOf(node)))
            is Emphasis -> listOf(MarkdownInline.Italic(inlinesOf(node)))
            is GfmStrikethrough -> listOf(MarkdownInline.Strikethrough(inlinesOf(node)))
            is Link ->
                listOf(
                    MarkdownInline.Link(inlinesOf(node).joinToString("") { it.text }.ifEmpty { node.destination }, node.destination),
                )
            is Image -> listOf(MarkdownInline.Image(inlinesOf(node).joinToString("") { it.text }, node.destination))
            // Chat text keeps its line breaks, as typed.
            is SoftLineBreak, is HardLineBreak -> listOf(MarkdownInline.Plain("\n"))
            is HtmlInline -> listOf(MarkdownInline.Plain(node.literal))
            is TaskListItemMarker -> emptyList()
            else -> inlinesOf(node)
        }

    /** Plain text with each bare http(s) URL in it made a link. */
    private fun linkified(text: String): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        var last = 0
        bareUrl.findAll(text).forEach { match ->
            if (match.range.first > last) result += MarkdownInline.Plain(text.substring(last, match.range.first))
            val url = trimUrlTail(match.value)
            result += MarkdownInline.Link(url, url)
            if (url.length < match.value.length) result += MarkdownInline.Plain(match.value.substring(url.length))
            last = match.range.last + 1
        }
        if (last < text.length) result += MarkdownInline.Plain(text.substring(last))
        return result
    }

    /** Neighbouring plain runs joined, so the text reads (and tests compare) as one piece. */
    private fun merged(inlines: List<MarkdownInline>): List<MarkdownInline> =
        inlines.fold(mutableListOf()) { acc, inline ->
            val previous = acc.lastOrNull()
            if (previous is MarkdownInline.Plain && inline is MarkdownInline.Plain) {
                acc[acc.lastIndex] = MarkdownInline.Plain(previous.text + inline.text)
            } else {
                acc += inline
            }
            acc
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
                    block.rows.chunked(LAZY_TABLE_ROWS).map { MarkdownBlock.Table(block.headers, it, block.alignments) }
                }
            else -> listOf(block)
        }
    }
