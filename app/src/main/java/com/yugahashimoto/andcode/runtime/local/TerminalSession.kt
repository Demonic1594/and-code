package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A long-lived interactive shell. Unlike [LocalRuntimeCommandRunner], which runs one bounded command
 * and returns its last few kilobytes, a session keeps running until it is closed or the shell exits,
 * streams everything it prints and accepts input at any time.
 */
interface TerminalSession {
    /**
     * Everything the shell prints, in order, as it prints it. The flow ends when the process closes
     * its output, so collecting it to the end means no output is left behind. It has a single
     * consumer: collect it once.
     */
    val output: Flow<String>

    /** The process exit code, or null while it is still running. */
    val exitCode: StateFlow<Int?>

    val isAlive: Boolean

    /** Sends [text] to the shell as typed input. Never blocks, and is dropped once the session is closed. */
    fun write(text: String)

    /** Ends the session and everything running inside it. Safe to call more than once. */
    fun close()
}

/** Where and how large the shell should start. */
data class TerminalOpenRequest(
    /** A directory inside the sandbox; the shell falls back to its home when it cannot go there. */
    val startDirectory: String?,
    val columns: Int,
    val rows: Int,
)

/** Starts [TerminalSession]s. Blocking: callers run it off the main thread. */
fun interface TerminalSessionFactory {
    /** @throws TerminalUnavailableException when there is no Linux environment to start a shell in */
    fun open(request: TerminalOpenRequest): TerminalSession
}

/** The Linux environment the terminal needs has not been installed. */
class TerminalUnavailableException : Exception("The local runtime is not installed")
