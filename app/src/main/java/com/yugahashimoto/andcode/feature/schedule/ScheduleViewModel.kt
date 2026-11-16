package com.yugahashimoto.andcode.feature.schedule

import androidx.lifecycle.ViewModel
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleRepository
import com.yugahashimoto.andcode.data.schedule.ScheduleRun

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val manager: ScheduleManager,
    /** Takes down an agent's notice about a schedule (see [AgentScheduleNotifications.cancel]). */
    private val dismissAgentNotice: (scheduleId: String) -> Unit = {},
) : ViewModel() {
    val schedules = repository.schedules
    val runs = repository.runs

    fun create(schedule: Schedule) {
        repository.upsert(schedule)
        manager.rescheduleAll()
    }

    fun update(schedule: Schedule) {
        repository.upsert(schedule)
        manager.rescheduleAll()
    }

    fun delete(scheduleId: String) {
        repository.delete(scheduleId)
        manager.cancel(scheduleId)
        manager.rescheduleAll()
    }

    fun setEnabled(
        scheduleId: String,
        enabled: Boolean,
    ) {
        repository.setEnabled(scheduleId, enabled)
        manager.rescheduleAll()
    }

    fun runNow(scheduleId: String) {
        manager.runNow(scheduleId)
    }

    /**
     * The user has opened [scheduleId], so an agent's "added/changed" marker on it and its
     * notification have done their job.
     */
    fun acknowledgeAgentChange(scheduleId: String) {
        repository.setAgentChange(scheduleId, null)
        dismissAgentNotice(scheduleId)
    }

    fun runsFor(scheduleId: String): List<ScheduleRun> = repository.runs.value.filter { it.scheduleId == scheduleId }
}
