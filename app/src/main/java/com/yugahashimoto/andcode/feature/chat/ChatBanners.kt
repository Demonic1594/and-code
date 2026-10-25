package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

/**
 * Shown while a subagent session is open. Subagent chats are opened from the parent conversation,
 * so they need an explicit way back to the main agent.
 */
@Composable
internal fun SubagentSessionBanner(
    parentTitle: String,
    onReturn: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = Spacing.hairline)
                .clickable(onClick = onReturn),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.subagent_session_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text =
                        stringResource(
                            R.string.subagent_return_to_parent,
                            parentTitle.ifBlank { stringResource(R.string.subagent_parent_untitled) },
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun StickyTodoBar(
    todos: List<TodoItem>,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val completedCount = todos.count { it.status == "completed" }
    val totalCount = todos.size
    val currentTask = todos.firstOrNull { it.status == "in_progress" }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clickable { expanded = !expanded },
        shape = AppShapes.md,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.todo_timeline_progress, completedCount, totalCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp).testTag("todo-bar-dismiss"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.todo_bar_dismiss),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (totalCount > 0) completedCount.toFloat() / totalCount else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                todos.forEach { todo ->
                    val isCompleted = todo.status == "completed"
                    val isInProgress = todo.status == "in_progress"
                    Row(
                        modifier = Modifier.padding(vertical = Spacing.hairline),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(
                            imageVector =
                                when {
                                    isCompleted -> Icons.Default.CheckCircle
                                    isInProgress -> Icons.Default.PendingActions
                                    else -> Icons.Default.RadioButtonUnchecked
                                },
                            contentDescription = null,
                            tint =
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.secondary
                                    isInProgress -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = todo.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color =
                                if (isCompleted) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                }
            } else if (currentTask != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = currentTask.content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
