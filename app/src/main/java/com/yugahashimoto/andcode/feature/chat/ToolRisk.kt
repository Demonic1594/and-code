package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PermissionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How much harm a tool call can do, worst last, so an approval says at a glance what it risks. */
enum class ToolRisk {
    /** Looks at files or the web and changes nothing. */
    READ_ONLY,

    /** Creates or changes files in the project. */
    WRITING,

    /** Runs a program, which can do anything the sandbox allows. */
    EXECUTING,

    /** Runs a command known to delete, overwrite or rewrite history for good. */
    DESTRUCTIVE,
}

/**
 * Sorts tool calls into [ToolRisk] classes, for Claude Code's tool names and OpenCode's permissions
 * alike. Best effort: [ToolRisk.DESTRUCTIVE] names commands known to destroy, and [ToolRisk.EXECUTING]
 * is not "safe" - a program can do anything the sandbox allows. Errors lean towards destructive.
 */
object ToolRisks {
    private val readOnly =
        setOf("read", "grep", "glob", "ls", "list", "notebookread", "webfetch", "websearch", "todoread", "todowrite")
    private val writing = setOf("write", "edit", "multiedit", "notebookedit", "patch")

    /** Shell words that come before a command without being one: `then rm -rf x`, `! rm x`. */
    private val shellWords = setOf("then", "do", "else", "elif", "if", "while", "until", "!", "$")

    /** Programs that only run the program after them, which is the one that counts. */
    private val launchers = setOf("sudo", "doas", "env", "nohup", "time", "nice", "command", "exec", "xargs", "timeout", "stdbuf")
    private val sql = Regex("""\b(drop\s+(table|database|schema)|truncate\s+(table\s+)?\w+|delete\s+from)\b""", RegexOption.IGNORE_CASE)

    fun of(
        toolName: String,
        command: String?,
    ): ToolRisk {
        val name = toolName.lowercase()
        return when {
            name in readOnly -> ToolRisk.READ_ONLY
            name in writing -> ToolRisk.WRITING
            command != null && isDestructive(command) -> ToolRisk.DESTRUCTIVE
            else -> ToolRisk.EXECUTING
        }
    }

    /** The class of an approval as the chat shows it: Claude Code's tool and input, or OpenCode's permission. */
    fun of(permission: PermissionRequest): ToolRisk {
        val toolName = (permission.metadata["toolName"] as? JsonPrimitive)?.content ?: permission.permission
        val input = permission.metadata["toolInput"] as? JsonObject
        val command = commandText(input?.get("command")) ?: commandText(permission.metadata["command"])
        if (command != null) return of(toolName, command)
        // OpenCode lists one pattern per command of a chain: each is classed alone, the worst counts.
        val patterns = permission.patterns.takeIf { toolName.equals("bash", ignoreCase = true) }.orEmpty()
        return patterns.maxOfOrNull { of(toolName, it) } ?: of(toolName, null)
    }

    /** A command as text: a string, or an argv array (as some Codex versions send it). */
    private fun commandText(element: JsonElement?): String? =
        when {
            element is JsonPrimitive && element.isString -> element.content
            element is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" ")
            else -> null
        }

    /**
     * Whether [command] destroys something for good. Quotes, backticks and `$(` are read as
     * separators too, so a command inside `bash -c "..."` or `ssh host '...'` is looked at as well.
     */
    fun isDestructive(command: String): Boolean {
        if (sql.containsMatchIn(command)) return true
        return command
            .split(Regex("""[;|&\n'"`(){}]"""))
            .map { segment -> segment.trim().split(Regex("""\s+""")).filter(String::isNotEmpty) }
            .any(::isDestructiveCall)
    }

    private fun isDestructiveCall(words: List<String>): Boolean {
        val start = words.indexOfFirst { it !in shellWords && !it.contains('=') }
        if (start < 0) return false
        // A launcher's own options and values come before its command (`sudo -u root rm`, `xargs -0
        // rm`, `timeout 5 rm`): any later word may be the program, so each one is tried.
        if (programOf(words[start]) in launchers) return (start + 1 until words.size).any { isDestructiveAt(words, it) }
        return isDestructiveAt(words, start)
    }

    /** Whether running [words] from index [at] as program and arguments destroys something. */
    private fun isDestructiveAt(
        words: List<String>,
        at: Int,
    ): Boolean {
        val program = programOf(words[at])
        val args = words.drop(at + 1)
        return when (program) {
            "rm" -> args.any { shortFlags(it, "rRf") || it == "--recursive" || it == "--force" }
            "find" -> "-delete" in args || args.zipWithNext().any { (a, b) -> a.startsWith("-exec") && programOf(b) == "rm" }
            "git" -> isDestructiveGit(args)
            "dd" -> args.any { it.startsWith("of=") }
            "shred", "wipefs", "truncate" -> true
            "chmod", "chown", "chgrp" -> args.any { shortFlags(it, "R") || it == "--recursive" }
            else -> program.startsWith("mkfs")
        }
    }

    private fun isDestructiveGit(args: List<String>): Boolean {
        // Skip git's own options before the subcommand: `git -C dir push -f`.
        var i = 0
        while (i < args.size && args[i].startsWith("-")) i += if (args[i] == "-C" || args[i] == "-c") 2 else 1
        val sub = args.getOrNull(i) ?: return false
        return gitRules[sub]?.invoke(args.drop(i + 1)) ?: false
    }

    /** For each git subcommand that can lose work for good, whether its arguments do. */
    private val gitRules: Map<String, (List<String>) -> Boolean> =
        mapOf(
            "push" to { rest -> rest.any(::destroysOnPush) },
            "reset" to { rest -> "--hard" in rest },
            "clean" to { rest -> rest.any { shortFlags(it, "f") || it == "--force" } },
            "branch" to { rest ->
                "-D" in rest || (rest.any { it == "-d" || it == "--delete" } && rest.any { shortFlags(it, "f") || it == "--force" })
            },
            "checkout" to { rest -> "." in rest },
            "restore" to { rest -> "." in rest && "--staged" !in rest },
            "stash" to { rest -> rest.firstOrNull() in setOf("drop", "clear") },
        )

    /** A push argument that overwrites or deletes on the remote: a force, a delete, a mirror, `+ref` or `:ref`. */
    private fun destroysOnPush(arg: String): Boolean =
        shortFlags(arg, "fd") || arg.startsWith("--force") || arg == "--delete" || arg == "--mirror" ||
            (!arg.startsWith("-") && (arg.startsWith("+") || arg.startsWith(":")))

    /** A cluster of one-letter flags (`-rf`, `-fu`) holding any of [letters]; not a `--long` one. */
    private fun shortFlags(
        word: String,
        letters: String,
    ): Boolean = word.length > 1 && word[0] == '-' && word[1] != '-' && word.drop(1).all(Char::isLetter) && word.any { it in letters }

    /** The program a word names: `/bin/rm` and `\\rm` are `rm`. */
    private fun programOf(word: String): String = word.substringAfterLast('/').trimStart('\\').lowercase()
}
