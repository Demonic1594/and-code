package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.runtime.local.StagedSystemPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The system-prompt preset of the open chat, for [ChatViewModel]: recorded on the session when there
 * is one, else held as [ChatUiState.draftSystemPrompt] until the chat's first message creates the
 * session, then written onto it through [onApplySystemPrompt].
 */
internal class SystemPromptDraft(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val onApplySystemPrompt: (sessionId: String, presetId: String?) -> Unit,
) {
    fun selectSystemPrompt(presetId: String?) {
        val sessionId = uiState.value.sessionId
        if (sessionId == null) {
            uiState.update { it.copy(draftSystemPrompt = StagedSystemPrompt(presetId)) }
            return
        }
        onApplySystemPrompt(sessionId, presetId)
    }

    /**
     * Writes the preset [draft] onto the session this send just created, and consumes it.
     *
     * Called inside the send's own coroutine, before the turn is dispatched, because the send path
     * reads the session's preset when it builds the process arguments - doing this from a
     * recomposition instead would race the message it is meant to accompany.
     *
     * Only the chat's identity is captured by the caller, not the choice itself: a preset belongs to
     * a session, not to a turn - `ClaudeCodeTarget` reads one `promptId` off the session record for
     * every turn it sends - so while the session is still being created there is one choice to make
     * about it, and the last tap before it exists is that choice. Reading the draft here is what
     * makes the chip's answer and the session's answer the same thing.
     *
     * [generation] is what the caller does capture. If the composer has moved to another chat since,
     * the session this send just created belongs to that chat, and the abandoned chat's preset would
     * be applied to a conversation it was never chosen for - so it is dropped instead. The draft is
     * consumed only once applied, so a failed `createSession` leaves it in place for the retry.
     */
    fun applyDraftSystemPrompt(
        sessionId: String,
        generation: Long,
    ) {
        if (uiState.value.chatGeneration != generation) return
        val draft = uiState.value.draftSystemPrompt ?: return
        onApplySystemPrompt(sessionId, draft.id)
        uiState.update { it.copy(draftSystemPrompt = null) }
    }
}
