package com.yugahashimoto.andcode.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPromptTest {
    @Test
    fun `name with spaces and punctuation becomes a single command token`() {
        assertEquals("/review-the-pr", SavedPrompt("1", "  Review the PR! ", "x").command)
    }

    @Test
    fun `non-latin letters are kept`() {
        assertEquals("レビュー", SavedPrompt.normalizeName("レビュー"))
    }

    @Test
    fun `name is capped at the max length`() {
        assertEquals(SavedPrompt.MAX_NAME_LENGTH, SavedPrompt.normalizeName("a".repeat(100)).length)
    }

    @Test
    fun `codec round-trips a list`() {
        val prompts = listOf(SavedPrompt("1", "review", "Review my diff"), SavedPrompt("2", "tests", "Write tests\nfor it"))
        assertEquals(prompts, SavedPromptCodec.decode(SavedPromptCodec.encode(prompts)))
    }

    @Test
    fun `corrupt or missing storage decodes to empty`() {
        assertTrue(SavedPromptCodec.decode(null).isEmpty())
        assertTrue(SavedPromptCodec.decode("").isEmpty())
        assertTrue(SavedPromptCodec.decode("{not json").isEmpty())
    }
}
