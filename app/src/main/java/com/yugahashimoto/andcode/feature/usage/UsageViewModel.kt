package com.yugahashimoto.andcode.feature.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.data.repository.ChatUsage
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.data.repository.UsageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UsageUiState(
    val range: UsageRange = UsageRange.MONTH,
    val includeArchived: Boolean = true,
    val report: UsageReport? = null,
    val read: Int = 0,
    val total: Int = 0,
) {
    val loading: Boolean get() = read < total
}

/**
 * The usage page: reads every chat's turns through [UsageRepository] and sums them for the chosen
 * range. The report is rebuilt as chats arrive, so the numbers climb while the page fills in.
 */
class UsageViewModel(
    private val repository: UsageRepository,
    private val activeChats: () -> List<RuntimeSessionRef>,
    private val archivedChats: suspend () -> List<RuntimeSessionRef>,
    private val now: () -> Long = System::currentTimeMillis,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = mutableState.asStateFlow()

    private var chats: List<ChatUsage> = emptyList()
    private var loadJob: Job? = null

    init {
        reload()
    }

    fun setRange(range: UsageRange) {
        if (range == mutableState.value.range) return
        mutableState.update { it.copy(range = range) }
        viewModelScope.launch { publish() }
    }

    fun setIncludeArchived(include: Boolean) {
        if (include == mutableState.value.includeArchived) return
        mutableState.update { it.copy(includeArchived = include) }
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val active = activeChats()
                val all =
                    if (mutableState.value.includeArchived) {
                        val ids = active.mapTo(HashSet()) { it.runtimeId to it.session.id }
                        active + archivedChats().filterNot { (it.runtimeId to it.session.id) in ids }
                    } else {
                        active
                    }
                repository.collect(all).conflate().collect { progress ->
                    chats = progress.chats
                    mutableState.update { it.copy(read = progress.read, total = progress.total) }
                    publish()
                }
            }
    }

    private suspend fun publish() {
        val range = mutableState.value.range
        val snapshot = chats
        val report = withContext(computeDispatcher) { UsageReport.build(snapshot, range, now()) }
        mutableState.update { if (it.range == range) it.copy(report = report) else it }
    }
}
