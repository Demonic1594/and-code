package com.yugahashimoto.andcode.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.ApprovalLog
import com.yugahashimoto.andcode.runtime.local.ToolClassRules

/**
 * What Claude Code was allowed or refused lately, and how, newest first: on the phone, by an
 * always-allow rule, by an allowed kind, by a blocking rule, or for want of an answer. Lets the user
 * see what their rules actually let through.
 */
internal fun LazyListScope.approvalHistory(
    entries: List<ApprovalLog.Entry>,
    onClear: () -> Unit,
) {
    item {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.command_rules_history),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.command_rules_history_clear)) }
            }
        }
        Text(
            text = stringResource(if (entries.isEmpty()) R.string.command_rules_history_empty else R.string.command_rules_history_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
    }
    itemsIndexed(entries, key = { index, entry -> "${entry.timeMs}:$index" }) { _, entry ->
        HistoryRow(entry)
        HorizontalDivider()
    }
}

@Composable
private fun HistoryRow(entry: ApprovalLog.Entry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (entry.allowed) Icons.Default.CheckCircle else Icons.Default.Block,
            contentDescription =
                stringResource(if (entry.allowed) R.string.command_rules_history_allowed else R.string.command_rules_history_refused),
            tint = if (entry.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${entry.tool}  ${entry.summary}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${howDecided(entry)} · ${DateUtils.getRelativeTimeSpanString(entry.timeMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun howDecided(entry: ApprovalLog.Entry): String {
    val rule = entry.rule?.takeIf { it.isNotBlank() }
    return when (entry.how) {
        ApprovalLog.RULE -> stringResource(R.string.command_rules_how_rule, rule ?: entry.tool)
        ApprovalLog.KIND -> stringResource(R.string.command_rules_how_kind, kindLabel(rule))
        ApprovalLog.BLOCK ->
            rule?.let { stringResource(R.string.command_rules_how_block, it) }
                ?: stringResource(R.string.command_rules_how_block_unreadable)
        ApprovalLog.TIMEOUT -> stringResource(R.string.command_rules_how_timeout)
        else ->
            rule?.let { stringResource(R.string.command_rules_how_phone_remembered, it) }
                ?: stringResource(R.string.command_rules_how_phone)
    }
}

@Composable
private fun kindLabel(key: String?): String =
    when (ToolClassRules.Kind.entries.firstOrNull { it.key == key }) {
        ToolClassRules.Kind.READING -> stringResource(R.string.command_rules_kind_reading)
        ToolClassRules.Kind.WEB -> stringResource(R.string.command_rules_kind_web)
        ToolClassRules.Kind.EDITING -> stringResource(R.string.command_rules_kind_editing)
        null -> key.orEmpty()
    }
