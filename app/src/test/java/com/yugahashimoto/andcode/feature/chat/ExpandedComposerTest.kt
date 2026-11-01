package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedComposerTest {
    @Test
    fun `the full-screen editor is offered once the message fills the composer`() {
        assertFalse(shouldOfferExpandedComposer(1))
        assertFalse(shouldOfferExpandedComposer(3))
        assertTrue(shouldOfferExpandedComposer(4))
        assertTrue(shouldOfferExpandedComposer(12))
    }
}
