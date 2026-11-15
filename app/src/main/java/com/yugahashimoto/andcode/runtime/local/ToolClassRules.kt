package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Kinds of Claude Code tool calls the user lets run without asking, in every project: reading
 * files, using the web, and editing the project's files. Running commands is never a kind that can
 * be allowed wholesale; those stay per command. The hook reads [FILE_NAME] after the blocking
 * rules, and lets editing by kind touch only files inside the call's project, never under `.git`,
 * `.claude` or the bridge, through which an edit could let commands run unasked.
 */
class ToolClassRules(
    /** The bridge folder; the guest writes there too, hence [GuestFiles]. */
    private val root: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    enum class Kind(
        /** The name stored in the file and matched by the hook. */
        val key: String,
        /** Claude Code's tools of this kind; the hook carries the same lists. */
        val tools: Set<String>,
    ) {
        READING("read", setOf("Read", "Grep", "Glob", "LS", "NotebookRead")),

        /** Apart from reading: a URL or a query can carry what was read out of the device. */
        WEB("web", setOf("WebFetch", "WebSearch")),
        EDITING("edit", setOf("Write", "Edit", "MultiEdit", "NotebookEdit")),
    }

    @Serializable
    private data class Stored(
        val allow: List<String> = emptyList(),
    )

    private val mutableAllowed = MutableStateFlow(load())

    /** The kinds allowed without asking. */
    val allowed: StateFlow<Set<Kind>> = mutableAllowed.asStateFlow()

    @Synchronized
    fun set(
        kind: Kind,
        allow: Boolean,
    ) {
        val next = if (allow) mutableAllowed.value + kind else mutableAllowed.value - kind
        if (next.isEmpty()) {
            // No file means nothing is allowed by kind, and the hook skips the check. One that cannot
            // be removed would keep allowing while the switch shows off: fail instead.
            GuestFiles.delete(root, FILE_NAME)
            if (Files.exists(File(root, FILE_NAME).toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("could not remove $FILE_NAME")
        } else {
            GuestFiles.write(root, FILE_NAME, json.encodeToString(Stored(next.map(Kind::key).sorted())))
        }
        mutableAllowed.value = next
    }

    private fun load(): Set<Kind> {
        val text = runCatching { GuestFiles.readText(root, FILE_NAME) }.getOrNull() ?: return emptySet()
        val keys = runCatching { json.decodeFromString<Stored>(text).allow }.getOrDefault(emptyList())
        return Kind.entries.filter { it.key in keys }.toSet()
    }

    companion object {
        const val FILE_NAME = "class-rules.json"
    }
}
