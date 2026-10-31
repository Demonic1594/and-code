package com.yugahashimoto.andcode.runtime.local

/**
 * Matching for "always allow" rules on shell commands.
 *
 * A rule names the first words of a command (`git status`, `npm test`). A plain text prefix is not
 * enough: `git status; rm -rf ~` starts with `git status`, and so does `git statusfoo`. So a command
 * that could run anything besides its first program (a separator, a pipe, a substitution, a
 * redirect, a line break or an escape) never matches any rule, and the rule's words have to equal
 * the command's first words exactly. Such a command is also never remembered as a rule. Quoting is
 * not parsed: a quoted `;` is refused too, which only costs an extra approval.
 */
internal object ShellCommandRules {
    /** How many leading words a remembered rule keeps. */
    private const val RULE_WORDS = 2

    private val unsafeCharacters = charArrayOf(';', '&', '|', '`', '$', '<', '>', '(', ')', '\\', '\n', '\r')

    /** True when [command] is one program with its arguments and nothing that chains or redirects. */
    fun isSimple(command: String): Boolean = command.isNotBlank() && command.none { it in unsafeCharacters }

    /** The rule to remember for [command], or null when it must not become a rule. */
    fun ruleFor(command: String): String? {
        if (!isSimple(command)) return null
        return words(command).take(RULE_WORDS).joinToString(" ").ifEmpty { null }
    }

    /** Whether [command] is covered by a rule naming [ruleWords]. */
    fun matches(
        ruleWords: String,
        command: String,
    ): Boolean {
        if (!isSimple(command)) return false
        val rule = words(ruleWords)
        val words = words(command)
        return rule.isNotEmpty() && words.size >= rule.size && words.subList(0, rule.size) == rule
    }

    private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
