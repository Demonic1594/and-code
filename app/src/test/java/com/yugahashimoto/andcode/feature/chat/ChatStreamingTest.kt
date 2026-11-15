package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.RunActivity
import com.yugahashimoto.andcode.core.api.RunRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merges behind the live bubble: [mergeStreamedPart], [appendStreamedDelta], [withStreamedParts]. */
class ChatStreamingTest {
    @Test
    fun `a part with no copy yet is taken as it is`() {
        val part = ChatPart.Text("p1", "hello")

        assertSame(part, mergeStreamedPart(existing = null, chatPart = part))
    }

    @Test
    fun `a late blank snapshot keeps the text the deltas already brought`() {
        val existing = ChatPart.Text("p1", "streamed so far")

        assertSame(existing, mergeStreamedPart(existing, ChatPart.Text("p1", "")))
    }

    @Test
    fun `a snapshot that turns out to be reasoning keeps the streamed text`() {
        val merged = mergeStreamedPart(ChatPart.Text("p1", "thinking"), ChatPart.Reasoning("p1", ""))

        assertEquals(ChatPart.Reasoning("p1", "thinking"), merged)
    }

    @Test
    fun `a snapshot with text replaces the streamed copy`() {
        val merged = mergeStreamedPart(ChatPart.Text("p1", "partial"), ChatPart.Text("p1", "partial and more"))

        assertEquals(ChatPart.Text("p1", "partial and more"), merged)
    }

    @Test
    fun `streamed reasoning survives a blank snapshot of either kind`() {
        val existing = ChatPart.Reasoning("p1", "weighing it up")

        assertSame(existing, mergeStreamedPart(existing, ChatPart.Reasoning("p1", "")))
        assertSame(existing, mergeStreamedPart(existing, ChatPart.Text("p1", "")))
    }

    @Test
    fun `blank reasoning over blank reasoning takes the snapshot`() {
        val snapshot = ChatPart.Reasoning("p1", "")

        assertSame(snapshot, mergeStreamedPart(ChatPart.Reasoning("p1", ""), snapshot))
    }

    @Test
    fun `a tool part always takes the snapshot`() {
        val snapshot = ChatPart.Tool("p1", "bash", ToolStatus.COMPLETED)

        assertSame(snapshot, mergeStreamedPart(ChatPart.Tool("p1", "bash", ToolStatus.RUNNING), snapshot))
    }

    @Test
    fun `a delta extends text and reasoning`() {
        assertEquals(ChatPart.Text("p1", "hello"), appendStreamedDelta(ChatPart.Text("p1", "hel"), delta("lo")))
        assertEquals(ChatPart.Reasoning("p1", "hmm."), appendStreamedDelta(ChatPart.Reasoning("p1", "hmm"), delta(".")))
    }

    @Test
    fun `a delta that outruns its part starts one of the kind its field names`() {
        assertEquals(ChatPart.Text("p1", "hi"), appendStreamedDelta(null, delta("hi", field = "text")))
        assertEquals(ChatPart.Reasoning("p1", "hm"), appendStreamedDelta(null, delta("hm", field = "reasoning")))
    }

    @Test
    fun `a delta for a part with no text is ignored`() {
        assertNull(appendStreamedDelta(ChatPart.Tool("p1", "bash", ToolStatus.RUNNING), delta("x")))
    }

    @Test
    fun `parts for a message on screen replace its parts and mark it streaming`() {
        val state =
            ChatUiState(
                messages = listOf(ChatMessage(id = "a1", isUser = false, parts = listOf(ChatPart.Text("p1", "old")))),
                retry = RunRetry(attempt = 1, message = "busy"),
                runActivity = RunActivity.Compacting,
                isThinking = true,
            )

        val next = withStreamedParts(state, "a1", listOf(ChatPart.Text("p1", "new")))

        val message = next.messages.single()
        assertEquals(listOf(ChatPart.Text("p1", "new")), message.parts)
        assertTrue(message.isStreaming)
        assertTrue(next.isRunning)
        assertFalse(next.isThinking)
        assertNull(next.retry)
        assertNull(next.runActivity)
    }

    @Test
    fun `a model fallback outlives the parts that follow it`() {
        val fallback = RunActivity.ModelSwitched(from = "big-model", to = "small-model")
        val state = ChatUiState(runActivity = fallback)

        assertEquals(fallback, withStreamedParts(state, "a1", listOf(ChatPart.Text("p1", "x"))).runActivity)
    }

    @Test
    fun `parts echoing the user's prompt take over that bubble's id instead of adding one`() {
        val prompt = ChatMessage(id = "local", isUser = true, parts = listOf(ChatPart.Text("u1", "fix the build")))
        val state = ChatUiState(messages = listOf(prompt))

        val next = withStreamedParts(state, "server-id", listOf(ChatPart.Text("p1", "fix the build")))

        assertEquals(listOf("server-id"), next.messages.map { it.id })
        assertFalse(next.isRunning)
    }

    @Test
    fun `parts for a new message add a streaming reply`() {
        val next = withStreamedParts(ChatUiState(), "a1", listOf(ChatPart.Text("p1", "hi")))

        val reply = next.messages.single()
        assertEquals("a1", reply.id)
        assertFalse(reply.isUser)
        assertTrue(reply.isStreaming)
        assertTrue(next.isRunning)
    }

    private fun delta(
        text: String,
        field: String = "text",
    ) = OpenCodeEvent.MessagePartDelta(
        sessionId = "s1",
        messageId = "a1",
        partId = "p1",
        field = field,
        delta = text,
    )
}
