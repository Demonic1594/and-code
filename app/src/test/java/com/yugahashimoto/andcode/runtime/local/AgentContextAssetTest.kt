package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled instructions keep every hard rule an agent must follow, and stay within budget. */
class AgentContextAssetTest {
    private val text: String by lazy {
        listOf("src/main/assets", "app/src/main/assets")
            .map { File(it, AND_CODE_AGENT_CONTEXT_ASSET) }
            .first { it.isFile }
            .readText()
    }

    @Test
    fun `the supervisor rules are all there`() {
        listOf(
            "ALWAYS start dev servers",
            "NEVER start them with `nohup`",
            "andcode-run start",
            "andcode-run list",
            "andcode-run logs",
            "andcode-run stop",
            "VERIFY before telling the user",
            "127.0.0.1",
        ).forEach { rule -> assertTrue("missing: $rule", text.contains(rule)) }
    }

    @Test
    fun `the artifact rules are all there`() {
        listOf("SINGLE self-contained file", "NEVER reference `./style.css`", "give the user its path").forEach { rule ->
            assertTrue("missing: $rule", text.contains(rule))
        }
    }

    @Test
    fun `the text stays within its length budget`() {
        // About 1,500 tokens at four characters a token; growing past this is a decision, not an accident.
        assertTrue("agent context is ${text.length} characters", text.length <= MAX_CHARACTERS)
    }

    private companion object {
        const val MAX_CHARACTERS = 6_500
    }
}
