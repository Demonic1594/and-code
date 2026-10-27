package com.yugahashimoto.andcode.feature.workspace

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.feature.terminal.AnsiTerminalEmulator
import com.yugahashimoto.andcode.feature.terminal.StyledLine
import com.yugahashimoto.andcode.feature.terminal.TerminalKey
import com.yugahashimoto.andcode.runtime.local.LocalTerminalLauncher
import com.yugahashimoto.andcode.runtime.local.TerminalOpenRequest
import com.yugahashimoto.andcode.runtime.local.TerminalSession
import com.yugahashimoto.andcode.runtime.local.TerminalSessionFactory
import com.yugahashimoto.andcode.runtime.local.TerminalUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

/**
 * Owns one interactive shell for as long as the terminal screen is open. Leaving the screen ends
 * the shell and anything it started - a dev server does not outlive the screen that launched it.
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /** Guards [emulator], [session] and [closed], and orders every publish of the screen. */
    private val lock = Any()
    private var emulator: AnsiTerminalEmulator? = null
    private var session: TerminalSession? = null
    private var closed = false
    private var sessionJob: Job? = null
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
        }
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
                session
            }
        ending?.close()
        sessionJob?.cancel()
    }

    override fun onCleared() {
        close()
    }

    private companion object {
        const val TAG = "TerminalViewModel"
        const val TERMINAL_LEASE_TAG = "terminal"
        const val ENTER = "\r"
    }
}
