package com.yugahashimoto.andcode.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo
import com.yugahashimoto.andcode.core.util.isNonGitWorkspaceError
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceExplorerUiState(
    val workspace: WorkspaceRef,
    val currentPath: String = WorkspaceFolders.GUEST_ROOT,
    val files: List<OpenCodeFileNode> = emptyList(),
    val searchQuery: String = "",
    val textMatches: List<OpenCodeSearchMatch> = emptyList(),
    val fileMatches: List<String> = emptyList(),
    val vcsInfo: OpenCodeVcsInfo? = null,
    val changes: List<OpenCodeFileChange> = emptyList(),
    val diff: List<OpenCodeFileChange> = emptyList(),
    val isLoadingFiles: Boolean = false,
    val isSearching: Boolean = false,
    val isLoadingChanges: Boolean = false,
    val error: String? = null,
    /** True when git actions are available, i.e. the workspace is on this device's runtime. */
    val canRunGit: Boolean = false,
    val branches: List<String> = emptyList(),
    val commits: List<CommitInfo> = emptyList(),
    /** The git action in flight, so the UI can disable the buttons and show progress. */
    val runningAction: WorkspaceAction? = null,
    /** The last git action that finished, for a one-shot confirmation; cleared by [WorkspaceExplorerViewModel.consumeActionResult]. */
    val completedAction: WorkspaceAction? = null,
    /** What git said when the last action failed. */
    val actionError: String? = null,
)

class WorkspaceExplorerViewModel(
    private val backend: OpenCodeBackend,
    workspace: WorkspaceRef,
    /** Null for a remote runtime: there is no shell on this device that can reach its files. */
    private val shell: WorkspaceShell? = null,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            WorkspaceExplorerUiState(
                workspace = workspace,
                currentPath = WorkspaceFolders.normalize(workspace.path),
                canRunGit = shell != null,
            ),
        )
    val state: StateFlow<WorkspaceExplorerUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        loadDirectory(mutableState.value.currentPath)
        refreshChanges()
    }

    fun open(node: OpenCodeFileNode) {
        if (node.type == "directory") {
            loadDirectory(WorkspaceFolders.normalize(node.absolute))
        }
    }

    fun navigateUp() {
        val path = mutableState.value.currentPath
        val parent = WorkspaceFolders.parentOf(path)
        if (parent == path) return
        loadDirectory(parent)
    }

    fun search(query: String) {
        val normalized = query.trim()
        mutableState.update {
            it.copy(
                searchQuery = query,
                isSearching = normalized.isNotEmpty(),
                textMatches = if (normalized.isEmpty()) emptyList() else it.textMatches,
                fileMatches = if (normalized.isEmpty()) emptyList() else it.fileMatches,
                error = null,
            )
        }
        if (normalized.isEmpty()) return

        viewModelScope.launch {
            val directory = mutableState.value.workspace.path
            val result =
                coroutineScope {
                    val text = async { runCatching { backend.searchText(directory, normalized) } }
                    val files =
                        async {
                            runCatching {
                                backend.findFiles(
                                    directory = directory,
                                    query = normalized,
                                    includeDirectories = false,
                                    type = "file",
                                    limit = 100,
                                )
                            }
                        }
                    text.await() to files.await()
                }
            val error = result.first.exceptionOrNull() ?: result.second.exceptionOrNull()
            mutableState.update {
                it.copy(
                    textMatches = result.first.getOrDefault(emptyList()),
                    fileMatches = result.second.getOrDefault(emptyList()),
                    isSearching = false,
                    error = error?.safeMessage("OpenCode workspace operation failed"),
                )
            }
        }
    }

    fun refreshChanges() {
        mutableState.update { it.copy(isLoadingChanges = true, error = null) }
        viewModelScope.launch {
            val directory = mutableState.value.workspace.path
            val result =
                coroutineScope {
                    val info = async { runCatching { backend.vcsInfo(directory) } }
                    val status = async { runCatching { backend.vcsStatus(directory) } }
                    val diff = async { runCatching { backend.vcsDiff(directory) } }
                    Triple(info.await(), status.await(), diff.await())
                }
            val error =
                result.first.exceptionOrNull()
                    ?: result.second.exceptionOrNull()
                    ?: result.third.exceptionOrNull()
            mutableState.update {
                it.copy(
                    vcsInfo = result.first.getOrNull(),
                    changes = result.second.getOrDefault(emptyList()),
                    diff = result.third.getOrDefault(emptyList()),
                    isLoadingChanges = false,
                    error = error?.takeUnless(Throwable::isNonGitWorkspaceError)?.safeMessage("OpenCode workspace operation failed"),
                )
            }
            if (result.first.isSuccess) loadGitHistory(directory)
        }
    }

    fun commit(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        runGitAction(WorkspaceAction.COMMIT) { shell, directory -> shell.commitAll(directory, trimmed) }
    }

    fun push() = runGitAction(WorkspaceAction.PUSH) { shell, directory -> shell.push(directory) }

    fun pull() = runGitAction(WorkspaceAction.PULL) { shell, directory -> shell.pull(directory) }

    fun stash() = runGitAction(WorkspaceAction.STASH) { shell, directory -> shell.stash(directory) }

    fun checkout(branch: String) = runGitAction(WorkspaceAction.CHECKOUT) { shell, directory -> shell.checkout(directory, branch) }

    fun createBranch(name: String) {
        if (name.isBlank()) return
        runGitAction(WorkspaceAction.CREATE_BRANCH) { shell, directory -> shell.createBranch(directory, name) }
    }

    fun discard(change: OpenCodeFileChange) {
        val path = change.displayPath.takeIf(String::isNotBlank) ?: return
        runGitAction(WorkspaceAction.DISCARD) { shell, directory -> shell.discard(directory, path) }
    }

    fun consumeActionResult() {
        mutableState.update { it.copy(completedAction = null, actionError = null) }
    }

    private fun runGitAction(
        action: WorkspaceAction,
        block: suspend (WorkspaceShell, String) -> Result<String>,
    ) {
        val shell = shell ?: return
        // One at a time: two git commands racing on the same index lock each other out.
        if (mutableState.value.runningAction != null) return
        mutableState.update { it.copy(runningAction = action, completedAction = null, actionError = null) }
        viewModelScope.launch {
            val result = block(shell, mutableState.value.workspace.path)
            mutableState.update {
                it.copy(
                    runningAction = null,
                    completedAction = action.takeIf { result.isSuccess },
                    actionError =
                        result.exceptionOrNull()?.let { error ->
                            if (error is InvalidBranchException) ACTION_ERROR_INVALID_BRANCH else error.safeMessage("git failed")
                        },
                )
            }
            refreshChanges()
        }
    }

    private suspend fun loadGitHistory(directory: String) {
        val shell = shell ?: return
        val branches = shell.branches(directory).getOrDefault(emptyList())
        val commits = shell.recentCommits(directory).getOrDefault(emptyList())
        mutableState.update { it.copy(branches = branches, commits = commits) }
    }

    private fun loadDirectory(path: String) {
        mutableState.update {
            it.copy(
                currentPath = path,
                files = emptyList(),
                isLoadingFiles = true,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { backend.listFiles(path, ".") }
                .onSuccess { files ->
                    mutableState.update {
                        it.copy(
                            files =
                                files.sortedWith(
                                    compareBy<OpenCodeFileNode> { node -> node.type != "directory" }
                                        .thenBy { node -> node.name.lowercase() },
                                ),
                            isLoadingFiles = false,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isLoadingFiles = false, error = error.safeMessage("OpenCode workspace operation failed"))
                    }
                }
        }
    }
}

/** Marker [WorkspaceExplorerUiState.actionError] value the screen swaps for its localized text. */
internal const val ACTION_ERROR_INVALID_BRANCH = "\u0000invalid-branch"
