package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * What Claude Code was allowed or refused, and how, for the history on the Command rules screen:
 * the phone's own answers, which the app writes here, and what the permission hook decided without
 * asking (a remembered rule, an allowed kind, a blocking rule, no answer in time), which the hook
 * writes to a file of its own so the two never write one file at once. One JSON object a line; each
 * file is cut back to its newest entries.
 */
class ApprovalLog(
    /** The bridge folder, which the guest writes too: both files go through [GuestFiles]. */
    private val root: File,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Entry(
        val timeMs: Long,
        val tool: String,
        val summary: String,
        val allowed: Boolean,
        /** How it was decided: [PHONE], [RULE], [KIND], [BLOCK] or [TIMEOUT]. */
        val how: String,
        /** The rule's words, the kind's key or the blocking rule's words; null when there is none. */
        val rule: String? = null,
    )

    private val mutableEntries = MutableStateFlow<List<Entry>>(emptyList())

    init {
        // The hook appends on every call it decides, so its file is cut back here and on each
        // phone answer too, not only when the history is opened.
        runCatching { trim(HOOK_FILE) }
    }

    /** The newest entries first, as of the last [record], [refresh] or [clear]. */
    val entries: StateFlow<List<Entry>> = mutableEntries.asStateFlow()

    @Synchronized
    fun record(
        tool: String,
        summary: String,
        allowed: Boolean,
        how: String,
        rule: String? = null,
    ) {
        runCatching {
            val line = json.encodeToString(Entry.serializer(), Entry(now(), tool, summary.take(MAX_SUMMARY), allowed, how, rule))
            // Only the app writes this file, so it is read, extended and renamed back in whole.
            val lines = runCatching { GuestFiles.readText(root, APP_FILE) }.getOrNull().orEmpty().lines().filter(String::isNotEmpty) + line
            GuestFiles.write(
                root,
                APP_FILE,
                (if (lines.size > MAX_LINES) lines.takeLast(KEEP_LINES) else lines).joinToString("\n", postfix = "\n"),
            )
            trim(HOOK_FILE)
        }
        refreshLocked()
    }

    /** Reads both files again: the hook's entries only show up through this. */
    @Synchronized
    fun refresh() {
        // The hook only appends; a line it adds while this cuts its file back can be lost, which a
        // history can afford.
        runCatching { trim(HOOK_FILE) }
        refreshLocked()
    }

    @Synchronized
    fun clear() {
        GuestFiles.delete(root, APP_FILE)
        GuestFiles.delete(root, HOOK_FILE)
        mutableEntries.value = emptyList()
    }

    private fun refreshLocked() {
        mutableEntries.value = (read(APP_FILE) + read(HOOK_FILE)).sortedByDescending { it.timeMs }.take(SHOWN)
    }

    private fun read(fileName: String): List<Entry> =
        runCatching { GuestFiles.readText(root, fileName)?.lines().orEmpty() }
            .getOrDefault(emptyList())
            .mapNotNull { line -> runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull() }
            // The guest can write the hook's file, so nothing read back is trusted to be short.
            .map { it.copy(summary = it.summary.take(MAX_SUMMARY), rule = it.rule?.take(MAX_SUMMARY)) }

    private fun trim(fileName: String) {
        val lines = GuestFiles.readText(root, fileName)?.lines()?.filter(String::isNotEmpty) ?: return
        if (lines.size <= MAX_LINES) return
        // Written aside and renamed over the file, so an append from the hook in the meantime is
        // lost whole rather than written over halfway.
        GuestFiles.write(root, fileName, lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val PHONE = "phone"
        const val RULE = "rule"
        const val KIND = "kind"
        const val BLOCK = "block"
        const val TIMEOUT = "timeout"
        internal const val APP_FILE = "approvals.jsonl"
        internal const val HOOK_FILE = "approvals-hook.jsonl"
        private const val MAX_LINES = 400
        private const val KEEP_LINES = 200
        private const val SHOWN = 100
        private const val MAX_SUMMARY = 200

        /** What to show of a call's input: its command, file, URL or pattern, else the input as JSON. Same as the hook. */
        fun summaryOf(input: JsonElement): String {
            val fields = input as? JsonObject
            val named =
                listOf("command", "file_path", "notebook_path", "path", "url", "pattern")
                    .firstNotNullOfOrNull { key -> (fields?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content }
            return (named ?: input.toString()).take(MAX_SUMMARY)
        }
    }
}
