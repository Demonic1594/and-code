package com.yugahashimoto.andcode.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.data.settings.SavedPrompt
import com.yugahashimoto.andcode.feature.workspace.GitHubReference
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.local.AntigravityPermissionMode
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode
import com.yugahashimoto.andcode.runtime.local.SystemPromptPreset
import com.yugahashimoto.andcode.ui.components.HapticEvent
import com.yugahashimoto.andcode.ui.components.SessionStatus
import com.yugahashimoto.andcode.ui.components.StatusChip
import com.yugahashimoto.andcode.ui.components.StatusDot
import com.yugahashimoto.andcode.ui.components.rememberHaptics
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** What the long-press action sheet offers for one message: its id, its text to copy, and whether "Edit & resend" applies. */
private data class MessageActionTarget(
    val id: String,
    val text: String,
    val canEdit: Boolean,
    val canRewind: Boolean = false,
)

private const val DRAFT_SAVE_DEBOUNCE_MS = 400L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatHomeScreen(
    state: ChatUiState,
    providers: List<OpenCodeProvider>,
    agents: List<OpenCodeAgent>,
    selectedProviderId: String?,
    selectedModelId: String?,
    selectedAgentId: String?,
    runtimeTargets: List<RuntimeTarget>,
    selectedRuntimeId: String?,
    onSelectRuntime: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onSelectAgent: (String) -> Unit,
    /** Asks the runtime for its agents again; the mode chip calls it when its list is empty. */
    onRefreshAgents: () -> Unit = {},
    agentsLoading: Boolean = false,
    selectedVariant: String? = null,
    onSelectVariant: (String?) -> Unit = {},
    /**
     * Turns Codex's fast service tier on or off. The chip for it shows only while
     * [ChatUiState.fastModeAvailable], i.e. for a Codex model that lists the tier.
     */
    onToggleFastMode: (Boolean) -> Unit = {},
    attachments: List<PromptAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    favoriteModelKeys: Set<String> = emptySet(),
    recentModelKeys: List<String> = emptyList(),
    hiddenModelKeys: Set<String> = emptySet(),
    onToggleFavorite: (String, String) -> Unit = { _, _ -> },
    /** Non-null only for runtimes that decide tool permissions per session, i.e. Claude Code. */
    claudePermissionMode: ClaudePermissionMode? = null,
    onSelectClaudePermissionMode: (ClaudePermissionMode) -> Unit = {},
    /**
     * System-prompt presets to switch between, empty for runtimes that cannot carry one.
     *
     * Switching is a per-task choice - Coding for one turn, Debug for the next - so it belongs on
     * the composer next to the thinking chip rather than only in settings.
     */
    systemPromptPresets: List<SystemPromptPreset> = emptyList(),
    selectedSystemPromptId: String? = null,
    onSelectSystemPrompt: (String?) -> Unit = {},
    /** The same, for Antigravity: non-null only while that runtime is the selected one. */
    antigravityPermissionMode: AntigravityPermissionMode? = null,
    onSelectAntigravityPermissionMode: (AntigravityPermissionMode) -> Unit = {},
    /**
     * Called once the model and runtime sheet closes.
     *
     * Switching runtime and picking a model happen in the same sheet, so anything that reacts to a
     * switch has to wait for the sheet to close or it acts on a half-made choice.
     */
    onModelPickerClosed: () -> Unit = {},
    onSelectQuestionAnswer: (String, Int, String) -> Unit,
    onSubmitQuestion: (String) -> Unit,
    onCancelQuestion: (String) -> Unit = {},
    onDismissQuestion: (String) -> Unit = {},
    onDismissTodoBar: (String) -> Unit = {},
    /** Hides one pull request badge above the composer, by its ref key. */
    onDismissPullRequest: (String) -> Unit = {},
    autoAcceptPermissions: Boolean = false,
    /** False for a runtime that never raises permission prompts, so the auto-accept chip is hidden. */
    supportsPermissions: Boolean = true,
    onToggleAutoAccept: (Boolean) -> Unit = {},
    sendBehavior: String = "interrupt",
    enterToSend: Boolean = false,
    /** Whether reasoning/thinking cards should start expanded when the activity sheet opens. */
    autoExpandReasoning: Boolean = false,
    onSendMessage: (String) -> Unit,
    /** Sends the diff review's comments without the composer's draft or attachments. */
    onSendReview: (String) -> Unit = {},
    /** False for a runtime that cannot delete a message server-side, so "Edit & resend" is hidden. */
    canEditMessages: Boolean = false,
    /** Removes the last user message (and its reply) and hands its text back via [state]'s edit draft. */
    onEditLastMessage: () -> Unit = {},
    onEditDraftConsumed: () -> Unit = {},
    /** False for a runtime with no checkpoints, so "Rewind to here" is hidden. */
    canRewind: Boolean = false,
    /** Rewinds the chat, files included, to just before the given message of the user's. */
    onRewind: (String) -> Unit = {},
    onUndoRewind: () -> Unit = {},
    onPermission: (String, PermissionResponse, Boolean) -> Unit,
    onAbort: () -> Unit,
    /** Probes the quiet run again now, rather than waiting for the next scheduled check. */
    onRecheckStall: () -> Unit = {},
    onMic: () -> Unit,
    onNewChat: () -> Unit,
    onOpenLocalSetup: () -> Unit,
    onOpenRemoteSetup: () -> Unit,
    onRefreshCatalog: () -> Unit = {},
    onOpenProviderSettings: (() -> Unit)? = null,
    onOpenDrawer: () -> Unit,
    subagents: List<SubagentInfo> = emptyList(),
    onSubagentClick: (String) -> Unit = {},
    onReturnToParentSession: () -> Unit = {},
    githubRefs: List<GitHubReference> = emptyList(),
    onOpenUrl: (String) -> Unit = {},
    onImageAttachment: (Bitmap) -> Unit = {},
    /** Fetches and opens the diff dialog for a tapped [ChatPart.Patch] card. */
    onOpenPatchDiff: (ChatPart.Patch) -> Unit = {},
    onDismissPatchDiff: () -> Unit = {},
    /** Opens a page, drawing, document or picture the agent wrote in the artifact preview. */
    onOpenArtifact: (Artifact.File) -> Unit = {},
    onDismissArtifact: () -> Unit = {},
    /** Opens a server on this device (a dev server the agent started) in the in-app browser. */
    onOpenLocalUrl: ((String) -> Unit)? = null,
    /** Reads the unsent text kept for a session, for restoring after the app was closed. */
    onLoadDraft: (String) -> String? = { null },
    /** Keeps the composer's unsent text for a session; blank text clears it. */
    onSaveDraft: (String, String) -> Unit = { _, _ -> },
    /** The user's saved prompts, offered in the slash popup next to the built-in commands. */
    savedPrompts: List<SavedPrompt> = emptyList(),
    /**
     * Opens the find bar on a chat with this text, e.g. from a search result in another screen,
     * as (session id, query); then [onFindRequestConsumed].
     */
    findRequest: Pair<String, String>? = null,
    onFindRequestConsumed: () -> Unit = {},
) {
    // Saved state, not plain remember: the Activity is recreated on rotation and other
    // configuration changes, and a remembered field would come back empty. The TextFieldValue
    // saver also keeps the cursor and selection, not just the string (issue #355).
    var inputValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val input = inputValue.text
    val listState = rememberLazyListState()
    var showModelPicker by remember { mutableStateOf(false) }
    val errorKind = classifyChatError(state.error)
    val runtimeNotReady = errorKind == ChatErrorKind.RUNTIME_NOT_READY && state.messages.isEmpty()
    val isAtBottom = remember { mutableStateOf(true) }
    var showActionSheet by remember { mutableStateOf<MessageActionTarget?>(null) }
    var rewindTarget by remember { mutableStateOf<MessageActionTarget?>(null) }
    var showArtifacts by remember { mutableStateOf(false) }
    var activityGroupId by remember { mutableStateOf<String?>(null) }
    var selectedImage by remember { mutableStateOf<ChatImageSource?>(null) }
    // A rewound chat shows only what comes before the point it was rewound to.
    val shownMessages = state.visibleMessages
    val timelineEntries = remember(shownMessages) { groupConversationTimeline(shownMessages) }
    // Find in chat: which rows match, and which of them is the current one. A new query starts at
    // the newest match, since the chat itself sits at the bottom.
    var findActive by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var findIndex by remember { mutableStateOf(-1) }
    // The chat the find bar belongs to: switching to another chat closes it.
    var findSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val findMatches =
        remember(timelineEntries, findQuery, findActive) {
            if (findActive) chatFindMatches(timelineEntries, findQuery) else emptyList()
        }
    // Resolved on every read rather than corrected in an effect: a longer query often matches the
    // very same rows, an equal list that would never re-run such an effect.
    val currentFindIndex = resolveFindIndex(findIndex, findMatches.size)
    val currentMatchRow = findMatches.getOrNull(currentFindIndex)

    // The pre-Q save flow goes through the system file picker; holding the pending image as plain
    // strings (the bitmap-bearing source is not saveable) survives the activity recreation the
    // picker can cause, instead of silently no-oping the save afterwards.
    var pendingSaveUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSaveMime by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSaveFilename by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var showSlashCommands by remember { mutableStateOf(false) }
    var showSidePanel by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    // Scanned only while the menu or the sheet is open, not on every streamed token.
    val artifacts =
        remember(shownMessages, showChatMenu || showArtifacts) {
            if (showChatMenu || showArtifacts) collectArtifacts(shownMessages) else emptyList()
        }
    val haptics = rememberHaptics()
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageSaveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
            val source =
                pendingSaveUrl?.let { url ->
                    ChatImageSource(url = url, mime = pendingSaveMime ?: "image/*", filename = pendingSaveFilename)
                }
            pendingSaveUrl = null
            pendingSaveMime = null
            pendingSaveFilename = null
            if (uri != null && source != null) {
                coroutineScope.launch {
                    val saved = saveChatImageToUri(context, source, uri)
                    android.widget.Toast.makeText(
                        context,
                        if (saved) R.string.image_saved else R.string.image_save_failed,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicturePreview(),
        ) { bitmap ->
            if (bitmap != null) {
                onImageAttachment(bitmap)
            }
        }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                cameraLauncher.launch(null)
            }
        }
    val showAttachFailure = {
        android.widget.Toast.makeText(
            context,
            R.string.attachment_load_failed,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
    val attachImageUris: suspend (List<Uri>) -> Unit = { uris ->
        uris.forEach { uri ->
            val bitmap = decodeImageUri(context, uri)
            if (bitmap != null) onImageAttachment(bitmap) else showAttachFailure()
        }
    }
    val galleryLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(),
        ) { uris -> coroutineScope.launch { attachImageUris(uris) } }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = info.totalItemsCount
            totalItems == 0 || lastVisible >= totalItems - 1
        }.collect { atBottom -> isAtBottom.value = atBottom }
    }

    // Declared before the request effect, so a request for a chat that is still loading is not
    // closed by this check on the same frame.
    LaunchedEffect(state.sessionId) {
        if (findActive && findSessionId != state.sessionId) findActive = false
    }
    LaunchedEffect(findRequest) {
        val (sessionId, query) = findRequest ?: return@LaunchedEffect
        findActive = true
        findSessionId = sessionId
        findQuery = query
        findIndex = -1
        onFindRequestConsumed()
    }
    // Keyed on the row, not the list, so a reply streaming in does not yank the view. Once shown,
    // the position is pinned, so a new match arriving later does not move it either.
    LaunchedEffect(currentMatchRow, findQuery) {
        val row = currentMatchRow ?: return@LaunchedEffect
        findIndex = currentFindIndex
        isAtBottom.value = false
        listState.animateScrollToItem(row)
    }

    LaunchedEffect(timelineEntries.size, state.permissions.size, state.pendingQuestions.size) {
        val totalItems = timelineEntries.size + state.permissions.size + state.pendingQuestions.size
        if (totalItems > 0 && isAtBottom.value) listState.animateScrollToItem(totalItems - 1)
    }

    LaunchedEffect(state.partialText) {
        if ((state.isListening || state.isSpeechProcessing) && state.partialText.isNotBlank()) {
            inputValue = TextFieldValue(state.partialText, TextRange(state.partialText.length))
        }
    }

    // Keeps the unsent text beyond this composition: the saved state above survives rotation and
    // the system reclaiming the process, while the stored draft also survives the user closing the
    // app and gives each chat its own text. A draft restored on first show only fills an empty
    // field, so it never overwrites what is being typed or text carried over from before a
    // configuration change; moving to another chat swaps the field for that chat's draft instead
    // of leaking the previous chat's text into it. A chat without a session yet has nothing to
    // key a draft on and relies on the saved state alone.
    val draftSessionId = state.sessionId
    var lastDraftSessionId by remember { mutableStateOf(draftSessionId) }
    LaunchedEffect(draftSessionId) {
        val switched =
            lastDraftSessionId != null && draftSessionId != null && lastDraftSessionId != draftSessionId
        lastDraftSessionId = draftSessionId
        if (draftSessionId != null && (switched || inputValue.text.isEmpty())) {
            val draft = onLoadDraft(draftSessionId)?.takeIf { it.isNotEmpty() }.orEmpty()
            if (switched || draft.isNotEmpty()) {
                inputValue = TextFieldValue(draft, TextRange(draft.length))
            }
        }
    }
    LaunchedEffect(draftSessionId, input) {
        if (draftSessionId != null) {
            // Debounced so a burst of typing is one write; sending clears the stored draft itself.
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            onSaveDraft(draftSessionId, input)
        }
    }

    // One-shot handoff from ChatViewModel.editLastUserMessage: it drops the message (and its
    // reply) from the transcript optimistically and deletes them server-side in the background,
    // handing its text back here so the composer opens on it ready to correct and resend rather
    // than the user having to retype it.
    LaunchedEffect(state.editDraft) {
        state.editDraft?.let { draft ->
            // A rewind hands its message back as a convenience; it must not overwrite something
            // the user was already typing. An edit always fills it: its message is gone otherwise.
            if (state.rewoundFrom == null || inputValue.text.isBlank()) {
                inputValue = TextFieldValue(draft, TextRange(draft.length))
            }
            onEditDraftConsumed()
        }
    }

    // A subagent session is a detour, not a destination: the system back gesture returns to the
    // main agent instead of leaving the chat, matching the in-chat return banner.
    BackHandler(enabled = state.parentSession != null && !findActive) { onReturnToParentSession() }
    BackHandler(enabled = findActive) { findActive = false }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(showSidePanel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val onRightEdge = down.position.x > size.width - 80f || showSidePanel
                        if (!onRightEdge) return@awaitEachGesture
                        awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        var offset = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dragAmount = change.positionChange().x
                            if (dragAmount == 0f) continue
                            change.consume()
                            offset += dragAmount
                            if (offset < -100f) {
                                showSidePanel = true
                                break
                            }
                            if (offset > 100f && showSidePanel) {
                                showSidePanel = false
                                break
                            }
                        }
                    }
                },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            if (findActive) {
                ChatFindBar(
                    query = findQuery,
                    onQueryChange = {
                        findQuery = it
                        findIndex = -1
                    },
                    current = currentFindIndex,
                    total = findMatches.size,
                    onOlder = { if (findMatches.isNotEmpty()) findIndex = (currentFindIndex - 1 + findMatches.size) % findMatches.size },
                    onNewer = { if (findMatches.isNotEmpty()) findIndex = (currentFindIndex + 1) % findMatches.size },
                    onClose = { findActive = false },
                )
            } else {
                TopAppBar(
                    // Title over a quiet status line, the way desktop agent apps head a session: what
                    // this chat is about, then which agent runs it and whether it is working right
                    // now - so a glance at the top says if the run you left is still going.
                    title = {
                        Column {
                            Text(
                                text = state.sessionTitle.ifBlank { stringResource(R.string.chat_home_title) },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.backendName.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    StatusDot(
                                        status =
                                            when {
                                                state.permissions.isNotEmpty() -> SessionStatus.PERMISSION
                                                state.isRunning -> SessionStatus.RUNNING
                                                state.isConnected -> SessionStatus.WAITING
                                                else -> SessionStatus.IDLE
                                            },
                                        size = 6.dp,
                                    )
                                    Text(
                                        text = state.backendName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu_description))
                        }
                    },
                    actions = {
                        IconButton(onClick = onNewChat) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat))
                        }
                        if (state.messages.isNotEmpty()) {
                            IconButton(onClick = {
                                findActive = true
                                findSessionId = state.sessionId
                                findIndex = -1
                            }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.chat_find_open))
                            }
                            Box {
                                IconButton(onClick = { showChatMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_menu_more))
                                }
                                ChatOverflowMenu(
                                    expanded = showChatMenu,
                                    onDismiss = { showChatMenu = false },
                                    usage = state.sessionUsage,
                                    artifactCount = artifacts.size,
                                    onArtifacts = {
                                        showChatMenu = false
                                        showArtifacts = true
                                    },
                                    onShare = {
                                        showChatMenu = false
                                        val title = state.sessionTitle.ifBlank { context.getString(R.string.chat_home_title) }
                                        val labels =
                                            TranscriptLabels(
                                                user = context.getString(R.string.transcript_user),
                                                assistant = context.getString(R.string.transcript_assistant),
                                                tool = context.getString(R.string.transcript_tool),
                                                error = context.getString(R.string.transcript_error),
                                            )
                                        shareChatTranscript(
                                            context = context,
                                            title = title,
                                            markdown = chatTranscriptMarkdown(title, shownMessages, labels),
                                            chooserTitle = context.getString(R.string.chat_menu_share),
                                        )
                                    },
                                    onCopy = {
                                        showChatMenu = false
                                        val title = state.sessionTitle.ifBlank { context.getString(R.string.chat_home_title) }
                                        val labels =
                                            TranscriptLabels(
                                                user = context.getString(R.string.transcript_user),
                                                assistant = context.getString(R.string.transcript_assistant),
                                                tool = context.getString(R.string.transcript_tool),
                                                error = context.getString(R.string.transcript_error),
                                            )
                                        val markdown = chatTranscriptMarkdown(title, shownMessages, labels)
                                        val copied =
                                            markdown.length <= MAX_CLIPBOARD_TRANSCRIPT_CHARS &&
                                                runCatching { clipboardManager.setText(AnnotatedString(markdown)) }.isSuccess
                                        if (copied) {
                                            haptics.perform(HapticEvent.CONFIRM)
                                        } else {
                                            // Too big for the clipboard: hand it over as a file instead.
                                            haptics.perform(HapticEvent.REJECT)
                                            Toast.makeText(context, R.string.chat_copy_too_long, Toast.LENGTH_LONG).show()
                                            shareChatTranscript(context, title, markdown, context.getString(R.string.chat_menu_share))
                                        }
                                    },
                                )
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                )
            }

            state.parentSession?.let { parent ->
                SubagentSessionBanner(
                    parentTitle = parent.title,
                    onReturn = onReturnToParentSession,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoadingHistory -> LoadingState()
                    runtimeNotReady ->
                        RuntimeSetupRequiredState(
                            onOpenLocalSetup = onOpenLocalSetup,
                            onOpenRemoteSetup = onOpenRemoteSetup,
                        )
                    shownMessages.isEmpty() && !state.isRunning && state.error == null &&
                        state.permissions.isEmpty() && state.pendingQuestions.isEmpty() ->
                        ChatWelcome(
                            agent = LocalAgent.entries.firstOrNull { it.targetId == selectedRuntimeId },
                            onSuggestion = { prompt -> inputValue = TextFieldValue(prompt, TextRange(prompt.length)) },
                        )
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // "Edit & resend" is offered only on the conversation's very last user
                            // message: editing an earlier one would also discard every turn after
                            // it, which is a bigger, riskier action than this affordance is meant
                            // for (see issue #269).
                            val lastUserMessageId = shownMessages.lastOrNull { it.isUser }?.id
                            itemsIndexed(timelineEntries, key = { _, entry -> entry.id }) { index, entry ->
                                val copyable =
                                    when (entry) {
                                        is TimelineEntry.UserMessage ->
                                            MessageActionTarget(
                                                id = entry.message.id,
                                                text = entry.message.text,
                                                canEdit =
                                                    canEditMessages && !state.isRunning && state.rewoundFrom == null &&
                                                        entry.message.id == lastUserMessageId,
                                                canRewind = canRewind && !state.isRunning && !state.isRewinding,
                                            )
                                        is TimelineEntry.Body ->
                                            MessageActionTarget(entry.messageId, entry.part.text, canEdit = false)
                                        is TimelineEntry.Image -> null
                                        is TimelineEntry.Activity -> null
                                        is TimelineEntry.Todo -> null
                                        is TimelineEntry.Changes -> null
                                        is TimelineEntry.Error -> null
                                        is TimelineEntry.Footer -> null
                                    }
                                val findTint =
                                    when {
                                        index == currentMatchRow -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        index in findMatches -> MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                                        else -> Color.Transparent
                                    }
                                Box(
                                    modifier =
                                        Modifier
                                            .background(findTint, RoundedCornerShape(12.dp))
                                            .then(
                                                if (copyable == null) {
                                                    Modifier
                                                } else {
                                                    Modifier.combinedClickable(
                                                        onClick = {},
                                                        onLongClick = { showActionSheet = copyable },
                                                    )
                                                },
                                            ),
                                ) {
                                    TimelineEntryRow(
                                        entry,
                                        onOpenActivity = { activityGroupId = it },
                                        onImageClick = { selectedImage = it },
                                        onOpenProviderSettings = onOpenProviderSettings,
                                        onOpenChanges = onOpenPatchDiff,
                                        onPreviewArtifact = onOpenArtifact,
                                    )
                                }
                            }
                            // A run that has gone quiet says so here, in place of the "Processing"
                            // label that otherwise looks identical whether it is working or dead.
                            if (state.isRunning && state.stall != null) {
                                item(key = "stall") {
                                    ChatStallCard(
                                        stall = state.stall,
                                        onRecheck = onRecheckStall,
                                        onStop = onAbort,
                                    )
                                }
                            } else if (state.isRunning && state.retry != null) {
                                // The runtime is waiting out a provider failure. Saying so beats a
                                // "Processing" label that reads the same as a model that is just slow.
                                item(key = "retry") { ChatRetryCard(retry = state.retry) }
                            } else if (state.isRunning && timelineEntries.isNotEmpty()) {
                                item(key = "processing") {
                                    Text(
                                        text = stringResource(R.string.processing),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(state.permissions, key = { "permission-${it.id}" }) { permission ->
                                PermissionCard(permission) { id, response, always ->
                                    haptics.perform(
                                        if (response == PermissionResponse.REJECT) HapticEvent.REJECT else HapticEvent.CONFIRM,
                                    )
                                    onPermission(id, response, always)
                                }
                            }
                            items(state.pendingQuestions, key = { "question-${it.request.id}" }) { question ->
                                QuestionCard(
                                    question = question,
                                    onAnswerSelected = onSelectQuestionAnswer,
                                    onSubmit = onSubmitQuestion,
                                    onCancel = onCancelQuestion,
                                    onDismiss = onDismissQuestion,
                                )
                            }
                            // Not while a stall is up: a turn that has produced nothing never
                            // cleared this flag, so the warning would sit directly above a chip
                            // cheerfully reporting that the same turn is thinking.
                            if (state.isThinking && state.stall == null && state.retry == null) {
                                item { StatusChip(text = stringResource(R.string.thinking), active = true) }
                            }
                            state.error?.let { error ->
                                item {
                                    ChatErrorCard(
                                        error = error,
                                        kind = errorKind ?: ChatErrorKind.GENERIC,
                                        onOpenLocalSetup = onOpenLocalSetup,
                                        onOpenRemoteSetup = onOpenRemoteSetup,
                                        onOpenProviderSettings = onOpenProviderSettings,
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isAtBottom.value) {
                    SmallFloatingActionButton(
                        onClick = {
                            isAtBottom.value = true
                            coroutineScope.launch {
                                val totalItems = timelineEntries.size + state.permissions.size + state.pendingQuestions.size
                                if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.cd_scroll_to_bottom))
                    }
                }
            }

            SubagentsTrack(subagents = subagents, onSubagentClick = onSubagentClick)

            val latestTodoEntry = timelineEntries.filterIsInstance<TimelineEntry.Todo>().lastOrNull()
            val activeTodos =
                latestTodoEntry
                    // A file the agent deletes off its own disk, or a task list it never marks
                    // complete, never sends this app an update - so a manual dismissal, kept keyed
                    // to this specific update, is the only reliable way to close the bar.
                    ?.takeIf { it.id != state.dismissedTodoBarId }
                    ?.todos
                    ?.takeIf { list -> list.any { it.status != "completed" } }
            if (activeTodos != null && latestTodoEntry != null) {
                StickyTodoBar(
                    todos = activeTodos,
                    onDismiss = { onDismissTodoBar(latestTodoEntry.id) },
                )
            }

            val hiddenCount = state.messages.size - shownMessages.size
            if (state.rewoundFrom != null && hiddenCount > 0) {
                RewindBanner(
                    hiddenCount = hiddenCount,
                    busy = state.isRewinding,
                    onUndo = onUndoRewind,
                )
            }

            if (!runtimeNotReady) {
                ChatComposer(
                    inputValue = inputValue,
                    onInputValueChange = {
                        inputValue = it
                        showSlashCommands = it.text.startsWith("/")
                    },
                    isRunning = state.isRunning,
                    onSend = {
                        // Held while a rewind lands: which messages the new one follows is not settled yet.
                        if (!state.isRewinding && (input.isNotBlank() || state.attachments.isNotEmpty())) {
                            haptics.perform(HapticEvent.CONFIRM)
                            onSendMessage(SlashCommandRegistry.expandSavedPrompt(input, savedPrompts))
                            inputValue = TextFieldValue("")
                            showSlashCommands = false
                        }
                    },
                    onAbort = onAbort,
                    onMic = onMic,
                    isListening = state.isListening,
                    isSpeechProcessing = state.isSpeechProcessing,
                    modelLabel =
                        providers
                            .firstOrNull { it.id == selectedProviderId }
                            ?.models?.get(selectedModelId)
                            ?.name
                            ?: selectedModelId
                            ?: stringResource(R.string.chat_model_short_default),
                    onModelChipClick = {
                        onRefreshCatalog()
                        showModelPicker = true
                    },
                    agents = agents,
                    selectedAgentId = selectedAgentId,
                    onSelectAgent = onSelectAgent,
                    onRefreshAgents = onRefreshAgents,
                    agentsLoading = agentsLoading,
                    thinkingOptions =
                        providers
                            .firstOrNull { it.id == selectedProviderId }
                            ?.models?.get(selectedModelId)
                            ?.variants?.keys?.toList() ?: emptyList(),
                    selectedVariant = state.selectedVariant,
                    onSelectVariant = onSelectVariant,
                    fastModeAvailable = state.fastModeAvailable,
                    fastModeEnabled = state.fastModeEnabled,
                    fastModeDescription = state.fastModeDescription,
                    onToggleFastMode = onToggleFastMode,
                    attachments = state.attachments,
                    onAttach = onAttach,
                    onRemoveAttachment = onRemoveAttachment,
                    autoAcceptPermissions = autoAcceptPermissions,
                    supportsPermissions = supportsPermissions,
                    claudePermissionMode = claudePermissionMode,
                    onSelectClaudePermissionMode = onSelectClaudePermissionMode,
                    antigravityPermissionMode = antigravityPermissionMode,
                    onSelectAntigravityPermissionMode = onSelectAntigravityPermissionMode,
                    systemPromptPresets = systemPromptPresets,
                    selectedSystemPromptId = selectedSystemPromptId,
                    onSelectSystemPrompt = onSelectSystemPrompt,
                    onToggleAutoAccept = onToggleAutoAccept,
                    sendBehavior = sendBehavior,
                    enterToSend = enterToSend,
                    contextTokensUsed = state.contextTokensUsed,
                    contextLimit =
                        providers
                            .firstOrNull { it.id == selectedProviderId }
                            ?.models?.get(selectedModelId)
                            ?.limit?.context ?: 0L,
                    tokensPerSecond = state.connectionQuality?.tokensPerSecond ?: 0.0,
                    showSlashCommands = showSlashCommands,
                    slashCommands = state.slashCommands,
                    slashSkills = state.slashSkills,
                    savedPrompts = savedPrompts,
                    onSlashCommandSelect = { suggestion ->
                        // A saved prompt expands to its text, left in the composer to send or tweak.
                        // One that takes arguments ($ARGUMENTS, $1...) completes to its command instead,
                        // so the user types the arguments and sending fills them in. Everything else
                        // completes to its command name for the send path to route.
                        val completed =
                            when {
                                suggestion is SlashSuggestion.Saved && !SlashCommandRegistry.hasPlaceholders(suggestion.prompt.text) ->
                                    suggestion.prompt.text
                                else -> suggestion.name + " "
                            }
                        inputValue = TextFieldValue(completed, TextRange(completed.length))
                        showSlashCommands = false
                    },
                    githubRefs = githubRefs,
                    pullRequests = state.pullRequests,
                    onOpenUrl = onOpenUrl,
                    onDismissPullRequest = onDismissPullRequest,
                    // The composer thumbnails render the ViewModel's previews directly: a local
                    // copy of the list drifted out of step with the attachments it stood for, so a
                    // removal could drop the wrong image or leave a thumbnail with nothing behind it.
                    attachedImages = state.imagePreviews,
                    onRemoveImage = { previewIndex ->
                        attachmentIndexForPreview(state.attachments, previewIndex)?.let(onRemoveAttachment)
                    },
                    onCameraLaunch = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            cameraLauncher.launch(null)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onGalleryLaunch = {
                        galleryLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onPasteImage = {
                        val clip = context.primaryClipOrNull()
                        coroutineScope.launch {
                            val uris = clip?.imageUris(context.contentResolver).orEmpty()
                            if (uris.isEmpty()) showAttachFailure() else attachImageUris(uris)
                        }
                    },
                    onReceiveImageContent = { clip ->
                        // The keyboard delivered the image itself; the same import path as a
                        // clipboard paste, only the clip's origin differs.
                        coroutineScope.launch {
                            val uris = clip.imageUris(context.contentResolver)
                            if (uris.isEmpty()) showAttachFailure() else attachImageUris(uris)
                        }
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = showSidePanel,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier =
                    Modifier
                        .width(280.dp)
                        .fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.side_panel_files_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.side_panel_files_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        ModelAndRuntimePickerSheet(
            runtimeTargets = runtimeTargets,
            selectedRuntimeId = selectedRuntimeId,
            onSelectRuntime = onSelectRuntime,
            providers = providers,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            onSelectModel = { providerId, modelId ->
                onSelectModel(providerId, modelId)
                showModelPicker = false
                onModelPickerClosed()
            },
            favoriteModelKeys = favoriteModelKeys,
            recentModelKeys = recentModelKeys,
            hiddenModelKeys = hiddenModelKeys,
            onToggleFavorite = onToggleFavorite,
            onConnectProvider =
                onOpenProviderSettings?.let { open ->
                    {
                        showModelPicker = false
                        onModelPickerClosed()
                        open()
                    }
                },
            onDismiss = {
                showModelPicker = false
                onModelPickerClosed()
            },
        )
    }

    activityGroupId?.let { groupId ->
        val parts = findActivityParts(state.messages, groupId)
        if (parts.isNotEmpty()) {
            AssistantActivitySheet(
                parts = parts,
                autoExpandReasoning = autoExpandReasoning,
                onDismiss = { activityGroupId = null },
                onOpenDiff = onOpenPatchDiff,
            )
        }
    }

    state.patchDiff?.let { patchDiff ->
        ChatDiffDialog(state = patchDiff, onDismiss = onDismissPatchDiff, onSendReview = onSendReview)
    }

    if (showArtifacts) {
        ArtifactsSheet(
            artifacts = artifacts,
            onOpenFile = { artifact ->
                showArtifacts = false
                onOpenArtifact(artifact)
            },
            onOpenServer = { url ->
                showArtifacts = false
                onOpenLocalUrl?.invoke(url)
            },
            onDismiss = { showArtifacts = false },
        )
    }

    state.artifactPreview?.let { preview ->
        ArtifactPreviewDialog(state = preview, onDismiss = onDismissArtifact)
    }

    rewindTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { rewindTarget = null },
            icon = { Icon(Icons.Default.History, contentDescription = null) },
            title = { Text(stringResource(R.string.rewind_confirm_title)) },
            text = { Text(stringResource(R.string.rewind_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onRewind(target.id)
                    rewindTarget = null
                }) { Text(stringResource(R.string.rewind_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { rewindTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    showActionSheet?.let { target ->
        ModalBottomSheet(onDismissRequest = { showActionSheet = null }) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(target.text))
                        showActionSheet = null
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy_markdown)) },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(target.text))
                        showActionSheet = null
                    },
                )
                if (target.canEdit) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit_message)) },
                        onClick = {
                            onEditLastMessage()
                            showActionSheet = null
                        },
                    )
                }
                if (target.canRewind) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rewind)) },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = {
                            rewindTarget = target
                            showActionSheet = null
                        },
                    )
                }
            }
        }
    }

    selectedImage?.let { source ->
        ChatImageViewerDialog(
            source = source,
            onDismiss = { selectedImage = null },
            onDownload = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    coroutineScope.launch {
                        val saved = saveChatImageToPictures(context, it)
                        android.widget.Toast.makeText(
                            context,
                            if (saved) R.string.image_saved else R.string.image_save_failed,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    pendingSaveUrl = it.url
                    pendingSaveMime = it.mime
                    pendingSaveFilename = it.filename
                    imageSaveLauncher.launch(safeImageFilename(it))
                }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatHomeScreenEmptyPreview() {
    AndCodeTheme {
        ChatHomeScreen(
            state = ChatUiState(backendName = "OpenCode · 1.0.0", isConnected = true),
            providers = emptyList(),
            agents = emptyList(),
            selectedProviderId = null,
            selectedModelId = null,
            selectedAgentId = null,
            runtimeTargets = emptyList(),
            selectedRuntimeId = null,
            onSelectRuntime = {},
            onSelectModel = { _, _ -> },
            onSelectAgent = {},
            onSelectQuestionAnswer = { _, _, _ -> },
            onSubmitQuestion = {},
            onSendMessage = {},
            onPermission = { _, _, _ -> },
            onAbort = {},
            onMic = {},
            onNewChat = {},
            onOpenLocalSetup = {},
            onOpenRemoteSetup = {},
            onOpenDrawer = {},
        )
    }
}

/**
 * A Codex model with the full effort range and its fast tier switched on, at phone width, to check
 * that the effort and Fast chips sit side by side and the row still fits.
 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ChatHomeScreenCodexFastPreview() {
    val efforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
    AndCodeTheme {
        ChatHomeScreen(
            state =
                ChatUiState(
                    backendName = "Codex",
                    isConnected = true,
                    selectedVariant = "ultra",
                    fastModeAvailable = true,
                    fastModeEnabled = true,
                    fastModeDescription = "1.5x speed, increased usage",
                ),
            providers =
                listOf(
                    OpenCodeProvider(
                        id = "openai",
                        name = "OpenAI (Codex)",
                        models =
                            mapOf(
                                "gpt-6-astra" to
                                    OpenCodeModel(
                                        id = "gpt-6-astra",
                                        providerId = "openai",
                                        name = "GPT-6-Astra",
                                        variants = efforts.associateWith { JsonPrimitive(it) },
                                    ),
                            ),
                    ),
                ),
            agents = emptyList(),
            selectedProviderId = "openai",
            selectedModelId = "gpt-6-astra",
            selectedAgentId = null,
            runtimeTargets = emptyList(),
            selectedRuntimeId = null,
            onSelectRuntime = {},
            onSelectModel = { _, _ -> },
            onSelectAgent = {},
            onSelectQuestionAnswer = { _, _, _ -> },
            onSubmitQuestion = {},
            onSendMessage = {},
            onPermission = { _, _, _ -> },
            onAbort = {},
            onMic = {},
            onNewChat = {},
            onOpenLocalSetup = {},
            onOpenRemoteSetup = {},
            onOpenDrawer = {},
        )
    }
}
