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

            assertEquals("https://www.google.com/", consume(agent))
            assertFalse(file.exists())
            assertNull(consumeCommand(agent))
        }
    }

    @Test
    fun `the command is consumed whichever local folder is open`() {
        val file = command("environment/rootfs")

        assertEquals("https://www.google.com/", consume())
        assertFalse(file.exists())
    }

    @Test
    fun `Antigravity consumes only the command in its Debian rootfs`() {
        val file = command("environment/antigravity-rootfs")
        val alpine = command("environment/rootfs", "https://example.com/alpine")

        assertEquals("https://www.google.com/", consume(LocalAgent.ANTIGRAVITY))
        assertFalse(file.exists())
        assertTrue(alpine.exists())
    }

    @Test
    fun `Antigravity falls back to the shared rootfs when Debian is absent`() {
        val file = command("environment/rootfs")

        assertEquals("https://www.google.com/", consume(LocalAgent.ANTIGRAVITY))
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

        assertNull(consumeCommand())
        assertTrue("the user's checkout is left as it is", inRootfs.exists())
        assertTrue(inWorkspace.exists())
    }

    @Test
    fun `a remote runtime leaves local commands untouched`() {
        val file = command("environment/rootfs")

        assertNull(consumeCommand(agent = null))
        assertTrue(file.exists())
    }

    @Test
    fun `missing command does not open the browser`() {
        assertNull(consumeCommand())
    }

    @Test
    fun `partially written command is retried after the writer completes it`() {
        val file = command("environment/rootfs")
        file.writeText("{\"action\":\"open\",\"url\":")

        assertNull(consumeCommand())
        assertTrue(file.exists())

        file.writeText("{\"action\":\"open\",\"url\":\"http://127.0.0.1:8080/\",\"createdAtMs\":$NOW}")
        assertEquals("http://127.0.0.1:8080/", consume())
        assertFalse(file.exists())
    }

    @Test
    fun `unsupported actions and invalid URLs do not open the browser and are dropped`() {
        listOf(
            "{\"action\":\"reload\",\"url\":\"https://example.com/\",\"createdAtMs\":$NOW}",
            "{\"action\":\"open\",\"url\":\"javascript:alert(1)\",\"createdAtMs\":$NOW}",
            "{\"action\":\"open\",\"url\":\"file:///root/private\",\"createdAtMs\":$NOW}",
            "{\"action\":\"preview\",\"url\":\"file:///root/private\",\"createdAtMs\":$NOW}",
            "{\"action\":\"open\",\"url\":\"https://\",\"createdAtMs\":$NOW}",
            "{\"action\":\"open\",\"url\":\"\",\"createdAtMs\":$NOW}",
        ).forEach { text ->
            val file = command("environment/rootfs").apply { writeText(text) }
            assertNull(consumeCommand())
            assertFalse(text, file.exists())
        }
    }

    @Test
    fun `a command without the time it was written is not opened`() {
        val file = command("environment/rootfs", createdAtMs = null)

        assertNull(consumeCommand())
        assertFalse(file.exists())
    }

    /** One the agent asked for long before the user came back, or before switching to its agent. */
    @Test
    fun `a command older than a few minutes is dropped unopened`() {
        val file = command("environment/rootfs", createdAtMs = NOW - 5 * 60_000L - 1)

        assertNull(consumeCommand())
        assertFalse(file.exists())
    }

    @Test
    fun `a command stamped in the future is not opened`() {
        val file = command("environment/rootfs", createdAtMs = NOW + 60_000L + 1)

        assertNull(consumeCommand())
        assertFalse(file.exists())
    }

    @Test
    fun `a command written a moment ago is opened`() {
        val file = command("environment/rootfs", createdAtMs = NOW - 5 * 60_000L)

        assertEquals("https://www.google.com/", consume())
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

        assertNull(consumeCommand())
        assertFalse(java.nio.file.Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(appFile.exists())

        val big = command("environment/rootfs").apply { appendText(" ".repeat(70_000)) }
        assertNull(consumeCommand())
        assertFalse(big.exists())
    }

    @Test
    fun `preview commands pin a loopback page inside the chat`() {
        val file = command("environment/rootfs").apply { writeText(json("preview", "http://127.0.0.1:5173/")) }

        assertEquals(BrowserCommand.Preview("http://127.0.0.1:5173/"), consumeCommand())
        assertFalse(file.exists())
    }

    /** The preview is a live WebView in the conversation: a public page goes to the Guest Browser instead. */
    @Test
    fun `preview commands for pages not on this device are dropped`() {
        listOf("https://example.com/", "http://example.com/", "http://10.0.0.2:8080/").forEach { url ->
            val file = command("environment/rootfs").apply { writeText(json("preview", url)) }

            assertNull(consumeCommand())
            assertFalse(url, file.exists())
        }
    }

    @Test
    fun `handoff waits on the current page and records that it is waiting, in the guest home`() {
        val file = command("environment/rootfs").apply { writeText(json("handoff", "")) }

        val command = consumeCommand()

        assertEquals(BrowserCommand.Handoff(File(runtimeDirectory, "environment/rootfs")), command)
        assertFalse(file.exists())
        val status = File(File(runtimeDirectory, "environment/rootfs"), BROWSER_STATUS_PATH)
        assertTrue(status.readText().contains(BROWSER_STATUS_AWAITING_USER))
    }

    /** Agents' servers share the status file: each reads only the answer to its own request. */
    @Test
    fun `handoff names its request in the status it records`() {
        command("environment/rootfs").writeText("{\"action\":\"handoff\",\"url\":\"\",\"requestId\":\"r1\",\"createdAtMs\":$NOW}")
        val rootfs = File(runtimeDirectory, "environment/rootfs")

        assertEquals(BrowserCommand.Handoff(rootfs, "r1"), consumeCommand())

        val status = File(rootfs, BROWSER_STATUS_PATH).readText()
        assertTrue(status, status.contains("\"state\":\"$BROWSER_STATUS_AWAITING_USER\""))
        assertTrue(status, status.contains("\"requestId\":\"r1\""))
        command("environment/rootfs").writeText("{\"action\":\"handoff\",\"hidden\":\"r0\",\"requestId\":\"r2\",\"createdAtMs\":$NOW}")
        assertEquals(BrowserCommand.Handoff(rootfs, "r2", ownHidden = "r0"), consumeCommand())
    }

    /** Taking the banner down without the answer would leave the agent waiting for a tap the user cannot give. */
    @Test
    fun `the handoff banner stays until the agent can read the answer`() {
        val rootfs = File(runtimeDirectory, "environment/rootfs")
        // A file where the status folder goes: the status cannot be written.
        val blocked = File(rootfs, BROWSER_STATUS_PATH).parentFile!!.apply { parentFile!!.mkdirs() }
        blocked.writeText("not a folder")
        BrowserHandoff.begin(rootfs, "r2")
        try {
            assertFalse(writeBrowserStatus(rootfs, BROWSER_STATUS_AGENT))
            BrowserHandoff.handBack()
            assertEquals("r2", BrowserHandoff.active?.requestId)

            blocked.delete()
            BrowserHandoff.handBack()
            assertNull(BrowserHandoff.active)
            assertTrue(File(rootfs, BROWSER_STATUS_PATH).readText().contains("\"requestId\":\"r2\""))
        } finally {
            BrowserHandoff.dismiss()
        }
    }

    @Test
    fun `handoff never carries a url because it acts on the page already open`() {
        listOf("https://example.com/login", "javascript:alert(1)").forEach { url ->
            val file = command("environment/rootfs").apply { writeText(json("handoff", url)) }

            assertNull(consumeCommand())
            assertFalse(file.exists())
        }
    }

    /** The guest owns its home: a link it planted there is replaced, never written through. */
    @Test
    fun `the handoff status replaces a link rather than writing through it`() {
        val appFile = temporaryFolder.newFile("shared_prefs.xml").apply { writeText("keep") }
        val rootfs = File(runtimeDirectory, "environment/rootfs")
        val status = File(rootfs, BROWSER_STATUS_PATH).apply { parentFile!!.mkdirs() }
        java.nio.file.Files.createSymbolicLink(status.toPath(), appFile.toPath())

        writeBrowserStatus(rootfs, BROWSER_STATUS_AGENT)

        assertEquals("keep", appFile.readText())
        assertTrue(status.readText().contains(BROWSER_STATUS_AGENT))
    }

    /**
     * The commands are only worth reading while the guest can be writing one: the app is on screen or
     * an agent is working. The loop stays parked otherwise and hands over what arrived the moment it
     * wakes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `watcher reads commands only while it is active`() =
        runTest {
            val active = MutableStateFlow(false)
            val handled = mutableListOf<BrowserCommand>()
            var pending: BrowserCommand? = BrowserCommand.Close("r1")
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            try {
                scope.launch {
                    watchBrowserCommands(
                        active = active,
                        intervalMillis = 1_000L,
                        poll = { pending.also { pending = null } },
                        onCommand = { handled += it },
                    )
                }
                advanceTimeBy(60_000L)
                runCurrent()
                assertEquals(emptyList<BrowserCommand>(), handled)

                active.value = true
                runCurrent()

                assertEquals(listOf<BrowserCommand>(BrowserCommand.Close("r1")), handled)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a hidden open names its mode, address and request`() {
        command("environment/rootfs").writeText(
            "{\"action\":\"open\",\"mode\":\"hidden\",\"url\":\"https://example.com/a\",\"requestId\":\"r7\",\"createdAtMs\":$NOW}",
        )

        assertEquals(BrowserCommand.Open("https://example.com/a", BrowserMode.HIDDEN, "r7"), consumeCommand())
    }

    /** A script from before modes wrote no mode and no request: it asked for the browser on screen. */
    @Test
    fun `an open without a mode is a visible one`() {
        command("environment/rootfs")

        assertEquals(BrowserCommand.Open("https://www.google.com/", BrowserMode.VISIBLE, ""), consumeCommand())
    }

    @Test
    fun `a visible open without an address hands the hidden page over, and a hidden one needs an address`() {
        val file = command("environment/rootfs")
        file.writeText("{\"action\":\"open\",\"mode\":\"visible\",\"requestId\":\"r1\",\"createdAtMs\":$NOW}")
        assertEquals(BrowserCommand.Open(null, BrowserMode.VISIBLE, "r1"), consumeCommand())
        // It names the asking agent's own hidden page: the app hands over only that one.
        file.writeText("{\"action\":\"open\",\"mode\":\"visible\",\"hidden\":\"r0\",\"requestId\":\"r1\",\"createdAtMs\":$NOW}")
        assertEquals(BrowserCommand.Open(null, BrowserMode.VISIBLE, "r1", ownHidden = "r0"), consumeCommand())

        file.writeText("{\"action\":\"open\",\"mode\":\"hidden\",\"requestId\":\"r2\",\"createdAtMs\":$NOW}")
        assertNull(consumeCommand())
        assertFalse("dropped, not retried", file.exists())
    }

    @Test
    fun `an unknown mode is dropped rather than guessed`() {
        val file = command("environment/rootfs")
        file.writeText("{\"action\":\"open\",\"mode\":\"popup\",\"url\":\"https://example.com/\",\"createdAtMs\":$NOW}")

        assertNull(consumeCommand())
        assertFalse(file.exists())
    }

    @Test
    fun `close is a command of its own and needs no address`() {
        command("environment/rootfs").writeText("{\"action\":\"close\",\"requestId\":\"r3\",\"createdAtMs\":$NOW}")

        assertEquals(BrowserCommand.Close("r3"), consumeCommand())
    }

    /** A visible open read while the app is in the background waits in its file for the user to come back. */
    @Test
    fun `a command the app cannot act on yet stays for a later read`() {
        val file = command("environment/rootfs")

        assertNull(consumeCommand(accept = { false }))
        assertTrue(file.exists())
        assertEquals(BrowserCommand.Open("https://www.google.com/", BrowserMode.VISIBLE, ""), consumeCommand())
        assertFalse(file.exists())
    }

    /** The address of the open command consumed, as the Guest Browser would get it. */
    private fun consume(
        agent: LocalAgent? = LocalAgent.CODEX,
        now: Long = NOW,
    ): String? = (consumeCommand(agent, now) as? BrowserCommand.Open)?.url

    private fun consumeCommand(
        agent: LocalAgent? = LocalAgent.CODEX,
        now: Long = NOW,
        accept: (BrowserCommand) -> Boolean = { true },
    ): BrowserCommand? = consumeBrowserCommand(agent, runtimeDirectory, clock = { now }, accept = accept)

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

    private fun json(
        action: String,
        url: String,
    ): String = "{\"action\":\"$action\",\"url\":\"$url\",\"createdAtMs\":$NOW}"

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
