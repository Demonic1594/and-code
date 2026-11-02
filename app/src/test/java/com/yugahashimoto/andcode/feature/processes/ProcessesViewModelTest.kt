package com.yugahashimoto.andcode.feature.processes

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.local.GuestProcessHandle
import com.yugahashimoto.andcode.runtime.local.GuestProcessLauncher
import com.yugahashimoto.andcode.runtime.local.ProcessSupervisor
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        // Joined before Main goes back: a status change landing after resetMain would reach the
        // view model's collector on a Main that no longer exists and fail whichever test runs next.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    private class Launcher : GuestProcessLauncher {
        val started = mutableListOf<String>()

        override fun start(
            spec: SupervisedProcessSpec,
            log: File,
        ): GuestProcessHandle {
            started += spec.name
            val exit = CompletableFuture<Int>()
            return object : GuestProcessHandle {
                override fun waitFor() = exit.get()

                override fun kill() {
                    exit.complete(143)
                }
            }
        }
    }

    private class Store(private val kept: List<SupervisedProcessSpec>) : SupervisorStore {
        override fun load() = kept

        override fun save(specs: List<SupervisedProcessSpec>) = Unit
    }

    @Test
    fun `processes from last time are offered until they run again, and can all be started`() {
        val web = SupervisedProcessSpec(name = "web", command = "npm run dev")
        val api = SupervisedProcessSpec(name = "api", command = "python app.py")
        val launcher = Launcher()
        val supervisor = ProcessSupervisor(launcher, temp.newFolder(), Store(listOf(web, api)), RuntimeWorkTracker(), scope)
        val viewModel = ProcessesViewModel(supervisor)

        assertEquals(listOf(web, api), viewModel.state.value.fromLastTime)

        viewModel.start(web)
        waitFor { viewModel.state.value.fromLastTime == listOf(api) }
        viewModel.startAll()
        waitFor { viewModel.state.value.fromLastTime.isEmpty() }

        assertEquals(setOf("web", "api"), viewModel.state.value.processes.map { it.spec.name }.toSet())
        viewModel.stop("web")
        waitFor { viewModel.state.value.processes.first { it.spec.name == "web" }.status == SupervisedStatus.STOPPED }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }
}
