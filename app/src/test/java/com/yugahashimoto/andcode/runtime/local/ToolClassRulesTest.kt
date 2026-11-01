package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolClassRulesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `an allowed kind covers its tools, survives a restart, and leaves commands alone`() {
        val rules = ToolClassRules(folder.root)

        rules.set(ToolClassRules.Kind.READING, allow = true)

        assertTrue(rules.allows("Read"))
        assertTrue(rules.allows("WebFetch"))
        assertFalse(rules.allows("Edit"))
        assertFalse(rules.allows("Bash"))
        assertEquals("""{"allow":["read"]}""", File(folder.root, ToolClassRules.FILE_NAME).readText())
        assertEquals(setOf(ToolClassRules.Kind.READING), ToolClassRules(folder.root).allowed.value)
    }

    @Test
    fun `turning the last kind off removes the file the hook reads`() {
        val rules = ToolClassRules(folder.root)
        rules.set(ToolClassRules.Kind.EDITING, allow = true)

        rules.set(ToolClassRules.Kind.EDITING, allow = false)

        assertFalse(File(folder.root, ToolClassRules.FILE_NAME).exists())
        assertTrue(rules.allowed.value.isEmpty())
    }

    @Test
    fun `an unreadable file allows nothing`() {
        File(folder.root, ToolClassRules.FILE_NAME).writeText("not json")

        assertTrue(ToolClassRules(folder.root).allowed.value.isEmpty())
    }
}
