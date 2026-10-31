package com.yugahashimoto.andcode.runtime.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Starts supervised processes in the guest the way [LocalRuntimeCommandRunner] runs a command, but
 * without waiting: output goes to the log file, and the process lives until it exits or is killed.
 *
 * It takes no runtime access lock. A one-shot command holds the read lock so an update cannot swap
 * the rootfs under it, but a server runs for hours and would block every update; the supervisor is
 * stopped with the runtime instead.
 */
class ProotProcessLauncher(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val workspaceDirectory: File,
) : GuestProcessLauncher {
    override fun start(
        spec: SupervisedProcessSpec,
        log: File,
    ): GuestProcessHandle {
        val runtime = installedRuntimeProvider() ?: error("the local runtime is not installed")
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        log.parentFile?.mkdirs()
        val command =
            prootCommandPrefix(runtime.commandSuite, runtime.rootfs, workspaceDirectory.takeIf { it.isDirectory }) +
                listOf("-w", spec.directory, "/bin/sh", "-lc", spec.command)
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .apply {
                    environment().clear()
                    environment().putAll(localRuntimeEnvironment(runtime.commandSuite.environment(), prootTmp))
                    spec.port?.let { environment()["PORT"] = it.toString() }
                }
                .start()
        return ProotProcessHandle(process)
    }
}

private class ProotProcessHandle(
    private val process: Process,
) : GuestProcessHandle {
    override fun waitFor(): Int = process.waitFor()

    /**
     * Asks politely first, then kills what is left. The tree is read before proot goes: once it dies
     * its guest processes detach (see [killManagedProcessTrees]) and are no longer its children.
     */
    override fun kill() {
        val tree = processId(process)?.let { root -> processTreePostOrder(root) { pid -> readDirectChildPids(pid) } }.orEmpty()
        process.destroy()
        if (!process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        tree.forEach { pid -> runCatching { android.os.Process.killProcess(pid.toInt()) } }
    }

    private companion object {
        const val GRACE_SECONDS = 3L
    }
}

/** [SupervisorStore] in a JSON file in the app's private storage. */
class JsonSupervisorStore(
    private val file: File,
) : SupervisorStore {
    override fun load(): List<SupervisedProcessSpec> {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::specFrom) }
    }

    override fun save(specs: List<SupervisedProcessSpec>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        specs.forEach { array.put(toJson(it)) }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    }

    internal companion object {
        fun toJson(spec: SupervisedProcessSpec): JSONObject =
            JSONObject()
                .put("name", spec.name)
                .put("command", spec.command)
                .put("directory", spec.directory)
                .put("restart", spec.restart.name)
                .apply {
                    spec.port?.let { put("port", it) }
                    spec.maxRunMinutes?.let { put("maxRunMinutes", it) }
                }

        fun specFrom(json: JSONObject): SupervisedProcessSpec? {
            val name = json.optString("name")
            val command = json.optString("command")
            if (!SupervisedProcessSpec.isValidName(name) || command.isBlank()) return null
            return SupervisedProcessSpec(
                name = name,
                command = command,
                directory = json.optString("directory").ifBlank { "/workspace" },
                port = json.optInt("port", 0).takeIf { it in 1..65_535 },
                restart = RestartPolicy.entries.firstOrNull { it.name == json.optString("restart") } ?: RestartPolicy.ON_CRASH,
                maxRunMinutes = if (json.has("maxRunMinutes")) json.optInt("maxRunMinutes").takeIf { it > 0 } else null,
            )
        }
    }
}
