package com.yugahashimoto.andcode.feature.chat

import android.graphics.Bitmap
import com.yugahashimoto.andcode.core.api.ConnectionQuality
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodeSessionTokens
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.api.RunActivity
import com.yugahashimoto.andcode.core.api.RunRetry
import com.yugahashimoto.andcode.core.diagnostics.StallDiagnosis
import com.yugahashimoto.andcode.runtime.local.StagedSystemPrompt
import java.util.UUID

enum class ToolStatus { PENDING, RUNNING, COMPLETED, ERROR, UNKNOWN }

data class TodoItem(
    val content: String,
    val status: String,
    val priority: String,
)

sealed interface ChatPart {
    val id: String

    data class Text(override val id: String, val text: String) : ChatPart

    data class Reasoning(override val id: String, val text: String) : ChatPart

    data class Tool(
        override val id: String,
        val name: String,
        val status: ToolStatus,
        val title: String? = null,
        val input: String? = null,
        val output: String? = null,
        val outputTruncated: Boolean = false,
        val error: String? = null,
        val todos: List<TodoItem> = emptyList(),
        /**
         * The files a writing tool's input names, read from it before [input] was formatted for
         * display; empty for any other tool.
         */
        val filePaths: List<String> = emptyList(),
    ) : ChatPart

    data class Patch(override val id: String, val files: List<String>, val messageId: String) : ChatPart

    data class Image(
        override val id: String,
        val mime: String,
        val url: String,
        val filename: String? = null,
    ) : ChatPart

    /** The model/provider failed; carries the human-readable error reported by the runtime. */
    data class Error(override val id: String, val message: String) : ChatPart
}

/** Files put back by [ChatViewModel.restoreFilesTo] in [sessionId]'s [directory]; [token] undoes it. */
data class FileRestore(
    val sessionId: String,
    val directory: String,
    val token: String,
)

/**
 * State of a diff fetched for a [ChatPart.Patch] card the user tapped open.
 *
 * Every state carries [files] so the dialog can always show the file list the chat already knew
 * about, whether or not [RuntimeCapabilities.diffCapable] let the diff itself be fetched.
 */
sealed interface PatchDiffState {
    val partId: String
    val files: List<String>

    data class Loading(override val partId: String, override val files: List<String>) : PatchDiffState

    data class Loaded(
        override val partId: String,
        override val files: List<String>,
        val changes: List<OpenCodeFileChange>,
    ) : PatchDiffState

    data class Unavailable(override val partId: String, override val files: List<String>) : PatchDiffState
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val parts: List<ChatPart> = emptyList(),
    val attachments: List<PromptAttachment> = emptyList(),
    val imagePreviews: List<Bitmap> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val isStreaming: Boolean = false,
    /** The model that wrote it, or for a prompt the one it was sent to, when the runtime says. */
    val model: OpenCodeModelReference? = null,
    /** The agent that wrote it, or for a prompt the one it was sent to, when the runtime says. */
    val agent: String? = null,
    /** The tokens it took, when the runtime reports them. */
    val tokens: OpenCodeSessionTokens? = null,
    /** What it cost in USD, when the runtime reports it. */
    val costUsd: Double? = null,
) {
    val text: String
        get() = parts.filterIsInstance<ChatPart.Text>().joinToString("") { it.text }
}

/** A prompt waiting for the running turn, as the queue above the composer shows it. */
data class QueuedPromptView(
    val id: String,
    val text: String,
    val attachmentCount: Int,
)

/** What the user can do with a prompt waiting for the running turn; see [ChatViewModel.sendQueuedPromptNow]. */
data class QueuedPromptActions(
    val onSendNow: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
)

/**
 * Maps an index into [ChatUiState.attachments] onto its index in [ChatUiState.imagePreviews],
 * or null when the attachment carries no preview.
 *
 * The two lists are not parallel: a file picked with the paperclip appends an attachment without a
 * preview bitmap, so the previews line up with the *image* attachments in attachment order rather
 * than with every attachment. Removing attachment 2 must not drop preview 2 when the images sit at
 * different offsets.
 */
internal fun previewIndexForAttachment(
    attachments: List<PromptAttachment>,
    attachmentIndex: Int,
): Int? {
    val attachment = attachments.getOrNull(attachmentIndex) ?: return null
    if (!attachment.mime.startsWith("image/")) return null
    return attachments.take(attachmentIndex).count { it.mime.startsWith("image/") }
}

/** Inverse of [previewIndexForAttachment]: the attachment behind the preview thumbnail at [previewIndex]. */
internal fun attachmentIndexForPreview(
    attachments: List<PromptAttachment>,
    previewIndex: Int,
): Int? = attachments.indices.filter { attachments[it].mime.startsWith("image/") }.getOrNull(previewIndex)

/** The session a subagent conversation was started from, used to walk back to the main agent. */
data class ParentSessionRef(
    val id: String,
    val title: String = "",
)

data class ChatUiState(
    val backendName: String = "",
    val sessionId: String? = null,
    /**
     * Counts chat changes, so a choice captured for one chat is not applied to another.
     *
     * A blank chat has no session id to identify it by - that is the whole reason the draft below
     * exists - so this stands in. A send captures it alongside the draft and the draft is applied
     * only if it still matches: tapping Send and then starting another blank chat lets that send's
     * `createSession` land on the new chat, and the abandoned chat's preset must not follow it there.
     */
    val chatGeneration: Long = 0,
    /**
     * System-prompt preset chosen for this chat before it had a session to record it on.
     *
     * A chat's session is not created until its first message, so this holds the composer's choice
     * until [ChatViewModel] can write it to that session. It lives here, rather than on the agent,
     * because it belongs to this chat alone: it dies with this view model when the runtime changes,
     * is cleared when the composer moves to another chat, survives a trip to Settings and back, and
     * cannot be picked up by a session that some other entry point - a schedule, voice, the
     * quick-input widget - happens to create in the meantime.
     */
    val draftSystemPrompt: StagedSystemPrompt? = null,
    val sessionTitle: String = "",
    /** Non-null while the open session is a subagent session spawned by [ParentSessionRef.id]. */
    val parentSession: ParentSessionRef? = null,
    val messages: List<ChatMessage> = emptyList(),
    val permissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<PendingQuestionUi> = emptyList(),
    val isConnected: Boolean = false,
    val isRunning: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val isListening: Boolean = false,
    val isSpeechProcessing: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialText: String = "",
    val autoAcceptPermissions: Boolean = false,
    val contextTokensUsed: Long = 0L,
    /**
     * The window size the agent itself reported for this chat (Codex), which the meter measures
     * against; 0 where the model's listed limit is used instead.
     */
    val contextWindow: Long = 0L,
    /** What this chat has used so far; null until a turn reports tokens. */
    val sessionUsage: SessionUsage? = null,
    val selectedVariant: String? = null,
    /**
     * Whether the selected model offers Codex's fast service tier. Only Codex sets this, and only
     * for a model that lists the tier, so the toggle is hidden everywhere else.
     */
    val fastModeAvailable: Boolean = false,
    /** Effective fast-mode state: the user's choice, or the model's own default when there is none. */
    val fastModeEnabled: Boolean = false,
    /** The server's own description of the fast tier, read out by screen readers on the Fast chip. */
    val fastModeDescription: String? = null,
    /**
     * Whether the selected model can run in Claude Code's ultracode. Only Claude Code sets this, and
     * only for a model the CLI allows it on, so the switch is hidden everywhere else.
     */
    val ultracodeAvailable: Boolean = false,
    /** Effective ultracode state: on only when available and the user turned it on. */
    val ultracodeEnabled: Boolean = false,
    val attachments: List<PromptAttachment> = emptyList(),
    val imagePreviews: List<Bitmap> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val selectedAgentId: String? = null,
    val selectedWorkspacePath: String? = null,
    /** Workspace directory of the open session, learned from the backend when it was opened. */
    val sessionDirectory: String? = null,
    val slashCommands: List<OpenCodeCommand> = emptyList(),
    val slashSkills: List<OpenCodeSkill> = emptyList(),
    val offlineQueue: List<String> = emptyList(),
    val isOfflineQueued: Boolean = false,
    val connectionQuality: ConnectionQuality? = null,
    /** Pull requests linked in this chat, newest first, for the badges above the composer. */
    val pullRequests: List<ChatPullRequest> = emptyList(),
    /**
     * Id of the [TimelineEntry.Todo] the user closed with the sticky bar's dismiss button.
     * [groupConversationTimeline] gives each `todowrite` update a distinct entry id -- even ones
     * that reuse a tool part id across a retry -- so the next task list always gets an id
     * different from this one and reopens the bar instead of staying suppressed for the rest of
     * the session.
     */
    val dismissedTodoBarId: String? = null,
    /**
     * Set while the running turn has gone quiet for longer than [STALL_THRESHOLD_MS], carrying the
     * best available answer to "is this still working, and if not, why not".
     */
    val stall: StallDiagnosis? = null,
    /**
     * Set while the runtime is waiting out a failing provider call before trying it again, and
     * cleared by the first output of the run or its end. Without it a rate-limited or overloaded
     * model looks exactly like a slow one: the chat sits on "Thinking" for as long as the retries
     * last, which has no upper bound.
     */
    val retry: RunRetry? = null,
    /**
     * What the running turn is doing that is not output: compacting the conversation, or answering
     * with a fallback model. Cleared at the end of the run; compacting also when it ends or output starts.
     */
    val runActivity: RunActivity? = null,
    /** True when the agent can say how its context window is split, so the context meter opens that. */
    val contextBreakdownAvailable: Boolean = false,
    /** The context window sheet the meter opens: hidden, being asked for, shown, or not available now. */
    val contextBreakdown: ContextBreakdownState = ContextBreakdownState.Hidden,
    /** True when the agent compacts a chat on request, so the context window sheet offers it. */
    val compactAvailable: Boolean = false,
    val error: String? = null,
    /**
     * Set by [ChatViewModel.editLastUserMessage] to the text of the message it just removed, so the
     * composer can drop it back into the input for editing. One-shot: the screen clears it with
     * [ChatViewModel.consumeEditDraft] once it has copied the text into its own input state, the
     * same handoff [partialText] uses for dictation.
     */
    val editDraft: String? = null,
    /**
     * True when [editDraft] is a held prompt taken back from the queue: it joins what the user is
     * typing instead of replacing it.
     */
    val editDraftJoinsInput: Boolean = false,
    /**
     * Prompts sent while a turn runs and held until it ends (send behaviour "queue", and always for a
     * runtime that cannot interrupt), for this chat only, oldest first.
     */
    val queuedPrompts: List<QueuedPromptView> = emptyList(),
    /** False where the running turn cannot be interrupted (Antigravity), so "Send now" is not offered. */
    val canSendQueuedNow: Boolean = true,
    /** The diff dialog open on a tapped [ChatPart.Patch] card, or null when none is open. */
    val patchDiff: PatchDiffState? = null,
    /** The artifact open in the preview, or null when none is. */
    val artifactPreview: ArtifactPreviewState? = null,
    /**
     * The message the chat is rewound to, set by [ChatViewModel.rewindTo]: that message and every
     * later one are hidden, and the files are as they were before it, until [ChatViewModel.undoRewind]
     * or the next message (which makes the rewind permanent).
     */
    val rewoundFrom: String? = null,
    /** True while a rewind or its undo is on its way, so the controls wait for it. */
    val isRewinding: Boolean = false,
    /** Files put back from a turn snapshot, with the token that undoes it; see [TurnSnapshots]. */
    val fileRestore: FileRestore? = null,
    val isRestoringFiles: Boolean = false,
) {
    /** The transcript as the chat shows it: everything before [rewoundFrom]. */
    val visibleMessages: List<ChatMessage>
        get() = rewoundFrom?.let { id -> messages.takeWhile { it.id != id } } ?: messages
}
