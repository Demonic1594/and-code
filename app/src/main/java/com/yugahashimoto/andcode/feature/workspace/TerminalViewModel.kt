package com.yugahashimoto.andcode.feature.workspace

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.feature.terminal.AnsiTerminalEmulator
import com.yugahashimoto.andcode.feature.terminal.DevServer
import com.yugahashimoto.andcode.feature.terminal.DevServerDetector
import com.yugahashimoto.andcode.feature.terminal.DevServerStatus
import com.yugahashimoto.andcode.feature.terminal.DevServerTracker
import com.yugahashimoto.andcode.feature.terminal.StyledLine
import com.yugahashimoto.andcode.feature.terminal.TerminalKey
import com.yugahashimoto.andcode.feature.terminal.WrappedLines
import com.yugahashimoto.andcode.runtime.local.DevServerProbe
import com.yugahashimoto.andcode.runtime.local.DevServerSharing
import com.yugahashimoto.andcode.runtime.local.LocalTerminalLauncher
import com.yugahashimoto.andcode.runtime.local.LoopbackDevServerProbe
import com.yugahashimoto.andcode.runtime.local.ShareState
import com.yugahashimoto.andcode.runtime.local.TerminalOpenRequest
import com.yugahashimoto.andcode.runtime.local.TerminalSession
import com.yugahashimoto.andcode.runtime.local.TerminalSessionFactory
import com.yugahashimoto.andcode.runtime.local.TerminalUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.IdentityHashMap

enum class TerminalStatus {
    /** Waiting for the screen to say how large it is. */
    IDLE,
    STARTING,
    RUNNING,
    EXITED,
    FAILED,
}

enum class TerminalFailure {
    NOT_INSTALLED,
    START_FAILED,
}

data class TerminalUiState(
    val lines: List<StyledLine> = listOf(StyledLine("")),
    val cursorRow: Int = 0,
    val cursorColumn: Int = 0,
    val status: TerminalStatus = TerminalStatus.IDLE,
    val exitCode: Int? = null,
    val failure: TerminalFailure? = null,
    val failureDetail: String? = null,
    val currentInput: String = "",
    /** Servers the shell has started that answer on this device; empty once the shell ends. */
    val servers: List<DevServer> = emptyList(),
    /** Servers shared by link, by port; a port that is not here is not shared. */
    val shares: Map<Int, ShareState> = emptyMap(),
)

/**
 * Owns one interactive shell for as long as the terminal screen is open. Leaving the screen ends
 * the shell and anything it started - a dev server does not outlive the screen that launched it.
 *
 * It also keeps an eye on the servers the shell starts: ports the output announces are probed, and
 * the ones that answer are published as [TerminalUiState.servers] for the screen to open in the
 * in-app browser.
 */
class TerminalViewModel(
    private val sessions: TerminalSessionFactory,
    /**
     * A shell can be running a build or a dev server on the runtime's proot process, which is real
     * work just like a chat turn but invisible to
     * [com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository] - so without a lease
     * the device could suspend under it. Held for exactly as long as the shell is alive.
     */
    private val runtimeWork: RuntimeWorkTracker,
    /** A directory in the sandbox to start in; null starts in the sandbox's home. */
    private val startDirectory: String? = null,
    private val serverProbe: DevServerProbe = LoopbackDevServerProbe(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Public links to the servers; null where the device cannot run a tunnel. */
    private val sharing: DevServerSharing? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /** Guards [emulator], [session] and [closed], and orders every publish of the screen. */
    private val lock = Any()
    private var emulator: AnsiTerminalEmulator? = null
    private var session: TerminalSession? = null
    private var closed = false
    private var sessionJob: Job? = null

    /** Guarded by [lock], like the fields that follow: what the shell announced, and what answered. */
    private var tracker = DevServerTracker()
    private var probing = false
    private var probeJob: Job? = null

    /** Ports this terminal shared: their links end with its servers, or with the screen. */
    private val sharedPorts = mutableSetOf<Int>()

    init {
        sharing?.let { links ->
            viewModelScope.launch {
                links.shares.collect { all -> publishShares(all) }
            }
        }
    }

    /** Only this terminal's shares, read under [lock] like every write to [sharedPorts]. */
    private fun publishShares(all: Map<Int, ShareState>) {
        synchronized(lock) {
            _state.update { it.copy(shares = all.filterKeys { port -> port in sharedPorts }) }
        }
    }

    /** Starts a public link to [port]. The screen asks first: anyone with the link reaches the server. */
    fun share(port: Int) {
        val links = sharing ?: return
        synchronized(lock) { sharedPorts += port }
        links.start(port)
        // Already shared (a retry, or before), the flow may not emit again: show what it holds now.
        publishShares(links.shares.value)
    }

    fun stopSharing(port: Int) {
        synchronized(lock) { sharedPorts -= port }
        sharing?.stop(port)
    }

    private fun stopAllSharing() {
        val ports = synchronized(lock) { sharedPorts.toList().also { sharedPorts.clear() } }
        ports.forEach { sharing?.stop(it) }
    }

    /** The screen lines already searched for addresses, compared by identity (see [freshTextOf]). */
    private var scannedLines: Set<StyledLine> = identitySetOf(emptyList())
    private var columns = 0
    private var rows = 0

    /** The screen measured itself; start the shell at that size. Later calls are ignored. */
    fun open(
        columns: Int,
        rows: Int,
    ) {
        if (_state.value.status != TerminalStatus.IDLE) return
        // The same bounds the pty is sized with, so what the shell wraps to is what is drawn.
        this.columns = columns.coerceIn(LocalTerminalLauncher.MIN_COLUMNS, LocalTerminalLauncher.MAX_COLUMNS)
        this.rows = rows.coerceIn(LocalTerminalLauncher.MIN_ROWS, LocalTerminalLauncher.MAX_ROWS)
        synchronized(lock) { emulator = AnsiTerminalEmulator(columns = this.columns, rows = this.rows) }
        start()
    }

    /** Starts a fresh shell after the previous one ended or failed to start. History stays on screen. */
    fun restart() {
        val status = _state.value.status
        if (status != TerminalStatus.EXITED && status != TerminalStatus.FAILED) return
        // The old shell may have stopped mid-line; the new prompt starts on a line of its own.
        receive("\r\n")
        start()
    }

    private fun start() {
        _state.update {
            it.copy(
                status = TerminalStatus.STARTING,
                exitCode = null,
                failure = null,
                failureDetail = null,
            )
        }
        sessionJob =
            viewModelScope.launch {
                val lease = runtimeWork.acquire(TERMINAL_LEASE_TAG)
                try {
                    run()
                } finally {
                    lease.release()
                }
            }
    }

    private suspend fun run() {
        val opened =
            try {
                withContext(ioDispatcher) {
                    sessions.open(TerminalOpenRequest(startDirectory, columns, rows)).also(::adopt)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TerminalUnavailableException) {
                fail(TerminalFailure.NOT_INSTALLED, detail = null)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Terminal session failed to start", e)
                fail(TerminalFailure.START_FAILED, e.message)
                return
            }
        _state.update { it.copy(status = TerminalStatus.RUNNING) }
        try {
            withContext(ioDispatcher) { opened.output.collect(::receive) }
            // Output ends before the exit code is published by a hair; waiting for it here keeps the
            // last line of output and the "exited" status from ever being reported in the wrong order.
            val exit = opened.exitCode.first { it != null }
            _state.update { it.copy(status = TerminalStatus.EXITED, exitCode = exit) }
        } finally {
            opened.close()
            synchronized(lock) { if (session === opened) session = null }
            // Everything the shell started died with it; leaving its servers listed would offer links
            // to pages that are gone.
            endServerWatch()
        }
    }

    /**
     * Takes ownership of a freshly opened [candidate]. Opening is blocking work that cannot be
     * cancelled, so the screen may have been closed while it ran; a session that arrives after that
     * is ended here instead of being left running with nothing to own it.
     */
    private fun adopt(candidate: TerminalSession) {
        val accepted =
            synchronized(lock) {
                if (!closed) session = candidate
                !closed
            }
        if (!accepted) {
            candidate.close()
            throw CancellationException("The terminal was closed while its shell was starting")
        }
    }

    private fun receive(chunk: String) {
        publish { feed(chunk) }
    }

    /**
     * Applies [change] to the emulator and publishes the result under the same lock, so a late
     * snapshot can never overwrite a newer one (a `clear` racing a burst of output would otherwise
     * bring the cleared text back).
     */
    private fun publish(change: AnsiTerminalEmulator.() -> Unit) {
        val probeToStart =
            synchronized(lock) {
                val terminal = emulator ?: return
                val snapshot =
                    try {
                        terminal.change()
                        terminal.snapshot()
                    } catch (e: RuntimeException) {
                        // The stream is untrusted bytes; whatever it does, it must not take the app down.
                        Log.w(TAG, "Terminal output could not be interpreted", e)
                        return
                    }
                _state.update {
                    it.copy(lines = snapshot.lines, cursorRow = snapshot.cursorRow, cursorColumn = snapshot.cursorColumn)
                }
                watchForServers(snapshot.lines)
            }
        // Started outside the lock. It was created, and recorded in [probeJob], inside it, so a close or
        // an exit that comes in between cancels it before it ever runs.
        probeToStart?.start()
    }

    /**
     * Looks for servers in the lines that appeared since the last call. Returns the probe loop to start
     * when there is now something to probe and no loop is running. Called with [lock] held.
     */
    private fun watchForServers(lines: List<StyledLine>): Job? {
        try {
            val mentioned = DevServerDetector.portsIn(freshTextOf(lines))
            if (mentioned.isNotEmpty()) tracker = tracker.observe(mentioned)
        } catch (e: RuntimeException) {
            // Finding servers is a convenience on top of untrusted output; if it breaks, the screen goes on.
            Log.w(TAG, "Terminal output could not be searched for servers", e)
        }
        if (probing || closed || tracker.ports.isEmpty()) return null
        probing = true
        return viewModelScope.launch(start = CoroutineStart.LAZY) { probeServers() }.also { probeJob = it }
    }

    /**
     * The text of the lines the program printed that are new, or changed, since the last call.
     *
     * Only the end of the screen is searched, and only what changed in it: an address printed long ago
     * and still on screen must not bring back a server that has since been forgotten. The emulator
     * hands out the same [StyledLine] for a row until the row changes, so comparing by identity finds
     * exactly the new and rewritten rows - a reprinted line is new even if its text is not. Rows the
     * screen width wrapped are put back together first ([WrappedLines]). Called with [lock] held.
     */
    private fun freshTextOf(lines: List<StyledLine>): List<String> {
        val tail = lines.takeLast(SCAN_TAIL_LINES)
        val seen = scannedLines
        scannedLines = identitySetOf(tail)
        return WrappedLines.join(tail, columns) { it !in seen }
    }

    /**
     * Asks every port the shell announced whether it answers, now and then every
     * [SERVER_PROBE_INTERVAL_MILLIS], for as long as there is a port worth asking about. Output that
     * names no server never gets here, so a plain shell costs nothing.
     */
    private suspend fun probeServers() {
        while (true) {
            val ports =
                synchronized(lock) {
                    val tracked = tracker.ports
                    // Deciding to stop and saying so happen under one lock, so a port announced in
                    // between starts a new loop instead of waiting on one that is already ending.
                    if (closed || tracked.isEmpty()) {
                        probing = false
                        return
                    }
                    tracked
                }
            val answers =
                coroutineScope {
                    ports.map { port -> async(ioDispatcher) { port to isAnswering(port) } }.awaitAll().toMap()
                }
            synchronized(lock) {
                tracker = tracker.applyProbe(answers)
                _state.update { it.copy(servers = tracker.servers) }
                // A link must not outlive the server it points at.
                val stopped = tracker.servers.filter { it.status == DevServerStatus.STOPPED }.map { it.port }
                if (stopped.isNotEmpty()) sharing?.stopGone(stopped.filter { it in sharedPorts })
            }
            delay(SERVER_PROBE_INTERVAL_MILLIS)
        }
    }

    private suspend fun isAnswering(port: Int): Boolean =
        try {
            serverProbe.isListening(port)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Could not probe port $port", e)
            false
        }

    private fun endServerWatch() {
        synchronized(lock) {
            tracker = DevServerTracker()
            probing = false
            probeJob?.cancel()
            probeJob = null
            _state.update { it.copy(servers = emptyList()) }
        }
        stopAllSharing()
    }

    private fun fail(
        failure: TerminalFailure,
        detail: String?,
    ) {
        _state.update { it.copy(status = TerminalStatus.FAILED, failure = failure, failureDetail = detail) }
    }

    fun updateInput(text: String) {
        _state.update { it.copy(currentInput = text) }
    }

    /** Presses Enter on whatever is typed, including nothing - an empty prompt line is valid input. */
    fun submit() {
        val running = runningSession() ?: return
        running.write(_state.value.currentInput + ENTER)
        _state.update { it.copy(currentInput = "") }
    }

    fun sendKey(key: TerminalKey) {
        val running = runningSession() ?: return
        val typed = _state.value.currentInput
        if (key.editsLine && typed.isNotEmpty()) {
            running.write(typed)
            _state.update { it.copy(currentInput = "") }
        }
        running.write(key.sequence)
    }

    /** Empties what is drawn. The shell is untouched and keeps printing below. */
    fun clear() {
        publish { this.clear() }
    }

    private fun runningSession(): TerminalSession? = synchronized(lock) { session }.takeIf { _state.value.status == TerminalStatus.RUNNING }

    /** Ends the shell and releases the runtime lease. Called when the screen is left for good. */
    fun close() {
        val ending =
            synchronized(lock) {
                closed = true
                probeJob?.cancel()
                session
            }
        ending?.close()
        sessionJob?.cancel()
    }

    override fun onCleared() {
        stopAllSharing()
        close()
    }

    companion object {
        /** How often the servers the shell announced are asked whether they still answer. */
        internal const val SERVER_PROBE_INTERVAL_MILLIS = 2_000L

        /** How many of the newest screen lines are searched for server addresses after each burst of output. */
        private const val SCAN_TAIL_LINES = 200
        private const val TAG = "TerminalViewModel"
        private const val TERMINAL_LEASE_TAG = "terminal"
        private const val ENTER = "\r"
    }
}

private fun identitySetOf(lines: Collection<StyledLine>): Set<StyledLine> =
    Collections.newSetFromMap(IdentityHashMap<StyledLine, Boolean>()).apply { addAll(lines) }
