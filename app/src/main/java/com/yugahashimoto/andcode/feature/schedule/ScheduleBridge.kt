package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.core.util.pollWhileActive
import com.yugahashimoto.andcode.data.schedule.CronExpression
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleAgentChange
import com.yugahashimoto.andcode.data.schedule.ScheduleRepository
import com.yugahashimoto.andcode.data.schedule.ScheduleRun
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Port that lets the [ScheduleBridge] drive the schedule domain without Android. Implemented by
 * [AppScheduleStore] against the encrypted repository and the alarm manager.
 */
interface ScheduleStore {
    fun schedules(): List<Schedule>

    fun schedule(id: String): Schedule?

    fun runs(scheduleId: String? = null): List<ScheduleRun>

    fun upsert(schedule: Schedule)

    fun delete(id: String)

    fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    fun runNow(id: String): Boolean

    fun nextFireAt(schedule: Schedule): Instant?

    /** Sets or clears [id]'s "added/changed by an agent" marker; not an edit of the schedule. */
    fun setAgentChange(
        id: String,
        change: ScheduleAgentChange?,
    )
}

/**
 * The bridge's real store: persists through [ScheduleRepository] and keeps the alarms in sync via
 * [ScheduleManager], exactly like the schedules UI does.
 */
class AppScheduleStore(
    private val repository: ScheduleRepository,
    private val manager: ScheduleManager,
) : ScheduleStore {
    override fun schedules(): List<Schedule> = repository.schedules.value

    override fun schedule(id: String): Schedule? = repository.schedule(id)

    override fun runs(scheduleId: String?): List<ScheduleRun> =
        if (scheduleId == null) repository.runs.value else repository.runs.value.filter { it.scheduleId == scheduleId }

    override fun upsert(schedule: Schedule) {
        repository.upsert(schedule)
        manager.rescheduleAll()
    }

    override fun delete(id: String) {
        repository.delete(id)
        manager.cancel(id)
        manager.rescheduleAll()
    }

    override fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        repository.setEnabled(id, enabled)
        manager.rescheduleAll()
    }

    override fun runNow(id: String): Boolean = manager.runNow(id)

    override fun nextFireAt(schedule: Schedule): Instant? = manager.nextFireAt(schedule)

    override fun setAgentChange(
        id: String,
        change: ScheduleAgentChange?,
    ) = repository.setAgentChange(id, change)
}

/**
 * Request/response file bridge between the guest `and-code-schedule` MCP server and the app's
 * schedule store.
 *
 * The server and the guest cannot reach the app's private encrypted preferences, so the server
 * writes a request under `pending/<id>.json` inside [workspaceDir]/.and-code/schedule-bridge (the
 * guest sees the same tree at `/workspace/.and-code/schedule-bridge`) and [pollOnce]/[run] pick it
 * up, execute it through [ScheduleStore], and write `responses/<id>.json` for the server to read.
 *
 * Nothing here asks the user first, so whatever adds to what runs - a new schedule, a change to what
 * or when one runs, turning one on - is told to them through [notifier] and marked on the schedule
 * ([Schedule.agentChange]), with a warning when the runs will not ask for approval ([approvals]).
 * Deleting gets a quieter notice; turning off and renaming only take away, and are not told.
 */
class ScheduleBridge(
    private val workspaceDir: File,
    private val store: ScheduleStore,
    private val notifier: ScheduleChangeNotifier,
    private val approvals: ScheduleApprovalSettings,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    private val staleAfterMillis: Long = STALE_AFTER_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val bridgeDir: File = File(workspaceDir, BRIDGE_RELATIVE_PATH)

    /**
     * Runs the poll loop until cancelled; typically launched on the application scope.
     *
     * [active] says whether the guest could be talking to the bridge right now. It is required
     * rather than defaulted to "always": a default would let a future call site forget it and
     * quietly bring back a loop that polls for the life of the process.
     */
    suspend fun run(active: Flow<Boolean>) {
        // A single failing request or IO error must never kill the bridge for the process
        // lifetime; drop it and keep polling.
        active.pollWhileActive(pollIntervalMillis) { runCatching { pollOnce() } }
    }

    /** Handles every currently pending request and prunes stale files. */
    fun pollOnce() {
        // The guest MCP server creates the tree on its first call, so an idle process only pays
        // for one directory stat per poll instead of keeping the bridge populated forever.
        if (!bridgeDir.isDirectory) return
        val pendingDir = File(bridgeDir, "pending")
        val responsesDir = File(bridgeDir, "responses")
        val now = clock()
        for (file in pendingDir.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }) {
            handlePending(file, responsesDir, now)
        }
        pruneStale(pendingDir, now)
        pruneStale(responsesDir, now)
    }

    private fun handlePending(
        file: File,
        responsesDir: File,
        now: Long,
    ) {
        val requestId = file.nameWithoutExtension
        val request = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (request == null) {
            file.delete()
            writeResponse(responsesDir, requestId, errorResponse("Malformed request"), now)
            return
        }
        // The guest stops waiting after RESPONSE_TIMEOUT_S and reports the call as failed. A request
        // that sat unanswered past that - written while the poll was parked - must not run on the
        // way back in, or a create/delete/runNow the agent was told did not happen would happen
        // anyway. A missing or zero timestamp is "unknown", not "ancient", so it still runs.
        val createdAtMs = request.optLong("createdAtMs", 0L)
        if (createdAtMs > 0L && now - createdAtMs > REQUEST_EXPIRES_AFTER_MILLIS) {
            file.delete()
            writeResponse(responsesDir, requestId, errorResponse("Request expired before the AndCode app could handle it"), now)
            return
        }
        val response =
            try {
                execute(request)
            } catch (error: Exception) {
                errorResponse(error.message ?: error::class.simpleName.orEmpty())
            }
        // Drop the request before the reply so a crash in between can never re-run a mutating
        // operation (create/delete/runNow) after the response was already consumed.
        file.delete()
        writeResponse(responsesDir, requestId, response, now)
    }

    private fun execute(request: JSONObject): JSONObject {
        val op = request.optString("op", "")
        val args = request.optJSONObject("args") ?: JSONObject()
        return when (op) {
            "list" ->
                ok("schedules", JSONArray().apply { store.schedules().forEach { put(scheduleJson(it)) } })
            "get" -> {
                val id = requiredString(args, "scheduleId")
                val schedule = store.schedule(id)
                ok(
                    "schedule" to (schedule?.let(::scheduleJson) ?: JSONObject.NULL),
                    "runs" to runsJson(store.runs(scheduleId = id)),
                )
            }
            "runs" -> {
                val scheduleId = optionalString(args, "scheduleId")
                ok("runs", runsJson(store.runs(scheduleId)))
            }
            "create" -> {
                refuseAutoAcceptGrant(args)
                val built = buildScheduleFromArgs(args).copy(agentChange = ScheduleAgentChange.CREATED)
                store.upsert(built)
                val stored = store.schedule(built.id) ?: built
                tellUser(AgentScheduleChangeKind.CREATED, stored)
                // Told either way: without a notification, the marker in the Schedules screen says it.
                ok("schedule" to scheduleJson(stored), USER_NOTIFIED to true)
            }
            "update" -> update(args)
            "delete" -> {
                val id = requiredString(args, "scheduleId")
                val existing = store.schedule(id) ?: throw IllegalArgumentException("schedule not found: $id")
                store.delete(id)
                // No schedule is left to carry a marker, so only a notification that was shown counts.
                val told = tellUser(AgentScheduleChangeKind.DELETED, existing)
                ok("id" to id, "deleted" to true, USER_NOTIFIED to told)
            }
            "setEnabled" -> {
                val id = requiredString(args, "scheduleId")
                if (!args.has("enabled")) throw IllegalArgumentException("enabled is required")
                val enabled = args.getBoolean("enabled")
                val existing = store.schedule(id) ?: throw IllegalArgumentException("schedule not found: $id")
                store.setEnabled(id, enabled)
                val turnedOn = enabled && !existing.enabled
                if (turnedOn) markChanged(existing)
                ok("id" to id, "enabled" to enabled, USER_NOTIFIED to turnedOn)
            }
            "runNow" -> {
                val id = requiredString(args, "scheduleId")
                if (store.schedule(id) == null) throw IllegalArgumentException("schedule not found: $id")
                if (!store.runNow(id)) throw IllegalArgumentException("the system refused to start the run")
                ok("id" to id, "started" to true)
            }
            else -> throw IllegalArgumentException("unknown operation: $op")
        }
    }

    /**
     * Requests here come from an agent, so an agent could otherwise give its future runs every approval
     * without asking: only the user grants that, in the Schedules screen. Turning it off (false) or
     * following the app's setting (null) is always allowed.
     */
    private fun refuseAutoAcceptGrant(args: JSONObject) {
        if (optionalBoolean(args, "autoAcceptPermissions") == true) {
            throw IllegalArgumentException(
                "autoAcceptPermissions can only be turned on by the user in the Schedules screen; " +
                    "leave it out or set it to false",
            )
        }
    }

    private fun update(args: JSONObject): JSONObject {
        val id = requiredString(args, "scheduleId")
        val existing = store.schedule(id) ?: throw IllegalArgumentException("schedule not found: $id")
        refuseAutoAcceptGrant(args)
        val updated = applyScheduleArgs(existing, args)
        // Only an explicit false unlocks it: null hands the schedule to the app-wide setting, which may
        // itself auto-approve.
        if (existing.autoAcceptPermissions == true && updated.autoAcceptPermissions != false && updated.changesWhatRuns(existing)) {
            throw IllegalArgumentException(
                "this schedule approves its own tool calls, so only the user can change what it runs; " +
                    "set autoAcceptPermissions to false in the same request, or ask the user to edit it in the Schedules screen",
            )
        }
        val told = updated.addsToWhatRuns(existing, approvals.appAutoApproves)
        if (!told) {
            store.upsert(updated)
            return ok("schedule" to scheduleJson(store.schedule(id) ?: updated), USER_NOTIFIED to false)
        }
        // A schedule the user has not looked at since an agent added it still reads "added".
        val marked = updated.copy(agentChange = existing.agentChange ?: ScheduleAgentChange.CHANGED)
        store.upsert(marked)
        val stored = store.schedule(id) ?: marked
        tellUser(AgentScheduleChangeKind.CHANGED, stored)
        return ok("schedule" to scheduleJson(stored), USER_NOTIFIED to true)
    }

    /** Marks a schedule an agent turned on, keeping an "added" mark the user has not seen, and says so. */
    private fun markChanged(before: Schedule) {
        store.setAgentChange(before.id, before.agentChange ?: ScheduleAgentChange.CHANGED)
        tellUser(AgentScheduleChangeKind.CHANGED, store.schedule(before.id) ?: before.copy(enabled = true))
    }

    /**
     * Tells the user what an agent did, returning whether a notification was shown. The change has
     * already happened by now, so a notification that cannot be posted must not turn the reply into
     * an error: the agent would think it failed and try again. The marker on a new or changed
     * schedule is already stored either way.
     */
    private fun tellUser(
        kind: AgentScheduleChangeKind,
        schedule: Schedule,
    ): Boolean =
        runCatching {
            val live = kind != AgentScheduleChangeKind.DELETED
            notifier.notify(
                AgentScheduleNotice(
                    kind = kind,
                    schedule = schedule,
                    nextRunAt = if (live && schedule.enabled) store.nextFireAt(schedule) else null,
                    runsWithoutAsking = live && schedule.runsWithoutAsking(approvals),
                ),
            )
        }.getOrDefault(false)

    private fun buildScheduleFromArgs(args: JSONObject): Schedule {
        val (oneTimeAt, cron) = resolveTrigger(args)
        return Schedule(
            name = args.optString("name", "").trim(),
            runtimeId = requiredString(args, "runtimeId"),
            workspacePath = optionalString(args, "workspacePath").orEmpty(),
            providerId = optionalString(args, "providerId"),
            modelId = optionalString(args, "modelId"),
            agentId = optionalString(args, "agentId"),
            prompt = requiredString(args, "prompt"),
            oneTimeAt = oneTimeAt,
            cron = cron,
            enabled = args.optBoolean("enabled", true),
            autoAcceptPermissions = optionalBoolean(args, "autoAcceptPermissions"),
        )
    }

    private fun applyScheduleArgs(
        existing: Schedule,
        args: JSONObject,
    ): Schedule {
        var oneTimeAt = existing.oneTimeAt
        var cron = existing.cron
        if (hasValue(args, "cron") && hasValue(args, "oneTimeAt")) {
            throw IllegalArgumentException("provide exactly one of oneTimeAt or cron")
        }
        if (hasValue(args, "cron")) {
            val value = args.getString("cron")
            if (CronExpression.parse(value) == null) throw IllegalArgumentException("invalid cron expression: $value")
            cron = value
            oneTimeAt = null
        } else if (hasValue(args, "oneTimeAt")) {
            oneTimeAt = args.getLong("oneTimeAt")
            cron = null
        }
        if (oneTimeAt == null && cron == null) throw IllegalArgumentException("schedule needs a trigger: oneTimeAt or cron")
        return existing.copy(
            name = args.optString("name", existing.name).trim(),
            runtimeId = if (hasValue(args, "runtimeId")) requiredString(args, "runtimeId") else existing.runtimeId,
            workspacePath = if (hasValue(args, "workspacePath")) args.optString("workspacePath") else existing.workspacePath,
            providerId = if (args.has("providerId")) optionalString(args, "providerId") else existing.providerId,
            modelId = if (args.has("modelId")) optionalString(args, "modelId") else existing.modelId,
            agentId = if (args.has("agentId")) optionalString(args, "agentId") else existing.agentId,
            prompt = if (args.has("prompt")) requiredString(args, "prompt") else existing.prompt,
            oneTimeAt = oneTimeAt,
            cron = cron,
            enabled = if (args.has("enabled")) args.getBoolean("enabled") else existing.enabled,
            autoAcceptPermissions =
                if (args.has("autoAcceptPermissions")) optionalBoolean(args, "autoAcceptPermissions") else existing.autoAcceptPermissions,
        )
    }

    /** Resolves the trigger pair; exactly one of oneTimeAt/cron must be present. */
    private fun resolveTrigger(args: JSONObject): Pair<Long?, String?> {
        val hasOneTimeAt = hasValue(args, "oneTimeAt")
        val hasCron = hasValue(args, "cron")
        if (hasOneTimeAt == hasCron) throw IllegalArgumentException("provide exactly one of oneTimeAt or cron")
        if (hasOneTimeAt) return args.getLong("oneTimeAt") to null
        val cron = args.getString("cron")
        if (CronExpression.parse(cron) == null) throw IllegalArgumentException("invalid cron expression: $cron")
        return null to cron
    }

    private fun scheduleJson(schedule: Schedule): JSONObject =
        JSONObject()
            .put("id", schedule.id)
            .put("name", schedule.name)
            .put("runtimeId", schedule.runtimeId)
            .put("workspacePath", schedule.workspacePath)
            .put("providerId", schedule.providerId ?: JSONObject.NULL)
            .put("modelId", schedule.modelId ?: JSONObject.NULL)
            .put("agentId", schedule.agentId ?: JSONObject.NULL)
            .put("prompt", schedule.prompt)
            .put("oneTimeAt", schedule.oneTimeAt ?: JSONObject.NULL)
            .put("cron", schedule.cron ?: JSONObject.NULL)
            .put("enabled", schedule.enabled)
            .put("autoAcceptPermissions", schedule.autoAcceptPermissions ?: JSONObject.NULL)
            .put("createdAt", schedule.createdAt)
            .put("updatedAt", schedule.updatedAt)
            .put("displayName", schedule.displayName)
            .put("isRecurring", schedule.isRecurring)
            .put("nextFireAt", if (schedule.enabled) store.nextFireAt(schedule)?.toEpochMilli() else JSONObject.NULL)

    private fun runsJson(runs: List<ScheduleRun>): JSONArray =
        JSONArray().apply {
            runs.forEach { run ->
                put(
                    JSONObject()
                        .put("id", run.id)
                        .put("scheduleId", run.scheduleId)
                        .put("sessionId", run.sessionId)
                        .put("runtimeId", run.runtimeId)
                        .put("startedAt", run.startedAt)
                        .put("finishedAt", run.finishedAt ?: JSONObject.NULL)
                        .put("status", run.status.name)
                        .put("error", run.error ?: JSONObject.NULL)
                        .put("isActive", run.isActive),
                )
            }
        }

    private fun writeResponse(
        responsesDir: File,
        requestId: String,
        payload: JSONObject,
        now: Long,
    ) {
        responsesDir.mkdirs()
        val target = File(responsesDir, "$requestId.json")
        val tmp = File(responsesDir, "$requestId.json.tmp")
        tmp.writeText(payload.put("at", now).toString())
        if (!tmp.renameTo(target)) {
            target.writeText(payload.toString())
            tmp.delete()
        }
    }

    private fun pruneStale(
        dir: File,
        now: Long,
    ) {
        val staleTargets =
            dir.listFiles().orEmpty().filter { file ->
                file.name.endsWith(".json") || file.name.endsWith(".json.tmp")
            }
        for (file in staleTargets) {
            if (now - file.lastModified() > staleAfterMillis) file.delete()
        }
    }

    private fun ok(vararg pairs: Pair<String, Any>): JSONObject {
        val data = JSONObject().apply { pairs.forEach { (key, value) -> put(key, value) } }
        return JSONObject().put("ok", true).put("data", data)
    }

    private fun ok(
        key: String,
        value: Any,
    ): JSONObject = ok(key to value)

    private fun errorResponse(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private fun requiredString(
        args: JSONObject,
        key: String,
    ): String {
        if (!hasValue(args, key)) throw IllegalArgumentException("$key is required")
        return args.getString(key).trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$key is required")
    }

    private fun optionalString(
        args: JSONObject,
        key: String,
    ): String? = if (hasValue(args, key)) args.getString(key) else null

    private fun optionalBoolean(
        args: JSONObject,
        key: String,
    ): Boolean? = if (hasValue(args, key)) args.getBoolean(key) else null

    /** True when the argument carries a real value rather than JSON null. */
    private fun hasValue(
        args: JSONObject,
        key: String,
    ): Boolean = args.has(key) && !args.isNull(key)

    companion object {
        /** Path under the workspace (guest-visible at /workspace) where the MCP server writes. */
        const val BRIDGE_RELATIVE_PATH = ".and-code/schedule-bridge"

        /**
         * In a reply to a change: whether the user was told about it, so the agent does not imply it
         * happened unseen. Always true for a new or changed schedule, which is marked in the
         * Schedules screen even when no notification can be shown; for a delete, only when one was.
         */
        const val USER_NOTIFIED = "userNotified"

        const val POLL_INTERVAL_MILLIS = 800L
        const val STALE_AFTER_MILLIS = 15 * 60_000L

        /**
         * Just past the guest's 90 s reply timeout (`RESPONSE_TIMEOUT_S` in andcode-schedule-mcp.py,
         * which must be kept in step with this), so a request is dropped once its caller has given up
         * on it. No allowance for clock drift is needed: the guest runs under PRoot and reads the same
         * kernel clock as this process.
         */
        const val REQUEST_EXPIRES_AFTER_MILLIS = 95_000L
    }
}
