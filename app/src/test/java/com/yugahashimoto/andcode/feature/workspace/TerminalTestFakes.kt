package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.runtime.local.DevServerProbe
import com.yugahashimoto.andcode.runtime.local.TerminalOpenRequest
import com.yugahashimoto.andcode.runtime.local.TerminalSession
import com.yugahashimoto.andcode.runtime.local.TerminalSessionFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

// Fakes shared by the terminal view model's tests.

internal class FakeSession : TerminalSession {
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val exit = MutableStateFlow<Int?>(null)
    val written = mutableListOf<String>()
    var closed = false
        private set

    override val output: Flow<String> = chunks.receiveAsFlow()
    override val exitCode: StateFlow<Int?> = exit
    override val isAlive: Boolean get() = exit.value == null && !closed

    override fun write(text: String) {
        if (!closed) written += text
    }

    override fun close() {
        closed = true
        finish(143)
    }

    fun emit(text: String) {
        chunks.trySend(text)
    }

    fun finish(code: Int) {
        exit.value = exit.value ?: code
        chunks.close()
    }
}

internal class FakeFactory(
    private val failure: Throwable? = null,
    /** Runs while "opening", the way the screen can be closed during the real blocking open. */
    private val whileOpening: () -> Unit = {},
) : TerminalSessionFactory {
    val requests = mutableListOf<TerminalOpenRequest>()
    val sessions = mutableListOf<FakeSession>()

    override fun open(request: TerminalOpenRequest): TerminalSession {
        requests += request
        failure?.let { throw it }
        whileOpening()
        return FakeSession().also { sessions += it }
    }
}

/** Answers for the ports in [listening]; remembers every port it was asked about. */
internal class FakeProbe : DevServerProbe {
    val listening = mutableSetOf<Int>()
    val asked = mutableListOf<Int>()

    override suspend fun isListening(port: Int): Boolean {
        asked += port
        return port in listening
    }
}
