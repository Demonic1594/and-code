package com.yugahashimoto.andcode.feature.chat

import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.ConnectionQualityMonitor
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.RunActivity
import com.yugahashimoto.andcode.core.api.sessionIdOrNull
import com.yugahashimoto.andcode.core.diagnostics.RunSignals
import com.yugahashimoto.andcode.core.diagnostics.StallEvidence
import com.yugahashimoto.andcode.core.diagnostics.StallReason
import com.yugahashimoto.andcode.core.diagnostics.diagnoseStall
import com.yugahashimoto.andcode.core.diagnostics.inspectRun
import com.yugahashimoto.andcode.core.diagnostics.provesRunProgress
import com.yugahashimoto.andcode.core.pricing.PriceCatalog
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.data.repository.PullRequestStatusRepository
import com.yugahashimoto.andcode.data.settings.Draft
import com.yugahashimoto.andcode.data.settings.DraftStore
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.local.VideoAttachmentHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal const val RESPONSE_POLL_INTERVAL_MS = 3000L

/**
 * Post-send poll cadence while the runtime's event stream is healthy and reports idle on its own
 * ([RuntimeCapabilities.reliableIdleEvents]). The poll is then only a heartbeat against a lost
 * `session.idle`, not the turn's completion path, so a slow read beats re-fetching the whole
 * transcript — base64 attachments included — every 3 seconds of a stream that already carries
 * every update. Any stream failure ([streamError]) or a runtime without the capability drops back
 * to [RESPONSE_POLL_INTERVAL_MS] immediately, including mid-turn.
 */
internal const val STREAM_HEALTHY_POLL_INTERVAL_MS = 30_000L

/**
 * How long after a settle a second idle for the same session is treated as the HTTP runtime's
 * duplicated run-end emission (status:idle + deprecated idle, one burst) rather than a new run
 * ending. Far below anything a real dispatched turn can complete in; the poll loop and stall
 * watchdog cover the pathological case. Read from the injectable [ChatViewModel] clock so tests
 * on virtual time see a genuinely later idle as later.
 */
private const val DUPLICATE_IDLE_WINDOW_MILLIS = 750L

private const val RESPONSE_POLL_TIMEOUT_MS = 120_000L
internal const val TRANSIENT_RECOVERY_DELAY_MS = 5000L
internal const val TRANSIENT_RECOVERY_RETRY_DELAY_MS = 3000L
private const val TRANSIENT_RECOVERY_MAX_BACKOFF_MS = 30_000L
private const val TRANSIENT_RECOVERY_MAX_ATTEMPTS = 20
private const val HEALTH_CHECK_ATTEMPTS = 15
private const val HEALTH_CHECK_DELAY_MS = 2000L

/**
 * How long a run may produce nothing before the chat stops taking it on faith and asks the runtime
 * what is going on. Long enough that a single slow tool call (a build, a test run) is not called a
 * stall on its own, and past the bounded post-send poll, which gives up at
 * [RESPONSE_POLL_TIMEOUT_MS] and leaves nothing else watching the turn.
 */
internal const val STALL_THRESHOLD_MS = 150_000L

/** How often a run in flight is measured against [STALL_THRESHOLD_MS]. */
internal const val STALL_CHECK_INTERVAL_MS = 30_000L

/** How long a running chat may hear nothing live before its transcript is re-read. */
internal const val CATCH_UP_QUIET_MS = 12_000L

/** The least time between two such re-reads. */
internal const val CATCH_UP_INTERVAL_MS = 10_000L

/** How often a running chat checks whether it needs to catch up. */
internal const val CATCH_UP_CHECK_MS = 4_000L

/** The most time between two catch-ups while the stream stays silent. */
internal const val CATCH_UP_MAX_INTERVAL_MS = 60_000L

/** How long the app must have been away before coming back re-reads the open chat. */
internal const val RESYNC_AFTER_AWAY_MS = 10_000L

/**
 * How often a run already reported as stalled is probed again. Once the chat has said its piece
 * there is nothing left to be timely about, and a wedged run left open for an hour should not cost
 * an hour of health checks and transcript reads on a phone battery. Any progress at all takes the
 * warning down and returns to [STALL_CHECK_INTERVAL_MS], and the card's own button probes now.
 */
internal const val STALL_RECHECK_INTERVAL_MS = 300_000L

private data class QueuedPrompt(
    val text: String,
    val attachments: List<PromptAttachment>,
    val imagePreviews: List<Bitmap>,
    /**
     * The chat it was written in; null while that chat is new and has no session yet. Only the open
     * chat's prompts are sent, so one queued in another chat never lands here.
     */
    val sessionId: String?,
    /** The chat generation it was queued in, so a new chat's prompts follow only that chat's session. */
    val chatGeneration: Long,
    /** False for a review from the diff screen, which must not touch the composer when it goes out. */
    val fromComposer: Boolean = true,
    /** Names it for the actions on the queue above the composer. */
    val id: String = UUID.randomUUID().toString(),
)

class ChatViewModel(
    private val backend: OpenCodeBackend? = null,
    private val eventFlow: Flow<OpenCodeEvent>? = null,
    private val onPermissionResolved: (String) -> Unit = {},
    /** Reports a question that was answered or declined, so its notification can be cancelled. */
    private val onQuestionResolved: (String) -> Unit = {},
    private val onSessionCreated: () -> Unit = {},
    /**
     * Records [ChatUiState.draftSystemPrompt] against the session the first message just created.
     *
     * Injected rather than reached for directly so this stays backend-agnostic: only Claude Code
     * carries a system prompt, and only the app knows how to route it there.
     */
    private val onApplySystemPrompt: (sessionId: String, presetId: String?) -> Unit = { _, _ -> },
    /**
     * Reports whether this chat is working, so the drawer shows real state even when no stream
     * events arrive. Deriving it from events alone left every chat on the idle marker.
     */
    private val onRunStateChanged: (String, Boolean) -> Unit = { _, _ -> },
    private val onSessionAborted: (String) -> Unit = {},
    private val draftRepo: DraftStore? = null,
    /**
     * Starts the periodic connection probe. It runs an unbounded polling loop, which a virtual
     * test clock advances through forever, so it stays off unless the real app asks for it.
     */
    private val monitorConnectionQuality: Boolean = false,
    private val resolvedPermissionFlow: Flow<String>? = null,
    /** Absent in tests and previews, where the pull request badges stay unresolved. */
    private val pullRequestStatuses: PullRequestStatusRepository? = null,
    /**
     * Starts the stall watchdog. Like [monitorConnectionQuality] it polls for as long as a turn
     * runs, so it stays off unless the real app asks for it; tests drive [checkForStall] directly.
     */
    private val monitorStalls: Boolean = false,
    /** The event stream's last failure, so a silent run can be blamed on a dead stream. */
    private val streamErrorFlow: Flow<String?>? = null,
    /** Wall clock, replaced in tests by the virtual one the watchdog is advanced against. */
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Suspends until the app is back in the foreground. Gates the connection probe and the stall
     * watchdog, both of which poll on a fixed interval for as long as a chat is open; without this
     * they keep doing so while the app is backgrounded, for a result the user cannot see. The
     * no-op default keeps every existing test - which drives these loops with a virtual clock that
     * would otherwise never return from a real wait - running unattended.
     */
    private val awaitForeground: suspend () -> Unit = {},
    /**
     * Whether the app is on screen. Coming back re-reads the open chat: whatever the runtime did
     * while the app was away - and a local agent's stream never reconnects to say so - is shown
     * without the user having to open the chat again from the drawer.
     */
    private val foreground: Flow<Boolean>? = null,
    /**
     * Shown when a video attachment reaches the send path. Videos are converted to image
     * frames at attach time, so this only fires for stale or queued attachments that slipped
     * through. Injected the same way as WorkspaceViewModel.incompleteConnectionMessage so
     * the real app shows a localized string while tests keep the English default.
     */
    private val videoNotSupportedMessage: String =
        "Video files cannot be sent directly. Remove the video and attach images instead.",
    /**
     * Template (one `%s`) telling the agent where a non-sendable attachment was copied inside the
     * workspace. Injected the same way as [videoNotSupportedMessage]; the app passes the string
     * resource (kept English-only, since it is a prompt fragment for the model) while tests keep
     * the same default.
     */
    private val workspaceAttachmentNoteTemplate: String = "[Attached file available in the workspace: %s]",
    /** Shown when the runtime took a rewind request but rewound nothing; localized by the app. */
    private val rewindNothingMessage: String = "There was nothing to rewind to at that message.",
    /**
     * Workspace snapshots before each user message, for runtimes without checkpoints of their own:
     * files can be put back to a message, and a turn's changes diffed outside a git repository.
     */
    private val turnSnapshots: TurnSnapshots? = null,
    /** Shown when putting the files back failed; localized by the app. */
    private val restoreFailedMessage: String = "The files could not be put back.",
    /** Shown when a restore was refused because an ignored file is in the way, named when known; localized by the app. */
    private val restoreRefusedMessage: (String?) -> String = { path -> "Nothing was changed: ${path ?: "an ignored file"} is in the way." },
    /** Shown when compacting failed and the runtime gave no reason; localized by the app. */
    private val compactFailedMessage: String = "Could not compact the conversation",
    /** Shown when the runtime refused an answer to a permission card; localized by the app. */
    private val permissionNotAppliedMessage: String = "OpenCode could not apply that permission response",
    /** Shown on a question card submitted without an answer to every question; localized by the app. */
    private val questionAnswerRequiredMessage: String = "Answer required",
    /** Shown on a question card whose answer or decline the runtime refused; localized by the app. */
    private val questionFailedMessage: String = "OpenCode question failed",
    /** Persists the Codex fast-mode choice (null = follow the model default). */
    private val onCodexFastModeChanged: (Boolean?) -> Unit = {},
    /** The user's saved Codex fast-mode choice, read when the ViewModel starts. */
    private val initialCodexFastMode: Boolean? = null,
    /** Persists the Claude Code ultracode choice. */
    private val onClaudeUltracodeChanged: (Boolean) -> Unit = {},
    /** The user's saved Claude Code ultracode choice, read when the ViewModel starts. */
    private val initialClaudeUltracode: Boolean = false,
    /**
     * The current price catalog, for the ⋮ usage row's "≈ at list prices" line on turns the
     * runtime priced at nothing. Absent in tests; the app passes the repository's live value.
     */
    private val priceCatalog: () -> PriceCatalog? = { null },
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            ChatUiState(
                backendName = backend?.displayName.orEmpty(),
                contextBreakdownAvailable = (backend as? RuntimeTarget)?.capabilities?.contextBreakdown == true,
                compactAvailable = (backend as? RuntimeTarget)?.capabilities?.compact == true,
            ),
        )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _sendBehavior = MutableStateFlow("interrupt")
    val sendBehavior: StateFlow<String> = _sendBehavior.asStateFlow()

    private val _workspaceTitleSource = MutableStateFlow("title")
    val workspaceTitleSource: StateFlow<String> = _workspaceTitleSource.asStateFlow()

    private val pullRequestBadges = PullRequestBadges(viewModelScope, _uiState, pullRequestStatuses)

    private val voice = ChatVoice(_uiState)

    private val systemPrompt = SystemPromptDraft(_uiState, onApplySystemPrompt)

    private val contextUsage = ContextUsage(backend, viewModelScope, _uiState, priceCatalog, compactFailedMessage)

    private val restorePoints =
        RestorePoints(viewModelScope, _uiState, turnSnapshots, restoreFailedMessage, restoreRefusedMessage) { drainHeldPrompts() }

    private val rewind =
        ChatRewind(
            backend = backend,
            scope = viewModelScope,
            uiState = _uiState,
            rewindNothingMessage = rewindNothingMessage,
            reportError = { reportError(it) },
            onSettled = { drainHeldPrompts() },
        )

    private val messageEdit =
        ChatMessageEdit(backend, viewModelScope, _uiState, toUiMessage = { toUiMessage(it) }, reportError = { reportError(it) })

    private val previews = ChatPreviews(backend, viewModelScope, _uiState, turnSnapshots, restorePoints)

    private val permissions = ChatPermissions(backend, viewModelScope, _uiState, onPermissionResolved, permissionNotAppliedMessage)

    private val questions =
        ChatQuestions(backend, viewModelScope, _uiState, onQuestionResolved, questionAnswerRequiredMessage, questionFailedMessage)

    private val modelSelection =
        ModelSelection(_uiState, onCodexFastModeChanged, initialCodexFastMode, onClaudeUltracodeChanged, initialClaudeUltracode)

    /**
     * Sessions whose idles currently belong to a run this chat interrupted rather than to the prompt
     * sent in its place.
     *
     * OpenCode reports one cancelled run as both `session.status: idle` and the deprecated
     * `session.idle`, so counting idles would not do; the window instead stays open until the
     * replacement run reports itself busy, and is closed by [closeInterruptWindow] if that turn ends
     * without ever getting there.
     */
    private val pendingInterrupts = mutableSetOf<String>()

    private val messageQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    private val offlineMessageQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())

    private var eventJob: Job? = null
    private var recoveringTransiently = false
    private val streamedParts = mutableMapOf<String, LinkedHashMap<String, ChatPart>>()

    /** Message id to role, learned from `message.updated`, so user echoes can be skipped. */
    private val messageRoles = mutableMapOf<String, String>()

    private val connectionMonitor = ConnectionQualityMonitor(viewModelScope, awaitForeground = awaitForeground)

    // The three fields below are read and written only from the main thread: every writer is either
    // a viewModelScope coroutine (main-dispatched) or a UI callback, and [checkForStall] is called
    // from the watchdog and from the card's button, both of which are on it too.

    /** When the running turn last produced something the chat could see. */
    private var lastProgressAt: Long = now()

    /** Guards against a slow probe being started again by the next watchdog tick. */
    private var stallCheckRunning = false

    /** When the runtime was last asked about a quiet run, so the watchdog can space its probes. */
    private var lastStallProbeAt: Long = 0L

    /** Last failure reported by the event stream, or null while it is healthy. */
    private var streamError: String? = null

    /** When the running chat's transcript was last re-read because nothing had arrived live. */
    private var lastCatchUpAt: Long = 0L

    /** When anything last came down the event stream, for any chat: proof the stream is alive. */
    private var lastStreamEventAt: Long = now()

    /** Grows while catch-ups find the stream still silent, so a long quiet tool run is not re-read every few seconds. */
    private var catchUpIntervalMs: Long = CATCH_UP_INTERVAL_MS

    /**
     * Bumped by every read that replaces the transcript, so a re-read that comes back after a
     * newer one (or after the turn's own end-of-run reload) is dropped instead of rolling the chat
     * back to an older snapshot.
     */
    private var transcriptGeneration: Long = 0L
    private var resyncJob: Job? = null
    private var leftScreenAt: Long? = null

    /**
     * Bumped every time this chat dispatches a turn (send, slash command, drained queue entry).
     * A poll loop from an earlier turn compares its captured value to decide whether it still
     * owns the composer: without it, turn 1's poller could wake after a queued prompt replaced
     * the run and settle the NEW turn mid-flight (its `turnFinished` check passes against turn
     * 1's own reply), clearing the stream cache and flipping isRunning off while tokens are
     * still arriving.
     */
    private var runGeneration: Long = 0L

    /**
     * The HTTP runtime ends every run with BOTH `session.status: idle` and the deprecated
     * `session.idle`, back to back in one event burst; the CLI bridges emit only the latter.
     * Two full settles for one run end double-drained the queue (firing two prompts per turn
     * end and killing the first), so an idle for the same session inside this window of the
     * last settle is treated as the echo it is. The window is far below anything a genuinely
     * distinct second run can achieve - dispatch plus model round-trip - and the poll loop
     * recovers the pathological case anyway.
     */
    private var lastIdleSettledSessionId: String? = null
    private var lastIdleSettledAtMillis = 0L

    init {
        pullRequestStatuses?.let(pullRequestBadges::track)
        streamErrorFlow?.let { flow ->
            viewModelScope.launch { flow.collect { streamError = it } }
        }
        // A permission answered from the notification (or the activity screen) never comes back
        // as an event, so without this the chat keeps showing a card for a settled request.
        resolvedPermissionFlow?.let(permissions::followResolvedElsewhere)
        if (backend != null) {
            // The open chat's held prompts, for the queue above the composer: the queue itself is shared by
            // every chat this view model has shown.
            viewModelScope.launch {
                combine(messageQueue, _uiState.map { it.sessionId }.distinctUntilChanged()) { queue, sessionId ->
                    // An image is in both lists (its preview pairs with it), so only attachments count.
                    queue.filter { it.sessionId == sessionId }.map { QueuedPromptView(it.id, it.text, it.attachments.size) }
                }.distinctUntilChanged().collect { held ->
                    val canInterrupt = (backend as? RuntimeTarget)?.capabilities?.forcesQueue != true
                    _uiState.update { it.copy(queuedPrompts = held, canSendQueuedNow = canInterrupt) }
                }
            }
            viewModelScope.launch {
                // Only real transitions are reported, so merely opening an idle chat is not
                // mistaken for a run that just ended.
                var runningSession: String? = null
                uiState
                    .map { it.sessionId to it.isRunning }
                    .distinctUntilChanged()
                    .collect { (sessionId, running) ->
                        if (sessionId == null) return@collect
                        // The run we last reported as active is over the moment the open chat
                        // stops running OR the client moves to another chat (it can no longer
                        // track the old one's isRunning). The previous logic keyed on the
                        // (sessionId, isRunning) pair, so a mid-turn navigation produced the
                        // transition (oldSession, true) -> (newSession, false) and matched neither
                        // branch: the old session was never reported finished and its drawer dot
                        // stayed on the spinner forever. Clear the stale run explicitly here so the
                        // drawer only reflects chats the runtime itself still reports as active.
                        runningSession?.let { previous ->
                            if (previous != sessionId || !running) {
                                onRunStateChanged(previous, false)
                                runningSession = null
                            }
                        }
                        if (running && runningSession != sessionId) {
                            runningSession = sessionId
                            onRunStateChanged(sessionId, true)
                        }
                    }
            }
            eventJob =
                viewModelScope.launch {
                    (eventFlow ?: backend.events())
                        .catch { error -> reportError(error) }
                        .collect(::handleEvent)
                }
            viewModelScope.launch {
                var lastError: String? = null
                repeat(HEALTH_CHECK_ATTEMPTS) {
                    runCatching { backend.health() }
                        .onSuccess { health ->
                            _uiState.update {
                                it.copy(
                                    isConnected = health.healthy,
                                    backendName = "${backend.displayName} · ${health.version}",
                                    error = null,
                                )
                            }
                            refreshSlashCatalog()
                            return@launch
                        }
                        .onFailure { error -> lastError = error.safeMessage() }
                    delay(HEALTH_CHECK_DELAY_MS)
                }
                reportError(lastError)
            }
            foreground?.let { visible ->
                viewModelScope.launch {
                    visible.distinctUntilChanged().drop(1).collect { onScreen ->
                        if (!onScreen) {
                            leftScreenAt = now()
                            return@collect
                        }
                        // A short glance away misses nothing a healthy stream would not deliver.
                        val awayFor = leftScreenAt?.let { now() - it } ?: 0L
                        leftScreenAt = null
                        if (awayFor >= RESYNC_AFTER_AWAY_MS) resyncOpenChat()
                    }
                }
            }
            // Wired up only where the app's visibility is known: the loop waits on it, and screens or
            // tests that do not pass it keep the transcript reads they already expect.
            if (foreground != null) {
                viewModelScope.launch {
                    uiState
                        .map { it.isRunning }
                        .distinctUntilChanged()
                        .collectLatest { running ->
                            if (!running) return@collectLatest
                            while (true) {
                                awaitForeground()
                                delay(CATCH_UP_CHECK_MS)
                                maybeCatchUp()
                            }
                        }
                }
            }
            if (monitorConnectionQuality) {
                connectionMonitor.startMonitoring { backend.health() }
                viewModelScope.launch {
                    connectionMonitor.quality.collect { quality ->
                        _uiState.update { it.copy(connectionQuality = quality) }
                    }
                }
            }
            viewModelScope.launch {
                uiState
                    .map { it.isRunning }
                    .distinctUntilChanged()
                    .collectLatest { running ->
                        if (!running) {
                            // Whatever the run was doing, it is not doing it any more.
                            if (_uiState.value.stall != null || _uiState.value.retry != null || _uiState.value.runActivity != null) {
                                _uiState.update { it.copy(stall = null, retry = null, runActivity = null) }
                            }
                            // Most replies stream for under the rate window; without this flush
                            // their chunks sat unpublished until some LATER turn's first delta.
                            connectionMonitor.flushStreamRate()
                            return@collectLatest
                        }
                        recordProgress()
                        if (!monitorStalls) return@collectLatest
                        while (true) {
                            awaitForeground()
                            delay(STALL_CHECK_INTERVAL_MS)
                            // The wait between probes is applied here rather than to the delay
                            // itself, so a run that recovers and goes quiet again is measured on
                            // the short interval immediately instead of finishing out a long sleep
                            // that started while the warning was still up.
                            val waited = now() - lastStallProbeAt
                            if (_uiState.value.stall != null && waited < STALL_RECHECK_INTERVAL_MS) continue
                            checkForStall()
                        }
                    }
            }
        }
    }

    /**
     * Refetches any pull request state that has gone stale. The chat screen calls this on a timer
     * while it is on screen, so a badge follows a pull request that is merged or closed elsewhere.
     */
    fun refreshPullRequests() = pullRequestBadges.refresh()

    /**
     * Hides one pull request's badges above the composer. Nothing server-side tracks this — the
     * badge is a view over the transcript, and the transcript keeps its link — so this is purely a
     * local "stop showing me this": the ref is dropped here, which also stops the periodic refresh
     * from fetching its state, and stays dismissed for as long as this chat is open.
     */
    fun dismissPullRequest(key: String) = pullRequestBadges.dismiss(key)

    /**
     * Loads the backend's slash commands and skills so the composer's `/` popup can offer them.
     */
    fun refreshSlashCatalog() {
        val currentBackend = backend ?: return
        viewModelScope.launch {
            runCatching { currentBackend.commands() }
                .onSuccess { commands ->
                    if (backend == currentBackend) {
                        _uiState.update { it.copy(slashCommands = commands) }
                    }
                }
            runCatching { currentBackend.skills() }
                .onSuccess { skills ->
                    if (backend == currentBackend) {
                        _uiState.update { it.copy(slashSkills = skills) }
                    }
                }
        }
    }

    fun selectWorkspace(path: String?) {
        if (_uiState.value.sessionId != null) return
        _uiState.update { it.copy(selectedWorkspacePath = path) }
    }

    fun selectConfiguration(
        providerId: String?,
        modelId: String?,
        agentId: String?,
        contextLimit: Long = 0L,
        fastServiceTier: String? = null,
        fastByDefault: Boolean = false,
        fastDescription: String? = null,
        ultracodeAvailable: Boolean = false,
    ) = modelSelection.selectConfiguration(
        providerId,
        modelId,
        agentId,
        contextLimit,
        fastServiceTier,
        fastByDefault,
        fastDescription,
        ultracodeAvailable,
    )

    /** Sets the Claude Code ultracode choice and persists it. It takes effect from the next message. */
    fun setUltracode(enabled: Boolean) = modelSelection.setUltracode(enabled)

    /**
     * Sets the Codex fast-mode choice and persists it. Passing null returns to the model's default.
     */
    fun setFastMode(enabled: Boolean?) = modelSelection.setFastMode(enabled)

    fun setAutoAcceptPermissions(enabled: Boolean) {
        _uiState.update { it.copy(autoAcceptPermissions = enabled) }
    }

    fun setSendBehavior(behavior: String) {
        _sendBehavior.value = behavior
    }

    fun setWorkspaceTitleSource(source: String) {
        _workspaceTitleSource.value = source
    }

    /**
     * Keeps unsent composer text for [sessionId]. Blank text clears the entry instead, so emptying
     * the field never leaves an empty draft behind to be "restored" later.
     */
    fun saveDraft(
        sessionId: String,
        text: String,
        model: String? = null,
        agent: String? = null,
    ) {
        if (text.isBlank()) {
            draftRepo?.clear(sessionId)
        } else {
            draftRepo?.save(sessionId, Draft(text, emptyList(), model, agent))
        }
    }

    fun loadDraft(sessionId: String): Draft? = draftRepo?.load(sessionId)

    fun clearDraft(sessionId: String) {
        draftRepo?.clear(sessionId)
    }

    fun selectVariant(variant: String?) {
        _uiState.update { it.copy(selectedVariant = variant) }
    }

    fun addAttachment(attachment: PromptAttachment) {
        _uiState.update { it.copy(attachments = it.attachments + attachment) }
    }

    fun addImageAttachment(
        attachment: PromptAttachment,
        preview: Bitmap,
    ) {
        _uiState.update {
            it.copy(
                attachments = it.attachments + attachment,
                imagePreviews = it.imagePreviews + preview,
            )
        }
    }

    /**
     * Drops the attachment at [index], along with its preview thumbnail when it has one.
     *
     * The preview bitmap is dropped, never recycled: the same instance is still referenced by the
     * composer row Compose is drawing this frame (and, once sent, by the optimistic message in the
     * transcript and by any queued prompt), so recycling it here crashed the next draw pass with
     * "Canvas: trying to use a recycled bitmap". Since API 26 the pixels live on the native heap
     * and are freed with the bitmap by the GC, so letting the last reference go is enough.
     */
    fun removeAttachment(index: Int) {
        _uiState.update { state ->
            val previewIndex = previewIndexForAttachment(state.attachments, index)
            state.copy(
                attachments = state.attachments.filterIndexed { i, _ -> i != index },
                imagePreviews =
                    previewIndex?.let { removed -> state.imagePreviews.filterIndexed { i, _ -> i != removed } }
                        ?: state.imagePreviews,
            )
        }
    }

    /**
     * Shows a chat's new name in the header when that chat is the one open. Renames come from outside
     * (the drawer or the ⋮ menu, through the chat lifecycle), and an on-device runtime sends no event
     * for them, so the header would otherwise keep the old name until the chat is reopened.
     */
    fun showRenamedTitle(
        sessionId: String,
        title: String,
    ) {
        _uiState.update { state -> if (state.sessionId == sessionId) state.copy(sessionTitle = title) else state }
    }

    /**
     * @param parent the session this one was spawned from when the caller already knows it
     * (subagent drill-in). When null the parent is resolved from the backend so a subagent session
     * opened from anywhere else still offers a way back to the main agent.
     */
    fun openSession(
        sessionId: String,
        title: String = "",
        parent: ParentSessionRef? = null,
    ) {
        val currentBackend = backend ?: return
        // A blank id is not a session: loading it would ask the runtime for `session//message` and
        // render its parameter error in place of the transcript. Open an empty chat instead.
        if (sessionId.isBlank()) {
            newSession()
            return
        }
        // Switching away from whatever chat was previously loaded here must drop its running
        // state too — otherwise the composer opens the new chat already stuck on the stop button
        // because the old chat happened to be mid-turn when the user navigated away.
        val switchingSession = _uiState.value.sessionId != sessionId
        if (switchingSession) pendingInterrupts.clear()
        dropNewChatPrompts()
        // The composer is moving to another chat, so a preset chosen for the one it is leaving -
        // which never got a session to record it on - stops applying here.
        _uiState.update { it.copy(chatGeneration = it.chatGeneration + 1, draftSystemPrompt = null) }
        streamedParts.clear()
        transcriptGeneration++
        messageRoles.clear()
        lastIdleSettledSessionId = null
        // Opening the chat is an explicit act of attention, so questions the user hid earlier are
        // offered again rather than staying suppressed by a stale dismissal.
        questions.forgetDismissals()
        pullRequestBadges.forgetDismissals()
        _uiState.update {
            it.copy(
                sessionId = sessionId,
                sessionTitle = title,
                sessionUsage = if (it.sessionId == sessionId) it.sessionUsage else null,
                parentSession = parent,
                isLoadingHistory = true,
                messages = emptyList(),
                permissions = emptyList(),
                pendingQuestions = emptyList(),
                sessionDirectory = null,
                rewoundFrom = if (switchingSession) null else it.rewoundFrom,
                isRewinding = if (switchingSession) false else it.isRewinding,
                // A restore's Undo belongs to the chat it was made in; offered in another, it would
                // throw away whatever was done there since.
                fileRestore = if (switchingSession) null else it.fileRestore,
                isRestoringFiles = if (switchingSession) false else it.isRestoringFiles,
                isRunning = if (switchingSession) false else it.isRunning,
                isThinking = if (switchingSession) false else it.isThinking,
                // The opened chat's usage is unknown until its first refresh lands; keeping the
                // previous chat's number here let a >=90% reading fire summarizeSession on a
                // chat that never earned it.
                contextTokensUsed = 0L,
                contextWindow = 0L,
                // The window sheet describes the chat it was opened on.
                contextBreakdown = if (switchingSession) ContextBreakdownState.Hidden else it.contextBreakdown,
                dismissedTodoBarId = null,
                error = null,
            )
        }
        if (parent == null) resolveParentSession(sessionId)
        rewind.refreshRewind(sessionId)
        questions.refreshPendingQuestions(sessionId)
        // Captured after the generation bump above: the history fetch below is async, and a
        // quick switch to another chat must not land THIS chat's transcript under THAT one's
        // title (the merge drops the other chat's unmatched bubbles).
        val generation = _uiState.value.chatGeneration
        viewModelScope.launch {
            runCatching { currentBackend.listMessages(sessionId) }
                .onSuccess { messages ->
                    val selectedModel =
                        messages.asReversed()
                            .firstNotNullOfOrNull { it.info.effectiveModel }
                    _uiState.update { state ->
                        if (state.chatGeneration != generation) {
                            state
                        } else {
                            state.copy(
                                isLoadingHistory = false,
                                messages = mergeReloadedMessages(messages.mapNotNull(::toUiMessage), state.messages),
                                selectedProviderId = selectedModel?.providerId ?: state.selectedProviderId,
                                selectedModelId = selectedModel?.modelId ?: state.selectedModelId,
                            )
                        }
                    }
                    if (_uiState.value.chatGeneration == generation) contextUsage.refresh(sessionId)
                }
                .onFailure { error ->
                    if (_uiState.value.chatGeneration != generation) return@onFailure
                    _uiState.update {
                        it.copy(isLoadingHistory = false, error = error.safeMessage("OpenCode operation failed"))
                    }
                }
        }
    }

    /** Opens a subagent session started by the open chat, remembering the session to return to. */
    fun openSubagentSession(
        sessionId: String,
        title: String = "",
    ) {
        val current = _uiState.value
        val parent = current.sessionId?.let { ParentSessionRef(it, current.sessionTitle) }
        openSession(sessionId, title, parent)
    }

    /** Returns from a subagent session to the session that spawned it. */
    fun openParentSession() {
        val parent = _uiState.value.parentSession ?: return
        openSession(parent.id, parent.title)
    }

    /**
     * Asks the backend whether the open session is a subagent session. Failures leave the return
     * affordance hidden rather than surfacing an error the user cannot act on.
     */
    private fun resolveParentSession(sessionId: String) {
        val currentBackend = backend ?: return
        viewModelScope.launch {
            val parentId =
                runCatching { currentBackend.session(sessionId) }.getOrNull()?.parentId
                    ?: return@launch
            val parentTitle =
                runCatching { currentBackend.session(parentId) }.getOrNull()?.title.orEmpty()
            _uiState.update { state ->
                if (state.sessionId != sessionId) {
                    state
                } else {
                    state.copy(parentSession = ParentSessionRef(parentId, parentTitle))
                }
            }
        }
    }

    /**
     * Picks a system-prompt preset for this chat.
     *
     * Recorded on the session when there is one, and held as [ChatUiState.draftSystemPrompt] until
     * the first message creates one when there is not.
     */
    fun selectSystemPrompt(presetId: String?) = systemPrompt.selectSystemPrompt(presetId)

    fun newSession() {
        dropNewChatPrompts()
        streamedParts.clear()
        transcriptGeneration++
        messageRoles.clear()
        questions.forgetDismissals()
        pullRequestBadges.forgetDismissals()
        pendingInterrupts.clear()
        lastIdleSettledSessionId = null
        _uiState.update {
            it.copy(
                chatGeneration = it.chatGeneration + 1,
                draftSystemPrompt = null,
                sessionId = null,
                sessionTitle = "",
                sessionUsage = null,
                parentSession = null,
                // A history load still in flight for the chat being left must not park the new
                // chat on its spinner: the stale guard drops that load's apply entirely.
                isLoadingHistory = false,
                messages = emptyList(),
                permissions = emptyList(),
                pendingQuestions = emptyList(),
                sessionDirectory = null,
                isRunning = false,
                isThinking = false,
                isListening = false,
                isSpeechProcessing = false,
                partialText = "",
                imagePreviews = emptyList(),
                contextTokensUsed = 0L,
                contextWindow = 0L,
                contextBreakdown = ContextBreakdownState.Hidden,
                dismissedTodoBarId = null,
                rewoundFrom = null,
                isRewinding = false,
                fileRestore = null,
                isRestoringFiles = false,
                error = null,
            )
        }
    }

    /** How many of the user's messages went out before the one being sent: its turn number. */
    private fun sentTurns(): Int = restorePoints.sentTurns(rewind.keepRewind(_uiState.value))

    /** Project files for the `@` mention being typed in the composer. */
    val fileMentions =
        FileMentionSearch(viewModelScope) { query ->
            val directory = restorePoints.snapshotDirectory()
            val currentBackend = backend
            if (directory == null || currentBackend == null) {
                emptyList()
            } else {
                currentBackend.findFiles(
                    directory = directory,
                    query = query,
                    includeDirectories = false,
                    type = "file",
                    limit = FileMentionSearch.LIMIT,
                )
            }
        }

    /** Whether the files can be put back to how they were before [messageId], a message of the user's. */
    fun canRestoreFiles(messageId: String): Boolean = restorePoints.canRestoreFiles(messageId)

    /**
     * Puts the workspace's files back to how they were before [messageId], from the snapshot taken
     * when it was sent. The messages stay: only files change. [undoFileRestore] takes it back.
     */
    fun restoreFilesTo(messageId: String) = restorePoints.restoreFilesTo(messageId)

    /** Takes the last [restoreFilesTo] back: the files return to how they were just before it. */
    fun undoFileRestore() = restorePoints.undoFileRestore()

    fun dismissFileRestore() = restorePoints.dismissFileRestore()

    /**
     * Rewinds the chat to just before [messageId], a message of the user's: the runtime restores
     * the files the chat changed from then on, and the message and every later one are hidden. Its
     * text goes back into the composer, ready to send again as it was or changed.
     *
     * Nothing is lost yet: [undoRewind] brings it all back until the next message is sent, which is
     * when the runtime drops the rewound messages for good. See
     * [com.yugahashimoto.andcode.runtime.RuntimeCapabilities.checkpoints] for which runtimes can.
     */
    fun rewindTo(messageId: String) = rewind.rewindTo(messageId)

    /** Takes a rewind back: the files return to how the chat left them, and so do the messages. */
    fun undoRewind() = rewind.undoRewind()

    /** Sends what queued up while a rewind or file restore was landing, now that things are settled. */
    private fun drainHeldPrompts() {
        val state = _uiState.value
        if (state.isConnected && offlineMessageQueue.value.any { it.sessionId == state.sessionId }) {
            drainOfflineQueue()
        } else if (!state.isRunning) {
            drainQueue()
        }
    }

    /**
     * Fetches and shows the diff for a tapped [ChatPart.Patch] card, or the "diff unavailable"
     * state straight away when the backend cannot produce one at all
     * ([com.yugahashimoto.andcode.runtime.RuntimeCapabilities.diffCapable]) — Antigravity has no
     * diff surface, so this skips the round trip entirely rather than calling a backend already
     * known to have nothing to return.
     *
     * A git-capable backend can still come back empty for a workspace that turns out not to be a
     * git repository (see [ClaudeCodeTarget.sessionDiff]'s own git fallback); that is treated the
     * same as [RuntimeCapabilities.diffCapable] being false, since [ChatPart.Patch.files] is only
     * ever non-empty when the event stream already reported real changes, so an empty diff response
     * in that situation is itself the "can't diff here" signal rather than "nothing changed".
     */
    fun openPatchDiff(patch: ChatPart.Patch) = previews.openPatchDiff(patch)

    /**
     * Opens [artifact] in the preview: reads the file - from the chat's workspace when it lies in
     * it, else from its own folder - and shows it as what it is: a page, a drawing, a document, a
     * picture. See [collectArtifacts] and [artifactReadPair].
     */
    fun openArtifact(artifact: Artifact.File) = previews.openArtifact(artifact)

    fun dismissArtifact() = previews.dismissArtifact()

    fun dismissPatchDiff() = previews.dismissPatchDiff()

    /** Asks the agent how this chat's context window is split, for the sheet the context meter opens. */
    fun showContextBreakdown() = contextUsage.showBreakdown()

    fun hideContextBreakdown() = contextUsage.hideBreakdown()

    /**
     * Has the agent compact the open chat now, from the context window sheet. Not while a turn runs:
     * the compaction is a turn of its own, which the agent would refuse to start beside another. The
     * sheet closes; the chat then shows the compaction running, and the meter refreshes when it ends.
     */
    fun compactContext() = contextUsage.compact()

    /**
     * Removes the last user message from the open session — and anything the runtime already
     * replied with since — so its text can be corrected and resent: the "edit & resend" affordance
     * other chat UIs offer for undoing what you just sent (see issue #269).
     *
     * Only available while the backend can actually delete a message server-side
     * ([com.yugahashimoto.andcode.runtime.RuntimeCapabilities.editMessages]) and the turn is idle —
     * OpenCode itself refuses to delete out of a busy session, and a chat mid-turn has nothing
     * settled yet to edit. The removed messages are dropped from [ChatUiState.messages]
     * optimistically so the edit feels instant; a delete call that fails is surfaced through
     * [ChatUiState.error], and the transcript is immediately reloaded from the backend so any
     * message it refused to delete reappears instead of staying gone on screen. If that reload
     * itself fails, the failure is also surfaced through [ChatUiState.error] rather than left
     * silent — there is nothing left to reconcile the optimistic deletion against.
     *
     * The original text comes back through [ChatUiState.editDraft] rather than a return value, so it
     * reaches the composer the same one-shot way [ChatUiState.partialText] carries dictation in —
     * see [consumeEditDraft].
     */
    fun editLastUserMessage() = messageEdit.editLastUserMessage()

    /** Clears [ChatUiState.editDraft] once the composer has copied it into its own input state. */
    fun consumeEditDraft() {
        _uiState.update { it.copy(editDraft = null, editDraftJoinsInput = false) }
    }

    /**
     * Appends a reference for every [PromptAttachment] that only exists as a workspace file, so the
     * agent knows to read it from disk. Returns [text] unchanged when there is nothing to reference.
     */
    private fun appendWorkspaceAttachmentRefs(
        text: String,
        attachments: List<PromptAttachment>,
    ): String {
        if (attachments.isEmpty()) return text
        val refs =
            attachments.joinToString("\n") { attachment ->
                workspaceAttachmentNoteTemplate.format(attachment.workspacePath.orEmpty())
            }
        return if (text.isBlank()) refs else "$text\n\n$refs"
    }

    fun sendMessage(text: String) = send(text, fromComposer = true)

    /**
     * Sends review comments from the diff review screen. Unlike [sendMessage] it leaves the
     * composer alone - an attachment or draft waiting there is not part of the review and must not
     * go out with it - and it queues behind a running turn rather than interrupting it: the
     * comments are about the turn's result, so cutting the turn short would review half of it.
     */
    fun sendReview(text: String) = send(text, fromComposer = false)

    private fun send(
        text: String,
        fromComposer: Boolean,
        /** Interrupts a running turn even where prompts otherwise queue; see [sendQueuedPromptNow]. */
        sendNow: Boolean = false,
        /**
         * A held prompt going out, with its own attachments. It never reads or clears the composer,
         * where the user may be writing the next message.
         */
        held: QueuedPrompt? = null,
    ) {
        // A rewind or its undo in flight decides which messages the next one follows; the screen
        // holds the composer until it lands.
        if (_uiState.value.isRewinding || restorePoints.inFlight) return
        val normalized = text.trim()
        // Whether this send takes, and afterwards clears, what is in the composer.
        val usesComposer = fromComposer && held == null
        val pendingAttachments = held?.attachments ?: if (usesComposer) _uiState.value.attachments else emptyList()
        val messageIdsBeforeSend = _uiState.value.messages.map { it.id }.toSet()
        val pendingPreviews = held?.imagePreviews ?: if (usesComposer) _uiState.value.imagePreviews else emptyList()
        // The model identity is part of the prompt, exactly like its text: reading it from live
        // state after the createSession/summarize suspensions below sent turn 1 with whatever
        // model the user switched to mid-dispatch. Text and attachments were already captured
        // this way; the selection now is too.
        val providerAtSend = _uiState.value.selectedProviderId
        val modelAtSend = _uiState.value.selectedModelId
        val agentAtSend = _uiState.value.selectedAgentId
        val variantAtSend = _uiState.value.selectedVariant
        val ultracodeAtSend = modelSelection.ultracodeForPrompt
        val pendingPreviewsByFilename =
            pendingAttachments
                .filter { it.mime.startsWith("image/") }
                .mapIndexedNotNull { index, attachment ->
                    pendingPreviews.getOrNull(index)?.let { attachment.filename to it }
                }.toMap()
        if (normalized.isEmpty() && pendingAttachments.isEmpty()) return
        // Video file parts are rejected by most providers
        // ("'file part media type video/mp4' functionality not supported"), failing the
        // whole turn. Videos are converted to image frames at attach time, so reaching
        // here means a stale/queued attachment slipped through: block the send with a
        // clear error instead of breaking the session.
        if (pendingAttachments.any { VideoAttachmentHelper.isVideoMime(it.mime) }) {
            _uiState.update { it.copy(error = videoNotSupportedMessage) }
            return
        }
        // Attachments whose MIME a provider would reject as a file part were copied into the
        // workspace at attach time. They stay visible as chips but must not reach the runtime: the
        // outgoing text points the agent at the on-disk copy instead.
        val (modelAttachments, workspaceAttachments) =
            pendingAttachments.partition { it.workspacePath.isNullOrBlank() }
        val outgoingText = appendWorkspaceAttachmentRefs(normalized, workspaceAttachments)
        val currentBackend = backend
        if (currentBackend == null) {
            _uiState.update { it.copy(error = "OpenCode connection is not configured") }
            return
        }

        // A known backend command or skill is executed through the runtime's own command handling
        // (OpenCode expands the template server-side) rather than sent as a plain prompt.
        val slashCommand = if (fromComposer) matchSlashCommand(normalized) else null
        if (slashCommand != null) {
            sendSlashCommand(slashCommand.first, slashCommand.second, sendNow, usesComposer)
            return
        }

        if (!_uiState.value.isConnected) {
            offlineMessageQueue.update {
                it +
                    QueuedPrompt(
                        outgoingText,
                        modelAttachments,
                        pendingPreviews,
                        _uiState.value.sessionId,
                        _uiState.value.chatGeneration,
                        fromComposer,
                    )
            }
            val userMessage =
                ChatMessage(
                    isUser = true,
                    parts = listOf(ChatPart.Text(id = UUID.randomUUID().toString(), text = outgoingText)),
                    attachments = pendingAttachments,
                    imagePreviews = pendingPreviews,
                )
            restorePoints.markOffline(userMessage.id)
            _uiState.update {
                it.copy(
                    messages = rewind.keepRewind(it) + userMessage,
                    rewoundFrom = null,
                    offlineQueue = it.offlineQueue + outgoingText,
                    isOfflineQueued = true,
                    attachments = if (usesComposer) emptyList() else it.attachments,
                    imagePreviews = if (usesComposer) emptyList() else it.imagePreviews,
                )
            }
            return
        }

        // Some runtimes (Antigravity) must always queue behind a running turn rather than interrupt
        // it - interrupting there kills the in-flight one-shot process instead of cancelling a
        // request, which surfaced as a crash. See RuntimeCapabilities.forcesQueue.
        val mustQueue = (currentBackend as? RuntimeTarget)?.capabilities?.forcesQueue == true
        if (holdsForRunningTurn(queues = mustQueue || !fromComposer, sendNow = sendNow)) {
            messageQueue.update {
                it +
                    QueuedPrompt(
                        outgoingText,
                        modelAttachments,
                        pendingPreviews,
                        _uiState.value.sessionId,
                        _uiState.value.chatGeneration,
                        fromComposer,
                    )
            }
            if (usesComposer) _uiState.update { it.copy(attachments = emptyList(), imagePreviews = emptyList()) }
            return
        }
        val interrupting = _uiState.value.isRunning
        // The position this message takes among the user's messages, which names its snapshot.
        val turnAtSend = sentTurns()

        val userMessage =
            ChatMessage(
                isUser = true,
                parts =
                    outgoingText.takeIf { it.isNotEmpty() }?.let {
                        listOf(ChatPart.Text(id = UUID.randomUUID().toString(), text = it))
                    }.orEmpty(),
                attachments = pendingAttachments,
                imagePreviews = pendingPreviews,
            )
        _uiState.update {
            it.copy(
                messages = rewind.keepRewind(it) + userMessage,
                rewoundFrom = null,
                // Undoing a restore after the agent has worked again would throw that work away.
                fileRestore = null,
                isRunning = true,
                isThinking = true,
                retry = null,
                runActivity = null,
                partialText = "",
                error = null,
            )
        }
        // A turn that replaces or queues behind a running one leaves isRunning true, so the stall
        // clock has to be restarted here rather than only on the idle-to-running transition.
        recordProgress()
        // This turn now owns the composer's poll; see [runGeneration].
        runGeneration += 1L
        val runGenerationAtSend = runGeneration

        // Which chat asked, captured here because the coroutine below does not begin until the
        // dispatcher runs it - long enough for another chat to have been started. See
        // SystemPromptDraft.applyDraftSystemPrompt.
        val draftGeneration = _uiState.value.chatGeneration
        viewModelScope.launch {
            // Captured once the target session is known so onFailure below can tell whether the
            // failure still concerns the chat currently on screen.
            var capturedSessionId: String? = null
            runCatching {
                val existingSessionId = _uiState.value.sessionId
                val session =
                    if (existingSessionId == null) {
                        currentBackend.createSession(
                            // Let OpenCode generate the concise chat summary from the first prompt.
                            title = null,
                            directory = _uiState.value.selectedWorkspacePath,
                        )
                    } else {
                        null
                    }
                val targetSessionId = existingSessionId ?: requireNotNull(session).id
                capturedSessionId = targetSessionId
                if (session != null) {
                    systemPrompt.applyDraftSystemPrompt(session.id, draftGeneration)
                    _uiState.update {
                        it.copy(
                            sessionId = session.id,
                            sessionTitle = session.title,
                            sessionDirectory = session.directory ?: it.sessionDirectory,
                        )
                    }
                    adoptHeldPrompts(session.id, draftGeneration)
                    onSessionCreated()
                }
                if (existingSessionId != null && modelSelection.shouldSummarizeBeforePrompt()) {
                    runCatching {
                        currentBackend.summarizeSession(
                            sessionId = targetSessionId,
                            providerId = requireNotNull(providerAtSend),
                            modelId = requireNotNull(modelAtSend),
                        )
                    }
                    contextUsage.refresh(targetSessionId)
                }
                if (interrupting) interruptRunningTurn(currentBackend, targetSessionId)
                restorePoints.recordTurnSnapshot(targetSessionId, turnAtSend, session?.directory)
                currentBackend.sendMessage(
                    targetSessionId,
                    PromptRequest(
                        text = outgoingText,
                        providerId = providerAtSend,
                        modelId = modelAtSend,
                        agent = agentAtSend,
                        variant = variantAtSend,
                        attachments = modelAttachments,
                        serviceTier = modelSelection.serviceTierForPrompt(),
                        ultracode = ultracodeAtSend,
                    ),
                )
                runCatching { currentBackend.session(targetSessionId).title }
                    .onSuccess { title ->
                        if (title.isNotBlank()) _uiState.update { it.copy(sessionTitle = title) }
                    }
                if (usesComposer) {
                    _uiState.update { it.copy(attachments = emptyList(), imagePreviews = emptyList()) }
                    clearDraft(targetSessionId)
                }
                var sessionCompleted = false

                // Every update below is guarded on the chat still being on screen: if the user
                // switches to another session, this poll must not keep overwriting its transcript
                // or clearing its running state with data that belongs to the old one.
                fun isStillActive() = _uiState.value.sessionId == targetSessionId

                // ...and on this turn still owning the composer: a queued prompt drained by the
                // idle handler starts the next turn on the same session synchronously, and
                // without the generation check this poller would wake into THAT turn, see turn
                // 1's completed reply pass `turnFinished`, and settle the new run mid-flight.
                fun stillOwnsRun() = runGeneration == runGenerationAtSend
                val trustsIdleEvents =
                    (currentBackend as? RuntimeTarget)?.capabilities?.reliableIdleEvents == true
                val pollFinished =
                    withTimeoutOrNull(RESPONSE_POLL_TIMEOUT_MS) {
                        while (isStillActive() && stillOwnsRun() && _uiState.value.isRunning) {
                            delay(
                                if (streamError == null && trustsIdleEvents) STREAM_HEALTHY_POLL_INTERVAL_MS else RESPONSE_POLL_INTERVAL_MS,
                            )
                            // The turn can end while this slept; the idle handler owns the
                            // transcript then. Another fetch here would merge against a stream
                            // cache it has already cleared and drop what only this client has.
                            if (!isStillActive() || !stillOwnsRun() || !_uiState.value.isRunning) return@withTimeoutOrNull
                            // Snapshotted before the fetch: the idle can clear the stream cache
                            // while it is in flight, and merging against an empty live view would
                            // drop what only this client has.
                            val retainIds = streamedParts.keys.toSet()
                            runCatching { currentBackend.listMessages(targetSessionId) }
                                .onSuccess { serverMessages ->
                                    if (!isStillActive()) return@onSuccess
                                    val uiMessages =
                                        mergeReloadedMessages(
                                            serverMessages.mapNotNull(::toUiMessage),
                                            _uiState.value.messages,
                                            pendingPreviewsByFilename,
                                            retainIds,
                                        )
                                    if (uiMessages.isNotEmpty() && uiMessages != _uiState.value.messages) {
                                        _uiState.update { it.copy(messages = uiMessages) }
                                    }
                                    // Completion is read off the transcript. A session object
                                    // carries no completion time — only assistant messages do — so
                                    // polling the session never ended the run and the composer sat
                                    // on the stop button until the 2 minute timeout expired.
                                    if (turnFinished(serverMessages, messageIdsBeforeSend)) {
                                        sessionCompleted = true
                                        _uiState.update { it.copy(isRunning = false, isThinking = false) }
                                    }
                                }
                        }
                    }
                closeInterruptWindow(targetSessionId)
                // Some runtimes deliver the final message but drop session.idle. Do not
                // leave the UI in the running state when the bounded fallback poll ends.
                if (isStillActive() && stillOwnsRun() && (sessionCompleted || pollFinished == null)) {
                    runCatching { currentBackend.listMessages(targetSessionId) }
                        .onSuccess { serverMessages ->
                            if (!isStillActive() || !stillOwnsRun()) return@onSuccess
                            // Settling requires a COMPLETED reply, not merely the presence of
                            // one: a still-streaming assistant message made `hasResponse`
                            // trivially true, so every turn longer than the poll timeout was
                            // force-settled mid-run - isRunning flipped off, the stream cache
                            // wiped, and the live bubble truncated to its next delta fragment.
                            val turnDone = turnFinished(serverMessages, messageIdsBeforeSend)
                            if (!sessionCompleted && !turnDone) return@onSuccess
                            // Captured before the stream cache is cleared: the final reload must
                            // keep what this client streamed that the transcript still does not
                            // carry, not drop it the way it otherwise would.
                            val retainedIds = streamedParts.keys.toSet()
                            streamedParts.clear()
                            transcriptGeneration++
                            // Re-attach the in-memory previews the same way the polling loop does:
                            // the final reload otherwise drops them, and a runtime whose attachment
                            // URLs are not inlineable data URLs would lose the just-sent image the
                            // moment the send completed.
                            val uiMessages =
                                mergeReloadedMessages(
                                    serverMessages.mapNotNull(::toUiMessage),
                                    _uiState.value.messages,
                                    pendingPreviewsByFilename,
                                    retainedIds,
                                )
                            _uiState.update {
                                it.copy(
                                    messages = uiMessages,
                                    isRunning = false,
                                    isThinking = false,
                                )
                            }
                            // Refresh after the final assistant message is persisted. The
                            // pre-send refresh only contains the previous turn's tokens.
                            contextUsage.refresh(targetSessionId)
                            // The idle handler is the usual drainer, but this settle bypassed
                            // it; without draining here, a queued prompt stranded until some
                            // LATER turn happened to end with an idle event.
                            drainQueue()
                        }
                }
            }.onFailure { error ->
                capturedSessionId?.let(::closeInterruptWindow)
                if (capturedSessionId == null || _uiState.value.sessionId == capturedSessionId) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            isThinking = false,
                        )
                    }
                }
                reportError(error)
            }
        }
    }

    /**
     * Sends a slash command or skill through the runtime's command handling.
     *
     * OpenCode expands the command's template server-side and runs it as a turn; Claude Code and
     * Antigravity receive `/name` as a prompt and interpret it themselves. The turn is completed and
     * polled exactly like [sendMessage], so the same completion signals drive the UI.
     */
    fun sendSlashCommand(
        command: String,
        arguments: String,
        sendNow: Boolean = false,
        /**
         * False for a held command going out once the turn it waited for ends. The composer may
         * hold the next message by then, and its stored draft is not this command's to clear.
         */
        usesComposer: Boolean = true,
    ) {
        if (_uiState.value.isRewinding || restorePoints.inFlight) return
        val currentBackend = backend
        if (currentBackend == null) {
            _uiState.update { it.copy(error = "OpenCode connection is not configured") }
            return
        }
        val displayText = "/$command${arguments.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}"
        val messageIdsBeforeSend = _uiState.value.messages.map { it.id }.toSet()

        if (!_uiState.value.isConnected) {
            offlineMessageQueue.update {
                it + QueuedPrompt(displayText, emptyList(), emptyList(), _uiState.value.sessionId, _uiState.value.chatGeneration)
            }
            val userMessage =
                ChatMessage(
                    isUser = true,
                    parts = listOf(ChatPart.Text(id = UUID.randomUUID().toString(), text = displayText)),
                )
            restorePoints.markOffline(userMessage.id)
            _uiState.update {
                it.copy(
                    messages = rewind.keepRewind(it) + userMessage,
                    rewoundFrom = null,
                    fileRestore = null,
                    offlineQueue = it.offlineQueue + displayText,
                    isOfflineQueued = true,
                )
            }
            return
        }

        val mustQueue = (currentBackend as? RuntimeTarget)?.capabilities?.forcesQueue == true
        if (holdsForRunningTurn(queues = mustQueue, sendNow = sendNow)) {
            messageQueue.update {
                it +
                    QueuedPrompt(
                        displayText,
                        emptyList(),
                        emptyList(),
                        _uiState.value.sessionId,
                        _uiState.value.chatGeneration,
                    )
            }
            return
        }
        val interrupting = _uiState.value.isRunning
        // A command is a turn like any message: without its own snapshot, the turn before it would
        // count the command's changes as its own.
        val turnAtSend = sentTurns()

        val userMessage =
            ChatMessage(
                isUser = true,
                parts = listOf(ChatPart.Text(id = UUID.randomUUID().toString(), text = displayText)),
            )
        _uiState.update {
            it.copy(
                messages = rewind.keepRewind(it) + userMessage,
                rewoundFrom = null,
                // A command changes files like any message: an Undo offered past it would undo its work.
                fileRestore = null,
                isRunning = true,
                isThinking = true,
                retry = null,
                runActivity = null,
                partialText = "",
                error = null,
            )
        }
        // A turn that replaces or queues behind a running one leaves isRunning true, so the stall
        // clock has to be restarted here rather than only on the idle-to-running transition.
        recordProgress()
        // This turn now owns the composer's poll; see [runGeneration].
        runGeneration += 1L
        val runGenerationAtSend = runGeneration

        // As in sendMessage - which chat asked, so a preset is not applied to a different one.
        val draftGeneration = _uiState.value.chatGeneration
        viewModelScope.launch {
            var capturedSessionId: String? = null
            runCatching {
                val existingSessionId = _uiState.value.sessionId
                val session =
                    if (existingSessionId == null) {
                        currentBackend.createSession(
                            title = null,
                            directory = _uiState.value.selectedWorkspacePath,
                        )
                    } else {
                        null
                    }
                val targetSessionId = existingSessionId ?: requireNotNull(session).id
                capturedSessionId = targetSessionId
                if (session != null) {
                    systemPrompt.applyDraftSystemPrompt(session.id, draftGeneration)
                    _uiState.update {
                        it.copy(
                            sessionId = session.id,
                            sessionTitle = session.title,
                            sessionDirectory = session.directory ?: it.sessionDirectory,
                        )
                    }
                    adoptHeldPrompts(session.id, draftGeneration)
                    onSessionCreated()
                }

                if (interrupting) interruptRunningTurn(currentBackend, targetSessionId)
                restorePoints.recordTurnSnapshot(targetSessionId, turnAtSend, session?.directory)

                // The command endpoint on OpenCode runs the whole turn before answering, so it is
                // fired in its own coroutine: the poll loop below (and the SSE stream) drive the UI
                // without waiting on an HTTP call that may outlive a long turn.
                viewModelScope.launch {
                    runCatching {
                        currentBackend.executeCommand(
                            sessionId = targetSessionId,
                            command = command,
                            arguments = arguments,
                        )
                    }.onFailure { error -> reportError(error) }
                }

                runCatching { currentBackend.session(targetSessionId).title }
                    .onSuccess { title ->
                        if (title.isNotBlank()) _uiState.update { it.copy(sessionTitle = title) }
                    }
                if (usesComposer) clearDraft(targetSessionId)
                var sessionCompleted = false

                fun isStillActive() = _uiState.value.sessionId == targetSessionId

                // As in sendMessage: a queued prompt that supersedes this turn must not be
                // settled by this poller waking into it.
                fun stillOwnsRun() = runGeneration == runGenerationAtSend
                val trustsIdleEvents =
                    (currentBackend as? RuntimeTarget)?.capabilities?.reliableIdleEvents == true
                val pollFinished =
                    withTimeoutOrNull(RESPONSE_POLL_TIMEOUT_MS) {
                        while (isStillActive() && stillOwnsRun() && _uiState.value.isRunning) {
                            delay(
                                if (streamError == null && trustsIdleEvents) STREAM_HEALTHY_POLL_INTERVAL_MS else RESPONSE_POLL_INTERVAL_MS,
                            )
                            // The turn can end while this slept; the idle handler owns the
                            // transcript then. Another fetch here would merge against a stream
                            // cache it has already cleared and drop what only this client has.
                            if (!isStillActive() || !stillOwnsRun() || !_uiState.value.isRunning) return@withTimeoutOrNull
                            // Snapshotted before the fetch: the idle can clear the stream cache
                            // while it is in flight, and merging against an empty live view would
                            // drop what only this client has.
                            val retainIds = streamedParts.keys.toSet()
                            runCatching { currentBackend.listMessages(targetSessionId) }
                                .onSuccess { serverMessages ->
                                    if (!isStillActive()) return@onSuccess
                                    val uiMessages =
                                        mergeReloadedMessages(
                                            serverMessages.mapNotNull(::toUiMessage),
                                            _uiState.value.messages,
                                            retainIds = retainIds,
                                        )
                                    if (uiMessages.isNotEmpty() && uiMessages != _uiState.value.messages) {
                                        _uiState.update { it.copy(messages = uiMessages) }
                                    }
                                    if (turnFinished(serverMessages, messageIdsBeforeSend)) {
                                        sessionCompleted = true
                                        _uiState.update { it.copy(isRunning = false, isThinking = false) }
                                    }
                                }
                        }
                    }
                closeInterruptWindow(targetSessionId)
                if (isStillActive() && stillOwnsRun() && (sessionCompleted || pollFinished == null)) {
                    runCatching { currentBackend.listMessages(targetSessionId) }
                        .onSuccess { serverMessages ->
                            if (!isStillActive() || !stillOwnsRun()) return@onSuccess
                            // A completed reply, not a merely present one - see sendMessage.
                            val turnDone = turnFinished(serverMessages, messageIdsBeforeSend)
                            if (!sessionCompleted && !turnDone) return@onSuccess
                            val retainedIds = streamedParts.keys.toSet()
                            streamedParts.clear()
                            transcriptGeneration++
                            _uiState.update {
                                it.copy(
                                    messages =
                                        mergeReloadedMessages(
                                            serverMessages.mapNotNull(::toUiMessage),
                                            it.messages,
                                            retainIds = retainedIds,
                                        ),
                                    isRunning = false,
                                    isThinking = false,
                                )
                            }
                            contextUsage.refresh(targetSessionId)
                            drainQueue()
                        }
                }
            }.onFailure { error ->
                capturedSessionId?.let(::closeInterruptWindow)
                if (capturedSessionId == null || _uiState.value.sessionId == capturedSessionId) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            isThinking = false,
                        )
                    }
                }
                reportError(error)
            }
        }
    }

    /**
     * If [text] invokes a command or skill the backend knows about, returns it as `(name, arguments)`.
     *
     * App-level commands (`/new`, `/clear`, …) are not backend commands, so they are left alone and
     * keep their existing handling.
     */
    private fun matchSlashCommand(text: String): Pair<String, String>? {
        if (!text.startsWith("/")) return null
        val firstLineEnd = text.indexOf('\n')
        val firstLine = if (firstLineEnd == -1) text else text.substring(0, firstLineEnd)
        val tokens = firstLine.split(" ")
        val rawName = tokens.firstOrNull()?.trimStart('/')?.trim().orEmpty()
        if (rawName.isEmpty()) return null
        val restOfLine = tokens.drop(1).joinToString(" ")
        val restOfInput = if (firstLineEnd == -1) "" else text.substring(firstLineEnd + 1)
        val arguments =
            when {
                restOfLine.isNotEmpty() && restOfInput.isNotEmpty() -> "$restOfLine\n$restOfInput"
                else -> restOfLine + restOfInput
            }
        val state = _uiState.value
        val known =
            state.slashCommands.any { it.name == rawName } || state.slashSkills.any { it.name == rawName }
        return if (known) rawName to arguments else null
    }

    /**
     * True once the assistant has answered this prompt and marked its reply finished.
     *
     * The newest message being a completed assistant turn is the signal every runtime reports;
     * requiring a reply that did not exist before the prompt keeps the previous turn's completion
     * from ending the new one instantly.
     */
    private fun turnFinished(
        messages: List<OpenCodeMessage>,
        messageIdsBeforeSend: Set<String>,
    ): Boolean {
        val newest = messages.lastOrNull()?.info ?: return false
        if (newest.role == "user") return false
        val answeredThisPrompt =
            messages.any { it.info.role != "user" && it.info.id !in messageIdsBeforeSend }
        return answeredThisPrompt && newest.time.completed != null
    }

    fun respondToPermission(
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ) = permissions.respondToPermission(permissionId, response, remember)

    /** Allows [permissionId] and every later call of a tool from the same MCP server. */
    fun allowMcpServer(permissionId: String) = permissions.allowMcpServer(permissionId)

    fun selectQuestionAnswer(
        questionId: String,
        questionIndex: Int,
        answer: String,
    ) = questions.selectQuestionAnswer(questionId, questionIndex, answer)

    fun submitQuestion(questionId: String) = questions.submitQuestion(questionId)

    /**
     * Hides the question card and leaves the turn alone, so the user can ignore the question and
     * just keep typing. The request stays open on the OpenCode side; this only affects what the
     * chat shows.
     */
    fun dismissQuestion(questionId: String) = questions.dismissQuestion(questionId)

    /**
     * Declines the question. OpenCode fails the waiting tool call with "the user dismissed this
     * question", which the agent can react to — unlike [dismissQuestion], which only clears the
     * card and leaves the request open.
     */
    fun cancelQuestion(questionId: String) = questions.cancelQuestion(questionId)

    /**
     * Closes the sticky todo bar for one `todowrite` update. Nothing server-side tracks this — the
     * agent has no "todo list closed" event, and deleting its scratch file on disk never reaches the
     * app either — so this is purely a local "stop showing me this" the user can always reach, even
     * when the agent never reports the list as finished.
     */
    fun dismissTodoBar(entryId: String) {
        _uiState.update { it.copy(dismissedTodoBarId = entryId) }
    }

    fun abort() {
        val currentBackend = backend ?: return
        val sessionId = _uiState.value.sessionId ?: return
        // Stopping by hand ends the turn outright, so the idle it produces is the one the chat is
        // waiting for rather than a leftover to skip past.
        closeInterruptWindow(sessionId)
        viewModelScope.launch {
            onSessionAborted(sessionId)
            runCatching { currentBackend.abortSession(sessionId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(isRunning = false, isThinking = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.safeMessage("OpenCode operation failed")) }
                }
        }
    }

    /**
     * Ends the turn in flight before the prompt that replaces it is sent.
     *
     * Sending alone is not enough on a runtime that drops a prompt arriving mid-turn (see
     * [com.yugahashimoto.andcode.runtime.RuntimeCapabilities.abortsBeforeInterrupt]): OpenCode
     * stores the message, hands it to a session runner that is already running, and never starts a
     * run for it. That is the "sent a message and nothing came back" case, and it is why stopping
     * the wedged turn by hand and then sending again worked. Aborting here does that stop
     * automatically.
     */
    private suspend fun interruptRunningTurn(
        currentBackend: OpenCodeBackend,
        sessionId: String,
    ) {
        // Reported whether or not this runtime is aborted below: a runtime that instead queues the
        // prompt on the turn in flight still ends that turn with an idle of its own (Claude Code
        // emits one per `result`), and announcing it as a completed run would be announcing the turn
        // the user just replaced.
        onSessionAborted(sessionId)
        if ((currentBackend as? RuntimeTarget)?.capabilities?.abortsBeforeInterrupt != true) return
        val aborted = runCatching { currentBackend.abortSession(sessionId) }.getOrDefault(false)
        // A backend that had nothing to cancel reports no run aborted and emits no idle for one, so
        // opening the window then would only cost the replacement turn its own end-of-turn idle.
        if (!aborted) return
        pendingInterrupts += sessionId
        // Settling the cancelled run's bubble is normally the idle's job, and that idle is about to
        // be skipped, so it is done here instead rather than leaving it spinning over work that has
        // already stopped.
        if (_uiState.value.sessionId != sessionId) return
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it },
            )
        }
    }

    /**
     * Re-reads the open chat's transcript and open questions, keeping what is still streaming in.
     * What opening the chat from the drawer does, without clearing the screen first: used when the
     * stream reconnects, when the app comes back on screen, and when a running chat has heard
     * nothing live for a while.
     */
    private fun resyncOpenChat() {
        val session = _uiState.value.sessionId ?: return
        val currentBackend = backend ?: return
        lastCatchUpAt = now()
        val generation = ++transcriptGeneration
        resyncJob?.cancel()
        resyncJob =
            viewModelScope.launch {
                runCatching { currentBackend.listMessages(session) }
                    .onSuccess { messages ->
                        // Superseded by a session switch, a newer read or the turn's own final reload.
                        if (_uiState.value.sessionId != session || generation != transcriptGeneration) return@onSuccess
                        applyResync(session, messages)
                    }
            }
        questions.refreshPendingQuestions(session)
    }

    private fun applyResync(
        session: String,
        messages: List<OpenCodeMessage>,
    ) {
        val live = streamedParts.keys.toSet()
        val reloaded = messages.mapNotNull(::toUiMessage)
        // Whether the transcript has caught up with the prompt on screen, rather than ending on the
        // previous turn while a prompt just sent is still on its way.
        val latestPrompt = _uiState.value.messages.lastOrNull { it.isUser }?.text
        val hasLatestPrompt = latestPrompt != null && reloaded.lastOrNull { it.isUser }?.text == latestPrompt
        _uiState.update { state ->
            val onScreen = state.messages.associateBy { it.id }
            val merged =
                mergeReloadedMessages(reloaded, state.messages, retainIds = live)
                    .map { message ->
                        val streamed = onScreen[message.id]?.takeIf { message.id in live }
                        if (streamed == null) message else fresherReply(streamed, message)
                    }
            state.copy(messages = merged)
        }
        if (!_uiState.value.isRunning) return
        // The stream cache now matches the screen, so the next delta extends what is shown.
        val messagesNow = _uiState.value.messages
        val lastUser = messagesNow.indexOfLast { it.isUser }
        messagesNow.drop(lastUser + 1).filter { !it.isUser && it.parts.isNotEmpty() }.forEach { message ->
            streamedParts[message.id] = LinkedHashMap(message.parts.associateBy { it.id })
        }
        // A turn that ended while the stream was silent sent its idle into the void. Only trusted
        // once the stream has been quiet a while: between two steps of a live turn the newest
        // message is briefly complete too.
        val answered = hasLatestPrompt && messages.lastOrNull()?.info?.role == "assistant"
        if (answered && inspectRun(messages).turnCompleted && now() - lastStreamEventAt >= CATCH_UP_QUIET_MS) {
            handleSessionIdle(session)
        }
    }

    /**
     * While a turn runs and nothing has arrived live for [CATCH_UP_QUIET_MS] - or the event stream
     * is down - the transcript is re-read every [CATCH_UP_INTERVAL_MS], so the reply still appears
     * when the live stream does not deliver it. A healthy stream never triggers this: every event
     * counts as progress.
     */
    private fun maybeCatchUp() {
        val state = _uiState.value
        if (!state.isRunning || state.sessionId == null) return
        val quiet = now() - lastProgressAt >= CATCH_UP_QUIET_MS && now() - lastStreamEventAt >= CATCH_UP_QUIET_MS
        if (!quiet && streamError == null) return
        if (now() - lastCatchUpAt < catchUpIntervalMs) return
        catchUpIntervalMs = (catchUpIntervalMs * 2).coerceAtMost(CATCH_UP_MAX_INTERVAL_MS)
        resyncOpenChat()
    }

    /**
     * Stops treating [sessionId]'s idles as leftovers of an interrupted run, so a turn that ends
     * without the replacement run ever reporting busy cannot leave idles ignored for good.
     */
    private fun closeInterruptWindow(sessionId: String) {
        pendingInterrupts -= sessionId
    }

    /**
     * Notes that the running turn produced something, which resets the stall clock and takes down
     * a warning the chat may already be showing.
     */
    private fun recordProgress() {
        lastProgressAt = now()
        catchUpIntervalMs = CATCH_UP_INTERVAL_MS
        if (_uiState.value.stall != null) _uiState.update { it.copy(stall = null) }
    }

    /**
     * Asks the runtime what became of a turn that has produced nothing for [STALL_THRESHOLD_MS].
     *
     * A run that dies in the background looks exactly like one that is thinking hard — same
     * spinner, same stop button, no message either way — and that is the whole problem this
     * answers. The verdict either settles the turn (it finished unheard, or it failed) or is
     * published as [ChatUiState.stall] for the chat to show, along with what the runtime blames
     * it on.
     */
    fun checkForStall() {
        val currentBackend = backend ?: return
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        if (!state.isRunning || stallCheckRunning) return
        val silentFor = now() - lastProgressAt
        if (silentFor < STALL_THRESHOLD_MS) return
        stallCheckRunning = true
        lastStallProbeAt = now()
        viewModelScope.launch {
            try {
                diagnoseSilentRun(currentBackend, sessionId, silentFor)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Nothing here is worth crashing the app over: this coroutine has no handler above
                // it, and a feature whose whole job is to report that something went wrong is the
                // last thing that should take the chat down when it does. Any verdict already on
                // screen is left alone — it is still the best answer anyone has — and the next tick
                // tries again.
            } finally {
                stallCheckRunning = false
            }
        }
    }

    private suspend fun diagnoseSilentRun(
        currentBackend: OpenCodeBackend,
        sessionId: String,
        silentFor: Long,
    ) {
        val health = runCatching { currentBackend.health() }
        val transcript = runCatching { currentBackend.listMessages(sessionId) }
        // Probing is not instant. Anything it learned about a turn that has ended in the meantime,
        // about a chat the user has since left, or about a run that came back to life while the
        // probe was in flight, is no longer this chat's business.
        val state = _uiState.value
        if (state.sessionId != sessionId || !state.isRunning) return
        if (now() - lastProgressAt < STALL_THRESHOLD_MS) return
        val diagnosis =
            diagnoseStall(
                StallEvidence(
                    silentForMillis = silentFor,
                    runtimeReachable = health.getOrNull()?.healthy == true,
                    runtimeError = health.exceptionOrNull()?.safeMessage(),
                    streamConnected = streamError == null,
                    streamError = streamError,
                    awaitingPermission = state.permissions.isNotEmpty(),
                    awaitingQuestion = state.pendingQuestions.isNotEmpty(),
                    retry = state.retry,
                    transcript = transcript.map(::inspectRun).getOrDefault(RunSignals()),
                ),
            )
        when (diagnosis.reason) {
            // The turn is over and the app simply never heard about it; settle it the way the idle
            // that went missing would have.
            StallReason.COMPLETION_MISSED -> handleSessionIdle(sessionId)
            StallReason.PROVIDER_ERROR -> {
                _uiState.update {
                    it.copy(isRunning = false, isThinking = false, stall = null, error = diagnosis.detail)
                }
                refreshMessages(sessionId)
            }
            else -> {
                val previous = _uiState.value.stall?.reason
                _uiState.update { it.copy(stall = diagnosis) }
                // A question the app never received blocks the turn for good. Fetching the open
                // ones puts the card back, so answering it can get the run moving again. Only worth
                // doing when the verdict is new: re-probing an unchanged one just costs requests.
                if (diagnosis.reason == StallReason.NO_OUTPUT && previous != StallReason.NO_OUTPUT) {
                    questions.refreshPendingQuestions(sessionId)
                }
            }
        }
    }

    private fun handleEvent(event: OpenCodeEvent) {
        lastStreamEventAt = now()
        val activeSession = _uiState.value.sessionId
        if (activeSession != null && event.sessionIdOrNull() == activeSession && event.provesRunProgress()) {
            recordProgress()
        }
        when (event) {
            OpenCodeEvent.ServerConnected -> {
                _uiState.update { it.copy(isConnected = true, error = null) }
                resyncOpenChat()
                drainOfflineQueue()
            }
            is OpenCodeEvent.MessageUpdated -> {
                if (event.info.sessionId != activeSession) return
                messageRoles[event.info.id] = event.info.role
            }
            is OpenCodeEvent.MessagePartUpdated -> {
                val part = event.part
                if (part.sessionId != activeSession) return
                val messageId = part.messageId ?: part.id ?: return
                // The server echoes the user's own prompt back as parts too; rendering those
                // would duplicate the bubble the composer already added.
                if (messageRoles[messageId] == "user") return
                val partId = part.id ?: messageId
                val chatPart = part.toChatPart(messageId) ?: return
                val messageParts = streamedParts.getOrPut(messageId) { linkedMapOf() }
                val mergedPart = mergeStreamedPart(messageParts[partId], chatPart)
                messageParts[partId] = mergedPart
                updateStreamingMessage(messageId, messageParts.values.toList())
            }
            is OpenCodeEvent.MessagePartDelta -> {
                // OpenCode streams both text and reasoning deltas with field="text"; the Claude
                // bridge uses field="reasoning" for thinking deltas. Anything else carries no
                // displayable text.
                if (event.sessionId != activeSession || (event.field != "text" && event.field != "reasoning")) return
                connectionMonitor.recordStreamTokens(event.mergeCount)
                val messageParts = streamedParts.getOrPut(event.messageId) { linkedMapOf() }
                val updatedPart = appendStreamedDelta(messageParts[event.partId], event) ?: return
                messageParts[event.partId] = updatedPart
                updateStreamingMessage(event.messageId, messageParts.values.toList())
            }
            is OpenCodeEvent.PermissionAsked -> permissions.onPermissionAsked(event, activeSession)
            is OpenCodeEvent.QuestionAsked -> questions.onQuestionAsked(event, activeSession)
            is OpenCodeEvent.PermissionReplied -> permissions.onPermissionReplied(event, activeSession)
            is OpenCodeEvent.SessionStatusChanged -> {
                // session.idle is deprecated in favour of session.status; treat idle the same way,
                // and let a busy status pick up a run that was started from another client.
                if (event.sessionId != activeSession) return
                when (event.status) {
                    "idle" -> handleSessionIdle(event.sessionId)
                    "busy", "retry" -> {
                        closeInterruptWindow(event.sessionId)
                        if (!_uiState.value.isRunning) {
                            _uiState.update { it.copy(isRunning = true) }
                        }
                        // Kept through the busy that starts each new attempt and only cleared by
                        // output or the end of the run, so the card does not flicker back to
                        // "Thinking" between failures.
                        event.retry?.let { retry -> _uiState.update { it.copy(retry = retry) } }
                    }
                }
            }
            is OpenCodeEvent.RunActivityChanged -> {
                if (event.sessionId != activeSession) return
                // Only while a turn runs: a late notice must not stick to an idle chat. The end of a
                // step (null) only ends compacting: a model switch holds for the rest of the run. A
                // new step also replaces a retry card left from before it, which only output clears.
                if (_uiState.value.isRunning) {
                    _uiState.update { state ->
                        state.copy(
                            runActivity = event.activity ?: state.runActivity.takeUnless { it == RunActivity.Compacting },
                            retry = if (event.activity != null) null else state.retry,
                        )
                    }
                }
            }
            is OpenCodeEvent.SessionIdle -> {
                if (event.sessionId != activeSession) return
                handleSessionIdle(event.sessionId)
            }
            is OpenCodeEvent.SessionError -> {
                if (event.sessionId != null && event.sessionId != activeSession) return
                // Stopping a run on purpose - the stop button, or a replacement prompt sent
                // mid-turn - makes the runtime emit this same event carrying an abort error.
                // That is a decision, not a failure: painting it red left the card up forever,
                // outliving every later turn, and nothing the turn did next ever cleared it.
                // The abort paths settle their own state; there is nothing to report here.
                if (event.isAbort) return
                event.sessionId?.let(::closeInterruptWindow)
                val message = event.message ?: "OpenCode session failed"
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isThinking = false,
                        error = message,
                    )
                }
                // The failed turn owned the queue's turn-end; without this drain the prompts
                // behind it sat stranded until some later turn happened to end with an idle.
                drainQueue()
                // A provider-fetch failure that arrives as session.error (e.g. issue #306:
                // "Cannot connect to API: Unable to connect...") left a static red card with
                // no recovery: only reinstall cleared it. Route transient ones through the
                // same reconnect loop as send failures so the chat retries instead of dying.
                if (classifyChatError(message) == ChatErrorKind.TRANSIENT_CONNECTION) {
                    scheduleTransientRecovery()
                }
            }
            is OpenCodeEvent.SessionCreated -> Unit
            // A rewind made permanent, or made or undone elsewhere (another device, the TUI).
            is OpenCodeEvent.SessionUpdated -> rewind.onSessionUpdated(event.session)
            is OpenCodeEvent.Unknown -> Unit
        }
    }

    private fun handleSessionIdle(sessionId: String) {
        // An idle answering an interrupt belongs to the run that was just cancelled, not to the
        // prompt sent in its place; acting on it would park the composer on the send button and
        // drain another queued prompt over a turn that is only starting.
        if (sessionId in pendingInterrupts) return
        // The HTTP runtime reports one run end as BOTH session.status:idle and the deprecated
        // session.idle, milliseconds apart. The second pass through here settled the turn the
        // first pass's queue-drain had just started (isRunning flipped off mid-run, the stream
        // cache wiped) and drained a SECOND prompt over it - so an idle echoing a settle that
        // just happened for this session is ignored. See [lastIdleSettledAtMillis]. The injected
        // [now] clock keeps the window testable: a genuinely distinct second run end advances it
        // by the whole turn, far past the window.
        val nowMillis = now()
        if (sessionId == lastIdleSettledSessionId &&
            nowMillis - lastIdleSettledAtMillis < DUPLICATE_IDLE_WINDOW_MILLIS
        ) {
            return
        }
        lastIdleSettledSessionId = sessionId
        lastIdleSettledAtMillis = nowMillis
        // Captured before the stream cache is cleared: a bubble this client streamed that the
        // transcript still does not carry (an interrupted turn some runtimes never persist) must
        // survive the reload below, not vanish with the cache.
        val retainedIds = streamedParts.keys.toSet()
        streamedParts.clear()
        transcriptGeneration++
        _uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { message ->
                        if (message.isStreaming) message.copy(isStreaming = false) else message
                    },
                isRunning = false,
                isThinking = false,
            )
        }
        contextUsage.refresh(sessionId)
        refreshMessages(sessionId, retainedIds)
        onSessionCreated()
        drainQueue()
    }

    private fun refreshMessages(
        sessionId: String,
        retainIds: Set<String> = emptySet(),
    ) {
        val currentBackend = backend ?: return
        // A settle for chat A can still be fetching when the user opens chat B; the merge below
        // would otherwise overwrite B's transcript with A's bubbles.
        val generation = _uiState.value.chatGeneration
        viewModelScope.launch {
            runCatching { currentBackend.listMessages(sessionId) }
                .onSuccess { messages ->
                    if (_uiState.value.chatGeneration != generation) return@onSuccess
                    val uiMessages =
                        mergeReloadedMessages(
                            messages.mapNotNull(::toUiMessage),
                            _uiState.value.messages,
                            retainIds = retainIds,
                        )
                    if (uiMessages.isNotEmpty()) {
                        _uiState.update { it.copy(messages = uiMessages) }
                    }
                }
        }
    }

    private fun updateStreamingMessage(
        messageId: String,
        parts: List<ChatPart>,
    ) {
        _uiState.update { state -> withStreamedParts(state, messageId, parts) }
    }

    private fun toUiMessage(message: OpenCodeMessage): ChatMessage? {
        if (rewind.isDiscarded(message.info.id)) return null
        messageRoles[message.info.id] = message.info.role
        return toChatMessage(message)
    }

    fun setTTS(textToSpeech: TextToSpeech) = voice.setTTS(textToSpeech)

    fun startListening() = voice.startListening()

    fun updateSpeechPartial(text: String) = voice.updateSpeechPartial(text)

    fun showSpeechProcessing() = voice.showSpeechProcessing()

    fun reportSpeechError(message: String) = voice.reportSpeechError(message)

    fun stopListening() = voice.stopListening()

    fun stopSpeaking() = voice.stopSpeaking()

    fun copyMessageContent(messageId: String): String? {
        return _uiState.value.messages.firstOrNull { it.id == messageId }?.text
    }

    /**
     * Sends the open chat's next held prompt. Prompts held for another chat stay held until that chat
     * is open again and finishes a turn: sending one on the way back in could interrupt a turn the
     * app has not yet heard is still running.
     */
    private fun drainQueue() {
        // Held, not dropped: send() refuses while a rewind lands, and the rewind drains after.
        if (_uiState.value.isRewinding || restorePoints.inFlight) return
        val queued = messageQueue.value
        val next = queued.indexOfFirst { it.sessionId == _uiState.value.sessionId }
        if (next >= 0) {
            messageQueue.value = queued.filterIndexed { index, _ -> index != next }
            sendQueued(queued[next])
        } else if (_uiState.value.isConnected) {
            // Written offline here while another chat was open when the connection came back.
            drainOfflineQueue()
        }
    }

    private fun drainOfflineQueue() {
        if (_uiState.value.isRewinding || restorePoints.inFlight) return
        val (open, others) = offlineMessageQueue.value.partition { it.sessionId == _uiState.value.sessionId }
        if (open.isEmpty()) return
        offlineMessageQueue.value = others
        _uiState.update { it.copy(offlineQueue = emptyList(), isOfflineQueued = false) }
        messageQueue.update { it + open.drop(1) }
        sendQueued(open.first())
    }

    /**
     * A new chat's held prompts were queued before it had an id: they belong to the session it got.
     * Only that chat's: one started after it, while its session was still being created, keeps its own.
     */
    private fun adoptHeldPrompts(
        sessionId: String,
        generation: Long,
    ) {
        val adopt = { queue: List<QueuedPrompt> ->
            queue.map { if (it.sessionId == null && it.chatGeneration == generation) it.copy(sessionId = sessionId) else it }
        }
        messageQueue.update(adopt)
        offlineMessageQueue.update(adopt)
    }

    /** Leaving a new chat that never got a session leaves its held prompts nowhere to go. */
    private fun dropNewChatPrompts() {
        messageQueue.update { queue -> queue.filter { it.sessionId != null } }
        offlineMessageQueue.update { queue -> queue.filter { it.sessionId != null } }
    }

    /**
     * Sends one held prompt and leaves the rest of the queue for the next idle.
     *
     * Firing a whole queue at once only worked while a send could not interrupt: the first prompt
     * marks the chat as running, so every prompt after it would now abort the turn the one before it
     * just started and take its place. See [interruptRunningTurn].
     */
    private fun sendQueued(
        queuedPrompt: QueuedPrompt,
        sendNow: Boolean = false,
    ) {
        // With its own attachments: the composer, where the user may be writing the next message, is
        // left alone.
        send(queuedPrompt.text, fromComposer = queuedPrompt.fromComposer, sendNow = sendNow, held = queuedPrompt)
    }

    /**
     * Whether a prompt sent now waits for the running turn: with send behaviour "queue", or when
     * [queues] says this prompt always waits (a runtime that cannot interrupt, a review); never for
     * a prompt the user chose to send now.
     */
    private fun holdsForRunningTurn(
        queues: Boolean,
        sendNow: Boolean,
    ): Boolean = !sendNow && _uiState.value.isRunning && (_sendBehavior.value == "queue" || queues)

    /** Drops a held prompt; it is not sent. */
    fun removeQueuedPrompt(id: String) {
        messageQueue.update { queue -> queue.filterNot { it.id == id } }
    }

    /**
     * Takes a held prompt back into the composer to change it, with its attachments; it joins what
     * the user is typing rather than replacing it. A review from the diff screen comes back as plain
     * text and goes out again as an ordinary message. A file that could not go to the model is
     * already a note in the text, so it comes back as that note, not as a chip.
     */
    fun editQueuedPrompt(id: String) {
        val prompt = messageQueue.value.firstOrNull { it.id == id } ?: return
        removeQueuedPrompt(id)
        _uiState.update {
            // Two taken back before the composer picks the first up both arrive.
            val pending = it.editDraft.takeIf { _ -> it.editDraftJoinsInput }
            it.copy(
                editDraft = listOfNotNull(pending, prompt.text).joinToString("\n\n"),
                editDraftJoinsInput = true,
                attachments = it.attachments + prompt.attachments,
                imagePreviews = it.imagePreviews + prompt.imagePreviews,
            )
        }
    }

    /**
     * Sends a held prompt at once, stopping the running turn as the "interrupt" send behaviour does.
     * Not for a runtime that cannot interrupt a turn, whose prompts always wait.
     */
    fun sendQueuedPromptNow(id: String) {
        if (!_uiState.value.canSendQueuedNow) return
        val prompt = messageQueue.value.firstOrNull { it.id == id } ?: return
        if (_uiState.value.isRewinding || restorePoints.inFlight) return
        removeQueuedPrompt(id)
        sendQueued(prompt, sendNow = true)
    }

    /**
     * Drops this ViewModel's heavy state when the store will never call [onCleared] for it.
     *
     * [AndCodeApp] keys one ChatViewModel per runtime id, and the Activity's ViewModelStore keeps
     * every keyed instance - and its transcript, attachment previews (~4 MB a bitmap) and stream
     * caches - alive until the Activity itself dies. Switching runtimes therefore leaks the
     * instance being left. The scope is deliberately NOT cancelled here: the store can hand the
     * same instance back when the user switches back, and a cancelled viewModelScope would
     * permanently break it.
     */
    fun release() {
        streamedParts.clear()
        messageRoles.clear()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                attachments = emptyList(),
                imagePreviews = emptyList(),
            )
        }
    }

    override fun onCleared() {
        // Preview bitmaps are deliberately not recycled here either: the composition that draws
        // them can outlive the ViewModel during teardown, and the GC reclaims them anyway.
        eventJob?.cancel()
        voice.shutDown()
        super.onCleared()
    }

    private fun reportError(throwable: Throwable) {
        _uiState.update { it.copy(error = throwable.safeMessage("OpenCode operation failed")) }
        if (classifyChatError(throwable) == ChatErrorKind.TRANSIENT_CONNECTION) {
            scheduleTransientRecovery()
        }
    }

    private fun reportError(message: String?) {
        _uiState.update { it.copy(error = message) }
        if (classifyChatError(message) == ChatErrorKind.TRANSIENT_CONNECTION) {
            scheduleTransientRecovery()
        }
    }

    /**
     * Keeps probing the runtime until it is reachable again. The previous implementation ran a
     * single health check five seconds after the failure and then stopped, so a runtime that came
     * back any later was never noticed: the "reconnecting" card stayed up forever even though no
     * reconnection was happening.
     */
    private fun scheduleTransientRecovery() {
        if (recoveringTransiently) return
        recoveringTransiently = true
        viewModelScope.launch {
            try {
                delay(TRANSIENT_RECOVERY_DELAY_MS)
                val currentBackend = backend ?: return@launch
                if (!stillDisconnected()) return@launch
                var backoffMs = TRANSIENT_RECOVERY_RETRY_DELAY_MS
                repeat(TRANSIENT_RECOVERY_MAX_ATTEMPTS) {
                    val recovered =
                        runCatching { currentBackend.health() }.getOrNull()?.healthy == true &&
                            restoreConnection(currentBackend)
                    if (recovered) return@launch
                    // A successful stream reconnect (ServerConnected), a fresh send, or a non
                    // transient error superseding the failure all mean the recovery is over.
                    if (!stillDisconnected()) return@launch
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(TRANSIENT_RECOVERY_MAX_BACKOFF_MS)
                }
            } finally {
                recoveringTransiently = false
            }
        }
    }

    /**
     * True while the chat is still reporting the transient disconnect that triggered recovery.
     *
     * [ChatUiState.isConnected] is deliberately not consulted: it is a sticky "has ever connected"
     * flag that stays true after a mid-run send failure, so keying on it would stop recovery before
     * the runtime is actually reachable again.
     */
    private fun stillDisconnected(): Boolean {
        val error = _uiState.value.error ?: return false
        return classifyChatError(error) == ChatErrorKind.TRANSIENT_CONNECTION
    }

    /**
     * Re-syncs the transcript after the runtime is healthy again. Returns false when the transcript
     * is not reachable yet, in which case the reconnecting card is left up and recovery retries.
     */
    private suspend fun restoreConnection(currentBackend: OpenCodeBackend): Boolean {
        val session = _uiState.value.sessionId
        val generation = _uiState.value.chatGeneration
        val refreshed =
            if (session != null) {
                runCatching { currentBackend.listMessages(session) }
                    .onSuccess { messages ->
                        if (_uiState.value.chatGeneration != generation) return@onSuccess
                        val retainedIds = streamedParts.keys.toSet()
                        // Recovery runs while a turn may still be streaming on the server;
                        // clearing the live cache here truncates the bubble exactly as the
                        // reconnect path above does. The retained ids keep the streamed-only
                        // content through the merge either way.
                        if (!_uiState.value.isRunning) streamedParts.clear()
                        transcriptGeneration++
                        _uiState.update {
                            it.copy(
                                messages =
                                    mergeReloadedMessages(
                                        messages.mapNotNull(::toUiMessage),
                                        it.messages,
                                        retainIds = retainedIds,
                                    ),
                            )
                        }
                    }
                    .isSuccess
            } else {
                true
            }
        if (refreshed) {
            _uiState.update { it.copy(error = null, isConnected = true) }
            drainOfflineQueue()
        }
        return refreshed
    }
}
