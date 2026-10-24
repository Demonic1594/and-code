package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AntigravityMessageStoreTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `messages are written through a temporary file and survive a reload`() {
        val directory = java.nio.file.Files.createTempDirectory("antigravity-message-store").toFile()
        val file = File(directory, "antigravity-messages.json")
        val store = AntigravityMessageStore(file, json)
        store.append("s1", message("m1", "s1"))
        store.flush()
        store.append("s1", message("m2", "s1"))
        store.flush()

        assertEquals(listOf("antigravity-messages.json"), directory.list()?.toList())
        assertEquals(listOf("m1", "m2"), AntigravityMessageStore(file, json).list("s1").map { it.info.id })
        directory.deleteRecursively()
    }

    @Test
    fun `removing a session deletes its messages from disk`() {
        val directory = java.nio.file.Files.createTempDirectory("antigravity-message-store").toFile()
        val file = File(directory, "antigravity-messages.json")
        val store = AntigravityMessageStore(file, json)
        store.append("kept", message("m1", "kept"))
        store.append("removed", message("m2", "removed"))
        store.flush()

        assertTrue(store.remove("removed"))
        assertFalse(store.remove("missing"))
        store.flush()

        val reloaded = AntigravityMessageStore(file, json)
        assertEquals(listOf("m1"), reloaded.list("kept").map { it.info.id })
        assertTrue(reloaded.list("removed").isEmpty())
        directory.deleteRecursively()
    }

    @Test
    fun `a corrupt history is preserved instead of being overwritten`() {
        val directory = java.nio.file.Files.createTempDirectory("antigravity-message-store").toFile()
        val file = File(directory, "antigravity-messages.json").also { it.writeText("{not json") }
        val store = AntigravityMessageStore(file, json)

        assertTrue(store.list("s1").isEmpty())
        store.append("s1", message("m1", "s1"))
        store.flush()

        assertEquals("{not json", File(directory, "antigravity-messages.json.unreadable").readText())
        assertEquals(listOf("m1"), AntigravityMessageStore(file, json).list("s1").map { it.info.id })
        directory.deleteRecursively()
    }

    private fun message(
        id: String,
        sessionId: String,
    ) = OpenCodeMessage(info = OpenCodeMessageInfo(id, sessionId, "user"))
}
