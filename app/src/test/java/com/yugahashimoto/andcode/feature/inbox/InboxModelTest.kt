package com.yugahashimoto.andcode.feature.inbox

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.data.repository.RuntimeActivityState
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.runtime.LocalAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxModelTest {
    private fun ref(
        id: String,
        updated: Long = 0L,
        parentId: String? = null,
        runtimeId: String = "opencode",
        agent: LocalAgent? = LocalAgent.OPEN_CODE,
    ) = RuntimeSessionRef(
        runtimeId = runtimeId,
        agent = agent,
        session = OpenCodeSession(id = id, title = "Chat $id", parentId = parentId, time = OpenCodeTime(created = 1L, updated = updated)),
    )

    @Test
    fun `sorts each section and keeps every chat in one place`() {
        val inbox =
            buildInbox(
                sessions = listOf(ref("a", 10), ref("b", 30), ref("c", 20), ref("d", 5), ref("e", 40, runtimeId = "claude")),
                activity =
                    RuntimeActivityState(
                        activeSessionIds = setOf("a", "b", "c"),
                        completedSessionIds = setOf("d", "e"),
                        permissions = listOf(PermissionRequest("p1", "c", "bash")),
                        currentSteps = mapOf("b" to "bash · ./gradlew test"),
                    ),
                selectedRuntimeId = "opencode",
            )

        assertEquals(listOf("c"), inbox.approvals.map { it.chat.sessionId })
        // Waiting on an approval, "c" is not listed again as running.
        assertEquals(listOf("b", "a"), inbox.running.map { it.chat.sessionId })
        assertEquals("bash · ./gradlew test", inbox.running.first().step)
        assertEquals(listOf("e", "d"), inbox.finished.map { it.sessionId })
        assertEquals("claude", inbox.finished.first().runtimeId)
        assertEquals(3, inbox.needsYou)
        // Only the selected runtime's chats can be answered in place.
        assertEquals(listOf(false, true), inbox.finished.map { it.canReply })
    }

    @Test
    fun `an approval from a subagent the catalog has not seen yet is filed under its parent`() {
        val inbox =
            buildInbox(
                sessions = listOf(ref("parent")),
                activity =
                    RuntimeActivityState(
                        activeSessionIds = setOf("parent", "new-child"),
                        permissions =
                            listOf(PermissionRequest("p1", "new-child", "bash"), PermissionRequest("p2", "new-child", "edit")),
                    ),
                selectedRuntimeId = "opencode",
                parentOf = { if (it == "new-child") "parent" else null },
            )

        assertEquals(listOf("parent", "parent"), inbox.approvals.map { it.chat.sessionId })
        assertEquals("Chat parent", inbox.approvals.first().chat.title)
        // Waiting on its subagent's approval, the parent is not running as well, and counts once.
        assertTrue(inbox.running.isEmpty())
        assertEquals(1, inbox.needsYou)
    }

    @Test
    fun `a subagent's approval is filed under the chat that started it and its run is not a chat`() {
        val inbox =
            buildInbox(
                sessions = listOf(ref("parent"), ref("child", parentId = "parent"), ref("grandchild", parentId = "child")),
                activity =
                    RuntimeActivityState(
                        activeSessionIds = setOf("parent", "child", "grandchild", "stream-only-child"),
                        completedSessionIds = setOf("child"),
                        permissions = listOf(PermissionRequest("p1", "grandchild", "edit")),
                    ),
                selectedRuntimeId = "opencode",
                parentOf = { if (it == "stream-only-child") "parent" else null },
            )

        assertEquals("parent", inbox.approvals.single().chat.sessionId)
        assertEquals("Chat parent", inbox.approvals.single().chat.title)
        assertTrue(inbox.running.isEmpty())
        assertTrue(inbox.finished.isEmpty())
    }

    @Test
    fun `chats the catalog does not know yet still show what they are waiting on`() {
        val inbox =
            buildInbox(
                sessions = emptyList(),
                activity =
                    RuntimeActivityState(
                        activeSessionIds = setOf("new"),
                        // A deleted chat leaves its unread marker behind.
                        completedSessionIds = setOf("deleted"),
                        permissions = listOf(PermissionRequest("p1", "fresh", "bash")),
                    ),
                selectedRuntimeId = "opencode",
            )

        val approval = inbox.approvals.single().chat
        assertEquals("", approval.title)
        assertEquals("opencode", approval.runtimeId)
        assertEquals(listOf("new"), inbox.running.map { it.chat.sessionId })
        assertTrue(inbox.finished.isEmpty())
    }

    @Test
    fun `chats waiting out a delete are left out`() {
        val inbox =
            buildInbox(
                sessions = listOf(ref("a"), ref("b"), ref("c")),
                activity =
                    RuntimeActivityState(
                        activeSessionIds = setOf("a"),
                        completedSessionIds = setOf("b"),
                        permissions = listOf(PermissionRequest("p1", "c", "bash")),
                    ),
                selectedRuntimeId = "opencode",
                hidden = setOf("a", "b", "c"),
            )

        assertTrue(inbox.isEmpty)
    }
}
