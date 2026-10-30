package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import com.yugahashimoto.andcode.R

@Composable
internal fun ChatOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    usage: SessionUsage?,
    onShare: () -> Unit,
    artifactCount: Int = 0,
    onArtifacts: () -> Unit = {},
    onCopy: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        usage?.let {
            val tokens =
                stringResource(
                    R.string.chat_usage_tokens,
                    formatUsageTokens(it.inputTokens + it.cacheReadTokens),
                    formatUsageTokens(it.outputTokens + it.reasoningTokens),
                )
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.chat_usage_title), style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = it.costUsd?.let { cost -> "$tokens · ${formatUsd(cost)}" } ?: tokens,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Cache reads are billed at a fraction of fresh input, so seeing them apart
                        // tells whether a long chat is actually expensive or mostly served from cache.
                        if (it.cacheReadTokens > 0) {
                            Text(
                                text = stringResource(R.string.chat_usage_cached, formatUsageTokens(it.cacheReadTokens)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // What the chat's turns would have cost at list prices, when the runtime
                        // priced them at nothing (plan access). Beside, never instead of, the rest.
                        it.estimatedCostUsd?.let { estimate ->
                            Text(
                                text = stringResource(R.string.usage_estimate_value, formatUsd(estimate)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = { Icon(Icons.Default.DataUsage, contentDescription = null) },
                onClick = {},
                enabled = false,
            )
            HorizontalDivider()
        }
        // Only offered once the chat has something to show.
        if (artifactCount > 0) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.artifacts_menu, artifactCount)) },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                onClick = onArtifacts,
                modifier = Modifier.testTag("chat-menu-artifacts"),
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_menu_share)) },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            onClick = onShare,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_menu_copy_markdown)) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = onCopy,
        )
    }
}
