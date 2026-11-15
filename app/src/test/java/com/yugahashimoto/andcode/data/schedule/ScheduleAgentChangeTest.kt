package com.yugahashimoto.andcode.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "added/changed by an agent" marker is stored with the schedule, so it outlives the process. */
class ScheduleAgentChangeTest {
    @Test
    fun `the marker survives being stored and read back`() {
        val schedules =
            listOf(
                Schedule(id = "a", prompt = "p", cron = "0 9 * * *", agentChange = ScheduleAgentChange.CREATED),
                Schedule(id = "b", prompt = "p", cron = "0 9 * * *", agentChange = ScheduleAgentChange.CHANGED),
                Schedule(id = "c", prompt = "p", cron = "0 9 * * *"),
            )

        val decoded = ScheduleCodec.decodeSchedules(ScheduleCodec.encodeSchedules(schedules))

        assertEquals(schedules, decoded)
        assertEquals(listOf(ScheduleAgentChange.CREATED, ScheduleAgentChange.CHANGED, null), decoded.map { it.agentChange })
    }

    @Test
    fun `schedules stored before the marker existed read back unmarked`() {
        val stored = """[{"id":"old","name":"","runtimeId":"local","prompt":"p","cron":"0 9 * * *","enabled":true}]"""

        val decoded = ScheduleCodec.decodeSchedules(stored).single()

        assertEquals("old", decoded.id)
        assertNull(decoded.agentChange)
    }

    @Test
    fun `setting the marker changes only that schedule and keeps the list's order and times`() {
        val first = Schedule(id = "a", prompt = "p", cron = "0 9 * * *", updatedAt = 2L, agentChange = ScheduleAgentChange.CREATED)
        val second = Schedule(id = "b", prompt = "p", cron = "0 9 * * *", updatedAt = 1L)

        val cleared = listOf(first, second).withAgentChange("a", null)
        val marked = listOf(first, second).withAgentChange("b", ScheduleAgentChange.CHANGED)

        assertEquals(listOf(first.copy(agentChange = null), second), cleared)
        assertEquals(listOf(first, second.copy(agentChange = ScheduleAgentChange.CHANGED)), marked)
    }

    @Test
    fun `setting the marker of a schedule that is gone changes nothing`() {
        val schedules = listOf(Schedule(id = "a", prompt = "p", cron = "0 9 * * *"))

        assertEquals(schedules, schedules.withAgentChange("missing", ScheduleAgentChange.CHANGED))
    }
}
