package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import java.time.Instant

/**
 * Whether this schedule's runs answer tool approvals themselves: the schedule's own choice, or the
 * app-wide setting when it has none. [ScheduleExecutionService] answers a run's approvals by this,
 * and the bridge warns by it, so the warning cannot say something the run then does differently.
 */
fun Schedule.autoApproves(appAutoApproves: Boolean): Boolean = autoAcceptPermissions ?: appAutoApproves

/** What, besides the schedule itself, decides whether a scheduled run stops to ask the user. */
interface ScheduleApprovalSettings {
    /** The app-wide auto-approve setting a schedule with no choice of its own follows. */
    val appAutoApproves: Boolean

    /** See [com.yugahashimoto.andcode.runtime.RuntimeTarget.sessionsSkipApproval]. */
    fun runtimeSkipsApproval(runtimeId: String): Boolean
}

/**
 * True when a run of this schedule, started now, would use tools without anyone being asked:
 * either the run answers approvals itself ([autoApproves]), or its runtime opens sessions in a mode
 * that never asks (full access).
 */
fun Schedule.runsWithoutAsking(settings: ScheduleApprovalSettings): Boolean =
    autoApproves(settings.appAutoApproves) || settings.runtimeSkipsApproval(runtimeId)

/** What a run does, as opposed to its name, timing or whether it is on. */
internal fun Schedule.changesWhatRuns(before: Schedule): Boolean =
    runtimeId != before.runtimeId || workspacePath != before.workspacePath || providerId != before.providerId ||
        modelId != before.modelId || agentId != before.agentId || prompt != before.prompt

/**
 * Whether an agent's update gives the schedule more to do than [before] had: it runs something
 * else, at another time, is turned on, or now leaves its approvals to an app setting that approves
 * them (false to null). Renaming and turning off only relabel or take away, and are not told.
 * Kept out of [ScheduleBridge] so the class keeps room under detekt's function limit.
 */
internal fun Schedule.addsToWhatRuns(
    before: Schedule,
    appAutoApproves: Boolean,
): Boolean =
    changesWhatRuns(before) || oneTimeAt != before.oneTimeAt || cron != before.cron || (enabled && !before.enabled) ||
        (autoApproves(appAutoApproves) && !before.autoApproves(appAutoApproves))

/** The real settings: the app's auto-approve switch and each runtime's current permission mode. */
class AppScheduleApprovalSettings(
    private val appAutoApprovesSetting: () -> Boolean,
    private val runtimes: RuntimeRegistry,
) : ScheduleApprovalSettings {
    override val appAutoApproves: Boolean get() = appAutoApprovesSetting()

    override fun runtimeSkipsApproval(runtimeId: String): Boolean = runtimes.target(runtimeId)?.sessionsSkipApproval == true
}

enum class AgentScheduleChangeKind { CREATED, CHANGED, DELETED }

/**
 * Something an agent did to a schedule through the bridge, without the user confirming it.
 *
 * [schedule] is the schedule as stored after the change (for [AgentScheduleChangeKind.DELETED], as
 * it was before). [nextRunAt] is null when it is off or has no run ahead.
 */
data class AgentScheduleNotice(
    val kind: AgentScheduleChangeKind,
    val schedule: Schedule,
    val nextRunAt: Instant?,
    val runsWithoutAsking: Boolean,
)

/** Tells the user about an [AgentScheduleNotice]; an Android notification in the app. */
fun interface ScheduleChangeNotifier {
    /** Returns false when the notice could not be shown: notifications are off for the app or its channel. */
    fun notify(notice: AgentScheduleNotice): Boolean
}
