package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.core.api.PromptRequest
import kotlinx.coroutines.withTimeoutOrNull

/** How long [followUpPrompt] waits for the transcript before going out on the chat's defaults. */
const val FOLLOW_UP_LOOKUP_TIMEOUT_MS = 3_000L

/**
 * A prompt that continues [sessionId] from outside the chat screen - a reply from the notification
 * shade or the inbox - with the agent and model the chat's last prompt used.
 *
 * Without them OpenCode falls back to its default agent, so answering a chat that runs the
 * read-only `plan` agent would run this one turn with a writing agent. Global settings are no
 * stand-in: they may belong to another runtime and get the prompt rejected outright. The lookup is
 * capped well inside any caller's own timeout so a long transcript cannot eat the time the send
 * itself needs; past it the prompt still goes out, on the chat's defaults.
 */
suspend fun followUpPrompt(
    backend: OpenCodeBackend,
    sessionId: String,
    text: String,
): PromptRequest {
    val last =
        runCatching { withTimeoutOrNull(FOLLOW_UP_LOOKUP_TIMEOUT_MS) { backend.listMessages(sessionId) } }
            .getOrNull()
            ?.lastOrNull { it.info.role == "user" }
            ?.info
    return PromptRequest(
        text = text,
        providerId = last?.model?.providerId,
        modelId = last?.model?.modelId,
        agent = last?.agent,
    )
}
