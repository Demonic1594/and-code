package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.PromptRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Ultracode is read only when Claude Code starts, so a turn sent without it would restart the
 * session and its prompt cache. Turns that do not say (a reply from a notification, a skill, a
 * schedule) keep what the session last ran with.
 */
class ClaudeCodeTargetUltracodeTest {
    @get:Rule val folder = TemporaryFolder()

    /** The stored value; an absent key is the default, off. */
    private fun storedUltracode(sessionId: String): Boolean =
        Json.parseToJsonElement(File(folder.root, "claude-sessions.json").readText())
            .jsonArray
            .map { it.jsonObject }
            .single { it["session"]?.jsonObject?.get("id")?.jsonPrimitive?.content == sessionId }["ultracode"]
            ?.jsonPrimitive
            ?.boolean ?: false

    @Test
    fun `a turn that does not say keeps the session's ultracode, one that does changes it`() =
        runBlocking {
            val target = ClaudeCodeTarget(ClaudeCodeRuntime(folder.root, { null }))
            val session = target.createSession("Chat", "/workspace")

            // No runtime is installed here, so each send fails after the session is updated.
            runCatching { target.sendMessage(session.id, PromptRequest(text = "plan it", ultracode = true)) }
            runCatching { target.sendMessage(session.id, PromptRequest(text = "reply from a notification")) }
            assertTrue(storedUltracode(session.id))

            runCatching { target.sendMessage(session.id, PromptRequest(text = "plain again", ultracode = false)) }
            assertFalse(storedUltracode(session.id))
        }
}
