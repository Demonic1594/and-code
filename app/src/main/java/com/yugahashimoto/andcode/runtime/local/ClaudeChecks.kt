package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Commands the user has Claude Code run in the project after it edits a file, or before it ends a
 * turn. Each is kept as `checks/<kind>.sh` in the permission bridge, which the guest sees, and run
 * by the permission hook script's `--check` mode: a failing check's output goes back to Claude Code,
 * which carries on to fix it. Claude Code reads its hooks when it starts, so a check being set or
 * removed starts each chat's process anew; a command edited in place is read when it runs.
 */
class ClaudeChecks(
    root: File,
) {
    enum class Kind(
        val fileName: String,
    ) {
        /** After every Write, Edit, MultiEdit or NotebookEdit: a linter or a type check. */
        AFTER_EDIT("after-edit"),

        /** When Claude Code is about to end its turn: the build or the tests. */
        BEFORE_FINISH("before-finish"),
    }

    private val dir = File(root, DIR)
    private val mutableCommands = MutableStateFlow(read())

    /** The command set for each kind; a kind with none is absent. */
    val commands: StateFlow<Map<Kind, String>> = mutableCommands.asStateFlow()

    /** Sets [kind]'s command, or removes it when [command] is blank. */
    @Synchronized
    fun set(
        kind: Kind,
        command: String,
    ) {
        val file = File(dir, "${kind.fileName}.sh")
        runCatching {
            if (command.isBlank()) {
                file.delete()
            } else {
                dir.mkdirs()
                // Written aside and renamed in, so the hook never runs half a command.
                val written = File(dir, ".${kind.fileName}.tmp")
                written.writeText(command.trim() + "\n")
                if (!written.renameTo(file)) {
                    file.writeText(command.trim() + "\n")
                    written.delete()
                }
            }
        }
        mutableCommands.value = read()
    }

    private fun read(): Map<Kind, String> =
        Kind.entries
            .mapNotNull { kind ->
                runCatching { File(dir, "${kind.fileName}.sh").takeIf(File::isFile)?.readText()?.trim() }
                    .getOrNull()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { kind to it }
            }.toMap()

    companion object {
        private const val DIR = "checks"

        /** Long enough for a test suite on a phone; Claude Code's own default is a minute. */
        private const val TIMEOUT_SEC = 600

        /** The tools whose calls change files, as Claude Code names them. */
        private const val EDIT_TOOLS = "Write|Edit|MultiEdit|NotebookEdit"

        /**
         * The `hooks` part of Claude Code's settings that runs [kinds]: a PostToolUse hook on the
         * editing tools for [Kind.AFTER_EDIT], a Stop hook for [Kind.BEFORE_FINISH]. Null for none.
         * Hooks given with `--settings` run alongside the user's own.
         */
        fun hooks(kinds: Set<Kind>): JsonObject? {
            if (kinds.isEmpty()) return null

            fun group(
                kind: Kind,
                matcher: String?,
            ): JsonArray {
                val hook =
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("command"),
                            "command" to JsonPrimitive("${ClaudePermissionHooks.HOOK_GUEST_PATH} --check ${kind.fileName}"),
                            "timeout" to JsonPrimitive(TIMEOUT_SEC),
                        ),
                    )
                val entry =
                    buildMap {
                        matcher?.let { put("matcher", JsonPrimitive(it)) }
                        put("hooks", JsonArray(listOf(hook)))
                    }
                return JsonArray(listOf(JsonObject(entry)))
            }
            return JsonObject(
                buildMap {
                    if (Kind.AFTER_EDIT in kinds) put("PostToolUse", group(Kind.AFTER_EDIT, EDIT_TOOLS))
                    if (Kind.BEFORE_FINISH in kinds) put("Stop", group(Kind.BEFORE_FINISH, null))
                },
            )
        }
    }
}
