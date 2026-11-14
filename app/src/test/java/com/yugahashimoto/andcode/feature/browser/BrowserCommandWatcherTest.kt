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

    /** Where commands used to go, and where a cloned or pulled repository can put one. */
    @Test
    fun `a command inside the workspace is never opened`() {
        val inRootfs = writeCommand(File(runtimeDirectory, "environment/rootfs/root/project/.and-code"), "https://phishing.example/")
        val inWorkspace = writeCommand(File(runtimeDirectory, "workspace/project/.and-code"), "https://phishing.example/")

        assertNull(consume("/root/project"))
        assertNull(consume("/workspace/project"))
        assertTrue(inRootfs.exists())
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

        file.writeText("{\"action\":\"open\",\"url\":\"http://127.0.0.1:8080/\"}")
        assertEquals("http://127.0.0.1:8080/", consume("/workspace/project"))
        assertFalse(file.exists())
    }

    @Test
    fun `unsupported actions and invalid URLs do not open the browser`() {
        val file = command("environment/rootfs")
        listOf(
            "{\"action\":\"close\",\"url\":\"https://example.com/\"}",
            "{\"action\":\"open\",\"url\":\"javascript:alert(1)\"}",
            "{\"action\":\"open\",\"url\":\"file:///root/private\"}",
            "{\"action\":\"open\",\"url\":\"https://\"}",
            "{\"action\":\"open\",\"url\":\"\"}",
        ).forEach { text ->
            file.writeText(text)
            assertNull(consume("/workspace/project"))
            assertTrue(file.exists())
        }
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
    ): String? = consumeBrowserOpenCommand(path, agent, runtimeDirectory)

    /** A command in the guest home of the rootfs at [rootfs], relative to the runtime directory. */
    private fun command(
        rootfs: String,
        url: String = "https://www.google.com/",
    ): File = writeCommand(File(runtimeDirectory, rootfs).resolve(BROWSER_COMMAND_PATH).parentFile!!, url)

    private fun writeCommand(
        directory: File,
        url: String = "https://www.google.com/",
    ): File =
        File(directory, "browser-command.json").apply {
            parentFile!!.mkdirs()
            writeText("{\"action\":\"open\",\"url\":\"$url\"}")
        }
}
