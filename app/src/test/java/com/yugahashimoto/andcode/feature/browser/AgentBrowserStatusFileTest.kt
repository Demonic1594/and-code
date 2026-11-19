package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class AgentBrowserStatusFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val runtime by lazy { folder.newFolder("runtime") }

    @Test
    fun `the status goes beside the command, in the guest home of each rootfs there is`() {
        File(runtime, "environment/rootfs").mkdirs()
        File(runtime, "environment/antigravity-rootfs").mkdirs()

        AgentBrowserStatusFile(runtime).write("{\"mode\":\"hidden\"}")

        listOf("environment/rootfs", "environment/antigravity-rootfs").forEach { rootfs ->
            val file = File(File(runtime, rootfs), BROWSER_STATE_PATH)
            assertEquals("{\"mode\":\"hidden\"}", file.readText())
            assertEquals(File(File(runtime, rootfs), BROWSER_COMMAND_PATH).parentFile, file.parentFile)
        }
    }

    @Test
    fun `nothing is written where no rootfs is installed`() {
        AgentBrowserStatusFile(runtime).write("{}")

        assertFalse(File(runtime, "environment").exists())
    }

    @Test
    fun `a newer status replaces the older one whole`() {
        File(runtime, "environment/rootfs").mkdirs()
        val status = AgentBrowserStatusFile(runtime)

        status.write("{\"mode\":\"hidden\",\"long\":\"${"x".repeat(100)}\"}")
        status.write("{\"mode\":\"visible\"}")

        assertEquals("{\"mode\":\"visible\"}", File(File(runtime, "environment/rootfs"), BROWSER_STATE_PATH).readText())
    }

    /** The guest owns its home: it could point the folder or the file at one of this app's own files. */
    @Test
    fun `a link the guest planted is never written through`() {
        val rootfs = File(runtime, "environment/rootfs").apply { mkdirs() }
        val appFiles = folder.newFolder("shared_prefs")
        val appFile = File(appFiles, "settings.xml").apply { writeText("keep") }

        val home = File(rootfs, "root/.local/share").apply { mkdirs() }
        Files.createSymbolicLink(File(home, "and-code").toPath(), appFiles.toPath())
        AgentBrowserStatusFile(runtime).write("{}")
        assertFalse(File(appFiles, "browser-state.json").exists())

        Files.delete(File(home, "and-code").toPath())
        File(home, "and-code").mkdirs()
        val link = File(rootfs, BROWSER_STATE_PATH)
        Files.createSymbolicLink(link.toPath(), appFile.toPath())
        AgentBrowserStatusFile(runtime).write("{\"mode\":\"visible\"}")

        assertEquals("keep", appFile.readText())
        assertTrue(Files.isRegularFile(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("{\"mode\":\"visible\"}", link.readText())
    }
}
