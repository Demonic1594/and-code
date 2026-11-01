package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.feature.workspace.WorkspaceShell
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The instruction file Claude Code reads from a project's root, opened from its settings so the
 * user can edit the rules it follows. The file is created empty when the project has none yet,
 * because the editor only opens files that exist.
 */
internal object ProjectInstructions {
    const val CLAUDE_FILE = "CLAUDE.md"

    /** A shell command that creates [file] in [workspace] when it is missing and leaves it alone otherwise. */
    fun ensureExistsCommand(
        workspace: String,
        file: String = CLAUDE_FILE,
    ): String {
        val quoted = WorkspaceShell.quote(file)
        return "cd ${WorkspaceShell.quote(workspace)} && { [ -e $quoted ] || : > $quoted; }"
    }

    /** Creates the file in [workspace] if needed; false when that failed, so the editor would find nothing. */
    suspend fun prepare(
        runner: LocalRuntimeCommandRunner,
        workspace: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { runner.runShell(ensureExistsCommand(workspace), PREPARE_TIMEOUT_SECONDS).exitCode == 0 }
                .getOrDefault(false)
        }

    private const val PREPARE_TIMEOUT_SECONDS = 15L
}
