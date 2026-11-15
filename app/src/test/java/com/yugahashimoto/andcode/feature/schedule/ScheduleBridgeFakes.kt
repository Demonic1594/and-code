package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleAgentChange
import com.yugahashimoto.andcode.data.schedule.ScheduleRun
import java.time.Instant

/** In-memory [ScheduleStore] for the bridge tests ([ScheduleBridgeTest], [ScheduleBridgeNoticeTest]). */
internal class FakeStore : ScheduleStore {
    val schedules = mutableListOf<Schedule>()
    val runs = mutableListOf<ScheduleRun>()
    val runNowCalls = mutableListOf<String>()
    var reschedules = 0
    var nextFire: Instant? = null

    override fun schedules(): List<Schedule> = schedules.toList()

    override fun schedule(id: String): Schedule? = schedules.firstOrNull { it.id == id }

    override fun runs(scheduleId: String?): List<ScheduleRun> =
        if (scheduleId == null) runs.toList() else runs.filter { it.scheduleId == scheduleId }

    override fun upsert(schedule: Schedule) {
        schedules.removeAll { it.id == schedule.id }
        schedules.add(schedule)
        reschedules++
    }

    override fun delete(id: String) {
        schedules.removeAll { it.id == id }
        runs.removeAll { it.scheduleId == id }
    }

    override fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        schedules.replaceAll { if (it.id == id) it.copy(enabled = enabled) else it }
        reschedules++
    }

    override fun runNow(id: String): Boolean {
        runNowCalls += id
        return true
    }

    override fun nextFireAt(schedule: Schedule): Instant? = nextFire

    override fun setAgentChange(
        id: String,
        change: ScheduleAgentChange?,
    ) {
        schedules.replaceAll { if (it.id == id) it.copy(agentChange = change) else it }
    }
}

/**
 * Records what the bridge would have told the user. [shows] false stands in for notifications that
 * are turned off; [fails] for a notification service that throws.
 */
internal class FakeScheduleNotifier : ScheduleChangeNotifier {
    val notices = mutableListOf<AgentScheduleNotice>()
    var shows = true
    var fails = false

    override fun notify(notice: AgentScheduleNotice): Boolean {
        check(!fails) { "notification service gone" }
        notices += notice
        return shows
    }
}

/** The app-wide auto-approve setting, and which runtimes are in full-access mode. */
internal class FakeApprovalSettings(
    override var appAutoApproves: Boolean = false,
    private val fullAccessRuntimes: Set<String> = emptySet(),
) : ScheduleApprovalSettings {
    override fun runtimeSkipsApproval(runtimeId: String): Boolean = runtimeId in fullAccessRuntimes
}
