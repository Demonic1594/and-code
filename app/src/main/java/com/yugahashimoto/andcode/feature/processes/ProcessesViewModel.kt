package com.yugahashimoto.andcode.feature.processes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.runtime.local.ProcessSupervisor
import com.yugahashimoto.andcode.runtime.local.SupervisedProcess
import com.yugahashimoto.andcode.runtime.local.SupervisedProcessSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProcessesUiState(
    /** Every process started since the app started, running or not. */
    val processes: List<SupervisedProcess> = emptyList(),
    /** Processes that were running when the app last closed and have not been started again. */
    val fromLastTime: List<SupervisedProcessSpec> = emptyList(),
)

/** The processes the agents keep running (`andcode-run`), with what can be done to them. */
class ProcessesViewModel(
    private val supervisor: ProcessSupervisor,
) : ViewModel() {
    private val saved = MutableStateFlow(supervisor.saved())

    val state: StateFlow<ProcessesUiState> =
        combine(supervisor.processes, saved) { processes, kept ->
            val names = processes.map { it.spec.name }.toSet()
            ProcessesUiState(processes, kept.filterNot { it.name in names })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ProcessesUiState())

    fun stop(name: String) {
        supervisor.stop(name)
    }

    fun restart(name: String) {
        viewModelScope.launch { supervisor.restart(name) }
    }

    fun start(spec: SupervisedProcessSpec) {
        supervisor.start(spec)
    }

    fun startAll() {
        state.value.fromLastTime.forEach(supervisor::start)
    }

    /** The end of [name]'s output, or null when there is none to show. */
    fun logTail(name: String): String? = supervisor.logTail(name)
}
