package com.yugahashimoto.andcode.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regular expressions with ICU, which refuses a closing brace that closes nothing
 * (`\{\d+}`); the desktop JVM these tests run on takes it as a literal. A pattern like that passes
 * every test and then throws when its class loads on a phone, so this reads the sources instead.
 * It looks at `Regex("...")` and `Regex("""...""")` literals, not patterns built by concatenation.
 */
class RegexPortabilityTest {
    private val tripleQuote = "\"\"\""

    private val literal =
        Regex("""Regex\(\s*(?:$tripleQuote(.*?)$tripleQuote|"((?:[^"\\]|\\.)*)")""", RegexOption.DOT_MATCHES_ALL)

    @Test
    fun `no regular expression has a closing brace that closes nothing`() {
        val root = File("src/main/java")
        assertTrue("run from the module folder: ${root.absolutePath}", root.isDirectory)
        val patterns =
            root
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file -> literal.findAll(file.readText()).map { file.name to patternOf(it) } }
                .toList()
        assertTrue("no regular expressions found to check", patterns.size > 10)

        val offenders = patterns.filter { (_, pattern) -> hasStrayClosingBrace(pattern) }.map { (name, pattern) -> "$name: $pattern" }

        assertTrue("Escape the closing brace (\\}) in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the check finds the pattern that crashed the git panel`() {
        assertTrue(hasStrayClosingBrace("""^stash@\{\d+}$"""))
        assertFalse(hasStrayClosingBrace("""^stash@\{\d+\}$"""))
        assertFalse(hasStrayClosingBrace("""^[0-9a-f]{4,64}$"""))
        assertFalse(hasStrayClosingBrace("""[^\s,;}]+"""))
    }

    private fun patternOf(match: MatchResult): String {
        val raw = match.groups[1]?.value
        // A quoted literal escapes its backslashes once more than the pattern it spells.
        return raw ?: match.groupValues[2].replace("\\\\", "\\")
    }

    private fun hasStrayClosingBrace(pattern: String): Boolean {
        var inClass = false
        var openQuantifiers = 0
        var index = 0
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index++
                '[' -> inClass = true
                ']' -> inClass = false
                '{' -> if (!inClass) openQuantifiers++
                '}' -> if (!inClass && --openQuantifiers < 0) return true
            }
            index++
        }
        return false
    }
}
