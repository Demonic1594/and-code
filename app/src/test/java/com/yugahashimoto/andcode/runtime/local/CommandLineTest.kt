package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandLineTest {
    @Test
    fun `a command line splits as a shell would, keeping a quoted empty argument`() {
        assertEquals(listOf("npx", "-y", "pkg"), splitCommandLine("  npx  -y pkg "))
        assertEquals(listOf("tool", "", "value"), splitCommandLine("tool '' value"))
        assertEquals(listOf("tool", "", "value"), splitCommandLine("tool \"\" value"))
        assertEquals(listOf("say", "two words", "it's"), splitCommandLine("say 'two words' it\\'s"))
        assertEquals(emptyList<String>(), splitCommandLine("   "))
    }

    @Test
    fun `a joined line splits back into the same words`() {
        val words = listOf("/opt/my tools/server", "", "two words", "it's", "back\\slash", "a\"b", "--flag=x")

        val line = joinCommandLine(words)

        assertEquals(words, splitCommandLine(line))
        // Plain words are left as they are.
        assertEquals("npx -y @scope/pkg --port=3000", joinCommandLine(listOf("npx", "-y", "@scope/pkg", "--port=3000")))
    }
}
