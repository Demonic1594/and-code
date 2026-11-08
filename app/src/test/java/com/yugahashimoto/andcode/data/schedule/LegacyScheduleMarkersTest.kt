package com.yugahashimoto.andcode.data.schedule

import com.yugahashimoto.andcode.data.security.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyScheduleMarkersTest {
    private val preferences = InMemorySharedPreferences()
    private val schedules =
        listOf(
            Schedule(id = "nightly", prompt = "run the tests"),
            Schedule(id = "weekly", prompt = "update dependencies"),
            Schedule(id = "paused", prompt = "tidy up", enabled = false),
        )

    /** A new instance each time, as after a restart: the marks must come from the preferences. */
    private fun markers() = LegacyScheduleMarkers(preferences)

    @Test
    fun `with no marks the legacy schedules come back as they were`() {
        assertEquals(schedules, markers().applyTo(schedules))
    }

    @Test
    fun `a schedule deleted in fallback mode does not come back`() {
        markers().markDeleted("nightly")

        assertEquals(listOf("weekly", "paused"), markers().applyTo(schedules).map { it.id })
    }

    @Test
    fun `a schedule turned off in fallback mode comes back off, as it was`() {
        markers().markTurnedOff("weekly")

        val restored = markers().applyTo(schedules)

        assertEquals(listOf(true, false, false), restored.map { it.enabled })
        assertEquals("update dependencies", restored[1].prompt)
    }

    @Test
    fun `turning a schedule back on removes its mark, and leaves an off one as it was`() {
        markers().markTurnedOff("weekly")
        markers().markTurnedOn("weekly")
        markers().markTurnedOn("paused")

        assertEquals(schedules, markers().applyTo(schedules))
    }

    @Test
    fun `an edited schedule stays off after a restart even if it was turned back on`() {
        markers().markEdited("nightly")
        markers().markTurnedOff("nightly")
        markers().markTurnedOn("nightly")

        assertEquals(false, markers().applyTo(schedules).first { it.id == "nightly" }.enabled)
    }

    @Test
    fun `clearing forgets every mark`() {
        markers().markDeleted("nightly")
        markers().markTurnedOff("weekly")
        markers().markEdited("paused")

        markers().clear()

        assertEquals(schedules, markers().applyTo(schedules))
    }
}
