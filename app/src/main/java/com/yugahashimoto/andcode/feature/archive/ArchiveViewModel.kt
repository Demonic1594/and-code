package com.yugahashimoto.andcode.feature.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.data.repository.ArchivedChat
import com.yugahashimoto.andcode.data.repository.ChatActionResult
import com.yugahashimoto.andcode.data.repository.ChatLifecycleRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ArchiveUiState(
    val chats: List<ArchivedChat> = emptyList(),
    val loading: Boolean = true,
    /** True while a restore or delete is running, so the buttons cannot fire it twice. */
    val busy: Boolean = false,
)

/** Backs the Archive screen: lists every runtime's archived chats and restores or deletes them. */
class ArchiveViewModel(
    private val lifecycle: ChatLifecycleRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = mutableState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            val chats = lifecycle.listArchived()
            mutableState.update { it.copy(chats = chats, loading = false) }
        }
    }

    fun restore(chats: Collection<ArchivedChat>) = act(chats) { lifecycle.restore(it) }

    fun deleteForever(chats: Collection<ArchivedChat>) = act(chats) { lifecycle.deleteNow(it) }

    /**
     * Runs a restore or delete and drops the chats it succeeded for from the list straight away;
     * the ones that failed stay, and the lifecycle's report says why.
     */
    private fun act(
        chats: Collection<ArchivedChat>,
        action: suspend (Map<String, String?>) -> ChatActionResult,
    ) {
        if (chats.isEmpty() || mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            // Leaving the screen must not cut a delete off halfway through its chats.
            val result = withContext(NonCancellable) { action(chats.associate { it.session.id to it.runtimeId }) }
            mutableState.update { current ->
                current.copy(chats = current.chats.filterNot { it.session.id in result.succeeded }, busy = false)
            }
        }
    }
}

/**
 * Archived chats whose title or project folder contains every word of [query], case-insensitively.
 * A blank query keeps them all.
 */
internal fun filterArchivedChats(
    chats: List<ArchivedChat>,
    query: String,
): List<ArchivedChat> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return chats
    return chats.filter { chat ->
        val haystack = "${chat.session.title} ${chat.session.directory.orEmpty()}"
        terms.all { haystack.contains(it, ignoreCase = true) }
    }
}

/** The project folder name a chat is grouped under, or null for chats outside any project. */
internal fun archivedProjectName(chat: ArchivedChat): String? =
    chat.session.directory
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() && it != "/root" && it != "/workspace" }
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
