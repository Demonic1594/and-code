package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AntigravitySandboxLauncherTest {
    @Test
    fun `pty command sizes the terminal before starting agy`() {
        val command = AntigravitySandboxLauncher.ptyShellCommand(emptyList())
        assertTrue(command.startsWith("stty rows ${AntigravitySandboxLauncher.PTY_ROWS} cols ${AntigravitySandboxLauncher.PTY_COLUMNS}"))
        assertTrue(command.endsWith("exec '/usr/local/bin/agy'"))
    }

    @Test
    fun `pty width keeps the official oauth url on a single line`() {
        // The captured agy 1.1.7 sign-in URL is 521 characters and the CLI indents it by one column.
        assertTrue(AntigravitySandboxLauncher.PTY_COLUMNS > 600)
    }

    @Test
    fun `pty arguments are shell quoted`() {
        val command = AntigravitySandboxLauncher.ptyShellCommand(listOf("--print", "it's a test; rm -rf /"))
        assertTrue(command.endsWith("'/usr/local/bin/agy' '--print' 'it'\\''s a test; rm -rf /'"))
    }

    @Test
    fun `a chat's agy starts in its project folder`() {
        val command =
            AntigravitySandboxLauncher.command(
                mockRuntime(File("/rootfs")),
                "/host/workspace",
                listOf("--print", "hi"),
                pty = false,
                workingDirectory = "/workspace/my-app",
            )

        assertEquals("/workspace/my-app", command.workingDirectory())
        // The mount stays the whole workspace, which is what puts the project folder in the guest.
        assertTrue("/host/workspace:/workspace" in command)
        assertEquals(listOf("/usr/local/bin/agy", "--print", "hi"), command.takeLast(3))
    }

    @Test
    fun `agy runs that belong to no chat start in the shared workspace`() {
        val command = AntigravitySandboxLauncher.command(mockRuntime(File("/rootfs")), "/host/workspace", listOf("models"), pty = false)

        assertEquals("/workspace", command.workingDirectory())
    }

    @Test
    fun `a chat without a folder starts in the shared workspace`() {
        val command =
            AntigravitySandboxLauncher.command(
                mockRuntime(File("/rootfs")),
                "/host/workspace",
                emptyList(),
                pty = true,
                workingDirectory = " ",
            )

        assertEquals("/workspace", command.workingDirectory())
    }

    private fun List<String>.workingDirectory(): String = this[indexOf("-w") + 1]

    @Test
    fun `a chat starts in its folder when the workspace or device storage has it`() {
        val existing = Files.createTempDirectory("antigravity-project").toFile()
        try {
            listOf("/workspace/my-app", "/sdcard/Projects/app", "/storage/ABCD-1234/app").forEach { folder ->
                assertEquals(folder, AntigravitySandboxLauncher.workingDirectoryFor(folder) { existing })
            }
        } finally {
            existing.deleteRecursively()
        }
    }

    /** PRoot would start agy in `/`, the whole guest, for a folder its guest does not have. */
    @Test
    fun `a chat whose folder is gone starts in the shared workspace`() {
        val missing = File(Files.createTempDirectory("antigravity-project").toFile().apply { delete() }, "gone")

        assertEquals("/workspace", AntigravitySandboxLauncher.workingDirectoryFor("/workspace/deleted") { missing })
        assertEquals("/workspace", AntigravitySandboxLauncher.workingDirectoryFor("/sdcard/app") { null })
    }

    /**
     * agy runs in a guest of its own, which shares only `/workspace` and device storage with the guest
     * the folder picker browses, so `/root/project` there is not agy's `/root/project`.
     */
    @Test
    fun `a chat whose folder is elsewhere in a guest starts in the shared workspace`() {
        val existing = Files.createTempDirectory("antigravity-project").toFile()
        try {
            listOf("/root/project", "/workspace-old/app", "/", " ").forEach { folder ->
                assertEquals(folder, "/workspace", AntigravitySandboxLauncher.workingDirectoryFor(folder) { existing })
            }
        } finally {
            existing.deleteRecursively()
        }
    }

    @Test
    fun `only the workspace and device storage are shared with agy's guest`() {
        listOf("/workspace", "/workspace/", "/workspace/app", "/sdcard", "/sdcard/Download", "/storage/ABCD-1234").forEach {
            assertTrue(it, AntigravitySandboxLauncher.isSharedGuestPath(it))
        }
        listOf("/", "/root/project", "/workspace-old", "/home/sdcard", "", "  ").forEach {
            assertFalse(it, AntigravitySandboxLauncher.isSharedGuestPath(it))
        }
    }

    @Test
    fun `guest settings are valid json with the alt screen disabled`() {
        val parsed = Json.parseToJsonElement(AntigravityGuestSettings.content).jsonObject
        assertEquals("never", parsed["altScreenMode"]?.jsonPrimitive?.content)
        assertEquals("always-proceed", parsed["toolPermission"]?.jsonPrimitive?.content)
    }

    @Test
    fun `repair heals a legacy request-review settings file`() {
        val rootfs = Files.createTempDirectory("antigravity-guest-settings").toFile()
        try {
            val settings = File(rootfs, "root/.gemini/antigravity-cli/settings.json")
            settings.parentFile?.mkdirs()
            settings.writeText("""{"toolPermission":"request-review"}""")

            val runtime = mockRuntime(rootfs)
            AntigravityGuestSettings.repair(runtime)

            val healed = Json.parseToJsonElement(settings.readText()).jsonObject
            assertEquals("always-proceed", healed["toolPermission"]?.jsonPrimitive?.content)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `repair leaves an up to date settings file untouched`() {
        val rootfs = Files.createTempDirectory("antigravity-guest-settings").toFile()
        try {
            val settings = File(rootfs, "root/.gemini/antigravity-cli/settings.json")
            settings.parentFile?.mkdirs()
            settings.writeText("""{"toolPermission":"always-proceed","custom":"keep"}""")

            AntigravityGuestSettings.repair(mockRuntime(rootfs))

            val healed = Json.parseToJsonElement(settings.readText()).jsonObject
            assertEquals("always-proceed", healed["toolPermission"]?.jsonPrimitive?.content)
            assertEquals("keep", healed["custom"]?.jsonPrimitive?.content)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    private fun mockRuntime(rootfs: File): LocalRuntimeInstaller.InstalledRuntime =
        LocalRuntimeInstaller.InstalledRuntime(
            metadata = LocalRuntimeMetadata(version = "test", port = 0, installedAt = 0),
            commandSuite =
                EmbeddedCommandSuite.Paths(
                    home = rootfs,
                    tmp = rootfs,
                    nativeLibraryDirectory = rootfs,
                    proot = rootfs,
                    loader = rootfs,
                    loader32 = rootfs,
                ),
            rootfs = rootfs,
            openCode = null,
            antigravityRootfs = rootfs,
        )
}
