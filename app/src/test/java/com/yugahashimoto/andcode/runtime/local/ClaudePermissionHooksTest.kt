package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ClaudePermissionHooksTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rootfs() = File(tmp.root, "environment/rootfs").apply { mkdirs() }

    /** An agent can point Claude Code's settings at a JSON file of the app's own; it must stay as it was. */
    @Test
    fun `a linked settings file is not merged into`() {
        val rootfs = rootfs()
        val appFile = File(tmp.newFolder("files"), "claude-resume-ids.json").apply { writeText("{\"chat\":\"session\"}") }
        val settings = File(rootfs, "root/.claude/settings.json")
        settings.parentFile.mkdirs()
        Files.createSymbolicLink(settings.toPath(), appFile.toPath())

        assertFalse(ClaudePermissionHooks.installInto(rootfs, "#!/bin/sh\n"))

        assertEquals("{\"chat\":\"session\"}", appFile.readText())
        assertTrue(Files.isSymbolicLink(settings.toPath()))
        assertFalse(ClaudePermissionHooks.isInstalled(rootfs))
    }

    @Test
    fun `a linked hook script is replaced, not written through`() {
        val rootfs = rootfs()
        val appFile = File(tmp.newFolder("shared_prefs"), "settings.xml").apply { writeText("<map/>") }
        val script = File(rootfs, "usr/local/bin/and-code-claude-permission-hook.sh")
        script.parentFile.mkdirs()
        Files.createSymbolicLink(script.toPath(), appFile.toPath())

        assertTrue(ClaudePermissionHooks.installInto(rootfs, "#!/bin/sh\n"))

        assertEquals("<map/>", appFile.readText())
        assertFalse(Files.isSymbolicLink(script.toPath()))
        assertTrue(script.canExecute())
        assertTrue(ClaudePermissionHooks.isInstalled(rootfs))
    }

    @Test
    fun `refreshing a linked script reads nothing through it`() {
        val rootfs = rootfs()
        val appFile = File(tmp.newFolder("shared_prefs"), "settings.xml").apply { writeText("<map/>") }
        val script = File(rootfs, "usr/local/bin/and-code-claude-permission-hook.sh")
        script.parentFile.mkdirs()
        Files.createSymbolicLink(script.toPath(), appFile.toPath())

        ClaudePermissionHooks.refreshScript(rootfs, "#!/bin/sh\necho new\n")

        assertEquals("<map/>", appFile.readText())
        assertFalse(ClaudePermissionHooks.supportsChecks(rootfs))
    }
}
