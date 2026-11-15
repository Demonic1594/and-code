package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Removes what an agent CLI itself saved about a conversation, so deleting a chat in AndCode
 * deletes it everywhere rather than only from AndCode's own records.
 *
 * Each CLI keeps per-conversation files named after the conversation id, spread over a few
 * folders in its home directory. Only entries whose name is the id itself, or the id followed by
 * `.` or `-` (`<id>.jsonl`, `<id>-agent-1.json`), are touched, and only ids made of the
 * characters a UUID uses: anything else could match a file that belongs to another conversation.
 * Each is found and removed through [GuestFiles], relative to the rootfs, which never follows a
 * link on the way or inside it: these folders are the guest's, and `File.deleteRecursively` would
 * walk into a linked folder.
 */
object AgentTranscriptFiles {
    private const val CLAUDE_HOME = "root/.claude"
    private const val GEMINI_HOME = "root/.gemini"

    /** Claude Code under the guest's `~/.claude`: transcripts per project folder, plus todo, file-history and env state. */
    fun deleteClaude(
        rootfs: File,
        conversationIds: Collection<String>,
    ): Int {
        val projectFolders =
            runCatching { GuestFiles.listFolders(rootfs, "$CLAUDE_HOME/projects") }
                .getOrDefault(emptyList())
                .map { "$CLAUDE_HOME/projects/$it" }
        val folders = projectFolders + listOf("todos", "file-history", "session-env", "debug").map { "$CLAUDE_HOME/$it" }
        return deleteMatching(rootfs, folders, conversationIds)
    }

    /**
     * Antigravity under the guest's `~/.gemini`. The CLI's layout is not documented; community notes
     * put the conversations and their "brain" folders under `antigravity-cli/` (and `antigravity/`
     * for the desktop build that shares the home directory), so each of those is checked.
     */
    fun deleteAntigravity(
        rootfs: File,
        conversationIds: Collection<String>,
    ): Int {
        val folders =
            listOf("antigravity-cli", "antigravity").flatMap { product ->
                listOf("conversations", "brain").map { "$GEMINI_HOME/$product/$it" }
            }
        return deleteMatching(rootfs, folders, conversationIds)
    }

    /**
     * Deletes the entries of [folders] (relative to [root]) that belong to [conversationIds]. Every
     * step is relative to [root], so a folder the guest made a link (`todos` pointing at this app's
     * data, with an "id" such as `shared_prefs` taken from the CLI's own output) is never listed
     * or deleted through.
     */
    internal fun deleteMatching(
        root: File,
        folders: Collection<String>,
        conversationIds: Collection<String>,
    ): Int {
        val ids = conversationIds.filter(::isSafeId).toSet()
        if (ids.isEmpty()) return 0
        var deleted = 0
        folders.forEach { folder ->
            runCatching { GuestFiles.listNames(root, folder) }.getOrDefault(emptyList()).forEach { name ->
                if (ids.any { id -> belongsTo(name, id) } && GuestFiles.deleteTree(root, "$folder/$name")) deleted++
            }
        }
        return deleted
    }

    private fun belongsTo(
        name: String,
        id: String,
    ): Boolean = name == id || name.startsWith("$id.") || name.startsWith("$id-")

    /** Long enough to be unique and made of UUID characters only, so it cannot spell a path or a short prefix. */
    internal fun isSafeId(id: String): Boolean = id.length >= MIN_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private const val MIN_ID_LENGTH = 8
}
