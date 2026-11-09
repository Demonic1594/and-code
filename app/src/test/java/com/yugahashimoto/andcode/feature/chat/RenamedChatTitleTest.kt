package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the rename dialog's Save does with what was typed. */
class RenamedChatTitleTest {
    @Test
    fun `a new name is saved trimmed`() {
        assertEquals("Login bug", renamedChatTitle("  Login bug ", "Fix the login crash"))
    }

    @Test
    fun `a blank name is not saved`() {
        assertNull(renamedChatTitle("   ", "Fix the login crash"))
    }

    @Test
    fun `the current name is not saved again`() {
        assertNull(renamedChatTitle("Fix the login crash ", "Fix the login crash"))
    }
}
