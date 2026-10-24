package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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
                if (ids.any { id -> belongsTo(entry.name, id) } && deleteTree(entry.toPath())) deleted++
            }
        }
        return deleted
    }

    /**
     * Deletes [root] and everything under it without following symbolic links: a link is removed
     * itself, never what it points at, so a link inside a transcript folder cannot reach the
     * workspace. (`File.deleteRecursively` follows links into directories.)
     */
    private fun deleteTree(root: Path): Boolean =
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    // One unreadable file must not stop the rest of the folder from going; the
                    // folder itself then stays and the delete reports false.
                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            true
        }.getOrDefault(false)

    private fun belongsTo(
        name: String,
        id: String,
    ): Boolean = name == id || name.startsWith("$id.") || name.startsWith("$id-")

    /** Long enough to be unique and made of UUID characters only, so it cannot spell a path or a short prefix. */
    internal fun isSafeId(id: String): Boolean = id.length >= MIN_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private const val MIN_ID_LENGTH = 8
}
