package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.feature.terminal.DevServer
import com.yugahashimoto.andcode.feature.terminal.DevServerStatus
import com.yugahashimoto.andcode.feature.terminal.DevServerTracker
import com.yugahashimoto.andcode.feature.terminal.TerminalKey
import com.yugahashimoto.andcode.runtime.local.DevServerProbe
import com.yugahashimoto.andcode.runtime.local.LocalTerminalLauncher
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
    private class FakeProbe : DevServerProbe {
        val listening = mutableSetOf<Int>()
        val asked = mutableListOf<Int>()

        override suspend fun isListening(port: Int): Boolean {
            asked += port
            return port in listening
        }
    }

    private fun viewModel(
        factory: FakeFactory = FakeFactory(),
        work: RuntimeWorkTracker = RuntimeWorkTracker(),
        startDirectory: String? = "/workspace/app",
        probe: DevServerProbe = FakeProbe(),
    ): TerminalViewModel = TerminalViewModel(factory, work, startDirectory, probe, ioDispatcher = dispatcher)

    private fun TerminalViewModel.screenText(): List<String> = state.value.lines.map { it.text }

    /** Lets one probe interval pass, so the next round of probes has run. */
    private fun TestScope.nextProbeRound() {
        advanceTimeBy(TerminalViewModel.SERVER_PROBE_INTERVAL_MILLIS)
        runCurrent()
    }

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

    @Test
    fun `sizes the emulator and the pty to the same bounds`() =
        runTest(dispatcher) {
            val small = FakeFactory()
            viewModel(small).open(columns = 5, rows = 1)
            val large = FakeFactory()
            viewModel(large).open(columns = 500, rows = 500)
            runCurrent()

            assertEquals(
                TerminalOpenRequest("/workspace/app", LocalTerminalLauncher.MIN_COLUMNS, LocalTerminalLauncher.MIN_ROWS),
                small.requests.single(),
            )
            assertEquals(
                TerminalOpenRequest("/workspace/app", LocalTerminalLauncher.MAX_COLUMNS, LocalTerminalLauncher.MAX_ROWS),
                large.requests.single(),
            )
        }

    @Test
    fun `a key that edits the line sends the typed text first`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            viewModel.updateInput("git sta")
            viewModel.sendKey(TerminalKey.TAB)

            assertEquals(listOf("git sta", "\t"), factory.sessions.single().written)
            assertEquals("", viewModel.state.value.currentInput)
        }

    @Test
    fun `a key that does not edit the line leaves the typed text alone`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()

            viewModel.updateInput("half typed")
            viewModel.sendKey(TerminalKey.CTRL_C)

            assertEquals(listOf("\u0003"), factory.sessions.single().written)
            assertEquals("half typed", viewModel.state.value.currentInput)
        }

    @Test
    fun `a shell that opens after the screen was closed is ended, not leaked`() =
        runTest(dispatcher) {
            val work = RuntimeWorkTracker()
            lateinit var viewModel: TerminalViewModel
            val factory = FakeFactory(whileOpening = { viewModel.close() })
            viewModel = viewModel(factory, work)

            viewModel.open(60, 24)
            runCurrent()

            assertTrue(factory.sessions.single().closed)
            assertFalse(work.active.value)
        }

    @Test
    fun `clearing keeps the screen empty until the shell prints again`() =
        runTest(dispatcher) {
            val factory = FakeFactory()
            val viewModel = viewModel(factory)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("before\r\n")
            runCurrent()

            viewModel.clear()
            runCurrent()
            assertEquals(listOf(""), viewModel.screenText())

            factory.sessions.single().emit("after")
            runCurrent()
            assertEquals(listOf("after"), viewModel.screenText())
        }

    @Test
    fun `shows a server once the port it announced answers`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("  Local:   http://localhost:5173/\r\n")
            runCurrent()

            assertEquals(listOf(DevServer(5173, DevServerStatus.RUNNING)), viewModel.state.value.servers)
            viewModel.close()
        }

    @Test
    fun `does not show a port that was only typed and echoed`() =
        runTest(dispatcher) {
            val probe = FakeProbe()
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("$ curl http://localhost:8080/health\r\n")
            runCurrent()

            assertTrue(8080 in probe.asked)
            assertTrue(viewModel.state.value.servers.isEmpty())
            viewModel.close()
        }

    @Test
    fun `finds an address even when the colour codes split it`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()

            // Vite bolds the port number inside the URL.
            factory.sessions.single().emit("Local: \u001b[36mhttp://localhost:\u001b[1m5173\u001b[22m/\u001b[39m\r\n")
            runCurrent()

            assertEquals(listOf(5173), viewModel.state.value.servers.map { it.port })
            viewModel.close()
        }

    @Test
    fun `finds an address that arrives in two chunks`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 3000 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("ready on http://local")
            runCurrent()
            factory.sessions.single().emit("host:3000\r\n")
            runCurrent()

            assertEquals(listOf(3000), viewModel.state.value.servers.map { it.port })
            viewModel.close()
        }

    @Test
    fun `lists every server the terminal announced`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += setOf(3000, 5173) }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("api  http://localhost:3000\r\nweb  http://localhost:5173\r\n")
            runCurrent()

            assertEquals(listOf(3000, 5173), viewModel.state.value.servers.map { it.port })
            viewModel.close()
        }

    @Test
    fun `marks a server stopped when it stops answering and running again when it comes back`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("http://localhost:5173/\r\n")
            runCurrent()

            probe.listening.clear()
            nextProbeRound()
            assertEquals(DevServerStatus.RUNNING, viewModel.state.value.servers.single().status)
            nextProbeRound()
            assertEquals(DevServerStatus.STOPPED, viewModel.state.value.servers.single().status)

            probe.listening += 5173
            nextProbeRound()
            assertEquals(DevServerStatus.RUNNING, viewModel.state.value.servers.single().status)
            viewModel.close()
        }

    @Test
    fun `forgets a server that stays down and then stops probing altogether`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("http://localhost:5173/\r\n")
            runCurrent()

            probe.listening.clear()
            repeat(DevServerTracker.DROP_AFTER_MISSES) { nextProbeRound() }
            assertTrue(viewModel.state.value.servers.isEmpty())

            val askedSoFar = probe.asked.size
            repeat(3) { nextProbeRound() }
            assertEquals(askedSoFar, probe.asked.size)
            viewModel.close()
        }

    @Test
    fun `probes again when a forgotten server is announced again`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("http://localhost:5173/\r\n")
            runCurrent()
            probe.listening.clear()
            repeat(DevServerTracker.DROP_AFTER_MISSES) { nextProbeRound() }

            probe.listening += 5173
            factory.sessions.single().emit("restarted: http://localhost:5173/\r\n")
            runCurrent()
            // The loop that gave up is still waiting out its last interval; it picks the port up on waking.
            nextProbeRound()

            assertEquals(DevServerStatus.RUNNING, viewModel.state.value.servers.single().status)
            viewModel.close()
        }

    @Test
    fun `an old announcement still on screen does not bring a forgotten server back`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("http://localhost:5173/\r\n")
            runCurrent()
            probe.listening.clear()
            repeat(DevServerTracker.DROP_AFTER_MISSES) { nextProbeRound() }
            val askedSoFar = probe.asked.size

            factory.sessions.single().emit("GET / 200\r\nGET /app.js 200\r\n")
            runCurrent()
            repeat(3) { nextProbeRound() }

            assertEquals(askedSoFar, probe.asked.size)
            viewModel.close()
        }

    @Test
    fun `never probes while the output names no server`() =
        runTest(dispatcher) {
            val probe = FakeProbe()
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()

            factory.sessions.single().emit("compiled successfully\r\n")
            runCurrent()
            repeat(5) { nextProbeRound() }

            assertTrue(probe.asked.isEmpty())
            viewModel.close()
        }

    @Test
    fun `the servers go away with the shell that started them`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("http://localhost:5173/\r\n")
            runCurrent()

            factory.sessions.single().finish(0)
            runCurrent()

            assertEquals(TerminalStatus.EXITED, viewModel.state.value.status)
            assertTrue(viewModel.state.value.servers.isEmpty())
            val askedSoFar = probe.asked.size
            repeat(3) { nextProbeRound() }
            assertEquals(askedSoFar, probe.asked.size)
        }

    @Test
    fun `closing the screen stops probing`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("http://localhost:5173/\r\n")
            runCurrent()

            viewModel.close()
            runCurrent()

            val askedSoFar = probe.asked.size
            repeat(3) { nextProbeRound() }
            assertEquals(askedSoFar, probe.asked.size)
        }

    @Test
    fun `a restarted shell starts with no servers`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val viewModel = viewModel(factory, probe = probe)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("http://localhost:5173/\r\n")
            runCurrent()
            factory.sessions.single().finish(0)
            runCurrent()

            viewModel.restart()
            runCurrent()

            assertEquals(TerminalStatus.RUNNING, viewModel.state.value.status)
            assertTrue(viewModel.state.value.servers.isEmpty())
            viewModel.close()
        }
}
