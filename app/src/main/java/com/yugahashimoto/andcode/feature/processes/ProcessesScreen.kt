package com.yugahashimoto.andcode.feature.processes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.SupervisedProcess
import com.yugahashimoto.andcode.runtime.local.SupervisedProcessSpec
import com.yugahashimoto.andcode.runtime.local.SupervisedStatus
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily

/** The processes the agents started with `andcode-run`: what they are doing, their output, and stop or restart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessesScreen(
    state: ProcessesUiState,
    onStop: (String) -> Unit,
    onRestart: (String) -> Unit,
    onStart: (SupervisedProcessSpec) -> Unit,
    onStartAll: () -> Unit,
    logTail: (String) -> String?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.processes_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.processes_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            if (state.processes.isEmpty()) {
                item { Text(stringResource(R.string.processes_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.processes, key = { "run:" + it.spec.name }) { process ->
                ProcessRow(process, onStop, onRestart, logTail)
                HorizontalDivider()
            }
            if (state.fromLastTime.isNotEmpty()) {
                item { FromLastTimeHeader(onStartAll) }
                items(state.fromLastTime, key = { "saved:" + it.name }) { spec ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spec.name, style = MaterialTheme.typography.titleSmall)
                            CommandText(spec.command)
                        }
                        TextButton(onClick = { onStart(spec) }) { Text(stringResource(R.string.processes_start)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FromLastTimeHeader(onStartAll: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.processes_from_last_time),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.processes_from_last_time_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onStartAll) { Text(stringResource(R.string.processes_start_all)) }
    }
}

@Composable
private fun ProcessRow(
    process: SupervisedProcess,
    onStop: (String) -> Unit,
    onRestart: (String) -> Unit,
    logTail: (String) -> String?,
) {
    val name = process.spec.name
    var showLog by remember(name) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        CommandText(process.spec.command)
        Text(statusLine(process), style = MaterialTheme.typography.bodySmall, color = statusColor(process.status))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val running = process.status == SupervisedStatus.RUNNING || process.status == SupervisedStatus.RESTARTING
            if (running) TextButton(onClick = { onStop(name) }) { Text(stringResource(R.string.processes_stop)) }
            TextButton(onClick = { onRestart(name) }) { Text(stringResource(R.string.processes_restart)) }
            TextButton(onClick = { showLog = !showLog }) {
                Text(stringResource(if (showLog) R.string.processes_hide_log else R.string.processes_show_log))
            }
        }
        if (showLog) {
            // Read when shown, and again each time the row recomposes (a status change).
            val log = logTail(name)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.processes_no_log)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    log,
                    fontFamily = CodeFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun CommandText(command: String) {
    Text(
        command,
        fontFamily = CodeFontFamily,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun statusLine(process: SupervisedProcess): String {
    val status =
        when (process.status) {
            SupervisedStatus.RUNNING -> stringResource(R.string.processes_status_running)
            SupervisedStatus.RESTARTING -> stringResource(R.string.processes_status_restarting)
            SupervisedStatus.CRASHED -> stringResource(R.string.processes_status_crashed, process.exitCode ?: -1)
            SupervisedStatus.EXITED -> stringResource(R.string.processes_status_exited)
            SupervisedStatus.STOPPED -> stringResource(R.string.processes_status_stopped)
        }
    val port = process.port?.let { " · " + stringResource(R.string.processes_port, it) }.orEmpty()
    return status + port
}

@Composable
private fun statusColor(status: SupervisedStatus) =
    when (status) {
        SupervisedStatus.RUNNING -> MaterialTheme.colorScheme.primary
        SupervisedStatus.CRASHED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
