package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFindTest {
    private val entries =
        listOf(
            TimelineEntry.UserMessage(
                "u1",
                ChatMessage(id = "m1", isUser = true, parts = listOf(ChatPart.Text("p1", "Fix the Login crash"))),
            ),
            TimelineEntry.Activity(
                "a1",
                listOf(
                    ChatPart.Reasoning("r1", "Looking at the auth flow"),
                    ChatPart.Tool("t1", name = "bash", status = ToolStatus.COMPLETED, title = "Run tests", input = "./gradlew test"),
                ),
            ),
            TimelineEntry.Body("b1", "m2", ChatPart.Text("p2", "The login screen now handles a null token.")),
            TimelineEntry.Footer("f1", completedAt = 1, durationMs = 1),
            TimelineEntry.Error("e1", ChatPart.Error("x", "Rate limit reached")),
        )

    @Test
    fun `matches every row containing the query, ignoring case, oldest first`() {
        assertEquals(listOf(0, 2), chatFindMatches(entries, "LOGIN"))
    }

    @Test
    fun `reasoning, tool calls and errors can be found too`() {
        assertEquals(listOf(1), chatFindMatches(entries, "auth flow"))
        assertEquals(listOf(1), chatFindMatches(entries, "gradlew"))
        assertEquals(listOf(4), chatFindMatches(entries, "rate limit"))
    }

    @Test
    fun `a phrase is matched as typed`() {
        assertEquals(listOf(2), chatFindMatches(entries, "null token"))
        assertTrue(chatFindMatches(entries, "token null").isEmpty())
    }

    @Test
    fun `blank query matches nothing`() {
        assertTrue(chatFindMatches(entries, "   ").isEmpty())
    }
}
