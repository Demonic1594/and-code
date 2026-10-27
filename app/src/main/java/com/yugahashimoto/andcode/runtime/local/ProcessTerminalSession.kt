package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [TerminalSession] over a started [process]. Output and error are expected to be merged by the
 * caller (`redirectErrorStream`), which is also what a pty does.
 */
class ProcessTerminalSession(
    private val process: Process,
) : TerminalSession {
    private val mutableExitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = mutableExitCode.asStateFlow()

    private val closed = AtomicBoolean(false)

    /**
     * One thread for all input: a write blocks when the shell is not reading (a long `sleep`, a
     * paste larger than the pipe), and it must not stall the UI or reorder what was typed.
     */
    private val input =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "terminal-input").apply { isDaemon = true }
        }

    init {
        Thread(
            {
                mutableExitCode.value = runCatching { process.waitFor() }.getOrDefault(EXIT_UNKNOWN)
                input.shutdown()
            },
            "terminal-exit",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override val isAlive: Boolean get() = process.isAlive

    override val output: Flow<String> =
        flow {
            val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
            val buffer = CharArray(READ_CHUNK)
            try {
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    val batch = StringBuilder(read).append(buffer, 0, read)
                    // Hand over what is already waiting in one piece, so a burst of output costs one
                    // screen update instead of one per read.
                    while (batch.length < MAX_BATCH && reader.ready()) {
                        val more = reader.read(buffer)
                        if (more < 0) break
                        batch.append(buffer, 0, more)
                    }
                    emit(batch.toString())
                }
            } catch (_: IOException) {
                // Closing the session destroys the process, which surfaces here as a failed read:
                // that is the end of the output, not an error worth reporting.
            }
        }.flowOn(Dispatchers.IO)

    override fun write(text: String) {
        if (closed.get()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            input.execute {
                try {
                    process.outputStream.apply {
                        write(bytes)
                        flush()
                    }
                } catch (_: IOException) {
                    // The shell has already gone: nothing is left to receive this input.
                }
            }
        } catch (_: RejectedExecutionException) {
            // Raced with the shell exiting; same as above.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        input.shutdownNow()
        // proot runs with --kill-on-exit, so ending it takes everything inside it down too.
        process.destroyForcibly()
    }

    private companion object {
        const val READ_CHUNK = 4_096
        const val MAX_BATCH = 64 * 1024
        const val EXIT_UNKNOWN = -1
    }
}
