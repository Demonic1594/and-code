package com.yugahashimoto.andcode.feature.browser.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * What DevTools has recorded for one browser view: the console and the network log, newest last,
 * each capped so a chatty page cannot grow them without bound. Fed by [handle] from the page agent
 * and by the console prompt; it knows nothing of WebView, so it is tested as plain Kotlin.
 *
 * Not thread-safe: the bridge delivers every event on the main thread, and so do the UI's calls.
 */
class DevToolsStore(
    private val maxConsoleEntries: Int = MAX_CONSOLE_ENTRIES,
    private val maxNetworkEntries: Int = MAX_NETWORK_ENTRIES,
) {
    private val mutableConsole = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val console: StateFlow<List<ConsoleEntry>> = mutableConsole.asStateFlow()

    private val mutableNetwork = MutableStateFlow<List<NetworkEntry>>(emptyList())
    val network: StateFlow<List<NetworkEntry>> = mutableNetwork.asStateFlow()

    /** Errors and warnings since the console was last cleared, for the badge on the DevTools button. */
    private val mutableProblems = MutableStateFlow(ProblemCount())
    val problems: StateFlow<ProblemCount> = mutableProblems.asStateFlow()

    /** An element the user picked on the page in inspect mode, for the Elements tab to open. */
    private val mutableInspected = MutableStateFlow<DevToolsEvent.Inspect?>(null)
    val inspected: StateFlow<DevToolsEvent.Inspect?> = mutableInspected.asStateFlow()

    /** Keep the logs across navigations, as DevTools' "Preserve log" does. Off clears them per page. */
    var preserveLog: Boolean = false

    private var nextId = 0L
    private var groupDepth = 0

    fun handle(event: DevToolsEvent) {
        when (event) {
            is DevToolsEvent.Console -> onConsole(event)
            is DevToolsEvent.PageError ->
                append(
                    ConsoleEntry(
                        id = nextId++,
                        level = ConsoleLevel.ERROR,
                        args = listOf(JsonPrimitive(event.message)),
                        source = event.source,
                        stack = event.stack,
                        timestamp = event.timestamp,
                        depth = groupDepth,
                        frame = event.frame,
                    ),
                )
            is DevToolsEvent.NetworkStart -> upsertNetwork(event.entry)
            is DevToolsEvent.NetworkEnd -> finishNetwork(event)
            is DevToolsEvent.WebSocketMessage ->
                mutableNetwork.update { list ->
                    list.map { if (it.id == event.id) it.copy(frames = (it.frames + event.frame).takeLast(MAX_WEBSOCKET_FRAMES)) else it }
                }
            is DevToolsEvent.Resources -> event.entries.forEach(::upsertNetwork)
            is DevToolsEvent.Inspect -> mutableInspected.value = event
            is DevToolsEvent.EvalSettled -> appendResult(event.ok, event.value)
            // A new top-level document: what the old one logged no longer applies.
            is DevToolsEvent.Ready -> if (event.frame.isEmpty()) onNavigation(event.url)
            is DevToolsEvent.Text -> Unit
        }
    }

    /** A new top-level page started loading. */
    fun onNavigation(url: String) {
        groupDepth = 0
        if (preserveLog) {
            append(ConsoleEntry(id = nextId++, level = ConsoleLevel.INFO, args = listOf(JsonPrimitive("Navigated to $url"))))
        } else {
            clearConsole()
            clearNetwork()
        }
    }

    fun appendInput(code: String) {
        append(ConsoleEntry(id = nextId++, level = ConsoleLevel.INPUT, args = listOf(JsonPrimitive(code))))
    }

    fun appendResult(
        ok: Boolean,
        value: JsonElement,
    ) {
        append(ConsoleEntry(id = nextId++, level = if (ok) ConsoleLevel.RESULT else ConsoleLevel.ERROR, args = listOf(value)))
    }

    fun clearConsole() {
        mutableConsole.value = emptyList()
        mutableProblems.value = ProblemCount()
        groupDepth = 0
    }

    fun clearNetwork() {
        mutableNetwork.value = emptyList()
    }

    fun consumeInspected() {
        mutableInspected.value = null
    }

    private fun onConsole(event: DevToolsEvent.Console) {
        when (event.level) {
            "clear" -> {
                clearConsole()
                append(ConsoleEntry(id = nextId++, level = ConsoleLevel.INFO, args = listOf(JsonPrimitive("Console was cleared"))))
                return
            }
            "groupEnd" -> {
                groupDepth = (groupDepth - 1).coerceAtLeast(0)
                return
            }
        }
        append(
            ConsoleEntry(
                id = nextId++,
                level = levelOf(event.level),
                args = event.args,
                source = event.source,
                timestamp = event.timestamp,
                depth = groupDepth,
                frame = event.frame,
            ),
        )
        if (event.level == "group") groupDepth = (groupDepth + 1).coerceAtMost(MAX_GROUP_DEPTH)
    }

    // A line identical to the one before it (same level, text and source) only bumps its counter, as
    // a busy loop logging the same thing would otherwise push everything else out.
    private fun append(entry: ConsoleEntry) {
        mutableConsole.update { list ->
            val last = list.lastOrNull()
            val merged =
                if (last != null && entry.level !in UNMERGED && last.level == entry.level && last.source == entry.source &&
                    last.depth == entry.depth && last.text == entry.text
                ) {
                    list.dropLast(1) + last.copy(repeat = last.repeat + 1, timestamp = entry.timestamp)
                } else {
                    list + entry
                }
            if (merged.size > maxConsoleEntries) merged.takeLast(maxConsoleEntries) else merged
        }
        when (entry.level) {
            ConsoleLevel.ERROR -> mutableProblems.update { it.copy(errors = it.errors + 1) }
            ConsoleLevel.WARN -> mutableProblems.update { it.copy(warnings = it.warnings + 1) }
            else -> Unit
        }
    }

    private fun upsertNetwork(entry: NetworkEntry) {
        mutableNetwork.update { list ->
            // The browser reports a fetch the page made both through the hook and as a resource
            // timing; the hooked one has the headers and body, so the timing is dropped.
            if (entry.id.startsWith("res:") && list.any { it.url == entry.url && !it.id.startsWith("res:") }) return@update list
            val index = list.indexOfFirst { it.id == entry.id }
            val updated = if (index >= 0) list.toMutableList().also { it[index] = entry } else list + entry
            if (updated.size > maxNetworkEntries) updated.takeLast(maxNetworkEntries) else updated
        }
    }

    private fun finishNetwork(event: DevToolsEvent.NetworkEnd) {
        mutableNetwork.update { list ->
            list.map {
                if (it.id != event.id) {
                    it
                } else {
                    it.copy(
                        status = event.status ?: it.status,
                        statusText = event.statusText,
                        responseHeaders = event.responseHeaders.ifEmpty { it.responseHeaders },
                        responseBody = event.responseBody ?: it.responseBody,
                        mime = event.mime.ifEmpty { it.mime },
                        size = event.size ?: it.size,
                        durationMs = event.durationMs ?: it.durationMs,
                        error = event.error,
                    )
                }
            }
        }
    }

    private fun levelOf(level: String): ConsoleLevel =
        when (level) {
            "warn" -> ConsoleLevel.WARN
            "error" -> ConsoleLevel.ERROR
            "info" -> ConsoleLevel.INFO
            "debug" -> ConsoleLevel.DEBUG
            else -> ConsoleLevel.LOG
        }

    data class ProblemCount(
        val errors: Int = 0,
        val warnings: Int = 0,
    )

    private companion object {
        const val MAX_CONSOLE_ENTRIES = 2_000
        const val MAX_NETWORK_ENTRIES = 1_000
        const val MAX_WEBSOCKET_FRAMES = 500
        const val MAX_GROUP_DEPTH = 8
        val UNMERGED = setOf(ConsoleLevel.INPUT, ConsoleLevel.RESULT)
    }
}
