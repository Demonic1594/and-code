package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeFileChange

/**
 * Snapshots of the workspace taken before each of the user's messages, for agents with no
 * checkpoints of their own. A turn is the 0-based position of the user message in the chat.
 *
 * Absent (null in [ChatViewModel]) on runtimes that cannot be snapshotted - a remote server has no
 * shell on this device - and on those whose own checkpoints already rewind (legacy OpenCode).
 */
interface TurnSnapshots {
    /** Snapshots [directory] as the start of [turn] in [sessionId]. Failing is fine: that turn just has none. */
    suspend fun record(
        sessionId: String,
        turn: Int,
        directory: String,
    )

    fun has(
        sessionId: String,
        turn: Int,
    ): Boolean

    /**
     * Puts the files back to how they were before [turn]. Returns a token for [undoRestore], or null
     * when there was no snapshot or the restore failed.
     */
    suspend fun restore(
        sessionId: String,
        turn: Int,
        directory: String,
    ): String?

    suspend fun undoRestore(
        directory: String,
        token: String,
    ): Boolean

    /** What [turn] changed: from its snapshot to the next turn's, or to the files as they are now. */
    suspend fun turnChanges(
        sessionId: String,
        turn: Int,
        directory: String,
    ): List<OpenCodeFileChange>?
}

/** Where each turn's snapshot id is kept, per chat. */
interface TurnSnapshotIndex {
    fun get(
        sessionId: String,
        turn: Int,
    ): String?

    fun put(
        sessionId: String,
        turn: Int,
        snapshot: String,
    )
}

/**
 * The user turn a message belongs to: a user message's own position among the user messages, and an
 * assistant message the position of the user message before it. Null before the first user message.
 */
internal fun turnOf(
    messages: List<ChatMessage>,
    messageId: String,
): Int? {
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return null
    val users = messages.take(index + 1).count { it.isUser }
    return (users - 1).takeIf { it >= 0 }
}

/**
 * Keeps the changes a patch card names: its paths may be absolute or relative to the workspace, so a
 * change matches when either path ends with the other.
 */
internal fun changesForPatch(
    changes: List<OpenCodeFileChange>,
    files: List<String>,
): List<OpenCodeFileChange> {
    if (files.isEmpty()) return changes
    val wanted = files.map { it.trimStart('/') }
    return changes.filter { change ->
        val path = change.displayPath.trimStart('/')
        path.isNotEmpty() && wanted.any { it.endsWith(path) || path.endsWith(it) }
    }
}
