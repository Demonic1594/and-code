package com.yugahashimoto.andcode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerSearchTest {
    private val sessions =
        listOf(
            DrawerRecentSession(id = "1", title = "Fix login bug", relativeTime = "1m", directory = "/root/webapp"),
            DrawerRecentSession(id = "2", title = "Write README", relativeTime = "2m", directory = "/root/docs-site"),
            DrawerRecentSession(id = "3", title = "Login page redesign", relativeTime = "3m", directory = "/root/webapp"),
        )

    @Test
    fun `blank query matches nothing`() {
        assertTrue(filterDrawerSessions(sessions, "   ").isEmpty())
    }

    @Test
    fun `matches title case-insensitively and keeps order`() {
        assertEquals(listOf("1", "3"), filterDrawerSessions(sessions, "LOGIN").map { it.id })
    }

    @Test
    fun `every word must match, across title and project folder`() {
        assertEquals(listOf("3"), filterDrawerSessions(sessions, "webapp redesign").map { it.id })
        assertEquals(listOf("2"), filterDrawerSessions(sessions, "docs").map { it.id })
    }
}
