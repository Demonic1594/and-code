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
 */
object AgentTranscriptFiles {
    /** Claude Code under `~/.claude`: transcripts per project folder, plus todo, file-history and env state. */
    fun deleteClaude(
        claudeHome: File,
        conversationIds: Collection<String>,
    ): Int {
        val projectFolders = File(claudeHome, "projects").listFiles(File::isDirectory).orEmpty().toList()
        val folders = projectFolders + listOf("todos", "file-history", "session-env", "debug").map { File(claudeHome, it) }
        return deleteMatching(folders, conversationIds)
    }

    /**
     * Antigravity under `~/.gemini`. The CLI's layout is not documented; community notes put the
     * conversations and their "brain" folders under `antigravity-cli/` (and `antigravity/` for the
     * desktop build that shares the home directory), so each of those is checked.
     */
    fun deleteAntigravity(
        geminiHome: File,
        conversationIds: Collection<String>,
    ): Int {
        val folders =
            listOf("antigravity-cli", "antigravity").flatMap { product ->
                listOf("conversations", "brain").map { File(File(geminiHome, product), it) }
            }
        return deleteMatching(folders, conversationIds)
    }

    internal fun deleteMatching(
        folders: Collection<File>,
        conversationIds: Collection<String>,
    ): Int {
        val ids = conversationIds.filter(::isSafeId).toSet()
        if (ids.isEmpty()) return 0
        var deleted = 0
        folders.forEach { folder ->
            folder.listFiles().orEmpty().forEach { entry ->
                if (ids.any { id -> belongsTo(entry.name, id) } && entry.deleteRecursively()) deleted++
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
