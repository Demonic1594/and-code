package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.feature.terminal.TerminalKey
import com.yugahashimoto.andcode.runtime.local.TerminalOpenRequest
import com.yugahashimoto.andcode.runtime.local.TerminalSession
import com.yugahashimoto.andcode.runtime.local.TerminalSessionFactory
import com.yugahashimoto.andcode.runtime.local.TerminalUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeSession : TerminalSession {
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

    private class FakeFactory(
        private val failure: Throwable? = null,
    ) : TerminalSessionFactory {
        val requests = mutableListOf<TerminalOpenRequest>()
        val sessions = mutableListOf<FakeSession>()

        override fun open(request: TerminalOpenRequest): TerminalSession {
            requests += request
            failure?.let { throw it }
            return FakeSession().also { sessions += it }
        }
    }

    private fun viewModel(
        factory: FakeFactory = FakeFactory(),
        work: RuntimeWorkTracker = RuntimeWorkTracker(),
        startDirectory: String? = "/workspace/app",
    ): TerminalViewModel = TerminalViewModel(factory, work, startDirectory, ioDispatcher = dispatcher)

    private fun TerminalViewModel.screenText(): List<String> = state.value.lines.map { it.text }

    @Test
    fun `waits for the screen to report its size before starting`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)

            runCurrent()

            assertEquals(TerminalStatus.IDLE, viewModel.state.value.status)
            assertTrue(factory.requests.isEmpty())
        }

    @Test
    fun `opens a session in the workspace with the measured size`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)

            viewModel.open(columns = 57, rows = 31)
            runCurrent()

            assertEquals(listOf(TerminalOpenRequest("/workspace/app", 57, 31)), factory.requests)
            assertEquals(TerminalStatus.RUNNING, viewModel.state.value.status)
        }

    @Test
    fun `a second open while a session runs is ignored`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)

            viewModel.open(60, 24)
            viewModel.open(60, 24)
            runCurrent()
            viewModel.open(60, 24)
            runCurrent()

            assertEquals(1, factory.requests.size)
        }

    @Test
    fun `shows output as it streams in`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("server listening\r\n")
            runCurrent()
            assertEquals(listOf("server listening", ""), viewModel.screenText())

            factory.sessions.single().emit("GET /\r\n")
            runCurrent()
            assertEquals(listOf("server listening", "GET /", ""), viewModel.screenText())
        }

    @Test
    fun `interprets colour codes in the output`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("\u001b[31mfail\u001b[0m")
            runCurrent()

            val line = viewModel.state.value.lines.single()
            assertEquals("fail", line.text)
            assertEquals(1, line.spans.size)
        }

    @Test
    fun `submitting sends the typed line with enter and clears the field`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            viewModel.updateInput("ls -la")
            viewModel.submit()

            assertEquals(listOf("ls -la\r"), factory.sessions.single().written)
            assertEquals("", viewModel.state.value.currentInput)
        }

    @Test
    fun `submitting an empty field still presses enter`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            viewModel.submit()

            assertEquals(listOf("\r"), factory.sessions.single().written)
        }

    @Test
    fun `keys are sent as terminal sequences`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            viewModel.sendKey(TerminalKey.CTRL_C)
            viewModel.sendKey(TerminalKey.UP)
            viewModel.sendKey(TerminalKey.TAB)
            viewModel.sendKey(TerminalKey.ESCAPE)
            viewModel.sendKey(TerminalKey.CTRL_D)

            assertEquals(listOf("\u0003", "\u001b[A", "\t", "\u001b", "\u0004"), factory.sessions.single().written)
        }

    @Test
    fun `input is dropped while no session is running`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)

            viewModel.updateInput("echo hi")
            viewModel.submit()
            viewModel.sendKey(TerminalKey.CTRL_C)
            runCurrent()

            assertTrue(factory.sessions.isEmpty())
            assertEquals("echo hi", viewModel.state.value.currentInput)
        }

    @Test
    fun `reports the exit code after draining the last output`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("bye\r\n")
            factory.sessions.single().finish(3)
            runCurrent()

            assertEquals(TerminalStatus.EXITED, viewModel.state.value.status)
            assertEquals(3, viewModel.state.value.exitCode)
            assertEquals(listOf("bye", ""), viewModel.screenText())
        }

    @Test
    fun `holds a runtime lease only while the session lives`() =
        runTest(dispatcher) {
            val work = RuntimeWorkTracker()
            val factory = FakeFactory()
            val viewModel = viewModel(factory, work)
            assertFalse(work.active.value)

            viewModel.open(60, 24)
            runCurrent()
            assertTrue(work.active.value)

            factory.sessions.single().finish(0)
            runCurrent()
            assertFalse(work.active.value)
        }

    @Test
    fun `closing the screen stops the session and releases the lease`() =
        runTest(dispatcher) {
            val work = RuntimeWorkTracker()
            val factory = FakeFactory()
            val viewModel = viewModel(factory, work)
            viewModel.open(60, 24)
            runCurrent()

            viewModel.close()
            runCurrent()

            assertTrue(factory.sessions.single().closed)
            assertFalse(work.active.value)
        }

    @Test
    fun `reports a missing runtime without keeping a lease`() =
        runTest(dispatcher) {
            val work = RuntimeWorkTracker()
            val viewModel = viewModel(FakeFactory(failure = TerminalUnavailableException()), work)

            viewModel.open(60, 24)
            runCurrent()

            assertEquals(TerminalStatus.FAILED, viewModel.state.value.status)
            assertEquals(TerminalFailure.NOT_INSTALLED, viewModel.state.value.failure)
            assertFalse(work.active.value)
        }

    @Test
    fun `reports any other start failure with its detail`() =
        runTest(dispatcher) {
            val work = RuntimeWorkTracker()
            val viewModel = viewModel(FakeFactory(failure = java.io.IOException("exec failed")), work)

            viewModel.open(60, 24)
            runCurrent()

            assertEquals(TerminalStatus.FAILED, viewModel.state.value.status)
            assertEquals(TerminalFailure.START_FAILED, viewModel.state.value.failure)
            assertEquals("exec failed", viewModel.state.value.failureDetail)
            assertFalse(work.active.value)
        }

    @Test
    fun `restart opens a new session after the old one ended and keeps the history`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("old\r\n")
            factory.sessions.single().finish(0)
            runCurrent()

            viewModel.restart()
            runCurrent()

            assertEquals(2, factory.sessions.size)
            assertEquals(TerminalStatus.RUNNING, viewModel.state.value.status)
            assertNull(viewModel.state.value.exitCode)
            assertTrue(viewModel.screenText().contains("old"))
        }

    @Test
    fun `restart is ignored while a session is running`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            viewModel.restart()
            runCurrent()

            assertEquals(1, factory.sessions.size)
        }

    @Test
    fun `restart retries after a failed start`() =
        runTest(dispatcher) {
            val factory = FakeFactory(failure = java.io.IOException("boom"))
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            viewModel.restart()
            runCurrent()

            assertEquals(2, factory.requests.size)
        }

    @Test
    fun `clear empties the screen but not the session`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("noise\r\n")
            runCurrent()

            viewModel.clear()

            assertEquals(listOf(""), viewModel.screenText())
            assertEquals(TerminalStatus.RUNNING, viewModel.state.value.status)
            assertNotNull(factory.sessions.single())
            assertFalse(factory.sessions.single().closed)
        }

    @Test
    fun `exposes the cursor so the screen can draw it`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("ab\r\ncde")
            runCurrent()

            assertEquals(1, viewModel.state.value.cursorRow)
            assertEquals(3, viewModel.state.value.cursorColumn)
        }
}
