package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeCacheTokens
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodeSessionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyActionsTest {
    private fun user(
        id: String,
        text: String,
        at: Long = 1_000L,
    ) = ChatMessage(id = id, isUser = true, parts = listOf(ChatPart.Text("$id-text", text)), timestamp = at)

    private fun assistant(
        id: String,
        vararg parts: ChatPart,
        completedAt: Long? = 2_000L,
        streaming: Boolean = false,
        model: OpenCodeModelReference? = null,
        tokens: OpenCodeSessionTokens? = null,
        cost: Double? = null,
    ) = ChatMessage(
        id = id,
        isUser = false,
        parts = parts.toList(),
        timestamp = 1_500L,
        completedAt = completedAt,
        isStreaming = streaming,
        model = model,
        tokens = tokens,
        costUsd = cost,
    )

    private fun footers(vararg messages: ChatMessage) = groupConversationTimeline(messages.toList()).filterIsInstance<TimelineEntry.Footer>()

    @Test
    fun `a finished reply carries its texts, in order, and the prompt that produced it`() {
        val footer =
            footers(
                user("u1", "fix the bug"),
                assistant("a1", ChatPart.Text("t1", "Looking."), ChatPart.Tool(id = "tool1", name = "read", status = ToolStatus.COMPLETED), completedAt = 1_800L),
                assistant("a2", ChatPart.Text("t2", "Fixed it.")),
            ).single()

        val reply = footer.reply!!
        assertEquals(listOf("Looking.", "Fixed it."), reply.texts)
        assertEquals("u1", reply.promptMessageId)
        assertTrue(reply.finished)
        assertFalse(reply.changedFiles)
    }

    @Test
    fun `a reply whose last message is still being written is not finished`() {
        val footer =
            footers(
                user("u1", "go"),
                assistant("a1", ChatPart.Text("t1", "Step one done."), completedAt = 1_800L),
                assistant("a2", ChatPart.Text("t2", "Working on step"), completedAt = null, streaming = true),
            ).single()

        assertFalse(footer.reply!!.finished)
    }

    @Test
    fun `a turn that ends in an error card gets no action row`() {
        val footer =
            footers(
                user("u1", "go"),
                assistant("a1", ChatPart.Text("t1", "Starting."), ChatPart.Error("e1", "Rate limited")),
            ).single()

        assertNull(footer.reply)
    }

    @Test
    fun `a turn with no text of its own gets no action row`() {
        val footer = footers(user("u1", "go"), assistant("a1", ChatPart.Tool(id = "tool1", name = "bash", status = ToolStatus.COMPLETED))).single()

        assertNull(footer.reply)
    }

    @Test
    fun `a turn that edited files says so, for a retry to offer putting them back`() {
        val footer =
            footers(
                user("u1", "edit it"),
                assistant("a1", ChatPart.Patch("p1", listOf("src/A.kt"), "a1"), ChatPart.Text("t1", "Edited A.")),
            ).single()

        assertTrue(footer.reply!!.changedFiles)
    }

    @Test
    fun `the reply's model is its last message's, and its tokens and cost add up over the turn`() {
        val footer =
            footers(
                user("u1", "go"),
                assistant(
                    "a1",
                    ChatPart.Text("t1", "One."),
                    completedAt = 1_800L,
                    model = OpenCodeModelReference("anthropic", "claude-old"),
                    tokens = OpenCodeSessionTokens(input = 100, output = 10, cache = OpenCodeCacheTokens(read = 5)),
                    cost = 0.01,
                ),
                assistant(
                    "a2",
                    ChatPart.Text("t2", "Two."),
                    model = OpenCodeModelReference("anthropic", "claude-new"),
                    tokens = OpenCodeSessionTokens(input = 200, output = 20, reasoning = 7),
                    cost = 0.02,
                ),
            ).single()

        val reply = footer.reply!!
        assertEquals("anthropic/claude-new", reply.model)
        assertEquals(ReplyTokens(input = 300, output = 30, reasoning = 7, cacheRead = 5), reply.tokens)
        assertEquals(0.03, reply.costUsd!!, 1e-9)
    }

    @Test
    fun `a reply with no reported tokens or cost leaves them unknown`() {
        val reply = footers(user("u1", "go"), assistant("a1", ChatPart.Text("t1", "Done."))).single().reply!!

        assertNull(reply.model)
        assertNull(reply.tokens)
        assertNull(reply.costUsd)
    }

    @Test
    fun `the latest reply is the last one, and none once a newer prompt is waiting`() {
        val answered =
            groupConversationTimeline(
                listOf(
                    user("u1", "one"),
                    assistant("a1", ChatPart.Text("t1", "First.")),
                    user("u2", "two"),
                    assistant("a2", ChatPart.Text("t2", "Second.")),
                ),
            )
        assertEquals("footer:a2", latestReplyFooterId(answered))

        val waiting = groupConversationTimeline(listOf(user("u1", "one"), assistant("a1", ChatPart.Text("t1", "First.")), user("u2", "two")))
        assertNull(latestReplyFooterId(waiting))
    }

    @Test
    fun `the row shows under finished replies, but not under the latest one while the agent works`() {
        val reply = ReplyContent(texts = listOf("Done."), promptMessageId = "u1", changedFiles = false, finished = true)
        val footer = TimelineEntry.Footer("footer:a1", completedAt = 2_000L, durationMs = 1_000L, reply = reply)

        assertTrue(showsReplyActions(footer, isLatest = true, isRunning = false))
        assertFalse(showsReplyActions(footer, isLatest = true, isRunning = true))
        assertTrue(showsReplyActions(footer, isLatest = false, isRunning = true))
        assertFalse(showsReplyActions(footer.copy(reply = reply.copy(finished = false)), isLatest = false, isRunning = false))
        assertFalse(showsReplyActions(footer.copy(reply = null), isLatest = false, isRunning = false))
    }

    @Test
    fun `retry is offered only on the latest reply, only while the agent is idle`() {
        val reply = ReplyContent(texts = listOf("Done."), promptMessageId = "u1", changedFiles = false, finished = true)

        assertTrue(showsRetry(reply, isLatest = true, isRunning = false, isBusy = false))
        assertFalse(showsRetry(reply, isLatest = false, isRunning = false, isBusy = false))
        assertFalse(showsRetry(reply, isLatest = true, isRunning = true, isBusy = false))
        assertFalse("a rewind or restore is landing", showsRetry(reply, isLatest = true, isRunning = false, isBusy = true))
        assertFalse("nothing to send again", showsRetry(reply.copy(promptMessageId = null), isLatest = true, isRunning = false, isBusy = false))
    }

    @Test
    fun `plain text is the reply as shown, without Markdown, its parts a paragraph apart`() {
        val text =
            replyPlainText(
                listOf(
                    "## Summary\n\nThe **fix** is in [the parser](https://example.com) and `Lexer.kt`.",
                    "- first\n- second\n\n1. one\n2. two\n\n```kotlin\nval x = 1\n```",
                ),
            )

        assertEquals(
            "Summary\n\nThe fix is in the parser and Lexer.kt.\n\n• first\n• second\n\n1. one\n2. two\n\nval x = 1",
            text,
        )
    }

    @Test
    fun `speech skips code blocks, saying so, but keeps inline code`() {
        val text = replySpeechText(listOf("Run `make` first.\n\n```sh\nmake all\n```\n\nThen it works."), codeOmitted = "Code omitted.")

        assertEquals("Run make first.\n\nCode omitted.\n\nThen it works.", text)
    }

    @Test
    fun `long speech is cut into pieces the engine takes, at paragraph and sentence ends`() {
        val paragraph = "This is one sentence. ".repeat(20).trim()
        val text = listOf(paragraph, paragraph, paragraph).joinToString("\n\n")

        val chunks = speechChunks(text, maxLength = 200)

        assertTrue(chunks.all { it.length <= 200 })
        assertTrue(chunks.all { it.endsWith(".") })
        assertEquals(text.replace(Regex("\\s+"), " "), chunks.joinToString(" ").replace(Regex("\\s+"), " "))
    }

    @Test
    fun `a single word longer than the limit is still cut to fit`() {
        val chunks = speechChunks("x".repeat(450), maxLength = 200)

        assertEquals(listOf(200, 200, 50), chunks.map { it.length })
    }

    @Test
    fun `a quote puts every line of the reply behind a marker and leaves a line to type on`() {
        assertEquals("> Fixed it.\n>\n> - one\n> - two\n\n", replyQuote(listOf("Fixed it.", "- one\n- two")))
    }

    @Test
    fun `details list only what the runtime reported`() {
        val known =
            ReplyContent(
                texts = listOf("Done."),
                promptMessageId = "u1",
                changedFiles = false,
                finished = true,
                model = "anthropic/claude",
                tokens = ReplyTokens(input = 10, output = 2, reasoning = 0, cacheRead = 0),
                costUsd = 0.0,
            )
        assertEquals(
            listOf(
                ReplyDetail.Model("anthropic/claude"),
                ReplyDetail.Duration(65_000L),
                ReplyDetail.Tokens(ReplyTokens(input = 10, output = 2, reasoning = 0, cacheRead = 0)),
                ReplyDetail.Cost(0.0),
            ),
            replyDetails(known, durationMs = 65_000L),
        )

        val unknown = known.copy(model = null, tokens = ReplyTokens(0, 0, 0, 0), costUsd = null)
        assertEquals(emptyList<ReplyDetail>(), replyDetails(unknown, durationMs = 0L))
    }
}
