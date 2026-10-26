package com.yugahashimoto.andcode.feature.browser.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The lines DevTools writes into the console itself, localized by the app. */
interface DevToolsMessages {
    fun navigatedTo(url: String): String

    val consoleCleared: String

    val cannotRunCode: String

    /** English, for tests and previews. */
    companion object Default : DevToolsMessages {
        override fun navigatedTo(url: String) = "Navigated to $url"

        override val consoleCleared = "Console was cleared"

        override val cannotRunCode = "The page cannot run code right now."
    }
}

/**
 * What DevTools has recorded for one browser view: the console and the network log, newest last,
 * each capped so a chatty page cannot grow them without bound. Fed by [handleAll] from the page agent
 * and by the console prompt; it knows nothing of WebView, so it is tested as plain Kotlin.
 *
 * Events arrive in batches, and a batch is applied as one update: the lists are copied and
 * published once per batch, not once per line.
 *
 * Not thread-safe: the bridge delivers every event on the main thread, and so do the UI's calls.
 */
class DevToolsStore(
    val messages: DevToolsMessages = DevToolsMessages,
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

    /**
     * Counts top-level documents: it goes up with each new page, so the tools that read the page on
     * demand (Elements, Storage, Sources, Performance) know to read it again.
     */
    private val mutablePage = MutableStateFlow(0)
    val page: StateFlow<Int> = mutablePage.asStateFlow()

    /** Keep the logs across navigations, as DevTools' "Preserve log" does. Off clears them per page. */
    var preserveLog: Boolean = false

    private val consoleItems = ArrayList<ConsoleEntry>()
    private val networkItems = ArrayList<NetworkEntry>()
    private var problemCount = ProblemCount()
    private var nextId = 0L
    private var groupDepth = 0

    fun handle(event: DevToolsEvent) = handleAll(listOf(event))

    fun handleAll(events: List<DevToolsEvent>) {
        if (events.isEmpty()) return
        events.forEach(::apply)
        publish()
    }

    fun appendInput(code: String) {
        appendConsole(ConsoleEntry(id = nextId++, level = ConsoleLevel.INPUT, args = listOf(JsonPrimitive(code))))
        publish()
    }

    fun appendResult(
        ok: Boolean,
        value: JsonElement,
    ) {
        appendConsole(ConsoleEntry(id = nextId++, level = if (ok) ConsoleLevel.RESULT else ConsoleLevel.ERROR, args = listOf(value)))
        publish()
    }

    fun clearConsole() {
        resetConsole()
        publish()
    }

    fun clearNetwork() {
        networkItems.clear()
        publish()
    }

    fun consumeInspected() {
        mutableInspected.value = null
    }

    private fun apply(event: DevToolsEvent) {
        when (event) {
            is DevToolsEvent.Console -> onConsole(event)
            is DevToolsEvent.PageError ->
                appendConsole(
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
            is DevToolsEvent.WebSocketMessage -> {
                val index = networkItems.indexOfFirst { it.id == event.id }
                if (index >= 0) {
                    val entry = networkItems[index]
                    networkItems[index] = entry.copy(frames = (entry.frames + event.frame).takeLast(MAX_WEBSOCKET_FRAMES))
                }
            }
            is DevToolsEvent.Resources -> event.entries.forEach(::upsertNetwork)
            is DevToolsEvent.Inspect -> mutableInspected.value = event
            is DevToolsEvent.EvalSettled -> appendConsole(resultEntry(event.ok, event.value))
            // A new top-level document: what the old one logged no longer applies.
            is DevToolsEvent.Ready -> if (event.frame.isEmpty()) onNavigation(event.url)
            is DevToolsEvent.Text -> Unit
        }
    }

    private fun onNavigation(url: String) {
        groupDepth = 0
        mutablePage.value += 1
        if (preserveLog) {
            appendConsole(ConsoleEntry(id = nextId++, level = ConsoleLevel.INFO, args = listOf(JsonPrimitive(messages.navigatedTo(url)))))
        } else {
            resetConsole()
            networkItems.clear()
        }
    }

    private fun resultEntry(
        ok: Boolean,
        value: JsonElement,
    ) = ConsoleEntry(id = nextId++, level = if (ok) ConsoleLevel.RESULT else ConsoleLevel.ERROR, args = listOf(value))

    private fun resetConsole() {
        consoleItems.clear()
        problemCount = ProblemCount()
        groupDepth = 0
    }

    private fun onConsole(event: DevToolsEvent.Console) {
        when (event.level) {
            "clear" -> {
                resetConsole()
                appendConsole(ConsoleEntry(id = nextId++, level = ConsoleLevel.INFO, args = listOf(JsonPrimitive(messages.consoleCleared))))
                return
            }
            "groupEnd" -> {
                groupDepth = (groupDepth - 1).coerceAtLeast(0)
                return
            }
        }
        appendConsole(
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

    // A line identical to the one before it (same level, values, source and frame) only bumps its
    // counter, as a busy loop logging the same thing would otherwise push everything else out. The
    // values themselves are compared, not their one-line preview, so two different objects that
    // abbreviate the same stay two lines.
    private fun appendConsole(entry: ConsoleEntry) {
        val last = consoleItems.lastOrNull()
        if (last != null && entry.level !in UNMERGED && last.level == entry.level && last.source == entry.source &&
            last.depth == entry.depth && last.frame == entry.frame && last.args == entry.args
        ) {
            consoleItems[consoleItems.lastIndex] = last.copy(repeat = last.repeat + 1, timestamp = entry.timestamp)
        } else {
            consoleItems += entry
            if (consoleItems.size > maxConsoleEntries) consoleItems.subList(0, consoleItems.size - maxConsoleEntries).clear()
        }
        problemCount =
            when (entry.level) {
                ConsoleLevel.ERROR -> problemCount.copy(errors = problemCount.errors + 1)
                ConsoleLevel.WARN -> problemCount.copy(warnings = problemCount.warnings + 1)
                else -> problemCount
            }
    }

    private fun upsertNetwork(entry: NetworkEntry) {
        // The browser reports a fetch the page made both through the hook and as a resource
        // timing; the hooked one has the headers and body, so the timing is dropped.
        if (entry.id.startsWith("res:") && networkItems.any { it.url == entry.url && !it.id.startsWith("res:") }) return
        val index = networkItems.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            networkItems[index] = entry
        } else {
            networkItems += entry
            if (networkItems.size > maxNetworkEntries) networkItems.subList(0, networkItems.size - maxNetworkEntries).clear()
        }
    }

    private fun finishNetwork(event: DevToolsEvent.NetworkEnd) {
        val index = networkItems.indexOfFirst { it.id == event.id }
        if (index < 0) return
        val entry = networkItems[index]
        networkItems[index] =
            entry.copy(
                status = event.status ?: entry.status,
                statusText = event.statusText,
                responseHeaders = event.responseHeaders.ifEmpty { entry.responseHeaders },
                responseBody = event.responseBody ?: entry.responseBody,
                mime = event.mime.ifEmpty { entry.mime },
                size = event.size ?: entry.size,
                durationMs = event.durationMs ?: entry.durationMs,
                error = event.error,
            )
    }

    private fun publish() {
        mutableConsole.value = consoleItems.toList()
        mutableNetwork.value = networkItems.toList()
        mutableProblems.value = problemCount
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
