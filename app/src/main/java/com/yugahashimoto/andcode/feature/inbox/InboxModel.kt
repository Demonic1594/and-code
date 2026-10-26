package com.yugahashimoto.andcode.feature.inbox

import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.data.repository.RuntimeActivityState
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.runtime.LocalAgent

/** A chat as the inbox lists it: enough to name it, mark its agent and open it. */
data class InboxChat(
    val sessionId: String,
    /** Blank while the chat is not in the catalog yet; the screen shows "New chat" then. */
    val title: String,
    val runtimeId: String?,
    val agent: LocalAgent?,
    val updatedAt: Long,
)

data class InboxApproval(
    val chat: InboxChat,
    val request: PermissionRequest,
)

data class InboxRun(
    val chat: InboxChat,
    /** What the run is doing now ("bash · ./gradlew test"), when a tool event has said. */
    val step: String?,
)

/**
 * Everything across the user's agents that wants their attention, most urgent first: approvals
 * that hold a run up, runs in progress, and chats that finished while nobody was looking.
 */
data class InboxContent(
    val approvals: List<InboxApproval> = emptyList(),
    val running: List<InboxRun> = emptyList(),
    val finished: List<InboxChat> = emptyList(),
) {
    val isEmpty: Boolean
        get() = approvals.isEmpty() && running.isEmpty() && finished.isEmpty()

    /** Chats that cannot move on without the user: the drawer's badge. */
    val needsYou: Int
        get() = approvals.size + finished.size
}

/**
 * Builds the inbox from every runtime's chats and the activity the event stream has seen.
 *
 * A subagent is not a chat of its own: its approval is filed under the chat that started it (that
 * is where the user answers it), and its run is part of that chat's run. A chat waiting on an
 * approval is listed once, under approvals, not again as running. Finished chats the catalog no
 * longer has (deleted, or [hidden] while a delete can still be undone) are left out; the unread
 * set outlives them.
 */
fun buildInbox(
    sessions: List<RuntimeSessionRef>,
    activity: RuntimeActivityState,
    selectedRuntimeId: String?,
    isSubagent: (String) -> Boolean = { false },
    hidden: Set<String> = emptySet(),
): InboxContent {
    val byId = sessions.associateBy { it.session.id }

    fun rootOf(sessionId: String): String {
        var id = sessionId
        // Bounded: a malformed parent chain must not spin forever.
        repeat(MAX_PARENT_DEPTH) {
            val parent = byId[id]?.session?.parentId ?: return id
            id = parent
        }
        return id
    }

    fun chat(
        sessionId: String,
        fallbackRuntimeId: String?,
    ): InboxChat {
        val ref = byId[sessionId]
        return InboxChat(
            sessionId = sessionId,
            title = ref?.session?.let { it.title.ifBlank { it.slug.orEmpty() } }.orEmpty(),
            runtimeId = ref?.runtimeId ?: fallbackRuntimeId,
            agent = ref?.agent,
            updatedAt = ref?.session?.time?.let { it.updated ?: it.created } ?: 0L,
        )
    }

    fun isRoot(sessionId: String): Boolean = byId[sessionId]?.session?.parentId == null && !isSubagent(sessionId)

    // Permissions only ever come from the selected runtime's stream.
    val approvals =
        activity.permissions
            .filter { rootOf(it.sessionId) !in hidden }
            .map { request -> InboxApproval(chat(rootOf(request.sessionId), selectedRuntimeId), request) }
    val waiting = approvals.map { it.chat.sessionId }.toSet()

    val running =
        activity.activeSessionIds
            .filter { isRoot(it) && it !in waiting && it !in hidden }
            .map { id -> InboxRun(chat(id, selectedRuntimeId), activity.currentSteps[id]) }
            .sortedByDescending { it.chat.updatedAt }

    val finished =
        (activity.completedSessionIds - activity.activeSessionIds - waiting - hidden)
            .filter { it in byId && isRoot(it) }
            .map { id -> chat(id, null) }
            .sortedByDescending { it.updatedAt }

    return InboxContent(approvals, running, finished)
}

private const val MAX_PARENT_DEPTH = 16
