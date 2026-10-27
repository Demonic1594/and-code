package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.PromptRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Claude Code chat's updated time follows the conversation. Caches keyed by it (message search,
 * the usage page) otherwise kept serving what they read before the latest turns.
 */
class ClaudeCodeTargetUpdatedTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `sending a turn moves the chat's updated time even when the run cannot start`() =
        runBlocking {
            val target = ClaudeCodeTarget(ClaudeCodeRuntime(folder.root, { null }))
            val session = target.createSession("Chat", "/workspace")
            val before = target.session(session.id).time.updated ?: 0L

            // No runtime is installed here, so the send itself fails after the chat is touched.
            runCatching { target.sendMessage(session.id, PromptRequest(text = "hello")) }

            assertTrue((target.session(session.id).time.updated ?: 0L) > before)
        }
}
