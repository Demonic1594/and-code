package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.components.FileTypeIcon
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.LocalThemeColors
import com.yugahashimoto.andcode.ui.theme.Spacing

private const val CARD_FILE_LIMIT = 4

/**
 * The files one answer changed, under the answer itself - like the Changes card of desktop agent
 * apps - with a button straight into the review screen. Edits used to be visible only by opening
 * the collapsed activity and finding the patch among the tool calls.
 */
@Composable
internal fun ChangesCard(
    entry: TimelineEntry.Changes,
    onReview: () -> Unit,
) {
    Surface(
        onClick = onReview,
        modifier = Modifier.fillMaxWidth().testTag("chat-changes-card"),
        shape = AppShapes.md,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.file_changes_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entry.files.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = CodeFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.files.take(CARD_FILE_LIMIT).forEach { file -> ChangedFileRow(file) }
            if (entry.files.size > CARD_FILE_LIMIT) {
                Text(
                    text = stringResource(R.string.changes_more, entry.files.size - CARD_FILE_LIMIT),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp, top = 2.dp, bottom = 2.dp),
                )
            }
            Row(
                modifier = Modifier.padding(start = Spacing.md, end = Spacing.sm, top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.RateReview,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.review_changes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** A changed file as its name, with the folder it lives in quieter beside it. */
@Composable
internal fun ChangedFileRow(
    path: String,
    trailing: @Composable () -> Unit = {},
) {
    val name = path.substringAfterLast('/')
    val dir = path.substringBeforeLast('/', "")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FileTypeIcon(fileName = name, modifier = Modifier.size(16.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = CodeFontFamily,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            text = dir,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = CodeFontFamily,
            color = LocalThemeColors.current.foregroundExtraMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** "+12 −3" in the diff colours. */
@Composable
internal fun DiffCounts(
    additions: Int,
    deletions: Int,
) {
    val tc = LocalThemeColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("+$additions", style = MaterialTheme.typography.labelMedium, fontFamily = CodeFontFamily, color = tc.success)
        Text("−$deletions", style = MaterialTheme.typography.labelMedium, fontFamily = CodeFontFamily, color = tc.destructive)
    }
}

/**
 * A diff drawn row by row with a line-number gutter, where a tap on a code line asks for a
 * comment. Commented rows carry their comment underneath, so the review reads like a pull request.
 */
@Composable
internal fun ReviewDiffView(
    diff: ParsedDiff,
    comments: List<ReviewComment>,
    onLineTap: (DiffRow) -> Unit,
    onCommentTap: (ReviewComment) -> Unit,
) {
    val tc = LocalThemeColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        diff.rows.forEach { row ->
            if (row.kind == DiffRow.Kind.META) return@forEach
            val background =
                when (row.kind) {
                    DiffRow.Kind.ADDED -> tc.diffAddition
                    DiffRow.Kind.REMOVED -> tc.diffDeletion
                    DiffRow.Kind.HUNK -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(background)
                        .then(if (row.commentable) Modifier.clickable { onLineTap(row) } else Modifier),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = (row.newLine ?: row.oldLine)?.toString().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = CodeFontFamily,
                    color = LocalThemeColors.current.foregroundExtraMuted,
                    modifier = Modifier.width(40.dp).padding(end = 6.dp, top = 1.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Text(
                    text =
                        when (row.kind) {
                            DiffRow.Kind.ADDED -> "+"
                            DiffRow.Kind.REMOVED -> "−"
                            else -> " "
                        },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = CodeFontFamily,
                    color =
                        when (row.kind) {
                            DiffRow.Kind.ADDED -> tc.success
                            DiffRow.Kind.REMOVED -> tc.destructive
                            else -> Color.Transparent
                        },
                    modifier = Modifier.width(14.dp),
                )
                Text(
                    text = row.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = CodeFontFamily,
                    color = if (row.kind == DiffRow.Kind.HUNK) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    // Wrapped rather than scrolled sideways: on a phone a wrapped line is readable at a
                    // glance, and one scroll position cannot be shared across many rows.
                    modifier = Modifier.weight(1f).padding(end = Spacing.sm, top = 1.dp, bottom = 1.dp),
                )
            }
            comments.filter { it.row == row }.forEach { comment ->
                Surface(
                    onClick = { onCommentTap(comment) },
                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
                    shape = AppShapes.sm,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(
                            Icons.Outlined.RateReview,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(comment.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
