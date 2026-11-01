package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.feature.terminal.DevServerStatus
import com.yugahashimoto.andcode.runtime.local.DevServerSharing
import com.yugahashimoto.andcode.runtime.local.GuestProcessHandle
import com.yugahashimoto.andcode.runtime.local.GuestProcessLauncher
import com.yugahashimoto.andcode.runtime.local.ProcessSupervisor
import com.yugahashimoto.andcode.runtime.local.ShareState
import com.yugahashimoto.andcode.runtime.local.SupervisedProcessSpec
import com.yugahashimoto.andcode.runtime.local.SupervisorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The terminal's public links to its servers. */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelShareTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Lets one probe interval pass, so the next round of probes has run. */
    private fun TestScope.nextProbeRound() {
        advanceTimeBy(TerminalViewModel.SERVER_PROBE_INTERVAL_MILLIS)
        runCurrent()
    }

    @Test
    fun `a shared server that stops answering ends its link`() =
        runTest(dispatcher) {
            val probe = FakeProbe().apply { listening += 5173 }
            val factory = FakeFactory()
            val supervisor =
                ProcessSupervisor(NoTunnel, temp.newFolder(), NoStore, RuntimeWorkTracker(), backgroundScope, restartDelayMillis = 1)
            // Never done installing: the share stays put until something ends it.
            val sharing = DevServerSharing(supervisor, install = { awaitCancellation() }, scope = backgroundScope)
            val viewModel =
                TerminalViewModel(factory, RuntimeWorkTracker(), "/workspace/app", probe, ioDispatcher = dispatcher, sharing = sharing)
            viewModel.open(60, 24)
            runCurrent()
            factory.sessions.single().emit("http://localhost:5173/\r\n")
            runCurrent()
            viewModel.share(5173)
            runCurrent()
            assertEquals(ShareState.Installing, viewModel.state.value.shares[5173])

            probe.listening.clear()
            nextProbeRound()
            nextProbeRound()

            assertEquals(DevServerStatus.STOPPED, viewModel.state.value.servers.single().status)
            assertNull(sharing.shares.value[5173])
            assertTrue(viewModel.state.value.shares.isEmpty())
            viewModel.close()
        }

    private object NoTunnel : GuestProcessLauncher {
        override fun start(
            spec: SupervisedProcessSpec,
            log: File,
        ): GuestProcessHandle = error("no tunnel is started in this test")
    }

    private object NoStore : SupervisorStore {
        override fun load() = emptyList<SupervisedProcessSpec>()

        override fun save(specs: List<SupervisedProcessSpec>) = Unit
    }
}
