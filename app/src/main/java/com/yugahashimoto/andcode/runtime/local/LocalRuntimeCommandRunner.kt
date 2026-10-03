package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
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
) {
    init {
        require(timeoutSeconds > 0)
        require(maxOutputCharacters > 0)
    }

    fun run(definition: LocalRuntimeToolDefinition): LocalRuntimeCommandResult = runShell(definition.command)

    @Synchronized
    fun runShell(
        commandText: String,
        timeoutSeconds: Long = this.timeoutSeconds,
    ): LocalRuntimeCommandResult =
        accessCoordinator.read {
            require(timeoutSeconds > 0L)
            val runtime =
                installedRuntimeProvider()
                    ?: return@read LocalRuntimeCommandResult(127, messages.notInstalled)
            val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
            val outputFile = File.createTempFile("diagnostic-", ".log", File(runtimeDirectory, "logs").apply { mkdirs() })
            try {
                val command =
                    buildList {
                        add(runtime.commandSuite.proot.absolutePath)
                        add("--kill-on-exit")
                        add("--link2symlink")
                        add("-0")
                        add("-r")
                        add(runtime.rootfs.absolutePath)
                        add("-b")
                        add("/dev")
                        add("-b")
                        add("/proc")
                        add("-b")
                        add("/sys")
                        add("-b")
                        add("/system")
                        // So a shell command can reach the device's files once the user allows it.
                        addAll(DeviceStorage.bindArguments())
                        add("-w")
                        add("/root")
                        add("/bin/sh")
                        add("-lc")
                        add(commandText)
                    }
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
                    LocalRuntimeCommandResult(124, messages.commandTimedOut)
                } else {
                    LocalRuntimeCommandResult(
                        exitCode = process.exitValue(),
                        output = readOutputTail(outputFile),
                    )
                }
            } finally {
                outputFile.delete()
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
    private fun readOutputTail(outputFile: File): String {
        val maxBytes = (maxOutputCharacters.toLong() * 4).coerceAtLeast(16L)
        RandomAccessFile(outputFile, "r").use { file ->
            val length = file.length()
            val window = maxBytes.coerceAtMost(length)
            file.seek(length - window)
            val bytes = ByteArray(window.toInt())
            file.readFully(bytes)
            return bytes.decodeToString().takeLast(maxOutputCharacters)
        }
    }
}
