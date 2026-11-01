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
            "! git status",
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

    @Test
    fun `programs that run other code are never matched or remembered`() {
        listOf(
            "bash -c ls",
            "/bin/sh -c ls",
            "FOO=1 sh -c ls",
            "\"sh\" -c ls",
            "python3.12 -c 1",
            "busybox sh",
            "sh -c ls",
            "env ls",
            "xargs ls",
            "sudo ls",
            "python3 -c 1",
            "node -e 1",
        ).forEach {
                command ->
            assertFalse(command, ShellCommandRules.matches(command.substringBefore(' '), command))
            assertNull(command, ShellCommandRules.ruleFor(command))
        }
    }

    @Test
    fun `tabs separate words and a dollar sign is refused`() {
        assertTrue(ShellCommandRules.matches("git status", "git\tstatus"))
        assertFalse(ShellCommandRules.matches("echo", "echo \$HOME"))
    }

    @Test
    fun `a blocking rule refuses the command wherever it is hidden`() {
        listOf(
            "rm -rf build",
            "cd x && sudo /bin/rm -rf /",
            "echo \$(rm -rf ~)",
            "ls `rm -rf ~`",
            "\"rm\" '-rf' x",
            "r\\m -rf x",
            "rm -v -rf x",
            "rm -fr x",
            "rm -r -f x",
            "rm \\\n  -rf x",
            "rm \\\r\n  -rf x",
        ).forEach { command -> assertTrue(command, ShellCommandRules.blocks("rm -rf", command)) }
    }

    @Test
    fun `a blocking rule keeps its words in order but allows others between`() {
        assertTrue(ShellCommandRules.blocks("git push --force", "git push origin main --force"))
        assertTrue(ShellCommandRules.blocks("git push --force", "ls; git push --force"))
        assertTrue(ShellCommandRules.blocks("git push --force", "git push \\\n  origin main --force"))
        assertFalse(ShellCommandRules.blocks("git push --force", "git push --force-with-lease"))
        assertFalse(ShellCommandRules.blocks("git push --force", "git push -f"))
        // The words must follow the program within one part of the command, not across parts.
        assertFalse(ShellCommandRules.blocks("git push --force", "echo push --force; git status"))
        assertFalse(ShellCommandRules.blocks("rm -rf", "rm -r x"))
        assertFalse(ShellCommandRules.blocks("rm -rf", "echo rm"))
    }

    @Test
    fun `a blocking rule is kept as the words the user typed`() {
        assertEquals("rm -rf", ShellCommandRules.blockRuleFor("  rm   -rf "))
        assertEquals("git push --force", ShellCommandRules.blockRuleFor("\"git push\" --force"))
        assertNull(ShellCommandRules.blockRuleFor("  ;  "))
        // Two commands would make one rule that matches neither.
        assertNull(ShellCommandRules.blockRuleFor("git push --force && git reset --hard"))
        assertFalse(ShellCommandRules.blocks("", "rm -rf x"))
    }
}
