package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeModelsTest {
    @Test
    fun `ultracode follows the models the CLI allows it on`() {
        // The aliases resolve to the newest models, which support it.
        listOf("fable", "opus", "sonnet", "haiku").forEach { assertTrue(it, ClaudeModels.supportsUltracode(it)) }
        listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1", "claude-mythos-5")
            .forEach { assertTrue(it, ClaudeModels.supportsUltracode(it)) }
        // The ones without the xhigh effort, as the CLI lists them, with the suffixes ids carry.
        listOf(
            "claude-sonnet-4-6",
            "claude-opus-4-6[1m]",
            "claude-haiku-4-5-20251001",
            "Claude-Opus-4-1",
            "claude-3-7-sonnet-20250219",
            "claude-3.5-haiku",
        ).forEach { assertFalse(it, ClaudeModels.supportsUltracode(it)) }
    }

    @Test
    fun `the catalogue marks ultracode by the model each alias resolved to`() {
        val models = ClaudeModels.catalog(mapOf("haiku" to "claude-haiku-4-5")).all.single().models

        assertTrue(models.getValue("opus").claudeUltracode)
        assertFalse(models.getValue("haiku").claudeUltracode)
    }

    @Test
    fun `an account whose session showed no Workflow tool gets no ultracode on any model`() {
        val unknown = ClaudeModels.catalog(emptyMap(), workflows = null).all.single().models
        val without = ClaudeModels.catalog(emptyMap(), workflows = false).all.single().models

        assertTrue(unknown.values.all { it.claudeUltracode })
        assertTrue(without.values.none { it.claudeUltracode })
    }

    @Test
    fun `ultracode is passed as a session setting and nothing is passed when it is off`() {
        assertEquals("{\"ultracode\":true}", ClaudeModels.cliSettings(true))
        assertNull(ClaudeModels.cliSettings(false))
    }
}
