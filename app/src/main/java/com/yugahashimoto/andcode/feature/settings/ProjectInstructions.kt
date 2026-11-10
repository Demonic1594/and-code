package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.feature.workspace.WorkspaceShell
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The instruction file an agent reads from a project's root, opened from that agent's settings so
 * the user can edit the rules it follows: [CLAUDE_FILE] for Claude Code, [AGENTS_FILE] for Codex and
 * OpenCode. The file is created when the project has none yet, because the editor only opens files
 * that exist. A new [AGENTS_FILE] starts as a copy of the project's [CLAUDE_FILE], if it has one:
 * OpenCode reads `CLAUDE.md` only while there is no `AGENTS.md`, so an empty one would hide those rules.
 */
internal object ProjectInstructions {
    const val CLAUDE_FILE = "CLAUDE.md"
    const val AGENTS_FILE = "AGENTS.md"

    /**
     * A shell command that creates [file] in [workspace] when it is missing (from [CLAUDE_FILE] for
     * [AGENTS_FILE], as the class says) and leaves it alone otherwise. A copy that fails is removed,
     * so the next try copies again instead of opening an empty file.
     */
    fun ensureExistsCommand(
        workspace: String,
        file: String = CLAUDE_FILE,
    ): String {
        val quoted = WorkspaceShell.quote(file)
        val create =
            if (file == AGENTS_FILE) {
                val claude = WorkspaceShell.quote(CLAUDE_FILE)
                "if [ -f $claude ]; then cp $claude $quoted || { rm -f $quoted; false; }; else : > $quoted; fi"
            } else {
                ": > $quoted"
            }
        return "cd ${WorkspaceShell.quote(workspace)} && { [ -e $quoted ] || { $create; }; }"
    }

    /** Creates [file] in [workspace] if needed; false when that failed, so the editor would find nothing. */
    suspend fun prepare(
        runner: LocalRuntimeCommandRunner,
        workspace: String,
        file: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { runner.runShell(ensureExistsCommand(workspace, file), PREPARE_TIMEOUT_SECONDS).exitCode == 0 }
                .getOrDefault(false)
        }

    private const val PREPARE_TIMEOUT_SECONDS = 15L
}
