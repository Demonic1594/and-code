package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edit and resend, for [ChatViewModel]: the last message of the user's and everything after it are
 * deleted so its text can go back into the composer, and the transcript is re-read when the runtime
 * kept any of them. Launches on the view model's scope and writes into its state; [toUiMessage] is the
 * view model's own mapping of a transcript message, and failures go to [reportError].
 */
internal class ChatMessageEdit(
    private val backend: OpenCodeBackend?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val toUiMessage: (OpenCodeMessage) -> ChatMessage?,
    private val reportError: (Throwable) -> Unit,
) {
    fun editLastUserMessage() {
        val currentBackend = backend ?: return
        val sessionId = uiState.value.sessionId ?: return
        if (uiState.value.isRunning) return
        // While rewound, the last message on screen is not the last one stored; deleting "the last"
        // would delete a hidden one.
        if (uiState.value.rewoundFrom != null || uiState.value.isRewinding) return
        if ((currentBackend as? RuntimeTarget)?.capabilities?.editMessages != true) return
        val messages = uiState.value.messages
        val lastUserIndex = messages.indexOfLast { it.isUser }
        if (lastUserIndex < 0) return
        val toRemove = messages.subList(lastUserIndex, messages.size).toList()
        val originalText = messages[lastUserIndex].text
        val idsToRemove = toRemove.map { it.id }.toSet()
        uiState.update {
            it.copy(
                messages = it.messages.filterNot { message -> message.id in idsToRemove },
                editDraft = originalText,
            )
        }
        scope.launch {
            var anyDeleteFailed = false
            // Newest first: if a later delete fails, the ones already gone still leave a clean,
            // shorter transcript instead of a surviving message stranded ahead of a gap.
            toRemove.asReversed().forEach { message ->
                runCatching { currentBackend.deleteMessage(sessionId, message.id) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        anyDeleteFailed = true
                        reportError(error)
                    }
                    .onSuccess { deleted ->
                        // The server signals real failures as thrown HTTP errors, so a `false`
                        // body is unlikely in practice - but deleteMessage's return value is
                        // treated as meaningful elsewhere, so a `false` here should not silently
                        // look like success either. There is no thrown error to report, so just
                        // fall into the same reconciliation reload a thrown failure triggers.
                        if (!deleted) anyDeleteFailed = true
                    }
            }
            // A refused delete leaves the backend holding messages this screen already dropped
            // optimistically. Reload so the transcript reflects what the backend actually kept
            // instead of silently lying to the user. If the reload itself fails there is nothing
            // left to reconcile with, so at least surface that the on-screen transcript may now be
            // wrong instead of swallowing the failure.
            if (anyDeleteFailed) {
                runCatching { reloadMessages(currentBackend, sessionId) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        reportError(error)
                    }
            }
        }
    }

    /**
     * Re-fetches [sessionId]'s transcript from the backend and reconciles it into
     * [ChatUiState.messages], the same merge [ChatViewModel.openSession] does on its initial load. Used to bring
     * the on-screen transcript back in line with the backend after an optimistic change it turns out
     * the backend did not actually apply.
     *
     * Throws if the fetch itself fails; callers decide how to surface that.
     */
    private suspend fun reloadMessages(
        currentBackend: OpenCodeBackend,
        sessionId: String,
    ) {
        val messages = currentBackend.listMessages(sessionId)
        uiState.update {
            it.copy(messages = mergeReloadedMessages(messages.mapNotNull(toUiMessage), it.messages))
        }
    }
}
