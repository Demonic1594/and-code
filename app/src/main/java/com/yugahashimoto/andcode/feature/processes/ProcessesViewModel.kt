package com.yugahashimoto.andcode.feature.processes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.runtime.local.ProcessSupervisor
import com.yugahashimoto.andcode.runtime.local.RunInstructions
import com.yugahashimoto.andcode.runtime.local.StartResult
import com.yugahashimoto.andcode.runtime.local.SupervisedProcess
import com.yugahashimoto.andcode.runtime.local.SupervisedProcessSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The output shown for one process: its [name], and the end of what it printed (null when none). */
data class ShownLog(val name: String, val text: String?)

data class ProcessesUiState(
    /** Every process started since the app started, running or not. */
    val processes: List<SupervisedProcess> = emptyList(),
    /** Processes that were running when the app last closed and have not been started again. */
    val fromLastTime: List<SupervisedProcessSpec> = emptyList(),
    /** The process whose output is open, read again every few seconds while it is. */
    val log: ShownLog? = null,
    /** Why the last start or restart did not happen, until the screen has shown it. */
    val problem: StartResult? = null,
    /** Agents' instructions files that do not teach `andcode-run`, so those agents may use `nohup`. */
    val instructionsWithoutRun: List<String> = emptyList(),
)

/**
 * The processes the agents keep running (`andcode-run`), with what can be done to them.
 *
 * Every call into the supervisor reads or writes files (the saved list, the logs) or starts a
 * guest process, so all of them run on [io], never on the main thread.
 */
class ProcessesViewModel(
    private val supervisor: ProcessSupervisor,
    private val instructions: RunInstructions? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logRefreshMillis: Long = LOG_REFRESH_MILLIS,
) : ViewModel() {
    private val saved = MutableStateFlow<List<SupervisedProcessSpec>>(emptyList())
    private val log = MutableStateFlow<ShownLog?>(null)
    private val problem = MutableStateFlow<StartResult?>(null)
    private val withoutRun = MutableStateFlow<List<String>>(emptyList())

    /** Files dismissed while this screen is open, also before the dismissal is saved. */
    private val dismissedHere = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var logJob: Job? = null

    val state: StateFlow<ProcessesUiState> =
        combine(supervisor.processes, saved, log, problem, withoutRun) { processes, kept, shown, failure, files ->
            val names = processes.map { it.spec.name }.toSet()
            ProcessesUiState(processes, kept.filterNot { it.name in names }, shown, failure, files)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ProcessesUiState())

    init {
        refreshSaved()
        refreshInstructions()
    }

    /** Adds the `andcode-run` section to the instructions file at [path], keeping what is in it. */
    fun addRunSection(path: String) {
        viewModelScope.launch(io) {
            instructions?.add(path)
            refreshInstructions()
        }
    }

    /** Stops pointing out the files currently listed. */
    fun dismissInstructions() {
        val files = withoutRun.value
        dismissedHere += files
        withoutRun.value = emptyList()
        viewModelScope.launch(io) { instructions?.dismiss(files) }
    }

    private fun refreshInstructions() {
        viewModelScope.launch(io) {
            // A read that started before a dismissal must not bring the dismissed files back.
            val missing = instructions?.missing().orEmpty()
            withoutRun.value = missing.filterNot { it in dismissedHere }
        }
    }

    fun stop(name: String) {
        viewModelScope.launch(io) { supervisor.stop(name) }
    }

    fun restart(name: String) {
        viewModelScope.launch(io) { report(supervisor.restart(name)) }
    }

    fun start(spec: SupervisedProcessSpec) {
        viewModelScope.launch(io) {
            report(supervisor.start(spec))
            refreshSaved()
        }
    }

    /** Starts every process from last time in order, stopping at the first that cannot start and saying why. */
    fun startAll() {
        val specs = state.value.fromLastTime
        viewModelScope.launch(io) {
            for (spec in specs) {
                val result = supervisor.start(spec)
                if (result !is StartResult.Started) {
                    report(result)
                    break
                }
            }
            refreshSaved()
        }
    }

    /** Shows [name]'s output, or hides it when it is already shown. */
    fun toggleLog(name: String) {
        logJob?.cancel()
        if (log.value?.name == name) {
            log.value = null
            return
        }
        log.value = ShownLog(name, null)
        logJob =
            viewModelScope.launch(io) {
                while (isActive) {
                    val text = supervisor.logTail(name)
                    // A hide or a switch to another process during the read must not be undone by it.
                    ensureActive()
                    log.update { shown -> if (shown?.name == name) ShownLog(name, text) else shown }
                    delay(logRefreshMillis)
                }
            }
    }

    fun problemShown() {
        problem.value = null
    }

    private fun report(result: StartResult) {
        if (result !is StartResult.Started) problem.value = result
    }

    private fun refreshSaved() {
        viewModelScope.launch(io) { saved.value = supervisor.saved() }
    }

    private companion object {
        const val LOG_REFRESH_MILLIS = 2_000L
    }
}
