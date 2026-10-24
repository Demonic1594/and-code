package com.yugahashimoto.andcode.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.data.repository.MessageSearchHit
import com.yugahashimoto.andcode.data.repository.MessageSearchRepository
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MessageSearchUiState(
    val query: String = "",
    val includeArchived: Boolean = false,
    val hits: List<MessageSearchHit> = emptyList(),
    val searched: Int = 0,
    val total: Int = 0,
    val searching: Boolean = false,
)

/**
 * Backs the search screen. Each change of the query or the archived toggle cancels the search
 * in flight and starts a new one after a short pause, so typing does not read every chat per
 * keystroke.
 */
class MessageSearchViewModel(
    private val repository: MessageSearchRepository,
    private val activeChats: () -> List<RuntimeSessionRef>,
    private val archivedChats: suspend () -> List<RuntimeSessionRef>,
    initialQuery: String = "",
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MessageSearchUiState(query = initialQuery))
    val state: StateFlow<MessageSearchUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        if (initialQuery.isNotBlank()) restart(immediately = true)
    }

    fun setQuery(query: String) {
        if (query == mutableState.value.query) return
        // The old results go at once: left up, they would be highlighted against the new text.
        mutableState.update { it.copy(query = query, hits = emptyList(), searched = 0, total = 0, searching = query.isNotBlank()) }
        restart()
    }

    fun setIncludeArchived(include: Boolean) {
        if (include == mutableState.value.includeArchived) return
        mutableState.update { it.copy(includeArchived = include) }
        restart(immediately = true)
    }

    private fun restart(immediately: Boolean = false) {
        job?.cancel()
        val query = mutableState.value.query
        if (query.isBlank()) {
            mutableState.update { it.copy(hits = emptyList(), searched = 0, total = 0, searching = false) }
            return
        }
        job =
            viewModelScope.launch {
                if (!immediately) delay(debounceMillis)
                mutableState.update { it.copy(searching = true) }
                val archived = if (mutableState.value.includeArchived) archivedChats() else emptyList()
                // Subagent chats are reached from their parent, as in the drawer.
                val chats = (activeChats() + archived).filter { it.session.parentId == null }
                repository.search(query, chats.distinctBy { it.runtimeId to it.session.id }).collect { progress ->
                    mutableState.update {
                        it.copy(
                            hits = progress.hits,
                            searched = progress.searched,
                            total = progress.total,
                            searching = !progress.done,
                        )
                    }
                }
                mutableState.update { it.copy(searching = false) }
            }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 300L
    }
}
