package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.SessionApproval
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

    /** See [com.yugahashimoto.andcode.runtime.RuntimeTarget.sessionApproval]. */
    fun runtimeApproval(runtimeId: String): SessionApproval
}

/**
 * What a run of this schedule, started now, would ask before using tools. A run that answers
 * approvals itself ([autoApproves]) never asks, whatever mode its runtime is in; otherwise it asks
 * what a session its runtime opens now would.
 */
fun Schedule.runApproval(settings: ScheduleApprovalSettings): SessionApproval =
    if (autoApproves(settings.appAutoApproves)) SessionApproval.NEVER_ASKS else settings.runtimeApproval(runtimeId)

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

    // A runtime that no longer exists cannot run the schedule at all, so there is nothing to warn about.
    override fun runtimeApproval(runtimeId: String): SessionApproval = runtimes.target(runtimeId)?.sessionApproval ?: SessionApproval.ASKS
}

enum class AgentScheduleChangeKind { CREATED, CHANGED, DELETED }

/**
 * Something an agent did to a schedule through the bridge, without the user confirming it.
 *
 * [schedule] is the schedule as stored after the change (for [AgentScheduleChangeKind.DELETED], as
 * it was before). [nextRunAt] is null when it is off or has no run ahead. [approval] is what its
 * runs would ask (always [SessionApproval.ASKS] for a deleted one, which runs nothing).
 */
data class AgentScheduleNotice(
    val kind: AgentScheduleChangeKind,
    val schedule: Schedule,
    val nextRunAt: Instant?,
    val approval: SessionApproval,
) {
    /** Nothing in its runs asks: the full ⚠ warning rather than the softer accept-edits one. */
    val runsWithoutAsking: Boolean get() = approval == SessionApproval.NEVER_ASKS
}

/** Tells the user about an [AgentScheduleNotice]; an Android notification in the app. */
fun interface ScheduleChangeNotifier {
    /** Returns false when the notice could not be shown: notifications are off for the app or its channel. */
    fun notify(notice: AgentScheduleNotice): Boolean
}
