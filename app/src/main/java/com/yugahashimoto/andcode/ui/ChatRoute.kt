package com.yugahashimoto.andcode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.core.UrlLauncher
import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.feature.activity.ActivityUiState
import com.yugahashimoto.andcode.feature.chat.ChatHomeScreen
import com.yugahashimoto.andcode.feature.chat.ChatUiState
import com.yugahashimoto.andcode.feature.chat.ChatViewModel
import com.yugahashimoto.andcode.feature.chat.FileMentionsUi
import com.yugahashimoto.andcode.feature.chat.LocalFileMentions
import com.yugahashimoto.andcode.feature.chat.LocalSupervisedProcesses
import com.yugahashimoto.andcode.feature.chat.SubagentInfo
import com.yugahashimoto.andcode.feature.git.GitClient
import com.yugahashimoto.andcode.feature.git.gitHubRepositoryUrl
import com.yugahashimoto.andcode.feature.settings.SettingsUiState
import com.yugahashimoto.andcode.feature.settings.SettingsViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceUiState
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.local.AntigravityControllerState
import com.yugahashimoto.andcode.ui.lock.LocalSendGate
import com.yugahashimoto.andcode.ui.navigation.ROUTE_ANDROID_SETUP
import com.yugahashimoto.andcode.ui.navigation.ROUTE_REMOTE_CONNECTION
import com.yugahashimoto.andcode.ui.navigation.ROUTE_SETTINGS_AGENT_CODEX
import com.yugahashimoto.andcode.ui.navigation.ROUTE_SETTINGS_PROVIDERS
import com.yugahashimoto.andcode.ui.navigation.guestBrowserRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The chat screen's route: what it shows from the app's state, the effects that belong to it (a
// chat or handoff waiting to be opened, pull request badges kept fresh, the repository link) and
// where its actions lead. AndCodeApp keeps the state and hands it over on each composition.
@Composable
internal fun ChatRoute(
    deps: ChatRouteDeps,
    state: ChatRouteState,
    fallbackProviderId: String?,
    fallbackModelId: String?,
    waiting: ChatRouteWaiting,
    actions: ChatRouteActions,
) {
    val context = LocalContext.current
    val sendGate = LocalSendGate.current
    val app = deps.app
    val navController = deps.navController
    val chatViewModel = deps.chatViewModel
    val settingsViewModel = deps.settingsViewModel
    val workspaceViewModel = deps.workspaceViewModel
    val repositoryGit = deps.repositoryGit
    val chatState = state.chat
    val settingsState = state.settings
    val workspaceState = state.workspace
    val antigravityState = state.antigravity
    val activityState = state.activity
    val preferences = state.preferences
    val selectedRuntime = state.runtime
    val runtimeTargets = state.runtimeTargets
    // Keyed on the runtime too: a deep link that also switches the runtime must run
    // against the view model of the new runtime, not the previous one's. While no
    // runtime is selected yet (cold start) the chat backend does not exist and
    // openSession would silently no-op — wait for one instead of dropping the request.
    LaunchedEffect(waiting.session, selectedRuntime?.id) {
        val pending = waiting.session ?: return@LaunchedEffect
        if (selectedRuntime == null) return@LaunchedEffect
        chatViewModel.openSession(pending.first, pending.second)
        waiting.clearSession()
    }
    LaunchedEffect(waiting.handoffPrompt, selectedRuntime?.id, waiting.handoffReady) {
        val pending = waiting.handoffPrompt
        if (pending != null && waiting.handoffReady && selectedRuntime?.id == pending.first) {
            waiting.takeHandoff()
            // Consumed now, whatever the unlock does: a cancelled unlock drops the
            // handoff rather than leave it to fire into whichever chat is open the
            // next time the model sheet closes. Like any send, it asks for the
            // unlock when the settings say so.
            sendGate { chatViewModel.sendMessage(pending.second) }
        }
    }
    // A pull request is usually merged or closed on GitHub, not from here, so the
    // badges are refreshed while the chat is on screen. The repository decides what
    // is actually stale, so this only costs a request when something can change.
    // repeatOnLifecycle stops the loop the instant the app leaves STARTED (backed
    // out to another app, screen off) and restarts it fresh on return, rather than
    // polling GitHub for badges nobody can see.
    if (chatState.pullRequests.isNotEmpty()) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(ChatRouteTiming.PULL_REQUEST_REFRESH_INTERVAL_MS)
                    chatViewModel.refreshPullRequests()
                }
            }
        }
    }
    // The chat's GitHub repository, for the ⋮ menu: only an on-device runtime has a
    // shell to read the origin through. An open chat uses only its own folder (until
    // that is known nothing shows, rather than another project's link); a new chat
    // the project it is about to start in. Read each time, so a remote changed in
    // the terminal shows up.
    val repositoryDirectory =
        (if (chatState.sessionId != null) chatState.sessionDirectory else chatState.selectedWorkspacePath)
            ?.takeIf { selectedRuntime?.kind == com.yugahashimoto.andcode.runtime.BackendKind.LOCAL }
    val repositoryUrl by produceState<String?>(null, selectedRuntime?.id, repositoryDirectory) {
        // produceState keeps its value across key changes: clear it first, so
        // the previous chat's link never shows while this one is looked up.
        value = null
        val directory = repositoryDirectory ?: return@produceState
        // Starting the shell can throw; a failure only hides the item.
        val origin =
            try {
                repositoryGit.originUrl(directory)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        origin.onSuccess { remote -> value = gitHubRepositoryUrl(remote) }
    }
    val mentionFiles by chatViewModel.fileMentions.results.collectAsState()
    val supervised by app.processSupervisor.processes.collectAsState()
    CompositionLocalProvider(
        LocalFileMentions provides FileMentionsUi(mentionFiles, chatViewModel.fileMentions::query),
        LocalSupervisedProcesses provides supervised,
    ) {
        ChatHomeScreen(
            repositoryUrl = repositoryUrl,
            findRequest = waiting.findRequest,
            onFindRequestConsumed = waiting.clearFindRequest,
            state = chatState,
            providers = settingsState.providers,
            agents = settingsState.agents,
            agentsLoading = settingsState.agentsLoading,
            selectedProviderId = chatState.selectedProviderId ?: fallbackProviderId,
            selectedModelId = chatState.selectedModelId ?: fallbackModelId,
            selectedAgentId = chatState.selectedAgentId ?: settingsState.agentId,
            runtimeTargets = runtimeTargets,
            selectedRuntimeId = selectedRuntime?.id,
            claudePermissionMode =
                workspaceState.claude
                    .takeIf { selectedRuntime?.agent == com.yugahashimoto.andcode.runtime.LocalAgent.CLAUDE_CODE }
                    ?.permissionMode,
            supportsPermissions = selectedRuntime?.capabilities?.permissions != false,
            onSelectClaudePermissionMode = { mode ->
                workspaceViewModel.setClaudePermissionMode(mode, chatState.sessionId)
            },
            // Only Claude Code can carry one: its CLI takes --append-system-prompt,
            // while agy has no equivalent flag, so the chip stays off elsewhere
            // rather than offering a control that would do nothing.
            systemPromptPresets =
                workspaceState.claude
                    .takeIf { selectedRuntime?.agent == com.yugahashimoto.andcode.runtime.LocalAgent.CLAUDE_CODE }
                    ?.systemPromptPresets
                    .orEmpty(),
            // The open chat's own preset, not the default new chats get: a session
            // keeps what it was created with, so naming the default here would show
            // a preset the send path is not going to use. Resolved off state rather
            // than by asking the backend, so a switch moves the chip on the tap
            // instead of on whatever recomposes next. A chat with no session yet
            // answers from its own draft, which is where its choice lives until the
            // first message creates the session to record it on.
            selectedSystemPromptId =
                workspaceState.claude
                    .takeIf { selectedRuntime?.agent == com.yugahashimoto.andcode.runtime.LocalAgent.CLAUDE_CODE }
                    ?.let { claude ->
                        // Presence of the draft decides, not its id: choosing None
                        // for this chat is a draft whose id is null, and an elvis
                        // here would read that as "no draft" and show the default
                        // while the first turn sent no prompt at all.
                        val draft = chatState.draftSystemPrompt
                        if (draft != null) draft.id else claude.systemPromptIdFor(chatState.sessionId)
                    },
            onSelectSystemPrompt = chatViewModel::selectSystemPrompt,
            // The mode settings shows, so the chip is not left naming whatever agent id
            // another runtime last remembered - see AntigravityTarget.listAgents.
            antigravityPermissionMode =
                antigravityState
                    .takeIf { selectedRuntime?.agent == com.yugahashimoto.andcode.runtime.LocalAgent.ANTIGRAVITY }
                    ?.permissionMode,
            onSelectAntigravityPermissionMode = { mode ->
                app.antigravityController.setPermissionMode(mode, chatState.sessionId)
            },
            onModelPickerClosed = waiting.markHandoffReady,
            onSelectRuntime = { id ->
                if (id != selectedRuntime?.id) {
                    if (chatState.messages.isNotEmpty()) {
                        actions.onHandoff(id)
                    } else {
                        app.runtimeRegistry.select(id)
                    }
                }
            },
            onOpenProviderSettings = {
                // OpenCode's provider list cannot connect Codex: its sign-in lives on
                // Codex's own settings screen.
                val codexSelected = selectedRuntime?.agent == com.yugahashimoto.andcode.runtime.LocalAgent.CODEX
                navController.navigate(if (codexSelected) ROUTE_SETTINGS_AGENT_CODEX else ROUTE_SETTINGS_PROVIDERS)
            },
            onSelectModel = settingsViewModel::selectModel,
            onSelectAgent = settingsViewModel::selectAgent,
            onRefreshAgents = app.catalogRepository::refreshAgents,
            selectedVariant = chatState.selectedVariant,
            onSelectVariant = chatViewModel::selectVariant,
            onToggleFastMode = chatViewModel::setFastMode,
            onToggleUltracode = chatViewModel::setUltracode,
            onAttach = actions.onAttach,
            onRemoveAttachment = chatViewModel::removeAttachment,
            onImageAttachment = { bitmap ->
                actions.importScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            com.yugahashimoto.andcode.runtime.local.AttachmentImporter(context).import(bitmap)
                        }
                    }.onSuccess { attachment ->
                        chatViewModel.addImageAttachment(attachment, bitmap)
                    }
                }
            },
            favoriteModelKeys = settingsState.favoriteModelKeys,
            recentModelKeys = settingsState.recentModelKeys,
            hiddenModelKeys =
                settingsState.hiddenModelKeys.takeIf {
                    selectedRuntime?.capabilities?.providerModelList == true
                }.orEmpty(),
            onToggleFavorite = settingsViewModel::toggleFavoriteModel,
            onSelectQuestionAnswer = chatViewModel::selectQuestionAnswer,
            onSubmitQuestion = chatViewModel::submitQuestion,
            onCancelQuestion = chatViewModel::cancelQuestion,
            onDismissQuestion = chatViewModel::dismissQuestion,
            onDismissTodoBar = chatViewModel::dismissTodoBar,
            onDismissPullRequest = chatViewModel::dismissPullRequest,
            autoAcceptPermissions = settingsState.autoAcceptPermissions,
            onToggleAutoAccept = settingsViewModel::setAutoAcceptPermissions,
            enterToSend = preferences.enterToSend,
            autoExpandReasoning = preferences.autoExpandReasoning,
            onSendMessage = chatViewModel::sendMessage,
            onSendReview = { review, sent ->
                sendGate {
                    chatViewModel.sendReview(review)
                    sent()
                }
            },
            canEditMessages = selectedRuntime?.capabilities?.editMessages == true,
            canRewind = selectedRuntime?.capabilities?.checkpoints == true,
            onRewind = chatViewModel::rewindTo,
            onUndoRewind = chatViewModel::undoRewind,
            canRestoreFiles = chatViewModel::canRestoreFiles,
            onRestoreFiles = chatViewModel::restoreFilesTo,
            onUndoFileRestore = chatViewModel::undoFileRestore,
            onDismissFileRestore = chatViewModel::dismissFileRestore,
            onEditLastMessage = chatViewModel::editLastUserMessage,
            onEditDraftConsumed = chatViewModel::consumeEditDraft,
            onPermission = chatViewModel::respondToPermission,
            onAbort = chatViewModel::abort,
            onRecheckStall = chatViewModel::checkForStall,
            onMic = actions.onMic,
            onNewChat = {
                waiting.clearSession()
                chatViewModel.newSession()
            },
            onOpenLocalSetup = {
                navController.navigate(ROUTE_ANDROID_SETUP) { launchSingleTop = true }
            },
            onOpenRemoteSetup = {
                navController.navigate(ROUTE_REMOTE_CONNECTION) { launchSingleTop = true }
            },
            onRefreshCatalog = app.catalogRepository::refreshProvidersOnly,
            onOpenDrawer = {
                app.catalogRepository.refreshSessionsOnly()
                actions.onOpenDrawer()
            },
            subagents = state.subagents,
            onSubagentClick = { childSessionId ->
                val childSession = activityState.sessions.firstOrNull { it.id == childSessionId }
                app.activityRepository.markSessionRead(childSessionId)
                // The chat this was opened from is the way back, so open the child
                // directly instead of routing through the waiting session, which would drop
                // the parent.
                waiting.clearSession()
                chatViewModel.openSubagentSession(
                    childSessionId,
                    childSession?.title ?: childSessionId,
                )
            },
            onReturnToParentSession = {
                waiting.clearSession()
                chatViewModel.openParentSession()
            },
            onOpenUrl = { url ->
                UrlLauncher.openUrl(context, url)
            },
            onOpenPatchDiff = chatViewModel::openPatchDiff,
            onDismissPatchDiff = chatViewModel::dismissPatchDiff,
            onOpenArtifact = chatViewModel::openArtifact,
            onDismissArtifact = chatViewModel::dismissArtifact,
            onOpenLocalUrl = { url -> navController.navigate(guestBrowserRoute(url)) { launchSingleTop = true } },
            onLoadDraft = { sessionId -> chatViewModel.loadDraft(sessionId)?.text },
            onSaveDraft = { sessionId, text -> chatViewModel.saveDraft(sessionId, text) },
            savedPrompts = preferences.savedPrompts,
        )
    }
}

/** The app objects the chat route works with. */
internal class ChatRouteDeps(
    val app: AndCodeApplication,
    val navController: NavController,
    val chatViewModel: ChatViewModel,
    val settingsViewModel: SettingsViewModel,
    val workspaceViewModel: WorkspaceViewModel,
    val repositoryGit: GitClient,
)

/** The app's state the chat route reads, as of this composition. */
internal class ChatRouteState(
    val chat: ChatUiState,
    val settings: SettingsUiState,
    val workspace: WorkspaceUiState,
    val antigravity: AntigravityControllerState,
    val activity: ActivityUiState,
    val preferences: AppPreferences,
    val runtime: RuntimeTarget?,
    val runtimeTargets: List<RuntimeTarget>,
    val subagents: List<SubagentInfo>,
)

/**
 * What waits for the chat screen (a chat to open, a handoff prompt to send once the model sheet
 * closes, text to find) and how the route marks each one done.
 */
internal class ChatRouteWaiting(
    val session: Pair<String, String>?,
    val handoffPrompt: Pair<String, String>?,
    val handoffReady: Boolean,
    val findRequest: Pair<String, String>?,
    val clearSession: () -> Unit,
    /** The handoff is consumed: neither it nor the readiness that sent it stays behind. */
    val takeHandoff: () -> Unit,
    val markHandoffReady: () -> Unit,
    val clearFindRequest: () -> Unit,
)

/** The chat route's actions that stay in AndCodeApp, which holds their launchers and the drawer. */
internal class ChatRouteActions(
    val onHandoff: (String) -> Unit,
    val onAttach: () -> Unit,
    val onMic: () -> Unit,
    val onOpenDrawer: () -> Unit,
    /** Outlives the route, so an image being imported is not dropped when the chat is left. */
    val importScope: CoroutineScope,
)

private object ChatRouteTiming {
    /** How often the open chat asks GitHub whether its pull requests have moved on. */
    const val PULL_REQUEST_REFRESH_INTERVAL_MS = 30_000L
}
