package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The Markdown blocks that hold other blocks or cells, drawn by MarkdownBlockView in
// ChatMessageComponents.kt.

@Composable
internal fun MarkdownQuoteView(
    quote: MarkdownBlock.Blockquote,
    onImageClick: (ChatImageSource) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier =
                Modifier.padding(
                    start = 10.dp,
                    end = 8.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            quote.blocks.forEach { child ->
                MarkdownBlockView(child, onImageClick, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun MarkdownTableView(
    table: MarkdownBlock.Table,
    linkColor: Color,
    codeInlineBackground: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                table.headers.forEachIndexed { column, header ->
                    TableCellText(
                        inlines = header,
                        align = table.alignments.getOrNull(column),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        linkColor = linkColor,
                        codeBackground = codeInlineBackground,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            table.rows.forEach { row ->
                val padded =
                    row.take(table.headers.size) +
                        List((table.headers.size - row.size).coerceAtLeast(0)) { emptyList() }
                Row(modifier = Modifier.fillMaxWidth()) {
                    padded.forEachIndexed { column, cell ->
                        TableCellText(
                            inlines = cell,
                            align = table.alignments.getOrNull(column),
                            style = MaterialTheme.typography.bodyMedium,
                            linkColor = linkColor,
                            codeBackground = codeInlineBackground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MarkdownFootnoteView(
    note: MarkdownBlock.Footnote,
    onImageClick: (ChatImageSource) -> Unit,
    contentColor: Color,
) {
    Row {
        Text("[${note.label}]  ", style = MaterialTheme.typography.bodySmall, color = contentColor)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            note.blocks.forEach { child -> MarkdownBlockView(child, onImageClick, contentColor) }
        }
    }
}

/**
 * One list item: its marker (a bullet, its number, or a task box), its first paragraph, and any
 * blocks under it, nested lists included, indented past the marker.
 */
@Composable
internal fun MarkdownListItemView(
    item: MarkdownListItem,
    marker: String,
    onImageClick: (ChatImageSource) -> Unit,
    contentColor: Color,
) {
    val shownMarker =
        when (item.checked) {
            true -> "\u2611"
            false -> "\u2610"
            null -> marker
        }
    Row {
        Text("$shownMarker  ", style = MaterialTheme.typography.bodyLarge, color = contentColor)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.inlines.isNotEmpty()) {
                InlineText(
                    inlines = item.inlines,
                    style = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                    linkColor = MaterialTheme.colorScheme.primary,
                    codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            item.children.forEach { child -> MarkdownBlockView(child, onImageClick, contentColor) }
        }
    }
}
