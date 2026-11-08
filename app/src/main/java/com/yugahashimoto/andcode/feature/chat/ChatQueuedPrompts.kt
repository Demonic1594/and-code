package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

/**
 * The prompts waiting for the running turn, above the composer: each can be sent at once (stopping
 * the turn), taken back into the composer to change, or dropped. Without it a prompt sent while the
 * agent works disappears until the turn ends, with no way to change one's mind.
 */
@Composable
internal fun QueuedPromptsBar(
    prompts: List<QueuedPromptView>,
    canSendNow: Boolean,
    actions: QueuedPromptActions,
    modifier: Modifier = Modifier,
) {
    if (prompts.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag("queued-prompts"),
        shape = AppShapes.md,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = Spacing.sm, bottom = Spacing.xs)) {
            Text(
                text = stringResource(R.string.queued_prompts_title, prompts.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            prompts.forEach { prompt -> QueuedPromptRow(prompt, canSendNow, actions) }
        }
    }
}

@Composable
private fun QueuedPromptRow(
    prompt: QueuedPromptView,
    canSendNow: Boolean,
    actions: QueuedPromptActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = prompt.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (prompt.attachmentCount > 0) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = stringResource(R.string.queued_prompt_attachments, prompt.attachmentCount),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = prompt.attachmentCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canSendNow) {
            IconButton(onClick = { actions.onSendNow(prompt.id) }) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.queued_prompt_send_now),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = { actions.onEdit(prompt.id) }) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.queued_prompt_edit), modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { actions.onRemove(prompt.id) }) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.queued_prompt_remove), modifier = Modifier.size(18.dp))
        }
    }
}
