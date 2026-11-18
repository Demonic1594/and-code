package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.RunActivity

/**
 * The newer of a part's streamed copy ([existing]) and the part event that just arrived ([chatPart]).
 *
 * Deltas can arrive before the part event that introduces them (see issue #26924): they are
 * accumulated under the same part id, and the late snapshot must not wipe them out with its own
 * (often empty) initial text.
 */
internal fun mergeStreamedPart(
    existing: ChatPart?,
    chatPart: ChatPart,
): ChatPart =
    when (existing) {
        is ChatPart.Text ->
            when {
                chatPart is ChatPart.Text && chatPart.text.isBlank() && existing.text.isNotBlank() -> existing
                // An early delta was stored as Text (deltas carry no part type) but
                // the snapshot reveals it was reasoning: keep the accumulated text.
                chatPart is ChatPart.Reasoning && chatPart.text.isBlank() && existing.text.isNotBlank() ->
                    chatPart.copy(text = existing.text)
                else -> chatPart
            }
        is ChatPart.Reasoning ->
            if ((chatPart is ChatPart.Reasoning && chatPart.text.isBlank()) ||
                (chatPart is ChatPart.Text && chatPart.text.isBlank())
            ) {
                if (existing.text.isNotBlank()) existing else chatPart
            } else {
                chatPart
            }
        else -> chatPart
    }

/**
 * [existing] with a text or reasoning delta appended, or the part the delta starts when there is
 * none yet. Null for a part that carries no text to extend, whose delta is dropped.
 */
internal fun appendStreamedDelta(
    existing: ChatPart?,
    event: OpenCodeEvent.MessagePartDelta,
): ChatPart? =
    when (existing) {
        is ChatPart.Text -> existing.copy(text = existing.text + event.delta)
        is ChatPart.Reasoning -> existing.copy(text = existing.text + event.delta)
        // A delta can outrun the part event that introduces it; start the part here
        // rather than dropping the text on the floor.
        null ->
            if (event.field == "reasoning") {
                ChatPart.Reasoning(event.partId, event.delta)
            } else {
                ChatPart.Text(event.partId, event.delta)
            }
        else -> null
    }

/**
 * [state] with [parts] streamed into message [messageId]: the bubble on screen is updated, a user
 * prompt the parts merely echo takes the server's id, and anything else starts a streaming reply.
 */
internal fun withStreamedParts(
    state: ChatUiState,
    messageId: String,
    parts: List<ChatPart>,
): ChatUiState {
    val index = state.messages.indexOfFirst { it.id == messageId }
    if (index >= 0) {
        val existing = state.messages[index]
        val updated =
            state.messages.toMutableList().apply {
                this[index] =
                    existing.copy(
                        parts = parts,
                        isStreaming = !existing.isUser,
                    )
            }
        return state.copy(
            messages = updated,
            isRunning = true,
            isThinking = false,
            retry = null,
            runActivity = state.runActivity.takeUnless { it == RunActivity.Compacting },
        )
    }

    val incomingText = parts.filterIsInstance<ChatPart.Text>().joinToString("") { it.text }
    val userEchoIndex = state.messages.indexOfLast { it.isUser && it.text == incomingText }
    if (incomingText.isNotBlank() && userEchoIndex >= 0) {
        val updated =
            state.messages.toMutableList().apply {
                this[userEchoIndex] = this[userEchoIndex].copy(id = messageId)
            }
        return state.copy(messages = updated)
    }

    return state.copy(
        messages =
            state.messages +
                ChatMessage(
                    id = messageId,
                    isUser = false,
                    parts = parts,
                    isStreaming = true,
                ),
        isRunning = true,
        isThinking = false,
        retry = null,
        runActivity = state.runActivity.takeUnless { it == RunActivity.Compacting },
    )
}
