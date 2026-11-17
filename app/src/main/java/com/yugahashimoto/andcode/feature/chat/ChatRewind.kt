package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Rewinds on runtimes with checkpoints of their own, for [ChatViewModel]: rewinding the chat to a
 * message of the user's and taking it back, a rewind the session already carries, and the messages a
 * rewind made permanent drops. Launches on the view model's scope and writes into its state; failures
 * go to [reportError], and [onSettled] releases the prompts held while a rewind was landing.
 */
internal class ChatRewind(
    private val backend: OpenCodeBackend?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    /** Shown when the runtime took a rewind request but rewound nothing. */
    private val rewindNothingMessage: String,
    private val reportError: (Throwable) -> Unit,
    private val onSettled: () -> Unit,
) {
    /**
     * Messages a rewind hid when the next message made it permanent. The runtime drops them as it
     * takes that message, but a transcript fetched in between can still carry them, and they must
     * not come back.
     */
    private val discardedByRewind = mutableSetOf<String>()

    /** The transcript a new message is appended to: a pending rewind becomes permanent here. */
    fun keepRewind(state: ChatUiState): List<ChatMessage> {
        val from = state.rewoundFrom ?: return state.messages
        discardedByRewind += state.messages.dropWhile { it.id != from }.map { it.id }
        return state.visibleMessages
    }

    /** Whether [messageId] was dropped by a rewind made permanent, so a transcript read must not bring it back. */
    fun isDiscarded(messageId: String): Boolean = messageId in discardedByRewind

    private fun canRewind(currentBackend: OpenCodeBackend?): Boolean = (currentBackend as? RuntimeTarget)?.capabilities?.checkpoints == true

    /**
     * Picks up a rewind the session already carries - one made on another device, or before the app
     * was last closed - so the chat hides what the runtime will drop on the next message.
     */
    fun refreshRewind(sessionId: String) {
        val currentBackend = backend ?: return
        if (!canRewind(currentBackend)) return
        scope.launch {
            val revert = runCatching { currentBackend.session(sessionId) }.getOrNull()?.revert
            uiState.update { if (it.sessionId == sessionId) it.copy(rewoundFrom = revert?.messageId) else it }
        }
    }

    fun rewindTo(messageId: String) {
        val currentBackend = backend ?: return
        val state = uiState.value
        val sessionId = state.sessionId ?: return
        if (state.isRunning || state.isRewinding || !canRewind(currentBackend)) return
        val message = state.messages.firstOrNull { it.id == messageId && it.isUser } ?: return
        uiState.update { it.copy(isRewinding = true) }
        scope.launch {
            runCatching { currentBackend.revertSession(sessionId, messageId) }
                .onSuccess {
                    // The session says where the rewind really landed; trust that over the request.
                    // One that answers with no rewind at all rewound nothing (a message the runtime
                    // never stored, say), and saying the files are back would be untrue. Only a
                    // session that cannot be read at all falls back to the request.
                    val session = runCatching { currentBackend.session(sessionId) }
                    val landed = if (session.isSuccess) session.getOrNull()?.revert?.messageId else messageId
                    uiState.update {
                        when {
                            it.sessionId != sessionId -> it.copy(isRewinding = false)
                            landed == null -> it.copy(isRewinding = false, error = rewindNothingMessage)
                            else -> it.copy(isRewinding = false, rewoundFrom = landed, editDraft = message.text)
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    uiState.update { it.copy(isRewinding = false) }
                    if (uiState.value.sessionId == sessionId) reportError(error)
                }
            onSettled()
        }
    }

    fun undoRewind() {
        val currentBackend = backend ?: return
        val state = uiState.value
        val sessionId = state.sessionId ?: return
        if (state.rewoundFrom == null || state.isRewinding) return
        uiState.update { it.copy(isRewinding = true) }
        scope.launch {
            runCatching { currentBackend.unrevertSession(sessionId) }
                .onSuccess {
                    // Whatever a failed send marked as gone is back on the server now.
                    discardedByRewind.clear()
                    uiState.update {
                        if (it.sessionId == sessionId) it.copy(isRewinding = false, rewoundFrom = null) else it.copy(isRewinding = false)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    uiState.update { it.copy(isRewinding = false) }
                    if (uiState.value.sessionId == sessionId) reportError(error)
                }
            onSettled()
        }
    }

    /** A rewind made permanent, or made or undone elsewhere (another device, the TUI). */
    fun onSessionUpdated(session: OpenCodeSession) {
        if (session.id == uiState.value.sessionId && canRewind(backend)) {
            uiState.update { if (it.isRewinding) it else it.copy(rewoundFrom = session.revert?.messageId) }
        }
    }
}
