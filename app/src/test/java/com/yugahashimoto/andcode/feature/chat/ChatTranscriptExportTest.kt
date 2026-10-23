package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ChatTranscriptExportTest {
    private val labels = TranscriptLabels(user = "You", assistant = "Assistant", tool = "Tool", error = "Error")

    @Test
    fun `renders turns, tools and errors but not reasoning`() {
        val markdown =
            chatTranscriptMarkdown(
                title = "Fix bug",
                messages =
                    listOf(
                        ChatMessage(isUser = true, parts = listOf(ChatPart.Text("a", "Please fix it"))),
                        ChatMessage(
                            isUser = false,
                            parts =
                                listOf(
                                    ChatPart.Reasoning("r", "secret thoughts"),
                                    ChatPart.Tool("t", name = "bash", status = ToolStatus.COMPLETED, title = "npm test\nmore"),
                                    ChatPart.Text("b", "Done."),
                                    ChatPart.Error("e", "rate limited"),
                                ),
                        ),
                    ),
                labels = labels,
                exportedAt = Date(0),
            )
        assertTrue(markdown.startsWith("# Fix bug\n"))
        assertTrue(markdown.contains("## You\n\nPlease fix it"))
        assertTrue(markdown.contains("- Tool: `bash` - npm test\n"))
        assertTrue(markdown.contains("Done."))
        assertTrue(markdown.contains("> **Error:** rate limited"))
        assertFalse(markdown.contains("secret thoughts"))
    }

    @Test
    fun `file name is a safe slug`() {
        assertEquals("andcode-fix-the-login-bug.md", transcriptFileName("Fix the login bug!"))
        assertEquals("andcode-chat.md", transcriptFileName("ログイン"))
    }
}
