package com.yugahashimoto.andcode.runtime.local

/**
 * Matching for "always allow" rules on shell commands.
 *
 * A rule names the first words of a command (`git status`, `npm test`). A plain text prefix is not
 * enough: `git status; rm -rf ~` starts with `git status`, and so does `git statusfoo`. So a command
 * that could run anything besides its first program (a separator, a pipe, a substitution, a
 * redirect, a line break or an escape) never matches any rule, and the rule's words have to equal
 * the command's first words exactly. A command that starts with a program that runs other code
 * (`bash -c ...`, `env`, `xargs`, an interpreter) is treated the same way. Such a command is also
 * never remembered as a rule. Quoting is not parsed: a quoted `;` is refused too, which only costs an
 * extra approval. The guest-side hook (`and-code-claude-permission-hook.sh`) applies the same rules.
 *
 * Blocking rules ([blocks]) go the other way: they are checked first and must err toward refusing.
 * Any chained, piped or substituted part of the command that runs the rule's program (its path
 * ignored) with all of the rule's flags, in any order, and its other words in order, other words
 * between them allowed, is refused: `git push --force` also catches `cd x && git push origin
 * --force`, and `rm -rf` catches `rm -fr` and `rm -r -f`, since a group of short flags counts as
 * each of them. Line continuations, quotes and escapes are taken out. A flag must be spelled the same: `--force` is not
 * `-f`.
 */
internal object ShellCommandRules {
    /** How many leading words a remembered rule keeps. */
    private const val RULE_WORDS = 2

    private val unsafeCharacters = charArrayOf(';', '&', '|', '`', '$', '<', '>', '(', ')', '!', '\\', '\n', '\r')

    /** Programs that run another command or code given as arguments; keep in step with the hook. */
    private val wrappers =
        setOf(
            "bash",
            "sh",
            "zsh",
            "dash",
            "ash",
            "ksh",
            "fish",
            "busybox",
            "env",
            "xargs",
            "sudo",
            "su",
            "doas",
            "eval",
            "exec",
            "command",
            "time",
            "nohup",
            "timeout",
            "nice",
            "setsid",
            "stdbuf",
            "python",
            "node",
            "nodejs",
            "npx",
            "bun",
            "deno",
            "perl",
            "ruby",
            "php",
            "lua",
            "awk",
            "gawk",
        )

    private val versionSuffix = Regex("[-.0-9]+$")

    /** An environment assignment or a quoted name: the program actually run is something else. */
    private val notAProgramName = charArrayOf('=', '"', '\'')

    private val whitespace = Regex("\\s+")

    /** Characters that end one command part and start another, or wrap one; keep in step with the hook. */
    private val commandBreaks = Regex("[;&|`(){}<>!$\\n\\r]")

    /** A backslash ending a line joins it to the next, so the command reads on across the break. */
    private val lineContinuation = Regex("\\\\\r?\n")

    /** Quoting that can hide a word (`"rm"`, `r\m`) from a plain comparison. */
    private val quoting = Regex("[\"'\\\\]")

    /** True when [command] is one program with its arguments and nothing that chains or redirects. */
    fun isSimple(command: String): Boolean =
        command.isNotBlank() &&
            command.none { it in unsafeCharacters } &&
            !runsOtherCode(words(command).firstOrNull())

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

    /**
     * The blocking rule to keep for what the user typed, or null when it names nothing or more than
     * one command (`a && b` would make one rule that matches neither).
     */
    fun blockRuleFor(text: String): String? = commandParts(text).filter { it.isNotEmpty() }.singleOrNull()?.joinToString(" ")

    /**
     * Whether a blocking rule naming [ruleWords] refuses [command]: some part of the command runs the
     * rule's program (`/bin/rm` is `rm`) with the rule's flags and, in order, its other words.
     */
    fun blocks(
        ruleWords: String,
        command: String,
    ): Boolean {
        val rule = commandParts(ruleWords).flatten()
        if (rule.isEmpty()) return false
        val program = programName(rule.first())
        val wanted = Arguments.of(rule.drop(1))
        return commandParts(command).any { words ->
            words.indices.any { start ->
                programName(words[start]) == program && wanted.foundIn(Arguments.of(words.subList(start + 1, words.size)))
            }
        }
    }

    /** A command's arguments: its flags as a set (`-rf` is `-r` and `-f`) and its other words in order. */
    private class Arguments(
        val flags: Set<String>,
        val plain: List<String>,
    ) {
        fun foundIn(other: Arguments): Boolean = other.flags.containsAll(flags) && inOrder(plain, other.plain)

        companion object {
            fun of(words: List<String>): Arguments {
                val flags = mutableSetOf<String>()
                val plain = mutableListOf<String>()
                for (word in words) {
                    when {
                        word.startsWith("--") -> flags += word
                        word.length > 1 && word.startsWith("-") -> word.drop(1).forEach { flags += "-$it" }
                        else -> plain += word
                    }
                }
                return Arguments(flags, plain)
            }
        }
    }

    /** Whether [wanted] appears in [words] in the same order, other words between them allowed. */
    private fun inOrder(
        wanted: List<String>,
        words: List<String>,
    ): Boolean {
        var next = 0
        for (word in words) {
            if (next < wanted.size && word == wanted[next]) next++
        }
        return next == wanted.size
    }

    /** [text] split where one command part ends, each part's words with all quoting removed. */
    private fun commandParts(text: String): List<List<String>> =
        text.replace(lineContinuation, "").split(commandBreaks).map { part ->
            words(part).map { it.replace(quoting, "") }.filter { it.isNotEmpty() }
        }

    private fun programName(word: String): String = word.substringAfterLast('/')

    /**
     * Whether a command's first word may run something other than itself: an environment assignment
     * (`FOO=1 sh -c ...`), a quoted program name, or a wrapper by its name (path and version suffix
     * removed, so `/bin/sh` and `python3.12` count).
     */
    private fun runsOtherCode(first: String?): Boolean {
        if (first == null || first.any { it in notAProgramName }) return true
        return first.substringAfterLast('/').replace(versionSuffix, "") in wrappers
    }

    private fun words(text: String): List<String> = text.trim().split(whitespace).filter { it.isNotEmpty() }
}
