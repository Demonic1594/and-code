package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CompletableFuture

class ProcessSupervisorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val work = RuntimeWorkTracker()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeHandle : GuestProcessHandle {
        val exit = CompletableFuture<Int>()
        var killed = false

        override fun waitFor(): Int = exit.get()

        override fun kill() {
            killed = true
            exit.complete(143)
        }
    }

    private class FakeLauncher : GuestProcessLauncher {
        val started = mutableListOf<FakeHandle>()
        var output = ""

        override fun start(
            spec: SupervisedProcessSpec,
            log: File,
        ): GuestProcessHandle {
            log.parentFile?.mkdirs()
            log.appendText(output)
            return FakeHandle().also { synchronized(started) { started += it } }
        }
    }

    private class MemoryStore : SupervisorStore {
        var saved: List<SupervisedProcessSpec> = emptyList()

        override fun load() = saved

        override fun save(specs: List<SupervisedProcessSpec>) {
            saved = specs
        }
    }

    private fun supervisor(
        launcher: FakeLauncher = FakeLauncher(),
        store: MemoryStore = MemoryStore(),
        max: Int = 5,
    ) = ProcessSupervisor(
        launcher = launcher,
        logDirectory = temp.newFolder(),
        store = store,
        runtimeWork = work,
        scope = scope,
        maxProcesses = max,
        restartDelayMillis = 1,
    )

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun `a running process keeps the device awake until it is stopped`() =
        runBlocking {
            val store = MemoryStore()
            val supervisor = supervisor(store = store)

            val result = supervisor.start(SupervisedProcessSpec("web", "npm run dev", port = 5173))

            assertTrue(result is StartResult.Started)
            until { work.active.value }
            assertEquals(listOf("web"), store.saved.map { it.name })

            assertTrue(supervisor.stop("web"))
            until { !work.active.value }
            assertEquals(SupervisedStatus.STOPPED, supervisor.processes.value.single().status)
            assertEquals(emptyList<SupervisedProcessSpec>(), store.saved)
        }

    @Test
    fun `a crash is restarted, a clean exit under on_crash is not`() =
        runBlocking {
            val launcher = FakeLauncher()
            val supervisor = supervisor(launcher)
            supervisor.start(SupervisedProcessSpec("api", "python3 app.py"))

            launcher.started.first().exit.complete(1)
            until { launcher.started.size == 2 && supervisor.processes.value.single().status == SupervisedStatus.RUNNING }
            assertEquals(1, supervisor.processes.value.single().restarts)

            launcher.started.last().exit.complete(0)
            until { supervisor.processes.value.single().status == SupervisedStatus.EXITED }
            assertEquals(2, launcher.started.size)
            until { !work.active.value }
        }

    @Test
    fun `restarts stop after the limit and the process is reported crashed`() =
        runBlocking {
            val launcher = FakeLauncher()
            val supervisor = supervisor(launcher)
            supervisor.start(SupervisedProcessSpec("flaky", "false"))

            repeat(ProcessSupervisor.MAX_RESTARTS + 1) { attempt ->
                until { launcher.started.size == attempt + 1 }
                launcher.started[attempt].exit.complete(1)
            }

            until { supervisor.processes.value.single().status == SupervisedStatus.CRASHED }
            assertEquals(ProcessSupervisor.MAX_RESTARTS + 1, launcher.started.size)
        }

    @Test
    fun `starts are refused past the limit, on a taken port, a duplicate or a bad name`() {
        val supervisor = supervisor(max = 2)
        supervisor.start(SupervisedProcessSpec("a", "x", port = 3000))
        supervisor.start(SupervisedProcessSpec("b", "y"))

        assertTrue(supervisor.start(SupervisedProcessSpec("c", "z")) is StartResult.LimitReached)
        supervisor.stop("b")
        assertEquals(StartResult.PortTaken(3000, "a"), supervisor.start(SupervisedProcessSpec("c", "z", port = 3000)))
        assertTrue(supervisor.start(SupervisedProcessSpec("a", "x")) is StartResult.AlreadyRunning)
        assertEquals(StartResult.InvalidName, supervisor.start(SupervisedProcessSpec("../etc", "x")))
    }

    @Test
    fun `the port is read from what the server prints`() =
        runBlocking {
            val launcher = FakeLauncher().apply { output = "  VITE ready\n  Local:   http://localhost:5173/\n" }
            val supervisor = supervisor(launcher)
            supervisor.start(SupervisedProcessSpec("web", "npm run dev"))

            until { supervisor.processes.value.single().port == 5173 }
            assertTrue(supervisor.logTail("web").orEmpty().contains("VITE ready"))
        }

    @Test
    fun `port detection reads only local urls`() {
        assertEquals(8000, ProcessSupervisor.detectPort("Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ..."))
        assertEquals(3000, ProcessSupervisor.detectPort("ready - started server on http://127.0.0.1:3000"))
        assertNull(ProcessSupervisor.detectPort("fetching https://registry.npmjs.org:443/react"))
    }

    @Test
    fun `the bridge starts, lists, reads logs and stops with replies an agent can act on`() =
        runBlocking {
            val launcher = FakeLauncher().apply { output = "listening on http://127.0.0.1:8000\n" }
            val supervisor = supervisor(launcher)
            val bridge = RunBridge(temp.newFolder("workspace"), supervisor)

            val started = bridge.handle("start", JSONObject().put("name", "api").put("command", "python3 -m http.server").put("port", 8000))
            assertTrue(started.getBoolean("ok"))
            assertEquals("http://localhost:8000", started.getJSONObject("data").getString("url"))

            val again = bridge.handle("start", JSONObject().put("name", "api").put("command", "x"))
            assertFalse(again.getBoolean("ok"))
            assertTrue(again.getString("error").contains("andcode-run restart api"))

            val list = bridge.handle("list", JSONObject()).getJSONObject("data").getJSONArray("processes")
            assertEquals("running", list.getJSONObject(0).getString("status"))
            assertTrue(bridge.handle("logs", JSONObject().put("name", "api")).getJSONObject("data").getString("log").contains("listening"))

            assertTrue(bridge.handle("stop", JSONObject().put("name", "api")).getBoolean("ok"))
            assertFalse(bridge.handle("stop", JSONObject().put("name", "api")).getBoolean("ok"))
            until { launcher.started.single().killed }
        }

    @Test
    fun `the bridge answers a request file the guest writes`() {
        val workspace = temp.newFolder("ws")
        val bridge = RunBridge(workspace, supervisor())
        val pending = File(workspace, "${RunBridge.BRIDGE_RELATIVE_PATH}/pending").apply { mkdirs() }
        File(pending, "r1.json").writeText(JSONObject().put("op", "list").put("args", JSONObject()).toString())

        bridge.pollOnce()

        val reply = JSONObject(File(workspace, "${RunBridge.BRIDGE_RELATIVE_PATH}/responses/r1.json").readText())
        assertTrue(reply.getBoolean("ok"))
        assertFalse(File(pending, "r1.json").exists())
    }

    @Test
    fun `saved processes round-trip through the json store`() {
        val store = JsonSupervisorStore(File(temp.root, "processes.json"))
        val specs =
            listOf(
                SupervisedProcessSpec("web", "npm run dev", "/workspace/app", 5173, RestartPolicy.ALWAYS, null),
                SupervisedProcessSpec("api", "python3 app.py"),
            )

        store.save(specs)

        assertEquals(specs, store.load())
    }
}
