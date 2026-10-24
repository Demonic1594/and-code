package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/** A git or file operation the explorer ran, for the confirmation it shows afterwards. */
enum class WorkspaceAction { COMMIT, PUSH, PULL, STASH, CHECKOUT, CREATE_BRANCH, DISCARD, SAVE }

/**
 * Git and file writes for a workspace on the on-device runtime, run through its shell.
 *
 * The runtimes only expose read access to the explorer (list, read, status, diff). Everything that
 * changes the workspace goes through here instead, as plain `git` and `sh` inside the same PRoot
 * environment the agents use, so it behaves exactly like the terminal would. Remote runtimes have no
 * such shell, which is why the explorer only offers these actions on this device.
 *
 * [run] executes one shell command with a timeout; it is blocking and is always called on IO.
 */
class WorkspaceShell(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val run: (command: String, timeoutSeconds: Long) -> LocalRuntimeCommandResult,
) {
    suspend fun branches(directory: String): Result<List<String>> =
        exec(git(directory, "branch --format='%(refname:short)'")).map { output ->
            output.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("(") }
        }

    suspend fun recentCommits(
        directory: String,
        limit: Int = RECENT_COMMIT_LIMIT,
    ): Result<List<CommitInfo>> = exec(git(directory, "log -n $limit --format='%h%x1f%s%x1f%an%x1f%cr'")).map(::parseCommits)

    suspend fun checkout(
        directory: String,
        branch: String,
    ): Result<String> = validBranch(branch)?.let { exec(git(directory, "checkout ${quote(it)} --")) } ?: invalidBranch()

    suspend fun createBranch(
        directory: String,
        name: String,
    ): Result<String> = validBranch(name)?.let { exec(git(directory, "checkout -b ${quote(it)}")) } ?: invalidBranch()

    /**
     * A name git could read as an option (`-f`, `--orphan`) is refused before it reaches the command
     * line. Quoting already stops the shell from interpreting it, but not git.
     */
    private fun validBranch(name: String): String? = name.trim().takeIf { it.isNotEmpty() && !it.startsWith("-") }

    private fun invalidBranch(): Result<String> = Result.failure(InvalidBranchException())

    /**
     * Throws away the uncommitted changes to one file, as VS Code's per-file "Discard Changes" does:
     * a tracked file goes back to its committed content (index and working tree), and a file git has
     * never seen is deleted. `--` keeps a path that starts with `-` from reading as an option.
     */
    suspend fun discard(
        directory: String,
        path: String,
    ): Result<String> {
        val quoted = quote(path)
        return exec(
            "cd ${quote(directory)} && if git ls-files --error-unmatch -- $quoted >/dev/null 2>&1; " +
                "then git restore --source=HEAD --staged --worktree -- $quoted; " +
                "else git clean -f -- $quoted; fi",
        )
    }

    /**
     * Stages everything and commits it. A fresh runtime has no git identity, and a commit there would
     * fail with "Please tell me who you are"; a placeholder identity is supplied only in that case,
     * so a configured one (from the GitHub sign-in or the terminal) always wins.
     */
    suspend fun commitAll(
        directory: String,
        message: String,
    ): Result<String> =
        exec(
            "cd ${quote(directory)} && git add -A -- $COMMIT_PATHSPEC && " +
                "if git config user.email >/dev/null 2>&1; then git commit -m ${quote(message)}; " +
                "else git -c user.name=AndCode -c user.email=andcode@localhost commit -m ${quote(message)}; fi",
        )

    /** `-u origin HEAD` so the first push of a new branch works without a separate upstream step. */
    suspend fun push(directory: String): Result<String> = exec(git(directory, "push -u origin HEAD"), NETWORK_TIMEOUT_SECONDS)

    /** Fast-forward only: a merge commit made silently from a phone is rarely what anyone wanted. */
    suspend fun pull(directory: String): Result<String> = exec(git(directory, "pull --ff-only"), NETWORK_TIMEOUT_SECONDS)

    suspend fun stash(directory: String): Result<String> = exec(git(directory, "stash push -u"))

    /**
     * Writes [content] to [path] as UTF-8. The text travels base64-encoded in chunks (one shell
     * argument has a hard size cap) into a temp file next to the target, which is then copied over it
     * with `cat` rather than moved, so the file keeps its mode (an executable script stays executable).
     */
    suspend fun writeFile(
        path: String,
        content: String,
    ): Result<String> {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) return Result.failure(FileTooLargeException())
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val temp = "$path.andcode-save"
        val chunks = if (encoded.isEmpty()) listOf("") else encoded.chunked(WRITE_CHUNK_CHARS)
        chunks.forEachIndexed { index, chunk ->
            val redirect = if (index == 0) ">" else ">>"
            val step = exec("printf '%s' ${quote(chunk)} | base64 -d $redirect ${quote(temp)}")
            if (step.isFailure) {
                exec("rm -f ${quote(temp)}")
                return step
            }
        }
        // The temp file goes either way: left behind, the next commit's `git add -A` would pick it up.
        return exec("cat ${quote(temp)} > ${quote(path)}; status=\$?; rm -f ${quote(temp)}; exit \$status")
    }

    private suspend fun exec(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): Result<String> =
        withContext(ioDispatcher) {
            val result = run(command, timeoutSeconds)
            if (result.exitCode == 0) {
                Result.success(result.output.trim())
            } else {
                Result.failure(WorkspaceShellException(result.output.trim().ifEmpty { "exit code ${result.exitCode}" }))
            }
        }

    companion object {
        const val MAX_WRITE_BYTES = 1024 * 1024
        private const val WRITE_CHUNK_CHARS = 48 * 1024
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val NETWORK_TIMEOUT_SECONDS = 120L
        private const val RECENT_COMMIT_LIMIT = 15
        private const val FIELD_SEPARATOR = '\u001f'

        /**
         * Everything in the repository except what AndCode itself drops into a workspace: attachments
         * copied in for the agent ([com.yugahashimoto.andcode.runtime.local.ATTACHMENT_DIRECTORY])
         * and a save's temp file. `:/` makes the add cover the whole repository, as a bare `-A` does,
         * even when the workspace is a subfolder of it.
         */
        private const val COMMIT_PATHSPEC = "':/' ':(exclude,glob)**/.andcode-attachments/**' ':(exclude,glob)**/*.andcode-save'"

        /** POSIX single-quoting: safe for any text, including quotes and newlines. */
        internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        internal fun git(
            directory: String,
            arguments: String,
        ): String = "git -C ${quote(directory)} $arguments"

        internal fun parseCommits(output: String): List<CommitInfo> =
            output.lines().mapNotNull { line ->
                val fields = line.split(FIELD_SEPARATOR)
                if (fields.size < 4) return@mapNotNull null
                CommitInfo(hash = fields[0], message = fields[1], author = fields[2], relativeTime = fields[3])
            }

        /** Files AndCode itself writes into a workspace; commits leave them out (see [COMMIT_PATHSPEC]). */
        fun isAndCodeArtifact(path: String): Boolean = path.split('/').contains(".andcode-attachments") || path.endsWith(".andcode-save")

        /** Where [filePath] lives in the guest: absolute as given, otherwise under [workspacePath]. */
        fun guestPath(
            workspacePath: String,
            filePath: String,
        ): String = if (filePath.startsWith("/")) filePath else workspacePath.trimEnd('/') + "/" + filePath.removePrefix("./")
    }
}

/** The command's own output, e.g. git's error, which is the most useful thing to show the user. */
class WorkspaceShellException(message: String) : Exception(message)

/** A branch name git would read as an option; the UI shows its own localized message. */
class InvalidBranchException : Exception("Invalid branch name")

/** The edited text is over [WorkspaceShell.MAX_WRITE_BYTES]; the UI shows its own localized message. */
class FileTooLargeException : Exception("File is too large to save from here")
