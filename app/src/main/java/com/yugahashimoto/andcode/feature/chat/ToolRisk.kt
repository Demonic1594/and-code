package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PermissionRequest
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

/** Sorts tool calls into [ToolRisk] classes, for Claude Code's tool names and OpenCode's permissions alike. */
object ToolRisks {
    private val readOnly =
        setOf("read", "grep", "glob", "ls", "list", "notebookread", "webfetch", "websearch", "todoread", "todowrite")
    private val writing = setOf("write", "edit", "multiedit", "notebookedit", "patch")

    /**
     * Commands that delete or overwrite without a way back. Matched anywhere in the command, chained
     * parts included, since `cd x && rm -rf .` is as destructive as `rm -rf .`.
     */
    private val destructive =
        listOf(
            Regex("""(^|[\s;&|(])rm\s+(-[^\s]*[rRf][^\s]*\s+|--(recursive|force)\s+)"""),
            Regex("""git\s+push\b[^;&|]*(\s-f\b|\s--force\b|\s--force-with-lease\b|\s\+\S)"""),
            Regex("""git\s+reset\b[^;&|]*\s--hard\b"""),
            Regex("""git\s+clean\b[^;&|]*\s-[^\s]*f"""),
            Regex("""git\s+branch\b[^;&|]*\s-D\b"""),
            Regex("""git\s+checkout\b[^;&|]*\s--\s+\."""),
            Regex("""(^|[\s;&|(])(mkfs(\.\w+)?|shred|wipefs)\b"""),
            Regex("""(^|[\s;&|(])dd\s+[^;&|]*\bof="""),
            Regex("""(^|[\s;&|(])(chmod|chown)\s+-[^\s]*R"""),
            Regex("""\bdrop\s+(table|database|schema)\b""", RegexOption.IGNORE_CASE),
        )

    fun of(
        toolName: String,
        command: String?,
    ): ToolRisk {
        val name = toolName.lowercase()
        return when {
            name in readOnly -> ToolRisk.READ_ONLY
            name in writing -> ToolRisk.WRITING
            command != null && destructive.any { it.containsMatchIn(command) } -> ToolRisk.DESTRUCTIVE
            else -> ToolRisk.EXECUTING
        }
    }

    /** The class of an approval as the chat shows it: Claude Code's tool and input, or OpenCode's permission. */
    fun of(permission: PermissionRequest): ToolRisk {
        val toolName = (permission.metadata["toolName"] as? JsonPrimitive)?.content ?: permission.permission
        val input = permission.metadata["toolInput"] as? JsonObject
        val command =
            (input?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: (permission.metadata["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: permission.patterns.takeIf { toolName.equals("bash", ignoreCase = true) }?.joinToString(" ")
        return of(toolName, command)
    }
}
