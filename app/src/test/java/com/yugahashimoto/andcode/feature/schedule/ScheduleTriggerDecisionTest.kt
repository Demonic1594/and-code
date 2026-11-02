package com.yugahashimoto.andcode.feature.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScheduleTriggerDecisionTest {
    @Test
    fun `only the token and an enabled schedule run, and a bad token wins over everything`() {
        assertEquals(TriggerDecision.RUN, triggerDecision(tokenAccepted = true, scheduleEnabled = true))
        assertEquals(TriggerDecision.SCHEDULE_OFF, triggerDecision(tokenAccepted = true, scheduleEnabled = false))
        assertEquals(TriggerDecision.NO_SCHEDULE, triggerDecision(tokenAccepted = true, scheduleEnabled = null))
        listOf(true, false, null).forEach { enabled ->
            assertEquals(TriggerDecision.BAD_TOKEN, triggerDecision(tokenAccepted = false, scheduleEnabled = enabled))
        }
    }

    @Test
    fun `a trigger, a retry and the cron alarm never share a request code`() {
        listOf("a", "nightly", "8f14e45f-ceea-467a-9575-c1a2f3e4b5c6").forEach { id ->
            val codes = listOf(cronRequestCode(id), retryRequestCode(id), triggerRequestCode(id))
            assertEquals(id, 3, codes.toSet().size)
        }
        assertNotEquals(triggerRequestCode("a"), triggerRequestCode("b"))
    }
}
