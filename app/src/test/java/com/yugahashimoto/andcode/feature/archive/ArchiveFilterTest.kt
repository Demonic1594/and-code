package com.yugahashimoto.andcode.feature.archive

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.data.repository.ArchivedChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveFilterTest {
    private val chats =
        listOf(
            chat("1", "Fix login crash", "/workspace/android-app"),
            chat("2", "Update README", "/workspace/api-server"),
            chat("3", "Login page copy", null),
        )

    @Test
    fun `blank query keeps every chat`() {
        assertEquals(chats, filterArchivedChats(chats, "  "))
    }

    @Test
    fun `every word must match the title or folder, ignoring case`() {
        assertEquals(listOf("1", "3"), filterArchivedChats(chats, "login").map { it.session.id })
        assertEquals(listOf("1"), filterArchivedChats(chats, "LOGIN android").map { it.session.id })
        assertEquals(listOf("2"), filterArchivedChats(chats, "api").map { it.session.id })
    }

    @Test
    fun `project name is the folder's last segment, none for the default folders`() {
        assertEquals("android-app", archivedProjectName(chats[0]))
        assertNull(archivedProjectName(chats[2]))
        assertNull(archivedProjectName(chat("4", "x", "/workspace/")))
        assertNull(archivedProjectName(chat("5", "x", "/root")))
    }

    private fun chat(
        id: String,
        title: String,
        directory: String?,
    ) = ArchivedChat(runtimeId = "local", agent = null, session = OpenCodeSession(id = id, title = title, directory = directory))
}
