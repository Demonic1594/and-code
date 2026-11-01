package com.yugahashimoto.andcode.feature.schedule

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.schedule.Schedule

/**
 * How another app runs a schedule now: the broadcast to send, the token that authorises it, and
 * each schedule's id, all copyable. Triggers are off until the user creates a token here.
 */
@Composable
fun ScheduleAutomationDialog(
    automation: ScheduleAutomation,
    schedules: List<Schedule>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(automation.token()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_automation_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.schedule_automation_body), style = MaterialTheme.typography.bodyMedium)
                CopyableValue(
                    label = stringResource(R.string.schedule_automation_action),
                    value = ScheduleTriggerToken.ACTION,
                    onCopy = { context.copy(ScheduleTriggerToken.ACTION) },
                )
                val current = token
                if (current == null) {
                    Text(
                        stringResource(R.string.schedule_automation_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CopyableValue(
                        label = stringResource(R.string.schedule_automation_token, ScheduleTriggerToken.EXTRA_TOKEN),
                        value = current,
                        onCopy = { context.copy(current) },
                    )
                    Text(
                        stringResource(R.string.schedule_automation_ids, ScheduleTriggerToken.EXTRA_SCHEDULE_ID),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    schedules.forEach { schedule ->
                        CopyableValue(
                            label = schedule.name.ifBlank { schedule.prompt.lineSequence().firstOrNull().orEmpty() },
                            value = schedule.id,
                            onCopy = { context.copy(schedule.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { token = automation.renew() }) {
                Text(
                    stringResource(
                        if (token == null) R.string.schedule_automation_turn_on else R.string.schedule_automation_new_token,
                    ),
                )
            }
        },
        dismissButton = {
            Row {
                if (token != null) {
                    TextButton(
                        onClick = {
                            automation.turnOff()
                            token = null
                        },
                    ) { Text(stringResource(R.string.schedule_automation_turn_off)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_description)) }
            }
        },
    )
}

@Composable
private fun CopyableValue(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCopy) { Text(stringResource(R.string.action_copy)) }
    }
}

private fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AndCode", text))
    Toast.makeText(this, R.string.action_copied, Toast.LENGTH_SHORT).show()
}

/** The trigger token, as the schedules screen sees it. */
class ScheduleAutomation(
    val token: () -> String?,
    val renew: () -> String,
    val turnOff: () -> Unit,
)
