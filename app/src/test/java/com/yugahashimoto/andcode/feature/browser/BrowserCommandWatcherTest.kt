package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrowserCommandWatcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val runtimeDirectory by lazy { temporaryFolder.newFolder("runtime") }

    @Test
    fun `each Alpine agent consumes the command from the guest home`() {
        listOf(LocalAgent.OPEN_CODE, LocalAgent.CLAUDE_CODE, LocalAgent.CODEX).forEach { agent ->
            val file = command("environment/rootfs")

            assertEquals("https://www.google.com/", consume("/root/project", agent))
            assertFalse(file.exists())
            assertNull(consume("/root/project", agent))
        }
    }

    @Test
    fun `the command is consumed whichever local folder is open`() {
        val file = command("environment/rootfs")

        assertEquals("https://www.google.com/", consume("/sdcard/Download/project"))
        assertFalse(file.exists())
    }

    @Test
    fun `Antigravity consumes only the command in its Debian rootfs`() {
        val file = command("environment/antigravity-rootfs")
        val alpine = command("environment/rootfs", "https://example.com/alpine")

        assertEquals("https://www.google.com/", consume("/root/project", LocalAgent.ANTIGRAVITY))
        assertFalse(file.exists())
        assertTrue(alpine.exists())
    }

    @Test
    fun `Antigravity falls back to the shared rootfs when Debian is absent`() {
        val file = command("environment/rootfs")

        assertEquals("https://www.google.com/", consume("/root/project", LocalAgent.ANTIGRAVITY))
        assertFalse(file.exists())
    }

    /**
     * Where commands used to go, and where a cloned or pulled repository can put one, even one stamped
     * a moment ago by a bot that keeps the file fresh.
     */
    @Test
    fun `a command inside the workspace is never opened, however fresh`() {
        val inRootfs = writeCommand(File(runtimeDirectory, "environment/rootfs/root/project/.and-code"), "https://phishing.example/")
        val inWorkspace = writeCommand(File(runtimeDirectory, "workspace/project/.and-code"), "https://phishing.example/")

        assertNull(consume("/root/project"))
        assertNull(consume("/workspace/project"))
        assertTrue("the user's checkout is left as it is", inRootfs.exists())
        assertTrue(inWorkspace.exists())
    }

    @Test
    fun `remote runtimes and absent workspaces leave local commands untouched`() {
        val file = command("environment/rootfs")

        assertNull(consume("/workspace/project", agent = null))
        assertNull(consume(null))
        assertTrue(file.exists())
    }

    @Test
    fun `missing command does not open the browser`() {
        assertNull(consume("/workspace/project"))
    }

    @Test
    fun `partially written command is retried after the writer completes it`() {
        val file = command("environment/rootfs")
        file.writeText("{\"action\":\"open\",\"url\":")

        assertNull(consume("/workspace/project"))
        assertTrue(file.exists())

        file.writeText("{\"action\":\"open\",\"url\":\"http://127.0.0.1:8080/\",\"createdAtMs\":$NOW}")
        assertEquals("http://127.0.0.1:8080/", consume("/workspace/project"))
        assertFalse(file.exists())
    }

    @Test
    fun `unsupported actions and invalid URLs do not open the browser and are dropped`() {
        listOf(
            "{\"action\":\"close\",\"url\":\"https://example.com/\",\"createdAtMs\":$NOW}",
            "{\"action\":\"open\",\"url\":\"javascript:alert(1)\",\"createdAtMs\":$NOW}",
            "{\"action\":\"open\",\"url\":\"file:///root/private\",\"createdAtMs\":$NOW}",
            "{\"action\":\"open\",\"url\":\"https://\",\"createdAtMs\":$NOW}",
            "{\"action\":\"open\",\"url\":\"\",\"createdAtMs\":$NOW}",
        ).forEach { text ->
            val file = command("environment/rootfs").apply { writeText(text) }
            assertNull(consume("/workspace/project"))
            assertFalse(text, file.exists())
        }
    }

    @Test
    fun `a command without the time it was written is not opened`() {
        val file = command("environment/rootfs", createdAtMs = null)

        assertNull(consume("/workspace/project"))
        assertFalse(file.exists())
    }

    /** One the agent asked for long before the user came back, or before switching to its agent. */
    @Test
    fun `a command older than a few minutes is dropped unopened`() {
        val file = command("environment/rootfs", createdAtMs = NOW - 5 * 60_000L - 1)

        assertNull(consume("/workspace/project"))
        assertFalse(file.exists())
    }

    @Test
    fun `a command stamped in the future is not opened`() {
        val file = command("environment/rootfs", createdAtMs = NOW + 60_000L + 1)

        assertNull(consume("/workspace/project"))
        assertFalse(file.exists())
    }

    @Test
    fun `a command written a moment ago is opened`() {
        val file = command("environment/rootfs", createdAtMs = NOW - 5 * 60_000L)

        assertEquals("https://www.google.com/", consume("/workspace/project"))
        assertFalse(file.exists())
    }

    /** The guest could point it at one of the app's own files, or one too big to read every second. */
    @Test
    fun `a command that is a link, or too big to be one, is dropped unread`() {
        val appFile =
            temporaryFolder.newFile("shared_prefs.xml").apply {
                writeText("{\"action\":\"open\",\"url\":\"https://x.example/\",\"createdAtMs\":$NOW}")
            }
        val link = File(File(runtimeDirectory, "environment/rootfs"), BROWSER_COMMAND_PATH).apply { parentFile!!.mkdirs() }
        java.nio.file.Files.createSymbolicLink(link.toPath(), appFile.toPath())

        assertNull(consume("/workspace/project"))
        assertFalse(java.nio.file.Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(appFile.exists())

        val big = command("environment/rootfs").apply { appendText(" ".repeat(70_000)) }
        assertNull(consume("/workspace/project"))
        assertFalse(big.exists())
    }

    /**
     * The guest browser is only worth opening for someone looking at the app, so the watcher stays
     * parked while it is in the background and hands over what arrived the moment it returns.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `watcher opens a pending command only while the app is in the foreground`() =
        runTest {
            val foreground = MutableStateFlow(false)
            val opened = mutableListOf<String>()
            var pending: String? = "https://www.google.com/"
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            try {
                scope.launch {
                    watchBrowserCommands(
                        active = foreground,
                        intervalMillis = 1_000L,
                        poll = { pending.also { pending = null } },
                        onOpenUrl = { opened += it },
                    )
                }
                advanceTimeBy(60_000L)
                runCurrent()
                assertEquals(emptyList<String>(), opened)

                foreground.value = true
                runCurrent()

                assertEquals(listOf("https://www.google.com/"), opened)
            } finally {
                scope.cancel()
            }
        }

    private fun consume(
        path: String?,
        agent: LocalAgent? = LocalAgent.CODEX,
        now: Long = NOW,
    ): String? = consumeBrowserOpenCommand(path, agent, runtimeDirectory) { now }

    /** A command in the guest home of the rootfs at [rootfs], relative to the runtime directory. */
    private fun command(
        rootfs: String,
        url: String = "https://www.google.com/",
        createdAtMs: Long? = NOW,
    ): File = writeCommand(File(File(runtimeDirectory, rootfs), BROWSER_COMMAND_PATH).parentFile!!, url, createdAtMs)

    private fun writeCommand(
        directory: File,
        url: String = "https://www.google.com/",
        createdAtMs: Long? = NOW,
    ): File =
        File(directory, "browser-command.json").apply {
            parentFile!!.mkdirs()
            val stamp = createdAtMs?.let { ",\"createdAtMs\":$it" }.orEmpty()
            writeText("{\"action\":\"open\",\"url\":\"$url\"$stamp}")
        }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
