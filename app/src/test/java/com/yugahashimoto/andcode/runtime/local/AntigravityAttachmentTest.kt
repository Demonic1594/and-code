package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.PromptAttachment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class AntigravityAttachmentTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `materializes image and references it in prompt`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val prompt =
            prepareAntigravityPrompt(
                runtimeDirectory = folder.root,
                sessionId = "session/unsafe",
                turn = 3,
                prompt = "Describe this image",
                attachments =
                    listOf(
                        PromptAttachment(
                            filename = "photo one.png",
                            mime = "image/png",
                            url = "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}",
                        ),
                    ),
            )

        assertEquals(
            "@/workspace/.andcode-attachments/session_unsafe/3/0-photo_one.png\nDescribe this image",
            prompt,
        )
        assertArrayEquals(
            bytes,
            folder.root.resolve("workspace/.andcode-attachments/session_unsafe/3/0-photo_one.png").readBytes(),
        )

        prepareAntigravityPrompt(
            folder.root,
            "session/unsafe",
            4,
            "Next image",
            listOf(PromptAttachment("next.png", "image/png", "data:image/png;base64,AQ==")),
        )
        assertArrayEquals(
            bytes,
            folder.root.resolve("workspace/.andcode-attachments/session_unsafe/3/0-photo_one.png").readBytes(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported attachments instead of silently dropping them`() {
        prepareAntigravityPrompt(
            folder.root,
            "session",
            0,
            "prompt",
            listOf(PromptAttachment("notes.txt", "text/plain", "data:text/plain;base64,dGVzdA==")),
        )
    }

    /** The workspace is the guest's: a linked attachments folder must not take the file into the app's own. */
    @Test
    fun `an attachment never lands through a folder the guest linked`() {
        val appFolder = folder.newFolder("shared_prefs")
        folder.root.resolve("workspace").mkdirs()
        java.nio.file.Files.createSymbolicLink(folder.root.resolve("workspace/.andcode-attachments").toPath(), appFolder.toPath())

        runCatching {
            prepareAntigravityPrompt(
                folder.root,
                "session",
                1,
                "Describe",
                listOf(PromptAttachment("a.png", "image/png", "data:image/png;base64,AQ==")),
            )
        }

        assertEquals(emptyList<String>(), appFolder.list()!!.toList())
    }
}
