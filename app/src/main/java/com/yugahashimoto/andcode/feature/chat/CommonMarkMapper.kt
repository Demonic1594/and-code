package com.yugahashimoto.andcode.feature.chat

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Block
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
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

/**
 * commonmark-java's syntax tree turned into the [MarkdownBlock] and [MarkdownInline] model the chat
 * renders. The parser decides what the Markdown means; the app's own renderer keeps deciding how it
 * looks, how links are opened and how images are loaded.
 */
internal object CommonMarkMapper {
    private const val MAX_NESTING = 24

    private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    fun blocks(parent: Node): List<MarkdownBlock> = blocks(parent, 0)

    private fun blocks(
        parent: Node,
        depth: Int,
    ): List<MarkdownBlock> {
        // Quotes and lists nest into composables that call each other; past this depth the rest is
        // shown as plain text, so a pathological `>>>>…` cannot overflow the stack while laying out.
        if (depth > MAX_NESTING) {
            val text = allText(parent).trim()
            return if (text.isEmpty()) emptyList() else listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain(text))))
        }
        val result = mutableListOf<MarkdownBlock>()
        parent.children().flatMap { block(it, depth) }.forEach { block ->
            val previous = result.lastOrNull()
            // A bullet list that switches marker (`-` to `*`) is a new list by the spec, but reads as
            // one: keep it one, as before.
            if (block is MarkdownBlock.BulletList && previous is MarkdownBlock.BulletList) {
                result[result.lastIndex] = MarkdownBlock.BulletList(previous.items + block.items)
            } else {
                result += block
            }
        }
        return result
    }

    private fun block(
        node: Node,
        depth: Int,
    ): List<MarkdownBlock> =
        when {
            // Lists inside list items reach here without passing through [blocks]: cap them too.
            depth > MAX_NESTING -> blocks(node, depth)
            else -> mappedBlock(node, depth)
        }

    private fun mappedBlock(
        node: Node,
        depth: Int,
    ): List<MarkdownBlock> =
        when (node) {
            is LinkReferenceDefinition -> emptyList()
            // An empty paragraph shows nothing.
            is Paragraph -> listOfNotNull(knownBlock(node, depth))
            // A block type an extension adds that is not mapped here: keep what it contains.
            else -> knownBlock(node, depth)?.let(::listOf) ?: blocks(node, depth)
        }

    private fun knownBlock(
        node: Node,
        depth: Int,
    ): MarkdownBlock? =
        when (node) {
            is Paragraph -> inlines(node).takeIf { it.isNotEmpty() }?.let { MarkdownBlock.Paragraph(it) }
            is Heading -> MarkdownBlock.Heading(node.level.coerceIn(1, 6), inlines(node))
            is FencedCodeBlock ->
                MarkdownBlock.CodeBlock(
                    node.literal.orEmpty().removeSuffix("\n"),
                    node.info?.trim()?.substringBefore(' ')?.ifBlank { null },
                )
            is IndentedCodeBlock -> MarkdownBlock.CodeBlock(node.literal.orEmpty().trimEnd('\n'), null)
            // Raw HTML is shown as the text it is, never interpreted.
            is HtmlBlock -> MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain(node.literal.orEmpty().trimEnd('\n'))))
            is ThematicBreak -> MarkdownBlock.HorizontalRule
            is BlockQuote -> MarkdownBlock.Blockquote(blocks(node, depth + 1))
            is BulletList -> MarkdownBlock.BulletList(listItems(node, depth + 1))
            is OrderedList -> MarkdownBlock.OrderedList(listItems(node, depth + 1), node.markerStartNumber ?: 1)
            is TableBlock -> table(node)
            is FootnoteDefinition -> MarkdownBlock.Footnote(node.label, blocks(node, depth + 1))
            else -> null
        }

    private fun listItems(
        list: Node,
        depth: Int,
    ): List<MarkdownListItem> =
        list.children().filterIsInstance<ListItem>().map { item ->
            val children = item.children()
            val checked = (children.firstOrNull() as? TaskListItemMarker)?.isChecked
            val content = children.filterNot { it is TaskListItemMarker }
            val lead = content.firstOrNull() as? Paragraph
            MarkdownListItem(
                inlines = lead?.let(::inlines).orEmpty(),
                checked = checked,
                children = content.drop(if (lead != null) 1 else 0).flatMap { block(it, depth) },
            )
        }

    private fun table(table: TableBlock): MarkdownBlock.Table {
        val rows = mutableListOf<TableRow>()

        fun collect(node: Node) {
            node.children().forEach { if (it is TableRow) rows += it else collect(it) }
        }
        collect(table)
        val cells = rows.map { row -> row.children().filterIsInstance<TableCell>() }
        val header = cells.firstOrNull { row -> row.any { it.isHeader } } ?: cells.firstOrNull().orEmpty()
        return MarkdownBlock.Table(
            headers = header.map(::inlines),
            rows = cells.filterNot { it === header }.map { row -> row.map(::inlines) },
            alignments =
                header.map { cell ->
                    when (cell.alignment) {
                        TableCell.Alignment.LEFT -> MarkdownAlign.LEFT
                        TableCell.Alignment.CENTER -> MarkdownAlign.CENTER
                        TableCell.Alignment.RIGHT -> MarkdownAlign.RIGHT
                        else -> null
                    }
                },
        )
    }

    fun inlines(parent: Node): List<MarkdownInline> = mergePlain(parent.children().flatMap(::inline))

    private fun inline(node: Node): List<MarkdownInline> =
        when (node) {
            is Text -> MarkdownLite.linkBareUrls(node.literal.orEmpty())
            // Agents lean on single newlines, so a soft break stays a line break, as before.
            is SoftLineBreak, is HardLineBreak -> listOf(MarkdownInline.Plain("\n"))
            is Code -> listOf(MarkdownInline.Code(node.literal.orEmpty()))
            is StrongEmphasis -> listOf(MarkdownInline.Bold(inlines(node)))
            is Emphasis -> listOf(MarkdownInline.Italic(inlines(node)))
            is Strikethrough -> listOf(MarkdownInline.Strikethrough(inlines(node)))
            is Link -> listOf(MarkdownInline.Link(plainText(node), node.destination.orEmpty()))
            is Image -> listOf(MarkdownInline.Image(plainText(node), node.destination.orEmpty()))
            // Tables written by agents use <br> for a line break in a cell; any other tag stays text.
            is HtmlInline ->
                listOf(MarkdownInline.Plain(if (BR_TAG.matches(node.literal.orEmpty())) "\n" else node.literal.orEmpty()))
            is FootnoteReference -> listOf(MarkdownInline.Plain("[${node.label}]"))
            else -> node.children().flatMap(::inline)
        }

    /** A link's or image's own text, flattened: the model keeps a link's label as plain text. */
    private fun plainText(node: Node): String = node.children().flatMap(::inline).joinToString("") { it.text }

    /** Every piece of text under [node], for content nested too deep to lay out as blocks. */
    private fun allText(node: Node): String =
        when (node) {
            is Text -> node.literal.orEmpty()
            is Code -> node.literal.orEmpty()
            is FencedCodeBlock -> node.literal.orEmpty().trimEnd('\n')
            is IndentedCodeBlock -> node.literal.orEmpty().trimEnd('\n')
            is HtmlBlock -> node.literal.orEmpty().trimEnd('\n')
            is HtmlInline -> if (BR_TAG.matches(node.literal.orEmpty())) "\n" else node.literal.orEmpty()
            is FootnoteReference -> "[${node.label}]"
            is SoftLineBreak, is HardLineBreak -> "\n"
            // Inline pieces run on within their paragraph; the blocks inside a container (list items,
            // quotes, the paragraphs of an item) each start a line.
            is Paragraph, is Heading -> node.children().joinToString("", transform = ::allText)
            is Block -> node.children().joinToString("\n", transform = ::allText)
            else -> node.children().joinToString("", transform = ::allText)
        }

    /** Neighbouring text runs as one, so the model does not depend on how the parser split them. */
    private fun mergePlain(inlines: List<MarkdownInline>): List<MarkdownInline> {
        val merged = mutableListOf<MarkdownInline>()
        inlines.forEach { inline ->
            val previous = merged.lastOrNull()
            if (inline is MarkdownInline.Plain && previous is MarkdownInline.Plain) {
                merged[merged.lastIndex] = MarkdownInline.Plain(previous.text + inline.text)
            } else if (!(inline is MarkdownInline.Plain && inline.text.isEmpty())) {
                merged += inline
            }
        }
        return merged
    }

    private fun Node.children(): List<Node> = generateSequence(firstChild) { it.next }.toList()
}
