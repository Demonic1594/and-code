package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTitlesTest {
    @Test
    fun `a short single line is its own title`() {
        assertFalse(needsSummarizedTitle("fix the login bug"))
        assertFalse(needsSummarizedTitle("  ログインのバグを直して  "))
    }

    @Test
    fun `long, wordy or multi-line prompts get a summarized title`() {
        assertTrue(needsSummarizedTitle("please look at the failing CI job and tell me why it broke"))
        assertTrue(needsSummarizedTitle("fix this\nand also that"))
        assertTrue(needsSummarizedTitle("a".repeat(41)))
    }

    @Test
    fun `an empty prompt has nothing to summarize`() {
        assertFalse(needsSummarizedTitle("   \n "))
    }
}
