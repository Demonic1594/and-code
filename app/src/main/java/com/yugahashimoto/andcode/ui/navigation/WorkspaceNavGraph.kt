package com.yugahashimoto.andcode.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.feature.browser.GuestBrowserScreen
import com.yugahashimoto.andcode.feature.git.GitClient
import com.yugahashimoto.andcode.feature.git.GitScreen
import com.yugahashimoto.andcode.feature.git.GitScreenActions
import com.yugahashimoto.andcode.feature.git.GitViewModel
import com.yugahashimoto.andcode.feature.workspace.AndroidCodeViewerMessages
import com.yugahashimoto.andcode.feature.workspace.CodeViewerScreen
import com.yugahashimoto.andcode.feature.workspace.CodeViewerViewModel
import com.yugahashimoto.andcode.feature.workspace.GitActions
import com.yugahashimoto.andcode.feature.workspace.LocalRuntimeManagementScreen
import com.yugahashimoto.andcode.feature.workspace.LocalRuntimeManagementViewModel
import com.yugahashimoto.andcode.feature.workspace.RemoteConnectionScreen
import com.yugahashimoto.andcode.feature.workspace.TerminalScreen
import com.yugahashimoto.andcode.feature.workspace.TerminalViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceExplorerScreen
import com.yugahashimoto.andcode.feature.workspace.WorkspaceExplorerViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceShell
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspacesScreen
import com.yugahashimoto.andcode.feature.workspace.isOpenable
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.ViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun NavGraphBuilder.workspaceNavGraph(
    navController: NavController,
    workspaceViewModel: WorkspaceViewModel,
    // Getters, not values: NavHost remembers the destination lambdas, so a value read here is the
    // one that existed when the graph was built. That is how the explorer came to be unreachable —
    // the workspace the user had just tapped still read as null and the screen popped straight back.
    selectedWorkspace: () -> WorkspaceRef?,
    onSelectWorkspace: (WorkspaceRef?) -> Unit,
    selectedRuntime: () -> RuntimeTarget?,
    app: AndCodeApplication,
    onImportFolder: () -> Unit,
    onShowCloneDialog: () -> Unit,
    completeOnboardingAndGoToChat: () -> Unit,
) {
    composable(ROUTE_REMOTE_CONNECTION) {
        RemoteConnectionScreen(
            onTestConnection = workspaceViewModel::testConnection,
            // Saving here is the user pressing "connect", so the PC becomes the active runtime even
            // when an Android-local runtime is already set up and selected.
            onSaveConnection = { form -> workspaceViewModel.saveConnection(form, activate = true) },
            onBack = { navController.popBackStack() },
            onConnected = completeOnboardingAndGoToChat,
        )
    }

    composable(ROUTE_WORKSPACES) {
        // Collected here rather than passed in: NavHost remembers the graph, so a state value
        // handed to this builder would stay frozen at whatever it was on first composition and
        // every later update — runtime installs, health checks — would never reach the screen.
        val workspaceState by workspaceViewModel.state.collectAsState()
        WorkspacesScreen(
            state = workspaceState,
            onSelectRuntime = workspaceViewModel::selectRuntime,
            // This screen edits the connection list and has its own per-target "select" action, so
            // saving must not move the running target under the user.
            onSaveConnection = { form -> workspaceViewModel.saveConnection(form, activate = false) },
            onDeleteConnection = workspaceViewModel::deleteConnection,
            onTestConnection = workspaceViewModel::testConnection,
            onRefresh = workspaceViewModel::refresh,
            onOpenWorkspace = { workspace ->
                onSelectWorkspace(workspace)
                navController.navigate(WORKSPACE_DETAIL_ROUTE)
            },
            onImportFolder = onImportFolder,
            onCloneGithub = onShowCloneDialog,
            onChooseFolder = workspaceViewModel::openFolderPicker,
            onBrowseFolderInto = workspaceViewModel::browseFolderInto,
            onBrowseFolderUp = workspaceViewModel::browseFolderUp,
            onConfirmFolder = { workspaceViewModel.confirmFolderPicker() },
            onDismissFolderPicker = workspaceViewModel::dismissFolderPicker,
            onDeviceStorageAccessChanged = workspaceViewModel::onDeviceStorageAccessChanged,
            onRemoveProject = workspaceViewModel::removeProject,
            onDeleteProjectFiles = workspaceViewModel::deleteProjectFiles,
            onDismissDeleteFailure = workspaceViewModel::dismissDeleteFailure,
            onBack = { navController.popBackStack() },
        )
    }

    composable(LOCAL_RUNTIME_MANAGEMENT_ROUTE) {
        val managementViewModel: LocalRuntimeManagementViewModel =
            viewModel(
                key = "local-runtime-management",
                factory =
                    ViewModelFactory {
                        LocalRuntimeManagementViewModel(
                            runtimeState = app.localRuntimeManager.state,
                            lastOperationState = app.localRuntimeManager.lastOperation,
                            diagnosticsProvider = {
                                withContext(Dispatchers.IO) {
                                    app.localRuntimeDiagnosticsCollector.collect()
                                }
                            },
                            repairAction = app.localRuntimeController::reinstall,
                            installFullDevelopmentToolsAction = app.localRuntimeController::installFullDevelopmentTools,
                            runtimeEnvironmentInstalledProvider = app.localRuntimeManager::runtimeEnvironmentInstalled,
                            fullDevelopmentToolsInstalledProvider = app.localRuntimeManager::fullDevelopmentToolsInstalled,
                            deleteAction = app.localRuntimeController::delete,
                            getString = { app.getString(it) },
                            adbState = app.adbConnectionManager.state,
                            adbPairAction = app.adbConnectionManager::pair,
                            adbConnectAction = app.adbConnectionManager::connect,
                            adbDisconnectAction = app.adbConnectionManager::disconnect,
                            adbStartDiscovery = app.adbConnectionManager::startDiscovery,
                        )
                    },
            )
        val managementState by managementViewModel.state.collectAsState()
        LaunchedEffect(managementState.deleteCompleted) {
            if (managementState.deleteCompleted) {
                managementViewModel.consumeDeleteCompleted()
                workspaceViewModel.refresh()
                navController.popBackStack()
            }
        }
        LocalRuntimeManagementScreen(
            state = managementState,
            onBack = { navController.popBackStack() },
            onRefresh = managementViewModel::refresh,
            onRepair = managementViewModel::repair,
            onInstallFullDevelopmentTools = managementViewModel::installFullDevelopmentTools,
            onRequestDelete = managementViewModel::requestDelete,
            onDismissDelete = managementViewModel::dismissDelete,
            onConfirmDelete = managementViewModel::confirmDelete,
            onShowAdbPairDialog = managementViewModel::showAdbPairDialog,
            onDismissAdbPairDialog = managementViewModel::dismissAdbPairDialog,
            onAdbPair = managementViewModel::adbPair,
            onAdbConnect = managementViewModel::adbConnect,
            onAdbDisconnect = managementViewModel::adbDisconnect,
        )
    }

    composable(WORKSPACE_DETAIL_ROUTE) {
        val workspace = selectedWorkspace()
        val runtime = selectedRuntime()
        if (workspace == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val explorerViewModel: WorkspaceExplorerViewModel =
                viewModel(
                    key = "workspace-explorer-${runtime.id}-${workspace.id}",
                    factory =
                        ViewModelFactory {
                            WorkspaceExplorerViewModel(runtime, workspace, localWorkspaceShell(app, runtime))
                        },
                )
            val explorerState by explorerViewModel.state.collectAsState()
            WorkspaceExplorerScreen(
                state = explorerState,
                onBack = { navController.popBackStack() },
                onRefresh = explorerViewModel::refresh,
                onOpenNode = { node ->
                    if (node.type == "directory") {
                        explorerViewModel.open(node)
                    } else {
                        // node.path is relative to the directory currently being browsed
                        // (explorerState.currentPath), not to the fixed workspace.path - the
                        // explorer can now navigate outside the original workspace root.
                        navController.navigate(codeViewerRoute(runtime.id, explorerState.currentPath, node.path))
                    }
                },
                onNavigateUp = explorerViewModel::navigateUp,
                onSearch = explorerViewModel::search,
                onRefreshChanges = explorerViewModel::refreshChanges,
                onOpenTerminal = { navController.navigate(ROUTE_TERMINAL) },
                onOpenSourceControl = { navController.navigate(ROUTE_GIT) },
                onOpenChange = { change ->
                    if (change.isOpenable()) {
                        navController.navigate(codeViewerRoute(runtime.id, workspace.path, change.displayPath))
                    }
                },
                onSwitchBranch = explorerViewModel::checkout,
                onCreateBranch = explorerViewModel::createBranch,
                gitActions =
                    GitActions(
                        onCommit = explorerViewModel::commit,
                        onPush = explorerViewModel::push,
                        onPull = explorerViewModel::pull,
                        onStash = explorerViewModel::stash,
                        onDiscard = explorerViewModel::discard,
                        onConsumeResult = explorerViewModel::consumeActionResult,
                    ),
            )
        }
    }

    composable(ROUTE_GIT) {
        val workspace = selectedWorkspace()
        val runtime = selectedRuntime()
        if (workspace == null || runtime?.type != RuntimeType.LOCAL) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val gitViewModel: GitViewModel =
                viewModel(
                    key = "git-${runtime.id}-${workspace.id}",
                    factory =
                        ViewModelFactory {
                            GitViewModel(GitClient { command, timeout -> app.commandRunner.runShell(command, timeout) }, workspace.path)
                        },
                )
            val gitState by gitViewModel.state.collectAsState()
            GitScreen(
                state = gitState,
                onBack = { navController.popBackStack() },
                actions =
                    GitScreenActions(
                        onRefresh = gitViewModel::refresh,
                        onStage = gitViewModel::stage,
                        onUnstage = gitViewModel::unstage,
                        onStageAll = gitViewModel::stageAll,
                        onUnstageAll = gitViewModel::unstageAll,
                        onDiscard = gitViewModel::discard,
                        onCommitMessageChange = gitViewModel::setCommitMessage,
                        onAmendChange = gitViewModel::setAmend,
                        onCommit = gitViewModel::commit,
                        onFetch = gitViewModel::fetch,
                        onPull = gitViewModel::pull,
                        onPush = gitViewModel::push,
                        onCheckout = gitViewModel::checkout,
                        onCreateBranch = gitViewModel::createBranch,
                        onDeleteBranch = gitViewModel::deleteBranch,
                        onStash = gitViewModel::stash,
                        onApplyStash = gitViewModel::applyStash,
                        onDropStash = gitViewModel::dropStash,
                        onOpenDiff = gitViewModel::openDiff,
                        onOpenCommit = gitViewModel::openCommit,
                        onOpenCommitFile = gitViewModel::openCommitFile,
                        onCloseDiff = gitViewModel::closeDiff,
                        onCloseCommit = gitViewModel::closeCommit,
                        onLoadMoreCommits = gitViewModel::loadMoreCommits,
                        onConsumeResult = gitViewModel::consumeResult,
                    ),
            )
        }
    }

    composable(ROUTE_TERMINAL) {
        val terminalViewModel: TerminalViewModel =
            viewModel(
                key = "terminal",
                factory =
                    ViewModelFactory {
                        TerminalViewModel(app.commandRunner, app.runtimeWork)
                    },
            )
        val terminalState by terminalViewModel.state.collectAsState()
        TerminalScreen(
            state = terminalState,
            onCommand = terminalViewModel::executeCommand,
            onInputChange = terminalViewModel::updateInput,
            onClear = terminalViewModel::clear,
        )
    }

    composable(GUEST_BROWSER_ROUTE_PATTERN) { backStack ->
        val requestedUrl = backStack.arguments?.getString(GUEST_BROWSER_ARG_URL)?.let { decodeRouteArg(it) }
        // Opened from Settings without a URL, there is no page to guess at: the runtime's HTTP
        // servers (opencode serve included) are APIs, not UIs, and the local one now requires basic
        // auth. Leave the URL bar empty for the user to fill in instead of loading a 401.
        GuestBrowserScreen(
            initialUrl = requestedUrl.orEmpty(),
            onBack = { navController.popBackStack() },
        )
    }

    composable(CODE_VIEWER_ROUTE_PATTERN) { backStack ->
        val arguments =
            runCatching {
                Triple(
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("runtimeId"))),
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("workspacePath"))),
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("filePath"))),
                )
            }.getOrNull()
        val runtime = arguments?.first?.let { app.runtimeRegistry.target(it) }
        if (arguments == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val viewerViewModel: CodeViewerViewModel =
                viewModel(
                    key = "code-viewer-${backStack.id}",
                    factory =
                        ViewModelFactory {
                            CodeViewerViewModel(
                                backend = runtime,
                                workspacePath = arguments.second,
                                filePath = arguments.third,
                                // Only a runtime whose workspace is a folder on this device can write a file; a
                                // remote OpenCode server has no write endpoint, so its viewer stays read-only.
                                // The selected workspace, not the folder being browsed, bounds what is editable.
                                editableRoot = if (runtime.capabilities.fileEditing) selectedWorkspace()?.path else null,
                                messages = AndroidCodeViewerMessages(app),
                            )
                        },
                )
            val viewerState by viewerViewModel.state.collectAsState()
            val preferences by app.preferences.state.collectAsState()
            CodeViewerScreen(
                state = viewerState,
                syntaxThemeKey = preferences.syntaxTheme,
                onBack = { navController.popBackStack() },
                onStartEditing = viewerViewModel::startEditing,
                onDraftChange = viewerViewModel::updateDraft,
                onSave = viewerViewModel::save,
                onStopEditing = viewerViewModel::stopEditing,
                onUndo = viewerViewModel::undo,
                onRedo = viewerViewModel::redo,
                onApplyEdit = viewerViewModel::applyEdit,
            )
        }
    }
}

/**
 * A shell for git and file writes, only for a runtime on this device: its commands run in the same
 * PRoot environment as the agents. A remote runtime's files are on another machine, out of reach.
 */
private fun localWorkspaceShell(
    app: AndCodeApplication,
    runtime: RuntimeTarget,
): WorkspaceShell? =
    if (runtime.type == RuntimeType.LOCAL) {
        WorkspaceShell { command, timeout -> app.commandRunner.runShell(command, timeout) }
    } else {
        null
    }
