package com.yugahashimoto.andcode.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.util.safeMessage
import kotlinx.coroutines.CancellationException
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
    val lastCommitMessage: String = "",
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

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val status = async { client.status(directory) }
            val branches = async { client.branches(directory) }
            val log = async { client.log(directory) }
            val stashes = async { client.stashes(directory) }
            val statusResult = status.await()
            val commits = log.await().getOrDefault(emptyList())
            mutableState.update {
                it.copy(
                    loading = false,
                    status = statusResult.getOrNull(),
                    loadError = statusResult.exceptionOrNull()?.safeMessage("git status failed"),
                    branches = branches.await().getOrDefault(emptyList()),
                    commits = commits,
                    moreCommits = commits.size >= GitClient.LOG_PAGE,
                    stashes = stashes.await().getOrDefault(emptyList()),
                )
            }
        }
    }

    fun loadMoreCommits() {
        val current = mutableState.value
        if (!current.moreCommits || current.loading) return
        viewModelScope.launch {
            val next = client.log(directory, skip = current.commits.size).getOrDefault(emptyList())
            mutableState.update { it.copy(commits = it.commits + next, moreCommits = next.size >= GitClient.LOG_PAGE) }
        }
    }

    fun stage(change: GitFileChange) = run(GitOperation.STAGE) { client.stage(directory, listOf(change.path)) }

    fun unstage(change: GitFileChange) = run(GitOperation.UNSTAGE) { client.unstage(directory, listOf(change.path), noCommits()) }

    fun stageAll() = run(GitOperation.STAGE) { client.stageAll(directory) }

    fun unstageAll() = run(GitOperation.UNSTAGE) { client.unstageAll(directory, noCommits()) }

    fun discard(change: GitFileChange) = run(GitOperation.DISCARD) { client.discard(directory, change) }

    /** Commits what is staged; with nothing staged, everything is staged first, as most git UIs offer. */
    fun commit(
        message: String,
        amend: Boolean,
    ) {
        val text = message.trim()
        if (text.isEmpty()) return
        run(GitOperation.COMMIT) {
            val stagedNothing = mutableState.value.status?.staged.isNullOrEmpty()
            if (stagedNothing && !amend) {
                client.stageAll(directory).onFailure { return@run Result.failure(it) }
            }
            client.commit(directory, text, amend)
        }
    }

    /** Loads the last commit's message, for the amend toggle to start from. */
    fun loadLastCommitMessage() {
        viewModelScope.launch {
            val message = client.lastCommitMessage(directory).getOrDefault("")
            mutableState.update { it.copy(lastCommitMessage = message) }
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
        val title = change.path.substringAfterLast('/')
        mutableState.update { it.copy(diff = GitDiffView(title, change.path)) }
        viewModelScope.launch {
            val result = client.diff(directory, change, staged)
            mutableState.update { state ->
                if (state.diff?.subtitle != change.path) return@update state
                state.copy(
                    diff = state.diff.copy(text = result.getOrNull(), error = result.exceptionOrNull()?.safeMessage("git diff failed")),
                )
            }
        }
    }

    fun openCommit(hash: String) {
        viewModelScope.launch {
            client.commitDetails(directory, hash)
                .onSuccess { details -> mutableState.update { it.copy(commit = details) } }
                .onFailure { error -> mutableState.update { it.copy(error = error.safeMessage("git show failed")) } }
        }
    }

    fun openCommitFile(file: GitCommitFile) {
        val commit = mutableState.value.commit ?: return
        val title = file.path.substringAfterLast('/')
        val subtitle = "${commit.commit.shortHash} · ${file.path}"
        mutableState.update { it.copy(diff = GitDiffView(title, subtitle)) }
        viewModelScope.launch {
            val result = client.commitFileDiff(directory, commit.commit.hash, file.path)
            mutableState.update { state ->
                if (state.diff?.subtitle != subtitle) return@update state
                state.copy(
                    diff = state.diff.copy(text = result.getOrNull(), error = result.exceptionOrNull()?.safeMessage("git show failed")),
                )
            }
        }
    }

    fun closeDiff() = mutableState.update { it.copy(diff = null) }

    fun closeCommit() = mutableState.update { it.copy(commit = null) }

    fun consumeResult() = mutableState.update { it.copy(done = null, error = null) }

    private fun noCommits(): Boolean = mutableState.value.status?.noCommits == true

    private fun run(
        operation: GitOperation,
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
            mutableState.update {
                it.copy(
                    running = null,
                    done = if (result.isSuccess) operation else null,
                    error =
                        result.exceptionOrNull()?.let { error ->
                            if (error is InvalidRefException) ERROR_INVALID_REF else error.safeMessage("git failed")
                        },
                )
            }
            refresh()
        }
    }

    companion object {
        /** Marker the screen swaps for its localized "invalid name" message. */
        const val ERROR_INVALID_REF = "\u0000invalid-ref"
    }
}
