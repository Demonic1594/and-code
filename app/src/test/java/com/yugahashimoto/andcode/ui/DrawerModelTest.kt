package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.ui.components.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerModelTest {
    private fun ref(
        id: String,
        title: String = "",
        slug: String? = null,
        parentId: String? = null,
        created: Long = 1L,
        updated: Long? = null,
        runtimeId: String = "local-claude",
    ) = RuntimeSessionRef(
        runtimeId = runtimeId,
        agent = null,
        session =
            OpenCodeSession(
                id = id,
                slug = slug,
                parentId = parentId,
                title = title,
                time = OpenCodeTime(created = created, updated = updated),
            ),
    )

    private fun recent(
        sessions: List<RuntimeSessionRef>,
        active: Set<String> = emptySet(),
        completed: Set<String> = emptySet(),
        pendingDeletion: Set<String> = emptySet(),
    ) = drawerRecentSessions(sessions, active, completed, pendingDeletion) { "t$it" }

    @Test
    fun `subagent chats and chats waiting to be deleted are left out`() {
        val rows = recent(listOf(ref("a"), ref("child", parentId = "a"), ref("gone")), pendingDeletion = setOf("gone"))

        assertEquals(listOf("a"), rows.map { it.id })
    }

    @Test
    fun `the list stops at twenty five chats`() {
        val rows = recent((1..30).map { ref("s$it") })

        assertEquals(25, rows.size)
        assertEquals("s1", rows.first().id)
    }

    @Test
    fun `a blank title falls back to the slug, then the id`() {
        val rows = recent(listOf(ref("a", title = "Fix login"), ref("b", slug = "brave-otter"), ref("c")))

        assertEquals(listOf("Fix login", "brave-otter", "c"), rows.map { it.title })
    }

    @Test
    fun `the time shown is the last update, else the creation`() {
        val rows = recent(listOf(ref("a", created = 5L, updated = 9L), ref("b", created = 7L)))

        assertEquals(listOf("t9", "t7"), rows.map { it.relativeTime })
    }

    @Test
    fun `running wins over unread`() {
        val rows = recent(listOf(ref("a"), ref("b"), ref("c")), active = setOf("a"), completed = setOf("a", "b"))

        assertEquals(listOf(SessionStatus.RUNNING, SessionStatus.COMPLETED_UNREAD, SessionStatus.IDLE), rows.map { it.status })
        assertEquals(listOf(true, false, false), rows.map { it.isActive })
        assertEquals(listOf(true, true, false), rows.map { it.hasUnread })
    }

    @Test
    fun `each row keeps the runtime that owns it`() {
        val rows = recent(listOf(ref("a", runtimeId = "local-codex")))

        assertEquals("local-codex", rows.single().runtimeId)
    }

    @Test
    fun `only pinned chats become shortcuts`() {
        val rows = recent(listOf(ref("a", title = "One"), ref("b", title = "Two")))

        val shortcuts = pinnedChatShortcuts(rows, setOf("b", "not-listed"))

        assertEquals(listOf(PinnedChatShortcut("b", "Two", "local-claude")), shortcuts)
    }

    @Test
    fun `toggling a section flips it`() {
        assertEquals(setOf("recent", "projects"), toggledSection(setOf("recent"), "projects"))
        assertEquals(emptySet<String>(), toggledSection(setOf("recent"), "recent"))
    }
}
