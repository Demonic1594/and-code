package com.yugahashimoto.andcode.feature.processes

import androidx.annotation.StringRes
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.StartResult
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
    onToggleLog: (String) -> Unit,
    onProblemShown: () -> Unit,
    onBack: () -> Unit,
    instructionsActions: InstructionsActions = InstructionsActions(),
) {
    val snackbar = remember { SnackbarHostState() }
    val problem = state.problem?.let { problemText(it) }
    LaunchedEffect(state.problem) {
        if (problem != null) {
            snackbar.showSnackbar(problem)
            onProblemShown()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
            if (state.instructionsWithoutRun.isNotEmpty()) {
                item { InstructionsNotice(state.instructionsWithoutRun, instructionsActions.onAdd, instructionsActions.onDismiss) }
            }
            if (state.processes.isEmpty()) {
                item { Text(stringResource(R.string.processes_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.processes, key = { "run:" + it.spec.name }) { process ->
                ProcessRow(process, state.log?.takeIf { it.name == process.spec.name }, onStop, onRestart, onToggleLog)
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
                        ActionButton(R.string.processes_start, spec.name) { onStart(spec) }
                    }
                }
            }
        }
    }
}

/** What the instructions notice can do: add the `andcode-run` section to a file, or stop showing. */
data class InstructionsActions(
    val onAdd: (String) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** Agents whose instructions file was changed, so AndCode never taught them `andcode-run`. */
@Composable
private fun InstructionsNotice(
    files: List<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.processes_instructions_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.processes_instructions_body), style = MaterialTheme.typography.bodySmall)
            files.forEach { path ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        path,
                        fontFamily = CodeFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(R.string.processes_instructions_add, path) { onAdd(path) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.processes_instructions_dismiss))
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
    log: ShownLog?,
    onStop: (String) -> Unit,
    onRestart: (String) -> Unit,
    onToggleLog: (String) -> Unit,
) {
    val name = process.spec.name
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        CommandText(process.spec.command)
        Text(statusLine(process), style = MaterialTheme.typography.bodySmall, color = statusColor(process.status))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val running = process.status == SupervisedStatus.RUNNING || process.status == SupervisedStatus.RESTARTING
            if (running) ActionButton(R.string.processes_stop, name) { onStop(name) }
            ActionButton(R.string.processes_restart, name) { onRestart(name) }
            ActionButton(if (log != null) R.string.processes_hide_log else R.string.processes_show_log, name) { onToggleLog(name) }
        }
        if (log != null) {
            val text = log.text?.takeIf { it.isNotBlank() } ?: stringResource(R.string.processes_no_log)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = CodeFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
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
}

/** A row's button, named with its process for TalkBack, where every row's buttons read the same. */
@Composable
private fun ActionButton(
    @StringRes label: Int,
    processName: String,
    onClick: () -> Unit,
) {
    val text = stringResource(label)
    val description = stringResource(R.string.processes_action_description, text, processName)
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) { Text(text) }
}

@Composable
private fun problemText(problem: StartResult): String? =
    when (problem) {
        is StartResult.Started -> null
        is StartResult.AlreadyRunning -> stringResource(R.string.processes_problem_running, problem.process.spec.name)
        is StartResult.LimitReached -> stringResource(R.string.processes_problem_limit, problem.limit)
        is StartResult.PortTaken -> stringResource(R.string.processes_problem_port, problem.port, problem.by)
        StartResult.InvalidName -> stringResource(R.string.processes_problem_name)
        is StartResult.Failed -> stringResource(R.string.processes_problem_failed, problem.reason)
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
