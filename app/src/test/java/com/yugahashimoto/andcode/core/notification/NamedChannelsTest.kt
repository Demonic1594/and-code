package com.yugahashimoto.andcode.core.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NamedChannelsTest {
    private val japanese = Locale("ja")
    private val spanish = Locale("es")

    @Test
    fun `a channel not yet named by this process is created`() {
        // It may exist from an earlier process, named in whatever language that one had.
        assertTrue(NamedChannels().claim("status", japanese))
    }

    @Test
    fun `a channel already named in the current language is left alone`() {
        val named = NamedChannels()
        named.claim("status", japanese)
        assertFalse(named.claim("status", japanese))
    }

    @Test
    fun `a channel is named again once the language has changed`() {
        val named = NamedChannels()
        named.claim("status", japanese)
        assertTrue(named.claim("status", spanish))
        assertFalse(named.claim("status", spanish))
    }

    @Test
    fun `each channel keeps its own record`() {
        val named = NamedChannels()
        named.claim("status", japanese)
        assertTrue(named.claim("approvals", japanese))
    }

    @Test
    fun `a channel whose creation failed is tried again`() {
        val named = NamedChannels()
        named.claim("status", japanese)
        named.forget("status")
        assertTrue(named.claim("status", japanese))
    }

    @Test
    fun `a language change renames only the channels the app already has`() {
        val all = listOf(AppNotificationChannels.APPROVALS, AppNotificationChannels.WAKE_WORD, AppNotificationChannels.STATUS)
        val existing = setOf(AppNotificationChannels.STATUS.id, AppNotificationChannels.APPROVALS.id, "some_other_app_channel")
        // Creating the wake-word channel here would list a feature the user never turned on.
        assertEquals(
            listOf(AppNotificationChannels.APPROVALS, AppNotificationChannels.STATUS),
            AppNotificationChannels.existingAmong(all, existing),
        )
    }

    @Test
    fun `every channel keeps the id it had, so the user's settings for it survive`() {
        assertEquals(
            setOf(
                "opencode_approvals",
                "opencode_status",
                "opencode_live_runs",
                "andcode_schedule_agent_changes",
                "andcode_agent_browsing",
                "andcode_schedule_runs",
                "local_opencode_runtime",
                "andcode_supervised_processes",
                "andcode_codex_keepalive",
                "wakeword_channel",
            ),
            AppNotificationChannels.all.map { it.id }.toSet(),
        )
        assertEquals(AppNotificationChannels.all.size, AppNotificationChannels.all.map { it.id }.toSet().size)
    }
}
