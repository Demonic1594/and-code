package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTerminalLauncherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun runtime(): LocalRuntimeInstaller.InstalledRuntime {
        val root = temporaryFolder.newFolder("runtime-root")
        return LocalRuntimeInstaller.InstalledRuntime(
            metadata = LocalRuntimeMetadata(version = "test", port = 0, installedAt = 0),
            commandSuite =
                EmbeddedCommandSuite.Paths(
                    home = root,
                    tmp = root,
                    nativeLibraryDirectory = root,
                    proot = File(root, "proot"),
                    loader = root,
                    loader32 = root,
                ),
            rootfs = File(root, "rootfs"),
            openCode = null,
        )
    }

    private fun command(
        startDirectory: String? = "/workspace/app",
        columns: Int = 60,
        rows: Int = 24,
    ): List<String> =
        LocalTerminalLauncher.command(
            runtime = runtime(),
            workspaceHostDir = File("/data/runtime/workspace"),
            startDirectory = startDirectory,
            columns = columns,
            rows = rows,
        )

    @Test
    fun `runs an interactive shell under a pty inside the sandbox`() {
        val command = command()

        assertTrue(command.first().endsWith("proot"))
        assertTrue("/data/runtime/workspace:/workspace" in command)
        val scriptIndex = command.indexOf("/usr/bin/script")
        assertTrue("script must provide the pty", scriptIndex > 0)
        assertEquals("-qefc", command[scriptIndex + 1])
        assertEquals("/dev/null", command.last())
        // The guest working directory is chosen by the script, so proot itself never fails on it.
        assertEquals("/root", command[command.indexOf("-w") + 1])
    }

    @Test
    fun `sizes the pty before the shell starts`() {
        val script = LocalTerminalLauncher.shellScript(startDirectory = null, columns = 57, rows = 31)

        assertTrue(script.startsWith("stty rows 31 cols 57"))
    }

    @Test
    fun `starts in the requested directory and falls back to home`() {
        val script = LocalTerminalLauncher.shellScript(startDirectory = "/workspace/my app", columns = 80, rows = 24)

        assertTrue(script.contains("cd '/workspace/my app' 2>/dev/null || cd /root"))
    }

    @Test
    fun `starts in home when no directory is given`() {
        val script = LocalTerminalLauncher.shellScript(startDirectory = null, columns = 80, rows = 24)

        assertTrue(script.contains("cd /root"))
        assertFalse(script.contains("cd '"))
    }

    @Test
    fun `quotes the directory so it cannot inject commands`() {
        val script =
            LocalTerminalLauncher.shellScript(
                startDirectory = "/workspace/it's; rm -rf /",
                columns = 80,
                rows = 24,
            )

        assertTrue(script.contains("cd '/workspace/it'\\''s; rm -rf /' 2>/dev/null || cd /root"))
    }

    @Test
    fun `hands the pty to a login shell`() {
        val script = LocalTerminalLauncher.shellScript(startDirectory = null, columns = 80, rows = 24)

        assertTrue(script.contains("exec bash -l") || script.contains("exec sh -l"))
        assertTrue(script.contains("command -v bash"))
    }

    @Test
    fun `keeps the pty size within sane bounds`() {
        val tiny = LocalTerminalLauncher.shellScript(startDirectory = null, columns = 2, rows = 1)
        val huge = LocalTerminalLauncher.shellScript(startDirectory = null, columns = 50_000, rows = 50_000)

        assertTrue(tiny.startsWith("stty rows ${LocalTerminalLauncher.MIN_ROWS} cols ${LocalTerminalLauncher.MIN_COLUMNS}"))
        assertTrue(huge.startsWith("stty rows ${LocalTerminalLauncher.MAX_ROWS} cols ${LocalTerminalLauncher.MAX_COLUMNS}"))
    }

    @Test
    fun `environment advertises a colour terminal in the sandbox`() {
        val environment = LocalTerminalLauncher.environment(runtime(), temporaryFolder.newFolder("proot-tmp"))

        assertEquals("xterm-256color", environment["TERM"])
        assertEquals("/root", environment["HOME"])
        assertEquals("C.UTF-8", environment["LANG"])
        assertTrue(environment.containsKey("PROOT_TMP_DIR"))
    }
}
