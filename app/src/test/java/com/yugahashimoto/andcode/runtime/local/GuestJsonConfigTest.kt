package com.yugahashimoto.andcode.runtime.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** The MCP registrations the installer merges into each agent's config (see [mergeGuestJsonConfig]). */
class GuestJsonConfigTest {
    @get:Rule val tmp = TemporaryFolder()

    private val addServer: (JSONObject) -> Unit = { root ->
        val servers = root.optJSONObject("mcpServers") ?: JSONObject()
        servers.put("and-code-browser", JSONObject().put("command", "/usr/local/bin/andcode-browser-mcp.py"))
        root.put("mcpServers", servers)
    }

    @Test
    fun `the user's own entries are kept`() {
        val rootfs = tmp.newFolder("rootfs")
        val config =
            File(rootfs, "root/.claude.json").apply {
                parentFile.mkdirs()
                writeText("""{"theme":"dark","mcpServers":{"mine":{"command":"x"}}}""")
            }

        assertTrue(mergeGuestJsonConfig(rootfs, "root/.claude.json", addServer))

        val merged = JSONObject(config.readText())
        assertEquals("dark", merged.getString("theme"))
        assertTrue(merged.getJSONObject("mcpServers").has("mine"))
        assertTrue(merged.getJSONObject("mcpServers").has("and-code-browser"))
    }

    /** `shared_prefs` holds XML, which used to read as malformed and be overwritten with `{"mcpServers":…}`. */
    @Test
    fun `a config the guest linked to an app file is skipped, not overwritten`() {
        val rootfs = tmp.newFolder("rootfs")
        val appFile = File(tmp.newFolder("shared_prefs"), "settings.xml").apply { writeText("<map/>") }
        val config = File(rootfs, "root/.claude.json")
        config.parentFile.mkdirs()
        Files.createSymbolicLink(config.toPath(), appFile.toPath())

        assertFalse(mergeGuestJsonConfig(rootfs, "root/.claude.json", addServer))

        assertEquals("<map/>", appFile.readText())
        assertTrue(Files.isSymbolicLink(config.toPath()))
    }

    @Test
    fun `a config under a linked folder is skipped`() {
        val rootfs = tmp.newFolder("rootfs")
        val appFolder = tmp.newFolder("files")
        File(rootfs, "root").mkdirs()
        Files.createSymbolicLink(File(rootfs, "root/.config").toPath(), appFolder.toPath())

        assertFalse(mergeGuestJsonConfig(rootfs, "root/.config/opencode/opencode.json", addServer))

        assertEquals(emptyList<String>(), appFolder.list()!!.toList())
    }
}
