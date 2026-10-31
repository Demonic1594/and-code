package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.feature.workspace.WorkspaceShell
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * Snapshots of a workspace that belong to the app, not to the project: a separate git history kept
 * under the guest's home (never inside the workspace, so neither the agent's tools nor the user's
 * own git see it), pointed at the workspace with `--work-tree`.
 *
 * Taken before each turn of an agent that has no checkpoints of its own, they give every on-device
 * agent two things only OpenCode had: the files a turn changed, line by line, in a folder that is
 * not a git repository; and putting the files back to how they were before a message.
 *
 * Bulky regenerable folders (dependencies, build output) are excluded, and every command has a
 * time limit, so a huge workspace skips the snapshot instead of holding up the turn.
 */
class ShadowSnapshots(
    private val launch: (command: String, timeoutSeconds: Long, maxOutputCharacters: Int) -> LocalRuntimeCommandResult,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where the histories live; the guest's home in the app, a temporary folder in tests. */
    private val root: String = ROOT,
) {
    /**
     * One operation at a time, across every chat: a snapshot taken while a restore is rewriting the
     * files (a message sent from another chat on the same folder) would record half of each, and the
     * two would fight over git's index lock.
     */
    private val lock = Mutex()

    /**
     * Runs one command. Starting the shell can throw (no runtime yet, no temp file); a snapshot is
     * best effort and must never fail the message it was taken for, so that becomes a failed result.
     */
    private fun run(
        command: String,
        timeoutSeconds: Long,
        maxOutputCharacters: Int,
    ): LocalRuntimeCommandResult =
        try {
            launch(command, timeoutSeconds, maxOutputCharacters)
        } catch (error: Exception) {
            LocalRuntimeCommandResult(exitCode = -1, output = error.message.orEmpty())
        }

    /** Snapshots [workspace] as it is now and returns the snapshot's id, or null when it could not. */
    suspend fun snapshot(workspace: String): String? = lock.withLock { snapshotUnlocked(workspace) }

    /**
     * Like [snapshot], but gives up when another operation (a restore, from another chat) still holds
     * the history after [lockWaitMillis]: a message must not wait that long for its restore point.
     */
    suspend fun snapshot(
        workspace: String,
        lockWaitMillis: Long,
    ): String? {
        withTimeoutOrNull(lockWaitMillis) { lock.lock() } ?: return null
        return try {
            snapshotUnlocked(workspace)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun snapshotUnlocked(workspace: String): String? =
        withContext(ioDispatcher) {
            val result = run(snapshotScript(root, workspace), SNAPSHOT_TIMEOUT_SECONDS, SMALL_OUTPUT)
            result.output.trim().lines().lastOrNull()?.trim()?.takeIf { result.exitCode == 0 && HASH.matches(it) }
        }

    /** What changed from snapshot [from] to snapshot [to], one entry per file. */
    suspend fun diff(
        workspace: String,
        from: String,
        to: String,
    ): List<OpenCodeFileChange>? =
        lock.withLock {
            withContext(ioDispatcher) {
                if (!HASH.matches(from) || !HASH.matches(to)) return@withContext null
                val result = run("${git(root, workspace)} diff --no-color -U3 -M $from $to", DIFF_TIMEOUT_SECONDS, DIFF_OUTPUT)
                if (result.exitCode != 0 || result.truncated) null else ClaudeWorkspaceGit.parseDiff(result.output)
            }
        }

    /**
     * Puts the files of [workspace] back to snapshot [target]: changed files get their old content,
     * files added since are removed. Excluded folders are not touched. The state just before is
     * snapshotted first and returned, so the restore can itself be undone; null means nothing changed.
     */
    suspend fun restore(
        workspace: String,
        target: String,
    ): String? {
        if (!HASH.matches(target)) return null
        return lock.withLock { restoreUnlocked(workspace, target) }
    }

    private suspend fun restoreUnlocked(
        workspace: String,
        target: String,
    ): String? {
        val before = snapshotUnlocked(workspace) ?: return null
        return withContext(ioDispatcher) {
            // HEAD is the snapshot just taken, so checkout knows which files are new since [target]
            // and removes them. It refuses, changing nothing, rather than delete an ignored or excluded
            // file in its way (a folder that must become a file again, say), which read-tree would.
            val result =
                run(
                    "${git(root, workspace)} checkout -q --no-overwrite-ignore --detach $target",
                    RESTORE_TIMEOUT_SECONDS,
                    SMALL_OUTPUT,
                )
            before.takeIf { result.exitCode == 0 }
        }
    }

    companion object {
        /** Under the guest's home, outside every workspace. */
        const val ROOT = "/root/.local/share/and-code/snapshots"

        private const val SNAPSHOT_TIMEOUT_SECONDS = 20L
        private const val DIFF_TIMEOUT_SECONDS = 20L
        private const val RESTORE_TIMEOUT_SECONDS = 30L
        private const val SMALL_OUTPUT = 4_000
        private const val DIFF_OUTPUT = 400_000
        private val HASH = Regex("^[0-9a-f]{40}$")

        /** Folders that are rebuilt rather than written, kept out of every snapshot. */
        internal val EXCLUDED =
            listOf(
                "node_modules/",
                ".gradle/",
                "build/",
                "dist/",
                "target/",
                ".venv/",
                "venv/",
                "__pycache__/",
                ".next/",
                ".nuxt/",
                ".cache/",
                ".andcode-attachments/",
                "*.andcode-save",
            )

        /** One history per workspace, named by a hash of its path. */
        internal fun gitDir(
            root: String,
            workspace: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(workspace.toByteArray())
            return "$root/" + digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** Git aimed at the shadow history and the workspace, with the user's hooks and signing out of the way. */
        internal fun git(
            root: String,
            workspace: String,
        ): String =
            "git --git-dir=${WorkspaceShell.quote(gitDir(root, workspace))} --work-tree=${WorkspaceShell.quote(workspace)}" +
                " -c core.quotepath=off -c core.hooksPath=/dev/null -c commit.gpgsign=false" +
                // Snapshots left behind by a restore are on no branch; never let gc prune them.
                " -c gc.auto=0" +
                " -c user.name=AndCode -c user.email=andcode@localhost"

        internal fun snapshotScript(
            root: String,
            workspace: String,
        ): String {
            val dir = WorkspaceShell.quote(gitDir(root, workspace))
            val exclude = EXCLUDED.joinToString(" ") { WorkspaceShell.quote(it) }
            return listOf(
                "set -e",
                // A lock left by a command that was killed at its time limit would fail every later one.
                "find $dir -maxdepth 1 -name index.lock -mmin +1 -delete 2>/dev/null || true",
                "if [ ! -d $dir ]; then mkdir -p $dir && git init -q --bare $dir && git --git-dir=$dir config core.bare false" +
                    " && printf '%s\\n' $exclude > $dir/info/exclude; fi",
                // `:/` is the top of the work tree, wherever the shell happens to be.
                "${git(root, workspace)} add -A -- ':/'",
                "${git(root, workspace)} commit -q --allow-empty --no-verify -m snapshot",
                "${git(root, workspace)} rev-parse HEAD",
            ).joinToString("\n")
        }
    }
}
