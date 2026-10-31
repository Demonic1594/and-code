package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CompletableFuture

class DevServerSharingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A tunnel whose output is written as the test says, and that ends when told or killed. */
    private class FakeTunnel : GuestProcessLauncher {
        val exits = mutableListOf<CompletableFuture<Int>>()
        var commands = mutableListOf<String>()
        lateinit var log: File

        override fun start(
            spec: SupervisedProcessSpec,
            log: File,
        ): GuestProcessHandle {
            this.log = log
            commands += spec.command
            val exit = CompletableFuture<Int>().also { exits += it }
            return object : GuestProcessHandle {
                override fun waitFor() = exit.get()

                override fun kill() {
                    exit.complete(143)
                }
            }
        }

        fun print(text: String) = log.appendText(text)
    }

    private fun sharing(
        tunnel: FakeTunnel,
        install: suspend () -> Unit = {},
    ): DevServerSharing {
        val supervisor =
            ProcessSupervisor(tunnel, temp.newFolder(), MemoryStore(), RuntimeWorkTracker(), scope, restartDelayMillis = 1)
        return DevServerSharing(supervisor, install, scope, pollMillis = 10, startTimeoutMillis = 2_000)
    }

    private class MemoryStore : SupervisorStore {
        override fun load() = emptyList<SupervisedProcessSpec>()

        override fun save(specs: List<SupervisedProcessSpec>) = Unit
    }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun `the link appears once cloudflared prints it`() =
        runBlocking {
            val tunnel = FakeTunnel()
            val sharing = sharing(tunnel)

            sharing.start(5173)
            until { sharing.shares.value[5173] == ShareState.Starting }
            assertTrue(tunnel.commands.single().contains("--url http://127.0.0.1:5173"))

            tunnel.print("INF |  https://quiet-river-blue-moon.trycloudflare.com  |\n")
            until { sharing.shares.value[5173] is ShareState.Live }
            assertEquals("https://quiet-river-blue-moon.trycloudflare.com", (sharing.shares.value[5173] as ShareState.Live).url)
        }

    @Test
    fun `a tunnel that dies before its link says why`() =
        runBlocking {
            val tunnel = FakeTunnel()
            val sharing = sharing(tunnel)
            sharing.start(8000)
            until { tunnel.exits.isNotEmpty() }

            tunnel.print("ERR failed to request quick Tunnel: no network\n")
            tunnel.exits.single().complete(1)

            until { sharing.shares.value[8000] is ShareState.Failed }
            assertEquals("ERR failed to request quick Tunnel: no network", (sharing.shares.value[8000] as ShareState.Failed).reason)
        }

    @Test
    fun `an install that fails is reported without starting anything`() =
        runBlocking {
            val tunnel = FakeTunnel()
            val sharing = sharing(tunnel) { error("no network") }

            sharing.start(3000)

            until { sharing.shares.value[3000] is ShareState.Failed }
            assertEquals("no network", (sharing.shares.value[3000] as ShareState.Failed).reason)
            assertTrue(tunnel.commands.isEmpty())
        }

    @Test
    fun `stopping the server ends its link`() =
        runBlocking {
            val tunnel = FakeTunnel()
            val sharing = sharing(tunnel)
            sharing.start(5173)
            until { tunnel.exits.isNotEmpty() }
            tunnel.print("https://a-b.trycloudflare.com\n")
            until { sharing.shares.value[5173] is ShareState.Live }

            sharing.stopGone(listOf(5173, 9999))

            assertNull(sharing.shares.value[5173])
            until { tunnel.exits.single().isDone }
        }

    @Test
    fun `a live link whose tunnel ends is no longer listed`() =
        runBlocking {
            val tunnel = FakeTunnel()
            val sharing = sharing(tunnel)
            sharing.start(5173)
            until { tunnel.exits.isNotEmpty() }
            tunnel.print("https://a-b.trycloudflare.com\n")
            until { sharing.shares.value[5173] is ShareState.Live }

            tunnel.exits.single().complete(0)

            until { sharing.shares.value[5173] == null }
        }

    @Test
    fun `the address is read from cloudflared's output and nothing else`() {
        assertEquals(
            "https://x-y-z.trycloudflare.com",
            DevServerSharing.tunnelUrl("2026 INF Requesting new quick Tunnel...\n|  https://x-y-z.trycloudflare.com  |"),
        )
        assertNull(DevServerSharing.tunnelUrl("https://api.trycloudflare.com.evil.example/path"))
        assertNull(DevServerSharing.tunnelUrl("http://localhost:5173"))
    }

    @Test
    fun `every android abi maps to a static linux build`() {
        assertEquals(
            setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
            CloudflaredReleaseClient.ASSET_BY_ABI.keys,
        )
    }
}
