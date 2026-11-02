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
    fun `an allowed kind is saved for the hook and survives a restart`() {
        val rules = ToolClassRules(folder.root)

        rules.set(ToolClassRules.Kind.READING, allow = true)

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
    fun `a file that cannot be removed fails rather than showing off while still allowing`() {
        val rules = ToolClassRules(folder.root)
        rules.set(ToolClassRules.Kind.READING, allow = true)
        // A directory in the way of the delete stands for a file that will not go.
        val file = File(folder.root, ToolClassRules.FILE_NAME)
        file.delete()
        file.mkdir()
        File(file, "child").writeText("x")

        val failed = runCatching { rules.set(ToolClassRules.Kind.READING, allow = false) }.isFailure

        assertTrue(failed)
        assertEquals(setOf(ToolClassRules.Kind.READING), rules.allowed.value)
    }

    @Test
    fun `the hook knows the same tools for each kind as the app`() {
        val hook = File("src/main/assets/scripts/and-code-claude-permission-hook.sh").readText()
        ToolClassRules.Kind.entries.forEach { kind ->
            val line = Regex("""^\s*([A-Za-z|]+)\) TOOL_KIND="${kind.key}" ;;""", RegexOption.MULTILINE).find(hook)
            assertEquals(kind.name, kind.tools, line?.groupValues?.get(1)?.split('|')?.toSet())
        }
    }

    @Test
    fun `an unreadable file allows nothing`() {
        File(folder.root, ToolClassRules.FILE_NAME).writeText("not json")

        assertTrue(ToolClassRules(folder.root).allowed.value.isEmpty())
    }
}
