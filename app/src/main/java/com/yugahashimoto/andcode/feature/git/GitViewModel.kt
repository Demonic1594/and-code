package com.yugahashimoto.andcode.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.util.safeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Source Control screen is doing, for its progress and its confirmation. */
enum class GitOperation { STAGE, UNSTAGE, DISCARD, COMMIT, FETCH, PULL, PUSH, CHECKOUT, CREATE_BRANCH, DELETE_BRANCH, STASH, APPLY_STASH, DROP_STASH }

/** A diff on screen: one file's change, in the working tree, the index or a commit. */
data class GitDiffView(
    val title: String,
    val subtitle: String,
    val text: String? = null,
    val error: String? = null,
)

data class GitUiState(
    val directory: String,
    val loading: Boolean = true,
    val status: GitStatus? = null,
    /** Set when the folder is not in a repository, or git itself failed; the screen says so. */
    val loadError: String? = null,
    val branches: List<GitBranch> = emptyList(),
    val commits: List<GitCommit> = emptyList(),
    val moreCommits: Boolean = false,
    val stashes: List<GitStash> = emptyList(),
    val running: GitOperation? = null,
    val done: GitOperation? = null,
    val error: String? = null,
    val diff: GitDiffView? = null,
    val commit: GitCommitDetails? = null,
    /** The commit being written; kept here so opening a diff or switching tabs does not lose it. */
    val commitMessage: String = "",
    val amend: Boolean = false,
)

/**
 * Source control for one workspace on this device's runtime: status, staging, commits, history,
 * branches and stashes, all through [GitClient]. Every write reloads what it can change, so the
 * screen always shows what git now says rather than what the app assumed.
 */
class GitViewModel(
    private val client: GitClient,
    directory: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GitUiState(directory = directory))
    val state: StateFlow<GitUiState> = mutableState.asStateFlow()

    private val directory get() = mutableState.value.directory

    private var refreshJob: Job? = null
    private var moreJob: Job? = null

    /** Bumped by each refresh, so a page of history read before it is not appended after it. */
    private var generation = 0

    /** Bumped by each diff, commit or amend request, so only the latest answer is shown. */
    private var request = 0

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        moreJob?.cancel()
        val current = ++generation
        mutableState.update { it.copy(loading = true) }
        refreshJob =
            viewModelScope.launch {
                val status = async { client.status(directory) }
                val branches = async { client.branches(directory) }
                val log = async { client.log(directory) }
                val stashes = async { client.stashes(directory) }
                val statusResult = status.await()
                val commits = log.await().getOrDefault(emptyList())
                val branchList = branches.await().getOrDefault(emptyList())
                val stashList = stashes.await().getOrDefault(emptyList())
                if (current != generation) return@launch
                mutableState.update {
                    it.copy(
                        loading = false,
                        status = statusResult.getOrNull(),
                        loadError = statusResult.exceptionOrNull()?.safeMessage("git status failed"),
                        branches = branchList,
                        commits = commits,
                        moreCommits = commits.size >= GitClient.LOG_PAGE,
                        stashes = stashList,
                    )
                }
            }
    }

    /** Reads the next page of history; a call while one is already being read is ignored. */
    fun loadMoreCommits() {
        val current = mutableState.value
        if (!current.moreCommits || current.loading || moreJob?.isActive == true) return
        val forGeneration = generation
        moreJob =
            viewModelScope.launch {
                val next = client.log(directory, skip = current.commits.size).getOrDefault(emptyList())
                if (forGeneration != generation) return@launch
                mutableState.update { state ->
                    val seen = state.commits.mapTo(HashSet()) { it.hash }
                    state.copy(commits = state.commits + next.filterNot { it.hash in seen }, moreCommits = next.size >= GitClient.LOG_PAGE)
                }
            }
    }

    fun stage(change: GitFileChange) = run(GitOperation.STAGE) { client.stage(directory, listOf(change.path)) }

    // A rename is staged as a delete plus an add; unstaging only the new path would leave the delete.
    fun unstage(change: GitFileChange) =
        run(GitOperation.UNSTAGE) { client.unstage(directory, listOfNotNull(change.path, change.originalPath), noCommits()) }

    fun stageAll() = run(GitOperation.STAGE) { client.stageAll(directory) }

    fun unstageAll() = run(GitOperation.UNSTAGE) { client.unstageAll(directory, noCommits()) }

    fun discard(change: GitFileChange) = run(GitOperation.DISCARD) { client.discard(directory, change) }

    fun setCommitMessage(message: String) = mutableState.update { it.copy(commitMessage = message) }

    /** Turning amend on starts the message from the last commit's, unless one is already written. */
    fun setAmend(amend: Boolean) {
        mutableState.update { it.copy(amend = amend) }
        if (!amend) return
        val asked = ++request
        viewModelScope.launch {
            val message = client.lastCommitMessage(directory).getOrDefault("").trim()
            mutableState.update {
                if (asked != request || !it.amend || it.commitMessage.isNotBlank()) it else it.copy(commitMessage = message)
            }
        }
    }

    /**
     * Commits what is staged; with nothing staged, everything is staged first, as most git UIs offer.
     * Whether anything is staged is asked of git at the time, not read from the screen, which may be
     * a refresh behind the user's last stage.
     */
    fun commit() {
        val current = mutableState.value
        val text = current.commitMessage.trim()
        if (text.isEmpty() || current.loading) return
        val amend = current.amend
        run(GitOperation.COMMIT, onSuccess = { it.copy(commitMessage = "", amend = false) }) {
            val status = client.status(directory).getOrElse { return@run Result.failure(it) }
            if (status.staged.isEmpty() && !amend) {
                client.stageAll(directory).onFailure { return@run Result.failure(it) }
            }
            client.commit(directory, text, amend)
        }
    }

    fun fetch() = run(GitOperation.FETCH) { client.fetch(directory) }

    fun pull() = run(GitOperation.PULL) { client.pull(directory) }

    fun push() = run(GitOperation.PUSH) { client.push(directory) }

    fun checkout(branch: GitBranch) =
        run(GitOperation.CHECKOUT) {
            if (branch.remote) client.checkoutRemote(directory, branch.name) else client.checkout(directory, branch.name)
        }

    fun createBranch(name: String) = run(GitOperation.CREATE_BRANCH) { client.createBranch(directory, name) }

    fun deleteBranch(
        branch: GitBranch,
        force: Boolean,
    ) = run(GitOperation.DELETE_BRANCH) { client.deleteBranch(directory, branch.name, force) }

    fun stash(message: String) = run(GitOperation.STASH) { client.stashPush(directory, message) }

    fun applyStash(
        stash: GitStash,
        pop: Boolean,
    ) = run(GitOperation.APPLY_STASH) { client.stashApply(directory, stash.ref, pop) }

    fun dropStash(stash: GitStash) = run(GitOperation.DROP_STASH) { client.stashDrop(directory, stash.ref) }

    fun openDiff(
        change: GitFileChange,
        staged: Boolean,
    ) {
        val asked = ++request
        mutableState.update { it.copy(diff = GitDiffView(change.path.substringAfterLast('/'), change.path)) }
        viewModelScope.launch {
            val result = client.diff(directory, change, staged)
            showDiff(asked, result, "git diff failed")
        }
    }

    fun openCommit(hash: String) {
        val asked = ++request
        viewModelScope.launch {
            val result = client.commitDetails(directory, hash)
            if (asked != request) return@launch
            result
                .onSuccess { details -> mutableState.update { it.copy(commit = details) } }
                .onFailure { error -> mutableState.update { it.copy(error = error.safeMessage("git show failed")) } }
        }
    }

    fun openCommitFile(file: GitCommitFile) {
        val commit = mutableState.value.commit ?: return
        val asked = ++request
        mutableState.update { it.copy(diff = GitDiffView(file.path.substringAfterLast('/'), "${commit.commit.shortHash} · ${file.path}")) }
        viewModelScope.launch {
            val result = client.commitFileDiff(directory, commit.commit.hash, file.path, file.originalPath)
            showDiff(asked, result, "git show failed")
        }
    }

    private fun showDiff(
        asked: Int,
        result: Result<String>,
        fallback: String,
    ) {
        mutableState.update { state ->
            val diff = state.diff
            if (asked != request || diff == null) return@update state
            state.copy(diff = diff.copy(text = result.getOrNull(), error = result.exceptionOrNull()?.safeMessage(fallback)))
        }
    }

    fun closeDiff() {
        request++
        mutableState.update { it.copy(diff = null) }
    }

    fun closeCommit() {
        request++
        mutableState.update { it.copy(commit = null) }
    }

    fun consumeResult() = mutableState.update { it.copy(done = null, error = null) }

    private fun noCommits(): Boolean = mutableState.value.status?.noCommits == true

    private fun run(
        operation: GitOperation,
        onSuccess: (GitUiState) -> GitUiState = { it },
        action: suspend () -> Result<String>,
    ) {
        if (mutableState.value.running != null) return
        mutableState.update { it.copy(running = operation, done = null, error = null) }
        viewModelScope.launch {
            val result =
                try {
                    action()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Result.failure(error)
                }
            // The screen stays busy until the reload has landed, so nothing acts on what git said before.
            mutableState.update {
                val updated =
                    it.copy(
                        done = if (result.isSuccess) operation else null,
                        error =
                            result.exceptionOrNull()?.let { error ->
                                if (error is InvalidRefException) ERROR_INVALID_REF else error.safeMessage("git failed")
                            },
                    )
                if (result.isSuccess) onSuccess(updated) else updated
            }
            refresh()
            refreshJob?.join()
            mutableState.update { it.copy(running = null) }
        }
    }

    companion object {
        /** Marker the screen swaps for its localized "invalid name" message. */
        const val ERROR_INVALID_REF = "\u0000invalid-ref"
    }
}
