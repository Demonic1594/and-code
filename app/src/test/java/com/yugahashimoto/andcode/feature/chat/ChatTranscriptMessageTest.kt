package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [toChatMessage]: one persisted transcript message as the chat shows it. */
class ChatTranscriptMessageTest {
    private fun message(
        id: String = "m1",
        role: String = "assistant",
        parts: List<OpenCodePart> = emptyList(),
        time: OpenCodeTime = OpenCodeTime(created = 10L, completed = 20L),
        error: OpenCodeMessageError? = null,
    ) = OpenCodeMessage(
        info = OpenCodeMessageInfo(id = id, sessionId = "s1", role = role, time = time, error = error),
        parts = parts,
    )

    @Test
    fun `keeps the id, role, parts and times of the message`() {
        val chat = toChatMessage(message(parts = listOf(OpenCodePart(id = "p1", type = "text", text = "hello"))))

        requireNotNull(chat)
        assertEquals("m1", chat.id)
        assertFalse(chat.isUser)
        assertEquals(listOf(ChatPart.Text("p1", "hello")), chat.parts)
        assertEquals(10L, chat.timestamp)
        assertEquals(20L, chat.completedAt)
        assertFalse(chat.isStreaming)
    }

    @Test
    fun `a user message is the user's`() {
        val chat = toChatMessage(message(role = "user", parts = listOf(OpenCodePart(id = "p1", type = "text", text = "hi"))))

        assertTrue(requireNotNull(chat).isUser)
    }

    @Test
    fun `a message with nothing to show is dropped`() {
        assertNull(toChatMessage(message(parts = listOf(OpenCodePart(id = "p1", type = "step-start")))))
    }

    @Test
    fun `file parts become attachments, with defaults for what they leave out`() {
        val chat =
            toChatMessage(
                message(
                    role = "user",
                    parts =
                        listOf(
                            OpenCodePart(
                                id = "f1",
                                type = "file",
                                filename = "a.png",
                                mime = "image/png",
                                url = "data:image/png;base64,AA",
                            ),
                            OpenCodePart(id = "f2", type = "file"),
                        ),
                ),
            )

        requireNotNull(chat)
        assertEquals(listOf("a.png", "attachment"), chat.attachments.map { it.filename })
        assertEquals(listOf("image/png", "application/octet-stream"), chat.attachments.map { it.mime })
        assertEquals("", chat.attachments[1].url)
    }

    @Test
    fun `a failed turn with no parts still shows its error`() {
        val error = OpenCodeMessageError(name = "APIError", data = mapOf("message" to JsonPrimitive("invalid api key")))

        val chat = toChatMessage(message(error = error))

        assertEquals(listOf(ChatPart.Error("m1-error", "invalid api key")), requireNotNull(chat).parts)
    }
}
