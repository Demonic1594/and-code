package com.yugahashimoto.andcode.feature.terminal

enum class DevServerStatus {
    RUNNING,

    /** It answered once and has stopped answering. */
    STOPPED,
}

/** A server listening on [port] of this device, as far as the last probe could tell. */
data class DevServer(
    val port: Int,
    val status: DevServerStatus,
) {
    /** Where the in-app browser opens it. `localhost` covers a server bound to IPv4 or to IPv6 only. */
    val url: String get() = "http://localhost:$port/"
}

/**
 * Which of the ports a terminal mentioned really have a server behind them, and whether they still do.
 *
 * Immutable: every step returns the next tracker. A port starts as a candidate that is probed but not
 * shown - a command echoed back such as `curl http://localhost:8080` names a port nothing listens on -
 * and becomes a [DevServer] the first time it answers. Probing is the caller's job; this only decides
 * what the answers mean.
 */
class DevServerTracker private constructor(
    private val entries: List<Entry>,
) {
    constructor() : this(emptyList())

    private data class Entry(
        val port: Int,
        val seenUp: Boolean,
        /** Probes in a row that got no answer. */
        val misses: Int,
    )

    /** Every port worth probing now: the shown servers and the candidates that have not answered yet. */
    val ports: List<Int> get() = entries.map { it.port }

    /** The servers to show, in the order they were first mentioned. */
    val servers: List<DevServer>
        get() =
            entries
                .filter { it.seenUp }
                .map { DevServer(it.port, if (it.misses >= STOPPED_AFTER_MISSES) DevServerStatus.STOPPED else DevServerStatus.RUNNING) }

    /** Starts watching ports the terminal mentioned. Programs can print any number of them; extras are ignored. */
    fun observe(mentioned: Collection<Int>): DevServerTracker {
        var next = entries
        for (port in mentioned) {
            if (next.size >= MAX_TRACKED) break
            if (next.none { it.port == port }) next = next + Entry(port, seenUp = false, misses = 0)
        }
        return if (next === entries) this else DevServerTracker(next)
    }

    /** Applies one round of probe answers: `true` if the port accepted a connection. */
    fun applyProbe(answers: Map<Int, Boolean>): DevServerTracker =
        DevServerTracker(
            entries.mapNotNull { entry ->
                val answered = answers[entry.port] ?: return@mapNotNull entry
                val updated = if (answered) entry.copy(seenUp = true, misses = 0) else entry.copy(misses = entry.misses + 1)
                updated.takeIf { it.misses < DROP_AFTER_MISSES }
            },
        )

    companion object {
        const val MAX_TRACKED = 8

        /** A slow request can make one probe miss; two in a row is a server that is not there. */
        const val STOPPED_AFTER_MISSES = 2

        /** At the probe interval this is about half a minute: long enough to see "stopped", short enough to free the slot. */
        const val DROP_AFTER_MISSES = 15
    }
}
