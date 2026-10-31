package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.util.pollWhileActive
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Request/response file bridge between the guest `andcode-run` command and [ProcessSupervisor], the
 * same mechanism as the schedule bridge: the command writes `pending/<id>.json` under
 * `/workspace/.and-code/run-bridge`, the app handles it and writes `responses/<id>.json`.
 *
 * Replies are written for an agent to read. A refusal says what to do next (a port taken names the
 * process holding it, the limit names how to stop one), because the reply is where an agent that
 * skimmed its instructions learns how this works.
 */
class RunBridge(
    workspaceDir: File,
    private val supervisor: ProcessSupervisor,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val bridgeDir: File = File(workspaceDir, BRIDGE_RELATIVE_PATH)

    /** Polls while [active] says the guest could be asking; see the schedule bridge for why it is required. */
    suspend fun run(active: Flow<Boolean>) {
        active.pollWhileActive(pollIntervalMillis) { runCatching { pollOnce() } }
    }

    fun pollOnce() {
        if (!bridgeDir.isDirectory) return
        val pendingDir = File(bridgeDir, "pending")
        val responsesDir = File(bridgeDir, "responses")
        val now = clock()
        pendingDir.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.forEach { file ->
            val requestId = file.nameWithoutExtension
            val request = runCatching { JSONObject(file.readText()) }.getOrNull()
            val createdAt = request?.optLong("createdAtMs", 0L) ?: 0L
            val response =
                when {
                    request == null -> error("malformed request")
                    createdAt > 0L && now - createdAt > REQUEST_EXPIRES_AFTER_MILLIS ->
                        error(
                            "request expired before the app could handle it",
                        )
                    else ->
                        try {
                            handle(request.optString("op"), request.optJSONObject("args") ?: JSONObject())
                        } catch (failure: IllegalArgumentException) {
                            error(failure.message.orEmpty())
                        }
                }
            // The request goes before the reply, so a crash in between never runs a start twice.
            file.delete()
            write(responsesDir, requestId, response)
        }
        prune(pendingDir, now)
        prune(responsesDir, now)
    }

    internal fun handle(
        op: String,
        args: JSONObject,
    ): JSONObject =
        when (op) {
            "start" -> start(args)
            "list" -> ok(JSONObject().put("processes", JSONArray().apply { supervisor.processes.value.forEach { put(json(it)) } }))
            "stop" -> {
                val name = required(args, "name")
                if (supervisor.stop(name)) ok(JSONObject().put("stopped", name)) else error(notRunning(name))
            }
            "restart" -> {
                val name = required(args, "name")
                if (supervisor.processes.value.none { it.spec.name == name }) {
                    error(
                        notRunning(name),
                    )
                } else {
                    startReply(supervisor.restart(name))
                }
            }
            "logs" -> {
                val name = required(args, "name")
                val characters = args.optInt("characters", DEFAULT_LOG_CHARACTERS).coerceIn(1, MAX_LOG_CHARACTERS)
                supervisor.logTail(name, characters)?.let { ok(JSONObject().put("name", name).put("log", it)) }
                    ?: error("No log for \"$name\": it has not been started. Run `andcode-run list` to see the processes.")
            }
            else -> error("unknown operation: $op")
        }

    private fun start(args: JSONObject): JSONObject {
        val spec =
            SupervisedProcessSpec(
                name = required(args, "name"),
                command = required(args, "command"),
                directory = args.optString("directory").ifBlank { "/workspace" },
                port = args.optInt("port", 0).takeIf { it in 1..MAX_PORT },
                restart =
                    RestartPolicy.entries.firstOrNull {
                        it.name.equals(
                            args.optString("restart"),
                            ignoreCase = true,
                        )
                    } ?: RestartPolicy.ON_CRASH,
                maxRunMinutes =
                    when {
                        !args.has("maxMinutes") -> SupervisedProcessSpec.DEFAULT_MAX_RUN_MINUTES
                        args.optInt("maxMinutes") <= 0 -> null
                        else -> args.optInt("maxMinutes")
                    },
            )
        return startReply(supervisor.start(spec))
    }

    private fun startReply(result: StartResult): JSONObject =
        when (result) {
            is StartResult.Started -> ok(json(result.process))
            is StartResult.AlreadyRunning ->
                error(
                    "\"${result.process.spec.name}\" is already running. Use `andcode-run restart ${result.process.spec.name}` " +
                        "to restart it, or start it under another name.",
                )
            is StartResult.LimitReached ->
                error(
                    "${result.limit} processes are already running, the most the app allows. Stop one first " +
                        "(`andcode-run list`, then `andcode-run stop NAME`), or ask the user which to stop.",
                )
            is StartResult.PortTaken ->
                error(
                    "Port ${result.port} is already used by \"${result.by}\". Stop it (`andcode-run stop ${result.by}`) " +
                        "or pick another port.",
                )
            StartResult.InvalidName ->
                error(
                    "A name is 1-40 letters, digits, dots, dashes or underscores, starting with a letter or digit.",
                )
            is StartResult.Failed -> error("Could not start: ${result.reason}. Tell the user; do not fall back to nohup or &.")
        }

    private fun json(process: SupervisedProcess): JSONObject =
        JSONObject()
            .put("name", process.spec.name)
            .put("status", process.status.name.lowercase())
            .put("command", process.spec.command)
            .put("directory", process.spec.directory)
            .put("restart", process.spec.restart.name.lowercase())
            .put("restarts", process.restarts)
            .put("uptimeSeconds", if (process.isActive) (clock() - process.startedAtMillis) / MILLIS_PER_SECOND else 0)
            .apply {
                process.port?.let { put("port", it).put("url", "http://localhost:$it") }
                process.exitCode?.let { put("exitCode", it) }
                process.spec.maxRunMinutes?.let { put("maxMinutes", it) }
            }

    private fun notRunning(name: String) = "No running process named \"$name\". Run `andcode-run list` to see the processes."

    private fun required(
        args: JSONObject,
        key: String,
    ): String =
        args.optString(key).trim().takeIf { it.isNotEmpty() && !args.isNull(key) } ?: throw IllegalArgumentException("$key is required")

    private fun ok(data: JSONObject) = JSONObject().put("ok", true).put("data", data)

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)

    private fun write(
        directory: File,
        requestId: String,
        response: JSONObject,
    ) {
        directory.mkdirs()
        val temp = File(directory, "$requestId.json.tmp")
        temp.writeText(response.toString())
        temp.renameTo(File(directory, "$requestId.json"))
    }

    private fun prune(
        directory: File,
        now: Long,
    ) {
        directory.listFiles().orEmpty().filter { now - it.lastModified() > STALE_AFTER_MILLIS }.forEach { it.delete() }
    }

    companion object {
        const val BRIDGE_RELATIVE_PATH = ".and-code/run-bridge"
        const val POLL_INTERVAL_MILLIS = 500L
        const val STALE_AFTER_MILLIS = 15 * 60_000L

        /** Just past the guest command's 30 s wait (`RESPONSE_TIMEOUT_S` in andcode-run; keep in step). */
        const val REQUEST_EXPIRES_AFTER_MILLIS = 35_000L
        private const val DEFAULT_LOG_CHARACTERS = 4_000
        private const val MAX_LOG_CHARACTERS = 100_000
        private const val MAX_PORT = 65_535
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
