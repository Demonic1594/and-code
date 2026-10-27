package com.yugahashimoto.andcode.feature.git

import com.yugahashimoto.andcode.feature.workspace.WorkspaceShell
import com.yugahashimoto.andcode.feature.workspace.WorkspaceShellException
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One changed path, as `git status` reports it. */
data class GitFileChange(
    val path: String,
    /** The path before a rename or copy, else null. */
    val originalPath: String? = null,
    /** The staged side: `M`, `A`, `D`, `R`, `C`, `U`, or ' ' for none. `?` for untracked. */
    val index: Char,
    /** The working-tree side, same letters. */
    val worktree: Char,
) {
    val untracked: Boolean get() = index == '?'
    val conflicted: Boolean get() = index == 'U' || worktree == 'U' || (index == 'A' && worktree == 'A') || (index == 'D' && worktree == 'D')
    val staged: Boolean get() = !untracked && !conflicted && index != ' '
    val unstaged: Boolean get() = untracked || conflicted || worktree != ' '
}

data class GitStatus(
    /** Null on a detached HEAD. */
    val branch: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    /** True before the first commit, when there is no HEAD to compare with. */
    val noCommits: Boolean,
    val changes: List<GitFileChange>,
) {
    val staged: List<GitFileChange> get() = changes.filter { it.staged }
    val unstaged: List<GitFileChange> get() = changes.filter { it.unstaged }
}

data class GitCommit(
    val hash: String,
    val shortHash: String,
    val parents: List<String>,
    val author: String,
    val email: String,
    /** Seconds since the epoch. */
    val time: Long,
    /** Branch and tag names pointing at it, as `git log --decorate` lists them. */
    val refs: List<String>,
    val subject: String,
)

data class GitBranch(
    val name: String,
    val remote: Boolean,
    val current: Boolean,
    val upstream: String?,
    /** `[ahead 1, behind 2]`, `[gone]`, or empty. */
    val track: String,
    val shortHash: String,
    val relativeDate: String,
)

data class GitStash(
    /** `stash@{0}`. */
    val ref: String,
    val message: String,
    val relativeDate: String,
)

data class GitCommitFile(
    /** `M`, `A`, `D`, `R`… as `--name-status` gives it. */
    val status: Char,
    val path: String,
    val originalPath: String? = null,
)

data class GitCommitDetails(
    val commit: GitCommit,
    val body: String,
    val files: List<GitCommitFile>,
)

/**
 * Git for the Source Control screen, run through the on-device runtime's shell like
 * [WorkspaceShell]. Paths from `git status` are relative to the repository root, so every pathspec is
 * written `:(top)path`, which reads it that way from any folder inside the repository.
 */
class GitClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val run: (command: String, timeoutSeconds: Long) -> LocalRuntimeCommandResult,
) {
    suspend fun status(directory: String): Result<GitStatus> =
        exec(git(directory, "status --porcelain=v1 -b -z --untracked-files=all"), trim = false).map(::parseStatus)

    /**
     * The diff of one file: what is staged when [staged], what is not otherwise. A file git has never
     * seen has no diff of its own, so it is compared with nothing to show it whole.
     */
    suspend fun diff(
        directory: String,
        change: GitFileChange,
        staged: Boolean,
    ): Result<String> {
        // A rename's old path goes in too, so git pairs the two and shows the edit, not a new file.
        val paths = listOfNotNull(change.path, change.originalPath).joinToString(" ") { top(it) }
        val command =
            when {
                change.untracked ->
                    // --no-index exits 1 whenever there is a difference, which is always here; any
                    // other failure (a file that vanished, say) is checked for first and fails.
                    "cd ${quote(directory)} && cd \"\$(git rev-parse --show-toplevel)\" && test -f ${quote(change.path)} && " +
                        "{ git diff --no-color --no-ext-diff --no-index -- /dev/null ${quote(change.path)}; [ \$? -eq 1 ]; }"
                staged -> git(directory, "diff --no-color --no-ext-diff --cached -M -- $paths")
                else -> git(directory, "diff --no-color --no-ext-diff -- $paths")
            }
        return exec(command, trim = false)
    }

    suspend fun stage(
        directory: String,
        paths: List<String>,
    ): Result<String> =
        if (paths.isEmpty()) Result.success("") else exec(git(directory, "add -A -- ${paths.joinToString(" ") { top(it) }}"))

    /**
     * Takes [paths] out of the index; before the first commit there is no HEAD to reset to, so
     * `rm --cached` does it, forced so a file edited since it was staged is unstaged too (only the
     * index is touched).
     */
    suspend fun unstage(
        directory: String,
        paths: List<String>,
        noCommits: Boolean,
    ): Result<String> {
        if (paths.isEmpty()) return Result.success("")
        val specs = paths.joinToString(" ") { top(it) }
        return exec(git(directory, if (noCommits) "rm -r -q -f --cached -- $specs" else "restore --staged -- $specs"))
    }

    suspend fun stageAll(directory: String): Result<String> = exec(git(directory, "add -A -- $ALL_PATHS"))

    suspend fun unstageAll(
        directory: String,
        noCommits: Boolean,
    ): Result<String> = exec(git(directory, if (noCommits) "rm -r -q -f --cached -- ':/'" else "reset -q"))

    /**
     * Commits what is staged. As in [WorkspaceShell.commitAll], a runtime with no git identity gets a
     * placeholder one for this commit only, so a configured identity always wins.
     */
    suspend fun commit(
        directory: String,
        message: String,
        amend: Boolean,
    ): Result<String> {
        val flags = (if (amend) "--amend " else "") + "-m ${quote(message)}"
        return exec(
            "cd ${quote(directory)} && if git config user.email >/dev/null 2>&1; then git commit $flags; " +
                "else git -c user.name=AndCode -c user.email=andcode@localhost commit $flags; fi",
        )
    }

    /** The last commit's message, for amending it. */
    suspend fun lastCommitMessage(directory: String): Result<String> = exec(git(directory, "log -1 --format=%B"))

    suspend fun log(
        directory: String,
        limit: Int = LOG_PAGE,
        skip: Int = 0,
    ): Result<List<GitCommit>> = exec(git(directory, "log --skip=$skip -n $limit --format='$LOG_FORMAT'"), trim = false).map(::parseLog)

    suspend fun commitDetails(
        directory: String,
        hash: String,
    ): Result<GitCommitDetails> {
        val sha = validHash(hash) ?: return Result.failure(WorkspaceShellException("bad commit"))
        val header = exec(git(directory, "show -s --format='$LOG_FORMAT' $sha"), trim = false).getOrElse { return Result.failure(it) }
        val body = exec(git(directory, "show -s --format=%b $sha")).getOrDefault("")
        val files = exec(git(directory, "show --format= --name-status -M -z --first-parent $sha"), trim = false).getOrDefault("")
        val commit = parseLog(header).firstOrNull() ?: return Result.failure(WorkspaceShellException("bad commit"))
        return Result.success(GitCommitDetails(commit, body.trim(), parseNameStatus(files)))
    }

    /** One file's change in one commit; for a merge, against its first parent. */
    suspend fun commitFileDiff(
        directory: String,
        hash: String,
        path: String,
        originalPath: String? = null,
    ): Result<String> {
        val sha = validHash(hash) ?: return Result.failure(WorkspaceShellException("bad commit"))
        val paths = listOfNotNull(path, originalPath).joinToString(" ") { top(it) }
        return exec(git(directory, "show --no-color --no-ext-diff --format= -M --first-parent $sha -- $paths"), trim = false)
    }

    suspend fun branches(directory: String): Result<List<GitBranch>> =
        exec(
            git(
                directory,
                "for-each-ref --format='%(refname)%1f%(refname:short)%1f%(HEAD)%1f%(upstream:short)%1f%(upstream:track)%1f" +
                    "%(objectname:short)%1f%(committerdate:relative)' refs/heads refs/remotes",
            ),
            trim = false,
        ).map(::parseBranches)

    suspend fun checkout(
        directory: String,
        branch: String,
    ): Result<String> {
        val name = validRef(branch) ?: return invalid()
        return exec(git(directory, "checkout ${quote(name)} --"))
    }

    /** Checks a remote branch out as a local one that tracks it, as `git switch --track` does. */
    suspend fun checkoutRemote(
        directory: String,
        remoteBranch: String,
    ): Result<String> {
        val name = validRef(remoteBranch) ?: return invalid()
        return exec(git(directory, "checkout --track ${quote(name)}"))
    }

    suspend fun createBranch(
        directory: String,
        name: String,
    ): Result<String> {
        val branch = validRef(name) ?: return invalid()
        return exec(git(directory, "checkout -b ${quote(branch)}"))
    }

    /** `-d` refuses to drop unmerged work; [force] (`-D`) is the user's explicit choice. */
    suspend fun deleteBranch(
        directory: String,
        name: String,
        force: Boolean,
    ): Result<String> {
        val branch = validRef(name) ?: return invalid()
        return exec(git(directory, "branch ${if (force) "-D" else "-d"} -- ${quote(branch)}"))
    }

    suspend fun fetch(directory: String): Result<String> = exec(git(directory, "fetch --all --prune"), NETWORK_TIMEOUT_SECONDS)

    /** Fast-forward only, as in the explorer: a merge made silently from a phone is rarely wanted. */
    suspend fun pull(directory: String): Result<String> = exec(git(directory, "pull --ff-only"), NETWORK_TIMEOUT_SECONDS)

    suspend fun push(directory: String): Result<String> = exec(git(directory, "push -u origin HEAD"), NETWORK_TIMEOUT_SECONDS)

    suspend fun stashes(directory: String): Result<List<GitStash>> =
        exec(git(directory, "stash list --format='%gd%x1f%s%x1f%cr'"), trim = false).map(::parseStashes)

    suspend fun stashPush(
        directory: String,
        message: String,
    ): Result<String> = exec(git(directory, "stash push -u" + if (message.isBlank()) "" else " -m ${quote(message)}"))

    suspend fun stashApply(
        directory: String,
        ref: String,
        pop: Boolean,
    ): Result<String> {
        val stash = validStash(ref) ?: return invalid()
        return exec(git(directory, "stash ${if (pop) "pop" else "apply"} ${quote(stash)}"))
    }

    suspend fun stashDrop(
        directory: String,
        ref: String,
    ): Result<String> {
        val stash = validStash(ref) ?: return invalid()
        return exec(git(directory, "stash drop ${quote(stash)}"))
    }

    /** Throws away the unstaged changes to [change], or deletes it when git has never seen it. */
    suspend fun discard(
        directory: String,
        change: GitFileChange,
    ): Result<String> {
        val path = top(change.path)
        return if (change.untracked) {
            exec(git(directory, "clean -f -- $path"))
        } else {
            exec(git(directory, "restore --worktree -- $path"))
        }
    }

    private fun invalid(): Result<String> = Result.failure(InvalidRefException())

    private suspend fun exec(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        trim: Boolean = true,
    ): Result<String> =
        withContext(ioDispatcher) {
            val result = run(command, timeoutSeconds)
            if (result.exitCode == 0) {
                // status -z output starts with a space when the first file is only changed in the
                // working tree, so parsed output is never trimmed.
                Result.success(if (trim) result.output.trim() else result.output)
            } else {
                Result.failure(WorkspaceShellException(result.output.trim().ifEmpty { "exit code ${result.exitCode}" }))
            }
        }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val NETWORK_TIMEOUT_SECONDS = 120L
        const val LOG_PAGE = 60
        private const val UNIT = '\u001f'
        private const val RECORD = '\u001e'
        private const val ALL_PATHS = "':/' ':(exclude,glob)**/.andcode-attachments/**' ':(exclude,glob)**/*.andcode-save'"
        private const val LOG_FORMAT = "%H%x1f%h%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%D%x1f%s%x1e"
        private val HASH = Regex("^[0-9a-fA-F]{4,64}$")
        private val STASH = Regex("^stash@\\{\\d+\\}$")

        internal fun quote(value: String): String = WorkspaceShell.quote(value)

        internal fun git(
            directory: String,
            arguments: String,
        ): String = "git --no-optional-locks -C ${quote(directory)} -c core.quotepath=off $arguments"

        /** A path from the repository root, taken literally: `[id]` or `*` in a name is not a glob. */
        internal fun top(path: String): String = quote(":(top,literal)$path")

        /** A name git could read as an option (`-f`) is refused before it reaches the command line. */
        internal fun validRef(name: String): String? = name.trim().takeIf { it.isNotEmpty() && !it.startsWith("-") && !it.contains('\n') }

        internal fun validHash(hash: String): String? = hash.trim().takeIf { HASH.matches(it) }

        internal fun validStash(ref: String): String? = ref.trim().takeIf { STASH.matches(it) }

        /** `git status --porcelain=v1 -b -z`: a `## branch...upstream [ahead 1]` header, then NUL-separated entries. */
        internal fun parseStatus(output: String): GitStatus {
            val fields = output.split('\u0000')
            var branch: String? = null
            var upstream: String? = null
            var ahead = 0
            var behind = 0
            var noCommits = false
            val changes = mutableListOf<GitFileChange>()
            var index = 0
            while (index < fields.size) {
                val field = fields[index]
                index++
                if (field.isEmpty()) continue
                if (field.startsWith("## ")) {
                    val header = field.removePrefix("## ")
                    val names = header.substringBefore(" [")
                    when {
                        names.startsWith("No commits yet on ") -> {
                            noCommits = true
                            branch = names.removePrefix("No commits yet on ")
                        }
                        names.startsWith("Initial commit on ") -> {
                            noCommits = true
                            branch = names.removePrefix("Initial commit on ")
                        }
                        names.startsWith("HEAD (no branch)") -> branch = null
                        else -> {
                            branch = names.substringBefore("...")
                            upstream = names.substringAfter("...", "").ifEmpty { null }
                        }
                    }
                    val track = header.substringAfter(" [", "").substringBefore("]")
                    Regex("ahead (\\d+)").find(track)?.let { ahead = it.groupValues[1].toInt() }
                    Regex("behind (\\d+)").find(track)?.let { behind = it.groupValues[1].toInt() }
                    continue
                }
                if (field.length < 4) continue
                val x = field[0]
                val y = field[1]
                val path = field.substring(3)
                // A rename or copy is followed by the path it came from, as its own field.
                val original =
                    if (x == 'R' || x == 'C' || y == 'R' || y == 'C') fields.getOrNull(index)?.also { index++ } else null
                changes += GitFileChange(path = path, originalPath = original, index = x, worktree = y)
            }
            return GitStatus(branch, upstream, ahead, behind, noCommits, changes)
        }

        internal fun parseLog(output: String): List<GitCommit> =
            output.split(RECORD).mapNotNull { record ->
                val fields = record.trim('\n').split(UNIT)
                if (fields.size < 8) return@mapNotNull null
                GitCommit(
                    hash = fields[0],
                    shortHash = fields[1],
                    parents = fields[2].split(' ').filter { it.isNotBlank() },
                    author = fields[3],
                    email = fields[4],
                    time = fields[5].toLongOrNull() ?: 0L,
                    refs = fields[6].split(", ").map { it.trim() }.filter { it.isNotEmpty() },
                    subject = fields[7],
                )
            }

        internal fun parseNameStatus(output: String): List<GitCommitFile> {
            val fields = output.split('\u0000').filter { it.isNotEmpty() }
            val files = mutableListOf<GitCommitFile>()
            var index = 0
            while (index < fields.size) {
                val status = fields[index].trim()
                index++
                val code = status.firstOrNull() ?: continue
                if (code == 'R' || code == 'C') {
                    val from = fields.getOrNull(index) ?: break
                    val to = fields.getOrNull(index + 1) ?: break
                    files += GitCommitFile(code, to, from)
                    index += 2
                } else {
                    val path = fields.getOrNull(index) ?: break
                    files += GitCommitFile(code, path)
                    index++
                }
            }
            return files
        }

        internal fun parseBranches(output: String): List<GitBranch> =
            output.lines().mapNotNull { line ->
                val fields = line.split(UNIT)
                if (fields.size < 7) return@mapNotNull null
                val remote = fields[0].startsWith("refs/remotes/")
                // origin/HEAD is a pointer, not a branch anyone works on.
                if (remote && fields[0].endsWith("/HEAD")) return@mapNotNull null
                GitBranch(
                    name = fields[1],
                    remote = remote,
                    current = fields[2] == "*",
                    upstream = fields[3].ifEmpty { null },
                    track = fields[4],
                    shortHash = fields[5],
                    relativeDate = fields[6],
                )
            }

        internal fun parseStashes(output: String): List<GitStash> =
            output.lines().mapNotNull { line ->
                val fields = line.split(UNIT)
                if (fields.size < 3) return@mapNotNull null
                GitStash(fields[0], fields[1], fields[2])
            }
    }
}

class InvalidRefException : IllegalArgumentException("invalid ref")
