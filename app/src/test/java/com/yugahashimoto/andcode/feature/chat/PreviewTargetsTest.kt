package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTargetsTest {
    @Test
    fun `collects newest loopback urls from messages`() {
        val messages =
            listOf(
                ChatMessage(isUser = false, parts = listOf(ChatPart.Text("1", "start http://127.0.0.1:5173/"))),
                ChatMessage(
                    isUser = false,
                    parts = listOf(ChatPart.Text("2", "then http://localhost:3000 and https://example.com")),
                ),
            )

        assertEquals(
            listOf("http://localhost:3000", "http://127.0.0.1:5173/"),
            loopbackPreviewTargets(messages).map { target -> target.url },
        )
    }

    /** `**Open [the app](http://127.0.0.1:5173/)**` is how agents often write it. */
    @Test
    fun `a link to a page on this device is found inside emphasis too`() {
        val link = MarkdownInline.Link("the app", "http://127.0.0.1:5173/")

        assertEquals(
            "http://127.0.0.1:5173/",
            firstLoopbackLink(
                listOf(
                    MarkdownInline.Plain("Open "),
                    MarkdownInline.Link("docs", "https://example.com/"),
                    MarkdownInline.Bold(listOf(MarkdownInline.Italic(listOf(MarkdownInline.Strikethrough(listOf(link)))))),
                ),
            ),
        )
        assertEquals(null, firstLoopbackLink(listOf(MarkdownInline.Bold(listOf(MarkdownInline.Link("x", "https://example.com/"))))))
    }

    @Test
    fun `deduplicates repeated urls`() {
        val messages =
            listOf(
                ChatMessage(isUser = false, parts = listOf(ChatPart.Text("1", "http://localhost:3000"))),
                ChatMessage(isUser = false, parts = listOf(ChatPart.Text("2", "http://localhost:3000"))),
            )

        assertEquals(1, loopbackPreviewTargets(messages).size)
    }
}
