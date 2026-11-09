package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.feature.workspace.WorkspaceShell
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The instruction file an agent reads from a project's root, opened from that agent's settings so
 * the user can edit the rules it follows: [CLAUDE_FILE] for Claude Code, [AGENTS_FILE] for Codex and
 * OpenCode. The file is created empty when the project has none yet, because the editor only opens
 * files that exist.
 */
internal object ProjectInstructions {
    const val CLAUDE_FILE = "CLAUDE.md"
    const val AGENTS_FILE = "AGENTS.md"

    /** A shell command that creates [file] in [workspace] when it is missing and leaves it alone otherwise. */
    fun ensureExistsCommand(
        workspace: String,
        file: String = CLAUDE_FILE,
    ): String {
        val quoted = WorkspaceShell.quote(file)
        return "cd ${WorkspaceShell.quote(workspace)} && { [ -e $quoted ] || : > $quoted; }"
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
