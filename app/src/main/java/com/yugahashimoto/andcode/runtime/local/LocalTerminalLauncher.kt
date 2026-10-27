package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Builds the PRoot invocation for the interactive terminal.
 *
 * Android gives a spawned process pipes, not a terminal, and Java cannot open a pty. The sandbox's
 * `script` can: it allocates one, runs the shell on its slave side and shuttles bytes between that
 * and our pipes. That is the same trick the Claude Code and Antigravity sign-in flows use. Because
 * the shell really has a controlling terminal, line editing, `Ctrl+C` (the line discipline turns the
 * byte into SIGINT for the foreground job), password prompts and `isatty` checks all behave.
 */
object LocalTerminalLauncher {
    const val MIN_COLUMNS = 20
    const val MAX_COLUMNS = 240
    const val MIN_ROWS = 5
    const val MAX_ROWS = 100

    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: File,
        startDirectory: String?,
        columns: Int,
        rows: Int,
    ): List<String> =
        prootCommandPrefix(runtime.commandSuite, runtime.rootfs, workspaceHostDir) +
            listOf(
                // `script` picks the directory below, so a missing one cannot stop proot from starting.
                "-w",
                "/root",
                "/usr/bin/script",
                "-qefc",
                shellScript(startDirectory, columns, rows),
                "/dev/null",
            )

    /**
     * `script` copies a window size only from its own terminal, and ours is a pipe, so the pty would
     * report 0x0. `stty` sets it explicitly before the shell starts; the size is the one the screen
     * measured, so wrapped output lines up with what is drawn.
     */
    internal fun shellScript(
        startDirectory: String?,
        columns: Int,
        rows: Int,
    ): String {
        val size = "stty rows ${rows.coerceIn(MIN_ROWS, MAX_ROWS)} cols ${columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)} 2>/dev/null"
        // Only an absolute path: anything else could be read by `cd` as an option or resolve against
        // a directory the user never chose.
        val directory =
            startDirectory
                ?.takeIf { it.startsWith("/") }
                ?.let { "cd ${AntigravitySandboxLauncher.shellQuote(it)} 2>/dev/null || cd /root" }
                ?: "cd /root"
        val shell = "if command -v bash >/dev/null 2>&1; then exec bash -l; else exec sh -l; fi"
        return "$size; $directory; $shell"
    }

    fun environment(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        prootTmp: File,
    ): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), prootTmp) +
            mapOf(
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                // musl ships only the C locales; this one is UTF-8, which is what the screen decodes.
                "LANG" to "C.UTF-8",
            )
}

/** Starts the terminal's shell in the installed runtime. */
class LocalTerminalSessionFactory(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
) : TerminalSessionFactory {
    /**
     * Holds the runtime's read lock only while the process is spawned. A shell may stay open for
     * hours, and the lock is fair: keeping it would make the next runtime update, and every command
     * queued behind that update, wait for the user to close the terminal.
     */
    override fun open(request: TerminalOpenRequest): TerminalSession =
        accessCoordinator.read {
            val runtime = installedRuntimeProvider() ?: throw TerminalUnavailableException()
            val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val process =
                ProcessBuilder(
                    LocalTerminalLauncher.command(
                        runtime = runtime,
                        workspaceHostDir = workspace,
                        startDirectory = request.startDirectory,
                        columns = request.columns,
                        rows = request.rows,
                    ),
                )
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(LocalTerminalLauncher.environment(runtime, prootTmp))
                    }
                    .start()
            ProcessTerminalSession(process)
        }
}
