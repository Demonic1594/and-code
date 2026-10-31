package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandRulesTest {
    @Test
    fun `a rule covers the same command and longer ones`() {
        assertTrue(ShellCommandRules.matches("git status", "git status"))
        assertTrue(ShellCommandRules.matches("git status", "git status --short"))
        assertTrue(ShellCommandRules.matches("npm test", "  npm   test  -- --watch"))
    }

    @Test
    fun `words must match whole`() {
        assertFalse(ShellCommandRules.matches("git status", "git statusfoo"))
        assertFalse(ShellCommandRules.matches("git status", "git"))
        assertFalse(ShellCommandRules.matches("npm test", "npm testing"))
    }

    @Test
    fun `chained, piped, substituted and redirected commands never match`() {
        listOf(
            "git status; rm -rf ~",
            "git status && rm -rf ~",
            "git status || rm -rf ~",
            "git status | sh",
            "git status & rm -rf ~",
            "git status\nrm -rf ~",
            "git status \$(rm -rf ~)",
            "git status `rm -rf ~`",
            "git status > /etc/passwd",
            "git status < input",
            "git status \\\nrm -rf ~",
        ).forEach { command ->
            assertFalse(command, ShellCommandRules.matches("git status", command))
            assertNull(command, ShellCommandRules.ruleFor(command))
        }
    }

    @Test
    fun `the remembered rule is the first two words of a simple command`() {
        assertEquals("git status", ShellCommandRules.ruleFor("git status --short"))
        assertEquals("ls", ShellCommandRules.ruleFor("ls"))
        assertNull(ShellCommandRules.ruleFor("   "))
    }

    @Test
    fun `an empty rule matches nothing`() {
        assertFalse(ShellCommandRules.matches("", "git status"))
        assertFalse(ShellCommandRules.matches("   ", "git status"))
    }
}
