package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing

/**
 * The review screen for a turn's changes, opened from the Changes card or a patch in the activity
 * sheet by [ChatViewModel.openPatchDiff].
 *
 * Every changed file is listed with its +/- counts and can be folded away. Tapping a code line
 * leaves a comment on it, the way a pull request review works; "Send to agent" turns all of them
 * into one message for this chat ([reviewCommentsMessage]). The file list
 * ([PatchDiffState.files]) always shows - it comes straight from the event stream - while a diff
 * only renders once the backend produced one; see [PatchDiffState] for why an empty result reads
 * the same as [PatchDiffState.Unavailable].
 */
@Composable
fun ChatDiffDialog(
    state: PatchDiffState,
    onDismiss: () -> Unit,
    onSendReview: (String) -> Unit = {},
) {
    val comments = remember(state.partId) { mutableStateListOf<ReviewComment>() }
    val collapsed = remember(state.partId) { mutableStateListOf<String>() }
    var editing by remember(state.partId) { mutableStateOf<ReviewComment?>(null) }
    var confirmingDiscard by remember(state.partId) { mutableStateOf(false) }
    val loaded = state as? PatchDiffState.Loaded
    val parsed =
        remember(loaded) {
            loaded
                ?.changes
                .orEmpty()
                .associate { change ->
                    val patch = change.patch
                    // A change the runtime only counted has no lines to show, but still its counts.
                    change.displayPath to
                        if (patch.isNullOrEmpty()) {
                            ParsedDiff(
                                rows = emptyList(),
                                additions = maxOf(change.additions.toInt(), change.added),
                                deletions = maxOf(change.deletions.toInt(), change.removed),
                            )
                        } else {
                            parseUnifiedDiff(patch)
                        }
                }
        }
    // Only this turn's files: a runtime may answer with the whole session's diff.
    val shown = remember(parsed, state.files) { state.files.mapNotNull { parsed[it] } }
    // Unsent comments are the user's work; closing must not drop them without asking.
    val close = { if (comments.isEmpty()) onDismiss() else confirmingDiscard = true }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("chat-review")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.xs, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = close) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.activity_details_close))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.review_changes), style = MaterialTheme.typography.titleMedium)
                    if (shown.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.review_files_changed, state.files.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            DiffCounts(shown.sumOf { it.additions }, shown.sumOf { it.deletions })
                        }
                    }
                }
            }
            if (loaded != null) {
                Text(
                    text = stringResource(R.string.review_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = Spacing.sm),
            ) {
                if (state is PatchDiffState.Unavailable) {
                    item {
                        Text(
                            text = stringResource(R.string.diff_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        )
                    }
                }
                items(state.files, key = { it }) { file ->
                    val diff = parsed[file]
                    val foldable = diff?.rows?.isNotEmpty() == true
                    val isCollapsed = file in collapsed
                    val stateLabel = stringResource(if (isCollapsed) R.string.review_file_collapsed else R.string.review_file_expanded)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = foldable) {
                                        if (isCollapsed) collapsed.remove(file) else collapsed.add(file)
                                    }
                                    .then(if (foldable) Modifier.semantics { stateDescription = stateLabel } else Modifier)
                                    .padding(vertical = Spacing.xs),
                        ) {
                            ChangedFileRow(file) {
                                if (diff != null) DiffCounts(diff.additions, diff.deletions)
                                if (foldable) {
                                    Icon(
                                        if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        contentDescription = stringResource(R.string.cd_expand_collapse),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        if (diff != null && foldable && !isCollapsed) {
                            ReviewDiffView(
                                diff = diff,
                                comments = comments.filter { it.file == file },
                                onLineTap = { index, row -> editing = ReviewComment(file, index, row, "") },
                                onCommentTap = { comment -> editing = comment },
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(top = Spacing.sm),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                if (state is PatchDiffState.Loading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
            if (comments.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.review_comments_pending, comments.size),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                onSendReview(reviewCommentsMessage(comments.toList()))
                                onDismiss()
                            },
                            modifier = Modifier.testTag("chat-review-send"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                stringResource(R.string.review_send_comments, comments.size),
                                modifier = Modifier.padding(start = Spacing.sm),
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        fun sameLine(comment: ReviewComment) = comment.file == draft.file && comment.rowIndex == draft.rowIndex
        CommentEditor(
            draft = draft,
            isExisting = comments.any(::sameLine),
            onSave = { text ->
                comments.removeAll(::sameLine)
                if (text.isNotBlank()) comments += draft.copy(text = text.trim())
                editing = null
            },
            onDelete = {
                comments.removeAll(::sameLine)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.review_discard_title)) },
            text = { Text(stringResource(R.string.review_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    onDismiss()
                }) { Text(stringResource(R.string.code_discard_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CommentEditor(
    draft: ReviewComment,
    isExisting: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(draft) { mutableStateOf(draft.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = draft.row.text.trim().ifEmpty { draft.file },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = CodeFontFamily,
                maxLines = 3,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.review_comment_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("chat-review-comment"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (isExisting) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.review_comment_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
