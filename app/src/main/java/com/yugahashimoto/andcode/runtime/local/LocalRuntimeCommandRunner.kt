package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class LocalRuntimeCommandRunner(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val timeoutSeconds: Long = 15L,
    private val maxOutputCharacters: Int = 4_000,
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
    /**
     * Host directory bound at `/workspace`, as the agents see it. Without it a command could not reach
     * the default workspace, so git actions and file saves from the explorer would fail there.
     */
    private val workspaceDirectory: File? = null,
) {
    init {
        require(timeoutSeconds > 0)
        require(maxOutputCharacters > 0)
        pruneStaleLogsOnce()
    }

    /**
     * Removes temp logs orphaned by an app death mid-command - their `finally` never ran, and
     * nothing else ever cleaned the directory. Pattern-matched to this runner's own files so a
     * runtime's real logs (startup, stderr) are never touched.
     *
     * Once per process: short-lived runners (Codex resolves one per RPC) would otherwise rescan
     * the directory on every call.
     */
    private fun pruneStaleLogsOnce() {
        if (!pruneGuard.compareAndSet(false, true)) return
        val cutoff = System.currentTimeMillis() - STALE_LOG_MILLIS
        File(runtimeDirectory, "logs")
            .listFiles { file ->
                val name = file.name
                (name.startsWith("diagnostic-") || name.startsWith("clone-") || name.startsWith("codex-login-")) &&
                    name.endsWith(".log")
            }
            .orEmpty()
            .forEach { file ->
                if (file.lastModified() < cutoff) runCatching { file.delete() }
            }
    }

    fun run(definition: LocalRuntimeToolDefinition): LocalRuntimeCommandResult = runShell(definition.command)

    /**
     * Runs [commandText] in the guest. Only [maxOutputCharacters] of the output are kept: the last
     * ones by default, which is what a diagnostic needs (its error is at the end), or the first ones
     * when [keepStart] is set, which is what anything parsed needs (a listing cut from the front
     * starts mid-record and is misread). The result says when output was cut.
     */
    @Synchronized
    fun runShell(
        commandText: String,
        timeoutSeconds: Long = this.timeoutSeconds,
        maxOutputCharacters: Int = this.maxOutputCharacters,
        keepStart: Boolean = false,
    ): LocalRuntimeCommandResult {
        require(timeoutSeconds > 0L)
        require(maxOutputCharacters > 0)
        // The runtime is resolved BEFORE the read lock is taken. In production the provider is
        // the installer's installedRuntime(), which acquires the WRITE lock on the very
        // coordinator shared with this runner - and ReentrantReadWriteLock cannot upgrade a
        // held read lock to the write lock. Calling it inside `read` deadlocked the calling
        // thread on itself while it still pinned the read lock, wedging every shell command
        // and, behind it, every install/update/adb operation app-wide. The returned snapshot
        // is sufficient: the read lock below still keeps an environment swap from racing the
        // process once it has started.
        val runtime =
            installedRuntimeProvider()
                ?: return LocalRuntimeCommandResult(127, messages.notInstalled)
        return accessCoordinator.read {
            val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
            val outputFile = File.createTempFile("diagnostic-", ".log", File(runtimeDirectory, "logs").apply { mkdirs() })
            try {
                val command =
                    prootCommandPrefix(runtime.commandSuite, runtime.rootfs, workspaceDirectory?.takeIf { it.isDirectory }) +
                        listOf(
                            "-w",
                            "/root",
                            "/bin/sh",
                            "-lc",
                            commandText,
                        )
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                        .apply {
                            environment().clear()
                            environment().putAll(localRuntimeEnvironment(runtime.commandSuite.environment(), prootTmp))
                        }
                        .start()
                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                    // SIGKILL only reaches proot; its guest tracees detach and survive. See
                    // [killManagedProcessTrees].
                    runCatching { killManagedProcessTrees(runtimeDirectory, process) }
                    LocalRuntimeCommandResult(124, messages.commandTimedOut)
                } else {
                    val (output, truncated) =
                        if (keepStart) readHead(outputFile, maxOutputCharacters) else readOutputTail(outputFile, maxOutputCharacters)
                    LocalRuntimeCommandResult(exitCode = process.exitValue(), output = output, truncated = truncated)
                }
            } finally {
                outputFile.delete()
            }
        }
    }

    /**
     * Reads only the last [maxOutputCharacters] of the output file. Reading the whole file first -
     * as this used to - allocated the command's entire output (a verbose diagnostic can be tens of
     * megabytes) to keep a four-thousand-character tail.
     *
     * The byte window reads up to four bytes per kept character, so a multibyte character cut at
     * the window's edge cannot shrink the tail below what was asked for; a split codepoint at the
     * very start decodes to one replacement character, which a tail this size absorbs.
     */
    private fun readOutputTail(
        outputFile: File,
        maxOutputCharacters: Int,
    ): Pair<String, Boolean> {
        val maxBytes = (maxOutputCharacters.toLong() * 4).coerceAtLeast(16L)
        RandomAccessFile(outputFile, "r").use { file ->
            val length = file.length()
            val window = maxBytes.coerceAtMost(length)
            file.seek(length - window)
            val bytes = ByteArray(window.toInt())
            file.readFully(bytes)
            val text = bytes.decodeToString()
            return text.takeLast(maxOutputCharacters) to (window < length || text.length > maxOutputCharacters)
        }
    }

    internal companion object {
        /**
         * The first [maxCharacters] of [file], and whether there was more.
         */
        internal fun readHead(
            file: File,
            maxCharacters: Int,
        ): Pair<String, Boolean> {
            // One character past the limit tells whether there was more, without reading the rest.
            file.reader(Charsets.UTF_8).use { reader ->
                // Sized by the file too: UTF-8 never has more characters than bytes, and most output is
                // far smaller than the limit.
                val buffer = CharArray((minOf(maxCharacters.toLong(), file.length()) + 1).toInt())
                var read = 0
                while (read < buffer.size) {
                    val n = reader.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                return String(buffer, 0, minOf(read, maxCharacters)) to (read > maxCharacters)
            }
        }

        /** Temp logs a killed app left behind are worthless after a day. */
        private const val STALE_LOG_MILLIS = 24L * 60L * 60L * 1000L

        /** See [pruneStaleLogsOnce]; shared across every runner in this process. */
        private val pruneGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
