package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LargeInstructionFileTest {
    @Test
    fun `a file is large past a twentieth of the window, and never below ten thousand tokens`() {
        assertEquals(10_000L, largeInstructionFileTokens(200_000))
        assertEquals(50_000L, largeInstructionFileTokens(1_000_000))
        assertEquals(10_000L, largeInstructionFileTokens(0))
    }
}
