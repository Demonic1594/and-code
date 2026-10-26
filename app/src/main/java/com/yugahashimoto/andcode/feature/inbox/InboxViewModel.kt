package com.yugahashimoto.andcode.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.followUpPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What went wrong with the last inbox action, for the screen to say. */
enum class InboxFailure { APPROVAL, STOP, REPLY }

data class InboxUiState(
    val content: InboxContent = InboxContent(),
    /** Approvals and chats with an answer, a stop or a reply in flight, so their buttons wait. */
    val busyIds: Set<String> = emptySet(),
    val failure: InboxFailure? = null,
)

/**
 * The agent inbox: every chat across the user's agents that wants an answer, is running, or
 * finished unread, with the answer one tap away - approve, stop or reply without opening the chat.
 */
class InboxViewModel(
    catalog: RuntimeCatalogRepository,
    private val activity: RuntimeActivityRepository,
    private val registry: RuntimeRegistry,
    /** Chats a delete is still waiting on, which already look gone everywhere else. */
    pendingDeletion: StateFlow<Set<String>>,
    /** Told of each stop, so a "this run looks stuck" notice for the chat can go too. */
    private val onStopped: (String) -> Unit = {},
) : ViewModel() {
    private val actions = MutableStateFlow(InboxUiState())

    val state: StateFlow<InboxUiState> =
        combine(
            catalog.allSessions,
            activity.state,
            registry.selected,
            pendingDeletion,
            actions,
        ) { sessions, events, selected, hidden, action ->
            action.copy(
                content =
                    buildInbox(
                        sessions = sessions,
                        activity = events,
                        selectedRuntimeId = selected?.id,
                        isSubagent = activity::isSubagentSession,
                        hidden = hidden,
                    ),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, InboxUiState())

    fun respond(
        request: PermissionRequest,
        response: PermissionResponse,
        remember: Boolean,
    ) = act(request.id, InboxFailure.APPROVAL, runtimeId = null) { backend ->
        val accepted = backend.respondToPermission(request.sessionId, request.id, response, remember)
        check(accepted) { "Permission ${request.id} was not accepted" }
        activity.resolvePermission(request.id)
    }

    /** Stops a run the way the chat's own Stop does: the chat stops counting as running at once. */
    fun stop(chat: InboxChat) {
        activity.markSessionAborted(chat.sessionId)
        onStopped(chat.sessionId)
        act(chat.sessionId, InboxFailure.STOP, chat.runtimeId) { backend -> backend.abortSession(chat.sessionId) }
    }

    fun reply(
        chat: InboxChat,
        text: String,
    ) {
        val message = text.trim()
        if (message.isEmpty()) return
        act(chat.sessionId, InboxFailure.REPLY, chat.runtimeId) { backend ->
            backend.sendMessage(chat.sessionId, followUpPrompt(backend, chat.sessionId, message))
            activity.markSessionRunning(chat.sessionId)
        }
    }

    fun markRead(chat: InboxChat) = activity.markSessionRead(chat.sessionId)

    fun markAllRead() = state.value.content.finished.forEach(::markRead)

    fun consumeFailure() = actions.update { it.copy(failure = null) }

    private fun act(
        id: String,
        failure: InboxFailure,
        runtimeId: String?,
        block: suspend (OpenCodeBackend) -> Unit,
    ) {
        if (id in actions.value.busyIds) return
        actions.update { it.copy(busyIds = it.busyIds + id, failure = null) }
        viewModelScope.launch {
            val result =
                runCatching {
                    val backend = runtimeId?.let(registry::target) ?: registry.selected.value ?: error("No runtime is selected")
                    block(backend)
                }
            actions.update { current ->
                current.copy(
                    busyIds = current.busyIds - id,
                    failure = if (result.isFailure) failure else current.failure,
                )
            }
        }
    }
}
