package com.yugahashimoto.andcode.feature.processes

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.local.GuestProcessHandle
import com.yugahashimoto.andcode.runtime.local.GuestProcessLauncher
import com.yugahashimoto.andcode.runtime.local.ProcessSupervisor
import com.yugahashimoto.andcode.runtime.local.StartResult
import com.yugahashimoto.andcode.runtime.local.SupervisedProcessSpec
import com.yugahashimoto.andcode.runtime.local.SupervisedStatus
import com.yugahashimoto.andcode.runtime.local.SupervisorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessesViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** The view model's main and IO work both run on it, so each call has finished when it returns. */
    private val dispatcher = UnconfinedTestDispatcher()

    /** The supervisor's own watchers block on the process, so they need real threads. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        // Joined before Main goes back: a status change landing after resetMain would reach the
        // view model's collector on a Main that no longer exists and fail whichever test runs next.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    private class Launcher(private val output: String = "") : GuestProcessLauncher {
        val started = mutableListOf<String>()

        override fun start(
            spec: SupervisedProcessSpec,
            log: File,
        ): GuestProcessHandle {
            started += spec.name
            log.writeText(output)
            val exit = CompletableFuture<Int>()
            return object : GuestProcessHandle {
                override fun waitFor() = exit.get()

                override fun kill() {
                    exit.complete(143)
                }
            }
        }
    }

    /** Starts with [kept], as the last run left it, and keeps what is saved over it. */
    private class Store(kept: List<SupervisedProcessSpec>) : SupervisorStore {
        @Volatile var saved = kept

        override fun load() = saved

        override fun save(specs: List<SupervisedProcessSpec>) {
            saved = specs
        }
    }

    private val web = SupervisedProcessSpec(name = "web", command = "npm run dev")
    private val api = SupervisedProcessSpec(name = "api", command = "python app.py")

    private fun supervisor(
        launcher: GuestProcessLauncher,
        store: SupervisorStore,
        maxProcesses: Int = 5,
    ) = ProcessSupervisor(
        launcher,
        temp.newFolder(),
        store,
        RuntimeWorkTracker(),
        scope,
        maxProcesses = maxProcesses,
    )

    @Test
    fun `starting one process from last time keeps the others saved and offered`() {
        val launcher = Launcher()
        val store = Store(listOf(web, api))
        val supervisor = supervisor(launcher, store)
        val viewModel = ProcessesViewModel(supervisor, io = dispatcher)

        assertEquals(listOf(web, api), viewModel.state.value.fromLastTime)

        viewModel.start(web)

        assertEquals(listOf("web"), launcher.started)
        assertEquals(listOf(api), viewModel.state.value.fromLastTime)
        // Leaving the screen and coming back builds a new view model; api must still be there,
        // and still saved for the next time the app starts.
        assertEquals(listOf(api), ProcessesViewModel(supervisor, io = dispatcher).state.value.fromLastTime)
        assertEquals(setOf("web", "api"), store.saved.map { it.name }.toSet())
    }

    @Test
    fun `start all starts everything from last time, and stop stops a process`() {
        val launcher = Launcher()
        val viewModel = ProcessesViewModel(supervisor(launcher, Store(listOf(web, api))), io = dispatcher)

        viewModel.startAll()

        assertEquals(listOf("web", "api"), launcher.started)
        assertEquals(emptyList<SupervisedProcessSpec>(), viewModel.state.value.fromLastTime)
        assertNull(viewModel.state.value.problem)
        viewModel.stop("web")
        waitFor { viewModel.state.value.processes.first { it.spec.name == "web" }.status == SupervisedStatus.STOPPED }
    }

    @Test
    fun `a start that cannot happen says why, and start all stops there`() {
        val launcher = Launcher()
        val viewModel = ProcessesViewModel(supervisor(launcher, Store(listOf(web, api)), maxProcesses = 1), io = dispatcher)

        viewModel.startAll()

        assertEquals(listOf("web"), launcher.started)
        assertEquals(StartResult.LimitReached(1), viewModel.state.value.problem)
        assertEquals(listOf(api), viewModel.state.value.fromLastTime)
        viewModel.problemShown()
        assertNull(viewModel.state.value.problem)
    }

    @Test
    fun `output is read off the main thread when shown and dropped when hidden`() {
        val viewModel = ProcessesViewModel(supervisor(Launcher(output = "listening on 3000"), Store(emptyList())), io = dispatcher)
        viewModel.start(web)

        viewModel.toggleLog("web")
        assertEquals(ShownLog("web", "listening on 3000"), viewModel.state.value.log)

        viewModel.toggleLog("web")
        assertNull(viewModel.state.value.log)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }
}
