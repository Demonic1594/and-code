package com.yugahashimoto.andcode.feature.schedule

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleRun
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.ui.runtimeAgentIcon
import com.yugahashimoto.andcode.ui.runtimeTargetLabel
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.LocalThemeColors
import java.time.Instant

@Composable
fun ScheduleListScreen(
    schedules: List<Schedule>,
    runs: List<ScheduleRun>,
    runtimeTargets: List<RuntimeTarget>,
    nextFireAt: (Schedule) -> Instant?,
    onOpenDrawer: () -> Unit,
    onNewSchedule: () -> Unit,
    onOpenSchedule: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRunNow: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    exactAlarmsAllowed: () -> Boolean,
    onExactAlarmsGranted: () -> Unit,
    automation: ScheduleAutomation,
) {
    var pendingDelete by remember { mutableStateOf<Schedule?>(null) }
    var showAutomation by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Re-read on the way back from settings: granting is what makes scheduled runs able to start.
    var alarmsAreExact by remember { mutableStateOf(exactAlarmsAllowed()) }
    val exactAlarmSettings =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            alarmsAreExact = exactAlarmsAllowed()
            if (alarmsAreExact) onExactAlarmsGranted()
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_schedules),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu_description))
                    }
                },
                actions = {
                    IconButton(onClick = { showAutomation = true }) {
                        Icon(Icons.Default.Bolt, contentDescription = stringResource(R.string.schedule_automation_menu))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSchedule,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.schedule_new)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (showAutomation) {
            ScheduleAutomationDialog(automation = automation, schedules = schedules, onDismiss = { showAutomation = false })
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!alarmsAreExact) {
                ExactAlarmBanner(
                    onAllow = {
                        // The dedicated screen is missing on some builds, so fall through the
                        // candidates until one of them opens.
                        exactAlarmSettingsIntents(context).any { intent ->
                            runCatching { exactAlarmSettings.launch(intent) }.isSuccess
                        }
                    },
                )
            }
            if (schedules.isEmpty()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(R.string.schedule_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    // Bottom padding clears the extended button, so the last card can be reached.
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 96.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(schedules, key = { it.id }) { schedule ->
                        ScheduleRow(
                            schedule = schedule,
                            lastRun = runs.firstOrNull { it.scheduleId == schedule.id },
                            nextFireAt = nextFireAt(schedule),
                            runtimeTargets = runtimeTargets,
                            onOpen = { onOpenSchedule(schedule.id) },
                            onEdit = { onEdit(schedule.id) },
                            onRunNow = { onRunNow(schedule.id) },
                            onDelete = { pendingDelete = schedule },
                            onToggleEnabled = { enabled -> onToggleEnabled(schedule.id, enabled) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { schedule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.schedule_delete)) },
            text = { Text(stringResource(R.string.schedule_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(schedule.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Asks for the alarms & reminders permission, without which Android refuses to let a scheduled
 * run start at all.
 */
@Composable
private fun ExactAlarmBanner(onAllow: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.schedule_exact_alarm_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.schedule_exact_alarm_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onAllow) {
                Text(
                    text = stringResource(R.string.schedule_exact_alarm_action),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    schedule: Schedule,
    lastRun: ScheduleRun?,
    nextFireAt: Instant?,
    runtimeTargets: List<RuntimeTarget>,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val target = runtimeTargets.firstOrNull { it.id == schedule.runtimeId }
    val context = androidx.compose.ui.platform.LocalContext.current

    val running = lastRun?.isActive == true
    val project = schedule.workspacePath.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
    // A card per schedule: the agent's mark in a tile, the name, where and when it runs, and a
    // status line coloured by what happens next (running now, next run, or last result).
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(
                            if (schedule.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            AppShapes.md,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(runtimeAgentIcon(target?.agent)),
                    contentDescription = target?.let { runtimeTargetLabel(it) },
                    tint = if (schedule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = schedule.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color =
                        if (schedule.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Text(
                    text = listOfNotNull(project, scheduleTimingLabel(context, schedule)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val statusLine =
                    when {
                        running -> stringResource(R.string.schedule_status_running)
                        nextFireAt != null && schedule.enabled ->
                            stringResource(R.string.schedule_next_run, dateTimeLabel(context, nextFireAt.toEpochMilli()))
                        lastRun != null ->
                            stringResource(R.string.schedule_last_run, scheduleRunStatusLabel(context, lastRun.status))
                        else -> stringResource(R.string.schedule_never_ran)
                    }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (running) LocalThemeColors.current.statusRunning else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (running) LocalThemeColors.current.statusRunning else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = onToggleEnabled,
                colors =
                    SwitchDefaults.colors(
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    ),
            )
            ScheduleMoreMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                onEdit = onEdit,
                onRunNow = onRunNow,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun ScheduleMoreMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.schedule_edit_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.schedule_edit_menu)) },
                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.schedule_run_now)) },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onRunNow()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.schedule_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    onExpandedChange(false)
                    onDelete()
                },
            )
        }
    }
}
