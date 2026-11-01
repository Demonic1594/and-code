package com.yugahashimoto.andcode.ui.lock

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptGuardTest {
    @Test
    fun `a second ask while a prompt shows is ignored, and asking works again once it ends`() {
        val guard = PromptGuard()
        val ends = mutableListOf<() -> Unit>()

        guard.ask { done -> ends += done }
        guard.ask { done -> ends += done }

        assertEquals(1, ends.size)

        ends.single()()
        guard.ask { done -> ends += done }

        assertEquals(2, ends.size)
    }
}
