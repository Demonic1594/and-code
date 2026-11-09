package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.ContextBreakdown
import com.yugahashimoto.andcode.core.api.ContextUsage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * What Codex last reported filling each thread's context window, from its `thread/tokenUsage/updated`
 * notifications. The app-server keeps no such figure on the thread itself, so it is kept here and on
 * disk: a chat opened after the app restarts shows its meter before the next turn.
 */
internal class CodexContextUsage(
    private val file: File,
    private val json: Json,
) {
    private val usage = linkedMapOf<String, ContextUsage>()
    private var dirty = false

    init {
        runCatching { json.decodeFromString<Map<String, ContextUsage>>(file.readText()) }
            .getOrNull()
            ?.let(usage::putAll)
    }

    @Synchronized
    fun get(threadId: String): ContextUsage? = usage[threadId]

    /** Records [value] for [threadId] as the newest entry; written out by [flush]. */
    @Synchronized
    fun put(
        threadId: String,
        value: ContextUsage,
    ) {
        usage.remove(threadId)
        usage[threadId] = value
        while (usage.size > MAX_THREADS) usage.remove(usage.keys.first())
        dirty = true
    }

    @Synchronized
    fun remove(threadId: String) {
        if (usage.remove(threadId) != null) dirty = true
        flush()
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            // Written aside and renamed in, so a process killed mid-write keeps the previous file.
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.encodeToString(usage.toMap()))
            if (!temporary.renameTo(file)) {
                temporary.delete()
                error("Could not replace ${file.name}")
            }
            dirty = false
        }
    }

    companion object {
        const val METHOD = "thread/tokenUsage/updated"

        /** The threads kept; the least recently updated go first. */
        private const val MAX_THREADS = 200

        /**
         * The window's fill from a `thread/tokenUsage/updated` body: the last model call's total, which
         * is what Codex itself counts as in the window (`tokens_in_context_window` in its protocol
         * crate), against `modelContextWindow`. Null without a window size, since there is then
         * nothing to measure against.
         */
        fun parse(body: JsonObject): ContextUsage? {
            val tokenUsage = body["tokenUsage"] as? JsonObject ?: return null
            val window = tokenUsage.long("modelContextWindow")?.takeIf { it > 0 } ?: return null
            val used = (tokenUsage["last"] as? JsonObject)?.long("totalTokens") ?: return null
            return ContextUsage(usedTokens = used.coerceAtLeast(0L), windowTokens = window)
        }

        /**
         * The context window sheet for Codex, which reports how much of the window is in use but not
         * what fills it: that share, then what is free.
         */
        fun breakdown(usage: ContextUsage): ContextBreakdown {
            val used = usage.usedTokens.coerceAtMost(usage.windowTokens)
            return ContextBreakdown(
                usedTokens = usage.usedTokens,
                maxTokens = usage.windowTokens,
                categories =
                    listOf(
                        ContextBreakdown.Category(IN_USE, "", used, ContextBreakdown.Kind.USED),
                        ContextBreakdown.Category(FREE_SPACE, "", usage.windowTokens - used, ContextBreakdown.Kind.FREE),
                    ),
                mcpServers = emptyList(),
                memoryFiles = emptyList(),
                autoCompactAt = null,
            )
        }

        /** The category ids [breakdown] uses, which pick the sheet's labels. */
        const val IN_USE = "in_use"
        private const val FREE_SPACE = "free_space"

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }
}
